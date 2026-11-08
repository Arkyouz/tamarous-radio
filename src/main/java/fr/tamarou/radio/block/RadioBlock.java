package fr.tamarou.radio.block;

import com.mojang.serialization.MapCodec;
import fr.tamarou.radio.RadioMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import fr.tamarou.radio.server.ServerHandlers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;

/** Le bloc radio : un poste posé, tourné vers le joueur. Clic droit : ouvre l'écran. */
public class RadioBlock extends BaseEntityBlock {
    public static final MapCodec<RadioBlock> CODEC = simpleCodec(p -> new RadioBlock(p, RadioBlock.WOOD));
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** Branché par le côté client : ouvre l'écran de la radio. */
    public static Consumer<BlockPos> openScreen = pos -> {};

    /** Petit poste en bois : boîtier, façade, molettes, antenne (en deux marches pour suivre son inclinaison). */
    public static final double[][] WOOD = {
            {1.5, 0, 4.5, 14.5, 10, 11.5},
            {2.75, 2.5, 4, 6.75, 4, 4.75},
            {4, 10, 7.5, 12, 12.5, 8.5},
            {12.5, 10, 9.5, 14.5, 13, 10.5},
            {14, 13, 9.5, 16, 16, 10.5}};
    /** Radio-cassette : boîtier, façade, poignée, antenne. */
    public static final double[][] BOOMBOX = {
            {0, 0, 4.5, 16, 9.25, 11.5},
            {2, 9.25, 7.5, 14, 13, 8.5},
            {14.25, 9.25, 9.5, 15.75, 13, 10.5},
            {15, 13, 9.5, 16, 16, 10.5}};

    private final java.util.Map<Direction, VoxelShape> shapes = new java.util.EnumMap<>(Direction.class);

    /** Même rotation que le fichier blockstate (y = 90 pour l'est, 180 pour le sud, 270 pour l'ouest). */
    private static VoxelShape rotated(double[] p, Direction d) {
        double x1 = p[0], z1 = p[2], x2 = p[3], z2 = p[5];
        return switch (d) {
            case EAST -> Block.box(16 - z2, p[1], x1, 16 - z1, p[4], x2);
            case SOUTH -> Block.box(16 - x2, p[1], 16 - z2, 16 - x1, p[4], 16 - z1);
            case WEST -> Block.box(z1, p[1], 16 - x2, z2, p[4], 16 - x1);
            default -> Block.box(x1, p[1], z1, x2, p[4], z2);
        };
    }

    /** @param parts les morceaux du modèle (tourné vers le nord), pour le contour quand on vise le bloc */
    public RadioBlock(Properties props, double[][] parts) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape s = Shapes.empty();
            for (double[] p : parts) s = Shapes.or(s, rotated(p, d));
            shapes.put(d, s);
        }
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

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
        return new RadioBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, RadioMod.RADIO_BE.get(), RadioBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p && level.getBlockEntity(pos) instanceof RadioBlockEntity be) {
            be.setOwner(p.getUUID(), p.getGameProfile().getName());
        }
    }

    /** Clic droit avec un disque : il est rangé dans la radio (et joue tout de suite si elle ne joue rien). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        // Une enceinte en main : c'est elle qui gère le clic (elle se relie à cette radio)
        if (stack.getItem() instanceof fr.tamarou.radio.item.SpeakerItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (!stack.has(DataComponents.JUKEBOX_PLAYABLE)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof RadioBlockEntity be) || !(player instanceof ServerPlayer sp)) {
            return ItemInteractionResult.CONSUME;
        }
        if (!ServerHandlers.canControl(sp, be)) {
            sp.displayClientMessage(Component.translatable("radio.msg.no_permission"), true);
            return ItemInteractionResult.CONSUME;
        }
        String id = RadioBlockEntity.songId(stack, level.registryAccess());
        if (id != null && be.hasDisc(id)) {
            sp.displayClientMessage(Component.translatable("radio.msg.disc_already"), true);
            return ItemInteractionResult.CONSUME;
        }
        ItemStack one = stack.copyWithCount(1);
        if (!be.insertDisc(one)) {
            sp.displayClientMessage(Component.translatable("radio.msg.disc_full"), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!player.getAbilities().instabuild) stack.shrink(1);
        sp.displayClientMessage(Component.translatable("radio.msg.disc_added", one.getHoverName()), true);
        if (!be.isPlaying()) ServerHandlers.playDisc(sp, be, id, true);
        return ItemInteractionResult.CONSUME;
    }

    /** Radio cassée : les disques rangés tombent par terre. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof RadioBlockEntity be) {
            for (ItemStack s : be.discs()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s);
            be.discs().clear();
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) openScreen.accept(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof RadioBlockEntity be && be.isPlaying() ? 15 : 0;
    }
}
