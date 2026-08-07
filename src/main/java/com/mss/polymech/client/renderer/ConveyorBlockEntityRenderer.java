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
 * 传送带物品包渲染器（Create 同款平滑）。
 * <p>
 * 直接渲染 BE 内的 {@link BeltItem} 队列：prevProgress → progress 按 partialTick
 * 线性插值。双端逐 tick 执行同一套确定性驱动，插值起点永远是上一 tick 真实位置；
 * 跨格时 prev 换算到新格坐标（可为小负值，几何上就是来源格出口边），
 * 因此<b>插值结果不钳制到 [0,1]</b>——跨边界帧间位置零跳变。
 * </p>
 * <p>
 * <b>物品站立渲染（Create 同款）</b>：物品沿带方向站立在带面上（绕 Y 轴朝向
 * 本格流向，底面贴带面），不再平躺；坡道上位置沿斜面移动但姿态不变。
 * </p>
 * <p>
 * <b>侧向汇入 = 横向平移滑入（Create 同款 sideOffset）</b>：侧入包携带横向
 * 偏移，从入口侧边 ±{@link ConveyorBlockEntity#SIDE_OFFSET_START} 起步，
 * 驱动收敛后随带移动逐渐收回到中线；渲染按 prevSideOffset → sideOffset
 * 插值做纯横向平移，全程不旋转物品、不走曲线。
 * </p>
 */
public class ConveyorBlockEntityRenderer implements BlockEntityRenderer<ConveyorBlockEntity> {

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

            // 不钳制：跨格时 prev 可为小负值（新格坐标下 = 来源格出口边），
            // 不钳制才能保证跨边界帧间位置连续；钳制会在每格边界瞬移一步
            double progress = Mth.lerp(partialTick, item.getPrevProgress(), item.getProgress());
            // Create 同款：横向偏移 prevSideOffset → sideOffset 插值
            double sideOffset = Mth.lerp(partialTick, item.getPrevSideOffset(), item.getSideOffset());

            ConveyorBlockEntity.computeItemPosition(pos, facing, type, progress, posOut);

            // 侧向滑入（Create 同款 sideOffset）：偏移轴取本格朝向的横向轴，
            // 符号与 Create BeltRenderer 一致（facing 沿 Z 时直接取 sideOffset，
            // 沿 X 时取反）——与 acceptIncoming 的初始符号配对后，物品恒从
            // 来源带所在一侧横向滑入中线，绝无左右颠倒
            boolean alongX = facing.getClockWise().getAxis() == Direction.Axis.X;
            double lateral = alongX ? sideOffset : -sideOffset;
            posOut[0] += alongX ? lateral : 0.0D;
            posOut[2] += alongX ? 0.0D : lateral;

            poseStack.pushPose();
            poseStack.translate(
                    posOut[0] - pos.getX(),
                    posOut[1] - pos.getY(),
                    posOut[2] - pos.getZ());

            // 站立渲染（Create 同款）：绕 Y 轴朝向本格流向，底面贴带面；
            // 坡道上位置沿斜面移动但姿态不变。全程不旋转物品、不走曲线
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));

            // 沿带面法线（世界 Y）抬升，避免 Z-fighting
            poseStack.translate(0.0D, LIFT, 0.0D);
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
