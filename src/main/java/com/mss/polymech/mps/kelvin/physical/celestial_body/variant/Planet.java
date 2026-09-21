package com.mss.polymech.mps.kelvin.physical.celestial_body.variant;

import com.mss.polymech.mps.kelvin.physical.KelvinConstants;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 行星 —— <b>与 {@code org.cn_grass_block.kelvin.physical.celestial_body.variant.Planet}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 在 {@link CelestialBody} 之上承载**大气与地表渲染参数**：大气分层高度、散射/吸收波长、
 * 温度、摩尔质量、海平面密度、卡门线高度、云层与光环，以及地表/法线/流体/云/环的贴图路径。
 *
 * <h2>两处真公式（照 space 0.1.3，别改）</h2>
 * <ul>
 *   <li><b>标高（scale height）</b>：{@code H = R_gas·T / (M·g)}，其中
 *       {@code g = G·M_planet/R_planet²}。这是大气密度随高度指数衰减的特征长度
 *       （{@code ρ = ρ0·e^(−h/H)}）—— 用温度与摩尔质量而不是写死一个高度，
 *       所以"换个大气成分/温度"密度剖面会自动跟着变。</li>
 *   <li><b>压强</b>：{@code p = ρ0·g·H·e^(−h/H)}（静力平衡下对密度积分的结果）。
 *       {@code g} 用行星表面重力而不是随高度变 —— 在标高尺度内这是个很好的近似，
 *       也是 space 的选择。</li>
 * </ul>
 * <b>防御</b>：摩尔质量/质量/半径 &le; 0 或标高非正/NaN 时压强直接返回 0，
 * 而不是让 {@code exp} 吐出 NaN 污染整条链路（太空里处处是"参数没配全"的天体）。
 *
 * <p><b>与 MPS 的唯一差异</b>：{@code g} 里的引力常数取本项目的
 * {@link KelvinConstants#GRAVITATIONAL_CONSTANT}（space 那边读它的配置项，默认同值）。</p>
 */
public class Planet extends CelestialBody {

    private double atmosphericHeight = -1.0;
    private double atmosphericRayHeight = -1.0;
    private double atmosphericMieHeight = -1.0;
    private double atmosphericObsorptionHeight = -1.0;
    private double atmosphericDensityFalloOff = 7.0;
    private double atmosphericG = 0.7;
    private final Vector3d atmosphericWLRay = new Vector3d(700.0, 530.0, 440.0);
    private final Vector3d atmosphericWLMie = new Vector3d(2500.0);
    private final Vector3d atmosphericWLObsorption = new Vector3d(7000.0, 5650.0, 2200.0);
    private double atmosphericTemperature = 0.0;
    private double atmosphericMolarMass = 0.0;
    private double atmosphericSeaLevelDensity = 0.0;
    private double carmenLineHeight = 0.0;
    private double cloudHeight = 0.0;
    private double ringInsideHeight = 0.0;
    private double ringOutsideHeight = 0.0;
    private final Quaterniond ringRotate = new Quaterniond();
    private ResourceLocation planetSurface;
    private ResourceLocation planetSurfaceNight;
    private ResourceLocation planetNormal;
    private ResourceLocation planetFluid;
    private ResourceLocation planetCloud;
    private ResourceLocation planetRing;

    public Planet(String level, String name, Vector3d pos, Quaterniond rotate, double radius,
                  ResourceLocation planet_surface) {
        super(level, name, pos, rotate, radius);
        this.planetSurface = planet_surface;
    }

    // ==================== 大气公式 ====================

    /** 某点处的大气密度（按"到行星中心距离 − 半径"算高度）。 */
    public double getAtmosphericDensity(Vector3d vector3d) {
        double altitude = vector3d.distance(this.getPos()) - this.getRadius();
        return this.getAtmosphericDensity(altitude);
    }

    /** 给定高度的大气密度：{@code ρ0·e^(−h/H)}，标高由温度/摩尔质量/表面重力算出。 */
    public double getAtmosphericDensity(double high) {
        double scaleHeight = 8.314
                * this.getAtmosphericTemperature()
                / (this.getAtmosphericMolarMass()
                * (KelvinConstants.GRAVITATIONAL_CONSTANT * this.getMass()
                / (this.getRadius() * this.getRadius())));
        return this.getAtmosphericSeaLevelDensity() * Math.exp(-high / scaleHeight);
    }

    public double getAtmosphericPressure(Vector3d vector3d) {
        double altitude = vector3d.distance(this.getPos()) - this.getRadius();
        return this.getAtmosphericPressure(altitude);
    }

    /** 给定高度的大气压强；参数不全或标高非正时返回 0（不让 NaN 扩散）。 */
    public double getAtmosphericPressure(double high) {
        if (this.getAtmosphericMolarMass() <= 0.0 || this.getMass() <= 0.0 || this.getRadius() <= 0.0) {
            return 0.0;
        }
        double g = KelvinConstants.GRAVITATIONAL_CONSTANT * this.getMass()
                / (this.getRadius() * this.getRadius());
        double scaleHeight = 8.314 * this.getAtmosphericTemperature() / (this.getAtmosphericMolarMass() * g);
        if (scaleHeight <= 0.0 || Double.isNaN(scaleHeight)) {
            return 0.0;
        }
        return this.getAtmosphericSeaLevelDensity() * g * scaleHeight * Math.exp(-high / scaleHeight);
    }

    // ==================== 序列化（网络） ====================

    @Override
    public void encode(FriendlyByteBuf buffer) {
        super.encode(buffer);
        buffer.writeDouble(this.getMass());
        buffer.writeDouble(this.atmosphericHeight);
        buffer.writeDouble(this.atmosphericRayHeight);
        buffer.writeDouble(this.atmosphericMieHeight);
        buffer.writeDouble(this.atmosphericObsorptionHeight);
        buffer.writeDouble(this.atmosphericDensityFalloOff);
        buffer.writeDouble(this.atmosphericG);
        buffer.writeDouble(this.atmosphericWLRay.x());
        buffer.writeDouble(this.atmosphericWLRay.y());
        buffer.writeDouble(this.atmosphericWLRay.z());
        buffer.writeDouble(this.atmosphericWLMie.x());
        buffer.writeDouble(this.atmosphericWLMie.y());
        buffer.writeDouble(this.atmosphericWLMie.z());
        buffer.writeDouble(this.atmosphericWLObsorption.x());
        buffer.writeDouble(this.atmosphericWLObsorption.y());
        buffer.writeDouble(this.atmosphericWLObsorption.z());
        buffer.writeDouble(this.atmosphericTemperature);
        buffer.writeDouble(this.atmosphericMolarMass);
        buffer.writeDouble(this.atmosphericSeaLevelDensity);
        buffer.writeDouble(this.carmenLineHeight);
        buffer.writeDouble(this.cloudHeight);
        buffer.writeDouble(this.ringInsideHeight);
        buffer.writeDouble(this.ringOutsideHeight);
        buffer.writeDouble(this.ringRotate.x());
        buffer.writeDouble(this.ringRotate.y());
        buffer.writeDouble(this.ringRotate.z());
        buffer.writeDouble(this.ringRotate.w());
        writeNullableResource(buffer, this.planetSurface);
        writeNullableResource(buffer, this.planetSurfaceNight);
        writeNullableResource(buffer, this.planetNormal);
        writeNullableResource(buffer, this.planetFluid);
        writeNullableResource(buffer, this.planetCloud);
        writeNullableResource(buffer, this.planetRing);
    }

    public static Planet decode(FriendlyByteBuf buffer) {
        CelestialBody base = CelestialBody.decode(buffer);
        double mass = buffer.readDouble();
        double atmosphericHeight = buffer.readDouble();
        double atmosphericRayHeight = buffer.readDouble();
        double atmosphericMieHeight = buffer.readDouble();
        double atmosphericObsorptionHeight = buffer.readDouble();
        double atmosphericDensityFalloOff = buffer.readDouble();
        double atmosphericG = buffer.readDouble();
        Vector3d wlRay = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Vector3d wlMie = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Vector3d wlObsorption = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        double atmosphericTemperature = buffer.readDouble();
        double atmosphericMolarMass = buffer.readDouble();
        double atmosphericSeaLevelDensity = buffer.readDouble();
        double carmenLineHeight = buffer.readDouble();
        double cloudHeight = buffer.readDouble();
        double ringInsideHeight = buffer.readDouble();
        double ringOutsideHeight = buffer.readDouble();
        Quaterniond ringRotate = new Quaterniond(buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
        ResourceLocation planetSurface = readNullableResource(buffer);
        ResourceLocation planetSurfaceNight = readNullableResource(buffer);
        ResourceLocation planetNormal = readNullableResource(buffer);
        ResourceLocation planetFluid = readNullableResource(buffer);
        ResourceLocation planetCloud = readNullableResource(buffer);
        ResourceLocation planetRing = readNullableResource(buffer);

        Planet planet = new Planet(base.level, base.getName(), base.getPos(), base.getRotate(),
                base.getRadius(), planetSurface);
        planet.setMass(base.getMass());
        planet.setAtmosphericHeight(atmosphericHeight);
        planet.setAtmosphericRayHeight(atmosphericRayHeight);
        planet.setAtmosphericMieHeight(atmosphericMieHeight);
        planet.setAtmosphericObsorptionHeight(atmosphericObsorptionHeight);
        planet.setAtmosphericDensityFalloOff(atmosphericDensityFalloOff);
        planet.setAtmosphericG(atmosphericG);
        planet.setAtmosphericWLRay(wlRay);
        planet.setAtmosphericWLMie(wlMie);
        planet.setAtmosphericWLObsorption(wlObsorption);
        planet.setAtmosphericTemperature(atmosphericTemperature);
        planet.setAtmosphericMolarMass(atmosphericMolarMass);
        planet.setAtmosphericSeaLevelDensity(atmosphericSeaLevelDensity);
        planet.setCarmenLineHeight(carmenLineHeight);
        planet.setCloudHeight(cloudHeight);
        planet.setRingInsideHeight(ringInsideHeight);
        planet.setRingOutsideHeight(ringOutsideHeight);
        planet.getRingRotate().set(ringRotate);
        planet.setPlanetSurface(planetSurface);
        planet.setPlanetSurfaceNight(planetSurfaceNight);
        planet.setPlanetNormal(planetNormal);
        planet.setPlanetFluid(planetFluid);
        planet.setPlanetCloud(planetCloud);
        planet.setPlanetRing(planetRing);
        return planet;
    }

    /** 贴图路径可为空（无云/无环的行星），用 boolean 前缀表示"有没有"。 */
    private void writeNullableResource(FriendlyByteBuf buffer, ResourceLocation rl) {
        if (rl == null) {
            buffer.writeBoolean(false);
        } else {
            buffer.writeBoolean(true);
            buffer.writeResourceLocation(rl);
        }
    }

    private static ResourceLocation readNullableResource(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readResourceLocation() : null;
    }

    // ==================== 访问器 ====================

    public void setAtmosphericWLRay(Vector3d atmospheric_wl_ray) {
        this.atmosphericWLRay.set(atmospheric_wl_ray);
    }

    public void setAtmosphericWLMie(Vector3d atmospheric_wl_mie) {
        this.atmosphericWLMie.set(atmospheric_wl_mie);
    }

    public void setAtmosphericWLObsorption(Vector3d atmospheric_wl_obsorption) {
        this.atmosphericWLObsorption.set(atmospheric_wl_obsorption);
    }

    public void setRingRotate(Quaterniond ring_rotate) {
        this.ringRotate.set(ring_rotate);
    }

    public void setAtmosphericHeight(double atmosphericHeight) {
        this.atmosphericHeight = atmosphericHeight;
    }

    public double getAtmosphericHeight() {
        return this.atmosphericHeight;
    }

    public void setAtmosphericRayHeight(double atmosphericRayHeight) {
        this.atmosphericRayHeight = atmosphericRayHeight;
    }

    public double getAtmosphericRayHeight() {
        return this.atmosphericRayHeight;
    }

    public void setAtmosphericMieHeight(double atmosphericMieHeight) {
        this.atmosphericMieHeight = atmosphericMieHeight;
    }

    public double getAtmosphericMieHeight() {
        return this.atmosphericMieHeight;
    }

    public void setAtmosphericObsorptionHeight(double atmosphericObsorptionHeight) {
        this.atmosphericObsorptionHeight = atmosphericObsorptionHeight;
    }

    public double getAtmosphericObsorptionHeight() {
        return this.atmosphericObsorptionHeight;
    }

    public void setAtmosphericDensityFalloOff(double atmosphericDensityFalloOff) {
        this.atmosphericDensityFalloOff = atmosphericDensityFalloOff;
    }

    public double getAtmosphericDensityFalloOff() {
        return this.atmosphericDensityFalloOff;
    }

    public void setAtmosphericG(double atmosphericG) {
        this.atmosphericG = atmosphericG;
    }

    public double getAtmosphericG() {
        return this.atmosphericG;
    }

    public Vector3d getAtmosphericWLRay() {
        return this.atmosphericWLRay;
    }

    public Vector3d getAtmosphericWLMie() {
        return this.atmosphericWLMie;
    }

    public Vector3d getAtmosphericWLObsorption() {
        return this.atmosphericWLObsorption;
    }

    public void setAtmosphericTemperature(double atmosphericTemperature) {
        this.atmosphericTemperature = atmosphericTemperature;
    }

    public double getAtmosphericTemperature() {
        return this.atmosphericTemperature;
    }

    public void setAtmosphericMolarMass(double atmosphericMolarMass) {
        this.atmosphericMolarMass = atmosphericMolarMass;
    }

    public double getAtmosphericMolarMass() {
        return this.atmosphericMolarMass;
    }

    public void setAtmosphericSeaLevelDensity(double atmosphericSeaLevelDensity) {
        this.atmosphericSeaLevelDensity = atmosphericSeaLevelDensity;
    }

    public double getAtmosphericSeaLevelDensity() {
        return this.atmosphericSeaLevelDensity;
    }

    public void setCarmenLineHeight(double carmenLineHeight) {
        this.carmenLineHeight = carmenLineHeight;
    }

    public double getCarmenLineHeight() {
        return this.carmenLineHeight;
    }

    public void setCloudHeight(double cloudHeight) {
        this.cloudHeight = cloudHeight;
    }

    public double getCloudHeight() {
        return this.cloudHeight;
    }

    public void setRingInsideHeight(double ringInsideHeight) {
        this.ringInsideHeight = ringInsideHeight;
    }

    public double getRingInsideHeight() {
        return this.ringInsideHeight;
    }

    public void setRingOutsideHeight(double ringOutsideHeight) {
        this.ringOutsideHeight = ringOutsideHeight;
    }

    public double getRingOutsideHeight() {
        return this.ringOutsideHeight;
    }

    public Quaterniond getRingRotate() {
        return this.ringRotate;
    }

    public void setPlanetSurface(ResourceLocation planetSurface) {
        this.planetSurface = planetSurface;
    }

    public ResourceLocation getPlanetSurface() {
        return this.planetSurface;
    }

    public void setPlanetSurfaceNight(ResourceLocation planetSurfaceNight) {
        this.planetSurfaceNight = planetSurfaceNight;
    }

    public ResourceLocation getPlanetSurfaceNight() {
        return this.planetSurfaceNight;
    }

    public void setPlanetNormal(ResourceLocation planetNormal) {
        this.planetNormal = planetNormal;
    }

    public ResourceLocation getPlanetNormal() {
        return this.planetNormal;
    }

    public void setPlanetFluid(ResourceLocation planetFluid) {
        this.planetFluid = planetFluid;
    }

    public ResourceLocation getPlanetFluid() {
        return this.planetFluid;
    }

    public void setPlanetCloud(ResourceLocation planetCloud) {
        this.planetCloud = planetCloud;
    }

    public ResourceLocation getPlanetCloud() {
        return this.planetCloud;
    }

    public void setPlanetRing(ResourceLocation planetRing) {
        this.planetRing = planetRing;
    }

    public ResourceLocation getPlanetRing() {
        return this.planetRing;
    }
}
