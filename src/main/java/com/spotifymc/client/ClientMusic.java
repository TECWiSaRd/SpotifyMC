package com.spotifymc.client;

import com.spotifymc.ClientConfig;
import com.spotifymc.audio.AudioStreamPlayer;
import com.spotifymc.network.PlayTrackPayload;
import com.spotifymc.network.TrackInfoPayload;
import com.spotifymc.spotify.SpotifyAuth;
import com.spotifymc.spotify.SpotifyPlayer;
import java.net.URI;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side state machine. Mirrors what the server says is playing and decides how to play
 * it here: through the player's linked Spotify app, or in-game via yt-dlp + ffmpeg.
 * All methods run on the client main thread.
 */
public final class ClientMusic {
    private static PlayTrackPayload serverState;      // last state the server sent, null = nothing
    private static volatile long localStartNanos;     // System.nanoTime() at which the track was at 0 ms
    private static AudioStreamPlayer player;
    private static boolean usingSpotify;
    private static long activeTrackId = -1;
    private static boolean wasEnabled = true;

    // ---- packets ----

    public static void onPlay(PlayTrackPayload p) {
        serverState = p;
        localStartNanos = System.nanoTime() - p.elapsedMs() * 1_000_000L;
        if (p.paused() || !ClientConfig.ENABLED.get()) {
            stopLocal();
            return;
        }
        if (p.trackId() == activeTrackId && isActive()) return; // already playing this one
        stopLocal();
        startLocal(p);
    }

    public static void onStop() {
        serverState = null;
        stopLocal();
    }

    /** Where playback should be right now, per the server. Safe to call from any thread. */
    public static long positionMs() {
        return (System.nanoTime() - localStartNanos) / 1_000_000L;
    }

    public static boolean isActive() {
        return usingSpotify || (player != null && !player.isStopped());
    }

    public static PlayTrackPayload serverState() {
        return serverState;
    }

    /** Re-applies the server state, e.g. after the player turns music back on. */
    public static void resync() {
        PlayTrackPayload p = serverState;
        stopLocal();
        if (p != null) {
            onPlay(new PlayTrackPayload(p.trackId(), p.query(), p.title(), p.spotifyUri(),
                    positionMs(), p.durationMs(), p.paused()));
        }
    }

    // ---- playback ----

    private static void startLocal(PlayTrackPayload p) {
        activeTrackId = p.trackId();
        if (!p.spotifyUri().isEmpty() && ClientConfig.PREFER_SPOTIFY_APP.get() && SpotifyAuth.isLinked()) {
            usingSpotify = true;
            long id = p.trackId();
            SpotifyPlayer.play(p.spotifyUri(), ClientMusic::positionMs).whenComplete((ok, err) -> {
                if (err == null) return;
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                Minecraft.getInstance().execute(() -> {
                    if (activeTrackId != id || !usingSpotify) return;
                    usingSpotify = false;
                    chat(cause.getMessage() + " Playing in-game instead.", ChatFormatting.YELLOW);
                    startInGame(p);
                });
            });
        } else {
            startInGame(p);
        }
    }

    private static void startInGame(PlayTrackPayload p) {
        if (!isAllowedSource(p.query())) {
            chat("Not playing " + p.title() + ": its site isn't in your allowedHosts client config.", ChatFormatting.YELLOW);
            return;
        }
        long id = p.trackId();
        boolean durationKnown = p.durationMs() > 0;
        AudioStreamPlayer[] self = new AudioStreamPlayer[1];
        self[0] = new AudioStreamPlayer(p.query(), ClientMusic::positionMs, new AudioStreamPlayer.Listener() {
            private long reportedDuration;

            @Override
            public void onStarted(String title, long durationMs) {
                reportedDuration = durationMs;
                // Tells the server the track loaded (cancels its timeout) and, if it can, how long it is.
                onMain(() -> sendInfo(id, title, durationMs));
            }

            @Override
            public void onFinished(long decodedMs) {
                // Unknown length up front: report what we actually played so the queue can move on.
                if (!durationKnown && reportedDuration == 0 && decodedMs > 0) {
                    onMain(() -> sendInfo(id, "", decodedMs));
                }
            }

            @Override
            public void onError(String message) {
                onMain(() -> chat(message, ChatFormatting.RED));
            }

            @Override
            public void onStatus(String message) {
                onMain(() -> chat(message, ChatFormatting.GRAY));
            }

            private void onMain(Runnable r) {
                Minecraft.getInstance().execute(() -> {
                    if (player == self[0]) r.run();
                });
            }
        });
        player = self[0];
        player.start();
    }

    private static void stopLocal() {
        if (player != null) {
            player.stop();
            player = null;
        }
        if (usingSpotify) {
            SpotifyPlayer.pause();
            usingSpotify = false;
        }
        activeTrackId = -1;
    }

    private static void sendInfo(long trackId, String title, long durationMs) {
        if (Minecraft.getInstance().getConnection() == null) return;
        String t = title.length() > 500 ? title.substring(0, 500) : title;
        PacketDistributor.sendToServer(new TrackInfoPayload(trackId, t, durationMs));
    }

    /** The server chooses what we stream, so only allow YouTube searches and approved sites. */
    private static boolean isAllowedSource(String query) {
        if (query.startsWith("ytsearch1:")) return true;
        if (!query.startsWith("https://") && !query.startsWith("http://")) return false;
        String host;
        try {
            host = URI.create(query.replace(" ", "%20")).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : ClientConfig.ALLOWED_HOSTS.get()) {
            String a = allowed.trim().toLowerCase(Locale.ROOT);
            if (a.equals("*") || host.equals(a) || host.endsWith("." + a)) return true;
        }
        return false;
    }

    static void chat(String message, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal("♪ " + message).withStyle(color));
        }
    }

    // ---- events ----

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        AudioStreamPlayer.gain = (float) (ClientConfig.VOLUME.get() / 100.0
                * mc.options.getSoundSourceVolume(SoundSource.MASTER)
                * mc.options.getSoundSourceVolume(SoundSource.RECORDS));

        // React to the toggle being flipped in the config screen.
        boolean enabled = ClientConfig.ENABLED.get();
        if (enabled != wasEnabled) {
            wasEnabled = enabled;
            if (enabled) resync();
            else stopLocal();
        }
    }

    /** Keeps Minecraft's own background music quiet while server music is playing. */
    public static void onSelectMusic(SelectMusicEvent event) {
        if (isActive() && ClientConfig.MUTE_VANILLA_MUSIC.get()) {
            event.setMusic(null);
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        serverState = null;
        stopLocal();
    }

    private ClientMusic() {}
}
