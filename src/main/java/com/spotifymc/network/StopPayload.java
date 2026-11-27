package com.spotifymc.network;

import com.spotifymc.SpotifyMC;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → client: nothing is playing any more. */
public record StopPayload() implements CustomPacketPayload {
    public static final StopPayload INSTANCE = new StopPayload();
    public static final Type<StopPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpotifyMC.MODID, "stop"));
    public static final StreamCodec<ByteBuf, StopPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
