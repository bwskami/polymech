package com.mss.polymech.client.space;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderObject;
import com.mss.polymech.space.RealAstroData;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕空间着色器共用的 {@code CelestialBodyData} UBO。
 *
 * <p>std140 布局照抄 space mod 的 {@code CelestialBodyDataUBO}，与
 * {@code planet_atmosphere.fsh} / {@code star_bloom.fsh} 里的结构体声明逐字段对齐：
 * 恒星 16 个、行星 64 个、黑洞 16 个（黑洞目前只占位，count 恒为 0）。</p>
 *
 * <p>所有位置都写成「相机相对的真实坐标（米）」，相机位于原点，
 * 与 {@link SpaceRenderer} 使用的 {@code spaceProj}（near=1000m / far=1e13m）同一套坐标空间。
 * 恒星与行星的 {@code Pos} / {@code RealPos} 取值相同——本项目不做 space mod 的距离压缩。</p>
 */
public final class CelestialBodyDataBuffer {

    /** 与 GLSL 里 CelestialBodyData 块的实际大小匹配（16 + 1024 + 16 + 5120 + 16 + 768 = 6960）。 */
    private static final int UBO_SIZE = 8192;
    private static final int MAX_STARS = 16;
    private static final int MAX_PLANETS = 64;
    private static final int MAX_BLACKHOLES = 16;
    private static final int STAR_STRUCT_FLOATS = 16;
    private static final int PLANET_STRUCT_FLOATS = 20;
    private static final int BLACKHOLE_STRUCT_FLOATS = 12;

    private static final String BLOCK_NAME = "CelestialBodyData";

    private static CelestialBodyDataBuffer instance;

    private UniformBuffer uniformBuffer;
    private ByteBuffer staging;

    private CelestialBodyDataBuffer() {
    }

    /**
     * 全局唯一实例：恒星泛光与大气散射两条后处理链路共用同一份天体数据，
     * 每帧只需由 {@link SpaceRenderer} 调一次 {@link #update}。
     */
    public static CelestialBodyDataBuffer get() {
        if (instance == null) instance = new CelestialBodyDataBuffer();
        return instance;
    }

    /** 惰性创建 UBO；必须在 GL 上下文可用的线程调用。 */
    private void ensureCreated() {
        if (uniformBuffer == null) {
            uniformBuffer = new UniformBuffer(UBO_SIZE);
            staging = MemoryUtil.memAlloc(UBO_SIZE);
        }
    }

    /** 按本帧天体列表重写 UBO 内容。 */
    public void update(List<PlanetRenderObject> bodies,
                       double camRealX, double camRealY, double camRealZ) {
        ensureCreated();
        ByteBuffer buffer = staging;
        buffer.clear();

        // 收集恒星与行星，沿用 PlanetRenderObjectFactory 的同一批对象。
        List<PlanetRenderObject> stars = new ArrayList<>();
        List<PlanetRenderObject> planets = new ArrayList<>();
        for (PlanetRenderObject body : bodies) {
            if (body.visual().isGlowing()) {
                stars.add(body);
            } else {
                planets.add(body);
            }
        }

        // ===== StarCount + starlist =====
        int starCount = Math.min(stars.size(), MAX_STARS);
        buffer.putInt(starCount);
        buffer.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < MAX_STARS; i++) {
            if (i < starCount) {
                PlanetRenderObject star = stars.get(i);
                double starRelX = star.posX() - camRealX;
                double starRelY = star.posY() - camRealY;
                double starRelZ = star.posZ() - camRealZ;
                putVec3(buffer, starRelX, starRelY, starRelZ);
                putVec3(buffer, starRelX, starRelY, starRelZ);
                float[] starColor = star.visual().baseColor();
                if (starColor == null) starColor = new float[]{1.0F, 1.0F, 1.0F};
                putVec4(buffer, starColor[0], starColor[1], starColor[2], 1.0F);
                buffer.putFloat((float) star.radius());
                putPaddingFloats(buffer, 3);
            } else {
                putStarPadding(buffer);
            }
        }

