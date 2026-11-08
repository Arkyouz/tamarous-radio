package fr.tamarou.radio.block;

import fr.tamarou.radio.RadioMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Une enceinte posée : elle retient la radio à laquelle elle est reliée. */
public class SpeakerBlockEntity extends BlockEntity {
    private BlockPos link;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(RadioMod.SPEAKER_BE.get(), pos, state);
    }

    public BlockPos link() {
        return link;
    }

    /** La grande enceinte porte deux fois plus loin que la radio. */
    public boolean big() {
        return getBlockState().is(RadioMod.SPEAKER_BIG.get());
    }

    public void setLink(BlockPos p) {
        link = p;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (link != null) tag.putLong("link", link.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        link = tag.contains("link") ? BlockPos.of(tag.getLong("link")) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) RadioMod.clientSpeakers().add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) RadioMod.clientSpeakers().remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && level.isClientSide) RadioMod.clientSpeakers().remove(this);
    }
}
