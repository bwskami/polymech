package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
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
        if (heldItem != ModBlocks.CONVEYOR.get().asItem()) {
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

        // 底面
        addThickLine(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);

        // 顶面
        addThickLine(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);

        // 垂直边
        addThickLine(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);

        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /**
     * 绘制有宽度的线条（使用两个垂直的 Quad 形成十字形）
     */
    private static void addThickLine(BufferBuilder buf, Matrix4f matrix,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float hw, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;

        boolean xMajor = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz);
        boolean yMajor = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz);

        float p1x = 0, p1y = 0, p1z = 0;
        float p2x = 0, p2y = 0, p2z = 0;

        if (xMajor) {
            p1y = hw;
            p2z = hw;
        } else if (yMajor) {
            p1x = hw;
            p2z = hw;
        } else {
            p1x = hw;
            p2y = hw;
        }

        // 第一个 Quad（双面）
        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);

        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);

        // 第二个 Quad（双面）
        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);

        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
    }
}