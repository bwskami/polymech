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
     * 每帧把全部渲染对象的位置刷新为<b>当前权威位置</b>
     * （{@link SpaceWorld#gamePos}，米）—— 由 {@code SpaceRenderer} 在每帧渲染前调用一次。
     *
     * <h2>为什么必须有这一步</h2>
     * 渲染对象的位置是<b>构造时快照</b>的，而 {@code SpaceWorld.gamePos} 在启用 kelvin 权威后
     * 会随时间变化。若不刷新，就会出现最糟的一种分脑：天体本体停在旧位置，
     * 而它的光照/阴影投射者（每帧读 {@code gamePos}）、GUI 星图、HUD 却按新位置算
     * —— 看起来像"影子从一颗星球投到空处"。
     *
     * <p>未启用 kelvin 权威时 {@code gamePos} 返回静态值，本方法是<b>等同赋值</b>，
     * 不产生任何行为差异。</p>
     */
    public static void refreshPositions() {
        refreshPositions(1.0f);
    }

    /**
     * 太空维度渲染用的<b>插值</b>版本（2026-09-25 新增）。
     *
     * <p>原来只走 {@link SpaceWorld#gamePos}（= {@code blockPos} → {@code getPos()}，物理步进的原始值），
     * <b>完全没有插值</b>：物理 20Hz / 渲染 60fps ⇒ 天体一个 tick 跳一次。
     * 地表那条路（{@link #refreshPositionsFromCelestial(float)}）一直是对的，太空这条一直是漏的，
     * 所以症状只在太空维度出现（用户 2026-09-25："太空维度里的星球移动还是不够流畅"）。
     * 天体速度是真实轨道速度 × 71.8 倍时间（地球每 tick 108.2 km），不插值就是肉眼可见的台阶。</p>
     *
     * @param partialTick 与本帧相机同一来源的插值系数（无参重载传 1.0 = 取当前位置）
     */
    public static void refreshPositions(float partialTick) {
        for (PlanetRenderObject object : BODIES) {
            RealAstroData data = RealAstroData.byId(object.planetName());
            if (data == null) {
                continue;
            }
            double[] pos = SpaceWorld.blockPos(data, partialTick);
            object.updatePosition(pos[0], pos[1], pos[2]);
        }
    }

    /**
     * <b>地表维度专用</b>：用天体在宇宙系里的<b>真实三维位置</b>刷新，而不是 {@code gamePos}。
     *
     * <h2>为什么必须分开</h2>
     * {@code SpaceWorld.gamePos} 走 {@code staticGamePos}，它把日心天体的 <b>Y 强制压成 0</b>
     * （除地球外；卫星继承母星的 0）—— 那是太空维度的需要：那是玩家可飞的 MC 方块空间，
     * 真实 Y 跨度 ±1.6×10⁶ 格会把天体扔出维度高度。
     *
     * <p>但<b>地表天空</b>的相机是用 {@code CelestialWorld.getSpacePosFromWorldPos} 映射到
     * <b>宇宙系</b>的（带真实 Y）。天体若仍按 Y=0 画，就成了"相机有真实 Y、天体全在一个平面里"
     * ⇒ 天空里所有天体必然落成<b>一条线</b>（2026-09 实测，用户一眼看出）。
     *
     * <p>数据里本来就带真实黄纬（水星 3.79°、土星 −2.17°、冥王星 15.0°…），这里把它用回来。
     * 位置与相机<b>同源</b>（都来自 kelvin 天体），两者天然一致。</p>
     *
     * @param partialTick 与相机同一帧的插值系数（{@code getSmoothPos} 用）
     */
    public static void refreshPositionsFromCelestial(float partialTick) {
        for (PlanetRenderObject object : BODIES) {
            RealAstroData data = RealAstroData.byId(object.planetName());
            if (data == null) {
                continue;
            }
            // S2：渲染口径已收敛到 SpaceWorld.renderPos（客户端显示世界优先 + 服务端兜底 + 插值 +
            // 缺席退回方块口径），这里不再自建一套查找 —— 同一概念只留一份实现（§29.3 的教训）。
            double[] pos = SpaceWorld.renderPos(data, partialTick);
            object.updatePosition(pos[0], pos[1], pos[2]);
        }
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
