package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.item.NetworkToolItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网络调试仪 —— 管道网络高亮渲染器。
 * <p>
 * 管道网络（WorldPipeNet）是服务端独有对象，客户端无镜像。
 * 因此这里按与服务端 {@code WorldPipeNet.isConnected} 完全一致的规则
 * （管道间连接两侧均为 CONNECTED）在客户端本地泛洪出连通域，
 * 每个连通域一个颜色，用于直观验证管道组网。
 * </p>
 * <p>
 * 性能：扫描结果每 20 tick 重建一次并缓存，每帧只重绘缓存。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class NetworkOverlayRenderer {

    /** 水平扫描半径（格） */
    private static final int SCAN_RADIUS = 24;

    /** 垂直扫描半径（格） */
    private static final int SCAN_HEIGHT = 16;

    /** 缓存重建间隔（tick） */
    private static final int REBUILD_INTERVAL = 20;

    /** 缓存：每个连通域一组坐标 */
    private static List<List<BlockPos>> cachedNets = new ArrayList<>();

    /** 上次重建时的世界时间 */
    private static long lastRebuildTick = -1;

    /** 上次重建时的玩家位置（方块坐标） */
    private static BlockPos lastRebuildPos = BlockPos.ZERO;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        if (!NetworkToolItem.isHolding(player)) return;

        long tick = mc.level.getGameTime();
        BlockPos playerBlockPos = player.blockPosition();
        if (tick - lastRebuildTick >= REBUILD_INTERVAL
                || !playerBlockPos.equals(lastRebuildPos)) {
            lastRebuildTick = tick;
            lastRebuildPos = playerBlockPos.immutable();
            cachedNets = scanNets(mc, playerBlockPos);
        }
        if (cachedNets.isEmpty()) return;

        renderNets(event.getPoseStack(), mc, cachedNets);
    }

    /** 扫描玩家附近的管道，按服务端连通规则泛洪聚类 */
    private static List<List<BlockPos>> scanNets(Minecraft mc, BlockPos center) {
        List<List<BlockPos>> nets = new ArrayList<>();
        List<BlockPos> seeds = new ArrayList<>();

        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        for (int x = cx - SCAN_RADIUS; x <= cx + SCAN_RADIUS; x++) {
            for (int z = cz - SCAN_RADIUS; z <= cz + SCAN_RADIUS; z++) {
                if (!mc.level.hasChunkAt(new BlockPos(x, cy, z))) continue;
                for (int y = cy - SCAN_HEIGHT; y <= cy + SCAN_HEIGHT; y++) {
                    if (mc.level.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PipeBlock) {
                        seeds.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        if (seeds.isEmpty()) return nets;

        Set<BlockPos> assigned = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : seeds) {
            if (!assigned.add(seed)) continue;
            List<BlockPos> net = new ArrayList<>();
            queue.clear();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                net.add(cur);
                BlockState state = mc.level.getBlockState(cur);
                if (!(state.getBlock() instanceof PipeBlock)) continue;
                for (Direction dir : Direction.values()) {
                    // 与服务端 WorldPipeNet.isConnected 一致：两侧均须为 CONNECTED
                    if (state.getValue(PipeBlock.getProperty(dir)) != PipeBlock.PipeConnection.CONNECTED) {
                        continue;
                    }
                    BlockPos next = cur.relative(dir);
                    if (assigned.contains(next)) continue;
                    BlockState nextState = mc.level.getBlockState(next);
                    if (nextState.getBlock() instanceof PipeBlock
                            && nextState.getValue(PipeBlock.getProperty(dir.getOpposite()))
                                    == PipeBlock.PipeConnection.CONNECTED) {
                        assigned.add(next);
                        queue.add(next);
                    }
                }
            }
            if (!net.isEmpty()) {
                nets.add(net);
            }
        }
        return nets;
    }

    /** 逐网络绘制线框（每网络一色，禁用深度测试透视显示） */
    private static void renderNets(PoseStack poseStack, Minecraft mc, List<List<BlockPos>> nets) {
        double camX = mc.gameRenderer.getMainCamera().getPosition().x();
        double camY = mc.gameRenderer.getMainCamera().getPosition().y();
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        poseStack.pushPose();
        poseStack.translate(-camX, -camY, -camZ);
        Matrix4f matrix = poseStack.last().pose();

        BufferBuilder buf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        int netIndex = 0;
        for (List<BlockPos> net : nets) {
            int rgb = Mth.hsvToRgb((netIndex * 61) % 360 / 360.0F, 0.85F, 1.0F);
            float r = ((rgb >> 16) & 0xFF) / 255.0F;
            float g = ((rgb >> 8) & 0xFF) / 255.0F;
            float b = (rgb & 0xFF) / 255.0F;
            for (BlockPos pos : net) {
                addBox(buf, matrix, pos, r, g, b);
            }
            netIndex++;
        }

        BufferUploader.drawWithShader(buf.buildOrThrow());
        poseStack.popPose();

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 以 (x,y,z) 为单位立方体绘制 12 条边（略微外扩防 Z-fighting） */
    private static void addBox(BufferBuilder buf, Matrix4f matrix, BlockPos pos,
                               float r, float g, float b) {
        float x0 = pos.getX() - 0.001F;
        float y0 = pos.getY() - 0.001F;
        float z0 = pos.getZ() - 0.001F;
        float x1 = pos.getX() + 1.001F;
        float y1 = pos.getY() + 1.001F;
        float z1 = pos.getZ() + 1.001F;
        // 12 条边
        line(buf, matrix, x0, y0, z0, x1, y0, z0, r, g, b);
        line(buf, matrix, x0, y1, z0, x1, y1, z0, r, g, b);
        line(buf, matrix, x0, y0, z1, x1, y0, z1, r, g, b);
        line(buf, matrix, x0, y1, z1, x1, y1, z1, r, g, b);
        line(buf, matrix, x0, y0, z0, x0, y1, z0, r, g, b);
        line(buf, matrix, x1, y0, z0, x1, y1, z0, r, g, b);
        line(buf, matrix, x0, y0, z1, x0, y1, z1, r, g, b);
        line(buf, matrix, x1, y0, z1, x1, y1, z1, r, g, b);
        line(buf, matrix, x0, y0, z0, x0, y0, z1, r, g, b);
        line(buf, matrix, x1, y0, z0, x1, y0, z1, r, g, b);
        line(buf, matrix, x0, y1, z0, x0, y1, z1, r, g, b);
        line(buf, matrix, x1, y1, z0, x1, y1, z1, r, g, b);
    }

    private static void line(BufferBuilder buf, Matrix4f matrix,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b) {
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, 0.9F);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, 0.9F);
    }
}
