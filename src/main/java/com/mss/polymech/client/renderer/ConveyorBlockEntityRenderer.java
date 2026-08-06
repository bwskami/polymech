package com.mss.polymech.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import com.mss.polymech.block.entity.BeltItem;
import com.mss.polymech.block.entity.ConveyorBlockEntity;
import com.mss.polymech.item.NetworkToolItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 传送带物品包渲染器。
 * <p>
 * 直接渲染 BE 内的 {@link BeltItem} 队列：prevProgress → progress 按 partialTick
 * 线性插值（客户端确定性模拟 + 快照对齐，纯移动零网络包也不抽搐）。
 * 物品平躺在带面上随带运动，转弯入场时平滑转向。
 * </p>
 */
public class ConveyorBlockEntityRenderer implements BlockEntityRenderer<ConveyorBlockEntity> {

    /** 转弯转向过渡区间：入场后该进度内从来源朝向旋转到本格朝向 */
    private static final double TURN_BLEND = 0.3D;

    /** 物品模型缩放 */
    private static final float ITEM_SCALE = 0.55F;

    /** 抬离带面的高度（格） */
    private static final double LIFT = 0.06D;

    private final ItemRenderer itemRenderer;
    private final double[] posOut = new double[3];

    public ConveyorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = Minecraft.getInstance().getItemRenderer();
    }

    @Override
    public void render(ConveyorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // 网络调试仪：手持时高亮本格所属线路（每成员各画各的框，不受线首视锥影响）
        if (NetworkToolItem.isHolding(Minecraft.getInstance().player)) {
            renderLineHighlight(be, poseStack, bufferSource);
        }

        List<BeltItem> items = be.getItemsForRender();
        if (items.isEmpty()) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);
        BlockPos pos = be.getBlockPos();
        float yaw = yawOf(facing);

        for (BeltItem item : items) {
            ItemStack stack = item.getStack();
            if (stack.isEmpty()) continue;

            double progress = Mth.clamp(
                    Mth.lerp(partialTick, item.getPrevProgress(), item.getProgress()), 0.0D, 1.0D);
            ConveyorBlockEntity.computeItemPosition(pos, facing, type, progress, posOut);

            poseStack.pushPose();
            poseStack.translate(
                    posOut[0] - pos.getX(),
                    posOut[1] - pos.getY(),
                    posOut[2] - pos.getZ());

            // 转弯入场平滑转向：来源朝向 → 本格朝向
            float itemYaw = yaw;
            byte entryDir = item.getEntryDir();
            if (entryDir != BeltItem.NO_ENTRY_TURN && progress < TURN_BLEND) {
                Direction source = Direction.from3DDataValue(entryDir);
                float sourceYaw = yawOf(source);
                float t = (float) (progress / TURN_BLEND);
                itemYaw = sourceYaw + Mth.degreesDifference(sourceYaw, yaw) * t;
            }
            poseStack.mulPose(Axis.YP.rotationDegrees(itemYaw));

            // 贴合带面：水平 -90°、上坡 -135°、下坡 -45°（绕局部 X 轴）
            float tilt = switch (type) {
                case UP -> -135.0F;
                case DOWN -> -45.0F;
                default -> -90.0F;
            };
            poseStack.mulPose(Axis.XP.rotationDegrees(tilt));

            // 沿带面法线抬升，避免 Z-fighting
            poseStack.translate(0.0D, 0.0D, LIFT);
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);

            BakedModel model = itemRenderer.getModel(stack, be.getLevel(), null, 0);
            itemRenderer.render(stack, ItemDisplayContext.FIXED,
                    false, poseStack, bufferSource, packedLight,
                    OverlayTexture.NO_OVERLAY, model);
            poseStack.popPose();
        }
    }

    /** 让模型局部 +Z 指向给定水平方向的偏航角 */
    private static float yawOf(Direction facing) {
        return (float) Math.toDegrees(Math.atan2(facing.getStepX(), facing.getStepZ()));
    }

    /**
     * 线路高亮：同一条线同一颜色（色相由线路身份哈希决定）；
     * 线首额外叠加白色框标记流向起点；未组线时红色框报警。
     */
    private static void renderLineHighlight(ConveyorBlockEntity be, PoseStack poseStack,
                                            MultiBufferSource bufferSource) {
        int lineId = be.getLineId();
        float r, g, b;
        if (lineId == -1) {
            r = 1.0F; g = 0.15F; b = 0.15F; // 未组线：红色报警
        } else {
            float hue = (lineId & 0x7FFFFFFF) % 360 / 360.0F;
            int rgb = Mth.hsvToRgb(hue, 0.85F, 1.0F);
            r = ((rgb >> 16) & 0xFF) / 255.0F;
            g = ((rgb >> 8) & 0xFF) / 255.0F;
            b = (rgb & 0xFF) / 255.0F;
        }

        RenderSystem.disableDepthTest();
        var consumer = bufferSource.getBuffer(RenderType.lines());
        AABB box = new AABB(-0.001, -0.001, -0.001, 1.001, 1.001, 1.001);
        LevelRenderer.renderLineBox(poseStack, consumer, box, r, g, b, 0.85F);
        if (be.isLineHead()) {
            // 线首标记：白色内框 + 略亮外框
            LevelRenderer.renderLineBox(poseStack, consumer,
                    new AABB(0.15, 0.15, 0.15, 0.85, 0.85, 0.85), 1.0F, 1.0F, 1.0F, 0.9F);
        }
        RenderSystem.enableDepthTest();
    }
}
