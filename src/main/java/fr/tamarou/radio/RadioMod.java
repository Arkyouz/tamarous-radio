package fr.tamarou.radio;

import com.mojang.logging.LogUtils;
import fr.tamarou.radio.block.RadioBlock;
import fr.tamarou.radio.block.RadioBlockEntity;
import fr.tamarou.radio.block.SpeakerBlock;
import fr.tamarou.radio.block.SpeakerBlockEntity;
import fr.tamarou.radio.item.PortableRadioItem;
import fr.tamarou.radio.item.SpeakerItem;
import net.minecraft.world.item.Item;
import fr.tamarou.radio.net.RadioNet;
import fr.tamarou.radio.server.ServerHandlers;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

@Mod(RadioMod.MODID)
public final class RadioMod {
    public static final String MODID = "radio";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);

    public static final DeferredBlock<RadioBlock> RADIO = BLOCKS.registerBlock("radio", p -> new RadioBlock(p, RadioBlock.WOOD),
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredItem<BlockItem> RADIO_ITEM = ITEMS.registerSimpleBlockItem(RADIO);
    /** La radio-cassette : même radio, autre look. */
    public static final DeferredBlock<RadioBlock> BOOMBOX = BLOCKS.registerBlock("boombox", p -> new RadioBlock(p, RadioBlock.BOOMBOX),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5f).sound(SoundType.METAL).noOcclusion());
    public static final DeferredItem<BlockItem> BOOMBOX_ITEM = ITEMS.registerSimpleBlockItem(BOOMBOX);
    /** Enceintes : un caisson en bois, et une grande enceinte de concert qui porte deux fois plus loin. */
    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.registerBlock("speaker", p -> new SpeakerBlock(p, false),
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredBlock<SpeakerBlock> SPEAKER_BIG = BLOCKS.registerBlock("speaker_big", p -> new SpeakerBlock(p, true),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2f).sound(SoundType.WOOL).noOcclusion());
    public static final DeferredItem<SpeakerItem> SPEAKER_ITEM = ITEMS.register("speaker", () -> new SpeakerItem(SPEAKER.get(), new Item.Properties()));
    public static final DeferredItem<SpeakerItem> SPEAKER_BIG_ITEM = ITEMS.register("speaker_big", () -> new SpeakerItem(SPEAKER_BIG.get(), new Item.Properties()));
    /** La radio portable : un objet, pas un bloc. */
    public static final DeferredItem<PortableRadioItem> PORTABLE_RADIO = ITEMS.register("portable_radio",
            () -> new PortableRadioItem(new Item.Properties().stacksTo(1)));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadioBlockEntity>> RADIO_BE =
            BLOCK_ENTITIES.register("radio", () -> BlockEntityType.Builder.of(RadioBlockEntity::new, RADIO.get(), BOOMBOX.get()).build(null));
    /** Le son « porteur » des radios : le vrai son est fourni par le mod au moment de jouer. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RADIO_STREAM = SOUNDS.register("radio_stream",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "radio_stream")));

    private static final Set<RadioBlockEntity> CLIENT_RADIOS = new HashSet<>();
    private static final Set<SpeakerBlockEntity> CLIENT_SPEAKERS = new HashSet<>();
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpeakerBlockEntity>> SPEAKER_BE =
            BLOCK_ENTITIES.register("speaker", () -> BlockEntityType.Builder.of(SpeakerBlockEntity::new, SPEAKER.get(), SPEAKER_BIG.get()).build(null));

    /** Le côté client se branche ici pour recevoir les messages du serveur. */
    public static Consumer<RadioNet.ServerInfo> onServerInfo = m -> {};

    public RadioMod(IEventBus bus, ModContainer container) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        SOUNDS.register(bus);
        container.registerConfig(ModConfig.Type.CLIENT, RadioConfig.CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, RadioConfig.SERVER_SPEC);
        bus.addListener(RadioMod::registerPayloads);
        bus.addListener(RadioMod::creativeTab);
        NeoForge.EVENT_BUS.addListener(RadioMod::onPlace);
        NeoForge.EVENT_BUS.addListener(RadioMod::onLogin);
    }

    /** Radios chargées chez le joueur (côté client seulement). */
    public static Set<RadioBlockEntity> clientRadios() {
        return CLIENT_RADIOS;
    }

    /** Enceintes chargées chez le joueur (côté client seulement). */
    public static Set<SpeakerBlockEntity> clientSpeakers() {
        return CLIENT_SPEAKERS;
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToServer(RadioNet.AddEntry.TYPE, RadioNet.AddEntry.CODEC, ServerHandlers::addEntry);
        r.playToServer(RadioNet.Control.TYPE, RadioNet.Control.CODEC, ServerHandlers::control);
        r.playToClient(RadioNet.ServerInfo.TYPE, RadioNet.ServerInfo.CODEC, (m, ctx) -> onServerInfo.accept(m));
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS || event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(RADIO_ITEM.get());
            event.accept(BOOMBOX_ITEM.get());
            event.accept(PORTABLE_RADIO.get());
            event.accept(SPEAKER_ITEM.get());
            event.accept(SPEAKER_BIG_ITEM.get());
        }
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof RadioBlock) || !(event.getEntity() instanceof ServerPlayer p)) return;
        if (RadioConfig.WHO_CAN_PLACE.get() != RadioConfig.Who.EVERYONE && !p.hasPermissions(2)) {
            event.setCanceled(true);
            p.displayClientMessage(Component.translatable("radio.msg.no_place"), true);
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            PacketDistributor.sendToPlayer(p, new RadioNet.ServerInfo(RadioConfig.ALLOW_SEARCH.get(),
                    RadioConfig.ALLOW_STATIONS.get(), RadioConfig.MAX_RANGE.get()));
        }
    }
}
