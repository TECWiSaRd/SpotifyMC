package com.spotifymc.server;

import com.spotifymc.ServerConfig;
import com.spotifymc.network.PlayTrackPayload;
import com.spotifymc.network.StopPayload;
import com.spotifymc.network.TrackInfoPayload;
import com.spotifymc.SpotifyMC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SequencedSet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The shared jukebox. All state lives on the server thread; the server is the clock that every
 * client syncs to. Clients are told how far into the track playback is (not a wall-clock time),
 * so clock differences between machines don't matter.
 */
public final class MusicServer {
    /** Grace period after a track's nominal end before moving on, so slower clients can finish. */
    private static final long END_GRACE_MS = 1500;

    /** Radio tops the queue up when it gets this short. */
    private static final int RADIO_LOW_WATER = 3;
    private static final int RADIO_HISTORY = 500;

    private static final List<Track> QUEUE = new ArrayList<>();
    private static Track current;
    private static long startNanos;        // System.nanoTime() at which elapsed was 0
    private static long pausedAtMs = -1;   // >= 0 while paused
    private static long nextId = 1;

    // Radio: endless YouTube Mix. Tracks it adds are marked fromRadio and stay behind requested songs.
    private static boolean radioOn;
    private static String radioStartedBy;
    private static boolean radioRefilling;
    private static final SequencedSet<String> RADIO_SEEN = new LinkedHashSet<>(); // video ids already queued
    private static final Set<String> RADIO_TRIED_SEEDS = new HashSet<>();
    private static int radioEmptyRefills;

    // ---- queue operations (server thread only) ----

    /**
     * Adds requested tracks after other requested tracks but before any radio tracks, so people
     * don't have to wait for the radio to run out.
     * @return how many tracks were actually added (the queue has a size limit).
     */
    public static int enqueue(MinecraftServer server, List<TrackResolver.Request> requests, String requestedBy) {
        return add(server, requests, requestedBy, false);
    }

    /** Starts radio mode with the given Mix: queues it, and keeps the queue topped up from then on. */
    public static int startRadio(MinecraftServer server, List<TrackResolver.Request> mix, String requestedBy) {
        QUEUE.removeIf(t -> t.fromRadio); // replace any previous radio
        RADIO_SEEN.clear();
        RADIO_TRIED_SEEDS.clear();
        radioOn = true;
        radioStartedBy = requestedBy;
        return add(server, mix, requestedBy, true);
    }

    public static boolean stopRadio() {
        if (!radioOn) return false;
        radioOn = false;
        QUEUE.removeIf(t -> t.fromRadio);
        return true;
    }

    public static boolean isRadioOn() {
        return radioOn;
    }

    private static int add(MinecraftServer server, List<TrackResolver.Request> requests, String requestedBy, boolean radio) {
        int room = ServerConfig.MAX_QUEUE_LENGTH.get() - QUEUE.size();
        int insertAt = radio ? QUEUE.size() : firstRadioIndex();
        int added = 0;
        for (TrackResolver.Request r : requests) {
            if (added >= room) break;
            String videoId = TrackResolver.videoId(r.query());
            if (radio && videoId != null && !RADIO_SEEN.add(videoId)) continue; // already played/queued
            Track t = new Track(nextId++, r, requestedBy);
            t.fromRadio = radio;
            t.videoId = videoId;
            QUEUE.add(insertAt++, t);
            added++;
        }
        while (RADIO_SEEN.size() > RADIO_HISTORY) RADIO_SEEN.removeFirst();
        if (current == null && added > 0) advance(server);
        return added;
    }

    private static int firstRadioIndex() {
        for (int i = 0; i < QUEUE.size(); i++) if (QUEUE.get(i).fromRadio) return i;
        return QUEUE.size();
    }

