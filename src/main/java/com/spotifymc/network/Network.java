package com.spotifymc.network;

import com.spotifymc.client.ClientMusic;
import com.spotifymc.server.MusicServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    public static void register(RegisterPayloadHandlersEvent event) {
        // optional(): players without the mod can still join; they just don't hear music.
        // The version string must change whenever any payload's fields or encoding change.
        // Then a client and server on mismatched mod versions just don't exchange music
        // (the player can still join), instead of misreading each other's packets and disconnecting.
        PayloadRegistrar registrar = event.registrar("1").optional();

        // The lambdas only touch ClientMusic when they run, which only happens on a client.
        registrar.playToClient(PlayTrackPayload.TYPE, PlayTrackPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ClientMusic.onPlay(payload)));
        registrar.playToClient(StopPayload.TYPE, StopPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(ClientMusic::onStop));

        registrar.playToServer(TrackInfoPayload.TYPE, TrackInfoPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        MusicServer.onTrackInfo(player, payload);
                    }
                }));
    }

    private Network() {}
}
