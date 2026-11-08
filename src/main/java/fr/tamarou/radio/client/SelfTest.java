package fr.tamarou.radio.client;

import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.block.QueueEntry;
import fr.tamarou.radio.block.RadioBlockEntity;
import fr.tamarou.radio.media.Deezer;
import fr.tamarou.radio.media.RadioBrowser;
import fr.tamarou.radio.media.TrackInfo;
import fr.tamarou.radio.media.YouTubeAudio;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.concurrent.CompletableFuture;

/**
 * Auto-test pour le développement (tâche Gradle « runSelftest ») : pose une radio à côté du joueur,
 * joue une chanson, un disque, une radio en direct, et écrit dans le journal si le son sort.
 */
final class SelfTest {
    private static int tick;
    private static BlockPos pos;
    private static int step;
    private static TrackInfo song;
    private static RadioBrowser.Station station;
    private static java.util.List<TrackInfo> searchResults;

    /** Captures de chaque onglet pendant que la chanson joue (dans run/screenshots). */
    private static void screens(int t) {
        Minecraft mc = Minecraft.getInstance();
        String[] tabs = {"SEARCH", "NOW", "RADIOS", "MINECRAFT"};
        int base = 170;
        for (int i = 0; i < tabs.length; i++) {
            if (t == base + i * 40) {
                RadioScreen.debug(tabs[i], searchResults);
                mc.setScreen(new RadioScreen(pos));
            }
            if (t == base + i * 40 + 30) {
                String name = "autotest_" + tabs[i].toLowerCase() + ".png";
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                        msg -> RadioMod.LOGGER.info("[AUTOTEST] capture {}", name));
            }
        }
        if (t == base + tabs.length * 40) mc.setScreen(null);
        // Même chose sur la radio-cassette (posée deux blocs à gauche)
        int b2 = 345;
        String[] boomTabs = {"SEARCH", "MINECRAFT"};
        for (int i = 0; i < boomTabs.length; i++) {
            if (t == b2 + i * 40) {
                RadioScreen.debug(boomTabs[i], searchResults);
                mc.setScreen(new RadioScreen(pos.relative(mc.player.getDirection().getClockWise(), -2)));
            }
            if (t == b2 + i * 40 + 30) {
                String name = "autotest_boombox_" + boomTabs[i].toLowerCase() + ".png";
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                        msg -> RadioMod.LOGGER.info("[AUTOTEST] capture {}", name));
            }
        }
        if (t == b2 + boomTabs.length * 40) mc.setScreen(null);
    }

    static void install() {
        if (System.getenv("RADIO_SELFTEST") == null) return;
        RadioMod.LOGGER.info("[AUTOTEST] actif");
        CompletableFuture.runAsync(() -> {
            try {
                searchResults = Deezer.search("angele");
                song = YouTubeAudio.find(Deezer.search("angele balance ton quoi").getFirst());
                station = RadioBrowser.search("fip", "FR", null).getFirst();
                RadioMod.LOGGER.info("[AUTOTEST] prêt : {} / {}", song.videoId(), station.url());
            } catch (Throwable e) {
                RadioMod.LOGGER.error("[AUTOTEST] préparation ratée", e);
            }
        });
        NeoForge.EVENT_BUS.addListener(SelfTest::onTick);
    }

    private static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        // Pas de pause quand la fenêtre du test perd le focus (sinon le temps du monde s'arrête), et pas de son
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.01); // presque muet (à 0, le jeu ne joue plus rien)
        if (mc.player == null || server == null || song == null || station == null) return;
        tick++;
        if (tick == 40) {
            pos = mc.player.blockPosition().relative(mc.player.getDirection(), 2);
            server.execute(() -> {
                var level = server.overworld();
                level.setBlock(pos, RadioMod.RADIO.get().defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof RadioBlockEntity be) {
                    be.setVolume(70); // le son général du jeu est coupé pendant le test
                    be.add(new QueueEntry(QueueEntry.Kind.SONG, song.title(), song.artist(), song.coverUrl(),
                            song.durationSec(), song.videoId(), "Steve"), true);
                    be.add(new QueueEntry(QueueEntry.Kind.SONG, "Alors on danse", "Stromae", "", 206, song.videoId(), "Alex"), false);
                    be.add(new QueueEntry(QueueEntry.Kind.SONG, "Get Lucky", "Daft Punk", "", 248, song.videoId(), "Sam"), false);
                }
            });
            RadioMod.LOGGER.info("[AUTOTEST] radio posée en {}, chanson lancée", pos);
        }
        // Photo des deux modèles : de face, et tournés d'un quart de tour
        if (tick == 45) {
            var dir = mc.player.getDirection();
            var side = dir.getClockWise();
            mc.player.setXRot(28);
            mc.player.setYRot(dir.toYRot());
            server.execute(() -> {
                var level = server.overworld();
                var face = net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;
                level.setBlock(pos, level.getBlockState(pos).setValue(face, dir.getOpposite()), 3);
                level.setBlock(pos.relative(side, -2), RadioMod.BOOMBOX.get().defaultBlockState().setValue(face, dir.getOpposite()), 3);
                level.setBlock(pos.relative(side, 2), RadioMod.RADIO.get().defaultBlockState().setValue(face, side.getOpposite()), 3);
                level.setBlock(pos.relative(side, -3), RadioMod.BOOMBOX.get().defaultBlockState().setValue(face, side), 3);
            });
        }
        // Livre des recettes : ramasser un bloc musical doit débloquer les deux recettes
        if (tick == 50) server.execute(() -> server.getPlayerList().getPlayers().forEach(p ->
                p.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NOTE_BLOCK))));
        if (tick == 70) server.execute(() -> server.getPlayerList().getPlayers().forEach(p -> RadioMod.LOGGER.info(
                "[AUTOTEST] recettes débloquées : radio={} radio-cassette={}",
                p.getRecipeBook().contains(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "radio")),
                p.getRecipeBook().contains(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "boombox")))));
        // Photos des blocs sans l'interface du jeu (pour la page du mod)
        if (tick == 72 || tick == 1196) mc.options.hideGui = true;
        if (tick == 78 || tick == 1204) mc.options.hideGui = false;
        if (tick == 75) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "autotest_blocs.png", mc.getMainRenderTarget(),
                msg -> RadioMod.LOGGER.info("[AUTOTEST] capture des blocs"));
        if (tick == 60) serverDo(be -> {
            be.insertDisc(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_13));
            be.insertDisc(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_MELLOHI));
            be.insertDisc(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_PIGSTEP));
            be.add(new QueueEntry(QueueEntry.Kind.DISC, "C418 - 13", "", "", 178, "minecraft:13", "Alex"), false);
        });
        screens(tick);
        if (tick == 200) reportStart();
        if (tick == 250) serverDo(be -> {
            be.seek(120);
            RadioMod.LOGGER.info("[AUTOTEST] saut à 2:00");
        });
        if (tick == 290) serverDo(be -> RadioMod.LOGGER.info("[AUTOTEST] moment dans la chanson : {} s", be.elapsedTicks() / 20));
        if (tick == 41) {
        }
        if (tick == 300) report("chanson");
        if (tick == 305) serverDo(be -> RadioMod.LOGGER.info("[AUTOTEST] disque rangé : {}",
                be.insertDisc(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_CAT))));
        if (tick == 310) serverDo(be -> be.add(new QueueEntry(QueueEntry.Kind.DISC, "cat", "", "", 185, "minecraft:cat", "autotest"), true));
        if (tick == 420) report("disque");
        if (tick == 430) serverDo(be -> be.playStation(station.name(), station.url(), station.codec()));
        if (tick == 640) report("radio en direct");
        if (tick == 650) serverDo(be -> be.playAmbient("overworld"));
        if (tick == 760) report("ambiance");
        if (tick == 770) serverDo(be -> {
            be.resumeQueue();
            be.togglePause();
        });
        if (tick == 820) report("pause");
        portableAndSpeakers(mc, server);
        if (tick == 1400) {
            RadioMod.LOGGER.info("[AUTOTEST] fini");
            mc.stop();
        }
    }

    private static BlockPos speakerPos;
    private static int startsBefore;

    /** Radio portable (casque puis haut-parleur) et enceinte loin de sa radio. */
    private static void portableAndSpeakers(Minecraft mc, net.minecraft.server.MinecraftServer server) {
        if (tick == 840) server.execute(() -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            var stack = new net.minecraft.world.item.ItemStack(RadioMod.PORTABLE_RADIO.get());
            var be = fr.tamarou.radio.item.PortableRadioItem.open(stack, p.level(), p.blockPosition());
            be.setVolume(70);
            be.add(new QueueEntry(QueueEntry.Kind.SONG, song.title(), song.artist(), song.coverUrl(),
                    song.durationSec(), song.videoId(), "Steve"), true);
            p.getInventory().setItem(0, stack);
            p.getInventory().selected = 0;
            RadioMod.LOGGER.info("[AUTOTEST] radio portable donnée");
        });
        if (tick == 1000) RadioMod.LOGGER.info("[AUTOTEST] portable casque : son={} octets lus={} casque={}",
                RadioClient.isPlaying(RadioClient.SELF), RadioClient.bytesRead(RadioClient.SELF), headphones(mc));
        if (tick == 1005) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "autotest_portable_main.png", mc.getMainRenderTarget(),
                m -> RadioMod.LOGGER.info("[AUTOTEST] capture de la radio portable en main"));
        if (tick == 1010) {
            RadioScreen.debug("NOW", null);
            mc.setScreen(RadioScreen.portable(0));
        }
        if (tick == 1040) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "autotest_portable.png", mc.getMainRenderTarget(),
                m -> RadioMod.LOGGER.info("[AUTOTEST] capture autotest_portable.png"));
        if (tick == 1050) {
            mc.setScreen(null);
            server.execute(() -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var stack = p.getInventory().getItem(0);
                fr.tamarou.radio.item.PortableRadioItem.open(stack, p.level(), p.blockPosition()).toggleHeadphones();
            });
        }
        if (tick == 1100) RadioMod.LOGGER.info("[AUTOTEST] portable haut-parleur : son={} octets lus={} casque={}",
                RadioClient.isPlaying(RadioClient.SELF), RadioClient.bytesRead(RadioClient.SELF), headphones(mc));
        // Enceintes : on range la portable, on relance la radio et on pose une grande enceinte à 40 blocs
        if (tick == 1110) server.execute(() -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            var level = server.overworld();
            if (level.getBlockEntity(pos) instanceof RadioBlockEntity be) {
                be.setRange(24, 48);
                be.add(new QueueEntry(QueueEntry.Kind.SONG, song.title(), song.artist(), song.coverUrl(),
                        song.durationSec(), song.videoId(), "autotest"), true);
            }
            var dir = p.getDirection();
            speakerPos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.relative(dir, 40));
            level.setBlock(speakerPos, RadioMod.SPEAKER_BIG.get().defaultBlockState()
                    .setValue(fr.tamarou.radio.block.SpeakerBlock.FACING, dir.getOpposite()), 3);
            if (level.getBlockEntity(speakerPos) instanceof fr.tamarou.radio.block.SpeakerBlockEntity s) s.setLink(pos);
            BlockPos small = speakerPos.relative(dir.getClockWise(), 2);
            level.setBlock(small, RadioMod.SPEAKER.get().defaultBlockState()
                    .setValue(fr.tamarou.radio.block.SpeakerBlock.FACING, dir.getOpposite()), 3);
            if (level.getBlockEntity(small) instanceof fr.tamarou.radio.block.SpeakerBlockEntity s) s.setLink(pos);
            BlockPos center = speakerPos.relative(dir.getOpposite(), 4);
            for (var d4 : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos sp = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, center.relative(d4, 6));
                level.setBlock(sp, RadioMod.SPEAKER.get().defaultBlockState()
                        .setValue(fr.tamarou.radio.block.SpeakerBlock.FACING, d4.getOpposite()), 3);
                if (level.getBlockEntity(sp) instanceof fr.tamarou.radio.block.SpeakerBlockEntity s) s.setLink(pos);
            }
            // Le joueur se met à 4 blocs devant les enceintes, en les regardant (à 36 blocs de la radio)
            BlockPos at = speakerPos.relative(dir.getOpposite(), 5).relative(dir.getClockWise(), 1);
            p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            p.setYRot(dir.toYRot());
            p.setXRot(12);
            RadioMod.LOGGER.info("[AUTOTEST] enceintes posées en {} (radio en {})", speakerPos, pos);
        });
        if (tick == 1200) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "autotest_enceintes.png", mc.getMainRenderTarget(),
                m -> RadioMod.LOGGER.info("[AUTOTEST] capture des enceintes"));
        // Marche d'une petite enceinte à l'autre : la musique ne doit pas redémarrer
        if (tick == 1210) startsBefore = RadioClient.startCount;
        for (int k = 0; k < 4; k++) {
            if (tick == 1215 + k * 20) {
                int kk = k;
                server.execute(() -> {
                    var pl = server.getPlayerList().getPlayers().getFirst();
                    var d4 = net.minecraft.core.Direction.from2DDataValue(kk);
                    BlockPos center = speakerPos.relative(pl.getDirection().getOpposite(), 0);
                    BlockPos near = center.relative(pl.getDirection().getOpposite(), 4).relative(d4, 5);
                    pl.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
                });
            }
            if (tick == 1230 + k * 20) RadioMod.LOGGER.info("[AUTOTEST] près de l'enceinte {} : sons qui jouent={} redémarrages={} volumes={}",
                    k + 1, RadioClient.soundCount(pos), RadioClient.startCount - startsBefore, RadioClient.gains(pos));
        }
        if (tick == 1300 && mc.player != null) RadioMod.LOGGER.info(
                "[AUTOTEST] enceinte : son={} sons qui jouent={} octets lus={} distance à la radio={} blocs",
                RadioClient.isPlaying(pos), RadioClient.soundCount(pos), RadioClient.bytesRead(pos), Math.round(Math.sqrt(mc.player.distanceToSqr(pos.getCenter()))));
    }

    private static Object headphones(Minecraft mc) {
        var s = mc.player.getInventory().getItem(0);
        if (!(s.getItem() instanceof fr.tamarou.radio.item.PortableRadioItem)) return "pas de radio";
        return RadioClient.portable(RadioClient.SELF, s, mc.player).headphones();
    }

    private static void serverDo(java.util.function.Consumer<RadioBlockEntity> c) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            if (server.overworld().getBlockEntity(pos) instanceof RadioBlockEntity be) c.accept(be);
        });
    }

    /** Combien de son a été joué depuis le top départ : doit valoir le temps écoulé (pas de début sauté). */
    private static void reportStart() {
        var be = Minecraft.getInstance().level.getBlockEntity(pos) instanceof RadioBlockEntity r ? r : null;
        long served = RadioClient.bytesRead(pos) / 2;
        RadioMod.LOGGER.info("[AUTOTEST] début : {} s depuis le top, {} s de son donné", be == null ? -1 : be.elapsedTicks() / 20.0,
                served / (double) SharedPcm.RATE);
    }

    private static void report(String what) {
        boolean audible = RadioClient.anyAudible();
        boolean loading = RadioClient.isLoading(pos);
        String err = RadioClient.error(pos);
        String live = RadioClient.liveTitle(pos);
        RadioMod.LOGGER.info("[AUTOTEST] {} : son={} chargement={} erreur={} titre direct={} octets lus={}",
                what, audible, loading, err, live, RadioClient.bytesRead(pos));
    }
}
