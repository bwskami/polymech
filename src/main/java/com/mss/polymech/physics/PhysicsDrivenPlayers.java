package com.mss.polymech.physics;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "这个玩家现在由物理驱动"的共享登记表（客户端 + 服务端都写）。
 *
 * <p>用途：诊断与状态判断（"本 tick 谁真的被物理接管了"）。
 * 只有客户端会写它 —— space 0.1.3 的玩家刚体本来就只在客户端存在，
 * 服务端玩家没有刚体（位置以客户端上报为准，见 {@code ServerPacketClampMixin}）。</p>
 *
 * <p><b>不再据此清零原版重力</b>：早先 {@code MixinEntity.getGravity} 会把物理驱动的玩家
 * 清零，理由"重力完全交给 Rapier"是误读 —— space 0.1.3 的玩家刚体
 * {@code gravity_scale = 0}（Rapier 根本不给玩家施加重力），玩家的重力来自**原版**那条路径
 *（太空世界 0、天体世界 G/122.5、其余 0.08）。两者只能留一个来源，否则会叠成双份；
 * 而清零那一个会让行星上的物理玩家浮起来。见 {@code MixinEntity.polymech$gravity} 与
 * {@code PlayerPhysicsBody} 的建体部分。</p>
 *
 * <p>刻意放在这个不依赖 Minecraft 的包里：混入 {@code Entity} 的 mixin 是**双端共用**的，
 * 直接引用客户端类会让专用服务端加载失败。</p>
 */
public final class PhysicsDrivenPlayers {

    private static final Set<UUID> DRIVEN = ConcurrentHashMap.newKeySet();

    private PhysicsDrivenPlayers() {
    }

    /** 标记该玩家已由物理接管（有活跃的双刚体）。 */
    public static void mark(UUID player) {
        if (player != null) {
            DRIVEN.add(player);
        }
    }

    /** 取消标记（下线、切维度、旁观、停用物理）。 */
    public static void unmark(UUID player) {
        if (player != null) {
            DRIVEN.remove(player);
        }
    }

    public static boolean isDriven(UUID player) {
        return player != null && DRIVEN.contains(player);
    }

    public static int count() {
        return DRIVEN.size();
    }

    public static void clear() {
        DRIVEN.clear();
    }
}
