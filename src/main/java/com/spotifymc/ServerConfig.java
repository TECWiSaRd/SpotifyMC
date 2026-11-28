package com.spotifymc;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ServerConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue CONTROL_PERMISSION_LEVEL;
    public static final ModConfigSpec.IntValue MAX_QUEUE_LENGTH;
    public static final ModConfigSpec.IntValue MAX_PLAYLIST_TRACKS;
    public static final ModConfigSpec.IntValue LOAD_TIMEOUT_SECONDS;
    public static final ModConfigSpec.BooleanValue AUTO_UPDATE;

    public static final ModConfigSpec.BooleanValue MOTD_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MOTD_PLAYING_FORMAT;
    public static final ModConfigSpec.ConfigValue<String> MOTD_PAUSED_FORMAT;
    public static final ModConfigSpec.IntValue MOTD_MAX_TITLE_LENGTH;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        CONTROL_PERMISSION_LEVEL = b
                .comment("Permission level needed to use /music play, skip, stop, pause, resume, shuffle.",
                        "0 = everyone, 2 = operators (cheats), 4 = server owner.")
                .defineInRange("controlPermissionLevel", 0, 0, 4);
        MAX_QUEUE_LENGTH = b
                .comment("Maximum number of tracks waiting in the queue.")
                .defineInRange("maxQueueLength", 200, 1, 10000);
        MAX_PLAYLIST_TRACKS = b
                .comment("Maximum number of tracks added from a single Spotify album/playlist.")
                .defineInRange("maxPlaylistTracks", 100, 1, 1000);
        LOAD_TIMEOUT_SECONDS = b
                .comment("If no client reports a track length within this many seconds (and the length",
                        "isn't otherwise known), the track is assumed unplayable and skipped.")
                .defineInRange("loadTimeoutSeconds", 45, 5, 600);
        AUTO_UPDATE = b
                .comment("Dedicated servers only: check GitHub for a newer SpotifyMC when the server starts and",
                        "every 12 hours, and install it automatically (checksum-verified). It takes effect after",
                        "the next restart; online operators are told. Set to false to update by hand.")
                .define("autoUpdate", true);

        b.comment("Shows the current song as the second line of the server's MOTD (the text under its",
                "name in the multiplayer list). Your own first line is kept, and your original MOTD comes",
                "back as soon as the music stops. server.properties is never changed.").push("motd");
        MOTD_ENABLED = b.comment("Show the current song in the MOTD.")
                .define("showNowPlaying", true);
        MOTD_PLAYING_FORMAT = b.comment("Second MOTD line while a song plays. {song} is the title.",
                        "Colors: &a green, &f white, &7 gray, &e yellow, &b aqua, &l bold, etc.")
                .define("playingFormat", "&a♪ Now playing: &f{song}");
        MOTD_PAUSED_FORMAT = b.comment("Second MOTD line while paused. Leave empty to show your normal MOTD while paused.")
                .define("pausedFormat", "&7⏸ Paused: {song}");
        MOTD_MAX_TITLE_LENGTH = b.comment("Song titles longer than this are shortened with \"…\" so the line fits the server list.")
                .defineInRange("maxTitleLength", 40, 10, 200);
        b.pop();

        SPEC = b.build();
    }

    public static int controlLevel() {
        try {
            return CONTROL_PERMISSION_LEVEL.get();
        } catch (IllegalStateException notLoaded) {
            return 0;
        }
    }

    private ServerConfig() {}
}
