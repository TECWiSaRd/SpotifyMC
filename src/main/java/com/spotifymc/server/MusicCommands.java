package com.spotifymc.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.spotifymc.ServerConfig;
import com.spotifymc.SpotifyMC;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class MusicCommands {
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("music")
                .then(Commands.literal("play").requires(MusicCommands::canControl)
                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                .executes(MusicCommands::play)))
                .then(Commands.literal("radio").requires(MusicCommands::canControl)
                        .then(Commands.literal("off").executes(ctx -> reply(ctx, MusicServer.stopRadio(),
                                "Radio off. Songs already playing finish normally.", "Radio isn't on.")))
                        .then(Commands.argument("song", StringArgumentType.greedyString())
                                .executes(MusicCommands::radio)))
                .then(Commands.literal("skip").requires(MusicCommands::canControl)
                        .executes(ctx -> reply(ctx, MusicServer.skip(server(ctx)), "Skipped.", "Nothing is playing.")))
                .then(Commands.literal("stop").requires(MusicCommands::canControl)
                        .executes(ctx -> {
                            MusicServer.stop(server(ctx));
                            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("♪ Music stopped by " + ctx.getSource().getTextName())
                                            .withStyle(ChatFormatting.YELLOW), false);
                            return 1;
                        }))
                .then(Commands.literal("pause").requires(MusicCommands::canControl)
                        .executes(ctx -> reply(ctx, MusicServer.pause(server(ctx)), "Paused.", "Nothing to pause.")))
                .then(Commands.literal("resume").requires(MusicCommands::canControl)
                        .executes(ctx -> reply(ctx, MusicServer.resume(server(ctx)), "Resumed.", "Nothing to resume.")))
                .then(Commands.literal("shuffle").requires(MusicCommands::canControl)
                        .executes(ctx -> {
                            MusicServer.shuffle();
                            return reply(ctx, true, "Queue shuffled.", "");
                        }))
                .then(Commands.literal("queue").executes(MusicCommands::queue))
                .then(Commands.literal("nowplaying").executes(MusicCommands::nowPlaying))
                .then(Commands.literal("np").executes(MusicCommands::nowPlaying)));
    }

    private static boolean canControl(CommandSourceStack src) {
        return src.hasPermission(ServerConfig.controlLevel());
    }

    private static MinecraftServer server(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getServer();
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, boolean ok, String yes, String no) {
        if (ok) ctx.getSource().sendSuccess(() -> Component.literal("♪ " + yes).withStyle(ChatFormatting.GREEN), true);
        else ctx.getSource().sendFailure(Component.literal(no));
        return ok ? 1 : 0;
    }

    private static int play(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "query");
        MinecraftServer server = src.getServer();
        int max = ServerConfig.MAX_PLAYLIST_TRACKS.get();
        src.sendSuccess(() -> Component.literal("♪ Looking up " + input + "…").withStyle(ChatFormatting.GRAY), false);

        // Network lookups happen off the server thread; results are applied back on it.
        CompletableFuture.supplyAsync(() -> {
            try {
                return TrackResolver.resolve(input, max);
            } catch (TrackResolver.ResolveException e) {
                throw new RuntimeException(e.getMessage());
            }
        }).whenComplete((requests, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                SpotifyMC.LOGGER.warn("Couldn't resolve '{}'", input, cause);
                src.sendFailure(Component.literal(String.valueOf(cause.getMessage())));
                return;
            }
            boolean wasIdle = MusicServer.current() == null;
            int added = MusicServer.enqueue(server, requests, src.getTextName());
            if (added == 0) {
                src.sendFailure(Component.literal("The queue is full."));
            } else if (requests.size() > 1) {
                src.sendSuccess(() -> Component.literal("♪ Added " + added + " tracks to the queue.")
                        .withStyle(ChatFormatting.GREEN), true);
            } else if (!wasIdle) {
                src.sendSuccess(() -> Component.literal("♪ Queued: ").withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(requests.get(0).title()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (#" + MusicServer.queue().size() + ")").withStyle(ChatFormatting.GRAY)), true);
            }
        }));
        return 1;
    }

    private static int radio(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "song");
        MinecraftServer server = src.getServer();
        int max = ServerConfig.MAX_PLAYLIST_TRACKS.get();
        src.sendSuccess(() -> Component.literal("♪ Starting radio from " + input + "…").withStyle(ChatFormatting.GRAY), false);

        CompletableFuture.supplyAsync(() -> {
            try {
                return TrackResolver.resolveRadio(input, max);
            } catch (TrackResolver.ResolveException e) {
                throw new RuntimeException(e.getMessage());
            }
        }).whenComplete((mix, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                SpotifyMC.LOGGER.warn("Couldn't start radio from '{}'", input, cause);
                src.sendFailure(Component.literal(String.valueOf(cause.getMessage())));
                return;
            }
            int added = MusicServer.startRadio(server, mix, src.getTextName());
            if (added == 0) {
                src.sendFailure(Component.literal("The queue is full."));
                return;
            }
            server.getPlayerList().broadcastSystemMessage(Component.literal("♪ " + src.getTextName()
                    + " started a radio based on ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(mix.get(0).title()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(". It keeps going until /music radio off. Songs from /music play go first.")
                            .withStyle(ChatFormatting.GRAY)), false);
        }));
        return 1;
    }

    private static int nowPlaying(CommandContext<CommandSourceStack> ctx) {
        Track t = MusicServer.current();
        if (t == null) {
            ctx.getSource().sendFailure(Component.literal("Nothing is playing."));
            return 0;
        }
        String progress = MusicServer.formatTime(MusicServer.elapsedMs())
                + (t.durationMs > 0 ? " / " + MusicServer.formatTime(t.durationMs) : "")
                + (MusicServer.isPaused() ? " (paused)" : "");
        ctx.getSource().sendSuccess(() -> Component.literal("♪ ").withStyle(ChatFormatting.GREEN)
                .append(MusicServer.describe(t).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  " + progress + " · requested by " + t.requestedBy).withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private static int queue(CommandContext<CommandSourceStack> ctx) {
        Track now = MusicServer.current();
        List<Track> q = MusicServer.queue();
        if (now == null && q.isEmpty() && !MusicServer.isRadioOn()) {
            ctx.getSource().sendFailure(Component.literal("The queue is empty."));
            return 0;
        }
        MutableComponent msg = Component.literal("♪ Queue" + (MusicServer.isRadioOn() ? " (radio on)" : "")).withStyle(ChatFormatting.GREEN);
        if (now != null) msg.append(Component.literal("\nNow: ").withStyle(ChatFormatting.YELLOW))
                .append(MusicServer.describe(now).withStyle(ChatFormatting.WHITE));
        int shown = Math.min(q.size(), 10);
        for (int i = 0; i < shown; i++) {
            msg.append(Component.literal("\n" + (i + 1) + ". ").withStyle(ChatFormatting.GRAY))
                    .append(MusicServer.describe(q.get(i)).withStyle(ChatFormatting.WHITE));
        }
        if (q.size() > shown) msg.append(Component.literal("\n…and " + (q.size() - shown) + " more").withStyle(ChatFormatting.GRAY));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private MusicCommands() {}
}
