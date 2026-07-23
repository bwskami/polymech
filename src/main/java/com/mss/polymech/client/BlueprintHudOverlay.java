package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class BlueprintHudOverlay {

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!BlueprintPreviewState.isActive()) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        Font font = mc.font;

        int screenWidth = guiGraphics.guiWidth();
        int x = 10;
        int y = 10;

        String modeName = switch (BlueprintPreviewState.getMode()) {
            case FACING -> "\u671d\u5411\u6a21\u5f0f";
            case XYZ -> "\u5750\u6807\u6a21\u5f0f";
            case CONFIRM -> "\u786e\u8ba4\u6a21\u5f0f";
        };
        int modeColor = switch (BlueprintPreviewState.getMode()) {
            case FACING -> 0xFF55FFFF;
            case XYZ -> 0xFFFFFF55;
            case CONFIRM -> 0xFF55FF55;
        };

        String facingStr = BlueprintPreviewState.getFacing().getName();
        int ox = BlueprintPreviewState.getOffsetX();
        int oy = BlueprintPreviewState.getOffsetY();
        int oz = BlueprintPreviewState.getOffsetZ();

        String line1 = "\u6a21\u5f0f: " + modeName;
        String line2 = "\u671d\u5411: " + facingStr;
        String line3 = "\u504f\u79fb: X=" + ox + " Y=" + oy + " Z=" + oz;
        String line4;
        if (BlueprintPreviewState.getMode() == BlueprintPreviewState.Mode.XYZ) {
            String axisName = switch (BlueprintPreviewState.getCurrentAxis()) {
                case 0 -> "X";
                case 1 -> "Y";
                case 2 -> "Z";
                default -> "?";
            };
            line4 = "\u5f53\u524d\u8f74: " + axisName;
        } else {
            line4 = "";
        }
        String hint1 = "[R] \u5207\u6362\u6a21\u5f0f  [X] \u5207\u6362\u8f74  [\u6eda\u8f6e] \u8c03\u6574";
        String hint2 = BlueprintPreviewState.getMode() == BlueprintPreviewState.Mode.CONFIRM
                ? "[\u53f3\u952e] \u653e\u7f6e\u673a\u5668  [G] \u53d6\u6d88"
                : "[G] \u53d6\u6d88";

        int maxWidth = Math.max(
                Math.max(font.width(line1), font.width(line2)),
                Math.max(font.width(line3), Math.max(font.width(line4), font.width(hint1)))
        );
        maxWidth = Math.max(maxWidth, font.width(hint2));
        int bgWidth = maxWidth + 10;
        int bgHeight = (line4.isEmpty() ? 72 : 84) + 8;

        guiGraphics.fill(x - 4, y - 4, x + bgWidth, y + bgHeight, 0x80000000);

        guiGraphics.drawString(font, Component.literal(line1), x, y, modeColor, true);
        guiGraphics.drawString(font, Component.literal(line2), x, y + 12, 0xFFFFFFFF, true);
        guiGraphics.drawString(font, Component.literal(line3), x, y + 24, 0xFFFFFFFF, true);
        if (!line4.isEmpty()) {
            guiGraphics.drawString(font, Component.literal(line4), x, y + 36, 0xFFFFFF00, true);
        }
        int hintY = line4.isEmpty() ? y + 48 : y + 60;
        guiGraphics.drawString(font, Component.literal(hint1), x, hintY, 0xFFAAAAAA, true);
        guiGraphics.drawString(font, Component.literal(hint2), x, hintY + 12, 0xFFAAAAAA, true);

        event.setCanceled(true);
    }
}
