package com.spotifymc.client;

import com.spotifymc.SpotifyMC;
import com.spotifymc.exaroton.ExarotonLauncher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point (never loaded on a dedicated server). */
@Mod(value = SpotifyMC.MODID, dist = Dist.CLIENT)
public final class SpotifyMCClient {
    public SpotifyMCClient(IEventBus modBus, ModContainer container) {
        // Adds a "Config" button for this mod in the Mods list.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        NeoForge.EVENT_BUS.addListener(ClientMusic::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientMusic::onSelectMusic);
        NeoForge.EVENT_BUS.addListener(ClientMusic::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(UpdateChecker::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ExarotonLauncher::registerCommands);

        // Starts the exaroton server in the background while the game is still loading.
        // (Client setup runs after configs are loaded; the constructor runs too early to read them.)
        modBus.addListener((FMLClientSetupEvent e) -> ExarotonLauncher.onGameLaunch());
        NeoForge.EVENT_BUS.addListener(ClientCommands::register);
    }
}
