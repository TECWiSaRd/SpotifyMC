package com.spotifymc.network;

import com.spotifymc.SpotifyMC;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a client resolved the track and tells the server its real title and
 * length, so the server knows when to advance the queue.
 * {@code durationMs}: positive = length, 0 = "started playing, length unknown", negative = live stream.
 */
public record TrackInfoPayload(long trackId, String title, long durationMs) implements CustomPacketPayload {
    public static final Type<TrackInfoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpotifyMC.MODID, "track_info"));

    public static final StreamCodec<FriendlyByteBuf, TrackInfoPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TrackInfoPayload::write, TrackInfoPayload::new);

    private TrackInfoPayload(FriendlyByteBuf buf) {
        this(buf.readVarLong(), buf.readUtf(512), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(trackId);
        buf.writeUtf(title, 512);
        buf.writeLong(durationMs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
