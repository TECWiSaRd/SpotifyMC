package com.spotifymc.exaroton;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.spotifymc.ClientConfig;
import com.spotifymc.SpotifyMC;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Starts your exaroton server when Minecraft launches, so it's (nearly) ready by the time you
 * reach the server list. Also adds /exaroton start|stop|restart|cmd|status|servers|token.
 *
 * <p>The API token can control your whole exaroton account, so it lives in its own file
 * (.minecraft/spotifymc/exaroton-token.txt), not in the config folder that modpack exports include.
 */
public final class ExarotonLauncher {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);
    private static final long POLL_MS = 5000;
    private static final long WATCH_LIMIT_MS = 10 * 60 * 1000;

    /** Only one start/watch at a time. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    // ---- launch ----

    /** Called from the client mod constructor, before the title screen. Never blocks. */
    public static void onGameLaunch() {
        if (!ClientConfig.EXAROTON_AUTO_START.get() || readToken() == null) return;
        background(() -> startAndWatch(false));
    }

    // ---- commands ----

    public static void registerCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("exaroton")
                .then(Commands.literal("start").executes(ctx -> {
                    if (!requireToken(ctx)) return 0;
                    background(() -> startAndWatch(true));
                    return 1;
                }))
                .then(Commands.literal("stop").executes(ctx -> {
                    if (!requireToken(ctx)) return 0;
                    background(ExarotonLauncher::stop);
                    return 1;
                }))
                .then(Commands.literal("restart").executes(ctx -> {
                    if (!requireToken(ctx)) return 0;
                    background(ExarotonLauncher::restart);
                    return 1;
                }))
                .then(Commands.literal("cmd")
                        .then(Commands.argument("command", StringArgumentType.greedyString()).executes(ctx -> {
                            if (!requireToken(ctx)) return 0;
                            String command = StringArgumentType.getString(ctx, "command").trim().replaceFirst("^/", "");
                            background(() -> sendCommand(command));
                            return 1;
                        })))
                .then(Commands.literal("status").executes(ctx -> {
                    if (!requireToken(ctx)) return 0;
                    background(ExarotonLauncher::status);
                    return 1;
                }))
                .then(Commands.literal("servers").executes(ctx -> {
                    if (!requireToken(ctx)) return 0;
                    background(ExarotonLauncher::listServers);
                    return 1;
                }))
                .then(Commands.literal("token")
                        .then(Commands.literal("clear").executes(ctx -> {
                            writeToken(null);
                            return reply(ctx, "exaroton token removed.");
                        }))
                        .then(Commands.argument("token", StringArgumentType.greedyString()).executes(ctx -> {
                            String token = StringArgumentType.getString(ctx, "token").trim();
                            if (!writeToken(token)) {
                                ctx.getSource().sendFailure(Component.literal("Couldn't save the token, see the log."));
                                return 0;
                            }
                            reply(ctx, "exaroton token saved. Checking it…");
                            background(ExarotonLauncher::listServers);
                            return 1;
                        }))));
    }

    private static boolean requireToken(CommandContext<CommandSourceStack> ctx) {
        if (readToken() != null) return true;
        ctx.getSource().sendFailure(Component.literal("Set your exaroton API token first: /exaroton token <token> "
                + "(create one at exaroton.com/account)"));
        return false;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal("⛁ " + msg).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // ---- actions (background thread) ----

    private static void startAndWatch(boolean fromCommand) {
        if (!BUSY.compareAndSet(false, true)) {
            notify("Already starting the server, hang on.", false);
            return;
        }
        try {
            String token = readToken();
            ExarotonApi.Server s = pickServer(token);
            if (s.status() == ExarotonApi.ONLINE) {
                if (fromCommand) notify(s.name() + " is already online: " + s.address() + playersSuffix(s), false);
                return;
            }
            if (s.status() == ExarotonApi.OFFLINE || s.status() == ExarotonApi.CRASHED) {
                ExarotonApi.start(token, s.id());
                notify("Starting " + s.name() + "…", false);
            } else {
                notify(s.name() + " is " + s.statusName() + "…", false);
            }
            watchUntilOnline(token, s.id(), false);
        } catch (IOException e) {
            notify("Couldn't start the server: " + e.getMessage(), true);
        } finally {
            BUSY.set(false);
        }
    }

    /**
     * Polls until the server is online. For a restart, exaroton may still report ONLINE for a
     * moment after the request, so {@code mustGoDownFirst} ignores ONLINE until it has seen
     * another status (or a minute has passed).
     */
    private static void watchUntilOnline(String token, String id, boolean mustGoDownFirst) throws IOException {
        long start = System.currentTimeMillis();
        long deadline = start + WATCH_LIMIT_MS;
        int lastStatus = -1;
        boolean wentDown = !mustGoDownFirst;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MS);
            ExarotonApi.Server s = ExarotonApi.get(token, id);
            if (s.status() != ExarotonApi.ONLINE) wentDown = true;
            if (s.status() == ExarotonApi.ONLINE && (wentDown || System.currentTimeMillis() - start > 60_000)) {
                notify(s.name() + " is online! " + s.address() + playersSuffix(s), false);
                return;
            }
            if (s.status() == ExarotonApi.OFFLINE || s.status() == ExarotonApi.CRASHED) {
                notify(s.name() + " didn't start (" + s.statusName() + "). Check the exaroton console.", true);
                return;
            }
            if (s.status() != lastStatus) SpotifyMC.LOGGER.info("exaroton: {} is {}", s.name(), s.statusName());
            lastStatus = s.status();
        }
        notify("Still waiting for the server after 10 minutes. Check the exaroton website.", true);
    }

    private static void stop() {
        try {
            String token = readToken();
            ExarotonApi.Server s = pickServer(token);
            if (s.status() == ExarotonApi.OFFLINE) {
                notify(s.name() + " is already offline.", false);
                return;
            }
            ExarotonApi.stop(token, s.id());
            notify("Stopping " + s.name() + ".", false);
        } catch (IOException e) {
            notify("Couldn't stop the server: " + e.getMessage(), true);
        }
    }

    private static void restart() {
        if (!BUSY.compareAndSet(false, true)) {
            notify("Already starting the server, hang on.", false);
            return;
        }
        try {
            String token = readToken();
            ExarotonApi.Server s = pickServer(token);
            if (s.status() != ExarotonApi.ONLINE) {
                // Nothing to restart; a plain start does the job.
                BUSY.set(false);
                startAndWatch(true);
                return;
            }
            ExarotonApi.restart(token, s.id());
            notify("Restarting " + s.name() + ". Everyone is disconnected for about a minute.", false);
            watchUntilOnline(token, s.id(), true);
        } catch (IOException e) {
            notify("Couldn't restart the server: " + e.getMessage(), true);
        } finally {
            BUSY.set(false);
        }
    }

    private static void sendCommand(String command) {
        if (command.isEmpty()) return;
        try {
            String token = readToken();
            ExarotonApi.Server s = pickServer(token);
            if (s.status() != ExarotonApi.ONLINE) {
                notify(s.name() + " is " + s.statusName() + "; commands only work while it's online.", true);
                return;
            }
            ExarotonApi.command(token, s.id(), command);
            // The API doesn't return the console output, so we can only confirm it was sent.
            chat("Sent to the server console: " + command, ChatFormatting.GREEN);
        } catch (IOException e) {
            notify("Couldn't send the command: " + e.getMessage(), true);
        }
    }

    /** ", 2/20 players: Alex, Sam" for online servers, "" otherwise. */
    private static String playersSuffix(ExarotonApi.Server s) {
        if (s.status() != ExarotonApi.ONLINE) return "";
        String names = s.playerNames().isEmpty() ? "" : ": " + String.join(", ", s.playerNames());
        return " · " + s.players() + "/" + s.maxPlayers() + " players" + names;
    }

    private static void status() {
        try {
            ExarotonApi.Server s = pickServer(readToken());
            notify(s.name() + " (" + s.address() + ") is " + s.statusName() + playersSuffix(s) + ".", false);
        } catch (IOException e) {
            notify("Couldn't get the server status: " + e.getMessage(), true);
        }
    }

    private static void listServers() {
        try {
            List<ExarotonApi.Server> servers = ExarotonApi.list(readToken());
            if (servers.isEmpty()) {
                notify("Token works, but this account has no servers.", true);
                return;
            }
            StringBuilder sb = new StringBuilder("Your exaroton servers:");
            for (ExarotonApi.Server s : servers) {
                sb.append("\n • ").append(s.name()).append(" (").append(s.address()).append(") ").append(s.statusName()).append(playersSuffix(s));
            }
            if (servers.size() > 1 && ClientConfig.EXAROTON_SERVER.get().isBlank()) {
                sb.append("\nYou have several, so set which one to use: Mods → SpotifyMC → Config → exaroton → Server.");
            }
            chat(sb.toString(), ChatFormatting.GREEN);
        } catch (IOException e) {
            notify(e.getMessage(), true);
        }
    }

    /** The configured server (by name, address or id), or the only server on the account. */
    private static ExarotonApi.Server pickServer(String token) throws IOException {
        List<ExarotonApi.Server> servers = ExarotonApi.list(token);
        String wanted = ClientConfig.EXAROTON_SERVER.get().trim().toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty()) {
            for (ExarotonApi.Server s : servers) {
                if (s.id().equalsIgnoreCase(wanted) || s.name().toLowerCase(Locale.ROOT).equals(wanted)
                        || s.address().toLowerCase(Locale.ROOT).equals(wanted)) return s;
            }
            throw new IOException("No server named \"" + ClientConfig.EXAROTON_SERVER.get() + "\" on this account.");
        }
        if (servers.size() == 1) return servers.get(0);
        if (servers.isEmpty()) throw new IOException("This exaroton account has no servers.");
        throw new IOException("You have " + servers.size() + " servers; choose one in the config (exaroton → Server).");
    }

    // ---- token storage ----

    private static Path tokenFile() {
        return FMLPaths.GAMEDIR.get().resolve("spotifymc").resolve("exaroton-token.txt");
    }

    private static String readToken() {
        try {
            Path f = tokenFile();
            if (!Files.isRegularFile(f)) return null;
            String t = Files.readString(f, StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? null : t;
        } catch (IOException e) {
            SpotifyMC.LOGGER.warn("Couldn't read the exaroton token", e);
            return null;
        }
    }

    private static boolean writeToken(String token) {
        try {
            Path f = tokenFile();
            if (token == null || token.isEmpty()) {
                Files.deleteIfExists(f);
                return true;
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, token, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the file stays in the user's own profile folder
            }
            return true;
        } catch (IOException e) {
            SpotifyMC.LOGGER.warn("Couldn't save the exaroton token", e);
            return false;
        }
    }

    // ---- output ----

    /** Shows a toast (works on the title screen too) and, when in a world, a chat line. */
    private static void notify(String message, boolean error) {
        if (error) SpotifyMC.LOGGER.warn("exaroton: {}", message);
        else SpotifyMC.LOGGER.info("exaroton: {}", message);
        onMainWhenReady(() -> {
            Minecraft mc = Minecraft.getInstance();
            SystemToast.addOrUpdate(mc.getToasts(), TOAST, Component.literal("exaroton"), Component.literal(message));
            if (mc.player != null) {
                mc.gui.getChat().addMessage(Component.literal("⛁ " + message)
                        .withStyle(error ? ChatFormatting.RED : ChatFormatting.GREEN));
            }
        });
    }

    private static void chat(String message, ChatFormatting color) {
        onMainWhenReady(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("⛁ " + message).withStyle(color));
        });
    }

    /** At launch the game window may not exist yet; wait for it (up to a minute) before showing anything. */
    private static void onMainWhenReady(Runnable r) {
        for (int i = 0; i < 120 && Minecraft.getInstance() == null; i++) sleep(500);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(r);
    }

    private static void background(Runnable r) {
        Thread t = new Thread(r, "SpotifyMC exaroton");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ExarotonLauncher() {}
}
