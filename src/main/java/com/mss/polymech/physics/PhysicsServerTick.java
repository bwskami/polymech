package com.mss.polymech.physics;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 物理世界的 tick 驱动与生命周期。
 * <p>挂在 NeoForge 游戏总线（由 Polymech 主类注册）。</p>
 */
public final class PhysicsServerTick {

    private PhysicsServerTick() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 单机暂停时 IntegratedServer.tickServer 走 tickPaused()，不调 super.tickServer()，
        // 所以这条事件本来就不会发；这里再做一道防御，语义上也更明确：
        // 游戏暂停 = 物理世界不推进。（DedicatedServer / 已发布局域网 isPaused() 恒为 false。）
        if (event.getServer().isPaused()) {
            return;
        }
        PhysicsWorldManager.tick();
        // 投影 → 刚体缓存 的脏区块回写：红石灯亮灭、活塞推块、机器自改结构都靠它传到玩家眼前
        ProjectionManager.tick();
        // 挖掘进度超时清理：松手时没有包，只能靠服务端自己发现"停手了"并广播清裂纹
        PhysicsBodyInteraction.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 投影维度必须先就绪：它是"物理体上的方块实体照常工作"的载体，
        // 而且下面 restore 出来的物理体要能立刻挂上自己的地皮。
        ProjectionManager.init(event.getServer());
        // 进存档：把上次保存的物理体重建出来（方块早已从世界移除，存档记录是唯一副本）
        PhysicsBodyTracker.restore(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PhysicsWorldManager.shutdown();
        ProjectionManager.reset();
    }
}
