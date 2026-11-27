package com.spotifymc.server;

import com.spotifymc.ServerConfig;
import com.spotifymc.SpotifyMC;
import com.spotifymc.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps a dedicated server's copy of the mod current: checks GitHub when the server starts and
 * every 12 hours, installs newer releases into the mods folder, and tells operators that a
 * restart will apply it. (In single-player, the client's [Install update] button covers it.)
 */
public final class ServerUpdater {
    private static final long RECHECK_MS = 12L * 60 * 60 * 1000;
    private static volatile long nextCheckAt = Long.MAX_VALUE;
    private static volatile boolean checking;

    public static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        check(event.getServer());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (System.currentTimeMillis() >= nextCheckAt) check(event.getServer());
    }

    /** Operators who join while an update waits for a restart get a reminder. */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Updater.Installed done = Updater.pendingRestart();
        if (done != null && event.getEntity() instanceof ServerPlayer p && p.hasPermissions(2)) {
            p.sendSystemMessage(restartMessage(done));
        }
    }

    private static void check(MinecraftServer server) {
        nextCheckAt = System.currentTimeMillis() + RECHECK_MS;
        if (checking || Updater.pendingRestart() != null || !ServerConfig.AUTO_UPDATE.get()) return;
        checking = true;
        Updater.latest(true).thenAccept(release -> {
            try {
                if (!Updater.isNewer(release)) return;
                SpotifyMC.LOGGER.info("SpotifyMC {} is available (running {}), installing", release.version(), Updater.currentVersion());
                Updater.Installed done = Updater.install(release);
                if (done.oldJarNote() != null) SpotifyMC.LOGGER.warn("SpotifyMC update: {}", done.oldJarNote());
                server.execute(() -> {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        if (p.hasPermissions(2)) p.sendSystemMessage(restartMessage(done));
                    }
                });
            } catch (Exception e) {
                SpotifyMC.LOGGER.warn("SpotifyMC automatic update failed: {}", e.getMessage());
            } finally {
                checking = false;
            }
        });
    }

    private static Component restartMessage(Updater.Installed done) {
        return Component.literal("♪ SpotifyMC " + done.release().version()
                + " was installed on this server. It takes effect after the next restart. Players should update too.")
                .withStyle(ChatFormatting.GOLD);
    }

    private ServerUpdater() {}
}
