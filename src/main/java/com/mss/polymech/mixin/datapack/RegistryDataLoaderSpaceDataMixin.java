package com.mss.polymech.mixin.datapack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.space.util.manger.SpaceModDataPackManger;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@code space_data} 合成的维度 JSON 当作<b>真实数据包文件</b>交给注册表加载 ——
 * <b>与 {@code org.deep_space_studio.space.mixin.common.datapack.MixinRegistryDataLoader}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>注入点为什么是 {@code Map.entrySet()}</h2>
 * {@code RegistryDataLoader.loadContentsFromManager} 的实现是：
 * <pre>
 *   String s = Registries.elementsDirPath(registry.key());          // "dimension" / "dimension_type" / "worldgen/biome"
 *   FileToIdConverter converter = FileToIdConverter.json(s);
 *   for (Map.Entry&lt;ResourceLocation, Resource&gt; e : converter.listMatchingResources(resourceManager).entrySet()) { … }
 * </pre>
 * 「待加载的文件清单」就是那个 {@code Map}。所以<b>改 {@code entrySet()} 的返回值</b>
 * 等于"往清单里多加几个文件"，而 {@code FileToIdConverter.fileToId()}
 * 随后会把 {@code <ns>:<s>/<name>.json} 还原成注册表 id —— 这正是我们要的：
 * 合成资源的 key 必须写成 {@code <ns>:<目录>/<名字>.json}，其余一切交给原版通路。
 * <p>目录名 {@code s} 用 {@code @Local String s} 直接拿（该方法的局部变量表里
 * 只有这一个 String），于是同一个注入点自动覆盖三个注册表，
 * 不必为每个注册表各写一遍。</p>
 *
 * <h2>真实文件优先（这条性质让迁移可以分步走）</h2>
 * 每处添加前都先 {@code datas.stream().noneMatch(...)} 判断"数据包里是不是
 * 已经有这个文件"。有就<b>不合成</b>。所以本机制与手写 JSON 安全共存：
 * <b>手写的赢，合成的只补空缺</b>。
 *
 * <h2>三处照抄的细节</h2>
 * <ul>
 *   <li><b>原版三维度被排除</b>（{@code minecraft:overworld/the_nether/the_end}）：
 *       它们没有对应的 {@code space_data/.../world/*.json}，但保险起见显式跳过，
 *       免得将来有人手滑登记进去而覆盖原版维度。</li>
 *   <li><b>太空维度类型与生物群系是"单个固定 id"</b>（{@code <mod>:space}），
 *       不随 {@link SpaceModDataPackManger#SpaceLevels} 里有多少个太空维度变化 ——
 *       因为 {@code SpaceLevelDimension} 这份 JSON 里 {@code "type"} 和
 *       {@code "biome"} 都写死成该 id。</li>
 *   <li><b>行星地表维度类型与生物群系按"每个行星一套"合成</b>（id 与行星维度同名），
 *       由 {@code addNewCelestialLevelDimension} 填表。</li>
 * </ul>
 */
@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderSpaceDataMixin {

    /**
     * 太空维度类型 / 生物群系的固定 id（{@code <mod>:space}）——
     * 不随 {@link SpaceModDataPackManger#SpaceLevels} 里有多少个太空维度变化。
     */
    private static final ResourceLocation SPACE_LEVEL_ID =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "space");

    @ModifyExpressionValue(
            method = "loadContentsFromManager",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;"))
    private static Set<Map.Entry<ResourceLocation, Resource>> polymech$injectSpaceData(
            Set<Map.Entry<ResourceLocation, Resource>> original,
            @Local String registryDirPath) {
        // 复制一份再改：原始 Set 可能是不可变的，且不该就地污染
        Set<Map.Entry<ResourceLocation, Resource>> datas = new HashSet<>(original);
        switch (registryDirPath) {
            case "dimension":
                for (ResourceLocation worldId : SpaceModDataPackManger.SpaceLevels) {
                    if (datas.stream().noneMatch(entry -> entry.getKey().toString()
                            .equals(dataFilePath(worldId, "dimension")))) {
                        datas.add(Map.entry(
                                dataFileId(worldId, "dimension"),
                                SpaceModDataPackManger.getResource(SpaceModDataPackManger.SpaceLevelDimension)));
                    }
                }

                for (ResourceLocation worldId : SpaceModDataPackManger.CelestialLevelDimensions.keySet()) {
                    if (datas.stream().noneMatch(entry -> entry.getKey().toString()
                            .equals(dataFilePath(worldId, "dimension")))
                            && !isVanillaDimension(worldId)) {
                        datas.add(Map.entry(
                                dataFileId(worldId, "dimension"),
                                SpaceModDataPackManger.getResource(
                                        SpaceModDataPackManger.CelestialLevelDimensions.get(worldId))));
                    }
                }
                break;
            case "dimension_type":
                if (!SpaceModDataPackManger.SpaceLevels.isEmpty()
                        && datas.stream().noneMatch(entry -> entry.getKey().toString()
                        .equals(dataFilePath(SPACE_LEVEL_ID, "dimension_type")))) {
                    datas.add(Map.entry(
                            dataFileId(SPACE_LEVEL_ID, "dimension_type"),
                            SpaceModDataPackManger.getResource(SpaceModDataPackManger.SpaceLevelDimensionType)));
                }

                for (ResourceLocation worldId : SpaceModDataPackManger.CelestialLevelDimensionTypes.keySet()) {
                    if (datas.stream().noneMatch(entry -> entry.getKey().toString()
                            .equals(dataFilePath(worldId, "dimension_type")))
                            && !isVanillaDimension(worldId)) {
                        datas.add(Map.entry(
                                dataFileId(worldId, "dimension_type"),
                                SpaceModDataPackManger.getResource(
                                        SpaceModDataPackManger.CelestialLevelDimensionTypes.get(worldId))));
                    }
                }
                break;
            case "worldgen/biome":
                if (!SpaceModDataPackManger.SpaceLevels.isEmpty()
                        && datas.stream().noneMatch(entry -> entry.getKey().toString()
                        .equals(dataFilePath(SPACE_LEVEL_ID, "worldgen/biome")))) {
                    datas.add(Map.entry(
                            dataFileId(SPACE_LEVEL_ID, "worldgen/biome"),
                            SpaceModDataPackManger.getResource(SpaceModDataPackManger.SpaceLevelDimensionBiome)));
                }

                for (ResourceLocation worldId : SpaceModDataPackManger.CelestialLevelBiomes.keySet()) {
                    if (datas.stream().noneMatch(entry -> entry.getKey().toString()
                            .equals(dataFilePath(worldId, "worldgen/biome")))
                            && !isVanillaDimension(worldId)) {
                        datas.add(Map.entry(
                                dataFileId(worldId, "worldgen/biome"),
                                SpaceModDataPackManger.getResource(
                                        SpaceModDataPackManger.CelestialLevelBiomes.get(worldId))));
                    }
                }
                break;
            default:
                break;
        }

        return datas;
    }

    /** 原版三个维度不参与合成（见类注释）。 */
    private static boolean isVanillaDimension(ResourceLocation id) {
        return id.toString().equals("minecraft:overworld")
                || id.toString().equals("minecraft:the_end")
                || id.toString().equals("minecraft:the_nether");
    }

    /** {@code <ns>:<目录>/<名字>.json} —— 必须与 {@code FileToIdConverter} 期望的形式一致。 */
    private static ResourceLocation dataFileId(ResourceLocation id, String dir) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), dir + "/" + id.getPath() + ".json");
    }

    private static String dataFilePath(ResourceLocation id, String dir) {
        return dataFileId(id, dir).toString();
    }
}
