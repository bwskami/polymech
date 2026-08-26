package com.mss.polymech.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.worldgen.ModMinerals;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/*
 * 矿石动态资源包（复刻格雷GTDynamicResourcePack的大型模组方案）。
 * <p>
 * 矿石"每矿物×每宿主岩"共2714个方块，每个方块的方块状态与物品模型都是
 * 一行指针JSON（指向184个共享矿石模型之一）——这些指针不写成静态文件，
 * 而在客户端启动时由本资源包在内存中从{@link ModBlocks}×{@link ModMinerals}
 * 数据驱动生成（首次资源访问时惰性构建）。
 * </p>
 * <p>
 * 效果：静态datagen减少约5400个文件；矿石共享模型仍由datagen生成（真资产）；
 * 运行时性能与静态方案一致（模型烘焙数同为184，指针JSON从内存读取反而更快）。
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public class OreDynamicResourcePack implements PackResources {

    /** 内存资源表：资源路径 → JSON字节 */
    private static final Map<ResourceLocation, byte[]> CONTENTS = new HashMap<>();
    private static volatile boolean generated = false;

    private final PackLocationInfo info;

    public OreDynamicResourcePack(PackLocationInfo info) {
        this.info = info;
    }

    /*
     * 惰性生成（双重检查锁）：无论资源管理器何时首次读取本包，
     * 此时模组构造早已完成、方块注册表已冻结，数据安全可用。
     */
    private static void ensureGenerated() {
        if (!generated) {
            synchronized (CONTENTS) {
                if (!generated) {
                    generate();
                    generated = true;
                }
            }
        }
    }

    /** 从矿石注册数据生成共享复合模型 + 全部方块状态/物品模型指针 */
    private static void generate() {
        java.util.Set<String> builtModels = new java.util.HashSet<>();
        for (var mineralEntry : ModBlocks.MINERAL_ORES.entrySet()) {
            var def = ModMinerals.getDefinition(mineralEntry.getKey());
            int shape = def != null ? def.oreShape() : 1;
            for (var variant : mineralEntry.getValue().byRock().entrySet()) {
                String host = variant.getKey();
                ResourceLocation blockId = variant.getValue().getId();
                ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(
                        Polymech.MOD_ID, "block/ore/shared/ore" + shape + "_" + host);

                // 共享复合模型（neoforge:composite）：岩石底 solid + 矿石层 translucent
                // 与格雷 OreBlockRenderer 一致——底先写深度，半透明矿石层共面叠加，
                // 既保留 alpha 混合，又不会有侧面穿透/方块接缝
                String modelKey = "ore" + shape + "_" + host;
                if (builtModels.add(modelKey)) {
                    JsonObject model = buildOreCompositeModel(host, shape);
                    CONTENTS.put(
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID,
                                    "models/block/ore/shared/" + modelKey + ".json"),
                            model.toString().getBytes(StandardCharsets.UTF_8));
                }

                // 方块状态：单变体指向共享矿石模型
                JsonObject blockstate = new JsonObject();
                JsonObject variants = new JsonObject();
                JsonObject variantEntry = new JsonObject();
                variantEntry.addProperty("model", modelId.toString());
                variants.add("", variantEntry);
                blockstate.add("variants", variants);
                CONTENTS.put(
                        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID,
                                "blockstates/" + blockId.getPath() + ".json"),
                        blockstate.toString().getBytes(StandardCharsets.UTF_8));

                // 物品模型：parent指向同一共享模型
                JsonObject itemModel = new JsonObject();
                itemModel.addProperty("parent", modelId.toString());
                CONTENTS.put(
                        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID,
                                "models/item/" + blockId.getPath() + ".json"),
                        itemModel.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        Polymech.LOGGER.debug("Ore dynamic resource pack generated: {} resources", CONTENTS.size());
    }

    /*
     * 构建格雷式复合矿石模型：
     * <pre>
     * children:
     *   base_stone  {parent: 岩石模型, render_type: "solid"}
     *   ore_texture {render_type: "translucent", elements: 3个0~16共面立方体,
     *                layer0=ore底图(tint1), layer1=阴影(tint2), layer2=高光(tint3)}
     * </pre>
     * 关键：矿石层与岩石底完全共面（0~16），不向外偏移，
     * 半透明层只会混合在已经写深度的 solid 底上，不会露背景或产生接缝。
     */
    private static JsonObject buildOreCompositeModel(String host, int shape) {
        JsonObject root = new JsonObject();
        root.addProperty("parent", "block/block");
        root.addProperty("loader", "neoforge:composite");

        JsonObject rootTextures = new JsonObject();
        rootTextures.addProperty("particle", baseParticleTexture(host).toString());
        root.add("textures", rootTextures);

        JsonObject children = new JsonObject();

        // 岩石底子模型：solid，继承岩种/石头/深板岩模型
        JsonObject baseStone = new JsonObject();
        baseStone.addProperty("parent", baseModelId(host).toString());
        baseStone.addProperty("render_type", "solid");
        children.add("base_stone", baseStone);

        // 矿石层子模型：translucent，3 个共面立方体
        JsonObject oreTexture = new JsonObject();
        oreTexture.addProperty("parent", "block/block");
        oreTexture.addProperty("render_type", "translucent");
        JsonObject oreTextures = new JsonObject();
        oreTextures.addProperty("layer0", "poly_mech:block/ore/ore" + shape);
        oreTextures.addProperty("layer1", "poly_mech:block/ore/ore" + shape + "_secondary");
        oreTextures.addProperty("layer2", "poly_mech:block/ore/ore" + shape + "_overlay");
        oreTextures.addProperty("particle", "#layer0");
        oreTexture.add("textures", oreTextures);
        JsonArray elements = new JsonArray();
        elements.add(buildOreElement("layer0", 1)); // 主色
        elements.add(buildOreElement("layer1", 2)); // 阴影
        elements.add(buildOreElement("layer2", 3)); // 高光
        oreTexture.add("elements", elements);
        children.add("ore_texture", oreTexture);

        root.add("children", children);

        JsonArray order = new JsonArray();
        order.add("base_stone");
        order.add("ore_texture");
        root.add("item_render_order", order);
        return root;
    }

    /** 0~16 共面立方体元素（无偏移！） */
    private static JsonObject buildOreElement(String textureName, int tintIndex) {
        JsonObject element = new JsonObject();
        JsonArray from = new JsonArray();
        from.add(0); from.add(0); from.add(0);
        element.add("from", from);
        JsonArray to = new JsonArray();
        to.add(16); to.add(16); to.add(16);
        element.add("to", to);
        JsonObject faces = new JsonObject();
        for (String dir : new String[]{"down","up","north","south","west","east"}) {
            JsonObject face = new JsonObject();
            face.addProperty("texture", "#" + textureName);
            face.addProperty("cullface", dir);
            face.addProperty("tintindex", tintIndex);
            faces.add(dir, face);
        }
        element.add("faces", faces);
        return element;
    }

    /** 岩石底子模型的 parent：石头/深板岩/下界岩/末地石/群峦岩种（都是模型ID，不是贴图ID！） */
    private static ResourceLocation baseModelId(String host) {
        return switch (host) {
            case "stone" -> ResourceLocation.withDefaultNamespace("block/stone");
            case "deepslate" -> ResourceLocation.withDefaultNamespace("block/deepslate");
            case "netherrack" -> ResourceLocation.withDefaultNamespace("block/netherrack");
            case "end_stone" -> ResourceLocation.withDefaultNamespace("block/end_stone");
            // 群峦岩石方块模型：poly_mech:block/{rock}（datagen generateRockBlocks 生成）
            default -> ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/" + host);
        };
    }

    /** 复合模型 particle 贴图：石头/深板岩/下界岩/末地石/群峦岩种贴图 */
    private static ResourceLocation baseParticleTexture(String host) {
        return switch (host) {
            case "stone" -> ResourceLocation.withDefaultNamespace("block/stone");
            case "deepslate" -> ResourceLocation.withDefaultNamespace("block/deepslate");
            case "netherrack" -> ResourceLocation.withDefaultNamespace("block/netherrack");
            case "end_stone" -> ResourceLocation.withDefaultNamespace("block/end_stone");
            default -> ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/rock/raw/" + host);
        };
    }

    // ==================== PackResources 实现 ====================

    @Override
    @Nullable
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Override
    @Nullable
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) return null;
        ensureGenerated();
        byte[] data = CONTENTS.get(location);
        if (data == null) return null;
        return () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES) return;
        if (!Polymech.MOD_ID.equals(namespace)) return;
        ensureGenerated();
        String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String prefix = normalizedPath.isEmpty() ? "" : normalizedPath + "/";
        for (var entry : CONTENTS.entrySet()) {
            ResourceLocation id = entry.getKey();
            String filePath = id.getPath();
            if (filePath.startsWith(prefix)) {
                byte[] data = entry.getValue();
                output.accept(id, () -> new ByteArrayInputStream(data));
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(Polymech.MOD_ID) : Set.of();
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
        if (deserializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(
                    Component.literal("Polymech dynamic ore assets"),
                    SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES));
        }
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return info;
    }

    @Override
    public void close() {
        // 内存包无需释放
    }
}