        // ===== PlanetCount + planetlist =====
        int planetCount = Math.min(planets.size(), MAX_PLANETS);
        buffer.putInt(planetCount);
        buffer.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < MAX_PLANETS; i++) {
            if (i < planetCount) {
                PlanetRenderObject planet = planets.get(i);
                RealAstroData data = RealAstroData.byId(planet.planetName());
                AtmoData atmo = AtmoData.forBody(data, planet);
                double relX = planet.posX() - camRealX;
                double relY = planet.posY() - camRealY;
                double relZ = planet.posZ() - camRealZ;
                buffer.putFloat((float) relX);
                buffer.putFloat((float) relY);
                buffer.putFloat((float) relZ);
                buffer.putFloat(atmo.gravity); // std140: vec3 Pos + float g 共 16 字节
                buffer.putFloat((float) relX);
                buffer.putFloat((float) relY);
                buffer.putFloat((float) relZ);
                buffer.putFloat((float) planet.radius()); // std140: vec3 RealPos + float R 共 16 字节
                buffer.putFloat((float) atmo.renderHeight);
                buffer.putFloat((float) atmo.realHeight);
                buffer.putFloat(atmo.temperature);
                buffer.putFloat(atmo.molarMass);
                buffer.putFloat(atmo.seaLevelDensity);
                putPaddingFloats(buffer, 3); // std140: vec4 AtmosphericColor 需要 16 字节对齐
                putVec4(buffer, atmo.color[0], atmo.color[1], atmo.color[2], 1.0F);
            } else {
                putPlanetPadding(buffer);
            }
        }

        // ===== BlackHoleCount + blackholelist =====
        buffer.putInt(0);
        buffer.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < MAX_BLACKHOLES; i++) {
            putBlackHolePadding(buffer);
        }

        if (buffer.position() > UBO_SIZE) {
            Polymech.LOGGER.error("[poly_mech] CelestialBodyData UBO overflow: {} > {}", buffer.position(), UBO_SIZE);
        }

        buffer.flip();
        uniformBuffer.update(buffer, false);
    }

    /** 把 UBO 绑到指定 GL 程序的 {@code CelestialBodyData} 块上。 */
    public void bindToShader(int programId) {
        if (uniformBuffer == null) return;
        uniformBuffer.bindToShader(programId, BLOCK_NAME);
    }

    public void close() {
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        if (uniformBuffer != null) {
            uniformBuffer.delete();
            uniformBuffer = null;
        }
    }

    private static void putVec3(ByteBuffer buffer, double x, double y, double z) {
        buffer.putFloat((float) x);
        buffer.putFloat((float) y);
        buffer.putFloat((float) z);
        buffer.putFloat(0.0F);
    }

    private static void putVec4(ByteBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(z);
        buffer.putFloat(w);
    }

    private static void putPaddingFloats(ByteBuffer buffer, int count) {
        for (int i = 0; i < count; i++) buffer.putFloat(0.0F);
    }

    private static void putStarPadding(ByteBuffer buffer) {
        putPaddingFloats(buffer, STAR_STRUCT_FLOATS);
    }

    private static void putPlanetPadding(ByteBuffer buffer) {
        putPaddingFloats(buffer, PLANET_STRUCT_FLOATS);
    }

    private static void putBlackHolePadding(ByteBuffer buffer) {
        putPaddingFloats(buffer, BLACKHOLE_STRUCT_FLOATS);
    }

    /** 大气参数照抄 space mod solar_system/object/*.json，渲染壳厚度与行星渲染对象一致。 */
    private record AtmoData(float gravity, double renderHeight, double realHeight,
                            float temperature, float molarMass, float seaLevelDensity,
                            float[] color) {
        static AtmoData forBody(RealAstroData body, PlanetRenderObject object) {
            float[] color = object.visual().atmosphereColor() != null
                    ? object.visual().atmosphereColor()
                    : new float[]{0.0F, 0.0F, 0.0F};
            if (body == null || !object.hasAtmosphere()) {
                return new AtmoData(0.0F, 0.0, 0.0, 0.0F, 0.0F, 0.0F, color);
            }
            double renderHeight = Math.max(0.0, object.atmosphereRadius() - object.radius());
            double realHeight = body.atmosphereHeightMeters();
            return switch (body.id()) {
                case "venus" -> new AtmoData(8.87F, renderHeight, realHeight, 737.0F, 0.04345F, 65.0F, color);
                case "earth" -> new AtmoData(9.807F, renderHeight, realHeight, 288.0F, 0.02896F, 1.225F, color);
                case "mars" -> new AtmoData(3.72F, renderHeight, realHeight, 210.0F, 0.04334F, 0.020F, color);
                case "jupiter" -> new AtmoData(24.79F, renderHeight, realHeight, 165.0F, 0.00222F, 0.16F, color);
                case "saturn" -> new AtmoData(10.44F, renderHeight, realHeight, 134.0F, 0.00207F, 0.19F, color);
                case "uranus" -> new AtmoData(8.69F, renderHeight, realHeight, 76.0F, 0.00264F, 0.42F, color);
                case "neptune" -> new AtmoData(11.15F, renderHeight, realHeight, 72.0F, 0.00253F, 0.45F, color);
                default -> new AtmoData(0.0F, renderHeight, realHeight, 0.0F, 0.0F, 0.0F, color);
            };
        }
    }
}
