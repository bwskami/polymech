package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.data.CelestialBody;
import com.mss.polymech.space.data.CelestialBodyType;
import com.mss.polymech.space.data.ModCelestialBodies;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 把 {@link RealAstroData} 的当前天体表导出为数据驱动的
 * {@code data/poly_mech/celestial_body/*.json}。
 *
 * <p>阶段目标：JSON 内容与硬编码值**逐位一致**，从而可以安全地把消费方
 * （PlanetDimensions / SpaceWorld / 渲染 / 命令）逐步切到注册表上，
 * 切换过程中游戏行为不变。</p>
 */
public class ModCelestialBodyProvider extends DatapackBuiltinEntriesProvider {

    private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
            .add(ModCelestialBodies.REGISTRY_KEY, ModCelestialBodyProvider::bootstrap);

    /** 气态巨行星（无可着陆表面，仅航行景观）。 */
    private static final Set<String> GAS_GIANTS = Set.of("jupiter", "saturn", "uranus", "neptune");

    public ModCelestialBodyProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, BUILDER, Set.of(Polymech.MOD_ID));
    }

    /**
     * {@link DatapackBuiltinEntriesProvider} 默认名固定为 "Registries"，
     * 同一个 DataGenerator 里注册两个会抛 {@code IllegalStateException: Duplicate provider}，
     * 因此必须给每个 provider 唯一名字。
     */
    @Override
    public String getName() {
        return "Polymech Celestial Bodies";
    }

    private static void bootstrap(BootstrapContext<CelestialBody> context) {
        for (RealAstroData body : RealAstroData.BODIES) {
            int index = RealAstroData.indexOf(body.id());
            ResourceLocation id = ModCelestialBodies.id(body.id());

            Optional<ResourceLocation> dimension = PlanetDimensions.isTeleportable(index)
                    ? Optional.of(PlanetDimensions.dimension(index).location())
                    : Optional.empty();
            RealAstroData parent = RealAstroData.parentOf(body);

            context.register(ModCelestialBodies.key(body.id()), new CelestialBody(
                    body.name(),
                    classify(body, parent),
                    body.radiusMeters(),
                    body.carmenLineHeightMeters(),
                    body.atmosphereHeightMeters(),
                    PlanetDimensions.gravity(index),
                    Optional.of(new Vec3(body.posX(), body.posY(), body.posZ())),
                    parent == null ? Optional.empty() : Optional.of(ModCelestialBodies.id(parent.id())),
                    dimension,
                    dimension.isPresent()));
        }
    }

    /** 由现有数据推断类型（后续可直接在 JSON 里写更精确的类型）。 */
    private static CelestialBodyType classify(RealAstroData body, RealAstroData parent) {
        if (body.bodyType() == RealAstroData.BodyType.STAR) {
            return CelestialBodyType.STAR;
        }
        if (GAS_GIANTS.contains(body.id())) {
            return CelestialBodyType.GAS_GIANT;
        }
        if (parent != null) {
            return CelestialBodyType.MOON;
        }
        if ("pluto".equals(body.id())) {
            return CelestialBodyType.DWARF_PLANET;
        }
        return CelestialBodyType.PLANET;
    }
}
