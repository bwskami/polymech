package com.mss.polymech.space.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Arrays;

/**
 * 天体类型。决定默认行为（可否着陆、是否发光、星图渲染方式等）。
 * <p>
 * 数据驱动：JSON 中写 {@code "type": "gas_giant"} 这样的字符串。
 * </p>
 */
public enum CelestialBodyType {
    /** 恒星：发光体，不可着陆，太空中的主要热源/光源。 */
    STAR("star"),
    /** 岩质行星：可着陆。 */
    PLANET("planet"),
    /** 天然卫星：可着陆，轨道绕母星。 */
    MOON("moon"),
    /** 气态巨行星：无可着陆表面（仅航行层景观 / 大气捕获）。 */
    GAS_GIANT("gas_giant"),
    /** 矮行星：可着陆。 */
    DWARF_PLANET("dwarf_planet"),
    /** 黑洞：不可着陆，引力场（后续阶段）。 */
    BLACK_HOLE("black_hole"),
    /** 小行星/陨石：可着陆（后续阶段动态天体）。 */
    METEOROID("meteoroid");

    public static final Codec<CelestialBodyType> CODEC = Codec.STRING.comapFlatMap(
            CelestialBodyType::byId,
            CelestialBodyType::id);

    public static final StreamCodec<FriendlyByteBuf, CelestialBodyType> STREAM_CODEC =
            StreamCodec.of((buf, type) -> buf.writeUtf(type.id), buf -> byId(buf.readUtf()).getOrThrow());

    private final String id;

    CelestialBodyType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    private static DataResult<CelestialBodyType> byId(String id) {
        return Arrays.stream(values())
                .filter(type -> type.id.equals(id))
                .findFirst()
                .map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "未知天体类型: " + id));
    }
}
