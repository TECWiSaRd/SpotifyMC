package com.spotifymc.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads YouTube playlists, Mixes (YouTube's auto-generated "radio") and search results from
 * the public web pages, so the server can queue many videos without needing yt-dlp.
 *
 * <p>YouTube changes its page layout from time to time, so this looks for every renderer
 * type known to hold a video (old and new layouts) anywhere in the page data.
 */
final class YouTube {
    record Video(String id, String title, long durationMs) {
        TrackResolver.Request toRequest() {
            return new TrackResolver.Request("https://www.youtube.com/watch?v=" + id, title, true, "", durationMs);
        }
    }

    private static final Pattern INITIAL_DATA = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>", Pattern.DOTALL);
    private static final Pattern DURATION = Pattern.compile("^(?:(\\d+):)?(\\d+):(\\d{2})$");

    /** Radio skips anything longer than this (hour-long mixes, full albums, livestream replays). */
    private static final long MAX_RADIO_TRACK_MS = 15 * 60 * 1000;
    /** A search for one song skips results longer than this. */
    private static final long MAX_SEARCH_TRACK_MS = 10 * 60 * 1000;

    private static final Set<String> PLAYLIST_KEYS = Set.of("playlistVideoRenderer", "lockupViewModel");
    /** Only the Mix panel: watch pages also list unrelated recommendations in the sidebar. */
    private static final Set<String> MIX_KEYS = Set.of("playlistPanelVideoRenderer");
    private static final Set<String> SEARCH_KEYS = Set.of("videoRenderer", "lockupViewModel");

    /** Videos of a normal playlist (also YouTube Music albums, which are playlists). First page only, max ~100. */
    static List<Video> playlist(String listId, int max) throws TrackResolver.ResolveException {
        return videos(page("https://www.youtube.com/playlist?list=" + enc(listId), "That YouTube playlist doesn't exist or is private."),
                PLAYLIST_KEYS, max, "That YouTube playlist");
    }

    /** A Mix: YouTube's endless radio based on one video. Returns ~25 videos, the seed first. */
    static List<Video> mix(String videoId, int max) throws TrackResolver.ResolveException {
        List<Video> all = videos(page("https://www.youtube.com/watch?v=" + enc(videoId) + "&list=RD" + enc(videoId),
                "That YouTube video doesn't exist."), MIX_KEYS, Integer.MAX_VALUE, "YouTube's radio for that song");
        List<Video> out = new ArrayList<>();
        for (Video v : all) {
            if (out.size() >= max) break;
            if (v.id().equals(videoId) || v.durationMs() <= MAX_RADIO_TRACK_MS) out.add(v);
        }
        return out;
    }

    /** Best song-length video in the search results, or null. */
    static Video search(String query) throws TrackResolver.ResolveException {
        // sp=EgIQAQ%3D%3D = "type: video" filter, so channels/playlists don't come first.
        List<Video> found = videos(page("https://www.youtube.com/results?sp=EgIQAQ%253D%253D&search_query=" + enc(query),
                "YouTube search failed."), SEARCH_KEYS, 10, null);
        for (Video v : found) {
            if (v.durationMs() > 0 && v.durationMs() <= MAX_SEARCH_TRACK_MS) return v;
        }
        return found.isEmpty() ? null : found.get(0);
    }

    // ---- parsing ----

    private static JsonObject page(String url, String notFound) throws TrackResolver.ResolveException {
        String html;
        try {
            html = TrackResolver.get(url);
        } catch (IOException e) {
            if ("HTTP 404".equals(e.getMessage())) throw new TrackResolver.ResolveException(notFound);
            throw new TrackResolver.ResolveException("Couldn't reach YouTube (" + e.getMessage() + ").");
        }
        Matcher m = INITIAL_DATA.matcher(html);
        if (!m.find()) throw new TrackResolver.ResolveException("YouTube returned an unexpected page.");
        try {
            return JsonParser.parseString(m.group(1)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new TrackResolver.ResolveException("YouTube returned an unexpected page.");
        }
    }

    private static List<Video> videos(JsonObject data, Set<String> keys, int max, String what) throws TrackResolver.ResolveException {
        Map<String, Video> found = new LinkedHashMap<>(); // keyed by id: Mix pages list some videos twice
        walk(data, (key, obj) -> {
            if (!keys.contains(key)) return;
            Video v = switch (key) {
                case "playlistVideoRenderer", "playlistPanelVideoRenderer", "videoRenderer" -> classicRenderer(obj);
                case "lockupViewModel" -> lockup(obj);
                default -> null;
            };
            if (v != null && found.size() < max) found.putIfAbsent(v.id(), v);
        });
        if (found.isEmpty() && what != null) {
            String alert = firstText(data, "alertRenderer");
            throw new TrackResolver.ResolveException(what + " couldn't be loaded"
                    + (alert != null ? ": " + alert : " (is it private?)") + ".");
        }
        return new ArrayList<>(found.values());
    }

    /** The older layout: {videoId, title: {runs|simpleText}, lengthText | lengthSeconds}. */
    private static Video classicRenderer(JsonObject o) {
        if (!o.has("videoId")) return null;
        if (o.has("isPlayable") && !o.get("isPlayable").getAsBoolean()) return null; // deleted/private entries
        String title = text(o.get("title"));
        long ms = 0;
        if (o.has("lengthSeconds")) ms = o.get("lengthSeconds").getAsLong() * 1000;
        else if (o.has("lengthText")) ms = parseDuration(text(o.get("lengthText")));
        if (o.has("badges") && o.toString().contains("BADGE_STYLE_TYPE_LIVE_NOW")) ms = 0;
        return new Video(o.get("videoId").getAsString(), title.isEmpty() ? o.get("videoId").getAsString() : title, ms);
    }

    /** The newer layout used on playlist pages since 2025. */
    private static Video lockup(JsonObject o) {
        if (!o.has("contentId") || !"LOCKUP_CONTENT_TYPE_VIDEO".equals(str(o, "contentType"))) return null;
        String id = o.get("contentId").getAsString();
        String title = "";
        JsonObject meta = obj(obj(o, "metadata"), "lockupMetadataViewModel");
        if (meta != null) title = text(meta.get("title"));
        long[] ms = {0};
        walk(o, (key, badge) -> {
            if (key.equals("thumbnailBadgeViewModel") && ms[0] == 0) ms[0] = parseDuration(str(badge, "text"));
        });
        return new Video(id, title.isEmpty() ? id : title, ms[0]);
    }

    private static long parseDuration(String s) {
        Matcher m = DURATION.matcher(s.trim());
        if (!m.matches()) return 0;
        long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
        return ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000;
    }

    /** Text from any of YouTube's text shapes: "x", {simpleText}, {runs:[{text}]}, {content}. */
    private static String text(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        if (!e.isJsonObject()) return "";
        JsonObject o = e.getAsJsonObject();
        if (o.has("simpleText")) return o.get("simpleText").getAsString();
        if (o.has("content")) return o.get("content").getAsString();
        if (o.has("runs")) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement run : o.getAsJsonArray("runs")) sb.append(text(run.getAsJsonObject().get("text")));
            return sb.toString();
        }
        return "";
    }

    private static String firstText(JsonObject data, String rendererKey) {
        String[] out = {null};
        walk(data, (key, o) -> {
            if (out[0] == null && key.equals(rendererKey)) {
                String t = text(o.get("text"));
                if (!t.isEmpty()) out[0] = t;
            }
        });
        return out[0];
    }

    private interface Visitor {
        void visit(String key, JsonObject value);
    }

    private static void walk(JsonElement e, Visitor v) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (en.getValue().isJsonObject()) v.visit(en.getKey(), en.getValue().getAsJsonObject());
                walk(en.getValue(), v);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement child : (JsonArray) e) walk(child, v);
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private YouTube() {}
}
