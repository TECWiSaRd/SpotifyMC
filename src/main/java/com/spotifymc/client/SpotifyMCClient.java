package com.spotifymc.client;

import com.spotifymc.SpotifyMC;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point (never loaded on a dedicated server). */
@Mod(value = SpotifyMC.MODID, dist = Dist.CLIENT)
public final class SpotifyMCClient {
    public SpotifyMCClient(ModContainer container) {
        // Adds a "Config" button for this mod in the Mods list.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        NeoForge.EVENT_BUS.addListener(ClientMusic::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientMusic::onSelectMusic);
        NeoForge.EVENT_BUS.addListener(ClientMusic::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientCommands::register);
    }
}
