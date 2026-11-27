package com.spotifymc.exaroton;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minimal client for the exaroton REST API (https://developers.exaroton.com/). */
final class ExarotonApi {
    static final int OFFLINE = 0, ONLINE = 1, STARTING = 2, STOPPING = 3, RESTARTING = 4, SAVING = 5,
            LOADING = 6, CRASHED = 7, PENDING = 8, TRANSFERRING = 9, PREPARING = 10;

    record Server(String id, String name, String address, int status, int players, int maxPlayers) {
        String statusName() {
            return switch (status) {
                case OFFLINE -> "offline";
                case ONLINE -> "online";
                case STARTING -> "starting";
                case STOPPING -> "stopping";
                case RESTARTING -> "restarting";
                case SAVING -> "saving";
                case LOADING -> "loading";
                case CRASHED -> "crashed";
                case PENDING -> "waiting for a free host";
                case TRANSFERRING -> "transferring";
                case PREPARING -> "preparing";
                default -> "status " + status;
            };
        }
    }

    private static final String BASE = "https://api.exaroton.com/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static List<Server> list(String token) throws IOException {
        List<Server> out = new ArrayList<>();
        for (JsonElement e : call(token, "/servers/").getAsJsonArray()) out.add(parse(e.getAsJsonObject()));
        return out;
    }

    static Server get(String token, String id) throws IOException {
        return parse(call(token, "/servers/" + enc(id)).getAsJsonObject());
    }

    static void start(String token, String id) throws IOException {
        call(token, "/servers/" + enc(id) + "/start/");
    }

    static void stop(String token, String id) throws IOException {
        call(token, "/servers/" + enc(id) + "/stop/");
    }

    private static Server parse(JsonObject o) {
        JsonObject players = o.has("players") && o.get("players").isJsonObject() ? o.getAsJsonObject("players") : null;
        return new Server(str(o, "id"), str(o, "name"), str(o, "address"),
                o.has("status") ? o.get("status").getAsInt() : -1,
                players != null && players.has("count") ? players.get("count").getAsInt() : 0,
                players != null && players.has("max") ? players.get("max").getAsInt() : 0);
    }

    /** Sends a GET and returns the "data" part of exaroton's {success, error, data} envelope. */
    private static JsonElement call(String token, String path) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "SpotifyMC")
                .GET().build();
        HttpResponse<String> res;
        try {
            res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (res.statusCode() == 401) throw new IOException("exaroton rejected the API token. Set a new one with /exaroton token <token>");
        JsonObject body;
        try {
            body = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("exaroton returned HTTP " + res.statusCode());
        }
        boolean ok = body.has("success") && body.get("success").getAsBoolean();
        if (!ok || res.statusCode() >= 400) {
            String err = str(body, "error");
            // exaroton answers a bad token with 403 {"error":"Unauthorized"}
            if (err.equalsIgnoreCase("Unauthorized")) {
                throw new IOException("exaroton rejected the API token. Set a new one with /exaroton token <token>");
            }
            throw new IOException(err.isEmpty() ? "exaroton returned HTTP " + res.statusCode() : err);
        }
        return body.get("data");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private ExarotonApi() {}
}
