package com.mss.polymech;

import com.mss.polymech.machine.BaseIOBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.BiConsumer;

/**
 * 通用代码 → 客户端 的 dist 安全钩子。
 * <p>
 * 通用类（方块/物品）直接引用 {@code net.minecraft.client.*} 会让专用服务端
 * 在类加载阶段被 dist cleaner 拒绝。通用代码只调用这里的钩子；
 * 客户端在 {@code ClientScreens}（仅客户端加载）初始化时装载真实实现，
 * 服务端保持 no-op。
 * </p>
 */
public final class ClientHooks {

    /**
     * 打开方块侧面配置屏幕（客户端实现；服务端 no-op）。
     * 使用 {@link BlockEntity} 作为参数类型：BatteryBlockEntity 等并不继承 BaseIOBlockEntity。
     */
    public static volatile BiConsumer<BlockPos, BlockEntity> sideConfigOpener = (pos, machine) -> {
    };

    /** 打开多方块选择屏幕（客户端实现；服务端 no-op）。 */
    public static volatile Runnable multiblockSelectionOpener = () -> {
    };

    private ClientHooks() {
    }
}
