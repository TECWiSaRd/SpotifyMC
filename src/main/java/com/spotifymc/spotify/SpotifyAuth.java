package com.spotifymc.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import com.spotifymc.ClientConfig;
import com.spotifymc.SpotifyMC;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Spotify login using Authorization Code + PKCE, so no client secret is needed. Each player
 * links their own account; tokens are stored locally in the config folder and never sent to
 * the Minecraft server.
 */
public final class SpotifyAuth {
    private static final String SCOPES = "user-modify-playback-state user-read-playback-state";
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final class Tokens {
        String accessToken;
        String refreshToken;
        long expiresAt; // epoch ms
    }

    private static Tokens tokens;
    private static HttpServer callbackServer;

    private static Path tokenFile() {
        return FMLPaths.CONFIGDIR.get().resolve("spotifymc-spotify-token.json");
    }

    public static synchronized boolean isLinked() {
        load();
        return tokens != null && tokens.refreshToken != null;
    }

    public static synchronized void unlink() {
        tokens = null;
        try {
            Files.deleteIfExists(tokenFile());
        } catch (IOException e) {
            SpotifyMC.LOGGER.warn("Couldn't delete Spotify token file", e);
        }
    }

    public static String redirectUri() {
        return "http://127.0.0.1:" + ClientConfig.SPOTIFY_REDIRECT_PORT.get() + "/callback";
    }

    /**
     * Starts a one-shot local HTTP server for the redirect and returns the URL the player must
     * open, or an error message. {@code done} receives the result message (on a background thread).
     */
    public static synchronized Either<String, String> beginLogin(Consumer<String> done) {
        String clientId = ClientConfig.SPOTIFY_CLIENT_ID.get().trim();
        if (clientId.isEmpty()) {
            return Either.right("Set your Spotify app's Client ID first (config: spotify.clientId). "
                    + "Create one at developer.spotify.com/dashboard with Redirect URI " + redirectUri());
        }
        stopServer();
        String verifier = randomString(64);
        String state = randomString(16);
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), ClientConfig.SPOTIFY_REDIRECT_PORT.get()), 0);
            server.createContext("/callback", exchange -> {
                Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
                String result;
                if (!state.equals(q.get("state"))) {
                    result = "Spotify login failed: state mismatch.";
                } else if (q.containsKey("error")) {
                    result = "Spotify login cancelled (" + q.get("error") + ").";
                } else {
                    result = exchangeCode(clientId, q.get("code"), verifier);
                }
                byte[] page = ("<!doctype html><meta charset=utf-8><title>SpotifyMC</title>"
                        + "<body style=\"font-family:sans-serif;padding:3em\"><h2>" + escape(result)
                        + "</h2><p>You can close this tab and return to Minecraft.</p>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, page.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(page);
                }
                done.accept(result);
                Executors.newSingleThreadScheduledExecutor().schedule(SpotifyAuth::stopServer, 1, TimeUnit.SECONDS);
            });
            server.start();
            callbackServer = server;
            // Give up after 5 minutes so the port isn't held forever.
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                synchronized (SpotifyAuth.class) {
                    if (callbackServer == server) stopServer();
                }
            }, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            return Either.right("Couldn't start Spotify login: " + e.getMessage());
        }

        return Either.left("https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirectUri())
                + "&state=" + state
                + "&code_challenge_method=S256&code_challenge=" + challenge);
    }

    private static synchronized void stopServer() {
        if (callbackServer != null) {
            callbackServer.stop(0);
            callbackServer = null;
        }
    }

    private static String exchangeCode(String clientId, String code, String verifier) {
        try {
            JsonObject json = tokenRequest("grant_type=authorization_code&code=" + enc(code)
                    + "&redirect_uri=" + enc(redirectUri()) + "&client_id=" + enc(clientId)
                    + "&code_verifier=" + enc(verifier));
            synchronized (SpotifyAuth.class) {
                tokens = new Tokens();
                apply(json);
            }
            return "Spotify account linked!";
        } catch (IOException e) {
            return "Spotify login failed: " + e.getMessage();
        }
    }

    /** Returns a valid access token, refreshing it if needed. */
    static synchronized String accessToken(boolean forceRefresh) throws IOException {
        load();
        if (tokens == null || tokens.refreshToken == null) throw new IOException("Spotify account not linked");
        if (forceRefresh || tokens.accessToken == null || System.currentTimeMillis() > tokens.expiresAt - 60_000) {
            String clientId = ClientConfig.SPOTIFY_CLIENT_ID.get().trim();
            JsonObject json = tokenRequest("grant_type=refresh_token&refresh_token=" + enc(tokens.refreshToken)
                    + "&client_id=" + enc(clientId));
            apply(json);
        }
        return tokens.accessToken;
    }

    private static JsonObject tokenRequest(String form) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            if (res.statusCode() != 200) {
                String err = json.has("error_description") ? json.get("error_description").getAsString()
                        : json.has("error") ? json.get("error").getAsString() : "HTTP " + res.statusCode();
                throw new IOException(err);
            }
            return json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (RuntimeException e) {
            throw new IOException("bad response from Spotify");
        }
    }

    private static void apply(JsonObject json) {
        tokens.accessToken = json.get("access_token").getAsString();
        if (json.has("refresh_token")) tokens.refreshToken = json.get("refresh_token").getAsString();
        tokens.expiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000;
        try {
            Files.writeString(tokenFile(), GSON.toJson(tokens));
        } catch (IOException e) {
            SpotifyMC.LOGGER.warn("Couldn't save Spotify token", e);
        }
    }

    private static void load() {
        if (tokens != null || !Files.isRegularFile(tokenFile())) return;
        try {
            tokens = GSON.fromJson(Files.readString(tokenFile()), Tokens.class);
        } catch (Exception e) {
            SpotifyMC.LOGGER.warn("Couldn't read Spotify token file", e);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String randomString(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private SpotifyAuth() {}
}
