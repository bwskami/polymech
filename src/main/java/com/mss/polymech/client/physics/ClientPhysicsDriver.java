package com.mss.polymech.client.physics;

import com.mss.polymech.Polymech;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端物理世界的 tick 驱动。
 *
 * <p>刻意放在 {@link ClientTickEvent.Pre}（实体 tick 之前）：这样玩家 {@code move()} 里
 * 施加的力会在紧接着的物理步进里生效、并且读回的是最新位置。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientPhysicsDriver {

    private ClientPhysicsDriver() {
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        ClientPhysics.tick();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        // 玩家 tick 之后：飞行时把玩家刚体同步到玩家位置（保留物理碰撞）
        ClientPhysics.afterPlayerTick();
        // ★ 天体显示世界同步（照 space 的"每帧渲染前 syncMoveData"意图，但**不能**依赖渲染路径）：
        // space 的 SpaceRenderer 在**所有维度**都跑（它有 CelestialWorld 分支算地表天空），
        // 所以它的 syncMoveData 到处都生效；我们照抄时把渲染器守在了 `dimension == SPACE`，
        // 于是站在**地表维度**（火星/金星…）时这一步永远不会发生 ——
        // 客户端显示世界停在创建快照，而 SpaceWorld.gamePos 优先读的正是它。
        // 实测证据：在 venus 上 50 秒，地球 gamePos 一位不变（1× 下应走约 150 格）。
        // 所以这里按 tick 同步一次（不依赖任何维度的渲染路径）；
        // SpaceRenderer 里那次保留 —— 渲染路径需要"消费位置之前"的同一帧同步。
        var celestials = com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld.getSpaceWorld();
        if (celestials != null) {
            com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld.syncMoveData();
        }
        // 天体位置日志（周期性 + 换维度/传送立刻打一次）：人在游戏里、日志在外，
        // 存档往返与传送落点只能靠它取证（见 KelvinDiagnostics 类注释）。
        com.mss.polymech.mps.kelvin.KelvinDiagnostics.tick();
    }
}
