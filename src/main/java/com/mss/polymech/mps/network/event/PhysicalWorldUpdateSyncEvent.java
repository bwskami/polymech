package com.mss.polymech.mps.network.event;

import com.mss.polymech.mps.network.packet.SyncPhysicalBodyBlockUpdate;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyCreate;
import com.mss.polymech.mps.network.packet.SyncPhysicalThreadStart;
import com.mss.polymech.mps.network.packet.SyncPhysicalWorldCreate;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import com.mss.polymech.mps.space.network.ReliableCreateSender;
import com.mss.polymech.mps.space.network.packet.SyncPhysicalWorldEnd;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * 玩家加入维度时的物理世界全量下发 —— <b>与
 * {@code org.polaris2023.mps.network.event.PhysicalWorldUpdateSyncEvent} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>发送顺序是依赖顺序，不能换</h2>
 * <pre>
 *   1. SyncPhysicalThreadStart            ← sendCritical：先把客户端的物理线程点起来
 *   2. SyncPhysicalWorldCreate            ← 世界（含重力）必须先存在
 *   3. SyncPhysicalBodyCreate ×N          ← 体（客户端建运动学镜像）
 *   4. SyncPhysicalBodyBlockUpdate ×8/体   ← 2×2×2 子块的方块状态（体建好才有地方放）
 *   5. SyncPhysicalWorldEnd               ← 摘要对账
 * </pre>
 * 第 1 步用 {@code sendCritical}（重发 10 次、失败踢人）：线程没起来，
 * 后面收到的所有位姿包都会"没人消费"，船永远停在原地。
 *
 * <h2>两个"没有物理世界"也必须发包</h2>
 * 服务端这个维度没有物理世界时，仍然发
 * {@code SyncPhysicalWorldCreate(id, null, null)} + {@code SyncPhysicalWorldEnd(null, 0)}：
 * 客户端可能刚从<b>有</b>物理世界的维度走过来，本地还留着旧世界；
 * 不发这两份，它会继续推一个已经不存在的世界（表现为"船在空气里继续动"），
 * 而且 {@code SyncPhysicalWorldEnd} 的对账也不会触发。
 *
 * <h2>方块状态不走可靠通道（这是刻意的）</h2>
 * 第 4 步是 {@code PacketDistributor.sendToPlayer} 直发，而 1/2/3/5 走
 * {@link ReliableCreateSender}。理由与 kelvin 的位姿批量同源：
 * <b>方块状态有"下一次"</b>（{@code uploadChunk}/脏块回写会重发），
 * 而"世界/体的存在"没有下一次 —— 丢了就永久缺。所以只有"存在性"需要可靠传输。
 *
 * <p>用 {@code EntityJoinLevelEvent} 而不是登录事件：<b>换维度也会触发</b>，
 * 于是"换维度后重新同步一遍"零额外机制（与 kelvin 的
 * {@code SpaceWorldUpdateSyncEvent} 同一套路）。</p>
 */
public class PhysicalWorldUpdateSyncEvent {

    @SubscribeEvent
    public static void onPlayerLoggedIn(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 1) 先把客户端的物理线程点起来
        ReliableCreateSender.sendCritical(serverPlayer, SyncPhysicalThreadStart::new);

        ServerLevel serverLevel = serverPlayer.serverLevel();
        ServerPhysicalWorld serverPhysicalWorld = ServerPhysicalWorld.getPhysicalWorld(serverLevel);
        if (serverPhysicalWorld == null) {
            // 见类注释：没有物理世界也要发"没有"，让客户端清掉旧世界
            ReliableCreateSender.send(serverPlayer, id -> new SyncPhysicalWorldCreate(id, null, null));
            ReliableCreateSender.send(serverPlayer, id -> new SyncPhysicalWorldEnd(null, 0));
            return;
        }

        // 2) 世界（含重力）
        ReliableCreateSender.send(serverPlayer, id -> new SyncPhysicalWorldCreate(
                id, serverPhysicalWorld.getLevel(), serverPhysicalWorld.getG()));

        // 3) 体 + 4) 方块状态
        for (PhysicalBody physicalBody : serverPhysicalWorld.getAllPhysicalBody()) {
            ReliableCreateSender.send(serverPlayer, id -> new SyncPhysicalBodyCreate(
                    id, physicalBody.getLevel(), new Vector3d(physicalBody.getPos()),
                    new Quaterniond(physicalBody.getRotation()), physicalBody.getUuid()));

            for (int x = -1; x <= 0; x++) {
                for (int y = -1; y <= 0; y++) {
                    for (int z = -1; z <= 0; z++) {
                        PacketDistributor.sendToPlayer(serverPlayer, new SyncPhysicalBodyBlockUpdate(
                                physicalBody.getUuid(), new Vector3i(x, y, z),
                                physicalBody.getBlockStateChunkInt(x, y, z)));
                    }
                }
            }
        }

        // 5) 摘要对账
        ReliableCreateSender.send(serverPlayer, id -> new SyncPhysicalWorldEnd(
                serverPhysicalWorld.getLevel(), serverPhysicalWorld.getAllPhysicalBody().size()));
    }
}
