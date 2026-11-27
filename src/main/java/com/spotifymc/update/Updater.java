package com.spotifymc.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifymc.SpotifyMC;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/**
 * Finds and installs new SpotifyMC releases from GitHub. Shared by the client's
 * [Install update] button and the server's automatic update; has no client-only code.
 *
 * <p>Safety: only jars attached to this mod's own GitHub releases are accepted, and each must
 * match the SHA-256 checksum GitHub publishes for it.
 */
public final class Updater {
    public static final String REPO = "TECWiSaRd/SpotifyMC";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";

    public record Release(ArtifactVersion version, String tag, String pageUrl, String jarName,
                          String jarUrl, String sha256, long size) {}

    /**
     * @param oldJarNote null if the old jar was removed, otherwise what the person must know
     *                   (e.g. "deleted after exit", or a manual step).
     */
    public record Installed(Release release, Path jar, String oldJarNote) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL) // asset downloads redirect to GitHub's CDN
            .build();

    private static CompletableFuture<Release> cached;
    private static volatile Installed installed;

    /** Latest release, fetched once per game launch unless {@code refresh}. Completes with null on failure. */
    public static synchronized CompletableFuture<Release> latest(boolean refresh) {
        if (cached == null || refresh) cached = CompletableFuture.supplyAsync(Updater::fetchLatest);
        return cached;
    }

    /** The update installed during this run (waiting for a restart), or null. */
    public static Installed pendingRestart() {
        return installed;
    }

    public static ArtifactVersion currentVersion() {
        return ModList.get().getModContainerById(SpotifyMC.MODID).orElseThrow().getModInfo().getVersion();
    }

    public static boolean isNewer(Release r) {
        return r != null && r.version().compareTo(currentVersion()) > 0;
    }

    private static Release fetchLatest() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SpotifyMC-update-check")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                SpotifyMC.LOGGER.info("Update check: GitHub answered HTTP {}", res.statusCode());
                return null;
            }
            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            String tag = json.get("tag_name").getAsString();
            for (JsonElement el : json.getAsJsonArray("assets")) {
                JsonObject a = el.getAsJsonObject();
                String name = a.get("name").getAsString();
                String url = a.get("browser_download_url").getAsString();
                if (!name.endsWith(".jar") || name.contains("-sources") || !url.startsWith(DOWNLOAD_PREFIX)) continue;
                String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : "";
                String sha = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
                return new Release(new DefaultArtifactVersion(tag.replaceFirst("^[vV]", "")), tag,
                        json.get("html_url").getAsString(), name, url, sha, a.get("size").getAsLong());
            }
            SpotifyMC.LOGGER.info("Update check: release {} has no jar attached", tag);
            return null;
        } catch (Exception e) {
            SpotifyMC.LOGGER.info("Update check failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Downloads the release jar into the mods folder and removes the running one.
     * Blocking; call from a background thread. Takes effect on the next start.
     */
    public static synchronized Installed install(Release r) throws IOException {
        if (installed != null) return installed;
        Path current = ModList.get().getModFileById(SpotifyMC.MODID).getFile().getFilePath();
        if (!Files.isRegularFile(current) || !current.toString().endsWith(".jar")) {
            throw new IOException("this is a development build, which can't update itself");
        }
        Path modsDir = current.getParent();
        Path target = modsDir.resolve(r.jarName());
        if (r.jarName().contains("/") || r.jarName().contains("\\") || target.equals(current)) {
            throw new IOException("unexpected file name in the release");
        }

        byte[] jar;
        try {
            HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(r.jarUrl()))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", "SpotifyMC-update-check").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IOException("download failed (HTTP " + res.statusCode() + ")");
            jar = res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (jar.length != r.size()) throw new IOException("download was incomplete");
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
            if (r.sha256() != null && !actual.equals(r.sha256())) throw new IOException("checksum mismatch, refusing to install");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

        Path tmp = Files.createTempFile(modsDir, "spotifymc-update", ".tmp");
        try {
            Files.write(tmp, jar);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        installed = new Installed(r, target, removeOldJar(current));
        SpotifyMC.LOGGER.info("Installed SpotifyMC {} to {}; it takes effect after a restart", r.version(), target);
        return installed;
    }

    /**
     * Two copies of the mod in the mods folder would stop the game from starting, so the old
     * jar must go. macOS and Linux allow deleting it while it's loaded. Windows keeps it locked
     * until the process exits, so a small helper waits for this process to end and deletes it then.
     *
     * @return null if removed now, otherwise a note for the person.
     */
    private static String removeOldJar(Path old) {
        try {
            Files.delete(old);
            return null;
        } catch (IOException e) {
            SpotifyMC.LOGGER.info("Old jar is locked ({}), deleting it after exit", e.toString());
        }
        long pid = ProcessHandle.current().pid();
        String path = old.toAbsolutePath().toString().replace("'", "''");
        String script = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "Remove-Item -LiteralPath '" + path + "' -Force";
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start();
            return "The old version is removed automatically when the game closes.";
        } catch (IOException e) {
            old.toFile().deleteOnExit(); // last resort
            return "After closing, delete " + old.getFileName() + " from the mods folder, or the next start will fail.";
        }
    }

    private Updater() {}
}
