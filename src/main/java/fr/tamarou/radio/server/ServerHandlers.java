package fr.tamarou.radio.server;

import fr.tamarou.radio.RadioConfig;
import fr.tamarou.radio.block.QueueEntry;
import fr.tamarou.radio.block.RadioBlockEntity;
import fr.tamarou.radio.media.YouTubeAudio;
import fr.tamarou.radio.net.RadioNet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Ce que fait le serveur quand un joueur touche à une radio. Tout est vérifié ici. */
public final class ServerHandlers {
    private static final List<String> COVER_HOSTS = List.of("dzcdn.net", "scdn.co", "spotifycdn.com");
    private static final List<String> AMBIENTS = List.of("overworld", "nether", "end", "menu", "underwater", "creative");

    private ServerHandlers() {}

    /** La radio visée : posée en {@code pos} (slot = -1), ou la radio portable de la case {@code slot}. */
    private static RadioBlockEntity radio(ServerPlayer p, net.minecraft.core.BlockPos pos, int slot, boolean control) {
        if (slot >= 0) {
            if (slot >= p.getInventory().getContainerSize()) return null;
            net.minecraft.world.item.ItemStack stack = p.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof fr.tamarou.radio.item.PortableRadioItem)) return null;
            return fr.tamarou.radio.item.PortableRadioItem.open(stack, p.level(), p.blockPosition());
        }
        if (!p.level().isLoaded(pos) || p.distanceToSqr(Vec3.atCenterOf(pos)) > 12 * 12) return null;
        if (!(p.level().getBlockEntity(pos) instanceof RadioBlockEntity be)) return null;
        if (control && !canControl(p, be)) {
            p.displayClientMessage(Component.translatable("radio.msg.no_permission"), true);
            return null;
        }
        return be;
    }

    public static boolean canControl(ServerPlayer p, RadioBlockEntity be) {
        boolean op = p.hasPermissions(2);
        return switch (RadioConfig.WHO_CAN_CONTROL.get()) {
            case EVERYONE -> true;
            case OWNER_AND_OPS -> op || p.getUUID().equals(be.owner()) || be.owner() == null;
            case OPS -> op;
        };
    }

    public static void addEntry(RadioNet.AddEntry msg, IPayloadContext ctx) {
        ServerPlayer p = (ServerPlayer) ctx.player();
        RadioBlockEntity be = radio(p, msg.pos(), msg.slot(), true);
        if (be == null) return;
        String who = p.getGameProfile().getName();
        if (be.waitingFrom(who) >= RadioConfig.MAX_QUEUE_PER_PLAYER.get() && be.current() != null && !msg.playNow()) {
            p.displayClientMessage(Component.translatable("radio.msg.queue_limit", RadioConfig.MAX_QUEUE_PER_PLAYER.get()), true);
            return;
        }
        QueueEntry e = msg.entry();
        QueueEntry clean = switch (e.kind()) {
            case SONG -> {
                if (!RadioConfig.ALLOW_SEARCH.get()) {
                    p.displayClientMessage(Component.translatable("radio.msg.search_off"), true);
                    yield null;
                }
                if (!YouTubeAudio.VIDEO_ID.matcher(e.ref()).matches() || e.durationSec() <= 0 || e.durationSec() > 3 * 3600) yield null;
                yield new QueueEntry(QueueEntry.Kind.SONG, cut(e.title(), 120), cut(e.artist(), 120),
                        coverOk(e.cover()) ? e.cover() : "", e.durationSec(), e.ref(), who);
            }
            case DISC -> discEntry(p, be, e.ref());
        };
        if (clean != null) be.add(clean, msg.playNow());
    }

    /** Un disque ne se joue que s'il est rangé dans cette radio. */
    private static QueueEntry discEntry(ServerPlayer p, RadioBlockEntity be, String ref) {
        ResourceLocation id = ResourceLocation.tryParse(ref);
        if (id == null || !be.hasDisc(id.toString())) return null;
        JukeboxSong song = p.server.registryAccess().registryOrThrow(Registries.JUKEBOX_SONG).get(id);
        return song == null ? null : new QueueEntry(QueueEntry.Kind.DISC, song.description().getString(), "", "",
                (int) Math.ceil(song.lengthInSeconds()), id.toString(), p.getGameProfile().getName());
    }

    public static void playDisc(ServerPlayer p, RadioBlockEntity be, String songId, boolean now) {
        QueueEntry e = discEntry(p, be, songId);
        if (e != null) be.add(e, now);
    }

    public static void control(RadioNet.Control msg, IPayloadContext ctx) {
        ServerPlayer p = (ServerPlayer) ctx.player();
        RadioBlockEntity be = radio(p, msg.pos(), msg.slot(), true);
        if (be == null) return;
        int v = msg.value();
        switch (msg.action()) {
            case "skip" -> be.skip();
            case "prev" -> be.previous();
            case "pause" -> be.togglePause();
            case "jump" -> be.jump(v);
            case "seek" -> be.seek(v);
            case "remove" -> be.remove(v);
            case "clear" -> be.clearUpcoming();
            case "volume" -> be.setVolume(v);
            case "range" -> be.setRange(v, RadioConfig.MAX_RANGE.get());
            case "repeat" -> be.cycleRepeat();
            case "shuffle" -> be.toggleShuffle();
            case "resume" -> be.resumeQueue();
            case "stop" -> be.stop();
            case "headphones" -> be.toggleHeadphones();
            case "eject" -> {
                // Le disque retourne dans l'inventaire (ou tombe s'il est plein)
                net.minecraft.world.item.ItemStack s = be.ejectDisc(v);
                if (!s.isEmpty() && !p.getInventory().add(s)) p.drop(s, false);
            }
            case "ambient" -> {
                if (AMBIENTS.contains(msg.text())) be.playAmbient(msg.text());
            }
            case "station" -> {
                if (!RadioConfig.ALLOW_STATIONS.get()) {
                    p.displayClientMessage(Component.translatable("radio.msg.stations_off"), true);
                    return;
                }
                String[] parts = msg.text().split("\n", 3);
                if (parts.length < 2) return;
                String url = parts[1].trim();
                if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 500) return;
                String codec = parts.length > 2 && parts[2].trim().equalsIgnoreCase("OGG") ? "OGG" : "MP3";
                be.playStation(cut(parts[0].trim(), 80), url, codec);
            }
            default -> {}
        }
    }

    private static boolean coverOk(String url) {
        if (url.isEmpty() || url.length() > 400) return false;
        try {
            URI u = URI.create(url);
            String h = u.getHost();
            if (!"https".equals(u.getScheme()) || h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            for (String ok : COVER_HOSTS) if (h.equals(ok) || h.endsWith("." + ok)) return true;
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
