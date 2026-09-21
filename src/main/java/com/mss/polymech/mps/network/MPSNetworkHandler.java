package com.mss.polymech.mps.network;

import com.mss.polymech.mps.network.packet.PhysicalBodyInteractionPacket;
import com.mss.polymech.mps.network.packet.SyncPhysicalBlockBreakProgress;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyBlockEntity;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyBlockUpdate;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyCreate;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyMoveBatch;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyRemove;
import com.mss.polymech.mps.network.packet.SyncPhysicalThreadStart;
import com.mss.polymech.mps.network.packet.SyncPhysicalWorldCreate;
import com.mss.polymech.mps.space.network.packet.SyncPhysicalWorldEnd;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * MPS 克隆层网络包注册 —— <b>与
 * {@code org.polaris2023.mps.network.MPSNetworkHandler} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>包的分工（按"有没有下一次"分层，见各包注释）</h2>
 * <pre>
 *   SyncPhysicalThreadStart      sendCritical  客户端物理线程启动
 *   SyncPhysicalWorldCreate      可靠          世界 + 重力
 *   SyncPhysicalBodyCreate       可靠          体（身份 + 初始位姿）
 *   SyncPhysicalWorldEnd         可靠          摘要对账（诊断）
 *   ─────────────────────────────── 以上丢了就永久缺状态 ───────────────────────────────
 *   SyncPhysicalBodyBlockUpdate  直发          2×2×2 子块方块状态调色板
 *   SyncPhysicalBodyBlockEntity  直发          同子块的方块实体 NBT
 *   SyncPhysicalBodyMoveBatch    直发（每 tick）位姿批量
 *   SyncPhysicalBlockBreakProgress 直发        挖掘进度
 * </pre>
 * 下四个都<b>有"下一次"</b>（脏块回写 / 每 tick 批量 / 进度会再发），
 * 走可靠通道只会浪费带宽、放大延迟。
 *
 * <h2>与本项目既有包的关系（两套并存是已接受的代价）</h2>
 * 项目自己有一套 {@code physics_body_*} 包（`PhysicsBodyMoveBatchPacket` 等），
 * 与这里的 {@code sync_physical_*} <b>资源路径不同、不会冲突</b>，
 * 但两套会同时注册。这是方案 A 的已知代价（见 §20）：S6 切换时必须删掉一套。
 *
 * <p>未注册：{@code SyncPhysicalSelection} 与 {@code PhysicalSelectionActionPacket}
 * —— 选体魔杖子系统未移植（见 §20 末节）。</p>
 *
 * <p>版本串 {@code "1"} 与 space 一致：增减字段或改语义时必须同步改它，
 * 否则新旧端会静默解错字节。</p>
 */
public final class MPSNetworkHandler {

    private MPSNetworkHandler() {
    }

    public static void register(PayloadRegistrar registrar) {
        // 存在性（可靠通道由 ReliableCreateSender 负责，这里只管注册）
        registrar.playToClient(SyncPhysicalThreadStart.TYPE, SyncPhysicalThreadStart.STREAM_CODEC,
                SyncPhysicalThreadStart::handle);
        registrar.playToClient(SyncPhysicalWorldCreate.TYPE, SyncPhysicalWorldCreate.STREAM_CODEC,
                SyncPhysicalWorldCreate::handle);
        registrar.playToClient(SyncPhysicalBodyCreate.TYPE, SyncPhysicalBodyCreate.STREAM_CODEC,
                SyncPhysicalBodyCreate::handle);
        registrar.playToClient(SyncPhysicalWorldEnd.TYPE, SyncPhysicalWorldEnd.STREAM_CODEC,
                SyncPhysicalWorldEnd::handle);

        // 高频/可重发
        registrar.playToClient(SyncPhysicalBodyRemove.TYPE, SyncPhysicalBodyRemove.STREAM_CODEC,
                SyncPhysicalBodyRemove::handle);
        registrar.playToClient(SyncPhysicalBodyMoveBatch.TYPE, SyncPhysicalBodyMoveBatch.STREAM_CODEC,
                SyncPhysicalBodyMoveBatch::handle);
        registrar.playToClient(SyncPhysicalBodyBlockUpdate.TYPE, SyncPhysicalBodyBlockUpdate.STREAM_CODEC,
                SyncPhysicalBodyBlockUpdate::handle);
        registrar.playToClient(SyncPhysicalBodyBlockEntity.TYPE, SyncPhysicalBodyBlockEntity.STREAM_CODEC,
                SyncPhysicalBodyBlockEntity::handle);
        registrar.playToClient(SyncPhysicalBlockBreakProgress.TYPE, SyncPhysicalBlockBreakProgress.STREAM_CODEC,
                SyncPhysicalBlockBreakProgress::handle);

        // 客户端 → 服务端：交互动作
        registrar.playToServer(PhysicalBodyInteractionPacket.TYPE, PhysicalBodyInteractionPacket.STREAM_CODEC,
                PhysicalBodyInteractionPacket::handle);
    }
}
