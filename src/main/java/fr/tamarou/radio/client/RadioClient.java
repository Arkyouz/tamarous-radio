package fr.tamarou.radio.client;

import fr.tamarou.radio.RadioConfig;
import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.audio.ChunkedHttpStream;
import fr.tamarou.radio.audio.IcyInputStream;
import fr.tamarou.radio.audio.Mp3Source;
import fr.tamarou.radio.audio.PcmSource;
import fr.tamarou.radio.audio.WebmOpusSource;
import fr.tamarou.radio.block.QueueEntry;
import fr.tamarou.radio.block.RadioBlockEntity;
import fr.tamarou.radio.block.SpeakerBlockEntity;
import fr.tamarou.radio.item.PortableRadioItem;
import fr.tamarou.radio.media.Http;
import fr.tamarou.radio.media.YouTubeAudio;
import fr.tamarou.radio.net.RadioNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Côté joueur : décide quelles radios on entend (radios posées et leurs enceintes, sa radio portable,
 * celles des autres joueurs), lance et arrête leur son, annonce les nouvelles chansons dans la barre
 * au-dessus de l'inventaire.
 */
public final class RadioClient {
    /** Clé de sa propre radio portable (les radios posées ont pour clé leur position). */
    public static final Object SELF = "self";

    private static final class Playing {
        String key;
        SharedPcm shared;
        final List<RadioSound> sounds = new ArrayList<>();
        /** L'émetteur sur lequel est posé chaque son (il change quand on se déplace, sans couper la musique). */
        final List<String> slotOn = new ArrayList<>();
        /** Là où chaque son doit aller (il y va après un fondu). */
        final List<String> slotGoal = new ArrayList<>();
        float[] fade = new float[0];
        int startedTick;
    }

    /** Un endroit d'où sort le son : la radio, une enceinte, ou le joueur qui porte la radio portable. */
    private record Emitter(String sig, Vec3 at, int range) {}

    /** Une radio qu'on pourrait entendre en ce moment, et tous les endroits d'où sort son son. */
    private record Listen(Object id, RadioBlockEntity be, List<Emitter> emitters, boolean relative) {}

    /**
     * Au plus 4 sons par radio : le jeu ne joue que 8 sons en flux en tout. Ces sons ne redémarrent jamais
     * quand on bouge : ils se posent à chaque instant sur les enceintes qu'on entend le plus fort.
     */
    private static final int MAX_SLOTS = 4;

    private static final Map<Object, Playing> PLAYING = new HashMap<>();
    /** Dernière chanson vue pour chaque radio, pour n'annoncer que les nouvelles. */
    private static final Map<Object, Integer> LAST_PLAY_ID = new HashMap<>();
    private static final Map<Object, String> LIVE_TITLE = new ConcurrentHashMap<>();
    private static final Map<Object, String> ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, Long> ERROR_UNTIL = new HashMap<>();
    private static final Map<String, CompletableFuture<String>> AUDIO_URLS = new ConcurrentHashMap<>();
    private static final Map<String, Long> AUDIO_URL_TIME = new ConcurrentHashMap<>();
    /** Radios portables lues depuis les objets (on ne les relit que si l'objet a changé). */
    private static final Map<Object, CustomData> PORTABLE_DATA = new HashMap<>();
    private static final Map<Object, RadioBlockEntity> PORTABLE_BE = new HashMap<>();

    /** Après l'arrêt d'une radio, on attend un peu avant de la relancer : le jeu libère ses sons au tick suivant. */
    private static final Map<Object, Integer> RESTART_AT = new HashMap<>();
    /** Nombre de démarrages de son (pour l'auto-test : marcher entre les enceintes ne doit rien relancer). */
    public static int startCount;

    public static RadioNet.ServerInfo serverInfo = new RadioNet.ServerInfo(true, true, 48);
    private static int ticks;

    private RadioClient() {}

    // ---------- Infos pour l'écran ----------

    public static String liveTitle(Object key) {
        return LIVE_TITLE.get(key);
    }

