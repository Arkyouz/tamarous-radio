package fr.tamarou.radio.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Enceinte : elle diffuse la radio à laquelle elle est reliée. Pour la relier : clic droit sur une radio
 * avec l'enceinte en main, puis la poser.
 */
public class SpeakerBlock extends BaseEntityBlock {
    public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(p -> new SpeakerBlock(p, false));
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** Distance maximum entre une enceinte et sa radio (au-delà, la radio n'est plus chargée chez les joueurs). */
    public static final int MAX_LINK = 64;
    private static final String LINK = "radio_link";

    private final boolean big;
    private final java.util.Map<Direction, VoxelShape> shapes = new java.util.EnumMap<>(Direction.class);

    public SpeakerBlock(Properties props, boolean big) {
        super(props);
        this.big = big;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            shapes.put(d, big ? Shapes.block() : (d.getAxis() == Direction.Axis.Z
                    ? Block.box(3, 0, 4, 13, 14, 12) : Block.box(4, 0, 3, 12, 14, 13)));
        }
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    // ---------- Liaison rangée dans l'objet ----------

    public static BlockPos linkOf(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        CompoundTag t = d.copyTag();
        return t.contains(LINK) ? BlockPos.of(t.getLong(LINK)) : null;
    }

    public static void setLink(ItemStack stack, BlockPos radio) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.putLong(LINK, radio.asLong()));
    }

    // ---------- Bloc ----------

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState s, Rotation r) {
        return s.setValue(FACING, r.rotate(s.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState s, Mirror m) {
        return s.rotate(m.getRotation(s.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes.get(s.getValue(FACING));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof SpeakerBlockEntity be)) return;
        BlockPos radio = linkOf(stack);
        Player p = placer instanceof Player pl ? pl : null;
        if (radio == null) {
            if (p != null) p.displayClientMessage(Component.translatable("radio.speaker.not_linked"), true);
        } else if (!radio.closerThan(pos, MAX_LINK)) {
            if (p != null) p.displayClientMessage(Component.translatable("radio.speaker.too_far", MAX_LINK), true);
        } else {
            be.setLink(radio);
            if (p != null) p.displayClientMessage(Component.translatable("radio.speaker.placed",
                    radio.getX(), radio.getY(), radio.getZ()), true);
        }
    }

    /** Clic droit sur une enceinte posée : dit à quelle radio elle est reliée. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
            BlockPos r = be.link();
            boolean ok = r != null && level.isLoaded(r) && level.getBlockState(r).getBlock() instanceof RadioBlock;
            player.displayClientMessage(ok
                    ? Component.translatable("radio.speaker.info", r.getX(), r.getY(), r.getZ())
                    : Component.translatable("radio.speaker.not_linked"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public boolean big() {
        return big;
    }
}
