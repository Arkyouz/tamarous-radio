package fr.tamarou.radio.item;

import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.block.RadioBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * La radio portable. Elle fait tout ce que fait une radio posée ; son état (file d'attente, disques…)
 * est rangé dans l'objet. Clic droit : l'écran. Avec un disque dans l'autre main : le disque est rangé dedans.
 */
public class PortableRadioItem extends Item {
    private static final String KEY = "radio";
    /** Branché par le côté client : ouvre l'écran pour la case d'inventaire donnée. */
    public static IntConsumer openScreen = slot -> {};

    public PortableRadioItem(Properties props) {
        super(props);
    }

    /** La radio rangée dans l'objet, prête à servir (comme une radio posée à la position donnée). */
    public static RadioBlockEntity load(ItemStack stack, Level level, BlockPos pos) {
        RadioBlockEntity be = new RadioBlockEntity(pos, RadioMod.RADIO.get().defaultBlockState());
        be.setLevel(level);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            if (tag.contains(KEY)) be.loadWithComponents(tag.getCompound(KEY), level.registryAccess());
        }
        return be;
    }

    /** Une radio qui recopie chacun de ses changements dans l'objet. */
    public static RadioBlockEntity open(ItemStack stack, Level level, BlockPos pos) {
        RadioBlockEntity be = load(stack, level, pos);
        be.setOnChange(() -> save(stack, be));
        return be;
    }

    public static void save(ItemStack stack, RadioBlockEntity be) {
        CompoundTag state = be.saveWithoutMetadata(be.getLevel().registryAccess());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(KEY, state));
    }

    /** Lecture rapide : est-ce qu'une chanson de la file est en cours (pour savoir s'il faut passer à la suivante) ? */
    private static boolean queuePlaying(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag t = data.copyTag().getCompound(KEY);
        return "QUEUE".equals(t.getString("mode")) && !t.getBoolean("paused");
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (other.has(DataComponents.JUKEBOX_PLAYABLE)) {
            // Un disque dans l'autre main : on le range dans la radio
            if (!level.isClientSide) {
                RadioBlockEntity be = open(stack, level, player.blockPosition());
                String id = RadioBlockEntity.songId(other, level.registryAccess());
                if (id != null && be.hasDisc(id)) {
                    player.displayClientMessage(Component.translatable("radio.msg.disc_already"), true);
                } else if (be.insertDisc(other.copyWithCount(1))) {
                    player.displayClientMessage(Component.translatable("radio.msg.disc_added", other.getHoverName()), true);
                    if (!player.getAbilities().instabuild) other.shrink(1);
                } else {
                    player.displayClientMessage(Component.translatable("radio.msg.disc_full"), true);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (level.isClientSide) {
            int slot = hand == InteractionHand.OFF_HAND ? 40 : player.getInventory().selected;
            openScreen.accept(slot);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Passe à la chanson suivante quand celle en cours est finie, même radio rangée dans l'inventaire. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof Player) || level.getGameTime() % 10 != 0 || !queuePlaying(stack)) return;
        RadioBlockEntity be = open(stack, level, entity.blockPosition());
        RadioBlockEntity.serverTick(level, be.getBlockPos(), be.getBlockState(), be);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        boolean phones = data == null || !data.copyTag().getCompound(KEY).contains("headphones")
                || data.copyTag().getCompound(KEY).getBoolean("headphones");
        lines.add(Component.translatable(phones ? "radio.portable.tip_phones" : "radio.portable.tip_speaker")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem(); // pas de « secousse » à chaque changement de chanson
    }
}
