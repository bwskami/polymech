package com.mss.polymech.physics;

import com.mss.polymech.network.PhysicsBodySyncPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * 通用代码 → 客户端 的物理桥（dist 安全）。
 *
 * <p>与 {@code ClientHooks} 同样的思路：同步包的 handler 在通用代码里注册，
 * 但真正的客户端实现（物理体注册表 + 渲染）只允许客户端类加载。
 * 服务端保持 no-op。</p>
 */
public final class PhysicsClientHooks {

    /** 客户端装载：接收物理体同步包。默认 no-op（服务端）。 */
    public static volatile Consumer<PhysicsBodySyncPacket> bodySyncConsumer = packet -> {
    };

    /** 客户端装载：接收物理体的方块实体同步包（箱子/熔炉等的渲染数据）。默认 no-op（服务端）。 */
    public static volatile Consumer<com.mss.polymech.network.PhysicsBodyBlockEntityPacket>
            bodyBlockEntityConsumer = packet -> {
    };

    /** 客户端装载：接收物理体批量运动同步（每 tick 一个包，内含全部刚体）。默认 no-op（服务端）。 */
    public static volatile Consumer<com.mss.polymech.network.PhysicsBodyMoveBatchPacket>
            bodyMoveBatchConsumer = packet -> {
    };

    /** 物理接管移动（客户端实现）；服务端 no-op。返回 true 表示已接管，本次不再执行原版 setPos。 */
    public static volatile MovementDriver movementDriver = (entity, delta) -> false;

    /** 客户端物理驱动移动的接口。 */
    @FunctionalInterface
    public interface MovementDriver {
        boolean drive(Entity entity, Vec3 delta);
    }

    public static boolean tryDrive(Entity entity, Vec3 delta) {
        return movementDriver.drive(entity, delta);
    }

    private PhysicsClientHooks() {
    }

    public static void acceptBodySync(PhysicsBodySyncPacket packet) {
        bodySyncConsumer.accept(packet);
    }

    public static void acceptBodyBlockEntities(com.mss.polymech.network.PhysicsBodyBlockEntityPacket packet) {
        bodyBlockEntityConsumer.accept(packet);
    }

    public static void acceptBodyMoveBatch(com.mss.polymech.network.PhysicsBodyMoveBatchPacket packet) {
        bodyMoveBatchConsumer.accept(packet);
    }
}
