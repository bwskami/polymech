package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.renderer.ClientWireCache;
import com.mss.polymech.item.WireCutterItem;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.GridWireType;
import com.mss.polymech.powergrid.WireTargetCache;
import com.mss.polymech.powergrid.WireTargeting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

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

        // 起点走 ClientViewOrigin（相机位置）：准星是相机的视线，太空里眼睛与相机差 1.31~1.43 格。
        Vec3 eye = com.mss.polymech.client.physics.ClientViewOrigin.of(mc.player);
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

        WireHighlightRenderer.render(event, target);
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

        drawPanel(guiGraphics, font, lines);
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

    static void drawPanel(GuiGraphics guiGraphics, Font font, List<Component> lines) {
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

    private static String formatResistance(double resistance) {
        return resistance == Math.rint(resistance)
                ? String.valueOf((long) resistance)
                : String.valueOf(resistance);
    }
}
