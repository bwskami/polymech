package com.mss.polymech.mps.kelvin.network;

import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialBodyCreate;
import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialBodyMoveBatch;
import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialWorldCreate;
import com.mss.polymech.mps.kelvin.network.packet.SyncSpaceWorldCreate;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * kelvin 网络包注册 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.KelvinNetworkHandler} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把 kelvin 的四个包登记到 NeoForge 的 play 阶段（全部<b>服务端 → 客户端</b>）。
 *
 * <h2>四个包的分工</h2>
 * <pre>
 *   SyncSpaceWorldCreate       一次：告诉客户端"你在太空里"，并建显示/缓冲两个世界
 *   SyncCelestialBodyCreate    每个天体一次：完整身份（含类型判别字节）
 *   SyncCelestialWorldCreate   一次：地表维度参数（重力/贴图投影/minY/height）
 *   SyncCelestialBodyMoveBatch 每 tick：只要名字 + 位姿（流式，不可靠）
 * </pre>
 * 前三个走 {@code ReliableCreateSender}（重发至回执），第四个<b>刻意不走</b>
 * —— 每 tick 都有下一份，可靠传输只会浪费带宽（见各包注释）。
 *
 * <p>全部 {@code playToClient}：客户端从不上报天体数据，它只回执
 * （{@code SyncCreateAck}，单独注册）。</p>
 *
 * <p>版本串 {@code "1"} 与 space 一致：这是<b>包格式版本</b>，
 * 增减字段或改语义时必须同步改它，否则新旧端会静默解错字节。</p>
 */
public final class KelvinNetworkHandler {

    private KelvinNetworkHandler() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(SyncSpaceWorldCreate.TYPE, SyncSpaceWorldCreate.STREAM_CODEC,
                SyncSpaceWorldCreate::handle);
        registrar.playToClient(SyncCelestialWorldCreate.TYPE, SyncCelestialWorldCreate.STREAM_CODEC,
                SyncCelestialWorldCreate::handle);
        registrar.playToClient(SyncCelestialBodyCreate.TYPE, SyncCelestialBodyCreate.STREAM_CODEC,
                SyncCelestialBodyCreate::handle);
        registrar.playToClient(SyncCelestialBodyMoveBatch.TYPE, SyncCelestialBodyMoveBatch.STREAM_CODEC,
                SyncCelestialBodyMoveBatch::handle);
    }
}
