package com.mss.polymech.mps.kelvin.physical.space_world;

import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端太空世界 —— <b>与
 * {@code org.cn_grass_block.kelvin.physical.space_world.ClientSpaceWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 客户端持有<b>一对</b>太空世界：{@code spaceWorld}（对外可见 / 渲染用）与
 * {@code bufferSpaceWorld}（网络缓冲区），并负责把缓冲区的位姿搬到显示世界。
 *
 * <h2>为什么要两个世界（照 space 0.1.3）</h2>
 * 天体位姿由服务端以<b>批量包</b>下发（{@code SyncCelestialBodyMoveBatch}），
 * 到达节奏是"一阵一阵"的，而渲染是每帧连续采样。若网络包直接写显示世界，
 * 天体就会<b>一顿一顿</b>地跳。
 * <p>所以：网络只写 {@code bufferSpaceWorld}；每帧渲染前调 {@link #syncMoveData()}，
 * 先 {@link SpaceWorld#up()} 把缓冲区里排队的操作 flush 掉（让缓冲世界反映最新状态），
 * 再<b>整块</b>把每个天体的 pos/rotate 用 {@code *Direct} 拷进显示世界。
 * 这样"突变"只发生在同步点，而同步点之间的插值交给
 * {@link CelestialBody#getSmoothPos(float)} / {@code getSmoothRotate}。</p>
 *
 * <p>注意拷贝走的是 {@code moveToDirect}/{@code rotateToDirect}：显示世界是纯客户端
 * 影子，没有物理线程，也<b>不该</b>再入队（入队只会被自己下一帧的 {@code up()} 消费，
 * 白白多一层延迟）。</p>
 *
 * <p>调用约定（照 space）：只有 {@code SyncSpaceWorldCreate} 会
 * {@link #setSpaceWorld}/{@link #setBufferSpaceWorld}，且两者同时写入；
 * {@link #syncMoveData()} 只在渲染路径上、且已确认 `spaceWorld != null` 后调用。</p>
 */
public class ClientSpaceWorld extends SpaceWorld {

    private static ClientSpaceWorld spaceWorld = null;
    private static ClientSpaceWorld bufferSpaceWorld = null;

    public ClientSpaceWorld(ResourceLocation WorldID, ResourceLocation SkyBoxTexture) {
        super(WorldID, SkyBoxTexture);
    }

    /** "我现在在太空"的唯一判据（客户端侧）。 */
    public static boolean isSpaceWorld() {
        return spaceWorld != null;
    }

    /** 退出世界 / 客户端清理时调；两个都清，否则残留的影子世界会被下一局复用。 */
    public static void init() {
        spaceWorld = null;
        bufferSpaceWorld = null;
    }

    /** 每帧渲染前：flush 缓冲区 → 把缓冲区的位姿整体拷进显示世界。 */
    public static void syncMoveData() {
        if (bufferSpaceWorld != null) {
            bufferSpaceWorld.up();
        }

        for (CelestialBody celestialBody : spaceWorld.getAllCelestialBody()) {
            CelestialBody bufferCelestialBody = bufferSpaceWorld.getCelestialBody(celestialBody.getName());
            if (bufferCelestialBody != null) {
                celestialBody.moveToDirect(bufferCelestialBody.getPos());
                celestialBody.rotateToDirect(bufferCelestialBody.getRotate());
            }
        }
    }

    public static ClientSpaceWorld getSpaceWorld() {
        return spaceWorld;
    }

    public static void setSpaceWorld(ClientSpaceWorld spaceWorld) {
        ClientSpaceWorld.spaceWorld = spaceWorld;
    }

    public static ClientSpaceWorld getBufferSpaceWorld() {
        return bufferSpaceWorld;
    }

    public static void setBufferSpaceWorld(ClientSpaceWorld bufferSpaceWorld) {
        ClientSpaceWorld.bufferSpaceWorld = bufferSpaceWorld;
    }
}
