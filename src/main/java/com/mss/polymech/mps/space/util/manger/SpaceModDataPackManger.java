package com.mss.polymech.mps.space.util.manger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.BlackHole;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Star;
import com.mss.polymech.mps.kelvin.physical.celestial_world.CelestialWorld;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackResources.ResourceOutput;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 太空数据包读取器 —— <b>与
 * {@code org.deep_space_studio.space.util.manger.SpaceModDataPackManger} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 从数据包目录 {@code <ns>:space_data/<世界名>/…} 读出整个宇宙的定义，并据此
 * <b>登记太空世界及其天体</b>。三类文件：
 * <pre>
 *   space_data/&lt;世界名&gt;/type.json              → 一个太空维度（ServerSpaceWorld）
 *   space_data/&lt;世界名&gt;/object/&lt;天体名&gt;.json    → 该宇宙里的恒星/行星/黑洞
 *   space_data/&lt;世界名&gt;/world/&lt;ns&gt;/&lt;名&gt;.json      → 某行星的地表维度参数（重力/贴图投影）
 * </pre>
 * {@code type.json} 被 {@link List#addFirst} <b>提前</b>处理：后面 object 里要用
 * {@code ServerSpaceWorld.getSpaceWorld(WorldID)} 往世界里塞天体，世界必须先存在。
 *
 * <h2>为什么维度是"运行时伪造 Resource"（照 space 0.1.3 的架构决定）</h2>
 * 天体是由数据包定义的，那么"某颗行星有没有地表维度"也只有读到数据后才知道 ——
 * 但 MC 的维度/维度类型/生物群系都是<b>注册表</b>，必须在世界加载时就齐备。
 * space 的解法分两步（见 {@code MixinWorldLoader} + {@code MixinRegistryDataLoader}）：
 * <ol>
 *   <li>{@code WorldLoader.load} 里、{@code RegistryLayer.createRegistryAccess()} 之前，
 *       调用本类的 {@link #readSpaceData}，把数据包读成内存里的 JSON 字符串，
 *       填进 {@link #SpaceLevels} / {@link #CelestialLevelDimensions} 等静态表；</li>
 *   <li>{@code RegistryDataLoader.loadContentsFromManager} 里，把这些 JSON 字符串
 *       <b>当作"真实的"数据包文件</b>塞进待加载资源集合（{@link #getResource} 包一个
 *       假 {@link PackResources}）。</li>
 * </ol>
 * 于是维度注册走的仍是<b>原版通路</b>，不需要任何自建维度注册表；代价是必须提供
 * 一个假的 {@code PackResources}。
 *
 * <p><b>关键性质：真实文件优先</b>。注入时每个 id 都先
 * {@code datas.stream().noneMatch(...)} 判断"数据包里是不是已经有这个文件"，
 * 有就<b>不合成</b>。所以本机制与手写 JSON 可以安全共存：手写的赢，
 * 合成的只补空缺。这条性质让迁移可以分步走。</p>
 *
 * <h2>与本项目的一处<b>必要</b>差异：命名空间</h2>
 * space 里 id 的命名空间是字面量 {@code "space"}。本项目沿用<b>本模组 id</b>
 * （{@link Polymech#MOD_ID}）—— 因为维度 id 必须和既有的
 * {@code data/poly_mech/dimension/*.json}、{@code PlanetDimensions} 保持一致，
 * 否则会出现"合成出来的是 {@code space:moon}、而别人引用 {@code poly_mech:moon}"
 * 的两套 id。<b>命名空间是名字，不是架构</b>，除命名空间外逻辑逐行同形。
 *
 * <h2>三处照抄的怪点（别"顺手修"）</h2>
 * <ul>
 *   <li>{@link #CelestialLevelNoiseSettings} 在 space 里<b>声明了但从未写入、从未读取</b>
 *       （噪声设置实际上复用维度 id 本身）。保留它只为公开面同形，别以为我们漏了。</li>
 *   <li>{@link #readJson} 里 {@code pos_list.size() == 4} 才解析 rotate、
 *       {@code pos_list.size() == 3} 才解析 speed —— 判定用的是<b>别的数组</b>的长度。
 *       这是 space 的原样 bug（写错了变量），影响仅限"格式异常的数据包"，
 *       照抄以免行为分叉。</li>
 *   <li>{@code world/*.json} 分支里 {@code MinY} 被<b>硬编码成 -64</b>，
 *       而 {@code Height} 从 JSON 读。两者共同决定地表 y → 半径的映射（见
 *       {@link CelestialWorld}），所以 min_y 必须与维度类型里的 {@code min_y} 对齐。</li>
 * </ul>
 */
public class SpaceModDataPackManger {

    /** 所有太空维度 id（{@code type.json} 里读出来的）。 */
    public static final Set<ResourceLocation> SpaceLevels = new HashSet<>();
    public static String SpaceLevelDimension = null;
    public static String SpaceLevelDimensionType = null;
    public static String SpaceLevelDimensionBiome = null;
    /** 行星地表维度 id → 合成的 dimension JSON。 */
    public static final Map<ResourceLocation, String> CelestialLevelDimensions = new HashMap<>();
    /** 行星地表维度 id → 合成的 dimension_type JSON。 */
    public static final Map<ResourceLocation, String> CelestialLevelDimensionTypes = new HashMap<>();
    /** 行星地表维度 id → 合成的 biome JSON。 */
    public static final Map<ResourceLocation, String> CelestialLevelBiomes = new HashMap<>();
    /** space 里声明后从未使用（见类注释），保留仅为公开面同形。 */
    public static final Map<ResourceLocation, String> CelestialLevelNoiseSettings = new HashMap<>();

    /**
     * 扫描 {@code space_data/**} 并登记宇宙。
     *
     * <p><b>每次都先清空两张服务端表</b>：数据包重载（{@code /reload}）时若不清，
     * 上一个数据包定义的天体会残留下来。注意客户端那两张表由各自的世界清理钩子负责。</p>
     */
    public static void readSpaceData(ResourceManager resourceManager) {
        ServerSpaceWorld.init();
        ServerCelestialWorld.init();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources("space_data", path -> true);
        List<ResourceLocation> resourcesList = new ArrayList<>();

        for (ResourceLocation resourceLocation : resources.keySet()) {
            String[] pathList = resourceLocation.getPath().split("/");
            if (pathList.length >= 3) {
                if (pathList[2].replace(".json", "").equals("type")) {
                    // type.json 必须最先处理：object 里要往已存在的世界里塞天体
                    resourcesList.addFirst(resourceLocation);
                } else {
                    resourcesList.add(resourceLocation);
                }
            }
        }

        for (ResourceLocation resourceLocation : resourcesList) {
            JsonObject jsonObject = loadJson(resourceManager, resourceLocation);
            if (jsonObject != null) {
                readJson(jsonObject, resourceLocation);
            }
        }

        // 诊断日志：本机制最大的失败模式是"**静默读到 0 个文件**"——
        // 路径写错、命名空间不对、数据包没打进去，症状都是"宇宙里什么都没有"，
        // 却没有任何报错（readJson 只处理它看得见的文件）。不报这一句就无从排查。
        int bodyCount = 0;
        for (ResourceLocation worldId : SpaceLevels) {
            SpaceWorld world = ServerSpaceWorld.getSpaceWorld(worldId);
            if (world != null) {
                bodyCount += world.getAllCelestialBody().size();
            }
        }
        Polymech.LOGGER.info("[Kelvin] space_data 读取完成：太空世界 {} 个，天体 {} 个，地表世界 {} 个",
                SpaceLevels.size(), bodyCount, CelestialLevelDimensions.size());
    }

    /** 按"目录第 2 段 = 世界名，第 3 段 = 文件种类"分派。 */
    public static void readJson(JsonObject jsonObject, ResourceLocation resourceLocation) {
        String path = resourceLocation.getPath().split("/")[1];
        ResourceLocation WorldID = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, path);
        String[] pathList = resourceLocation.getPath().split("/");
        String name = pathList[2].replace(".json", "");
        switch (name) {
            case "type": {
                String skyTexture = jsonObject.get("sky_texture").getAsString();
                ServerSpaceWorld.newSpaceWorld(WorldID, ResourceLocation.parse(skyTexture));
                addNewSpaceLevelDimension(WorldID);
                break;
            }
            case "object": {
                String object_name = pathList[3].replace(".json", "");
                Vector3d pos = new Vector3d();
                List<JsonElement> pos_list = jsonObject.getAsJsonArray("pos").asList();
                if (pos_list.size() == 3) {
                    pos = new Vector3d(pos_list.get(0).getAsDouble(), pos_list.get(1).getAsDouble(),
                            pos_list.get(2).getAsDouble());
                }

                Quaterniond rotate = new Quaterniond();
                List<JsonElement> rotate_list = jsonObject.getAsJsonArray("rotate").asList();
                // 原样保留：这里判定的是 pos_list 的长度（space 的笔误，见类注释）
                if (pos_list.size() == 4) {
                    rotate = new Quaterniond(rotate_list.get(0).getAsDouble(), rotate_list.get(1).getAsDouble(),
                            rotate_list.get(2).getAsDouble(), rotate_list.get(3).getAsDouble());
                }

                double radius = jsonObject.get("scale").getAsDouble();
                boolean compute = false;
                if (jsonObject.has("compute")) {
                    compute = jsonObject.get("compute").getAsBoolean();
                }

                Vector3d speed = new Vector3d();
                if (jsonObject.has("speed")) {
                    List<JsonElement> speed_list = jsonObject.getAsJsonArray("speed").asList();
                    // 同上：判定 pos_list 的长度
                    if (pos_list.size() == 3) {
                        speed = new Vector3d(speed_list.get(0).getAsDouble(), speed_list.get(1).getAsDouble(),
                                speed_list.get(2).getAsDouble());
                    }
                }

                double rotate_speed = 0.0;
                if (jsonObject.has("rotate_speed")) {
                    rotate_speed = jsonObject.get("rotate_speed").getAsDouble();
                }

                double mass = 0.0;
                if (jsonObject.has("mass")) {
                    mass = jsonObject.get("mass").getAsDouble();
                }

                String type = jsonObject.get("type").getAsString();
                if (type.equals("star")) {
                    double temperature = jsonObject.get("temperature").getAsDouble();
                    Star star = new Star(WorldID.toString(), object_name, pos, rotate, radius, temperature);
                    star.init(compute, speed, rotate_speed, mass);
                    ServerSpaceWorld serverSpaceWorld = ServerSpaceWorld.getSpaceWorld(WorldID);
                    if (serverSpaceWorld != null) {
                        serverSpaceWorld.putCelestialBody(star);
                    }
                } else if (type.equals("planet")) {
                    String texture = jsonObject.get("texture").getAsString();
                    Planet planet = new Planet(WorldID.toString(), object_name, pos, rotate, radius,
                            ResourceLocation.parse(texture));
                    planet.init(compute, speed, rotate_speed, mass);
                    planet.setCarmenLineHeight(jsonObject.get("carmen_line_height").getAsDouble());
                    if (jsonObject.has("night_texture")) {
                        planet.setPlanetSurfaceNight(ResourceLocation.parse(jsonObject.get("night_texture").getAsString()));
                    }

                    if (jsonObject.has("normal")) {
                        planet.setPlanetNormal(ResourceLocation.parse(jsonObject.get("normal").getAsString()));
                    }

                    if (jsonObject.has("fluid")) {
                        planet.setPlanetFluid(ResourceLocation.parse(jsonObject.get("fluid").getAsString()));
                    }

                    if (jsonObject.has("atmospheric")) {
                        JsonObject atmospheric_data = jsonObject.getAsJsonObject("atmospheric");
                        planet.setAtmosphericHeight(atmospheric_data.get("height").getAsDouble());
                        planet.setAtmosphericRayHeight(atmospheric_data.get("ray_height").getAsDouble());
                        planet.setAtmosphericMieHeight(atmospheric_data.get("mie_height").getAsDouble());
                        planet.setAtmosphericObsorptionHeight(atmospheric_data.get("obsorption_height").getAsDouble());
                        planet.setAtmosphericDensityFalloOff(atmospheric_data.get("density_fall_off").getAsDouble());
                        planet.setAtmosphericG(atmospheric_data.get("g").getAsDouble());
                        List<JsonElement> wl_ray_list = atmospheric_data.getAsJsonArray("wl_ray").asList();
                        if (wl_ray_list.size() == 3) {
                            planet.setAtmosphericWLRay(new Vector3d(wl_ray_list.get(0).getAsDouble(),
                                    wl_ray_list.get(1).getAsDouble(), wl_ray_list.get(2).getAsDouble()));
                        }

                        List<JsonElement> wl_mie_list = atmospheric_data.getAsJsonArray("wl_mie").asList();
                        if (wl_mie_list.size() == 3) {
                            planet.setAtmosphericWLMie(new Vector3d(wl_mie_list.get(0).getAsDouble(),
                                    wl_mie_list.get(1).getAsDouble(), wl_mie_list.get(2).getAsDouble()));
                        }

                        List<JsonElement> wl_obsorption_list = atmospheric_data.getAsJsonArray("wl_obsorption").asList();
                        if (wl_obsorption_list.size() == 3) {
                            planet.setAtmosphericWLObsorption(new Vector3d(wl_obsorption_list.get(0).getAsDouble(),
                                    wl_obsorption_list.get(1).getAsDouble(), wl_obsorption_list.get(2).getAsDouble()));
                        }

                        planet.setAtmosphericSeaLevelDensity(atmospheric_data.get("sea_level_density").getAsDouble());
                        planet.setAtmosphericTemperature(atmospheric_data.get("temperature").getAsDouble());
                        planet.setAtmosphericMolarMass(atmospheric_data.get("molar_mass").getAsDouble());
                    }

                    if (jsonObject.has("cloud")) {
                        JsonObject cloud_data = jsonObject.getAsJsonObject("cloud");
                        planet.setCloudHeight(cloud_data.get("height").getAsDouble());
                        planet.setPlanetCloud(ResourceLocation.parse(cloud_data.get("texture").getAsString()));
                    }

                    if (jsonObject.has("ring")) {
                        JsonObject ring_data = jsonObject.getAsJsonObject("ring");
                        planet.setRingInsideHeight(ring_data.get("ring_inside_height").getAsDouble());
                        planet.setRingOutsideHeight(ring_data.get("ring_outside_height").getAsDouble());
                        planet.setPlanetRing(ResourceLocation.parse(ring_data.get("texture").getAsString()));
                    }

                    ServerSpaceWorld serverSpaceWorld = ServerSpaceWorld.getSpaceWorld(WorldID);
                    if (serverSpaceWorld != null) {
                        serverSpaceWorld.putCelestialBody(planet);
                    }

                    if (jsonObject.has("dimension")) {
                        JsonObject dimension_data = jsonObject.getAsJsonObject("dimension");
                        String WorldId = dimension_data.get("id").getAsString();
                        // 第二参数 = 地表维度 id，第三参数 = 所属太空维度 id
                        ServerCelestialWorld.newCelestialWorld(planet, ResourceLocation.parse(WorldId),
                                ResourceLocation.parse(WorldID.toString()));
                        addNewCelestialLevelDimension(ResourceLocation.parse(WorldId));
                    }
                } else if (type.equals("black_hole")) {
                    BlackHole blackHoleServer = new BlackHole(WorldID.toString(), object_name, pos, rotate, radius);
                    blackHoleServer.init(compute, speed, rotate_speed, mass);
                    ServerSpaceWorld serverSpaceWorld = ServerSpaceWorld.getSpaceWorld(WorldID);
                    if (serverSpaceWorld != null) {
                        serverSpaceWorld.putCelestialBody(blackHoleServer);
                    }
                }
                break;
            }
            case "world": {
                // world/<ns>/<名>.json → 地表维度 <ns>:<名>
                String thisWorldID = pathList[3] + ":" + pathList[4].replace(".json", "");
                ServerCelestialWorld serverCelestialWorld = ServerCelestialWorld.getCelestialWorld(thisWorldID);
                if (serverCelestialWorld != null) {
                    serverCelestialWorld.G = jsonObject.get("gravity").getAsDouble();
                    serverCelestialWorld.Height = jsonObject.get("height").getAsDouble();
                    serverCelestialWorld.MinY = -64.0; // 硬编码，须与维度类型的 min_y 对齐（见类注释）
                    Vector2d pos = new Vector2d();
                    List<JsonElement> pos_list = jsonObject.getAsJsonArray("pos_shadow_center").asList();
                    if (pos_list.size() == 2) {
                        pos = new Vector2d(pos_list.get(0).getAsDouble(), pos_list.get(1).getAsDouble());
                    }

                    CelestialWorld.posShadowData posShadowData = new CelestialWorld.posShadowData(
                            pos, jsonObject.get("pos_shadow_rotate").getAsDouble(),
                            jsonObject.get("pos_shadow_longitude_length").getAsDouble());
                    serverCelestialWorld.setPosShadowData(posShadowData);
                }
            }
        }
    }

    /**
     * 读一个 JSON；坏文件返回 null（不让一个坏数据包炸掉整个加载）。
     *
     * <p><b>一处有意的加固差异</b>：space 只 catch {@code IOException}，
     * 于是"JSON 语法错"（{@code JsonSyntaxException}）会一路抛到世界加载、
     * 直接把游戏打崩。这里把 {@code RuntimeException} 也吞成 null ——
     * <b>只扩大"坏文件"的判定范围，不改变任何正常路径的行为</b>。
     * 这是移植中唯一主动放宽的一处，其余判定逻辑逐行同形。</p>
     */
    public static JsonObject loadJson(ResourceManager resourceManager, ResourceLocation loc) {
        try {
            JsonObject jsonObject;
            try (InputStream inputStream = resourceManager.getResource(loc).orElseThrow().open()) {
                jsonObject = JsonParser.parseReader(new InputStreamReader(inputStream)).getAsJsonObject();
            }
            return jsonObject;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void addNewSpaceLevelDimension(ResourceLocation WorldID) {
        SpaceLevels.add(WorldID);
    }

    /**
     * 为一个行星地表维度合成 dimension / dimension_type / biome 三份 JSON。
     *
     * <p>三份都用<b>同一个 id</b>（如 {@code poly_mech:moon}）：
     * dimension 的 {@code "type"} 指向 dimension_type 同 id，
     * {@code "settings"} 指向同 id 的 noise settings（后者仍由真实数据包提供），
     * biome_source 里也放同 id 的 biome。这是"一个行星一套命名"的取法。</p>
     */
    public static void addNewCelestialLevelDimension(ResourceLocation WorldID) {
        String dimension_json = """
                {
                  "type": "$world_id$",
                  "generator": {
                    "type": "minecraft:noise",
                    "settings": "$world_id$",
                    "biome_source": {
                      "type": "minecraft:multi_noise",
                      "biomes": [
                        {
                          "biome": "$world_id$",
                          "parameters": {
                            "temperature": 0,
                            "humidity": -1.5,
                            "continentalness": 1.7,
                            "erosion": 1.7,
                            "weirdness": -1.3,
                            "depth": 0,
                            "offset": 0
                          }
                        }
                      ]
                    }
                  }
                }
                """;
        String dimension_type_json = """
                {
                  "ambient_light": 0.0,
                  "bed_works": true,
                  "coordinate_scale": 1,
                  "has_ceiling": false,
                  "has_raids": false,
                  "has_skylight": true,
                  "effects": "minecraft:overworld",
                  "fixed_time": 6000,
                  "min_y": -64,
                  "height": 384,
                  "logical_height": 384,
                  "infiniburn": "#minecraft:infiniburn_overworld",
                  "monster_spawn_block_light_limit": 0,
                  "monster_spawn_light_level": 0,
                  "natural": true,
                  "piglin_safe": false,
                  "respawn_anchor_works": false,
                  "ultrawarm": false
                }
                """;
        String biome_json = """
                {
                  "carvers": {
                    "air": [
                      "minecraft:cave",
                      "minecraft:cave_extra_underground",
                      "minecraft:canyon"
                    ]
                  },
                  "downfall": 0.4,
                  "effects": {
                    "fog_color": 12638463,
                    "mood_sound": {
                      "block_search_extent": 8,
                      "offset": 2.0,
                      "sound": "minecraft:ambient.cave",
                      "tick_delay": 6000
                    },
                    "sky_color": 7907327,
                    "water_color": 4159204,
                    "water_fog_color": 329011
                  },
                  "features": [[], [], [], [], [], [], [], [], [], [], []],
                  "has_precipitation": true,
                  "spawn_costs": {},
                  "spawners": {
                    "ambient": [],
                    "axolotls": [],
                    "creature": [],
                    "misc": [],
                    "monster": [],
                    "underground_water_creature": [],
                    "water_ambient": [],
                    "water_creature": []
                  },
                  "temperature": 0.8
                }
                """;
        dimension_json = dimension_json.replace("$world_id$", WorldID.toString());
        CelestialLevelDimensions.put(WorldID, dimension_json);
        CelestialLevelDimensionTypes.put(WorldID, dimension_type_json);
        CelestialLevelBiomes.put(WorldID, biome_json);
    }

    /**
     * 把一段 JSON 字符串伪装成一个数据包资源。
     *
     * <p>假 pack 的所有查询方法都返回"空"——它唯一的作用是被
     * {@link Resource#sourcePackId()} 之类问到"你从哪来"时有个答案
     * （{@code space} / {@code Fake Resource By Space}），真正的数据由
     * {@code streamSupplier} 直接给。</p>
     */
    public static Resource getResource(String data) {
        PackResources dummyPack = new PackResources() {
            @Override
            public IoSupplier<InputStream> getRootResource(String... strings) {
                return null;
            }

            @Override
            public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation resourceLocation) {
                return null;
            }

            @Override
            public void listResources(PackType packType, String namespace, String path, ResourceOutput resourceOutput) {
            }

            @Override
            public Set<String> getNamespaces(PackType packType) {
                return Set.of();
            }

            @Override
            public <T> T getMetadataSection(MetadataSectionSerializer<T> metadataSectionSerializer) throws IOException {
                return null;
            }

            @Override
            public PackLocationInfo location() {
                return new PackLocationInfo(Polymech.MOD_ID,
                        Component.literal("Fake Resource By Polymech"), PackSource.DEFAULT, Optional.empty());
            }

            @Override
            public String packId() {
                return "fake_resource";
            }

            @Override
            public void close() {
            }
        };
        IoSupplier<InputStream> streamSupplier = () -> new ByteArrayInputStream(data.getBytes());
        return new Resource(dummyPack, streamSupplier);
    }

    static {
        // 太空维度本身：平坦、无方块、无结构 —— 只有天体在飞
        SpaceLevelDimension = """
                {
                  "type": "$mod$:space",
                  "generator": {
                    "type": "minecraft:flat",
                    "settings": {
                      "biome": "$mod$:space",
                      "layers": [],
                      "structures": false,
                      "lakes": false,
                      "features": false
                    }
                  }
                }
                """.replace("$mod$", Polymech.MOD_ID);
        // 太空维度类型：无天光、天空全黑。
        // effects 用**内联对象**（space 原样），其中 clouds/weather 并不是原版
        // DimensionSpecialEffects 的字段，会被 codec 静默忽略 —— 照抄不删，
        // 以免与 space 的解析结果分叉。本项目实际生效的是手写的
        // data/poly_mech/dimension_type/space.json（真实文件优先，见类注释）。
        SpaceLevelDimensionType = """
                {
                  "ambient_light": 0.0,
                  "bed_works": false,
                  "coordinate_scale": 1.0,
                  "effects": {
                    "sky_color": 0,
                    "fog_color": 0,
                    "clouds": false,
                    "weather": false
                  },
                  "has_ceiling": false,
                  "has_raids": false,
                  "has_skylight": false,
                  "height": 512,
                  "infiniburn": "#minecraft:infiniburn_overworld",
                  "logical_height": 256,
                  "min_y": -256,
                  "monster_spawn_block_light_limit": 0,
                  "monster_spawn_light_level": {
                    "type": "minecraft:constant",
                    "value": 0
                  },
                  "natural": false,
                  "piglin_safe": true,
                  "respawn_anchor_works": false,
                  "ultrawarm": false
                }
                """;
        SpaceLevelDimensionBiome = """
                {
                  "temperature": 0.0,
                  "downfall": 0.0,
                  "precipitation": "none",
                  "effects": {
                    "sky_color": 0,
                    "fog_color": 0,
                    "water_color": 4159204,
                    "water_fog_color": 329011
                  },
                  "features": [],
                  "carvers": {},
                  "has_precipitation": true,
                  "spawn_costs": {},
                  "spawners": {
                    "monster": [],
                    "creature": [],
                    "ambient": [],
                    "water_creature": []
                  },
                  "category": "space",
                  "music": {
                    "replace": true,
                    "sound": "",
                    "min_delay": 0,
                    "max_delay": 0
                  }
                }
                """;
    }
}
