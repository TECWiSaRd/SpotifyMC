package com.spotifymc.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Controls the player's own Spotify app through Spotify Connect (Web API). Spotify doesn't
 * allow third-party apps to receive its audio, so this is the legitimate way to hear an actual
 * Spotify track: Spotify plays it (Premium required) and we keep it in sync with the server.
 */
public final class SpotifyPlayer {
    private static final Pattern TRACK_URI = Pattern.compile("spotify:track:[A-Za-z0-9]{22}");
    private static final String API = "https://api.spotify.com/v1/me/player";

    /** One thread, so play/pause requests reach Spotify in the order they were made. */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpotifyMC Spotify");
        t.setDaemon(true);
        return t;
    });

    /** Completes normally once playback started, exceptionally with a readable message otherwise. */
    public static CompletableFuture<Void> play(String uri, LongSupplier positionMs) {
        return CompletableFuture.runAsync(() -> {
            if (!TRACK_URI.matcher(uri).matches()) throw new IllegalArgumentException("Invalid Spotify track");
            try {
                startPlayback(uri, positionMs, null, false);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, EXEC);
    }

    public static void pause() {
        EXEC.execute(() -> {
            try {
                request("PUT", API + "/pause", null, false);
            } catch (IOException ignored) {
                // already paused / no device: nothing to do
            }
        });
    }

    private static void startPlayback(String uri, LongSupplier positionMs, String deviceId, boolean retried) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray uris = new JsonArray();
        uris.add(uri);
        body.add("uris", uris);
        body.addProperty("position_ms", Math.max(0, positionMs.getAsLong()));
        String url = API + "/play" + (deviceId != null ? "?device_id=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8) : "");

        HttpResponse<String> res = request("PUT", url, body.toString(), false);
        int code = res.statusCode();
        if (code == 200 || code == 202 || code == 204) return;
        if (code == 404 && deviceId == null && !retried) {
            // No active device: pick one the player has open (desktop app, phone, web player...).
            String device = findDevice();
            if (device == null) throw new IOException("Open Spotify on any device first, then try again.");
            startPlayback(uri, positionMs, device, true);
            return;
        }
        if (code == 403) throw new IOException("Spotify refused playback (Premium is required to control playback).");
        throw new IOException("Spotify error " + code + ": " + errorMessage(res.body()));
    }

    private static String findDevice() throws IOException {
        HttpResponse<String> res = request("GET", API + "/devices", null, false);
        if (res.statusCode() != 200) return null;
        JsonArray devices = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("devices");
        String fallback = null;
        for (JsonElement el : devices) {
            JsonObject d = el.getAsJsonObject();
            if (d.get("is_restricted").getAsBoolean() || d.get("id").isJsonNull()) continue;
            if (d.get("is_active").getAsBoolean()) return d.get("id").getAsString();
            if (fallback == null) fallback = d.get("id").getAsString();
        }
        return fallback;
    }

    private static HttpResponse<String> request(String method, String url, String json, boolean refreshed) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + SpotifyAuth.accessToken(refreshed))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        try {
            HttpResponse<String> res = SpotifyAuth.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 && !refreshed) return request(method, url, json, true);
            return res;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String errorMessage(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
        } catch (RuntimeException e) {
            return body.isBlank() ? "(no details)" : body;
        }
    }

    private SpotifyPlayer() {}
}
