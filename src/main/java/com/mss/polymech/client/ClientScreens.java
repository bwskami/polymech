package com.mss.polymech.client;

import com.mss.polymech.ClientHooks;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.screen.MultiblockSelectionScreen;
import com.mss.polymech.client.gui.screen.SideConfigScreen;
import com.mss.polymech.machine.BaseIOBlockEntity;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 仅客户端的屏幕打开器集合。
 * <p>
 * 通用代码（方块/物品）不允许直接引用 {@code net.minecraft.client.*}，
 * 否则专用服务端会在类加载时被 dist cleaner 拒绝（BaseMachineBlock 曾因此崩溃）。
 * 通用代码改为调用 {@link ClientHooks}，由本类在客户端初始化时装载真实实现。
 * </p>
 * <p>
 * 注意：{@code @EventBusSubscriber} 要求类中至少有一个 {@code @SubscribeEvent} 方法，
 * 否则 NeoForge 会抛 {@code IllegalArgumentException: ... has no @SubscribeEvent methods}。
 * 因此这里用 {@link FMLClientSetupEvent} 订阅来完成钩子装载（早于任何玩家交互）。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientScreens {

    private ClientScreens() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        ClientHooks.sideConfigOpener = (pos, machine) -> {
            if (!(machine instanceof BaseIOBlockEntity ioMachine)) {
                return;
            }
            runOnMainThread(() -> Minecraft.getInstance().setScreen(
                    new SideConfigScreen(pos, ioMachine.getSideConfig())));
        };
        ClientHooks.multiblockSelectionOpener =
                () -> runOnMainThread(() -> Minecraft.getInstance().setScreen(
                        new MultiblockSelectionScreen()));
    }

    private static void runOnMainThread(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}
