package com.mss.polymech.mps.kelvin.physical.celestial_world;

import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;

/**
 * 天体世界（"某颗行星的地表维度"）—— <b>与
 * {@code org.cn_grass_block.kelvin.physical.celestial_world.CelestialWorld} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把一个<b>行星地表维度</b>和一颗 {@link Planet} 在天体世界里的位姿绑起来，
 * 并负责两套坐标之间的换算：<b>地表世界坐标 ⇄ 太空坐标</b>。
 *
 * <h2>为什么需要这一层（照 space 0.1.3）</h2>
 * 地表是一个普通 MC 维度（平的世界：x/z 是经纬、y 是高度），而行星在太空里是一个球。
 * 两者必须<b>双向</b>可换算，否则：地表的位置放不进太空（飞船找不到你在哪），
 * 太空的天体也落不到地表（着陆点算不出来）。换算规则集中在这一个类里，
 * 别处一律调它 —— 散开必错。
 *
 * <h2>三条关键约定（都别改）</h2>
 * <ol>
 *   <li><b>进太空时 x 取负</b>（{@code x = -x}）：地表与太空的手性不同，
 *       不减这个负号，行星会转反方向。</li>
 *   <li><b>经纬度是等距投影</b>：{@code latitude = x0/length·π/2}、
 *       {@code longitude = z0/length·π/2}，再拼成单位球向量乘半径 —— 等于把
 *       地表这块"方毯"贴到球上（能覆盖到极点，但高纬会挤）。</li>
 *   <li><b>高度沿卡门线归一</b>：{@code r = R·(1 + h_ratio·(carmenLine/R))}，
 *       即"地表 y 从 MinY 到 Height"线性映射到"半径 R 到 R+卡门线"。
 *       所以 {@link #Height}/{@link #MinY} 与行星的 {@code carmenLineHeight}
 *       必须一起看，改一个就要重新想另一个。</li>
 * </ol>
 *
 * <p>{@code pos_shadow}（中心 / 旋转 / 经度长度）是数据侧给的"地表贴图参数"：
 * 决定这块毯子贴到球面的哪一块、转多少。它被 {@link #getPosShadowData()} 以<b>副本</b>
 * 形式收发，避免调用方直接改到内部状态。</p>
 */
public class CelestialWorld {

    public final Planet celestialBody;
    public final ResourceLocation WorldID;
    /** 对应的太空维度 id（地表 → 太空的跳转目标）。 */
    public final ResourceLocation SpaceWorldID;
    private posShadowData pos_shadow;

    /** 行星表面重力（供 gravity 那条线读取）。 */
    public double G = 1.0;
    /** 地表世界的"顶"（y 上限，坐标换算用）。 */
    public double Height = 1024.0;
    /** 地表世界的"底"（y 下限）。 */
    public double MinY = 0.0;

    protected CelestialWorld(Planet celestialBody, ResourceLocation WorldID, ResourceLocation SpaceWorldID) {
        this.celestialBody = celestialBody;
        this.WorldID = WorldID;
        this.SpaceWorldID = SpaceWorldID;
        this.pos_shadow = new posShadowData(new Vector2d(), 0.0, 1024.0);
    }

    @Override
    public String toString() {
        return this.WorldID.toString();
    }

    @Override
    public int hashCode() {
        return this.WorldID.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CelestialWorld celestialWorld && celestialWorld.WorldID.equals(this.WorldID);
    }

    public void setPosShadowData(posShadowData pos_shadow) {
        this.pos_shadow = new posShadowData(pos_shadow);
    }

    /** 返回副本（内部状态不外卖）。 */
    public posShadowData getPosShadowData() {
        return new posShadowData(this.pos_shadow);
    }

    // ==================== 地表 → 太空 ====================

