package fr.tamarou.radio.item;

import fr.tamarou.radio.block.RadioBlock;
import fr.tamarou.radio.block.SpeakerBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** L'enceinte en main : clic droit sur une radio pour la relier, puis on la pose où on veut. */
public class SpeakerItem extends BlockItem {
    public SpeakerItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        if (ctx.getLevel().getBlockState(pos).getBlock() instanceof RadioBlock) {
            if (!ctx.getLevel().isClientSide) {
                SpeakerBlock.setLink(ctx.getItemInHand(), pos);
                if (ctx.getPlayer() != null) ctx.getPlayer().displayClientMessage(
                        Component.translatable("radio.speaker.linked", pos.getX(), pos.getY(), pos.getZ()), true);
            }
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
        return super.useOn(ctx);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        BlockPos link = SpeakerBlock.linkOf(stack);
        lines.add((link == null
                ? Component.translatable("radio.speaker.tip_unlinked")
                : Component.translatable("radio.speaker.tip_linked", link.getX(), link.getY(), link.getZ()))
                .withStyle(ChatFormatting.GRAY));
    }
}
