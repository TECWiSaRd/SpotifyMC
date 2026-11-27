package com.spotifymc.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a player typed into one or more playable requests. Runs on the server, uses only
 * public, unauthenticated endpoints, and never needs yt-dlp on the server.
 *
 * <p>Spotify artists give their top 10 tracks; YouTube playlists give up to their first 100 videos.
 *
 * <p>Spotify audio itself cannot be streamed by third-party software, so Spotify links are
 * converted to metadata (title, artists, length). Clients then either play the track in their
 * own Spotify app, or search YouTube for the same song.
 */
public final class TrackResolver {
    public record Request(String query, String title, boolean titleKnown, String spotifyUri, long durationMs) {}

    public static final class ResolveException extends Exception {
        public ResolveException(String message) {
            super(message);
        }
    }

    private static final Pattern SPOTIFY = Pattern.compile(
            "(?:spotify:(track|album|playlist|artist):|open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(track|album|playlist|artist)/)([A-Za-z0-9]{22})");
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static List<Request> resolve(String input, int maxTracks) throws ResolveException {
        input = input.trim();
        Matcher m = SPOTIFY.matcher(input);
        if (m.find()) {
            String kind = m.group(1) != null ? m.group(1) : m.group(2);
            return resolveSpotify(kind, m.group(3), maxTracks);
        }
        if (input.startsWith("http://") || input.startsWith("https://")) {
            URI uri = parseUri(input);
            if (uri != null && isYouTube(uri)) {
                String list = queryParam(uri, "list");
                if (list != null && list.matches("[A-Za-z0-9_-]{10,}")) {
                    String video = videoId(uri);
                    // RD... = a Mix (radio). Other lists are normal playlists or YouTube Music albums.
                    List<YouTube.Video> videos = list.startsWith("RD")
                            ? YouTube.mix(video != null ? video : list.substring(2), maxTracks)
                            : YouTube.playlist(list, maxTracks);
                    return videos.stream().map(YouTube.Video::toRequest).toList();
                }
                String title = youTubeTitle(input);
                return List.of(new Request(input, title != null ? title : input, title != null, "", 0));
            }
            return List.of(new Request(input, input, false, "", 0));
        }
        // Plain text: let clients search YouTube.
        return List.of(new Request("ytsearch1:" + input, input, false, "", 0));
    }

    /**
     * Radio: finds one song for the input (a link, a Spotify track/artist/album, or search text)
     * and returns YouTube's Mix for it, starting with that song.
     */
    public static List<Request> resolveRadio(String input, int maxTracks) throws ResolveException {
        input = input.trim();
        String seed = null;
        URI uri = input.startsWith("http") ? parseUri(input) : null;
        if (uri != null && isYouTube(uri)) {
            seed = videoId(uri);
        } else if (SPOTIFY.matcher(input).find()) {
            // Use the first track, e.g. an artist's most popular song.
            String title = resolve(input, 1).get(0).title();
            YouTube.Video v = YouTube.search(title + " audio");
            seed = v != null ? v.id() : null;
        } else if (uri == null) {
            YouTube.Video v = YouTube.search(input);
            seed = v != null ? v.id() : null;
        }
        if (seed == null) throw new ResolveException("Couldn't find a song to start a radio from.");
        return YouTube.mix(seed, maxTracks).stream().map(YouTube.Video::toRequest).toList();
    }

    /** The YouTube video id in a URL (watch?v=, youtu.be/, /shorts/, /live/), or null. */
    public static String videoId(String url) {
        URI uri = parseUri(url);
        return uri != null && isYouTube(uri) ? videoId(uri) : null;
    }

    private static String videoId(URI uri) {
        String id = queryParam(uri, "v");
        if (id == null) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (uri.getHost().equalsIgnoreCase("youtu.be")) id = path.replaceFirst("^/", "");
            else if (path.matches("/(shorts|live|embed)/.+")) id = path.replaceFirst("^/[^/]+/", "");
        }
        return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            if (pair.startsWith(name + "=")) return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static URI parseUri(String url) {
        try {
            URI uri = URI.create(url.replace(" ", "%20"));
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Request> resolveSpotify(String kind, String id, int maxTracks) throws ResolveException {
        JsonObject entity;
        try {
            String html = get("https://open.spotify.com/embed/" + kind + "/" + id);
            Matcher m = NEXT_DATA.matcher(html);
            if (!m.find()) throw new ResolveException("Spotify returned an unexpected page.");
            entity = JsonParser.parseString(m.group(1)).getAsJsonObject()
                    .getAsJsonObject("props").getAsJsonObject("pageProps")
                    .getAsJsonObject("state").getAsJsonObject("data").getAsJsonObject("entity");
        } catch (IOException | RuntimeException e) {
            throw new ResolveException("Couldn't read that Spotify link (" + e.getMessage() + ").");
        }

        List<Request> out = new ArrayList<>();
        if (kind.equals("track")) {
            List<String> artists = new ArrayList<>();
            JsonArray arr = entity.getAsJsonArray("artists");
            if (arr != null) for (JsonElement a : arr) artists.add(str(a.getAsJsonObject(), "name"));
            String name = str(entity, "name").isEmpty() ? str(entity, "title") : str(entity, "name");
            out.add(spotifyRequest(name, String.join(", ", artists), "spotify:track:" + id, num(entity, "duration")));
        } else {
            JsonArray list = entity.getAsJsonArray("trackList");
            if (list != null) {
                for (JsonElement el : list) {
                    if (out.size() >= maxTracks) break;
                    JsonObject t = el.getAsJsonObject();
                    String uri = str(t, "uri");
                    if (!uri.startsWith("spotify:track:")) continue; // skip podcast episodes etc.
                    String artists = str(t, "subtitle").replace('\u00a0', ' ');
                    out.add(spotifyRequest(str(t, "title"), artists, uri, num(t, "duration")));
                }
            }
        }
        if (out.isEmpty()) throw new ResolveException("That Spotify " + kind + " has no playable tracks.");
        return out;
    }

    private static Request spotifyRequest(String title, String artists, String uri, long durationMs) {
        String display = artists.isEmpty() ? title : artists + " - " + title;
        // "audio" steers YouTube toward the album version rather than a music video with an intro.
        return new Request("ytsearch1:" + display + " audio", display, true, uri, durationMs);
    }

    private static boolean isYouTube(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
    }

    private static String youTubeTitle(String url) {
        try {
            String json = get("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode(url, StandardCharsets.UTF_8));
            String title = str(JsonParser.parseString(json).getAsJsonObject(), "title");
            return title.isEmpty() ? null : title;
        } catch (Exception e) {
            return null; // a client will report the title later
        }
    }

    static String get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept-Language", "en")
                .header("Cookie", "CONSENT=YES+1") // skip the EU cookie wall on YouTube
                .GET().build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private TrackResolver() {}
}