    /** Fetches the Mix of the most recent radio song and appends the songs we haven't had yet. */
    private static void refillRadio(MinecraftServer server) {
        if (!radioOn || radioRefilling) return;
        String seed = pickRadioSeed();
        if (seed == null) {
            radioOn = false;
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "♪ Radio stopped: couldn't find more songs.").withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        RADIO_TRIED_SEEDS.add(seed);
        radioRefilling = true;
        int max = ServerConfig.MAX_PLAYLIST_TRACKS.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                return YouTube.mix(seed, max).stream().map(YouTube.Video::toRequest).toList();
            } catch (TrackResolver.ResolveException e) {
                throw new RuntimeException(e.getMessage());
            }
        }).whenComplete((mix, error) -> server.execute(() -> {
            radioRefilling = false;
            if (!radioOn) return;
            if (error != null) SpotifyMC.LOGGER.warn("Radio refill from {} failed", seed, error);
            int added = error == null ? add(server, mix, radioStartedBy, true) : 0;
            // That Mix had nothing new (or failed): try another seed, up to a few times per refill.
            if (added == 0 && ++radioEmptyRefills < 4) refillRadio(server);
            else radioEmptyRefills = 0;
        }));
    }

    /** Newest radio song we haven't already used as a seed. */
    private static String pickRadioSeed() {
        for (int i = QUEUE.size() - 1; i >= 0; i--) {
            String id = QUEUE.get(i).videoId;
            if (QUEUE.get(i).fromRadio && id != null && !RADIO_TRIED_SEEDS.contains(id)) return id;
        }
        if (current != null && current.videoId != null && !RADIO_TRIED_SEEDS.contains(current.videoId)) return current.videoId;
        for (String id : RADIO_SEEN.reversed()) if (!RADIO_TRIED_SEEDS.contains(id)) return id;
        return null;
    }

    public static boolean skip(MinecraftServer server) {
        if (current == null) return false;
        advance(server);
        return true;
    }

    public static void stop(MinecraftServer server) {
        radioOn = false;
        QUEUE.clear();
        current = null;
        pausedAtMs = -1;
        broadcast(server, StopPayload.INSTANCE);
    }

    public static boolean pause(MinecraftServer server) {
        if (current == null || pausedAtMs >= 0) return false;
        pausedAtMs = elapsedMs();
        broadcast(server, playPayload());
        return true;
    }

    public static boolean resume(MinecraftServer server) {
        if (current == null || pausedAtMs < 0) return false;
        startNanos = System.nanoTime() - pausedAtMs * 1_000_000L;
        pausedAtMs = -1;
        broadcast(server, playPayload());
        return true;
    }

    public static void shuffle() {
        List<Track> list = new ArrayList<>(QUEUE);
        Collections.shuffle(list);
        QUEUE.clear();
        QUEUE.addAll(list);
        // Keep requested songs ahead of radio songs.
        QUEUE.sort((a, b) -> Boolean.compare(a.fromRadio, b.fromRadio));
    }

    public static Track current() {
        return current;
    }

    public static List<Track> queue() {
        return List.copyOf(QUEUE);
    }

    public static boolean isPaused() {
        return pausedAtMs >= 0;
    }

    public static long elapsedMs() {
        if (pausedAtMs >= 0) return pausedAtMs;
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void advance(MinecraftServer server) {
        current = QUEUE.isEmpty() ? null : QUEUE.remove(0);
        pausedAtMs = -1;
        if (radioOn && QUEUE.size() < RADIO_LOW_WATER) refillRadio(server);
        if (current == null) {
            broadcast(server, StopPayload.INSTANCE);
            return;
        }
        startNanos = System.nanoTime();
        broadcast(server, playPayload());
        server.getPlayerList().broadcastSystemMessage(Component.literal("♪ Now playing: ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(current.title).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(current.fromRadio ? " (radio)" : " (requested by " + current.requestedBy + ")")
                        .withStyle(ChatFormatting.GRAY)), false);
    }

    private static PlayTrackPayload playPayload() {
        Track t = current;
        return new PlayTrackPayload(t.id, t.query, t.title, t.spotifyUri, elapsedMs(),
                Math.max(t.durationMs, 0), pausedAtMs >= 0);
    }

    private static void broadcast(MinecraftServer server, CustomPacketPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p, payload);
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    // ---- events ----

    public static void onTrackInfo(ServerPlayer from, TrackInfoPayload info) {
        Track t = current;
        if (t == null || t.id != info.trackId()) return;
        if (!t.titleKnown && !info.title().isBlank()) {
            t.title = info.title().strip();
            t.titleKnown = true;
        }
        // Ignore nonsense so a modified client can't skip tracks instantly.
        if (info.durationMs() == 0) {
            t.playingSomewhere = true; // started, length not known yet
        } else if (t.durationMs <= 0 && !t.live) {
            if (info.durationMs() < 0) {
                t.live = true;
            } else if (info.durationMs() >= 1000 && info.durationMs() <= 24L * 3600 * 1000) {
                t.durationMs = info.durationMs();
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Track t = current;
        if (t == null || pausedAtMs >= 0 || t.live) return;
        long elapsed = elapsedMs();
        MinecraftServer server = event.getServer();
        if (t.durationMs > 0) {
            if (elapsed >= t.durationMs + END_GRACE_MS) advance(server);
        } else if (!t.playingSomewhere && elapsed >= ServerConfig.LOAD_TIMEOUT_SECONDS.get() * 1000L) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("♪ Couldn't load ")
                    .withStyle(ChatFormatting.RED).append(t.title).append(", skipping."), false);
            advance(server);
        }
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (current != null && event.getEntity() instanceof ServerPlayer player) {
            send(player, playPayload());
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        radioOn = false;
        radioRefilling = false;
        RADIO_SEEN.clear();
        RADIO_TRIED_SEEDS.clear();
        QUEUE.clear();
        current = null;
        pausedAtMs = -1;
    }

    public static MutableComponent describe(Track t) {
        MutableComponent c = Component.literal(t.title);
        if (t.durationMs > 0) c.append(Component.literal(" [" + formatTime(t.durationMs) + "]").withStyle(ChatFormatting.GRAY));
        if (!t.spotifyUri.isEmpty()) c.append(Component.literal(" (Spotify)").withStyle(ChatFormatting.DARK_GREEN));
        if (t.fromRadio) c.append(Component.literal(" (radio)").withStyle(ChatFormatting.DARK_AQUA));
        return c;
    }

    public static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    private MusicServer() {}
}
