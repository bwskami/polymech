package com.mss.polymech.mps.kelvin.physical.celestial_body.variant;

import com.mss.polymech.mps.kelvin.OrbitPhysicalThread;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Arrays;

/**
 * 流星体 —— <b>与 {@code org.cn_grass_block.kelvin.physical.celestial_body.variant.Meteoroid}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 一个会<b>烧蚀</b>的小天体：进入行星大气时被气动加热升温、同时向外辐射与对流散热，
 * 并把颜色按温度变红/变白；同时留一条 32 点的<b>尾迹</b>给渲染画拖尾。
 *
 * <h2>能量模型（照 space 0.1.3；这是"每个类的大体实现方法"的核心）</h2>
 * <pre>
 *   相对速度   V = |v_self − v_planet|                    // 不能只看自己速度
 *   气动阻力   F = ρ(h)·C_d·π·r²·V² / 2                   // C_d = 1.0
 *   迎风面积   S = 4πr²
 *   吸热       Q_I = F·V·dt                               // 阻力做功全变热
 *   散热       Q_O = σ_eff·S·(T⁴ − T_周围⁴) + 对流项        // σ_eff = 5.3301519538599993E-8
 *   升温       ΔT = (Q_I − Q_O) / 600 / m                 // 600 ≈ 岩石比热(J/(kg·K))
 * </pre>
 * 三点必须照抄的理由：
 * <ol>
 *   <li><b>相对速度</b>：用自身速度会让"和行星同向同速飞行的流星"也被烤红 —— 大气静止在
 *       行星参考系里，热量只由<b>相对</b>速度产生。</li>
 *   <li><b>对流项只在稠密大气里加</b>（{@code InAtmospheric}）：卡门线外用对流公式没有物理意义，
 *       且系数 10000 很大，误用会让高空的流星瞬间被吹凉。</li>
 *   <li><b>T⁴ 辐射</b>：高温段辐射占主导，所以流星越热散热越快，自然稳定在某个平衡温度；
 *       换成线性散热会让温度无界增长。</li>
 * </ol>
 * {@code Q_I}/{@code Q_O} 任一为 NaN（大气参数没配全）时<b>整步跳过</b>温度更新，
 * 而不是带着 NaN 继续 —— NaN 一旦进 {@code Temperature} 就再也回不来
 * （{@code NaN + x = NaN}，{@code TemperatureToRGB(NaN)} 会吐黑）。
 *
 * <p><b>为什么只认最近的 {@link Planet}</b>：气动加热本质是"我此刻穿谁的大气"，
 * 用最近的那颗即可；遍历全部天体求叠加既无物理依据也贵。
 * {@link SpaceWorld#getNearCelestialBody} 已按距离升序，取第一个 {@code instanceof Planet}
 * 就是最近行星。</p>
 *
 * <h2>两个字段名很像但完全不同（容易写错）</h2>
 * <ul>
 *   <li>{@link CelestialBody} 的 {@code pos}（私有）—— 物理位置，物理线程每步更新。</li>
 *   <li>{@code this.pos}（本类新增）—— <b>上一次落尾迹采样点</b>，只在移动超过
 *       {@code 100000} 时更新。不是位置，是"尾迹游标"。</li>
 * </ul>
 *
 * <p>构造里给名字拼 {@code Math.random()}：{@link CelestialBody#equals} <b>只比名字</b>，
 * 而流星会大量生成，重名会让它们在池里被当成同一个天体互相顶掉。</p>
 */
public class Meteoroid extends CelestialBody {

    /** 当前温度（K）；初值 2.73 = 宇宙微波背景。 */
    private double Temperature = 2.73;
    /** 上一次落尾迹的位置（尾迹游标，不是物理位置；见类注释）。 */
    public Vector3d pos = new Vector3d();
    /** 自发光颜色（按温度算），渲染直接用。 */
    public double[] RGB = new double[]{1.0, 1.0, 1.0};
    /** 尾迹：32 个采样点 × 3 个 double；定长环形缓冲，避免每帧分配。 */
    private final double[] samples = new double[96];
    private int index = 0;
    /** 环形缓冲是否已绕过一圈（决定读取时的起点与有效长度）。 */
    private boolean filled = false;
    /** 输出缓冲复用（32 × (xyz + 半径)）；{@link #getSamples} 返回其副本。 */
    private final float[] resultBuffer = new float[128];

    public Meteoroid(String level, String name, Vector3d pos, Quaterniond rotate, double radius) {
        super(level, name + Math.random(), pos, rotate, radius);
        this.setMass(1.47E16);
        this.speed().set(50000.0, 0.0, 0.0);
    }

