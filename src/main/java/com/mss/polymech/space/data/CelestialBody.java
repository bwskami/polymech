package com.mss.polymech.space.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * 天体数据（数据驱动注册表 {@code poly_mech:celestial_body} 的条目）。
 * <p>
 * 单位统一为米 / 秒（真实尺度）；游戏内坐标 = 真实坐标 / {@code SpaceWorld.ZOOM}。
 * 天体 id 即注册表键名（文件名），因此条目内不再重复存 id。
 * </p>
 *
 * <p>示例 JSON（{@code data/poly_mech/celestial_body/mars.json}）：</p>
 * <pre>{@code
 * {
 *   "name": "火星",
 *   "type": "planet",
 *   "radius_meters": 3389500.0,
 *   "carmen_line_height_meters": 80000.0,
 *   "atmosphere_height_meters": 8000.0,
 *   "gravity": 0.3793,
 *   "position_meters": [-1.87E11, -6.37E9, 8.37E10],
 *   "dimension": "poly_mech:mars",
 *   "landable": true
 * }
 * }</pre>
 *
 * @param name                      显示名（中文，用于命令/星图）
 * @param type                      天体类型
 * @param radiusMeters              平均半径（米）
 * @param carmenLineHeightMeters    卡门线高度（米）——太空与行星大气的分界，着陆捕获判定用
 * @param atmosphereHeightMeters    大气层总厚度（米），0 表示无大气
 * @param gravity                   表面重力（地球 = 1.0），驱动重力 mixin
 * @param positionMeters            J2000 基准坐标（米）；卫星为母星坐标 + 轨道偏移后的绝对坐标
 * @param parent                    母星 id（卫星才有）；为后续轨道演化保留
 * @param dimension                 本地维度 id（可着陆天体才有）
 * @param landable                  是否可着陆（恒星/气态巨行星为 false）
 */
public record CelestialBody(
        String name,
        CelestialBodyType type,
        double radiusMeters,
        double carmenLineHeightMeters,
        double atmosphereHeightMeters,
        double gravity,
        Optional<Vec3> positionMeters,
        Optional<ResourceLocation> parent,
        Optional<ResourceLocation> dimension,
        boolean landable) {

    public static final Codec<CelestialBody> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(CelestialBody::name),
            CelestialBodyType.CODEC.fieldOf("type").forGetter(CelestialBody::type),
            Codec.DOUBLE.fieldOf("radius_meters").forGetter(CelestialBody::radiusMeters),
            Codec.DOUBLE.optionalFieldOf("carmen_line_height_meters", 0.0)
                    .forGetter(CelestialBody::carmenLineHeightMeters),
            Codec.DOUBLE.optionalFieldOf("atmosphere_height_meters", 0.0)
                    .forGetter(CelestialBody::atmosphereHeightMeters),
            Codec.DOUBLE.optionalFieldOf("gravity", 1.0).forGetter(CelestialBody::gravity),
            Vec3.CODEC.optionalFieldOf("position_meters").forGetter(CelestialBody::positionMeters),
            ResourceLocation.CODEC.optionalFieldOf("parent").forGetter(CelestialBody::parent),
            ResourceLocation.CODEC.optionalFieldOf("dimension").forGetter(CelestialBody::dimension),
            Codec.BOOL.optionalFieldOf("landable", false).forGetter(CelestialBody::landable)
    ).apply(instance, CelestialBody::new));

    /** 网络同步用（datapack 注册表同步到客户端靠它）。 */
    public static final StreamCodec<FriendlyByteBuf, CelestialBody> STREAM_CODEC =
            StreamCodec.of(CelestialBody::encode, CelestialBody::decode);

    private static void encode(FriendlyByteBuf buf, CelestialBody body) {
        buf.writeUtf(body.name);
        CelestialBodyType.STREAM_CODEC.encode(buf, body.type);
        buf.writeDouble(body.radiusMeters);
        buf.writeDouble(body.carmenLineHeightMeters);
        buf.writeDouble(body.atmosphereHeightMeters);
        buf.writeDouble(body.gravity);
        buf.writeBoolean(body.positionMeters.isPresent());
        body.positionMeters.ifPresent(pos -> {
            buf.writeDouble(pos.x);
            buf.writeDouble(pos.y);
            buf.writeDouble(pos.z);
        });
        writeOptionalLocation(buf, body.parent);
        writeOptionalLocation(buf, body.dimension);
        buf.writeBoolean(body.landable);
    }

    private static CelestialBody decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        CelestialBodyType type = CelestialBodyType.STREAM_CODEC.decode(buf);
        double radius = buf.readDouble();
        double carmen = buf.readDouble();
        double atmosphere = buf.readDouble();
        double gravity = buf.readDouble();
        Optional<Vec3> position = buf.readBoolean()
                ? Optional.of(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()))
                : Optional.empty();
        Optional<ResourceLocation> parent = readOptionalLocation(buf);
        Optional<ResourceLocation> dimension = readOptionalLocation(buf);
        boolean landable = buf.readBoolean();
        return new CelestialBody(name, type, radius, carmen, atmosphere, gravity,
                position, parent, dimension, landable);
    }

    private static void writeOptionalLocation(FriendlyByteBuf buf, Optional<ResourceLocation> value) {
        buf.writeBoolean(value.isPresent());
        value.ifPresent(buf::writeResourceLocation);
    }

    private static Optional<ResourceLocation> readOptionalLocation(FriendlyByteBuf buf) {
        return buf.readBoolean() ? Optional.of(buf.readResourceLocation()) : Optional.empty();
    }
}
