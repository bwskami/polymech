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
 * 物品平躺在带面上随带运动。
 * </p>
 * <p>
 * 转角（侧向馈入）平滑曲线：带 entryDir 且与本格朝向垂直的物品走一条二次贝塞尔
 * 曲线——从入口边中点（恰为来源带路径终点，位置无缝衔接）经方块中心弯到出口边
 * 中点，朝向沿曲线切线连续旋转。纯渲染层，不改变任何逻辑坐标。
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

            // 不钳制：跨格时 prev 可为小负值（新格坐标下 = 来源格出口边），
            // 不钳制才能保证跨边界帧间位置连续；钳制会在每格边界瞬移一步
            double progress = Mth.lerp(partialTick, item.getPrevProgress(), item.getProgress());
            byte entryDir = item.getEntryDir();

            float itemYaw;
            if (type == ConveyorType.HORIZONTAL && isCornerEntry(entryDir, facing)) {
                // 转角平滑曲线：位置走贝塞尔弧，朝向沿切线连续旋转（t 映射需钳制）
                Direction source = Direction.from3DDataValue(entryDir);
                double cp = Mth.clamp(progress, 0.0D, 1.0D);
                double t = Mth.clamp(
                        (cp - ConveyorBlockEntity.SIDE_ENTRY_PROGRESS)
                                / (1.0D - ConveyorBlockEntity.SIDE_ENTRY_PROGRESS),
                        0.0D, 1.0D);
                computeCornerPosition(pos, source, facing, t, posOut);
                double tx = (1.0D - t) * source.getStepX() + t * facing.getStepX();
                double tz = (1.0D - t) * source.getStepZ() + t * facing.getStepZ();
                itemYaw = (float) Math.toDegrees(Math.atan2(tx, tz));
            } else {
                ConveyorBlockEntity.computeItemPosition(pos, facing, type, progress, posOut);

                // 非转角入场（如垂直落入）的平滑转向：来源朝向 → 本格朝向
                itemYaw = yaw;
                if (entryDir != BeltItem.NO_ENTRY_TURN && progress < TURN_BLEND) {
                    Direction source = Direction.from3DDataValue(entryDir);
                    float sourceYaw = yawOf(source);
                    float blend = (float) (Math.max(0.0D, progress) / TURN_BLEND);
                    itemYaw = sourceYaw + Mth.degreesDifference(sourceYaw, yaw) * blend;
                }
            }

            poseStack.pushPose();
            poseStack.translate(
                    posOut[0] - pos.getX(),
                    posOut[1] - pos.getY(),
                    posOut[2] - pos.getZ());

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
     * 是否为转角入场：entryDir 有效、来源为水平方向且与本格朝向垂直。
     * （同向直连入场不记 entryDir；垂直落入或迎面馈入不走转角曲线）
     */
    private static boolean isCornerEntry(byte entryDir, Direction facing) {
        if (entryDir == BeltItem.NO_ENTRY_TURN) return false;
        Direction source = Direction.from3DDataValue(entryDir);
        return source.getAxis().isHorizontal() && source.getAxis() != facing.getAxis();
    }

    /**
     * 转角二次贝塞尔曲线（Create 同款丝滑转角）。
     * <p>
     * P0 = 入口边中点（= 来源带路径终点，跨带衔接零跳变）；
     * P1 = 方块中心（控制点）；P2 = 出口边中点（progress=1，切线 = 本格朝向）。
     * t∈[0,1] 由格内进度 [SIDE_ENTRY_PROGRESS, 1] 线性映射，首尾切线分别
     * 平行于来源朝向与本格朝向，保证位置与旋转双重连续。
     * </p>
     */
    private static void computeCornerPosition(BlockPos pos, Direction source, Direction facing,
                                              double t, double[] out) {
        double c0x = pos.getX() + 0.5 - source.getStepX() * 0.5;
        double c0z = pos.getZ() + 0.5 - source.getStepZ() * 0.5;
        double pcx = pos.getX() + 0.5;
        double pcz = pos.getZ() + 0.5;
        double c1x = pos.getX() + 0.5 + facing.getStepX() * 0.5;
        double c1z = pos.getZ() + 0.5 + facing.getStepZ() * 0.5;
        double mt = 1.0D - t;
        out[0] = mt * mt * c0x + 2.0D * t * mt * pcx + t * t * c1x;
        out[2] = mt * mt * c0z + 2.0D * t * mt * pcz + t * t * c1z;
        out[1] = pos.getY() + 4.0 / 16.0;
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
