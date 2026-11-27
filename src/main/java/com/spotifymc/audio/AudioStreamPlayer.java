package com.spotifymc.audio;

import com.spotifymc.ClientConfig;
import com.spotifymc.SpotifyMC;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Streams one track: {@code yt-dlp -o -} → {@code ffmpeg} → 48 kHz stereo PCM → speakers.
 *
 * <p>Sync: {@code targetPositionMs} says where playback should be right now (derived from the
 * server). Audio decoded before that point is thrown away, which absorbs yt-dlp start-up time
 * and lets late joiners land in the middle of a song.
 */
public final class AudioStreamPlayer {
    public interface Listener {
        /** Called once, when audio starts. durationMs is 0 if unknown, negative for live streams. */
        void onStarted(String title, long durationMs);

        /** Called when the stream ends naturally, with the length that was actually decoded. */
        void onFinished(long decodedMs);

        void onError(String message);

        void onStatus(String message);
    }

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_BYTES = 4; // 16-bit stereo
    private static final double BYTES_PER_MS = SAMPLE_RATE * FRAME_BYTES / 1000.0;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
    /** Never drop audio unless we are at least this far behind. */
    private static final long CATCH_UP_SLACK_MS = 300;

    /** Linear gain applied to samples, updated from the client thread each tick. */
    public static volatile float gain = 1f;

    private final String query;
    private final LongSupplier targetPositionMs;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile Process ytdlp;
    private volatile Process ffmpeg;
    private volatile SourceDataLine line;

    public AudioStreamPlayer(String query, LongSupplier targetPositionMs, Listener listener) {
        this.query = query;
        this.targetPositionMs = targetPositionMs;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(this::run, "SpotifyMC audio");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped = true;
        SourceDataLine l = line;
        if (l != null) {
            l.stop();  // unblocks a pending write()
            l.flush();
        }
        kill(ytdlp);
        kill(ffmpeg);
    }

    public boolean isStopped() {
        return stopped;
    }

