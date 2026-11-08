package fr.tamarou.radio.client;

import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.block.QueueEntry;
import fr.tamarou.radio.block.RadioBlockEntity;
import fr.tamarou.radio.media.Deezer;
import fr.tamarou.radio.media.RadioBrowser;
import fr.tamarou.radio.media.TrackInfo;
import fr.tamarou.radio.media.YouTubeAudio;
import fr.tamarou.radio.net.RadioNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * L'écran de la radio, dessiné comme la façade du bloc : boîtier en bois avec son antenne, onglets en
 * boutons-poussoirs, grille du haut-parleur, cadran crème (l'aiguille rouge montre l'onglet) et molettes.
 */
public final class RadioScreen extends Screen {
    private enum Tab { SEARCH, NOW, RADIOS, MINECRAFT }

    private static final int W = 340, H = 224, ROW = 24;
    private static final int CHROME = 0xFFD0D0D0, CHROME_DARK = 0xFF8A8A8A, CHROME_LIGHT = 0xFFF2F2F2, CHROME_ON = 0xFF9A9A9A;
    // Couleurs du poste : reprises de la texture du bloc ouvert (bois et cadran crème, ou plastique gris et écran vert)
    private int WOOD, WOOD_DARK, WOOD_LIGHT, WOOD_EDGE, DIAL, DIAL_SHADE, DIAL_LINE, DIAL_MARK, INK, INK_SOFT, NEEDLE, ERROR,
            LABEL, TITLE, FIELD, SLOT_LIGHT;
    /** La radio-cassette a son propre look : boîtier gris, poignée, haut-parleurs ronds et écran vert lumineux. */
    private boolean boombox;

    private void applyTheme() {
        RadioBlockEntity be = radio();
        // La radio portable prend le look de la radio-cassette
        boombox = slot >= 0 || (be != null && be.getBlockState().is(RadioMod.BOOMBOX.get()));
        if (boombox) {
            WOOD = 0xFF55555A; WOOD_DARK = 0xFF3E3E42; WOOD_LIGHT = 0xFF6E6E73; WOOD_EDGE = 0xFF2A2A2E;
            DIAL = 0xFF16241C; DIAL_SHADE = 0xFF0E1813; DIAL_LINE = 0xFF233A2D; DIAL_MARK = 0xFF2F5A45;
            INK = 0x7CF0B4; INK_SOFT = 0x4F9C78; NEEDLE = 0xFF4FC3A1; ERROR = 0xFF7070;
            LABEL = 0xD8D8DE; TITLE = 0xC8FFE4; FIELD = 0xFF0B140F; SLOT_LIGHT = 0xFF1E3428;
        } else {
            WOOD = 0xFF7A5230; WOOD_DARK = 0xFF5A3A20; WOOD_LIGHT = 0xFF94683F; WOOD_EDGE = 0xFF3D2914;
            DIAL = 0xFFE8D9A0; DIAL_SHADE = 0xFFC9B877; DIAL_LINE = 0xFFCDBB7E; DIAL_MARK = 0xFF8A7440;
            INK = 0x3D2914; INK_SOFT = 0x7A6030; NEEDLE = 0xFFC0392B; ERROR = 0xA01818;
            LABEL = 0xF1E3C2; TITLE = 0x000000; FIELD = 0xFFFFF8DC; SLOT_LIGHT = 0xFFF4E9BE;
        }
    }

