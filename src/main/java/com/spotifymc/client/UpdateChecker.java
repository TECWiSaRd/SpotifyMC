package com.spotifymc.client;

import com.spotifymc.ClientConfig;
import com.spotifymc.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client side of updates: when the player joins a world, offers newer releases with an
 * [Install update] button. Nothing is downloaded until they click it.
 */
public final class UpdateChecker {
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ClientConfig.CHECK_FOR_UPDATES.get() || Updater.pendingRestart() != null) return;
        Updater.latest(false).thenAccept(release -> onMain(() -> {
            if (Updater.isNewer(release)) announce(release);
        }));
    }

    /** /musicclient update: re-checks, and installs if there's something newer. */
    public static void installLatest() {
        Updater.Installed done = Updater.pendingRestart();
        if (done != null) {
            ClientMusic.chat("SpotifyMC " + done.release().version() + " is already installed. Restart Minecraft to use it.",
                    ChatFormatting.YELLOW);
            return;
        }
        ClientMusic.chat("Checking for updates…", ChatFormatting.GRAY);
        Updater.latest(true).thenAccept(release -> {
            if (release == null) {
                onMain(() -> ClientMusic.chat("Couldn't reach GitHub to check for updates.", ChatFormatting.RED));
            } else if (!Updater.isNewer(release)) {
                onMain(() -> ClientMusic.chat("You already have the latest version (" + Updater.currentVersion() + ").",
                        ChatFormatting.GREEN));
            } else {
                onMain(() -> ClientMusic.chat("Downloading SpotifyMC " + release.version() + "…", ChatFormatting.GRAY));
                try {
                    Updater.Installed result = Updater.install(release);
                    onMain(() -> {
                        ClientMusic.chat("SpotifyMC " + release.version() + " installed. Restart Minecraft to use it.",
                                ChatFormatting.GREEN);
                        if (result.oldJarNote() != null) ClientMusic.chat(result.oldJarNote(), ChatFormatting.YELLOW);
                    });
                } catch (Exception e) {
                    onMain(() -> ClientMusic.chat("Update failed: " + e.getMessage() + ". You can download it from "
                            + release.pageUrl(), ChatFormatting.RED));
                }
            }
        });
    }

    private static void announce(Updater.Release r) {
        MutableComponent msg = Component.literal("♪ SpotifyMC " + r.version() + " is available (you have "
                + Updater.currentVersion() + "). ").withStyle(ChatFormatting.GOLD);
        msg.append(button("[Install update]", ChatFormatting.GREEN,
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/musicclient update"),
                "Download " + r.jarName() + " into your mods folder.\nTakes effect after restarting Minecraft."));
        msg.append(" ");
        msg.append(button("[What's new]", ChatFormatting.AQUA,
                new ClickEvent(ClickEvent.Action.OPEN_URL, r.pageUrl()), r.pageUrl()));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addMessage(msg);
    }

    private static Component button(String label, ChatFormatting color, ClickEvent click, String hover) {
        return Component.literal(label).withStyle(s -> s.withColor(color).withUnderlined(true)
                .withClickEvent(click).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static void onMain(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private UpdateChecker() {}
}
