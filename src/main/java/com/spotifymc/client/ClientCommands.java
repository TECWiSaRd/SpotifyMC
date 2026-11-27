package com.spotifymc.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.spotifymc.ClientConfig;
import com.spotifymc.audio.Tools;
import com.spotifymc.network.PlayTrackPayload;
import com.spotifymc.spotify.SpotifyAuth;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Per-player settings: /musicclient ... (these only affect your own client). */
public final class ClientCommands {
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("musicclient")
                .then(Commands.literal("volume")
                        .executes(ctx -> say(ctx, "Volume: " + ClientConfig.VOLUME.get() + "%"))
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 200))
                                .executes(ctx -> {
                                    int v = IntegerArgumentType.getInteger(ctx, "percent");
                                    ClientConfig.VOLUME.set(v);
                                    ClientConfig.VOLUME.save();
                                    return say(ctx, "Volume set to " + v + "%");
                                })))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx, false)))
                .then(Commands.literal("resync").executes(ctx -> {
                    ClientMusic.resync();
                    return say(ctx, "Resynced with the server.");
                }))
                .then(Commands.literal("status").executes(ClientCommands::status))
                .then(Commands.literal("spotify")
                        .then(Commands.literal("link").executes(ClientCommands::spotifyLink))
                        .then(Commands.literal("unlink").executes(ctx -> {
                            SpotifyAuth.unlink();
                            return say(ctx, "Spotify account unlinked.");
                        }))));
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean on) {
        ClientConfig.ENABLED.set(on);
        ClientConfig.ENABLED.save();
        // The client tick handler notices the change and starts/stops playback.
        return say(ctx, on ? "Server music on." : "Server music off (just for you).");
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        PlayTrackPayload s = ClientMusic.serverState();
        String now = s == null ? "nothing" : s.title() + (s.paused() ? " (paused)" : "");
        String spotify = SpotifyAuth.isLinked() ? "linked" : ClientConfig.SPOTIFY_CLIENT_ID.get().isBlank()
                ? "not set up (no client ID)" : "not linked, use /musicclient spotify link";
        return say(ctx, "Now playing: " + now
                + "\nMusic: " + (ClientConfig.ENABLED.get() ? "on" : "off") + ", volume " + ClientConfig.VOLUME.get() + "%"
                + "\nSpotify: " + spotify
                + "\n" + Tools.describe());
    }

    private static int spotifyLink(CommandContext<CommandSourceStack> ctx) {
        var result = SpotifyAuth.beginLogin(message -> Minecraft.getInstance().execute(() -> {
            boolean ok = SpotifyAuth.isLinked();
            ClientMusic.chat(message, ok ? ChatFormatting.GREEN : ChatFormatting.RED);
            if (ok) ClientMusic.resync();
        }));
        return result.map(url -> {
            Util.getPlatform().openUri(url);
            ctx.getSource().sendSuccess(() -> Component.literal("♪ Opened Spotify login in your browser. ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("[Click here if it didn't open]").withStyle(s -> s
                            .withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))))), false);
            return 1;
        }, error -> {
            ctx.getSource().sendFailure(Component.literal(error));
            return 0;
        });
    }

    private static int say(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("♪ " + message).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private ClientCommands() {}
}
