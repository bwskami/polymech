package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceWorld;
import com.mss.polymech.techtree.Polyhedron;

import java.util.ArrayList;
import java.util.List;

/**
 * 从不同数据源构建 {@link PlanetRenderObject}。
 *
 * <p>本类不再为每颗星球写 switch，也不直接给每颗星球配颜色；它只做数据适配：
 * 从 {@link SpacePlanetCatalog} 取天体对象，调用基类的 {@link SpacePlanet#createPlanet}。
 * 这样新增/调整星球时只改星球子类，渲染工厂保持稳定。</p>
 */
public final class PlanetRenderObjectFactory {

    private static final List<PlanetRenderObject> BODIES = createBodies();

    private PlanetRenderObjectFactory() {
    }

    public static List<PlanetRenderObject> bodies() {
        return BODIES;
    }

    public static PlanetRenderObject fromRealAstroData(RealAstroData data) {
        return fromRealAstroData(data, null);
    }

    /**
     * @param ignoredVisual 保留旧签名兼容；当前天体视觉由 {@link SpacePlanet#visual()} 统一提供。
     */
    public static PlanetRenderObject fromRealAstroData(RealAstroData data, PlanetVisual ignoredVisual) {
        SpacePlanet body = SpacePlanetCatalog.byId(data.id());
        // 游戏宇宙坐标（保向压缩）
        double[] pos = SpaceWorld.gamePos(data);
        Planet planet = body.createPlanet(Polyhedron.goldberg(body.meshSubdivision()), (float) data.radiusMeters());
        return new PlanetRenderObject(planet, data.radiusMeters(), atmosphereRadius(data, body),
                castersFor(data), pos[0], pos[1], pos[2]);
    }

    public static PlanetRenderObject fromVisual(double radius, double posX, double posY, double posZ,
                                                PlanetVisual visual) {
        return new PlanetRenderObject(visual, radius, posX, posY, posZ);
    }

    private static List<PlanetRenderObject> createBodies() {
        List<PlanetRenderObject> list = new ArrayList<>();
        for (RealAstroData data : RealAstroData.BODIES) {
            list.add(fromRealAstroData(data));
        }
        return List.copyOf(list);
    }

    /**
     * 阴影投射者（数据驱动）：绕我转的卫星（它们能遮挡太阳→日食），
     * 以及我绕转的母星（它能遮挡太阳→月食/卫星食）。
     * uniform 槽位固定 4 个，applyCasterUniforms 会截断。
     */
    private static List<RealAstroData> castersFor(RealAstroData data) {
        List<RealAstroData> res = new ArrayList<>();
        for (RealAstroData b : RealAstroData.BODIES) {
            if (data == RealAstroData.parentOf(b)) res.add(b);
        }
        RealAstroData parent = RealAstroData.parentOf(data);
        if (parent != null) res.add(parent);
        return res;
    }

    /** 视觉大气外半径：真实大气很薄，具体比例由星球子类提供。 */
    private static double atmosphereRadius(RealAstroData data, SpacePlanet body) {
        if (!body.visual().hasAtmosphere() || body.atmosphereRatio() <= 1.0f) return 0;
        return data.radiusMeters() * body.atmosphereRatio();
    }
}
