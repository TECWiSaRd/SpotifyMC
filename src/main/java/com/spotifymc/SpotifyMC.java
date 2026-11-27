package com.spotifymc;

import com.mojang.logging.LogUtils;
import com.spotifymc.network.Network;
import com.spotifymc.server.MusicCommands;
import com.spotifymc.server.MusicServer;
import com.spotifymc.server.ServerUpdater;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Common entry point. The server owns the queue and the playback clock; every client
 * that has the mod installed streams the audio locally and stays in sync with it.
 */
@Mod(SpotifyMC.MODID)
public final class SpotifyMC {
    public static final String MODID = "spotifymc";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SpotifyMC(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        modBus.addListener(Network::register);

        NeoForge.EVENT_BUS.addListener(MusicCommands::register);
        NeoForge.EVENT_BUS.addListener(MusicServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(MusicServer::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(MusicServer::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerUpdater::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerUpdater::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerUpdater::onPlayerLogin);
    }
}
