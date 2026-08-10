package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mss.polymech.Polymech;
import com.mss.polymech.item.ConveyorItem;
import com.mss.polymech.util.ConveyorPathCalculator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 传送带铺设预览渲染器。
 * <p>
 * 手持传送带物品时，在目标方块上显示半透明预览框：
 * <ul>
 *   <li>未选起点：绿色框标注当前指向的放置位置</li>
 *   <li>已选起点：绿色框标注起点，红色框标注终点，黄色框标注路径</li>
 * </ul>
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class ConveyorPreviewRenderer {

    private static BlockPos startPos = null;

    private static final int COLOR_A_POINT = 0xFF00FF00;
    private static final int COLOR_B_POINT = 0xFFFF0000;
    private static final int COLOR_PATH = 0xFFFFFF00;

    private static final float LINE_WIDTH = 0.06F;

    public static void setStartPos(BlockPos pos) {
        startPos = pos;
    }

    public static BlockPos getStartPos() {
        return startPos;
    }

    public static void clearStartPos() {
        startPos = null;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        Item heldItem = player.getMainHandItem().getItem();
        if (!(heldItem instanceof ConveyorItem)) {
            return;
        }

        if (player.isShiftKeyDown()) return;

        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;

        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;

        BlockPos targetPos = clickedPos.relative(blockHitResult.getDirection());

        // 检查目标位置在同一 Y 层且有相邻支撑
        if (!hasAdjacentSupport(mc.level, targetPos)) {
            return;
        }

        int available = player.isCreative() ? Integer.MAX_VALUE : player.getMainHandItem().getCount();

        PoseStack poseStack = event.getPoseStack();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();

        if (startPos != null) {
            // 起点和目标必须在同一 Y 层
            if (startPos.getY() == targetPos.getY()) {
                List<BlockPos> path = ConveyorPathCalculator.calculatePath(startPos, targetPos);

                int emptyCount = 0;
                for (BlockPos pos : path) {
                    if (mc.level != null && (mc.level.isEmptyBlock(pos) || mc.level.getBlockState(pos).canBeReplaced())) {
                        emptyCount++;
                    }
                }

                boolean canAfford = emptyCount <= available;

                renderBlockOutline(poseStack, event.getCamera(), startPos, COLOR_A_POINT);

                if (!path.isEmpty() && canAfford) {
                    for (BlockPos pos : path) {
                        if (pos.equals(startPos)) continue;

                        if (pos.equals(targetPos)) {
                            renderBlockOutline(poseStack, event.getCamera(), pos, COLOR_B_POINT);
                        } else {
                            renderBlockOutline(poseStack, event.getCamera(), pos, COLOR_PATH);
                        }
                    }
                }
            }
        } else {
            renderBlockOutline(poseStack, event.getCamera(), targetPos, COLOR_A_POINT);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    /**
     * 检查目标位置是否有相邻支撑。
     * 至少一个相邻面有非空方块即可。
     */
    public static boolean hasAdjacentSupport(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level == null) return false;
        for (Direction dir : Direction.values()) {
            if (!level.isEmptyBlock(pos.relative(dir))) {
                return true;
            }
        }
        return false;
    }

    private static void renderBlockOutline(PoseStack poseStack, net.minecraft.client.Camera camera, BlockPos pos, int color) {
        poseStack.pushPose();
        poseStack.translate(
                (double) pos.getX() - camera.getPosition().x(),
                (double) pos.getY() - camera.getPosition().y(),
                (double) pos.getZ() - camera.getPosition().z()
        );

        Matrix4f matrix = poseStack.last().pose();
        renderCubeWireframe(matrix, 0, 0, 0, 1, 1, 1, color);

        poseStack.popPose();
    }

    private static void renderCubeWireframe(Matrix4f matrix, float x1, float y1, float z1,
                                             float x2, float y2, float z2, int color) {
        float a = (float) ((color >> 24) & 0xFF) / 255.0F;
        float r = (float) ((color >> 16) & 0xFF) / 255.0F;
        float g = (float) ((color >> 8) & 0xFF) / 255.0F;
        float b = (float) (color & 0xFF) / 255.0F;

        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float hw = LINE_WIDTH / 2.0F;

        // 12 条实心方条棱 + 8 个角点立方体（任何视角完全闭合，无十字截面穿帮）
        addBar(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);

        addBar(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);

        addBar(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);

        addCorner(buf, matrix, x1, y1, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y1, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y1, z2, hw, r, g, b, a);
        addCorner(buf, matrix, x1, y1, z2, hw, r, g, b, a);
        addCorner(buf, matrix, x1, y2, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y2, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y2, z2, hw, r, g, b, a);
        addCorner(buf, matrix, x1, y2, z2, hw, r, g, b, a);

        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /**
     * 提交一条实心方条棱：以棱方向为轴、截面为 hw×hw 正方形的 4 侧面柱体（每面双面提交）。
     * <p>
     * 与 {@link #addCorner(BufferBuilder, Matrix4f, float, float, float, float, float, float, float, float)}
     * 配合使用：棱端实心正方形截面与角点立方体面完全对齐，
     * 任何视角都看不到十字截面或顶点空洞（替代旧十字双平面 quad 方案）。
     * </p>
     */
    private static void addBar(BufferBuilder buf, Matrix4f matrix,
                               float x1, float y1, float z1,
                               float x2, float y2, float z2,
                               float hw, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6F)
            return;
        // 单位方向向量
        float ux = dx / len, uy = dy / len, uz = dz / len;
        // 构造垂直于方向的截面正交基 v、w（v×w = 方向）
        float vx, vy, vz;
        if (Math.abs(uy) < 0.99F) {
            vx = 0;
            vy = 1;
            vz = 0; // 参考世界 Y
        } else {
            vx = 1;
            vy = 0;
            vz = 0; // 方向接近 Y 时参考 X
        }
        // v = normalize(dir × up)
        float tvx = uy * vz - uz * vy;
        float tvy = uz * vx - ux * vz;
        float tvz = ux * vy - uy * vx;
        float tl = (float) Math.sqrt(tvx * tvx + tvy * tvy + tvz * tvz);
        if (tl < 1.0e-6F)
            return;
        tvx /= tl;
        tvy /= tl;
        tvz /= tl;
        // w = dir × v
        float wx = uy * tvz - uz * tvy;
        float wy = uz * tvx - ux * tvz;
        float wz = ux * tvy - uy * tvx;

        // 截面四角（乘 hw 后才是实际半宽偏移）：c1=-v-w, c2=+v-w, c3=+v+w, c4=-v+w
        float c1x = (-tvx - wx) * hw, c1y = (-tvy - wy) * hw, c1z = (-tvz - wz) * hw;
        float c2x = (tvx - wx) * hw, c2y = (tvy - wy) * hw, c2z = (tvz - wz) * hw;
        float c3x = (tvx + wx) * hw, c3y = (tvy + wy) * hw, c3z = (tvz + wz) * hw;
        float c4x = (-tvx + wx) * hw, c4y = (-tvy + wy) * hw, c4z = (-tvz + wz) * hw;

        // 4 个侧面（双面提交，任何绕序/剔除状态下均可见）
        // 面 -w（c1-c2）
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        // 面 +w（c4-c3）
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
        // 面 -v（c1-c4）
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        // 面 +v（c2-c3）
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
    }

    /** 提交一个实心小立方体（6 面双面），用于衔接棱端、闭合框体顶点 */
    private static void addCorner(BufferBuilder buf, Matrix4f matrix,
                                  float cx, float cy, float cz,
                                  float hw, float r, float g, float b, float a) {
        float x1 = cx - hw, y1 = cy - hw, z1 = cz - hw;
        float x2 = cx + hw, y2 = cy + hw, z2 = cz + hw;
        // +Z
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        // -Z
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        // +X
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        // -X
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        // +Y
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        // -Y
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
    }
}