    private void run() {
        Path meta = null;
        List<String> ytErrors = new ArrayList<>();
        try {
            Path ffmpegExe = Tools.ffmpeg();
            Path ytdlpExe = Tools.ytDlp(listener::onStatus);
            if (stopped) return;

            meta = Files.createTempFile("spotifymc-", ".txt");
            List<String> yt = new ArrayList<>(List.of(ytdlpExe.toString(),
                    "--quiet", "--no-warnings", "--no-progress",
                    "--no-playlist", "--playlist-items", "1", "--no-part",
                    "-f", "bestaudio/best",
                    "--ffmpeg-location", ffmpegExe.toString(),
                    // Written before the download starts, so it's ready when audio arrives.
                    "--print-to-file", "before_dl:%(duration)s|%(is_live)s|%(title)s",
                    meta.toString().replace("%", "%%"),
                    "-o", "-"));
            for (String arg : ClientConfig.YTDLP_EXTRA_ARGS.get().trim().split("\\s+")) {
                if (!arg.isEmpty()) yt.add(arg);
            }
            // "--" ends option parsing so a server-supplied query can never inject yt-dlp options.
            yt.add("--");
            yt.add(query);

            ytdlp = new ProcessBuilder(yt).start();
            ffmpeg = new ProcessBuilder(ffmpegExe.toString(), "-hide_banner", "-loglevel", "error",
                    "-i", "pipe:0", "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
                    "-ac", "2", "-ar", String.valueOf(SAMPLE_RATE), "pipe:1").start();
            if (stopped) return;

            collectLines(ytdlp.getErrorStream(), ytErrors);
            collectLines(ffmpeg.getErrorStream(), new ArrayList<>());
            Process yProc = ytdlp, fProc = ffmpeg;
            daemon(() -> {
                try (InputStream in = yProc.getInputStream(); OutputStream out = fProc.getOutputStream()) {
                    in.transferTo(out);
                } catch (IOException ignored) {
                    // stopped or pipe closed
                }
            }, "SpotifyMC pipe");

            playPcm(new BufferedInputStream(ffmpeg.getInputStream(), 1 << 16), meta, ytErrors);
        } catch (Tools.MissingToolException e) {
            if (!stopped) listener.onError(e.getMessage());
        } catch (LineUnavailableException e) {
            if (!stopped) listener.onError("No audio output available: " + e.getMessage());
        } catch (Exception e) {
            if (!stopped) {
                SpotifyMC.LOGGER.error("Music playback failed", e);
                listener.onError("Playback failed: " + e.getMessage());
            }
        } finally {
            stopped = true;
            SourceDataLine l = line;
            if (l != null) l.close();
            kill(ytdlp);
            kill(ffmpeg);
            if (meta != null) {
                try {
                    Files.deleteIfExists(meta);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void playPcm(InputStream pcm, Path meta, List<String> ytErrors) throws Exception {
        byte[] buf = new byte[FRAME_BYTES * 1024];
        long bytesDecoded = 0;
        boolean started = false;

        while (!stopped) {
            int n = pcm.readNBytes(buf, 0, buf.length);
            n -= n % FRAME_BYTES;
            if (n <= 0) break;

            if (!started) {
                started = true;
                SourceDataLine l = AudioSystem.getSourceDataLine(FORMAT);
                l.open(FORMAT, (int) (BYTES_PER_MS * 250)); // ~250 ms device buffer
                l.start();
                line = l;
                reportStarted(meta);
            }

            long positionMs = (long) (bytesDecoded / BYTES_PER_MS);
            bytesDecoded += n;
            if (positionMs + CATCH_UP_SLACK_MS < targetPositionMs.getAsLong()) continue; // catching up

            applyGain(buf, n, gain);
            line.write(buf, 0, n);
        }

        if (stopped) return;
        if (!started) {
            ytdlp.waitFor();
            Thread.sleep(200); // let the stderr reader catch the final error line
            String reason;
            synchronized (ytErrors) {
                reason = ytErrors.isEmpty() ? "no audio received" : ytErrors.get(ytErrors.size() - 1);
            }
            listener.onError("Couldn't play track: " + reason.replaceFirst("^ERROR:\\s*", ""));
            return;
        }
        line.drain();
        listener.onFinished((long) (bytesDecoded / BYTES_PER_MS));
    }

    private void reportStarted(Path meta) {
        String title = "";
        long durationMs = 0;
        try {
            String text = Files.readString(meta, StandardCharsets.UTF_8).strip();
            String[] parts = text.split("\\|", 3); // duration|is_live|title
            if (parts.length == 3) {
                title = parts[2].strip();
                if (parts[1].equalsIgnoreCase("True")) durationMs = -1;
                else if (!parts[0].equals("NA")) durationMs = Math.round(Double.parseDouble(parts[0]) * 1000);
            }
        } catch (IOException | NumberFormatException e) {
            SpotifyMC.LOGGER.debug("No yt-dlp metadata for {}", query, e);
        }
        listener.onStarted(title, durationMs);
    }

    private static void applyGain(byte[] buf, int len, float g) {
        if (Math.abs(g - 1f) < 0.001f) return;
        for (int i = 0; i < len; i += 2) {
            int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            sample = Math.round(sample * g);
            // Boosts above 100% can overflow 16 bits; clamp instead of wrapping (which crackles).
            sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
            buf[i] = (byte) sample;
            buf[i + 1] = (byte) (sample >> 8);
        }
    }

    private static void collectLines(InputStream stream, List<String> sink) {
        daemon(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    SpotifyMC.LOGGER.debug("[external] {}", line);
                    synchronized (sink) {
                        sink.add(line);
                        if (sink.size() > 20) sink.remove(0);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "SpotifyMC stderr");
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /** Kills the process and its children (the yt-dlp binary spawns a child process). */
    private static void kill(Process p) {
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
