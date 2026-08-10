package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.renderer.ClientWireCache;
import com.mss.polymech.item.WireSpoolItem;
import com.mss.polymech.ModDataComponents;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.GridNode;
import com.mss.polymech.powergrid.GridNodeBlock;
import com.mss.polymech.powergrid.GridNodes;
import com.mss.polymech.powergrid.GridWireType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * 电线拉线选择框渲染器（仿机械动力 Outliner 的 AABBOutline 视觉）。
 * <p>
 * 手持线轴时，在电网连接器上显示类似 Create 胶水/蓝图预览的选择框：
 * <ul>
 *   <li><b>瞄准预览</b>：未选起点时，准星悬停节点即显示醒目绿色预览框（与选中后的可连目标框同款）</li>
 *   <li><b>起点框</b>：选中起点后常驻亮蓝色半透明面 + 粗亮边框（Create 风格柔和蓝）</li>
 *   <li><b>目标框</b>：准星悬停节点时实时变色——绿色（可连接）/ 红色（不可连接：自身、重复、超距）</li>
 *   <li><b>chase 动画</b>：瞄准从一格移到另一格时框不瞬移，而是指数平滑滑向新位置（Create outliner 的 chaseAABB 手感）</li>
 * </ul>
 * 视觉细节对齐 Create 的 outliner：保留深度测试（被墙遮挡部分不显示）、
 * 关闭深度写入（不干扰后续叠加层）、背面剔除关闭、半透明混合开启、
 * 新框 0.4s easeOutCubic 淡入。边线用十字双平面 quad 模拟线宽（1.21 无可靠 GL 线宽）。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class NodeSelectionRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSelectionRenderer.class);

    // ===== Create 色系（catnip Outliner 常用配色） =====
    /** 起点框：柔和亮蓝（ARGB） */
    private static final int COLOR_START = 0xFF4D9EFF;
    /** 目标可连接：Create 胶水高亮绿 */
    private static final int COLOR_VALID = 0xFF68C586;
    /** 目标不可连接：柔和红 */
    private static final int COLOR_INVALID = 0xFFC5564D;

    /** 线宽（格）：统一粗线，瞄准/选中均醒目可见 */
    private static final float LINE_WIDTH = 1 / 16F;

    /** 面透明度 */
    private static final float FACE_ALPHA_START = 0.32F;
    private static final float FACE_ALPHA_VALID = 0.35F;
    private static final float FACE_ALPHA_INVALID = 0.28F;

    /** 淡入时长（tick，0.4s） */
    private static final int FADE_IN_TICKS = 8;

    /** chase 追赶时间常数（秒）：框从旧位置滑向新目标的速度（越小越快，Create 手感约 0.1s） */
    private static final float CHASE_TIME_CONSTANT = 0.10F;

    /** 当前帧要渲染的起点框（null=不显示） */
    private static Entry startEntry;
    /** 当前帧要渲染的目标/提示框（null=不显示） */
    private static Entry targetEntry;

    /** 诊断：事件是否已记录（每次会话一条，确认处理器被触发） */
    private static boolean loggedEventFired;
    /** 诊断：类激活是否已记录（每次会话一条） */
    private static boolean loggedActive;
    /** 诊断：上次记录的悬停节点（变化时打一条日志） */
    private static GridNode lastLoggedHovered;

    private NodeSelectionRenderer() {}

    // ==================== 事件 ====================

    /**
     * 世界渲染阶段绘制选择框。
     * <p>
     * 在 {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS} 阶段
     * 与电线渲染同层叠加（先画半透明面，再画亮边线，保证边线清晰可读）。
     * </p>
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!loggedEventFired) {
            // 无条件一次性日志：确认事件处理器被触发、阶段匹配、主手物品与等级就绪
            String item = mc.player == null ? "no-player" : String.valueOf(mc.player.getMainHandItem().getItem());
            LOGGER.info("NodeSelectionRenderer event fired: stage={}, item={}, level={}",
                    event.getStage(), item,
                    mc.level == null ? "no-level" : mc.level.dimension().location());
            loggedEventFired = true;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;

        if (mc.level == null || mc.player == null)
            return;
        if (mc.options.hideGui || mc.player.isSpectator())
            return;
        if (!(mc.player.getMainHandItem().getItem() instanceof WireSpoolItem spool))
            return;
        if (!loggedActive) {
            LOGGER.info("NodeSelectionRenderer active: holding {}", spool);
            loggedActive = true;
        }

        GridWireType wireType = spool.getWireType();
        Level level = mc.level;
        long tick = level.getGameTime();

        // 收集状态：起点（SELECTED_NODE 数据组件）+ 准星悬停节点
        GridNode start = mc.player.getMainHandItem().get(ModDataComponents.SELECTED_NODE.get());
        if (start != null && GridNodes.getNodePosition(level, start) == null)
            start = null; // 起点方块已不存在，不再渲染

        GridNode hovered = null;
        HitResult hitResult = mc.hitResult;
        if (hitResult instanceof BlockHitResult bhr && bhr.getType() != HitResult.Type.MISS) {
            hovered = findNodeAt(level, bhr);
        }
        if (hovered != null && !Objects.equals(hovered, lastLoggedHovered)) {
            LOGGER.info("NodeSelectionRenderer hovered node: {} (start={})", hovered, start);
            lastLoggedHovered = hovered;
        }

        // 更新两个框的当前帧状态（复用 Entry 保持已满 alpha，新建则触发淡入）
        startEntry = start == null ? null
                : entry(startEntry, boxOf(start.sourcePos()), COLOR_START, LINE_WIDTH, FACE_ALPHA_START, tick);

        // 先保存上一帧的目标框（悬停同一节点时复用，保证淡入只触发一次；
        // 若每帧都新建 Entry，bornTick 恒为当前帧，alpha 永远停在 0，框将完全不可见）
        Entry prevTarget = targetEntry;
        targetEntry = null;
        if (hovered != null) {
            if (start == null) {
                // 未选起点：瞄准即预览——与可连目标框同款的醒目绿框
                targetEntry = entry(prevTarget, boxOf(hovered.sourcePos()),
                        COLOR_VALID, LINE_WIDTH, FACE_ALPHA_VALID, tick);
            } else if (!hovered.equals(start)) {
                // 已选起点：按可连接性变色
                boolean valid = canConnect(level, start, hovered, wireType);
                targetEntry = entry(prevTarget, boxOf(hovered.sourcePos()),
                        valid ? COLOR_VALID : COLOR_INVALID,
                        LINE_WIDTH, valid ? FACE_ALPHA_VALID : FACE_ALPHA_INVALID, tick);
            }
        }

        if (startEntry == null && targetEntry == null)
            return;

        // chase 动画：每帧向目标位置指数平滑追赶（帧率无关，Create outliner 同款滑动）
        if (startEntry != null)
            startEntry.tickChase();
        if (targetEntry != null)
            targetEntry.tickChase();

        if (targetEntry != null && targetEntry.logged == null) {
            LOGGER.info("NodeSelectionRenderer rendering frame box: {}", targetEntry.box);
            targetEntry.logged = Boolean.TRUE;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 第一轮：半透明面（QUADS 逐面提交，与边线同盒微膨胀防 Z-fighting）
        BufferBuilder faceBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (startEntry != null)
            startEntry.appendFaces(faceBuf, matrix, tick);
        if (targetEntry != null)
            targetEntry.appendFaces(faceBuf, matrix, tick);
        BufferUploader.drawWithShader(faceBuf.buildOrThrow());

        // 第二轮：亮边线（十字双平面 quad 模拟线宽）
        BufferBuilder edgeBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (startEntry != null)
            startEntry.appendEdges(edgeBuf, matrix, tick);
        if (targetEntry != null)
            targetEntry.appendEdges(edgeBuf, matrix, tick);
        BufferUploader.drawWithShader(edgeBuf.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    /**
     * 取消原版白色方块瞄准框：手持线轴瞄准电网连接器时，
     * 由本渲染器的彩色选择框取代原版白框，避免叠加杂乱（对齐 Create 轨道/胶水交互）。
     */
    @SubscribeEvent
    public static void onHighlightBlock(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
            return;
        if (!(mc.player.getMainHandItem().getItem() instanceof WireSpoolItem))
            return;
        if (mc.level.getBlockState(event.getTarget().getBlockPos()).getBlock() instanceof GridNodeBlock)
            event.setCanceled(true);
    }

    // ==================== 状态与校验 ====================

    /** 创建/复用框条目：新建时记录出生 tick 触发淡入；复用时更新颜色并把新位置设为追赶目标（平滑滑过去） */
    private static Entry entry(Entry existing, AABB box, int color, float lineWidth, float faceAlpha, long tick) {
        if (existing == null)
            return new Entry(box, color, lineWidth, faceAlpha, tick);
        existing.chase(box);
        existing.color = color;
        existing.lineWidth = lineWidth;
        existing.faceAlpha = faceAlpha;
        return existing;
    }

    /**
     * 查找准星命中的电网节点：优先直接检查瞄准方块本身，
     * 未命中时兜底用邻域搜索（点击点可能偏到相邻格）。
     */
    private static GridNode findNodeAt(Level level, BlockHitResult bhr) {
        BlockPos aimed = bhr.getBlockPos();
        BlockState state = level.getBlockState(aimed);
        if (state.getBlock() instanceof GridNodeBlock gridBlock) {
            Vec3 hit = bhr.getLocation();
            GridNode best = null;
            double bestDist = GridNodes.NODE_HIT_THRESHOLD;
            for (Map.Entry<Integer, Vec3> e : gridBlock.getNodePositions(state).entrySet()) {
                Vec3 nodePos = e.getValue().add(aimed.getX(), aimed.getY(), aimed.getZ());
                double dist = nodePos.distanceTo(hit);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new GridNode(e.getKey(), aimed);
                }
            }
            if (best != null)
                return best;
        }
        return GridNodes.closestNode(level, bhr.getLocation(), GridNodes.NODE_HIT_THRESHOLD);
    }

    /** 节点所在方块整格包围盒（微膨胀防 Z-fighting） */
    private static AABB boxOf(BlockPos pos) {
        return new AABB(pos).inflate(0.002);
    }

    /** 客户端可连接性预判（与服务端 WireSpoolItem 校验一致：非自身、未重复、未超距） */
    private static boolean canConnect(Level level, GridNode a, GridNode b, GridWireType wireType) {
        if (a.equals(b))
            return false;
        for (GridConnection connection : ClientWireCache.getAll()) {
            if (connection.touches(a) && connection.touches(b))
                return false;
        }
        return GridNodes.distanceBetween(level, a, b) <= wireType.getMaxLength();
    }

    /** easeOutCubic 缓动：0.4s 内透明度从 0 平滑升至目标值（Create outliner 同款感觉） */
    private static float easeOutCubic(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    // ==================== 渲染 ====================

    /** 单个选择框：半透明填充面 + 12 条亮边线（位置带 chase 追赶动画） */
    private static class Entry {
        AABB box;          // 当前显示位置（chase 追赶中，渲染用）
        AABB targetBox;    // 目标位置（每帧向此指数平滑追赶）
        int color;
        float lineWidth;
        float faceAlpha;
        final long bornTick;
        private long lastNano; // 上次追赶更新的纳秒时间戳（帧率无关计时）
        /** 诊断：该框是否已记录首次渲染 */
        Boolean logged;

        Entry(AABB box, int color, float lineWidth, float faceAlpha, long bornTick) {
            this.box = box;
            this.targetBox = box;
            this.color = color;
            this.lineWidth = lineWidth;
            this.faceAlpha = faceAlpha;
            this.bornTick = bornTick;
            this.lastNano = System.nanoTime();
        }

        /** 更新追赶目标：框从当前位置平滑滑向新目标（Create outliner 的 chaseAABB 效果） */
        void chase(AABB target) {
            targetBox = target;
        }

        /** 每帧向目标指数平滑追赶一次（时间常数 {@link #CHASE_TIME_CONSTANT}，帧率无关） */
        void tickChase() {
            if (box.equals(targetBox))
                return;
            long now = System.nanoTime();
            float dt = (now - lastNano) / 1.0e9F;
            lastNano = now;
            if (dt <= 0)
                return;
            if (dt > 0.05F)
                dt = 0.05F; // 卡顿/暂停保护：超长间隔按 0.05s 算，避免瞬移
            float f = 1.0F - (float) Math.exp(-dt / CHASE_TIME_CONSTANT);
            if (f >= 0.999F) {
                box = targetBox;
                return;
            }
            box = new AABB(
                    box.minX + (targetBox.minX - box.minX) * f,
                    box.minY + (targetBox.minY - box.minY) * f,
                    box.minZ + (targetBox.minZ - box.minZ) * f,
                    box.maxX + (targetBox.maxX - box.maxX) * f,
                    box.maxY + (targetBox.maxY - box.maxY) * f,
                    box.maxZ + (targetBox.maxZ - box.maxZ) * f);
        }

        /** 当前透明度倍率（淡入动画） */
        float alphaMul(long tick) {
            float t = Mth.clamp((tick - bornTick) / (float) FADE_IN_TICKS, 0, 1);
            return easeOutCubic(t);
        }

        /** 追加 6 个半透明面（QUADS，逆时针绕序，背面剔除已关闭） */
        void appendFaces(BufferBuilder buf, Matrix4f matrix, long tick) {
            float a = faceAlpha * alphaMul(tick);
            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float b = (color & 0xFF) / 255.0F;
            double minX = box.minX, minY = box.minY, minZ = box.minZ;
            double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;

            // +Z
            quad(buf, matrix, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);
            // -Z
            quad(buf, matrix, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, minX, minY, minZ, r, g, b, a);
            // +X
            quad(buf, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a);
            // -X
            quad(buf, matrix, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, r, g, b, a);
            // +Y
            quad(buf, matrix, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
            // -Y
            quad(buf, matrix, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        }

        /** 追加 12 条亮边线（每条一个十字双平面 quad，线宽 = lineWidth） */
        void appendEdges(BufferBuilder buf, Matrix4f matrix, long tick) {
            float a = 0.9F * alphaMul(tick);
            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float b = (color & 0xFF) / 255.0F;
            float hw = lineWidth / 2.0F;
            float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
            float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

            // 底面
            thickLine(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
            thickLine(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
            thickLine(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
            thickLine(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);
            // 顶面
            thickLine(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
            thickLine(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
            thickLine(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
            thickLine(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);
            // 垂直边
            thickLine(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
            thickLine(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
            thickLine(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
            thickLine(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);
        }
    }

    /** 提交一个四边形（POSITION_COLOR） */
    private static void quad(BufferBuilder buf, Matrix4f matrix,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                             float r, float g, float b, float a) {
        buf.addVertex(matrix, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        buf.addVertex(matrix, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        buf.addVertex(matrix, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        buf.addVertex(matrix, (float) x4, (float) y4, (float) z4).setColor(r, g, b, a);
    }

    /**
     * 提交一条有宽度的线段：沿主轴方向的两个垂直 quad 组成十字形横截面，
     * 从任何视角都可见约 lineWidth 的线宽（管线渲染已验证的绘制模式）。
     */
    private static void thickLine(BufferBuilder buf, Matrix4f matrix,
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

        // 第一个 quad（双面可见）
        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);

        // 第二个 quad（与第一个垂直）
        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);
    }
}
