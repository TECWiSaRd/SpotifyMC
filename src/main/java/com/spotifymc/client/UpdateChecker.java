package com.spotifymc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifymc.ClientConfig;
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
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/**
 * Checks GitHub Releases for a newer version when the player joins a world, and offers a
 * one-click install. Nothing is downloaded until the player clicks the button; the download
 * must come from this mod's own repository and match the SHA-256 checksum GitHub publishes.
 */
public final class UpdateChecker {
    public static final String REPO = "TECWiSaRd/SpotifyMC";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";

    private record Release(ArtifactVersion version, String tag, String pageUrl, String jarName,
                           String jarUrl, String sha256, long size) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL) // asset downloads redirect to GitHub's CDN
            .build();

    /** Fetched once per game launch, then reused for every join. */
    private static CompletableFuture<Release> latest;
    private static volatile boolean installed;

    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ClientConfig.CHECK_FOR_UPDATES.get() || installed) return;
        fetch().thenAccept(release -> Minecraft.getInstance().execute(() -> {
            if (release != null && isNewer(release)) announce(release);
        }));
    }

    /** /musicclient update: re-checks, and installs if there's something newer. */
    public static void installLatest() {
        if (installed) {
            ClientMusic.chat("The update is already installed. Restart Minecraft to use it.", ChatFormatting.YELLOW);
            return;
        }
        latest = null; // always ask GitHub again
        ClientMusic.chat("Checking for updates…", ChatFormatting.GRAY);
        fetch().thenAccept(release -> {
            if (release == null) {
                onMain(() -> ClientMusic.chat("Couldn't reach GitHub to check for updates.", ChatFormatting.RED));
            } else if (!isNewer(release)) {
                onMain(() -> ClientMusic.chat("You already have the latest version (" + currentVersion() + ").", ChatFormatting.GREEN));
            } else {
                install(release);
            }
        });
    }

    private static synchronized CompletableFuture<Release> fetch() {
        if (latest == null) latest = CompletableFuture.supplyAsync(UpdateChecker::fetchLatest);
        return latest;
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

    private static boolean isNewer(Release r) {
        return r.version().compareTo(currentVersion()) > 0;
    }

    private static ArtifactVersion currentVersion() {
        return ModList.get().getModContainerById(SpotifyMC.MODID).orElseThrow().getModInfo().getVersion();
    }

    private static void announce(Release r) {
        MutableComponent msg = Component.literal("♪ SpotifyMC " + r.version() + " is available (you have "
                + currentVersion() + "). ").withStyle(ChatFormatting.GOLD);
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

    // ---- install (background thread) ----

    private static void install(Release r) {
        Path current = ModList.get().getModFileById(SpotifyMC.MODID).getFile().getFilePath();
        if (!Files.isRegularFile(current) || !current.toString().endsWith(".jar")) {
            onMain(() -> ClientMusic.chat("Can't self-update a development build. Download it from " + r.pageUrl(), ChatFormatting.YELLOW));
            return;
        }
        Path modsDir = current.getParent();
        Path target = modsDir.resolve(r.jarName());
        if (r.jarName().contains("/") || r.jarName().contains("\\") || target.equals(current)) {
            onMain(() -> ClientMusic.chat("Unexpected file name in the release; install it by hand from " + r.pageUrl(), ChatFormatting.RED));
            return;
        }
        onMain(() -> ClientMusic.chat("Downloading SpotifyMC " + r.version() + "…", ChatFormatting.GRAY));
        Path tmp = null;
        try {
            HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(r.jarUrl()))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", "SpotifyMC-update-check").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
            byte[] jar = res.body();
            if (jar.length != r.size()) throw new IOException("download was incomplete");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
            if (r.sha256() != null && !actual.equals(r.sha256())) {
                throw new IOException("checksum mismatch, refusing to install");
            }

            tmp = Files.createTempFile(modsDir, "spotifymc-update", ".tmp");
            Files.write(tmp, jar);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            removeOldJar(current);
            installed = true;
            onMain(() -> ClientMusic.chat("SpotifyMC " + r.version() + " installed. Restart Minecraft to use it.", ChatFormatting.GREEN));
        } catch (Exception e) {
            SpotifyMC.LOGGER.warn("Update install failed", e);
            onMain(() -> ClientMusic.chat("Update failed: " + e.getMessage() + ". You can download it from " + r.pageUrl(), ChatFormatting.RED));
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Two copies of the mod in the mods folder would stop the game from starting, so the old
     * jar must go. macOS and Linux allow deleting it while it's loaded. Windows keeps it locked
     * until Minecraft exits, so a small helper waits for this process to end and deletes it then.
     */
    private static void removeOldJar(Path old) {
        try {
            Files.delete(old);
            return;
        } catch (IOException e) {
            SpotifyMC.LOGGER.info("Old jar is locked ({}), deleting it after exit", e.toString());
        }
        long pid = ProcessHandle.current().pid();
        String path = old.toAbsolutePath().toString().replace("'", "''");
        String script = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "Remove-Item -LiteralPath '" + path + "' -Force";
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start();
        } catch (IOException e) {
            old.toFile().deleteOnExit(); // last resort
            onMain(() -> ClientMusic.chat("After closing Minecraft, delete " + old.getFileName()
                    + " from your mods folder.", ChatFormatting.YELLOW));
        }
    }

    private static void onMain(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private UpdateChecker() {}
}
