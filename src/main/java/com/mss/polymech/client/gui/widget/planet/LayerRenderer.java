package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;
import org.joml.Matrix4f;

/**
 * 星球图层渲染器接口。
 * <p>
 * 每种 {@link PlanetLayerType} 对应一个 {@code LayerRenderer} 实现。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>新增图层类型只需实现此接口 + 注册，无需修改 {@code drawLayer()} 方法。</li>
 *   <li>渲染器不持有状态，所有数据通过参数传入（无副作用）。</li>
 *   <li>扩展方可以为自定义图层类型实现特殊渲染效果。</li>
 * </ul>
 *
 * @see PlanetLayerType
 * @see RenderContext
 */
@FunctionalInterface
public interface LayerRenderer {

    /**
     * 渲染一个图层。
     *
     * @param ctx      渲染上下文（矩阵、投影参数、时间等）
     * @param planet   所属星球
     * @param layer    当前图层
     * @param mesh     该图层使用的几何体
     * @param pi       行星索引（在 SolarSystem 中的位置）
     * @param sc       自转角 cos
     * @param ss       自转角 sin
     * @param dwx      世界X偏移（星球位置 - 焦点位置）
     * @param dwz      世界Z偏移
     * @param cam      相机控制器
     */
    void render(RenderContext ctx, Planet planet, PlanetLayer layer, Polyhedron mesh,
                int pi, float sc, float ss, float dwx, float dwz, CameraController cam);
}
