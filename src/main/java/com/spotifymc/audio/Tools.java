package com.spotifymc.audio;

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
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.neoforged.fml.loading.FMLPaths;

/** Locates (and if allowed, downloads) the external yt-dlp and ffmpeg executables. */
public final class Tools {
    public static final class MissingToolException extends Exception {
        public MissingToolException(String message) {
            super(message);
        }
    }

    private static final String YTDLP_RELEASES = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final Duration YTDLP_MAX_AGE = Duration.ofDays(7);
    private static final Object DOWNLOAD_LOCK = new Object();

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS_NAME.contains("win");
    private static final boolean MAC = OS_NAME.contains("mac");
    private static final boolean ARM = System.getProperty("os.arch").toLowerCase(Locale.ROOT).matches("aarch64|arm64");

    public static Path binDir() {
        return FMLPaths.GAMEDIR.get().resolve("spotifymc").resolve("bin");
    }

    public static Path ffmpeg() throws MissingToolException {
        Path p = find(ClientConfig.FFMPEG_PATH.get(), "ffmpeg");
        if (p == null) {
            throw new MissingToolException("ffmpeg not found. Install it ("
                    + (MAC ? "brew install ffmpeg" : WINDOWS ? "winget install ffmpeg" : "your package manager")
                    + "), put it in " + binDir() + ", or set ffmpegPath in the SpotifyMC client config.");
        }
        return p;
    }

    public static Path ytDlp(Consumer<String> status) throws MissingToolException {
        Path configured = ClientConfig.YTDLP_PATH.get().isBlank() ? null : find(ClientConfig.YTDLP_PATH.get(), "yt-dlp");
        if (configured != null) return configured;

        Path managed = binDir().resolve(ytDlpAssetName());
        boolean autoDownload = ClientConfig.AUTO_DOWNLOAD_YTDLP.get();
        synchronized (DOWNLOAD_LOCK) {
            if (Files.isRegularFile(managed)) {
                if (autoDownload && isStale(managed)) {
                    try {
                        download(managed, status);
                    } catch (IOException e) {
                        SpotifyMC.LOGGER.warn("Couldn't refresh yt-dlp, using the existing copy", e);
                    }
                }
                return managed;
            }
            Path system = find("", "yt-dlp");
            if (system != null) return system;
            if (autoDownload) {
                try {
                    status.accept("Downloading yt-dlp (one-time)…");
                    download(managed, status);
                    return managed;
                } catch (IOException e) {
                    throw new MissingToolException("Couldn't download yt-dlp: " + e.getMessage());
                }
            }
        }
        throw new MissingToolException("yt-dlp not found. Install it, put it in " + binDir()
                + ", or enable autoDownloadYtDlp in the SpotifyMC client config.");
    }

    /** One-line summary for /musicclient status. */
    public static String describe() {
        String ff;
        try {
            ff = ffmpeg().toString();
        } catch (MissingToolException e) {
            ff = "NOT FOUND";
        }
        Path managed = binDir().resolve(ytDlpAssetName());
        String yt = !ClientConfig.YTDLP_PATH.get().isBlank() ? ClientConfig.YTDLP_PATH.get()
                : Files.isRegularFile(managed) ? managed.toString()
                : find("", "yt-dlp") != null ? find("", "yt-dlp").toString()
                : ClientConfig.AUTO_DOWNLOAD_YTDLP.get() ? "will be downloaded on first use" : "NOT FOUND";
        return "yt-dlp: " + yt + "\nffmpeg: " + ff;
    }

    // ---- lookup ----

    /** Explicit path if given, otherwise the mod's bin folder, PATH, and common install folders. */
    private static Path find(String explicit, String name) {
        if (!explicit.isBlank()) {
            Path p = Path.of(explicit.trim());
            return Files.isRegularFile(p) ? p : null;
        }
        String exe = WINDOWS ? name + ".exe" : name;
        List<Path> dirs = new ArrayList<>();
        dirs.add(binDir());
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(java.io.File.pathSeparator)) if (!d.isBlank()) dirs.add(Path.of(d));
        }
        // Launchers started from the Dock/Start menu often don't inherit the shell PATH.
        String home = System.getProperty("user.home");
        if (WINDOWS) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) dirs.add(Path.of(local, "Microsoft", "WinGet", "Links"));
            dirs.add(Path.of(home, "scoop", "shims"));
            dirs.add(Path.of("C:\\ProgramData\\chocolatey\\bin"));
            dirs.add(Path.of("C:\\ffmpeg\\bin"));
        } else {
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/usr/bin"));
            dirs.add(Path.of("/bin"));
            dirs.add(Path.of("/snap/bin"));
            dirs.add(Path.of(home, ".local", "bin"));
        }
        for (Path dir : dirs) {
            try {
                Path candidate = dir.resolve(exe);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            } catch (RuntimeException ignored) {
                // malformed PATH entry
            }
        }
        return null;
    }

    // ---- yt-dlp download ----

    private static String ytDlpAssetName() {
        if (WINDOWS) return ARM ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
        if (MAC) return "yt-dlp_macos";
        return ARM ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
    }

    private static boolean isStale(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(YTDLP_MAX_AGE));
        } catch (IOException e) {
            return false;
        }
    }

    /** Downloads the official release binary and checks it against the release's SHA-256 list. */
    private static void download(Path target, Consumer<String> status) throws IOException {
        String asset = ytDlpAssetName();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15)).build();
        try {
            String sums = http.send(HttpRequest.newBuilder(URI.create(YTDLP_RELEASES + "SHA2-256SUMS")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            String expected = null;
            for (String line : sums.split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].equals(asset)) expected = parts[0].toLowerCase(Locale.ROOT);
            }
            if (expected == null) throw new IOException("no checksum published for " + asset);

            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(YTDLP_RELEASES + asset))
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(res.body()));
            if (!actual.equals(expected)) throw new IOException("checksum mismatch, refusing to use download");

            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), asset, ".part");
            Files.write(tmp, res.body());
            if (!WINDOWS && !tmp.toFile().setExecutable(true)) throw new IOException("couldn't mark yt-dlp executable");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            SpotifyMC.LOGGER.info("Downloaded yt-dlp to {}", target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private Tools() {}
}
