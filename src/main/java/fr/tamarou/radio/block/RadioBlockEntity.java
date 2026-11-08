package fr.tamarou.radio.block;

import fr.tamarou.radio.RadioMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ce que joue une radio. Le serveur décide (file d'attente, passage à la chanson suivante) ; chaque joueur
 * reçoit cet état et va chercher le son lui-même. L'heure de départ ({@code startTick}, en temps du monde)
 * permet à tout le monde d'entendre le même moment de la chanson.
 */
public class RadioBlockEntity extends BlockEntity {
    public enum Mode { OFF, QUEUE, STATION, AMBIENT }
    public enum Repeat { OFF, ALL, ONE }

    public static final int MAX_QUEUE = 200;
    private static final int KEEP_HISTORY = 30;

    private Mode mode = Mode.OFF;
    private final List<QueueEntry> queue = new ArrayList<>();
    private int index;
    private long startTick;
    private boolean paused;
    private long pausedElapsed;
    private int volume = 70;
    private int range = 24;
    private Repeat repeat = Repeat.OFF;
    private boolean shuffle;
    private String stationName = "", stationUrl = "", stationCodec = "MP3";
    private String ambient = "overworld";
    private UUID owner;
    private String ownerName = "";
    /** Change à chaque nouvelle chanson : les joueurs savent qu'il faut relancer le son. */
    private int playId;
    /** Change quand quelqu'un avance ou recule dans la chanson (sans annoncer une nouvelle chanson). */
    private int seekId;
    /** Jusqu'à ce moment (temps du monde), la chanson attend que tout le monde ait chargé le son. */
    private long holdUntil;
    /** Disques mis dans la radio : ils y restent et on peut les rejouer. */
    private final List<ItemStack> discs = new ArrayList<>();
    public static final int MAX_DISCS = 54;
    /** Radio portable : seul le porteur entend (casque), ou tout le monde autour (haut-parleur). */
    private boolean headphones = true;
    /**
     * Pour une radio portable, la radio n'est pas posée dans le monde : elle vit dans l'objet.
     * Chaque changement est alors recopié dans l'objet au lieu d'être envoyé comme un bloc.
     */
    private Runnable onChange;

    public RadioBlockEntity(BlockPos pos, BlockState state) {
        super(RadioMod.RADIO_BE.get(), pos, state);
    }

    // ---------- Lecture de l'état ----------

    public Mode mode() { return mode; }
    public List<QueueEntry> queue() { return queue; }
    public int index() { return index; }
    public long startTick() { return startTick; }
    public boolean paused() { return paused; }
    public int volume() { return volume; }
    public int range() { return range; }
    public Repeat repeat() { return repeat; }
    public boolean shuffle() { return shuffle; }
    public String stationName() { return stationName; }
    public String stationUrl() { return stationUrl; }
    public String stationCodec() { return stationCodec; }
    public String ambient() { return ambient; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public int playId() { return playId; }
    public int seekId() { return seekId; }
    public boolean headphones() { return headphones; }

    public void setOnChange(Runnable r) {
        onChange = r;
    }

    public void toggleHeadphones() {
        headphones = !headphones;
        sync();
    }

    /** Va à un moment de la chanson en cours, pour tout le monde. */
    public void seek(int seconds) {
        QueueEntry cur = current();
        if (cur == null || level == null) return;
        long t = Math.max(0, Math.min(cur.durationSec() - 1, seconds)) * 20L;
        if (paused) pausedElapsed = t;
        else begin(t, lead() / 2); // le son est déjà connu de tous : chargement plus court
        seekId++;
        sync();
    }
    public List<ItemStack> discs() { return discs; }

    /** L'identifiant du disque (minecraft:cat), ou null si l'objet n'est pas un disque. */
    public static String songId(ItemStack stack, HolderLookup.Provider registries) {
        JukeboxPlayable jp = stack.get(DataComponents.JUKEBOX_PLAYABLE);
        if (jp == null) return null;
        if (jp.song().key() != null) return jp.song().key().location().toString();
        return jp.song().unwrap(registries).flatMap(h -> h.unwrapKey()).map(k -> k.location().toString()).orElse(null);
    }

    public boolean hasDisc(String songId) {
        if (level == null) return false;
        for (ItemStack s : discs) if (songId.equals(songId(s, level.registryAccess()))) return true;
        return false;
    }

    /** Range un disque (un seul exemplaire de chaque). Renvoie false si déjà là ou plus de place. */
    public boolean insertDisc(ItemStack disc) {
        if (level == null) return false;
        String id = songId(disc, level.registryAccess());
        if (id == null || hasDisc(id) || discs.size() >= MAX_DISCS) return false;
        discs.add(disc.copyWithCount(1));
        sync();
        return true;
    }

    /** Ressort un disque ; ses passages encore à venir dans la file sont retirés. */
    public ItemStack ejectDisc(int i) {
        if (i < 0 || i >= discs.size() || level == null) return ItemStack.EMPTY;
        ItemStack s = discs.remove(i);
        String id = songId(s, level.registryAccess());
        for (int q = queue.size() - 1; q > index; q--) {
            QueueEntry e = queue.get(q);
            if (e.kind() == QueueEntry.Kind.DISC && e.ref().equals(id)) queue.remove(q);
        }
        QueueEntry cur = current();
        if (cur != null && cur.kind() == QueueEntry.Kind.DISC && cur.ref().equals(id)) advance(false);
        else sync();
        return s;
    }

    public QueueEntry current() {
        return mode == Mode.QUEUE && index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public long elapsedTicks() {
        if (level == null) return 0;
        return paused ? pausedElapsed : Math.max(0, Math.max(level.getGameTime(), holdUntil) - startTick);
    }

    public boolean isPlaying() {
        return !paused && (mode == Mode.STATION || mode == Mode.AMBIENT || current() != null);
    }

    public int waitingFrom(String player) {
        int n = 0;
        for (int i = Math.max(index + 1, 0); i < queue.size(); i++) if (queue.get(i).addedBy().equals(player)) n++;
        return n;
    }

    // ---------- Actions (serveur) ----------

    public void setOwner(UUID id, String name) {
        owner = id;
        ownerName = name;
        sync();
    }

    public void add(QueueEntry e, boolean playNow) {
        trimHistory();
        if (queue.size() >= MAX_QUEUE) return;
        boolean idle = mode != Mode.QUEUE || current() == null;
        if (idle) {
            // Rien ne joue : la chanson part tout de suite, à la fin de la file
            if (mode != Mode.QUEUE) index = queue.size();
            queue.add(Math.min(index, queue.size()), e);
            mode = Mode.QUEUE;
            start();
        } else if (playNow) {
            queue.add(index + 1, e);
            index++;
            start();
        } else {
            queue.add(e);
            sync();
        }
    }

    public void skip() {
        if (mode != Mode.QUEUE) return;
        advance(false);
    }

    public void previous() {
        if (mode != Mode.QUEUE || queue.isEmpty()) return;
        if (current() != null && elapsedTicks() > 100) {
            start();
            return;
        }
        index = Math.max(0, Math.min(index, queue.size()) - 1);
        start();
    }

    public void togglePause() {
        if (mode == Mode.OFF || level == null) return;
        if (paused) {
            begin(pausedElapsed);
            paused = false;
        } else {
            pausedElapsed = elapsedTicks();
            paused = true;
        }
        sync();
    }

    public void jump(int i) {
        if (i < 0 || i >= queue.size()) return;
        mode = Mode.QUEUE;
        index = i;
        start();
    }

    public void remove(int i) {
        if (i < 0 || i >= queue.size()) return;
        queue.remove(i);
        if (i < index) {
            index--;
            sync();
        } else if (i == index && mode == Mode.QUEUE) {
            start(); // la suivante prend sa place
        } else {
            sync();
        }
    }

    public void clearUpcoming() {
        while (queue.size() > index + 1) queue.removeLast();
        sync();
    }

    public void setVolume(int v) {
        volume = Math.max(0, Math.min(100, v));
        sync();
    }

    public void setRange(int r, int max) {
        range = Math.max(4, Math.min(max, r));
        sync();
    }

    public void cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length];
        sync();
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        sync();
    }

    public void playStation(String name, String url, String codec) {
        mode = Mode.STATION;
        stationName = name;
        stationUrl = url;
        stationCodec = codec;
        start();
    }

    public void playAmbient(String key) {
        mode = Mode.AMBIENT;
        ambient = key;
        start();
    }

    /** Reprend la file d'attente là où elle en était (après une radio ou une ambiance). */
    public void resumeQueue() {
        mode = Mode.QUEUE;
        start();
    }

    public void stop() {
        mode = Mode.OFF;
        paused = false;
        sync();
    }

    /**
     * Temps laissé aux joueurs pour aller chercher le son avant le top départ : tout le monde commence
     * alors pile au début (ou au moment choisi), au lieu de sauter les secondes de chargement.
     */
    /**
     * Repart du moment {@code t} (en ticks) : le son reste à {@code t} pendant le temps de chargement,
     * puis tout le monde repart ensemble, pile à ce moment-là.
     */
    private void begin(long t) {
        begin(t, lead());
    }

    private void begin(long t, int leadTicks) {
        holdUntil = level.getGameTime() + leadTicks;
        startTick = holdUntil - t;
    }

    public long holdUntil() { return holdUntil; }

    private int lead() {
        QueueEntry cur = current();
        if (cur == null) return 0;
        return cur.kind() == QueueEntry.Kind.SONG ? 60 : 10;
    }

    private void start() {
        if (level == null) return;
        begin(0);
        paused = false;
        pausedElapsed = 0;
        playId++;
        sync();
    }

    private void advance(boolean auto) {
        if (auto && repeat == Repeat.ONE && current() != null) {
            start();
            return;
        }
        int next = index + 1;
        if (shuffle && queue.size() - next > 1 && level != null) {
            int r = next + level.random.nextInt(queue.size() - next);
            QueueEntry tmp = queue.get(next);
            queue.set(next, queue.get(r));
            queue.set(r, tmp);
        }
        if (next >= queue.size()) {
            if (repeat == Repeat.ALL && !queue.isEmpty()) next = 0;
            else next = queue.size(); // fin de la file : la radio se tait
        }
        index = next;
        start();
    }

    private void trimHistory() {
        while (index > KEEP_HISTORY && !queue.isEmpty()) {
            queue.removeFirst();
            index--;
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadioBlockEntity be) {
        if (be.mode != Mode.QUEUE || be.paused) return;
        QueueEntry cur = be.current();
        if (cur == null) return;
        // Un petit temps en plus : les joueurs ont pu démarrer la chanson une seconde après le serveur
        if (be.elapsedTicks() >= cur.durationSec() * 20L + 30) be.advance(true);
    }

    private void sync() {
        if (onChange != null) {
            onChange.run();
            return;
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ---------- Sauvegarde et envoi aux joueurs ----------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("mode", mode.name());
        ListTag list = new ListTag();
        for (QueueEntry e : queue) list.add(e.save());
        tag.put("queue", list);
        tag.putInt("index", index);
        tag.putLong("start", startTick);
        tag.putBoolean("paused", paused);
        tag.putLong("pausedAt", pausedElapsed);
        tag.putInt("volume", volume);
        tag.putInt("range", range);
        tag.putString("repeat", repeat.name());
        tag.putBoolean("shuffle", shuffle);
        tag.putString("stationName", stationName);
        tag.putString("stationUrl", stationUrl);
        tag.putString("stationCodec", stationCodec);
        tag.putString("ambient", ambient);
        if (owner != null) tag.putUUID("owner", owner);
        tag.putString("ownerName", ownerName);
        tag.putInt("playId", playId);
        tag.putInt("seekId", seekId);
        tag.putLong("hold", holdUntil);
        ListTag d = new ListTag();
        for (ItemStack s : discs) d.add(s.save(registries));
        tag.put("discs", d);
        tag.putBoolean("headphones", headphones);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = parse(Mode.class, tag.getString("mode"), Mode.OFF);
        queue.clear();
        for (Tag t : tag.getList("queue", Tag.TAG_COMPOUND)) {
            QueueEntry e = QueueEntry.load((CompoundTag) t);
            if (e != null) queue.add(e);
        }
        index = tag.getInt("index");
        startTick = tag.getLong("start");
        paused = tag.getBoolean("paused");
        pausedElapsed = tag.getLong("pausedAt");
        volume = tag.contains("volume") ? tag.getInt("volume") : 70;
        range = tag.contains("range") ? tag.getInt("range") : 24;
        repeat = parse(Repeat.class, tag.getString("repeat"), Repeat.OFF);
        shuffle = tag.getBoolean("shuffle");
        stationName = tag.getString("stationName");
        stationUrl = tag.getString("stationUrl");
        stationCodec = tag.contains("stationCodec") ? tag.getString("stationCodec") : "MP3";
        ambient = tag.contains("ambient") ? tag.getString("ambient") : "overworld";
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        playId = tag.getInt("playId");
        seekId = tag.getInt("seekId");
        holdUntil = tag.getLong("hold");
        discs.clear();
        for (Tag t : tag.getList("discs", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parseOptional(registries, (CompoundTag) t);
            if (!s.isEmpty()) discs.add(s);
        }
        headphones = !tag.contains("headphones") || tag.getBoolean("headphones");
    }

    private static <E extends Enum<E>> E parse(Class<E> c, String s, E def) {
        try {
            return Enum.valueOf(c, s);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Le client garde la liste des radios chargées pour savoir lesquelles il entend
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) RadioMod.clientRadios().add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) RadioMod.clientRadios().remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && level.isClientSide) RadioMod.clientRadios().remove(this);
    }
}
