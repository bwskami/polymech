package com.mss.polymech.client.renderer;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import com.mss.polymech.block.entity.ConveyorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ConveyorBlockEntityRenderer implements BlockEntityRenderer<ConveyorBlockEntity> {

    public ConveyorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ConveyorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;
        if (be.getTransportedItems().isEmpty()) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();

        for (ConveyorBlockEntity.TransportedItem item : be.getTransportedItems()) {
            if (item.stack.isEmpty()) continue;

            double progress = Math.min(item.progress + 0.02 * partialTick, 1.0);

            double dx = facing.getStepX() * (progress - 0.5);
            double dz = facing.getStepZ() * (progress - 0.5);
            double dy = 0.25;
            if (type == ConveyorType.UP) {
                dy += progress;
            } else if (type == ConveyorType.DOWN) {
                dy += (1.0 - progress);
            }

            poseStack.pushPose();
            poseStack.translate(0.5 + dx, dy, 0.5 + dz);
            poseStack.scale(0.4f, 0.4f, 0.4f);

            poseStack.mulPose(Axis.XP.rotationDegrees(90));

            itemRenderer.renderStatic(item.stack, ItemDisplayContext.FIXED,
                    packedLight, packedOverlay, poseStack, bufferSource, be.getLevel(), 0);

            poseStack.popPose();
        }
    }
}
