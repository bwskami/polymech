package com.mss.polymech.mps.kelvin.physical.celestial_body.variant;

import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 黑洞 —— <b>与 {@code org.cn_grass_block.kelvin.physical.celestial_body.variant.BlackHole}
 * 同形</b>的自有实现（clean-room）。
 *
 * <p>暂无额外行为的标记类：它的"特殊性"目前全靠数据侧配置（质量极大、半径极小）体现，
 * 行为与基类一致。保留独立类型是为了<b>数据包/存档里的 {@code class} 字段能区分</b>
 * （见 {@link CelestialBody#toJsonObject()} 写入的 {@code class}），
 * 将来要加"吸积/视界"之类的行为时也有落点。</p>
 */
public class BlackHole extends CelestialBody {

    public BlackHole(String level, String name, Vector3d pos, Quaterniond rotate, double radius) {
        super(level, name, pos, rotate, radius);
    }
}
