package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.renderer.ClientWireCache;
import com.mss.polymech.item.ClampMeterItem;
import com.mss.polymech.powergrid.ClampMeterMeasurementState;
import com.mss.polymech.powergrid.ClampMeterTargetCache;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.GridWireType;
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
 * 钳形表客户端 HUD 与瞄准描边。
 * <p>
 * 每 tick 计算准星瞄准的电线并更新 {@link ClampMeterTargetCache}；
 * 瞄准时显示与剪线钳相同的电线描边，并在准星旁显示电线参数；
 * 右键后追加显示该电线当前电压/电流/功率。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class ClampMeterOverlay {

    private static final double TARGET_REACH = WireTargeting.TARGET_REACH;

    private static GridConnection target;
    private static GridConnection lastTarget;

    private ClampMeterOverlay() {}

    // ==================== 目标更新 ====================

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || mc.player.isSpectator()) {
            return;
        }
        if (!isHoldingClampMeter(mc)) {
            ClampMeterTargetCache.setClientTarget(null);
            return;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getViewVector(1.0F);
        double blockClip = WireTargeting.blockClipDistance(mc.level, mc.player, eye, look, TARGET_REACH);
        GridConnection found = WireTargeting.findConnection(
                mc.level, ClientWireCache.getAll(), eye, look, TARGET_REACH, blockClip);
        target = found;
        ClampMeterTargetCache.setClientTarget(found);

        if (!java.util.Objects.equals(found, lastTarget)) {
            lastTarget = found;
            ClampMeterMeasurementState.clear();
        }
    }

    private static boolean isHoldingClampMeter(Minecraft mc) {
        return mc.player.getMainHandItem().getItem() instanceof ClampMeterItem
                || mc.player.getOffhandItem().getItem() instanceof ClampMeterItem;
    }

    // ==================== 世界描边 ====================

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || target == null)
            return;
        if (!isHoldingClampMeter(mc))
            return;

        WireHighlightRenderer.render(event, target);
    }

    // ==================== 屏幕信息面板 ====================

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || target == null)
            return;
        if (!isHoldingClampMeter(mc))
            return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        Font font = mc.font;
        List<Component> lines = buildInfoLines(target);

        WireCutterOverlay.drawPanel(guiGraphics, font, lines);
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

        GridConnection measured = ClampMeterMeasurementState.connection();
        if (measured != null && measured.equals(connection)) {
            double current = ClampMeterMeasurementState.current();
            int voltage = ClampMeterMeasurementState.voltage();
            double power = voltage * current;
            lines.add(Component.translatable("gui.poly_mech.clamp_meter.voltage", voltage)
                    .withStyle(ChatFormatting.GOLD));
            lines.add(Component.translatable("gui.poly_mech.clamp_meter.current",
                    String.format(Locale.ROOT, "%.2f", current))
                    .withStyle(ChatFormatting.AQUA));
            lines.add(Component.translatable("gui.poly_mech.clamp_meter.power",
                    String.format(Locale.ROOT, "%.1f", power))
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            lines.add(Component.translatable("gui.poly_mech.clamp_meter.prompt")
                    .withStyle(ChatFormatting.GREEN));
        }

        return lines;
    }

    private static String formatResistance(double resistance) {
        return resistance == Math.rint(resistance)
                ? String.valueOf((long) resistance)
                : String.valueOf(resistance);
    }
}