    @Override
    public void tick() {
        SpaceWorld spaceWorld = ServerSpaceWorld.getSpaceWorld(ResourceLocation.parse(this.level));
        if (spaceWorld != null) {
            Planet planet = null;
            // getNearCelestialBody 已按距离升序 → 第一个 Planet 就是最近行星
            for (CelestialBody celestialBody : spaceWorld.getNearCelestialBody(this.getPos())) {
                if (celestialBody instanceof Planet planet1) {
                    planet = planet1;
                    break;
                }
            }

            if (planet != null) {
                // 气动加热必须用相对行星的速度（见类注释）
                double V = new Vector3d(this.speed()).sub(planet.speed()).length();
                double F = planet.getAtmosphericDensity(this.getPos()) * 1.0
                        * Math.PI * this.getRadius() * this.getRadius() * V * V / 2.0;
                double S = (Math.PI * 4) * this.getRadius() * this.getRadius();
                boolean InAtmospheric = this.getPos().distance(planet.getPos())
                        < planet.getRadius() + planet.getAtmosphericHeight();
                double T_Nearby = InAtmospheric ? planet.getAtmosphericTemperature() : 2.73;
                double Q_I = F * V * OrbitPhysicalThread.core_tick_time;
                double Q_O = 5.3301519538599993E-8 * S
                        * (Math.pow(this.Temperature, 4.0) - Math.pow(T_Nearby, 4.0))
                        + (InAtmospheric ? 10000.0 * S * (this.Temperature - T_Nearby) : 0.0);
                // 参数没配全时整步跳过，绝不让 NaN 进 Temperature（见类注释）
                if (!Double.isNaN(Q_I) && !Double.isNaN(Q_O)) {
                    this.Temperature = this.Temperature + (Q_I - Q_O) / 600.0 / this.getMass();
                }

                this.RGB = Star.TemperatureToRGB(this.Temperature);
                if (this.getPos().sub(this.pos).length() > 100000.0) {
                    this.addSample(this.getPos());
                    this.pos.set(this.getPos());
                }
            }
        }
    }

    /** 写入一个尾迹采样点（环形缓冲，满 32 后覆盖最旧的并置 {@code filled}）。 */
    public void addSample(Vector3d pos) {
        int i = this.index * 3;
        this.samples[i] = pos.x;
        this.samples[i + 1] = pos.y;
        this.samples[i + 2] = pos.z;
        this.index++;
        if (this.index >= 32) {
            this.index = 0;
            this.filled = true;
        }
    }

    /**
     * 取尾迹的渲染数据（每点 4 个 float：相对相机的偏移 xyz + 点半径）。
     *
     * <p>返回 {@code count * 4} 长度的<b>副本</b>：{@code resultBuffer} 是复用的，
     * 直接返回它会让调用方手里的数据被下一帧覆盖。</p>
     *
     * @param cameraPos     相机位置（太空里数值极大，所以必须先减再算，避免精度丢失）
     * @param spaceRotation 太空姿态（世界 → 相机朝向）
     */
    public float[] getSamples(Vector3d cameraPos, Quaterniond spaceRotation) {
        float cx = (float) cameraPos.x;
        float cy = (float) cameraPos.y;
        float cz = (float) cameraPos.z;
        int count = this.filled ? 32 : this.index;

        for (int i = 0; i < count; i++) {
            // 环形缓冲的读取起点：写满后从最旧的（= 当前 index）开始
            int srcIndex = this.filled ? (this.index + i) % 32 : i;
            int di = srcIndex * 3;
            int fi = i * 4;
            double size = this.getRenderSize(this.samples[di], this.samples[di + 1],
                    this.samples[di + 2], cameraPos, spaceRotation);
            Vector3d pos = new Vector3d(
                    (this.samples[di] - (double) cx) * size,
                    (this.samples[di + 1] - (double) cy) * size,
                    (this.samples[di + 2] - (double) cz) * size)
                    .rotate(spaceRotation);
            this.resultBuffer[fi] = (float) pos.x();
            this.resultBuffer[fi + 1] = (float) pos.y();
            this.resultBuffer[fi + 2] = (float) pos.z();
            this.resultBuffer[fi + 3] = (float) (this.getRadius() * size);
        }

        return Arrays.copyOf(this.resultBuffer, count * 4);
    }

    /**
     * 距离压缩系数 —— <b>返回的是一个缩放比而非距离</b>：
     * 把它乘到"相对相机的偏移"上，就能把极远的东西拉进可见范围。
     *
     * <p>太空尺度下（几十万到几百万格）线性投影早就过了 far plane，
     * 但流星尾迹又必须可见。这里的做法是：{@code ≤ n} 保持不变，
     * 之后再按指数律趋近 {@code f}，于是"无限远"被压进 {@code [n, f]}。
     * {@code f} 取当前 far plane 的一半，保证压缩后的位置仍在裁剪范围内。</p>
     */
    public double getRenderSize(double x, double y, double z, Vector3d cameraPos, Quaterniond spaceRotation) {
        Vector3d CToBPos = new Vector3d(x, y, z).sub(cameraPos).rotate(spaceRotation);
        double length = CToBPos.length();
        double compression_length = PositionCompression(length, 10000.0,
                (double) Minecraft.getInstance().gameRenderer.getDepthFar() * 0.5);
        return compression_length / length;
    }

    /** {@code x ≤ n} 时原样返回；否则 {@code f − (f−n)·e^(−(x−n)/(f−n))}（单调、有界于 f）。 */
    private static double PositionCompression(double x, double n, double f) {
        return x <= n ? x : f - (f - n) * Math.exp(-(x - n) / (f - n));
    }
}
