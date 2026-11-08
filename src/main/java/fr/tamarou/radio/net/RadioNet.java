package fr.tamarou.radio.net;

import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.block.QueueEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Les messages entre le jeu du joueur et le serveur. */
public final class RadioNet {
    private RadioNet() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, name));
    }

    // ---------- Joueur → serveur ----------

    /**
     * Ajoute une chanson (recherche ou disque). {@code slot} : -1 pour une radio posée en {@code pos},
     * sinon la case d'inventaire de la radio portable.
     */
    public record AddEntry(BlockPos pos, int slot, QueueEntry entry, boolean playNow) implements CustomPacketPayload {
        public static final Type<AddEntry> TYPE = id("add_entry");
        public static final StreamCodec<FriendlyByteBuf, AddEntry> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, AddEntry::pos, ByteBufCodecs.VAR_INT, AddEntry::slot, QueueEntry.STREAM_CODEC, AddEntry::entry,
                ByteBufCodecs.BOOL, AddEntry::playNow, AddEntry::new);

        @Override
        public Type<AddEntry> type() { return TYPE; }
    }

    /** Un bouton : suivant, pause, volume, radio en direct… */
    public record Control(BlockPos pos, int slot, String action, int value, String text) implements CustomPacketPayload {
        public static final Type<Control> TYPE = id("control");
        public static final StreamCodec<ByteBuf, Control> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Control::pos, ByteBufCodecs.VAR_INT, Control::slot, ByteBufCodecs.stringUtf8(32), Control::action,
                ByteBufCodecs.VAR_INT, Control::value, ByteBufCodecs.stringUtf8(1024), Control::text, Control::new);

        @Override
        public Type<Control> type() { return TYPE; }
    }

    // ---------- Serveur → joueur ----------

    /** Ce que le serveur autorise (envoyé à la connexion). */
    public record ServerInfo(boolean search, boolean stations, int maxRange) implements CustomPacketPayload {
        public static final Type<ServerInfo> TYPE = id("server_info");
        public static final StreamCodec<ByteBuf, ServerInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ServerInfo::search, ByteBufCodecs.BOOL, ServerInfo::stations,
                ByteBufCodecs.VAR_INT, ServerInfo::maxRange, ServerInfo::new);

        @Override
        public Type<ServerInfo> type() { return TYPE; }
    }
}