    public static String error(Object key) {
        return ERRORS.get(key);
    }

    public static long bytesRead(Object key) {
        Playing p = PLAYING.get(key);
        return p == null ? -1 : p.shared.served() * 2;
    }

    public static boolean isLoading(Object key) {
        Playing p = PLAYING.get(key);
        return p != null && ERRORS.get(key) == null && p.shared.served() == 0;
    }

    public static boolean isPlaying(Object key) {
        return PLAYING.containsKey(key);
    }

    /** Nombre de sons (radio + enceintes) qui jouent pour cette radio. */
    public static int soundCount(Object key) {
        Playing p = PLAYING.get(key);
        return p == null ? 0 : (int) p.sounds.stream().filter(s -> Minecraft.getInstance().getSoundManager().isActive(s)).count();
    }

    /** La radio rangée dans un objet radio portable (relue seulement quand l'objet change). */
    public static RadioBlockEntity portable(Object id, ItemStack stack, Player holder) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        RadioBlockEntity be = PORTABLE_BE.get(id);
        if (be == null || !java.util.Objects.equals(PORTABLE_DATA.get(id), data) || be.getLevel() != holder.level()) {
            be = PortableRadioItem.load(stack, holder.level(), holder.blockPosition());
            PORTABLE_BE.put(id, be);
            PORTABLE_DATA.put(id, data);
        }
        return be;
    }

    // ---------- Boucle ----------

    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (mc.level == null || mc.player == null) {
            if (!PLAYING.isEmpty()) stopAll();
            return;
        }
        List<Listen> wanted = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        Vec3 me = mc.player.getEyePosition();

        // 1. Radios posées : le son sort de la radio ET de chacune de ses enceintes, toutes en même temps
        Map<BlockPos, List<SpeakerBlockEntity>> speakers = new HashMap<>();
        for (SpeakerBlockEntity s : RadioMod.clientSpeakers()) {
            if (!s.isRemoved() && s.getLevel() == mc.level && s.link() != null) speakers.computeIfAbsent(s.link(), k -> new ArrayList<>()).add(s);
        }
        for (RadioBlockEntity be : new ArrayList<>(RadioMod.clientRadios())) {
            if (be.isRemoved() || be.getLevel() != mc.level) continue;
            BlockPos pos = be.getBlockPos();
            seen.add(pos);
            int range = be.range();
            List<Emitter> all = new ArrayList<>();
            all.add(new Emitter("r" + pos.asLong(), Vec3.atCenterOf(pos), range));
            for (SpeakerBlockEntity s : speakers.getOrDefault(pos, List.of())) {
                int r = s.big() ? Math.min(128, range * 2) : range;
                all.add(new Emitter("s" + s.getBlockPos().asLong() + ":" + r, Vec3.atCenterOf(s.getBlockPos()), r));
            }
            boolean near = all.stream().anyMatch(e -> me.distanceTo(e.at) <= e.range + 4);
            if (!near) {
                LAST_PLAY_ID.put(pos, be.playId()); // trop loin de tout : on n'entend rien (ni annonce en arrivant)
                continue;
            }
            wanted.add(new Listen(pos, be, all, false));
        }

        // 2. Sa radio portable (la première trouvée dans l'inventaire)
        ItemStack own = ItemStack.EMPTY;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.getItem() instanceof PortableRadioItem) {
                own = s;
                break;
            }
        }
        if (!own.isEmpty()) {
            seen.add(SELF);
            RadioBlockEntity be = portable(SELF, own, mc.player);
            wanted.add(new Listen(SELF, be, List.of(new Emitter("me", me, be.range())), be.headphones()));
        }

        // 3. Les radios portables des autres, en mode haut-parleur et tenues en main
        for (Player other : mc.level.players()) {
            if (other == mc.player) continue;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack s = other.getItemInHand(hand);
                if (!(s.getItem() instanceof PortableRadioItem)) continue;
                Object id = other.getUUID();
                RadioBlockEntity be = portable(id, s, other);
                if (be.headphones()) break;
                Vec3 at = other.getEyePosition();
                if (me.distanceTo(at) > be.range() + 4) break;
                seen.add(id);
                wanted.add(new Listen(id, be, List.of(new Emitter("p", at, be.range())), false));
                break;
            }
        }

        for (Listen l : wanted) update(l);
        // Radios disparues (cassées, déchargées, trop loin, portable rangée ou lâchée)
        for (Object id : new ArrayList<>(PLAYING.keySet())) {
            if (wanted.stream().noneMatch(l -> l.id.equals(id))) stop(id);
        }
        LAST_PLAY_ID.keySet().retainAll(seen);
        PORTABLE_BE.keySet().retainAll(seen);
        PORTABLE_DATA.keySet().retainAll(seen);
    }

    private static void update(Listen l) {
        Minecraft mc = Minecraft.getInstance();
        RadioBlockEntity be = l.be;
        boolean want = RadioConfig.HEAR_RADIOS.get() && be.isPlaying();
        // La clé ne dépend pas de l'endroit où l'on est : on ne redémarre que si la chanson change,
        // ou si le nombre de sons / la plus grande portée changent (enceinte posée ou cassée)
        int slots = Math.min(MAX_SLOTS, l.emitters.size());
        int maxRange = 1;
        for (Emitter e : l.emitters) maxRange = Math.max(maxRange, e.range);
        String key = be.mode() + "#" + be.playId() + "#" + be.seekId() + "#" + l.relative + "#" + slots + "#" + maxRange;
        Playing p = PLAYING.get(l.id);

        // Annonce : seulement quand une nouvelle chanson commence près de nous
        Integer last = LAST_PLAY_ID.put(l.id, be.playId());
        if (last != null && last != be.playId() && want) announce(be);

        if (!want) {
            if (p != null) stop(l.id);
            return;
        }
        boolean dead = p != null && ticks - p.startedTick > 60
                && p.sounds.stream().noneMatch(s -> mc.getSoundManager().isActive(s));
        if (p != null && (!p.key.equals(key) || (dead && shouldRestart(be)))) {
            stop(l.id);
            RESTART_AT.put(l.id, ticks + 3);
            return;
        }
        if (p == null) {
            if (ERROR_UNTIL.getOrDefault(key, 0L) > ticks || RESTART_AT.getOrDefault(l.id, 0) > ticks) return;
            p = start(l, key, slots, maxRange);
        }
        place(p, l, maxRange);
    }

    /**
     * Pose les sons sur les émetteurs qu'on entend le plus fort. Un son reste sur son émetteur tant qu'aucun
     * autre ne s'entend nettement mieux (sinon il ferait des allers-retours entre deux enceintes presque à la
     * même distance). Quand il change d'émetteur, il baisse en douceur, change de place, puis remonte.
     */
    private static void place(Playing p, Listen l, int maxRange) {
        Vec3 me = Minecraft.getInstance().player.getEyePosition();
        int n = p.sounds.size();
        Map<String, Emitter> bySig = new HashMap<>();
        for (Emitter e : l.emitters) bySig.put(e.sig, e);

        // 1. Les sons dont l'émetteur a disparu, ou pas encore posés, prennent le meilleur émetteur libre
        for (int i = 0; i < n; i++) {
            String goal = p.slotGoal.get(i);
            if (!goal.isEmpty() && bySig.containsKey(goal)) continue;
            Emitter free = bestFree(p, l, me, null);
            p.slotGoal.set(i, free == null ? "" : free.sig);
            if (p.slotOn.get(i).isEmpty() && free != null) {
                p.slotOn.set(i, free.sig); // premier placement : tout de suite, sans fondu
                p.fade[i] = 1;
            }
        }
        // 2. Un seul changement à la fois : le son le moins entendu va sur un émetteur libre bien meilleur
        Emitter better = bestFree(p, l, me, null);
        if (better != null) {
            int worst = -1;
            double worstScore = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Emitter e = bySig.get(p.slotGoal.get(i));
                double s = e == null ? -1 : score(l, e, me);
                if (s < worstScore) {
                    worstScore = s;
                    worst = i;
                }
            }
            if (worst >= 0 && score(l, better, me) > worstScore + 0.08) p.slotGoal.set(worst, better.sig);
        }
        // 3. Fondu : le son baisse avant de changer de place, puis remonte (environ un tiers de seconde)
        for (int i = 0; i < n; i++) {
            String goal = p.slotGoal.get(i);
            if (!goal.equals(p.slotOn.get(i))) {
                p.fade[i] = Math.max(0, p.fade[i] - 0.34f);
                if (p.fade[i] == 0) p.slotOn.set(i, goal);
            } else {
                p.fade[i] = Math.min(1, p.fade[i] + 0.34f);
            }
            Emitter e = bySig.get(p.slotOn.get(i));
            if (e == null) {
                p.sounds.get(i).follow(l.be, l.emitters.getFirst().at, 0); // son en trop : muet
                continue;
            }
            p.sounds.get(i).follow(l.be, e.at, p.fade[i] * gainFactor(l, e, me.distanceTo(e.at), maxRange));
        }
    }

    /** Ce qu'on entend d'un émetteur (de 0 à 1), pour choisir où poser les sons. */
    private static double score(Listen l, Emitter e, Vec3 me) {
        double d = me.distanceTo(e.at);
        double s = Math.max(0, 1 - d / e.range);
        return l.emitters.size() > 1 ? s / (1 + d / 4) : s;
    }

    /** Le meilleur émetteur qui n'a pas encore de son (ni en place, ni en route). */
    private static Emitter bestFree(Playing p, Listen l, Vec3 me, Emitter skip) {
        Emitter best = null;
        double bestScore = -1;
        for (Emitter e : l.emitters) {
            if (e == skip || p.slotGoal.contains(e.sig) || p.slotOn.contains(e.sig)) continue;
            double s = score(l, e, me);
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        return best;
    }
    /**
     * Correction du volume d'un son. Le moteur du jeu baisse le son en ligne droite jusqu'à la plus grande
     * portée ; on veut à la place :
     * <ul>
     *   <li>radio seule : en ligne droite jusqu'à sa portée (comme avant) ;</li>
     *   <li>radio avec enceintes : le son baisse vite autour de chaque émetteur (comme un vrai haut-parleur),
     *   pour que l'enceinte dont on est près passe nettement devant les autres, puis s'éteint à la portée.</li>
     * </ul>
     */
    private static float gainFactor(Listen l, Emitter e, double d, int maxRange) {
        if (l.relative) return 1;
        double engine = Math.max(0.0001, 1 - d / maxRange);
        double wanted = Math.max(0, 1 - d / e.range);
        if (l.emitters.size() > 1) wanted /= 1 + d / 4;
        return (float) Math.max(0, Math.min(1, wanted / engine));
    }

    /** Pour l'auto-test : le volume voulu de chaque son de cette radio (proche → loin). */
    public static String gains(Object key) {
        Playing p = PLAYING.get(key);
        if (p == null || Minecraft.getInstance().player == null) return "-";
        Vec3 me = Minecraft.getInstance().player.getEyePosition();
        StringBuilder sb = new StringBuilder();
        for (RadioSound s : p.sounds) {
            double d = me.distanceTo(new Vec3(s.getX(), s.getY(), s.getZ()));
            double engine = Math.max(0, 1 - d / s.attenuation());
            sb.append(String.format(java.util.Locale.ROOT, "%.0fb=%.0f%% ", d, 100 * engine * s.factor()));
        }
        return sb.toString().trim();
    }
    /** Un son qui s'est arrêté tout seul : on le relance, sauf si c'est juste la fin de la chanson. */
    private static boolean shouldRestart(RadioBlockEntity be) {
        QueueEntry cur = be.current();
        if (be.mode() == RadioBlockEntity.Mode.QUEUE && cur != null) {
            return be.elapsedTicks() < (cur.durationSec() - 3) * 20L;
        }
        return true; // radio en direct ou ambiance : on reprend
    }

    private static void announce(RadioBlockEntity be) {
        if (!RadioConfig.SHOW_NOW_PLAYING.get()) return;
        String text = switch (be.mode()) {
            case QUEUE -> be.current() == null ? null : be.current().display();
            case STATION -> be.stationName();
            default -> null;
        };
        if (text != null && !text.isEmpty()) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("radio.now_playing", text), true);
        }
    }

    private static void announceLive(Object id, String title) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            String old = LIVE_TITLE.put(id, title);
            if (old != null && !old.equals(title) && RadioConfig.SHOW_NOW_PLAYING.get() && PLAYING.containsKey(id)) {
                mc.gui.setOverlayMessage(Component.translatable("radio.now_playing", title), true);
            }
        });
    }

    private static Playing start(Listen l, String key, int slots, int maxRange) {
        RadioBlockEntity be = l.be;
        Object id = l.id;
        ERRORS.remove(id);
        LIVE_TITLE.remove(id);
        Playing p = new Playing();
        p.key = key;
        p.startedTick = ticks;
        RadioBlockEntity.Mode mode = be.mode();
        QueueEntry entry = be.current();
        String stationUrl = be.stationUrl(), codec = be.stationCodec(), ambient = be.ambient();
        long startTick = be.startTick(), holdUntil = be.holdUntil();
        boolean paused = be.paused();
        // Où en est la chanson, en millisecondes. Négatif : le top départ n'est pas encore donné
        // (le serveur laisse quelques secondes à chacun pour aller chercher le son).
        java.util.function.LongSupplier offsetMs = () -> {
            var level = Minecraft.getInstance().level;
            if (level == null || paused) return 0;
            return (Math.max(level.getGameTime(), holdUntil) - startTick) * 50L;
        };
        // Pour une chanson ou un disque, le son préparé attend le top départ en silence
        java.util.function.LongSupplier untilStart = mode == RadioBlockEntity.Mode.QUEUE && !paused ? () -> {
            var level = Minecraft.getInstance().level;
            return level == null ? 0 : (holdUntil - level.getGameTime()) * 50L;
        } : () -> 0;
        p.shared = new SharedPcm(String.valueOf(id), untilStart, () -> switch (mode) {
            case QUEUE -> openEntry(entry, offsetMs);
            case STATION -> openStation(id, stationUrl, codec);
            case AMBIENT -> new AmbientSource(ambient);
            case OFF -> throw new IOException("Éteinte");
        }, msg -> {
            ERRORS.put(id, msg);
            Minecraft.getInstance().execute(() -> ERROR_UNTIL.put(key, (long) ticks + 200));
        });
        // Tous les lecteurs d'abord, puis tous les sons : ils démarrent ensemble et restent calés
        List<SharedPcm.Reader> readers = new ArrayList<>();
        for (int i = 0; i < slots; i++) readers.add(p.shared.reader());
        for (int i = 0; i < slots; i++) {
            p.sounds.add(new RadioSound(be, readers.get(i), l.emitters.get(Math.min(i, l.emitters.size() - 1)).at, maxRange, l.relative));
            p.slotOn.add("");
            p.slotGoal.add("");
        }
        p.fade = new float[slots];
        PLAYING.put(id, p);
        startCount++;
        for (RadioSound s : p.sounds) Minecraft.getInstance().getSoundManager().play(s);
        return p;
    }

    private static void stop(Object id) {
        Playing p = PLAYING.remove(id);
        if (p != null) {
            for (RadioSound s : p.sounds) Minecraft.getInstance().getSoundManager().stop(s);
            p.shared.close();
        }
        LIVE_TITLE.remove(id);
    }

    public static void stopAll() {
        for (Object id : new ArrayList<>(PLAYING.keySet())) stop(id);
        LAST_PLAY_ID.clear();
    }

    public static boolean anyAudible() {
        return !PLAYING.isEmpty();
    }

    // ---------- Ouverture des sources (dans le fil de décodage) ----------

    private static PcmSource openEntry(QueueEntry e, java.util.function.LongSupplier offsetMs) throws Exception {
        if (e == null) throw new IOException("Rien à jouer");
        switch (e.kind()) {
            case SONG -> {
                String url = audioUrl(e.ref());
                long skip = Math.max(0, offsetMs.getAsLong());
                RadioMod.LOGGER.debug("Chanson prête : {}, départ à {} ms", e.display(), skip);
                return new WebmOpusSource(new ChunkedHttpStream(url), skip);
            }
            case DISC -> {
                var level = Minecraft.getInstance().level;
                if (level == null) throw new IOException("Pas de monde");
                ResourceLocation rid = ResourceLocation.parse(e.ref());
                JukeboxSong song = level.registryAccess().registryOrThrow(Registries.JUKEBOX_SONG).get(rid);
                if (song == null) throw new IOException("Disque inconnu");
                RadioMod.LOGGER.debug("Disque prêt : {}, départ à {} ms", e.display(), Math.max(0, offsetMs.getAsLong()));
                return new OggSource(openSoundFile(song.soundEvent().value().getLocation()), Math.max(0, offsetMs.getAsLong()));
            }
        }
        throw new IOException("?");
    }

    private static PcmSource openStation(Object id, String url, String codec) throws IOException {
        var r = Http.open(url, Map.of("Icy-MetaData", "1"));
        int metaInt = r.headers().firstValue("icy-metaint").map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }).orElse(0);
        InputStream in = new IcyInputStream(r.body(), metaInt, t -> announceLive(id, t));
        String type = r.headers().firstValue("content-type").orElse("").toLowerCase(java.util.Locale.ROOT);
        if (type.contains("ogg") || "OGG".equals(codec)) return new OggSource(in, 0);
        if (type.contains("aac") || type.contains("mp4")) throw new IOException("Format AAC non pris en charge");
        return new Mp3Source(in, 0);
    }

    /** Ouvre le fichier .ogg d'un son du jeu (événement sonore → un des fichiers au hasard). */
    static InputStream openSoundFile(ResourceLocation event) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        WeighedSoundEvents w = mc.getSoundManager().getSoundEvent(event);
        if (w == null) throw new IOException("Son inconnu : " + event);
        Sound s = w.getSound(RandomSource.create());
        return mc.getResourceManager().open(s.getPath());
    }

    /** Commence à chercher le son d'une chanson tout de suite (le joueur qui l'ajoute gagne du temps). */
    public static void prefetch(String videoId) {
        CompletableFuture.runAsync(() -> {
            try {
                audioUrl(videoId);
            } catch (Throwable ignored) {
            }
        });
    }

    /** L'adresse du son d'une vidéo, gardée deux heures (YouTube la fait expirer). */
    private static String audioUrl(String videoId) throws Exception {
        Long t = AUDIO_URL_TIME.get(videoId);
        if (t == null || System.currentTimeMillis() - t > 2 * 3600_000L) {
            AUDIO_URLS.remove(videoId);
            AUDIO_URL_TIME.put(videoId, System.currentTimeMillis());
        }
        CompletableFuture<String> f = AUDIO_URLS.computeIfAbsent(videoId, vid -> CompletableFuture.supplyAsync(() -> {
            try {
                return YouTubeAudio.audioUrl(vid);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }));
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Throwable e) {
            AUDIO_URLS.remove(videoId);
            throw e;
        }
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAll();
        ERRORS.clear();
        ERROR_UNTIL.clear();
        PORTABLE_BE.clear();
        PORTABLE_DATA.clear();
        RadioMod.clientRadios().clear();
        RadioMod.clientSpeakers().clear();
    }

    /** Coupe la musique du jeu quand on entend une radio (réglable). */
    public static void onSelectMusic(SelectMusicEvent event) {
        if (RadioConfig.MUTE_MUSIC_NEAR_RADIO.get() && anyAudible()) event.setMusic(null);
    }
}
