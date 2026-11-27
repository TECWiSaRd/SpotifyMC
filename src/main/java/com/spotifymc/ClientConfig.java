package com.spotifymc;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue VOLUME;
    public static final ModConfigSpec.BooleanValue MUTE_VANILLA_MUSIC;

    public static final ModConfigSpec.ConfigValue<String> YTDLP_PATH;
    public static final ModConfigSpec.ConfigValue<String> FFMPEG_PATH;
    public static final ModConfigSpec.BooleanValue AUTO_DOWNLOAD_YTDLP;
    public static final ModConfigSpec.ConfigValue<String> YTDLP_EXTRA_ARGS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_HOSTS;

    public static final ModConfigSpec.ConfigValue<String> SPOTIFY_CLIENT_ID;
    public static final ModConfigSpec.IntValue SPOTIFY_REDIRECT_PORT;
    public static final ModConfigSpec.BooleanValue PREFER_SPOTIFY_APP;

    public static final ModConfigSpec.BooleanValue CHECK_FOR_UPDATES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("playback");
        ENABLED = b.comment("Play server music on this client at all.")
                .define("enabled", true);
        VOLUME = b.comment("Music volume in percent (0-200). Over 100 boosts quiet tracks.",
                        "Also scaled by Minecraft's Master and Jukebox/Note Blocks sliders.")
                .defineInRange("volume", 100, 0, 200);
        MUTE_VANILLA_MUSIC = b.comment("Silence Minecraft's own background music while a track is playing.")
                .define("muteVanillaMusic", true);
        b.pop();

        b.push("tools");
        YTDLP_PATH = b.comment("Path to yt-dlp. Leave empty to auto-detect (mod folder, PATH, Homebrew, etc).")
                .define("ytDlpPath", "");
        FFMPEG_PATH = b.comment("Path to ffmpeg. Leave empty to auto-detect.")
                .define("ffmpegPath", "");
        AUTO_DOWNLOAD_YTDLP = b.comment("Download the official yt-dlp release from GitHub into .minecraft/spotifymc/bin",
                        "if it isn't installed, and refresh it weekly (YouTube changes break old versions).")
                .define("autoDownloadYtDlp", true);
        YTDLP_EXTRA_ARGS = b.comment("Extra arguments for yt-dlp, e.g. \"--cookies-from-browser firefox\" if YouTube",
                        "asks you to sign in.")
                .define("ytDlpExtraArgs", "");
        ALLOWED_HOSTS = b.comment("Servers can only make your client stream from these sites (and their subdomains).",
                        "Add \"*\" to allow any site yt-dlp supports.")
                .defineListAllowEmpty("allowedHosts",
                        List.of("youtube.com", "youtu.be", "soundcloud.com", "bandcamp.com", "vimeo.com"),
                        () -> "example.com",
                        o -> o instanceof String s && !s.isBlank());
        b.pop();

        b.push("spotify");
        SPOTIFY_CLIENT_ID = b.comment("Client ID of your Spotify app (https://developer.spotify.com/dashboard).",
                        "Add http://127.0.0.1:<redirectPort>/callback as a Redirect URI in the app settings.",
                        "Needed only if you want Spotify tracks to play through your own Spotify app (Premium).")
                .define("clientId", "");
        SPOTIFY_REDIRECT_PORT = b.comment("Local port used for the Spotify login redirect.")
                .defineInRange("redirectPort", 43821, 1024, 65535);
        PREFER_SPOTIFY_APP = b.comment("When your Spotify account is linked, play Spotify tracks through your Spotify",
                        "app instead of the in-game YouTube match.")
                .define("preferSpotifyApp", true);
        b.pop();

        b.push("updates");
        CHECK_FOR_UPDATES = b.comment("When joining a world, check GitHub for a newer SpotifyMC and offer to install it.")
                .define("checkForUpdates", true);
        b.pop();

        SPEC = b.build();
    }

    private ClientConfig() {}
}
