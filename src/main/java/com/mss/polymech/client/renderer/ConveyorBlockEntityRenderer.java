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
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 传送带渲染器。
 * <p>
 * 无状态渲染器。视觉位置由 {@link ConveyorBlockEntity#getVisualProgress(int)} 提供，
 * 该值在客户端根据 buffer 索引和计时器纯计算得出。
 * </p>
 */
public class ConveyorBlockEntityRenderer implements BlockEntityRenderer<ConveyorBlockEntity> {
    private static final float RENDER_SCALE = 0.65f;
    private static final double BASE_Y = 0.25;
    private static final double SLOPE_HEIGHT = 1.0;

    public ConveyorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ConveyorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        List<ItemStack> contents = be.getBufferContents();
        if (contents.isEmpty()) return;

        int totalTicks = be.getSpec().slotCount() * be.getSpec().ticksPerSlot();
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();

        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty()) continue;

            // partialTick 插值（0~1）：平滑两帧之间的位置。
            // timer 在客户端 tick(20fps) 时 +1，renderer 60fps 运行，中间帧通过插值补全。
            // timer=0 时用 partialTick 渲染 0 → 1 的起始段而非直接跳到 1/totalTicks。
            int timer = be.getTimer(i);
            // partialTick 插值（0~1）：平滑两 tick 之间的位置，补全 20fps→60fps 的中间帧
            double p = (timer + partialTick) / totalTicks;

            // 出口延展：p > 1.0 时仍继续渲染在方块边缘（位置被 clamp 到 1.0），
            // 直到 p >= 1.15（约 3 帧）才停止。
            // 这填补了"源传送带移除 → 目标传送带添加"之间的同步延迟，消除闪烁。
            if (p >= 1.15) continue;
            double renderP = Math.min(p, 1.0);

            double dx = facing.getStepX() * (renderP - 0.5);
            double dz = facing.getStepZ() * (renderP - 0.5);
            double dy = BASE_Y;
            if (type == ConveyorType.UP) {
                dy += renderP * SLOPE_HEIGHT;
            } else if (type == ConveyorType.DOWN) {
                dy += (1.0 - renderP) * SLOPE_HEIGHT;
            }

            poseStack.pushPose();
            poseStack.translate(0.5 + dx, dy, 0.5 + dz);
            poseStack.scale(RENDER_SCALE, RENDER_SCALE, RENDER_SCALE);
            poseStack.mulPose(Axis.XP.rotationDegrees(90));
            itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED,
                    packedLight, packedOverlay, poseStack, bufferSource, be.getLevel(), 0);
            poseStack.popPose();
        }
    }
}