package fr.tamarou.radio.client;

import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.block.RadioBlock;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Tout ce qui n'existe que chez le joueur : le son, l'écran, les réglages. */
@Mod(value = RadioMod.MODID, dist = Dist.CLIENT)
public final class RadioModClient {
    public RadioModClient(IEventBus bus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        RadioBlock.openScreen = pos -> Minecraft.getInstance().setScreen(new RadioScreen(pos));
        fr.tamarou.radio.item.PortableRadioItem.openScreen = slot -> Minecraft.getInstance().setScreen(RadioScreen.portable(slot));
        RadioMod.onServerInfo = m -> RadioClient.serverInfo = m;
        NeoForge.EVENT_BUS.addListener(RadioClient::tick);
        NeoForge.EVENT_BUS.addListener(RadioClient::onLogout);
        NeoForge.EVENT_BUS.addListener(RadioClient::onSelectMusic);
        SelfTest.install();
    }
}
