package fr.tamarou.radio.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Une chanson de la file d'attente.
 * <ul>
 *   <li>{@code SONG} : chanson trouvée par la recherche ; {@code ref} = vidéo dont on lit le son</li>
 *   <li>{@code DISC} : disque rangé dans la radio ; {@code ref} = identifiant du disque (minecraft:cat)</li>
 * </ul>
 */
public record QueueEntry(Kind kind, String title, String artist, String cover, int durationSec, String ref, String addedBy) {
    public enum Kind { SONG, DISC }

    public static final StreamCodec<FriendlyByteBuf, QueueEntry> STREAM_CODEC = StreamCodec.of(
            (buf, e) -> {
                buf.writeEnum(e.kind);
                buf.writeUtf(e.title, 200);
                buf.writeUtf(e.artist, 200);
                buf.writeUtf(e.cover, 400);
                buf.writeVarInt(e.durationSec);
                buf.writeUtf(e.ref, 200);
                buf.writeUtf(e.addedBy, 40);
            },
            buf -> new QueueEntry(buf.readEnum(Kind.class), buf.readUtf(200), buf.readUtf(200), buf.readUtf(400),
                    buf.readVarInt(), buf.readUtf(200), buf.readUtf(40)));

    public String display() {
        return artist.isEmpty() ? title : artist + " - " + title;
    }

    public QueueEntry withAddedBy(String name) {
        return new QueueEntry(kind, title, artist, cover, durationSec, ref, name);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind.name());
        t.putString("title", title);
        t.putString("artist", artist);
        t.putString("cover", cover);
        t.putInt("duration", durationSec);
        t.putString("ref", ref);
        t.putString("by", addedBy);
        return t;
    }

    public static QueueEntry load(CompoundTag t) {
        Kind k;
        try {
            k = Kind.valueOf(t.getString("kind"));
        } catch (IllegalArgumentException e) {
            return null; // ancien type (fichiers MP3, retirés)
        }
        return new QueueEntry(k, t.getString("title"), t.getString("artist"), t.getString("cover"),
                t.getInt("duration"), t.getString("ref"), t.getString("by"));
    }
}