    private static final String[] COUNTRIES = {"FR", "BE", "CH", "CA", "GB", "US", ""};
    private static final String[] GENRES = {"", "pop", "rock", "jazz", "lofi", "hip-hop", "electronic", "classical", "news", "80s"};
    private static final String[] AMBIENTS = {"overworld", "nether", "end", "creative", "underwater", "menu"};
    private static final int DISCS_PER_ROW = 14, DISC_SLOT = 18;
    private static final ResourceLocation ICONS = ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "textures/gui/icons.png");
    private static final ResourceLocation KNOB = ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "textures/gui/knob.png");
    private static final int I_PREV = 0, I_PLAY = 1, I_PAUSE = 2, I_NEXT = 3, I_REPEAT = 4, I_REPEAT_ONE = 5, I_SHUFFLE = 6,
            I_STOP = 7, I_PLUS = 8, I_CROSS = 9, I_STAR = 10, I_STAR_EMPTY = 11, I_SEARCH = 12, I_HEADPHONES = 13, I_SPEAKER = 14, ICON_COUNT = 15;

    // L'onglet et les recherches restent quand on ferme et rouvre l'écran
    private static Tab tab = Tab.SEARCH;
    private static String lastQuery = "";
    private static List<TrackInfo> results = new ArrayList<>();
    private static String lastStationQuery = "";
    private static int country, genre;
    private static List<RadioBrowser.Station> stations;

    private final BlockPos pos;
    /** -1 : radio posée en {@code pos}. Sinon la case d'inventaire de la radio portable. */
    private final int slot;
    private int left, top;
    // Le cadran (zone crème) et son contenu
    private int dx, dy, dw, dh, cx, cy, cw, ch;
    private int scroll;
    private double clickX;
    private float needle = -1;
    private String status;
    private boolean busy;
    private EditBox searchBox, stationBox;
    private Knob volumeKnob, rangeKnob;
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int x, int y, int w, int h, Runnable action, Component tip) {}
    private List<DiscEntry> discs = new ArrayList<>();
    private record DiscEntry(ResourceLocation id, JukeboxSong song, ItemStack icon) {}

    /** Pour l'auto-test : choisir l'onglet et les résultats affichés. */
    static void debug(String tabName, List<TrackInfo> searchResults) {
        tab = Tab.valueOf(tabName);
        if (searchResults != null) {
            results = searchResults;
            lastQuery = "angele";
        }
    }

    public RadioScreen(BlockPos pos) {
        this(pos, -1);
    }

    /** L'écran de la radio portable rangée dans la case {@code slot} de l'inventaire. */
    public static RadioScreen portable(int slot) {
        var p = Minecraft.getInstance().player;
        return new RadioScreen(p == null ? BlockPos.ZERO : p.blockPosition(), slot);
    }

    private RadioScreen(BlockPos pos, int slot) {
        super(Component.translatable(slot >= 0 ? "item.radio.portable_radio" : "block.radio.radio"));
        this.pos = pos;
        this.slot = slot;
    }

    private RadioBlockEntity radio() {
        Minecraft mc = Minecraft.getInstance();
        if (slot >= 0) {
            if (mc.player == null || slot >= mc.player.getInventory().getContainerSize()) return null;
            ItemStack s = mc.player.getInventory().getItem(slot);
            return s.getItem() instanceof fr.tamarou.radio.item.PortableRadioItem ? RadioClient.portable(RadioClient.SELF, s, mc.player) : null;
        }
        var level = mc.level;
        return level != null && level.getBlockEntity(pos) instanceof RadioBlockEntity be ? be : null;
    }

    /** La clé du son de cette radio chez le joueur (pour « chargement », « erreur », titre en direct). */
    private Object audioKey() {
        return slot >= 0 ? RadioClient.SELF : pos;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // en solo, le jeu (et le son) continue
    }

    @Override
    protected void init() {
        applyTheme();
        left = (width - W) / 2;
        top = Math.max(16, (height - H) / 2 + 7); // de la place au-dessus pour l'antenne
        dx = left + 60;
        dy = top + 30;
        dw = W - 70;
        dh = H - 66;
        cx = dx + 6;
        cy = dy + 19;
        cw = dw - 12;
        ch = dh - 23;

        // Onglets : boutons-poussoirs en haut du poste
        int kw = (W - 20 - 3 * 6) / 4;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab t = Tab.values()[i];
            addRenderableWidget(new Key(left + 10 + i * (kw + 6), top + 7, kw, 17,
                    Component.translatable("radio.tab." + t.name().toLowerCase()), b -> switchTab(t), () -> tab == t));
        }

        // Molettes et boutons de lecture, en bas du poste
        RadioBlockEntity be = radio();
        int max = RadioClient.serverInfo.maxRange();
        int by = top + H - 32;
        volumeKnob = addRenderableWidget(new Knob(left + 12, by, be == null ? 0.7 : be.volume() / 100.0,
                v -> Component.translatable("radio.knob.volume", Math.round(v * 100)),
                v -> control("volume", (int) Math.round(v * 100), "")));
        rangeKnob = addRenderableWidget(new Knob(left + 110, by, be == null ? 0.4 : (be.range() - 4) / (double) Math.max(1, max - 4),
                v -> Component.translatable("radio.knob.range", 4 + Math.round(v * (max - 4))),
                v -> control("range", 4 + (int) Math.round(v * (max - 4)), "")));
        // Boutons de lecture : l'icône et l'état suivent la radio (pause / lecture, répéter, aléatoire)
        record Transport(String action, String tip, java.util.function.IntSupplier icon, java.util.function.BooleanSupplier on) {}
        List<Transport> transport = new ArrayList<>(List.of(
                new Transport("prev", "prev", () -> I_PREV, () -> false),
                new Transport("pause", "pause", () -> {
                    RadioBlockEntity r = radio();
                    return r != null && r.isPlaying() ? I_PAUSE : I_PLAY;
                }, () -> false),
                new Transport("skip", "next", () -> I_NEXT, () -> false)));
        if (tab == Tab.NOW) {
            transport.add(new Transport("repeat", "repeat", () -> {
                RadioBlockEntity r = radio();
                return r != null && r.repeat() == RadioBlockEntity.Repeat.ONE ? I_REPEAT_ONE : I_REPEAT;
            }, () -> {
                RadioBlockEntity r = radio();
                return r != null && r.repeat() != RadioBlockEntity.Repeat.OFF;
            }));
            transport.add(new Transport("shuffle", "shuffle", () -> I_SHUFFLE, () -> {
                RadioBlockEntity r = radio();
                return r != null && r.shuffle();
            }));
            transport.add(new Transport("stop", "stop", () -> I_STOP, () -> false));
        }
        if (slot >= 0) {
            // Radio portable : casque (moi seul) ou haut-parleur (tout le monde autour)
            transport.add(new Transport("headphones", "headphones", () -> {
                RadioBlockEntity r = radio();
                return r == null || r.headphones() ? I_HEADPHONES : I_SPEAKER;
            }, () -> false));
        }
        int step = transport.size() > 6 ? 20 : 21;
        int tx = left + W - 12 - transport.size() * step;
        for (Transport t : transport) {
            addRenderableWidget(new IconKey(tx, by + 2, 19, 19, t.icon(), b -> control(t.action()), t.on()))
                    .setTooltip(Tooltip.create(Component.translatable("radio.tip." + t.tip())));
            tx += step;
        }

        status = null;
        scroll = 0;
        switch (tab) {
            case SEARCH -> initSearch();
            case NOW -> initNow();
            case RADIOS -> initRadios();
            case MINECRAFT -> initMinecraft();
        }
    }

    private void switchTab(Tab t) {
        tab = t;
        rebuildWidgets();
    }

    private EditBox field(int x, int y, int w, String hint, String value) {
        EditBox b = new EditBox(font, x + 4, y + 4, w - 8, 12, Component.translatable(hint));
        b.setBordered(false);
        b.setTextColor(INK);
        b.setMaxLength(300);
        b.setHint(Component.translatable(hint).withColor(INK_SOFT));
        b.setValue(value);
        return addRenderableWidget(b);
    }

    // =====================================================================
    // Onglet Recherche
    // =====================================================================

    private void initSearch() {
        searchBox = field(cx, cy, cw - 58, "radio.search.placeholder", lastQuery);
        setInitialFocus(searchBox);
        addRenderableWidget(new Key(cx + cw - 54, cy, 54, 16, Component.translatable("radio.search.go"), b -> doSearch(), () -> false));
    }

    private void doSearch() {
        String q = searchBox.getValue().trim();
        if (q.isEmpty() || busy) return;
        if (!RadioClient.serverInfo.search()) {
            status = tr("radio.msg.search_off");
            return;
        }
        lastQuery = q;
        busy = true;
        status = tr("radio.search.searching");
        CompletableFuture.supplyAsync(() -> {
            try {
                return Deezer.search(q);
            } catch (Throwable e) {
                RadioMod.LOGGER.warn("Recherche ratée", e);
                return null;
            }
        }).thenAccept(r -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (r == null) {
                status = tr("radio.search.error");
            } else {
                results = r;
                scroll = 0;
                status = r.isEmpty() ? tr("radio.search.none") : null;
            }
        }));
    }

    /** Trouve le son de la chanson puis l'envoie à la radio. */
    private void addSong(TrackInfo t, boolean now) {
        if (busy) return;
        busy = true;
        status = tr("radio.search.finding", t.display());
        CompletableFuture.supplyAsync(() -> {
            try {
                return YouTubeAudio.find(t);
            } catch (Throwable e) {
                RadioMod.LOGGER.warn("Son introuvable", e);
                return null;
            }
        }).thenAccept(found -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (found == null) {
                status = tr("radio.search.no_audio", t.display());
                return;
            }
            RadioClient.prefetch(found.videoId());
            send(new RadioNet.AddEntry(pos, slot, new QueueEntry(QueueEntry.Kind.SONG, found.title(), found.artist(),
                    found.coverUrl(), found.durationSec(), found.videoId(), ""), now));
            status = tr(now ? "radio.search.playing" : "radio.search.added", t.display());
        }));
    }

    private void renderSearch(GuiGraphics g, int mx, int my) {
        fieldFrame(g, cx, cy, cw - 58, 16);
        line(g, status != null ? status : tr("radio.search.hint"), cx, cy + 20, cw, INK_SOFT);
        int ly = cy + 31, lh = ch - 31;
        list(g, mx, my, cx, ly, cw, lh, results.size(), ROW, (i, x, y) -> {
            TrackInfo t = results.get(i);
            cover(g, t.coverUrl(), x + 1, y + 2, 20);
            int textW = cw - 96;
            g.drawString(font, font.plainSubstrByWidth(t.title(), textW), x + 26, y + 3, TITLE, false);
            g.drawString(font, font.plainSubstrByWidth(t.artist() + (t.album().isEmpty() ? "" : " · " + t.album()), textW), x + 26, y + 13, INK_SOFT, false);
            g.drawString(font, time(t.durationSec()), x + cw - 66, y + 8, INK, false);
            mini(g, mx, my, x + cw - 40, y + 4, I_PLAY, () -> addSong(t, true), Component.translatable("radio.tip.play_now"));
            mini(g, mx, my, x + cw - 21, y + 4, I_PLUS, () -> addSong(t, false), Component.translatable("radio.tip.add"));
        });
        if (results.isEmpty() && status == null) centered(g, tr("radio.search.empty"), ly + lh / 2 - 4, INK_SOFT);
    }

    // =====================================================================
    // Onglet En cours
    // =====================================================================

    private void initNow() {
        RadioBlockEntity be = radio();
        addRenderableWidget(new Key(cx + cw - 46, cy + 55, 46, 13, Component.translatable("radio.now.clear"), b -> control("clear"), () -> false));
        if (be != null && be.mode() != RadioBlockEntity.Mode.QUEUE && be.index() < be.queue().size()) {
            addRenderableWidget(new Key(cx + cw - 130, cy + 55, 80, 13, Component.translatable("radio.now.resume"), b -> control("resume"), () -> false));
        }
    }

    private void renderNow(GuiGraphics g, int mx, int my) {
        RadioBlockEntity be = radio();
        if (be == null) return;
        String title, sub, extra = "";
        String coverUrl = "";
        QueueEntry cur = be.current();
        switch (be.mode()) {
            case STATION -> {
                title = be.stationName();
                String live = RadioClient.liveTitle(audioKey());
                sub = live != null ? live : tr("radio.now.live");
                extra = tr("radio.now.live");
            }
            case AMBIENT -> {
                title = tr("radio.ambient." + be.ambient());
                sub = tr("radio.now.ambient");
            }
            case QUEUE -> {
                if (cur == null) {
                    title = tr("radio.now.nothing");
                    sub = tr("radio.now.nothing_hint");
                } else {
                    title = cur.title();
                    sub = cur.artist();
                    coverUrl = cur.cover();
                    if (!cur.addedBy().isEmpty()) extra = tr("radio.now.added_by", cur.addedBy());
                }
            }
            default -> {
                title = tr("radio.now.off");
                sub = tr("radio.now.nothing_hint");
            }
        }
        // Pochette, ou l'icône du disque / un bloc musical
        int size = 50;
        g.fill(cx - 1, cy - 1, cx + size + 1, cy + size + 1, WOOD_EDGE);
        if (!cover(g, coverUrl, cx, cy, size)) {
            g.fill(cx, cy, cx + size, cy + size, WOOD);
            ItemStack icon = cur != null && cur.kind() == QueueEntry.Kind.DISC ? discIcon(cur.ref()) : new ItemStack(Items.NOTE_BLOCK);
            g.pose().pushPose();
            g.pose().translate(cx + 9, cy + 9, 0);
            g.pose().scale(2, 2, 1);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        int tx = cx + size + 8, tw = cw - size - 8;
        line(g, title, tx, cy + 1, tw, TITLE);
        line(g, sub, tx, cy + 12, tw, INK);
        String state = be.paused() ? tr("radio.now.paused") : extra;
        String err = RadioClient.error(audioKey());
        if (err != null) state = tr("radio.now.error", err);
        else if (RadioClient.isLoading(audioKey())) state = tr("radio.now.loading");
        line(g, state, tx, cy + 23, tw, err != null ? ERROR : INK_SOFT);
        String modes = tr("radio.repeat." + be.repeat().name().toLowerCase()) + (be.shuffle() ? " · " + tr("radio.now.shuffle_on") : "");
        if (be.mode() == RadioBlockEntity.Mode.QUEUE && cur != null) {
            int el = (int) Math.min(cur.durationSec(), be.elapsedTicks() / 20);
            int bw = tw - 2;
            g.fill(tx, cy + 35, tx + bw, cy + 41, DIAL_MARK);
            g.fill(tx + 1, cy + 36, tx + bw - 1, cy + 40, DIAL_SHADE);
            int dur = Math.max(1, cur.durationSec());
            int fillTo = tx + 1 + (bw - 2) * el / dur;
            g.fill(tx + 1, cy + 36, fillTo, cy + 40, NEEDLE);
            // Curseur, et clic sur la barre pour aller à ce moment de la chanson
            g.fill(fillTo - 1, cy + 33, fillTo + 2, cy + 43, 0xFF000000);
            g.fill(fillTo, cy + 34, fillTo + 1, cy + 42, CHROME);
            boolean hoverBar = mx >= tx && mx < tx + bw && my >= cy + 32 && my < cy + 44;
            if (hoverBar) {
                int at = (int) ((mx - tx - 1) / (double) (bw - 2) * dur);
                g.fill(mx, cy + 34, mx + 1, cy + 42, 0x80000000);
                hits.add(new Hit(tx, cy + 32, bw, 12, () -> {
                    int sec = (int) Math.max(0, Math.min(dur - 1, (clickX - tx - 1) / (bw - 2) * dur));
                    control("seek", sec, "");
                }, Component.translatable("radio.tip.seek", time(Math.max(0, Math.min(dur, at))))));
            }
            line(g, time(el) + " / " + time(cur.durationSec()) + " · " + modes, tx, cy + 44, tw, INK);
        } else {
            line(g, modes, tx, cy + 44, tw, INK_SOFT);
        }

        // File d'attente
        int start = Math.max(be.index() + 1, 0);
        int count = Math.max(0, be.queue().size() - start);
        g.drawString(font, tr("radio.now.up_next", count), cx, cy + 58, INK, false);
        int ly = cy + 70, lh = ch - 70;
        list(g, mx, my, cx, ly, cw, lh, count, 16, (i, rx, ry) -> {
            int qi = start + i;
            QueueEntry e = be.queue().get(qi);
            boolean hover = mx >= rx && mx < rx + cw - 20 && my >= ry && my < ry + 16;
            if (hover) g.fill(rx, ry, rx + cw, ry + 15, 0x40FFFFFF);
            g.drawString(font, (i + 1) + ".", rx + 2, ry + 4, INK, false);
            String name = e.display() + (e.kind() == QueueEntry.Kind.DISC ? " " + tr("radio.now.disc") : "");
            g.drawString(font, font.plainSubstrByWidth(name, cw - 100), rx + 18, ry + 4, TITLE, false);
            g.drawString(font, font.plainSubstrByWidth(e.addedBy(), 56), rx + cw - 78, ry + 4, INK_SOFT, false);
            hits.add(new Hit(rx, ry, cw - 20, 16, () -> control("jump", qi, ""), Component.translatable("radio.tip.jump")));
            mini(g, mx, my, rx + cw - 19, ry, I_CROSS, () -> control("remove", qi, ""), Component.translatable("radio.tip.remove"));
        });
    }

    // =====================================================================
    // Onglet Radios
    // =====================================================================

    private void initRadios() {
        stationBox = field(cx, cy, 96, "radio.radios.placeholder", lastStationQuery);
        addRenderableWidget(new Key(cx + 100, cy, 58, 16, countryLabel(), b -> {
            country = (country + 1) % COUNTRIES.length;
            b.setMessage(countryLabel());
        }, () -> false));
        addRenderableWidget(new Key(cx + 162, cy, 58, 16, genreLabel(), b -> {
            genre = (genre + 1) % GENRES.length;
            b.setMessage(genreLabel());
        }, () -> false));
        addRenderableWidget(new IconKey(cx + 224, cy, cw - 224, 16, () -> I_SEARCH, b -> searchStations(), () -> false))
                .setTooltip(Tooltip.create(Component.translatable("radio.search.go")));
        if (stations == null) searchStations();
    }

    private Component countryLabel() {
        String c = COUNTRIES[country];
        return Component.translatable(c.isEmpty() ? "radio.radios.world" : "radio.country." + c.toLowerCase());
    }

    private Component genreLabel() {
        String gname = GENRES[genre];
        return Component.translatable(gname.isEmpty() ? "radio.radios.all_genres" : "radio.genre." + gname);
    }

    private void searchStations() {
        if (busy) return;
        if (!RadioClient.serverInfo.stations()) {
            status = tr("radio.msg.stations_off");
            return;
        }
        String q = stationBox == null ? "" : stationBox.getValue();
        lastStationQuery = q;
        busy = true;
        status = tr("radio.search.searching");
        String c = COUNTRIES[country], gname = GENRES[genre];
        CompletableFuture.supplyAsync(() -> {
            try {
                return RadioBrowser.search(q, c, gname);
            } catch (Throwable e) {
                RadioMod.LOGGER.warn("Recherche ratée", e);
                return null;
            }
        }).thenAccept(r -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (r == null) status = tr("radio.search.error");
            else {
                stations = r;
                scroll = 0;
                status = r.isEmpty() ? tr("radio.search.none") : null;
            }
        }));
    }

    private void renderRadios(GuiGraphics g, int mx, int my) {
        RadioBlockEntity be = radio();
        fieldFrame(g, cx, cy, 96, 16);
        List<RadioBrowser.Station> shown = new ArrayList<>(Favorites.all());
        if (stations != null) for (RadioBrowser.Station s : stations) if (!Favorites.has(s)) shown.add(s);
        line(g, status != null ? status : tr("radio.radios.hint"), cx, cy + 20, cw, INK_SOFT);
        int ly = cy + 31, lh = ch - 31;
        list(g, mx, my, cx, ly, cw, lh, shown.size(), 22, (i, x, y) -> {
            RadioBrowser.Station s = shown.get(i);
            boolean on = be != null && be.mode() == RadioBlockEntity.Mode.STATION && s.url().equals(be.stationUrl());
            g.drawString(font, "●", x + 2, y + 7, on ? 0x2E8B2E : 0xC0392B, false);
            g.drawString(font, font.plainSubstrByWidth(s.name(), cw - 60), x + 12, y + 2, TITLE, false);
            String live = on ? RadioClient.liveTitle(audioKey()) : null;
            String sub = live != null ? tr("radio.radios.live", live) : (s.country() + " · " + s.tags().replace(",", ", "));
            g.drawString(font, font.plainSubstrByWidth(sub, cw - 60), x + 12, y + 12, INK_SOFT, false);
            boolean fav = Favorites.has(s);
            mini(g, mx, my, x + cw - 40, y + 3, fav ? I_STAR : I_STAR_EMPTY, () -> Favorites.toggle(s),
                    Component.translatable(fav ? "radio.tip.unfav" : "radio.tip.fav"));
            mini(g, mx, my, x + cw - 21, y + 3, I_PLAY, () -> control("station", 0, s.name() + "\n" + s.url() + "\n" + s.codec()),
                    Component.translatable("radio.tip.play_now"));
        });
    }

    // =====================================================================
    // Onglet Minecraft
    // =====================================================================

    private void initMinecraft() {
        int kw = (cw - 8) / 3;
        for (int i = 0; i < AMBIENTS.length; i++) {
            String a = AMBIENTS[i];
            addRenderableWidget(new Key(cx + (i % 3) * (kw + 4), cy + 106 + (i / 3) * 14, kw, 13,
                    Component.translatable("radio.ambient." + a), b -> control("ambient", 0, a), () -> {
                RadioBlockEntity be = radio();
                return be != null && be.mode() == RadioBlockEntity.Mode.AMBIENT && be.ambient().equals(a);
            }));
        }
    }

    /** Les disques rangés dans cette radio (relus à chaque image : un joueur peut en ajouter pendant qu'on regarde). */
    private List<DiscEntry> storedDiscs() {
        List<DiscEntry> out = new ArrayList<>();
        RadioBlockEntity be = radio();
        var level = Minecraft.getInstance().level;
        if (be == null || level == null) return out;
        var reg = level.registryAccess().registryOrThrow(Registries.JUKEBOX_SONG);
        for (ItemStack s : be.discs()) {
            String id = RadioBlockEntity.songId(s, level.registryAccess());
            JukeboxSong song = id == null ? null : reg.get(ResourceLocation.parse(id));
            if (song != null) out.add(new DiscEntry(ResourceLocation.parse(id), song, s));
        }
        return out;
    }

    /** L'icône d'un disque de la file : celui rangé dans la radio, sinon un disque quelconque. */
    private ItemStack discIcon(String songId) {
        for (DiscEntry d : storedDiscs()) if (d.id.toString().equals(songId)) return d.icon;
        return new ItemStack(Items.MUSIC_DISC_13);
    }

    private int discX(int i) {
        return cx + 1 + (i % DISCS_PER_ROW) * DISC_SLOT;
    }

    private int discY(int i) {
        return cy + 11 + (i / DISCS_PER_ROW) * DISC_SLOT;
    }

    private void renderMinecraft(GuiGraphics g, int mx, int my) {
        discs = storedDiscs();
        g.drawString(font, tr("radio.mc.discs_count", discs.size()), cx, cy, INK, false);
        RadioBlockEntity be = radio();
        QueueEntry cur = be == null ? null : be.current();
        for (int i = 0; i < DISCS_PER_ROW * 4; i++) {
            int x = discX(i), y = discY(i);
            g.fill(x, y, x + DISC_SLOT - 1, y + DISC_SLOT - 1, DIAL_MARK);
            g.fill(x + 1, y + 1, x + DISC_SLOT - 1, y + DISC_SLOT - 1, SLOT_LIGHT);
            g.fill(x + 1, y + 1, x + DISC_SLOT - 2, y + DISC_SLOT - 2, DIAL_SHADE);
            if (i >= discs.size()) continue;
            DiscEntry d = discs.get(i);
            boolean playing = cur != null && cur.kind() == QueueEntry.Kind.DISC && cur.ref().equals(d.id.toString());
            if (playing) g.fill(x + 1, y + 1, x + DISC_SLOT - 2, y + DISC_SLOT - 2, 0xFFE07A5F);
            g.renderItem(d.icon, x + 1, y + 1);
            if (mx >= x && mx < x + DISC_SLOT - 1 && my >= y && my < y + DISC_SLOT - 1) {
                g.fill(x + 1, y + 1, x + DISC_SLOT - 2, y + DISC_SLOT - 2, 0x60FFFFFF);
                hits.add(new Hit(x, y, DISC_SLOT - 1, DISC_SLOT - 1, () -> addDisc(d, true), d.song.description().copy()
                        .append("\n").append(Component.translatable("radio.tip.disc", time((int) d.song.lengthInSeconds())))));
            }
        }
        int hintY = discY(DISCS_PER_ROW * 4) + 2;
        line(g, discs.isEmpty() ? tr(slot >= 0 ? "radio.mc.no_disc_portable" : "radio.mc.no_disc") : tr("radio.mc.discs_hint"), cx, hintY, cw, discs.isEmpty() ? INK : INK_SOFT);
        g.drawString(font, tr("radio.mc.ambients"), cx, cy + 96, INK, false);
    }

    private void addDisc(DiscEntry d, boolean now) {
        send(new RadioNet.AddEntry(pos, slot, new QueueEntry(QueueEntry.Kind.DISC, d.song.description().getString(), "", "",
                (int) Math.ceil(d.song.lengthInSeconds()), d.id.toString(), ""), now));
    }

    // =====================================================================
    // Le poste
    // =====================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        super.renderBackground(g, mx, my, partial);
        drawCase(g);
    }

    private void drawCase(GuiGraphics g) {
        int x1 = left, y1 = top, x2 = left + W, y2 = top + H;
        // Antenne (comme sur le bloc) : barre fine argentée qui sort en haut à droite
        int ax = x2 - 70;
        g.fill(ax - 1, y1 - 12, ax + 4, y1 + 1, 0xFF000000);
        g.fill(ax, y1 - 11, ax + 3, y1, 0xFFB8B8C4);
        g.fill(ax, y1 - 11, ax + 1, y1, 0xFFE0E0EA);
        g.fill(ax - 3, y1 - 16, ax + 6, y1 - 10, 0xFF000000);
        g.fill(ax - 2, y1 - 15, ax + 5, y1 - 11, CHROME);
        if (boombox) {
            // Poignée noire au-dessus du boîtier
            int hx1 = x1 + 70, hx2 = x2 - 120;
            g.fill(hx1 - 1, y1 - 15, hx2 + 1, y1 + 1, 0xFF000000);
            g.fill(hx1, y1 - 14, hx2, y1 - 8, 0xFF2A2A2E);
            g.fill(hx1, y1 - 14, hx2, y1 - 13, 0xFF3E3E44);
            g.fill(hx1 + 6, y1 - 8, hx2 - 6, y1 + 1, 0xFF000000);
            g.fill(hx1, y1 - 8, hx1 + 6, y1, 0xFF2A2A2E);
            g.fill(hx2 - 6, y1 - 8, hx2, y1, 0xFF2A2A2E);
        }
        // Boîtier : bord sombre, reflet en haut à gauche, ombre en bas à droite
        g.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, 0xFF000000);
        g.fill(x1, y1, x2, y2, WOOD_EDGE);
        g.fill(x1 + 2, y1 + 2, x2 - 2, y2 - 2, WOOD);
        g.fill(x1 + 2, y1 + 2, x2 - 2, y1 + 4, WOOD_LIGHT);
        g.fill(x1 + 2, y1 + 2, x1 + 4, y2 - 2, WOOD_LIGHT);
        g.fill(x1 + 2, y2 - 4, x2 - 2, y2 - 2, WOOD_DARK);
        g.fill(x2 - 4, y1 + 2, x2 - 2, y2 - 2, WOOD_DARK);
        int gx1 = left + 10, gy1 = dy, gx2 = left + 54, gy2 = dy + dh;
        if (boombox) {
            // Deux haut-parleurs ronds l'un au-dessus de l'autre, comme sur la façade du bloc
            speaker(g, (gx1 + gx2) / 2, gy1 + dh / 4, 21);
            speaker(g, (gx1 + gx2) / 2, gy1 + dh * 3 / 4, 21);
        } else {
            // Grille du haut-parleur
            g.fill(gx1 - 1, gy1 - 1, gx2 + 1, gy2 + 1, 0xFF000000);
            for (int yy = gy1; yy < gy2; yy += 2) {
                for (int xx = gx1; xx < gx2; xx += 2) {
                    g.fill(xx, yy, xx + 2, yy + 2, ((xx - gx1) / 2 + (yy - gy1) / 2) % 2 == 0 ? 0xFF2B2B2B : 0xFF4A4A4A);
                }
            }
        }
        // Cadran crème
        g.fill(dx - 2, dy - 2, dx + dw + 2, dy + dh + 2, 0xFF000000);
        g.fill(dx, dy, dx + dw, dy + dh, DIAL);
        g.fill(dx, dy, dx + dw, dy + 1, DIAL_SHADE);
        g.fill(dx, dy, dx + 1, dy + dh, DIAL_SHADE);
        // Échelle du cadran avec les noms des onglets
        int sy = dy + 15;
        g.fill(dx + 4, sy, dx + dw - 4, sy + 1, DIAL_MARK);
        for (int xx = dx + 6; xx < dx + dw - 4; xx += 8) g.fill(xx, sy - ((xx - dx) % 32 == 6 ? 5 : 3), xx + 1, sy, DIAL_MARK);
        String[] labels = new String[Tab.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = tr("radio.tab." + Tab.values()[i].name().toLowerCase()).toUpperCase();
        for (int i = 0; i < labels.length; i++) {
            int lx = needleTarget(i);
            g.pose().pushPose();
            g.pose().translate(lx + 3, dy + 3, 0);
            g.pose().scale(0.5f, 0.5f, 1);
            g.drawString(font, labels[i], 0, 0, INK_SOFT, false);
            g.pose().popPose();
        }
        // L'aiguille glisse vers l'onglet choisi
        float target = needleTarget(tab.ordinal());
        needle = needle < 0 ? target : needle + (target - needle) * 0.25f;
        int nx = Math.round(needle);
        g.fill(nx, dy + 2, nx + 2, sy + 3, NEEDLE);
    }

    /** Haut-parleur rond : bord clair, membrane sombre striée, cache central. */
    private static void speaker(GuiGraphics g, int cxs, int cys, int r) {
        for (int yy = -r; yy <= r; yy++) {
            int half = (int) Math.floor(Math.sqrt(r * r - yy * yy + 0.5));
            int y = cys + yy;
            g.fill(cxs - half, y, cxs + half + 1, y + 1, 0xFF000000);
            int inner = (int) Math.floor(Math.sqrt(Math.max(0, (r - 1) * (r - 1) - yy * yy)));
            if (Math.abs(yy) < r) g.fill(cxs - inner, y, cxs + inner + 1, y + 1, 0xFF8A8A90);
            int cone = (int) Math.floor(Math.sqrt(Math.max(0, (r - 3) * (r - 3) - yy * yy)));
            if (Math.abs(yy) < r - 2) g.fill(cxs - cone, y, cxs + cone + 1, y + 1, (yy & 1) == 0 ? 0xFF2A2A2E : 0xFF36363A);
            int cap = (int) Math.floor(Math.sqrt(Math.max(0, 25 - yy * yy)));
            if (Math.abs(yy) < 5) g.fill(cxs - cap, y, cxs + cap + 1, y + 1, 0xFF4A4A50);
        }
        g.fill(cxs - 1, cys - 1, cxs + 2, cys + 2, 0xFF9A9AA2);
    }

    private int needleTarget(int i) {
        return dx + 10 + i * (dw - 20) / 4;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        RadioBlockEntity be = radio();
        var player = Minecraft.getInstance().player;
        if (be == null || player == null || (slot < 0 && player.distanceToSqr(pos.getCenter()) > 12 * 12)) {
            onClose();
            return;
        }
        super.render(g, mx, my, partial);
        hits.clear();
        switch (tab) {
            case SEARCH -> renderSearch(g, mx, my);
            case NOW -> renderNow(g, mx, my);
            case RADIOS -> renderRadios(g, mx, my);
            case MINECRAFT -> renderMinecraft(g, mx, my);
        }
        // Libellés des molettes
        g.drawString(font, volumeKnob.label(), volumeKnob.getX() + 28, volumeKnob.getY() + 8, LABEL, false);
        g.drawString(font, rangeKnob.label(), rangeKnob.getX() + 28, rangeKnob.getY() + 8, LABEL, false);
        for (Hit h : hits) {
            if (h.tip != null && mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                g.renderTooltip(font, font.split(h.tip, 200), mx, my);
                break;
            }
        }
    }

    private interface RowPainter {
        void paint(int index, int x, int y);
    }

    /** Une liste sur le cadran, qui défile à la molette de la souris. */
    private void list(GuiGraphics g, int mx, int my, int lx, int ly, int lw, int lh, int count, int rowH, RowPainter painter) {
        int maxScroll = Math.max(0, count * rowH - lh);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        g.fill(lx, ly - 1, lx + lw, ly, DIAL_MARK);
        g.enableScissor(lx, ly, lx + lw, ly + lh);
        int before = hits.size();
        for (int i = 0; i < count; i++) {
            int y = ly + i * rowH - scroll;
            if (y + rowH < ly || y > ly + lh) continue;
            painter.paint(i, lx, y);
            g.fill(lx, y + rowH - 1, lx + lw - (maxScroll > 0 ? 4 : 0), y + rowH, DIAL_LINE);
        }
        g.disableScissor();
        // Les zones cliquables à moitié cachées ne comptent pas
        hits.subList(before, hits.size()).removeIf(h -> h.y < ly || h.y + h.h > ly + lh);
        if (maxScroll > 0) {
            int barH = Math.max(12, lh * lh / (count * rowH));
            int barY = ly + (lh - barH) * scroll / maxScroll;
            g.fill(lx + lw - 3, ly, lx + lw, ly + lh, DIAL_SHADE);
            g.fill(lx + lw - 3, barY, lx + lw, barY + barH, WOOD);
        }
    }

    private void fieldFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, WOOD_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, FIELD);
    }

    private void line(GuiGraphics g, String s, int x, int y, int w, int color) {
        g.drawString(font, font.plainSubstrByWidth(s, w), x, y, color, false);
    }

    private void centered(GuiGraphics g, String s, int y, int color) {
        String t = font.plainSubstrByWidth(s, cw);
        g.drawString(font, t, cx + (cw - font.width(t)) / 2, y, color, false);
    }

    /** Petit bouton chromé dans une liste. */
    private void mini(GuiGraphics g, int mx, int my, int x, int y, int icon, Runnable action, Component tip) {
        boolean hover = mx >= x && mx < x + 16 && my >= y && my < y + 16;
        chrome(g, x, y, 16, 16, hover, false);
        icon(g, icon, x, y);
        hits.add(new Hit(x, y, 16, 16, action, tip));
    }

    /** Un bouton-poussoir gris, enfoncé ou non. */
    private static void chrome(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean pressed) {
        // Comme un bouton du jeu, en gris clair : reflet en haut à gauche, ombre fine en bas à droite
        g.fill(x, y, x + w, y + h, 0xFF000000);
        if (pressed) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF6E6E6E);
            g.fill(x + 2, y + 2, x + w - 1, y + h - 1, CHROME_ON);
        } else {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF6E6E6E);
            g.fill(x + 1, y + 1, x + w - 2, y + h - 2, CHROME_LIGHT);
            g.fill(x + 2, y + 2, x + w - 2, y + h - 2, hover ? 0xFFDDE0EE : CHROME);
        }
    }

    /** Une icône de 16 px (textures/gui/icons.png), décalée d'un pixel quand le bouton est enfoncé. */
    private static void icon(GuiGraphics g, int index, int x, int y) {
        g.blit(ICONS, x, y, index * 16, 0, 16, 16, ICON_COUNT * 16, 16);
    }

    private boolean cover(GuiGraphics g, String url, int x, int y, int size) {
        ResourceLocation rl = CoverCache.get(url);
        if (rl == null) {
            if (size <= 24) g.fill(x, y, x + size, y + size, DIAL_SHADE);
            return false;
        }
        g.blit(rl, x, y, size, size, 0, 0, 128, 128, 128, 128);
        return true;
    }

    // =====================================================================
    // Souris et clavier
    // =====================================================================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        clickX = mx;
        for (Hit h : new ArrayList<>(hits)) {
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                int disc = tab == Tab.MINECRAFT ? discAt(h) : -1;
                if (disc >= 0 && hasShiftDown()) {
                    control("eject", disc, ""); // Maj + clic : on reprend le disque
                } else if (disc >= 0 && button == 1) {
                    addDisc(discs.get(disc), false); // clic droit : à la suite de la file
                } else {
                    h.action.run();
                }
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                return true;
            }
        }
        return false;
    }

    private int discAt(Hit h) {
        for (int i = 0; i < discs.size(); i++) if (discX(i) == h.x && discY(i) == h.y) return i;
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (volumeKnob.isMouseOver(mx, my)) return volumeKnob.mouseScrolled(mx, my, sx, sy);
        if (rangeKnob.isMouseOver(mx, my)) return rangeKnob.mouseScrolled(mx, my, sx, sy);
        scroll -= (int) (sy * 18);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (tab == Tab.SEARCH && searchBox != null && searchBox.isFocused()) {
                doSearch();
                return true;
            }
            if (tab == Tab.RADIOS && stationBox != null && stationBox.isFocused()) {
                searchStations();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    private void control(String action) {
        control(action, 0, "");
    }

    private void control(String action, int value, String text) {
        send(new RadioNet.Control(pos, slot, action, value, text));
    }

    private void send(CustomPacketPayload p) {
        PacketDistributor.sendToServer(p);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String time(int sec) {
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    // =====================================================================
    // Commandes du poste
    // =====================================================================

    /** Bouton-poussoir gris ; reste enfoncé quand {@code pressed} est vrai (onglet choisi, ambiance en cours). */
    private final class Key extends Button {
        private final java.util.function.BooleanSupplier pressed;

        Key(int x, int y, int w, int h, Component label, OnPress onPress, java.util.function.BooleanSupplier pressed) {
            super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
            this.pressed = pressed;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            boolean on = pressed.getAsBoolean();
            chrome(g, getX(), getY(), getWidth(), getHeight(), isHovered(), on);
            Component m = getMessage();
            String s = font.plainSubstrByWidth(m.getString(), getWidth() - 4);
            int ty = getY() + (getHeight() - 8) / 2 + (on ? 1 : 0);
            g.drawString(font, s, getX() + (getWidth() - font.width(s)) / 2, ty, on ? 0x000000 : 0x333333, false);
        }
    }

    /** Bouton-poussoir avec une icône dessinée (lecture, pause, répéter…). */
    private final class IconKey extends Button {
        private final java.util.function.IntSupplier icon;
        private final java.util.function.BooleanSupplier pressed;

        IconKey(int x, int y, int w, int h, java.util.function.IntSupplier icon, OnPress onPress, java.util.function.BooleanSupplier pressed) {
            super(x, y, w, h, Component.empty(), onPress, DEFAULT_NARRATION);
            this.icon = icon;
            this.pressed = pressed;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            boolean on = pressed.getAsBoolean();
            chrome(g, getX(), getY(), getWidth(), getHeight(), isHovered(), on);
            int off = on ? 1 : 0;
            icon(g, icon.getAsInt(), getX() + (getWidth() - 16) / 2 + off, getY() + (getHeight() - 16) / 2 + off);
        }
    }

    /**
     * Molette ronde : on la tourne en glissant la souris (ou avec la molette de la souris).
     * La valeur part au serveur quand on lâche.
     */
    private final class Knob extends AbstractWidget {
        private double value;
        private final java.util.function.DoubleFunction<Component> text;
        private final java.util.function.DoubleConsumer onSet;
        private double dragStartX, dragStartValue;

        Knob(int x, int y, double value, java.util.function.DoubleFunction<Component> text, java.util.function.DoubleConsumer onSet) {
            super(x, y, 24, 24, Component.empty());
            this.value = Math.max(0, Math.min(1, value));
            this.text = text;
            this.onSet = onSet;
        }

        Component label() {
            return text.apply(value);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            int x = getX(), y = getY();
            // Molette dessinée (bord cranté, face claire), puis le repère qui tourne
            g.blit(KNOB, x, y, 0, 0, 24, 24, 24, 24);
            if (isHovered()) g.fill(x + 6, y + 6, x + 18, y + 18, 0x18FFFFFF);
            // Repère : de -135° (minimum) à +135° (maximum), point par point
            double a = Math.toRadians(-135 + value * 270);
            for (int d = 1; d <= 6; d++) {
                int px = x + 12 + (int) Math.floor(Math.sin(a) * d);
                int py = y + 12 - (int) Math.ceil(Math.cos(a) * d);
                g.fill(px, py, px + 1, py + 1, 0xFF2B2B2B);
                g.fill(px + 1, py, px + 2, py + 1, 0xFF2B2B2B);
            }
        }

        @Override
        public void onClick(double mx, double my) {
            dragStartX = mx;
            dragStartValue = value;
        }

        @Override
        protected void onDrag(double mx, double my, double ddx, double ddy) {
            value = Math.max(0, Math.min(1, dragStartValue + (mx - dragStartX) / 120.0));
        }

        @Override
        public void onRelease(double mx, double my) {
            onSet.accept(value);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double sx, double sy) {
            value = Math.max(0, Math.min(1, value + sy * 0.05));
            onSet.accept(value);
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
