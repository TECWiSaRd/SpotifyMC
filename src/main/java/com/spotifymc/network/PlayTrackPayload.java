package com.spotifymc.network;

import com.spotifymc.SpotifyMC;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: "this is the current track, it is {@code elapsedMs} in".
 * Sent on track change, pause/resume and when a player joins.
 *
 * @param query       what yt-dlp should stream: an http(s) URL or {@code ytsearch1:<terms>}
 * @param spotifyUri  {@code spotify:track:<id>} when the track came from Spotify, otherwise empty
 * @param durationMs  track length if the server knows it, otherwise 0
 */
public record PlayTrackPayload(long trackId, String query, String title, String spotifyUri,
                               long elapsedMs, long durationMs, boolean paused) implements CustomPacketPayload {

    public static final Type<PlayTrackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpotifyMC.MODID, "play_track"));

    public static final StreamCodec<FriendlyByteBuf, PlayTrackPayload> STREAM_CODEC =
            CustomPacketPayload.codec(PlayTrackPayload::write, PlayTrackPayload::new);

    private PlayTrackPayload(FriendlyByteBuf buf) {
        this(buf.readVarLong(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readVarLong(), buf.readVarLong(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(trackId);
        buf.writeUtf(query);
        buf.writeUtf(title);
        buf.writeUtf(spotifyUri);
        buf.writeVarLong(elapsedMs);
        buf.writeVarLong(durationMs);
        buf.writeBoolean(paused);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
