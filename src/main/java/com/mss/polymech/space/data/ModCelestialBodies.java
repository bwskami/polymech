package com.mss.polymech.space.data;

import com.mss.polymech.Polymech;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 天体数据驱动注册表（{@code poly_mech:celestial_body}）。
 *
 * <p>这是 P2「基础框架」的基石：天体不再是 Java 硬编码常量，而是 datapack 数据。
 * 加一个星球 = 加一个 JSON（或改 datagen 里的一行），不需要动代码。</p>
 *
 * <ul>
 *   <li>数据路径：{@code data/<命名空间>/poly_mech/celestial_body/<id>.json}</li>
 *   <li>带 networkCodec 注册 → 服务端与客户端自动同步，客户端渲染可直接读</li>
 *   <li>服务端在 {@link DataPackRegistryEvent.NewRegistry} 时注册（mod 总线）</li>
 * </ul>
 */
public final class ModCelestialBodies {

    /** 注册表键：{@code poly_mech:celestial_body} */
    public static final ResourceKey<Registry<CelestialBody>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "celestial_body"));

    private ModCelestialBodies() {
    }

    /** mod 总线监听：注册为「可同步的 datapack 注册表」。 */
    public static void register(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(REGISTRY_KEY, CelestialBody.CODEC, CelestialBody.CODEC);
    }

    /** 本模组命名空间下的天体键。 */
    public static ResourceKey<CelestialBody> key(String id) {
        return ResourceKey.create(REGISTRY_KEY, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, id));
    }

    /** 本模组命名空间下的天体 id。 */
    public static ResourceLocation id(String id) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, id);
    }

    public static Optional<Registry<CelestialBody>> registry(RegistryAccess access) {
        return access.registry(REGISTRY_KEY);
    }

    /** 按 id 查天体；注册表缺失或条目不存在时返回空。 */
    public static Optional<CelestialBody> get(RegistryAccess access, ResourceLocation id) {
        return registry(access).flatMap(reg -> reg.getOptional(id));
    }

    /** 按路径名（本模组命名空间）查天体。 */
    public static Optional<CelestialBody> get(RegistryAccess access, String path) {
        return get(access, id(path));
    }

    /** 该注册表键对应的已注册天体键（供命令补全等）。 */
    public static List<String> ids(RegistryAccess access) {
        List<String> list = new ArrayList<>();
        Optional<Registry<CelestialBody>> registry = registry(access);
        if (registry.isPresent()) {
            for (ResourceLocation bodyKey : registry.get().keySet()) {
                list.add(bodyKey.getPath());
            }
        }
        return list;
    }

    /** 全部天体条目；注册表未加载时返回空列表。 */
    public static List<CelestialBody> all(RegistryAccess access) {
        return registry(access).map(Registry::stream).map(stream -> stream.toList()).orElse(List.of());
    }

    /** 条目数量（用于自检/日志）。 */
    public static int size(RegistryAccess access) {
        return registry(access).map(Registry::size).orElse(0);
    }
}
