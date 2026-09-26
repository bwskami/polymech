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

    /**
     * 位姿搬运次数 / 最后一次搬运的时刻（诊断用：见 {@link #getPoseCopyCount()}）。
     *
     * <p>刻意只放"无初始化器的静态 long"（默认 0，不产生 {@code <clinit>}）。</p>
     */
    private static long poseCopyCount = 0L;
    private static long lastPoseCopyNanos = 0L;

    /** 诊断用：累计位姿搬运次数。每帧都搬 ⇒ 这个数的增速 ≈ 帧率×天体数；每 tick 搬 ⇒ ≈ 20×天体数。 */
    public static long getPoseCopyCount() {
        return poseCopyCount;
    }

    /** 诊断用：最后一次真正搬运的时刻（{@link System#nanoTime()}）。 */
    public static long getLastPoseCopyNanos() {
        return lastPoseCopyNanos;
    }

    /** 每帧渲染前：flush 缓冲区 → 把缓冲区的位姿整体拷进显示世界。 */
    public static void syncMoveData() {
        if (bufferSpaceWorld != null) {
            bufferSpaceWorld.up();
        }

        for (CelestialBody celestialBody : spaceWorld.getAllCelestialBody()) {
            CelestialBody bufferCelestialBody = bufferSpaceWorld.getCelestialBody(celestialBody.getName());
            if (bufferCelestialBody != null) {
                // ★★ 只在缓冲区**真的变了**的时候才搬 —— 本项目对 space 0.1.3 的**有意偏离**。
                //
                // 【为什么必须加这个判断】moveToDirect 的第一件事是 `old_pos.set(pos)`：
                // 它把"上一帧的位置"推平成"当前帧的位置"。而 syncMoveData 是**每帧**被调的
                // （space 在 SpaceRenderer.init 每帧一次；我们另有 ClientPhysicsDriver 每 tick 一次），
                // 网络包却只有**每 tick**一份。于是同一个包值被反复搬进去：
                //   第 1 帧：old_pos = P(t−1), pos = P(t)      ← 插值还有救
                //   第 2 帧起：old_pos = P(t), pos = P(t)      ← 被推平了
                // 从此 `getSmoothPos` 的 `lerp(old_pos, pos, partialTick)` 恒等于 `pos`，
                // **插值完全失效**，天体只能在包到达的那一瞬跳一格 —— 用户看到的"跟抽帧一样"。
                // 注意本类 javadoc 声明的设计就是"突变只发生在同步点，同步点之间交给 getSmoothPos"，
                // 即**实现把自己的前提拆掉了**；这个 if 只是把前言与实现重新对齐。
                //
                // 【这是偏离，不是"修正抄错"】：space 0.1.3 同样每帧调用
                // （decompiled-space/0.1.3/.../SpaceRenderer.java:106）、moveToDirect 同形 ⇒ 它也一样跳。
                // 依据是它自己写下的意图；若要回到逐字同形，把这个 if 去掉即可（其余一字不改）。
                // 影响面仅限**渲染采样**：显示世界是纯客户端影子，不参与任何权威状态。
                if (!celestialBody.getPos().equals(bufferCelestialBody.getPos(), 1.0e-9)
                        || !celestialBody.getRotate().equals(bufferCelestialBody.getRotate(), 1.0e-9)) {
                    celestialBody.moveToDirect(bufferCelestialBody.getPos());
                    celestialBody.rotateToDirect(bufferCelestialBody.getRotate());
                    poseCopyCount++;
                    lastPoseCopyNanos = System.nanoTime();
                }
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
