package com.mss.polymech.powergrid;

import org.jetbrains.annotations.Nullable;

/**
 * 剪线钳客户端目标缓存。
 * <p>
 * 客户端每 tick 通过 {@code WireCutterOverlay} 计算当前准星瞄准的电线连接，
 * 并写入本缓存；{@code WireCutterItem} 在客户端交互时读取它，
 * 以便在本地预测是否应吞掉右键交互（避免误触背后方块）。
 * </p>
 */
public final class WireTargetCache {

    private static volatile GridConnection clientTarget;

    private WireTargetCache() {}

    /** 客户端渲染/交互线程读取当前目标 */
    @Nullable
    public static GridConnection getClientTarget() {
        return clientTarget;
    }

    /** 客户端 tick 更新当前目标（null=未瞄准任何电线） */
    public static void setClientTarget(@Nullable GridConnection target) {
        clientTarget = target;
    }
}
