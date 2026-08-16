package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.renderer.ClientWireCache;
import com.mss.polymech.item.WireCutterItem;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.GridNodes;
import com.mss.polymech.powergrid.GridWireType;
import com.mss.polymech.powergrid.WireTargetCache;
import com.mss.polymech.powergrid.WireTargeting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 剪线钳高亮与信息面板。
 * <p>
 * <ul>
 *   <li><b>描边</b>：持剪线钳时，把准星瞄准的电线渲染为外暗内亮的双层发光管</li>
 *   <li><b>屏幕信息</b>：类似机械动力工程师护目镜，在准星旁显示当前电线的详细电气参数</li>
 * </ul>
 * 目标连接由客户端每 tick 用 {@link WireTargeting} 从 {@link ClientWireCache} 中计算，
 * 写入 {@link WireTargetCache} 供物品本地预测与渲染使用。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class WireCutterOverlay {

    private static final double TARGET_REACH = WireTargeting.TARGET_REACH;
    private static final int CROSS_SECTION_SEGMENTS = 8;

    private static GridConnection target;

    private WireCutterOverlay() {}

    // ==================== 目标更新 ====================

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || mc.player.isSpectator()) {
            setTarget(null);
            return;
        }
        if (!isHoldingCutter(mc)) {
            setTarget(null);
            return;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getViewVector(1.0F);
        double blockClip = WireTargeting.blockClipDistance(mc.level, mc.player, eye, look, TARGET_REACH);
        GridConnection found = WireTargeting.findConnection(
                mc.level, ClientWireCache.getAll(), eye, look, TARGET_REACH, blockClip);
        setTarget(found);
    }

    private static void setTarget(GridConnection connection) {
        target = connection;
        WireTargetCache.setClientTarget(connection);
    }

    private static boolean isHoldingCutter(Minecraft mc) {
        return mc.player.getMainHandItem().getItem() instanceof WireCutterItem
                || mc.player.getOffhandItem().getItem() instanceof WireCutterItem;
    }

    // ==================== 世界描边 ====================

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || target == null)
            return;
        if (!isHoldingCutter(mc))
            return;

        GridConnection connection = target;
        Vec3 p1 = GridNodes.getNodePosition(mc.level, connection.node1());
        Vec3 p2 = GridNodes.getNodePosition(mc.level, connection.node2());
        if (p1 == null || p2 == null)
            return;

        List<Vec3> path = new ArrayList<>(GridNodes.cablePoints(
                p1, p2, connection.wireType().getSag(), 0.5F));
        if (path.isEmpty())
            return;
        if (path.get(path.size() - 1).distanceToSqr(p2) > 1.0e-8)
            path.add(p2);

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

        // 外圈深色描边 + 内圈高亮，两层叠出“发光描边”感
        renderTube(matrix, path,
                connection.wireType().getThickness() + 0.05F, 0.35F, 0.05F, 0.05F, 0.55F);
        renderTube(matrix, path,
                connection.wireType().getThickness() + 0.02F, 1.0F, 0.78F, 0.35F, 0.70F);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderTube(Matrix4f matrix, List<Vec3> path, float radius,
                                   float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec3 aPos = path.get(i);
            Vec3 bPos = path.get(i + 1);
            appendSegment(buf, matrix, aPos, bPos, radius, r, g, b, a);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void appendSegment(BufferBuilder buf, Matrix4f matrix, Vec3 a, Vec3 b,
                                      float radius, float r, float g, float bl, float aAlpha) {
        Vec3 dir = b.subtract(a);
        if (dir.lengthSqr() < 1.0e-8)
            return;
        dir = dir.normalize();

        Vec3 up = Math.abs(dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = u.cross(dir).normalize();

        float[] ax = new float[CROSS_SECTION_SEGMENTS];
        float[] ay = new float[CROSS_SECTION_SEGMENTS];
        float[] az = new float[CROSS_SECTION_SEGMENTS];
        float[] bx = new float[CROSS_SECTION_SEGMENTS];
        float[] by = new float[CROSS_SECTION_SEGMENTS];
        float[] bz = new float[CROSS_SECTION_SEGMENTS];

        for (int i = 0; i < CROSS_SECTION_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / CROSS_SECTION_SEGMENTS;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            ax[i] = (float) (a.x + radius * (cos * u.x + sin * v.x));
            ay[i] = (float) (a.y + radius * (cos * u.y + sin * v.y));
            az[i] = (float) (a.z + radius * (cos * u.z + sin * v.z));
            bx[i] = (float) (b.x + radius * (cos * u.x + sin * v.x));
            by[i] = (float) (b.y + radius * (cos * u.y + sin * v.y));
            bz[i] = (float) (b.z + radius * (cos * u.z + sin * v.z));
        }

        for (int i = 0; i < CROSS_SECTION_SEGMENTS; i++) {
            int j = (i + 1) % CROSS_SECTION_SEGMENTS;
            addVertex(buf, matrix, ax[i], ay[i], az[i], r, g, bl, aAlpha);
            addVertex(buf, matrix, ax[j], ay[j], az[j], r, g, bl, aAlpha);
            addVertex(buf, matrix, bx[j], by[j], bz[j], r, g, bl, aAlpha);
            addVertex(buf, matrix, bx[i], by[i], bz[i], r, g, bl, aAlpha);
        }
    }

    private static void addVertex(BufferBuilder buf, Matrix4f matrix,
                                  float x, float y, float z, float r, float g, float b, float a) {
        buf.addVertex(matrix, x, y, z).setColor(r, g, b, a);
    }

    // ==================== 屏幕信息面板 ====================

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || target == null)
            return;
        if (!isHoldingCutter(mc))
            return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        Font font = mc.font;
        List<Component> lines = buildInfoLines(target);

        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * 10 + 8;
        int x = guiGraphics.guiWidth() / 2 + 18;
        int y = guiGraphics.guiHeight() / 2 - height / 2;
        x = Math.min(x, guiGraphics.guiWidth() - width - 16);
        y = Math.max(4, y);

        guiGraphics.fill(x - 5, y - 5, x + width + 5, y + height + 5, 0x90101010);
        guiGraphics.fill(x - 5, y - 5, x + width + 5, y - 4, 0xFFF0C75E);
        guiGraphics.fill(x - 5, y + height + 4, x + width + 5, y + height + 5, 0xFFF0C75E);
        guiGraphics.fill(x - 5, y - 5, x - 4, y + height + 5, 0xFFF0C75E);
        guiGraphics.fill(x + width + 4, y - 5, x + width + 5, y + height + 5, 0xFFF0C75E);

        int lineY = y;
        for (Component line : lines) {
            guiGraphics.drawString(font, line, x, lineY, 0xFFFFFFFF, false);
            lineY += 10;
        }
    }

    private static List<Component> buildInfoLines(GridConnection connection) {
        GridWireType type = connection.wireType();
        List<Component> lines = new ArrayList<>();

        lines.add(Component.translatable("item.poly_mech." + type.spoolItemName())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        lines.add(Component.translatable("tooltip.poly_mech.wire.tier", type.getVoltageTier().getName())
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.poly_mech.wire.max_voltage", type.getMaxVoltage())
                .withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("tooltip.poly_mech.wire.max_amperage", type.getMaxAmperage())
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.poly_mech.wire.max_power", type.getMaxPower())
                .withStyle(ChatFormatting.YELLOW));
        lines.add(Component.translatable("tooltip.poly_mech.wire.resistance", formatResistance(type.getResistance()))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.poly_mech.wire_cutter.length",
                String.format(Locale.ROOT, "%.1f", connection.length())).withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("gui.poly_mech.wire_cutter.total_resistance",
                String.format(Locale.ROOT, "%.3f", connection.getResistance())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.poly_mech.wire_cutter.nodes",
                WireTargeting.nodeLabel(connection.node1()), WireTargeting.nodeLabel(connection.node2()))
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("gui.poly_mech.wire_cutter.hint")
                .withStyle(ChatFormatting.GREEN));

        return lines;
    }

    private static String formatResistance(double resistance) {
        return resistance == Math.rint(resistance)
                ? String.valueOf((long) resistance)
                : String.valueOf(resistance);
    }
}