    public Vector3d getSpacePosFromWorldPos(Vec3 worldPos, float partialTick) {
        return this.getSpacePosFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    public Vector3d getSpacePosFromWorldPos(Vector3d worldPos, float partialTick) {
        return this.getSpacePosFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    public Vector3d getSpacePosFromWorldPos(double x, double y, double z, float partialTick) {
        x = -x; // 地表与太空手性不同（见类注释第 1 条）
        double dx = x - this.pos_shadow.center.x();
        double dz = z - this.pos_shadow.center.y();
        double cos = Math.cos(this.pos_shadow.rotate);
        double sin = Math.sin(this.pos_shadow.rotate);
        double x0 = dx * cos + dz * sin;
        double z0 = dx * sin + dz * cos;
        double latitude = x0 / this.pos_shadow.longitude_length * (Math.PI / 2);
        double longitude = z0 / this.pos_shadow.longitude_length * (Math.PI / 2);
        double heightRatio = (y - this.MinY) / (this.Height - this.MinY);
        double r = this.celestialBody.getRadius()
                * (1.0 + heightRatio * (this.celestialBody.getCarmenLineHeight() / this.celestialBody.getRadius()));
        Vector3d space = new Vector3d(
                Math.cos(latitude) * Math.cos(longitude),
                Math.sin(latitude),
                Math.cos(latitude) * Math.sin(longitude)).mul(r);
        this.celestialBody.getSmoothRotate(partialTick).invert().transform(space)
                .add(this.celestialBody.getSmoothPos(partialTick));
        return space;
    }

    // ==================== 太空 → 地表 ====================

    public Vector3d getWorldPosFromSpacePos(Vec3 spacePos, float partialTick) {
        return this.getWorldPosFromSpacePos(spacePos.x(), spacePos.y(), spacePos.z(), partialTick);
    }

    public Vector3d getWorldPosFromSpacePos(Vector3d spacePos, float partialTick) {
        return this.getWorldPosFromSpacePos(spacePos.x(), spacePos.y(), spacePos.z(), partialTick);
    }

    public Vector3d getWorldPosFromSpacePos(double x, double y, double z, float partialTick) {
        Vector3d relative = new Vector3d(x, y, z)
                .sub(this.celestialBody.getSmoothPos(partialTick))
                .rotate(new Quaterniond(this.celestialBody.getSmoothRotate(partialTick)));
        double r = relative.length();
        Vector3d normal = new Vector3d(relative).normalize();
        double latitude = Math.asin(normal.y);
        double longitude = Math.atan2(normal.z, normal.x);
        double x0 = latitude / (Math.PI / 2) * this.pos_shadow.longitude_length;
        double z0 = longitude / (Math.PI / 2) * this.pos_shadow.longitude_length;
        double cos = Math.cos(this.pos_shadow.rotate);
        double sin = Math.sin(this.pos_shadow.rotate);
        return new Vector3d(
                x0 * cos - z0 * sin + this.pos_shadow.center.x(),
                this.MinY + (r - this.celestialBody.getRadius())
                        / this.celestialBody.getCarmenLineHeight() * this.Height,
                x0 * sin + z0 * cos + this.pos_shadow.center.y());
    }

    // ==================== 姿态 ====================

    public Quaterniond getRotateFromWorldPos(Vec3 worldPos, float partialTick) {
        return this.getRotateFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    public Quaterniond getRotateFromWorldPos(Vector3d worldPos, float partialTick) {
        return this.getRotateFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    /**
     * 地表某点对应的"站姿"：先算该点在太空里的方向，再把局部 +Y 转到该方向、最后取逆
     * —— 即<b>让人的"上"指向天顶</b>，于是站在行星背面时不会头朝下。
     */
    public Quaterniond getRotateFromWorldPos(double x, double y, double z, float partialTick) {
        Vector3d spacePos = this.getSpacePosFromWorldPos(x, y, z, partialTick)
                .sub(this.celestialBody.getSmoothPos(partialTick));
        return new Quaterniond(fromToQuaternion(new Vector3d(0.0, 1.0, 0.0), spacePos.normalize())).invert();
    }

    /** 构造"把 a 转到 b"的四元数（绕 a×b 旋转夹角）。 */
    private static Quaterniond fromToQuaternion(Vector3d a, Vector3d b) {
        Vector3d v1 = new Vector3d(a).normalize();
        Vector3d v2 = new Vector3d(b).normalize();
        return new Quaterniond().fromAxisAngleRad(v1.cross(v2, new Vector3d()).normalize(),
                Math.acos(v1.dot(v2)));
    }

    /**
     * 地表贴图参数：毯子中心（x,z）、整体旋转、经度方向的半长。
     *
     * <p>{@code longitude_length == 0} 视为未配 → 取默认 1024（避免除零）。</p>
     */
    public static class posShadowData {
        public final Vector2d center;
        public final double rotate;
        public final double longitude_length;

        public posShadowData(Vector2d center, double rotate, double longitude_length) {
            this.center = center;
            this.rotate = rotate;
            this.longitude_length = longitude_length == 0.0 ? 1024.0 : longitude_length;
        }

        public posShadowData(posShadowData posShadowData) {
            this.center = new Vector2d(posShadowData.center);
            this.rotate = posShadowData.rotate;
            this.longitude_length = posShadowData.longitude_length;
        }
    }
}
