package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.util.PipePathCalculator;
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

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class PipePreviewRenderer {
    
    private static BlockPos startPos = null;
    private static PipeIdentifier startPipeId = null;
    
    private static final int COLOR_A_POINT = 0xFF00FF00;
    private static final int COLOR_B_POINT = 0xFFFF0000;
    
    private static final float LINE_WIDTH = 0.06F;
    
    public static void setStartPos(BlockPos pos, PipeIdentifier pipeId) {
        startPos = pos;
        startPipeId = pipeId;
    }
    
    public static BlockPos getStartPos() {
        return startPos;
    }
    
    public static PipeIdentifier getStartPipeId() {
        return startPipeId;
    }
    
    public static void clearStartPos() {
        startPos = null;
        startPipeId = null;
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
        PipeIdentifier pipeId = getPipeId(heldItem);
        
        if (pipeId == null) {
            return;
        }

        if (player.isShiftKeyDown()) return;
        
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
        
        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;
        
        BlockPos targetPos = PipeInputHandler.getEndpointPosition(mc.level, blockHitResult);
        boolean containerEndpoint = targetPos.equals(clickedPos);
        
        // 容器/机器端点不需要支撑检查；普通铺设位置需有邻接支撑
        if (!containerEndpoint && !hasAdjacentSupport(mc.level, targetPos)) {
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
        
        int pathColor = getPathColor(pipeId.size());
        
        if (startPos != null) {
            // 与服务端一致：路径端点先经过代理面吸附解析（接线锚点不变）
            BlockPos pathStart = PipePathCalculator.resolveEndpoint(mc.level, startPos, targetPos);
            BlockPos pathEnd = PipePathCalculator.resolveEndpoint(mc.level, targetPos, startPos);
            List<BlockPos> path = PipePathCalculator.calculatePath(mc.level, pathStart, pathEnd);
            
            int emptyCount = 0;
            for (BlockPos pos : path) {
                if (mc.level != null && mc.level.isEmptyBlock(pos)) {
                    emptyCount++;
                }
            }
            
            boolean canAfford = emptyCount <= available;
            
            renderBlockOutline(poseStack, event.getCamera(), startPos, COLOR_A_POINT);
            
            if (!path.isEmpty() && canAfford) {
                for (int i = 0; i < path.size(); i++) {
                    BlockPos pos = path.get(i);
                    if (pos.equals(pathStart)) continue;
                    
                    if (pos.equals(pathEnd)) {
                        renderBlockOutline(poseStack, event.getCamera(), pos, COLOR_B_POINT);
                    } else {
                        renderBlockOutline(poseStack, event.getCamera(), pos, pathColor);
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
    
    private static int getPathColor(PipeBlock.PipeSize size) {
        return switch (size) {
            case SMALL -> 0xFF00FFFF;
            case BIG   -> 0xFFFF00FF;
            case HUGE  -> 0xFF0080FF;
            default    -> 0xFFFFFF00;
        };
    }
    
    private static PipeIdentifier getPipeId(Item item) {
        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                if (item == sizeEntry.getValue().get().asItem()) {
                    return new PipeIdentifier(materialEntry.getKey(), sizeEntry.getKey());
                }
            }
        }
        return null;
    }
    
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
        // 底面
        addBar(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);

        // 顶面
        addBar(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);

        // 垂直边
        addBar(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);

        // 8 个角点实心立方体衔接（棱端实心正方形与立方体面完全对齐，杜绝十字截面穿帮）
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
