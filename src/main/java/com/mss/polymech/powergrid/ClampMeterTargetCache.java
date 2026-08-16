package com.mss.polymech.powergrid;

import org.jetbrains.annotations.Nullable;

/**
 * 钳形表客户端目标缓存。
 * <p>
 * 与 {@link WireTargetCache} 分离，避免剪线钳与钳形表的客户端 tick 互相覆盖当前瞄准电线。
 * </p>
 */
public final class ClampMeterTargetCache {

    private static volatile GridConnection clientTarget;

    private ClampMeterTargetCache() {}

    @Nullable
    public static GridConnection getClientTarget() {
        return clientTarget;
    }

    public static void setClientTarget(@Nullable GridConnection target) {
        clientTarget = target;
    }
}
