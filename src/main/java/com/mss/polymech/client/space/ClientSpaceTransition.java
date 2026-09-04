package com.mss.polymech.client.space;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端无缝切换状态。服务端在跨维度前发送目标位置，客户端在 respawn 后应用。
 */
public final class ClientSpaceTransition {

    private static boolean active;
    private static boolean applied;
    private static double x, y, z;
    private static float yRot, xRot;

    private ClientSpaceTransition() {
    }

    public static void begin(double targetX, double targetY, double targetZ, float targetYRot, float targetXRot) {
        active = true;
        applied = false;
        x = targetX;
        y = targetY;
        z = targetZ;
        yRot = targetYRot;
        xRot = targetXRot;
    }

    public static boolean isActive() {
        return active;
    }

    public static void apply(LocalPlayer player) {
        if (!active || applied || player == null) return;
        player.moveTo(x, y, z);
        player.setYRot(yRot);
        player.setXRot(xRot);
        applied = true;
    }

    /** 加载完成后关闭取消加载画面渲染的标记。 */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (applied && mc.level != null && mc.player != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen)) {
            active = false;
        }
    }
}
