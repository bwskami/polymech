package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.mps.thread.ClientCollisionPhysicalThread;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端"退出世界"清理 —— 对应 space 0.1.3 的
 * {@code org.deep_space_studio.space.client.ClientWorldCleanup}（职责逐字相同）：
 * 退出世界时把三样<b>跨世界常驻</b>的东西复位，否则下一局会复用上一局的残留状态。
 *
 * <ol>
 *   <li>{@link ClientCollisionPhysicalThread#stopThread()} —— 步进线程是<b>整个客户端生命周期</b>的：
 *       登录时由 {@code SyncPhysicalThreadStart} 点起来一次，换维度不会重起。
 *       这里不停，下一局进世界时服务端会再发一次启动包，撞名判据拒绝启动 ⇒
 *       线程一直停在上一个客户端会话的循环里（本类出现前就是这个状态）；</li>
 *   <li>{@link ClientPhysicalWorld#init()} —— 物理世界的静态引用；</li>
 *   <li>{@link ClientSpaceWorld#init()} / {@link ClientCelestialWorld#init()} —— 太空世界与天体世界。
 *       这两个类正是"上一颗行星的重力、换算参数、影子世界"的载体，
 *       它们自己的 javadoc 就写着"否则残留的影子世界会被下一局复用"。</li>
 * </ol>
 *
 * <p><b>为什么挂 {@code LoggingOut}（退出世界）而不是"换维度"</b>：与 space 一致。
 * 换维度时这三样必须<b>继续存在</b>——步进线程要接着跑，太空/天体世界由各自的 create 包刷新；
 * 只有真正退出世界才该复位。</p>
 *
 * <p>注：{@code @EventBusSubscriber} 这里不写 {@code bus}：NeoForge 21.1 的 {@code bus()} 已标记待删除，
 * 默认即为 GAME 总线（客户端网络事件走的就是它）。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class ClientWorldCleanup {

    private ClientWorldCleanup() {
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCollisionPhysicalThread.stopThread();
        ClientPhysicalWorld.init();
        ClientSpaceWorld.init();
        ClientCelestialWorld.init();
    }
}
