package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.powergrid.GridWireType;
import com.mss.polymech.fluid.ModChemicalFluids;
import com.mss.polymech.fluid.ModElementFluids;
import com.mss.polymech.fluid.ModFluids;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.texture_data.ItemLayerTemplates;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.HashMap;
import java.util.Map;

public class ModItemModelsProvider extends ItemModelProvider {
    private static final Map<String, ItemLayerTemplates> ITEM_TYPE_OVERRIDES = new HashMap<>();
    
    static {
        // 初始化物品类型映射
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            // 锭
            ITEM_TYPE_OVERRIDES.put(materialName + "_ingot", ItemLayerTemplates.INGOT);
            // 合金锭
            ITEM_TYPE_OVERRIDES.put(materialName + "_alloy_ingot", ItemLayerTemplates.ALLOY);
            // 粒
            ITEM_TYPE_OVERRIDES.put(materialName + "_nugget", ItemLayerTemplates.NUGGET);
            // 粉
            ITEM_TYPE_OVERRIDES.put(materialName + "_dust", ItemLayerTemplates.DUST);
            // 板
            ITEM_TYPE_OVERRIDES.put(materialName + "_plate", ItemLayerTemplates.PLATE);
            // 箔
            ITEM_TYPE_OVERRIDES.put(materialName + "_foil", ItemLayerTemplates.FOIL);
            // 杆
            ITEM_TYPE_OVERRIDES.put(materialName + "_stick", ItemLayerTemplates.STICK);
            // 齿轮
            ITEM_TYPE_OVERRIDES.put(materialName + "_gear", ItemLayerTemplates.GEAR);
            // 小齿轮
            ITEM_TYPE_OVERRIDES.put(materialName + "_small_gear", ItemLayerTemplates.SMALL_GEAR);
            // 弹簧
            ITEM_TYPE_OVERRIDES.put(materialName + "_spring", ItemLayerTemplates.SPRING);
            // 螺丝
            ITEM_TYPE_OVERRIDES.put(materialName + "_screw", ItemLayerTemplates.SCREW);
            // 螺栓
            ITEM_TYPE_OVERRIDES.put(materialName + "_bolt", ItemLayerTemplates.BOLT);
            // 环
            ITEM_TYPE_OVERRIDES.put(materialName + "_ring", ItemLayerTemplates.RING);
        }

        // 矿物加工链（真实矿物系统，数据驱动）：
        // 粗矿 raw_{mineral} → 粉碎矿 {mineral}_crushed → 洗净矿 {mineral}_purified
        // 全部按矿物配色染色，贴图取自GregTech material_sets
        for (com.mss.polymech.worldgen.ModMinerals.MineralDefinition def : com.mss.polymech.worldgen.ModMinerals.getDefinitions()) {
            // 粗矿物品图标按矿物形态（1普通/2层状/3斜向小块/4大块）选择 raw{shape} 模板
            ItemLayerTemplates rawTemplate = switch (def.oreShape()) {
                case 2 -> ItemLayerTemplates.RAW_ORE_2;
                case 3 -> ItemLayerTemplates.RAW_ORE_3;
                case 4 -> ItemLayerTemplates.RAW_ORE_4;
                default -> ItemLayerTemplates.RAW_ORE_1;
            };
            ITEM_TYPE_OVERRIDES.put(def.rawItemName(), rawTemplate);
            if (def.kind() == com.mss.polymech.worldgen.ModMinerals.ProductKind.COAL) continue;
            ITEM_TYPE_OVERRIDES.put(
                    com.mss.polymech.api.item.ModItemTypes.CRUSHED.getIdPattern().formatted(def.mineral()),
                    ItemLayerTemplates.CRUSHED);
            ITEM_TYPE_OVERRIDES.put(
                    com.mss.polymech.api.item.ModItemTypes.PURIFIED.getIdPattern().formatted(def.mineral()),
                    ItemLayerTemplates.PURIFIED);
        }

        // 宝石/晶体（{gem}_gem，按宝石配色染色）
        for (String gem : com.mss.polymech.api.material.GemMaterials.getGems()) {
            ITEM_TYPE_OVERRIDES.put(gem + "_gem", ItemLayerTemplates.GEM);
        }
        
        // 管道物品（数据驱动：全部材质×全部尺寸，模板按尺寸选择）
        for (PipeMaterial pipeMaterial : PipeMaterial.getAll()) {
            for (PipeBlock.PipeSize size : PipeBlock.PipeSize.values()) {
                ItemLayerTemplates pipeTemplate = switch (size) {
                    case SMALL -> ItemLayerTemplates.SMALL_PIPE_ITEM;
                    case BIG -> ItemLayerTemplates.BIG_PIPE_ITEM;
                    case HUGE -> ItemLayerTemplates.HUGE_PIPE_ITEM;
                    default -> ItemLayerTemplates.PIPE_ITEM;
                };
                ITEM_TYPE_OVERRIDES.put(size.getRegistryName(pipeMaterial), pipeTemplate);
            }
        }

        // 传送带物品（按材质注册名映射）
        for (ConveyorMaterial material : ConveyorMaterial.values()) {
            ITEM_TYPE_OVERRIDES.put(material.getConveyorRegistryName(), ItemLayerTemplates.CONVEYOR_BELT_ITEM);
        }

        // 焦煤：复用粉尘染色模板，颜色由 colors.json 的 coke 材质条目提供
        ITEM_TYPE_OVERRIDES.put("coke", ItemLayerTemplates.DUST);

        // 电网线轴（数据驱动：全部金属线缆），底层空线轴不染色、线圈三层按金属染色；
        // 绝缘变体额外追加 Insulated_logo 标识层（不染色原样显示）
        for (GridWireType wireType : GridWireType.values()) {
            ITEM_TYPE_OVERRIDES.put(wireType.spoolItemName(),
                    wireType.isInsulated() ? ItemLayerTemplates.INSULATED_SPOOL : ItemLayerTemplates.SPOOL);
        }
        // 空线轴：标准化单层模型（material_sets/spool/empty_spool），不染色
        ITEM_TYPE_OVERRIDES.put("empty_spool", ItemLayerTemplates.EMPTY_SPOOL);

        // 电线物品（数据驱动：全部材料），三层染色模板
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            ITEM_TYPE_OVERRIDES.put(materialName + "_wire", ItemLayerTemplates.WIRE);
        }
    }
    private static final String[] NORMAL_ITEMS = {
            "steel_ingot",
            "wrench",
            "blueprint",
            "wire_cutter",
            "clamp_meter",
            "prospector",
            // 在这里列出所有需要独立纹理的普通物品
    };
    private boolean isNormalItem(String path) {
        for (String normal : NORMAL_ITEMS) {
            if (normal.equals(path)) {
                return true;
            }
        }
        return false;
    }

    public ModItemModelsProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, Polymech.MOD_ID, existingFileHelper);
    }



    @Override
    protected void registerModels() {
        for (var entry : ModItems.ITEMS.getEntries()) {
            Item item = entry.get();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            String path = itemId.getPath();

            // 情况1: 染色模板物品 → 生成多层模型
            // 注：item/generated 会自动给第 N 层分配 tintindex=N；
            // 超出 colors.json 颜色数组范围的图层（如传送带第 4 层）会返回白色，即不染色
            ItemLayerTemplates type = ITEM_TYPE_OVERRIDES.get(path);
            if (type != null) {
                var builder = withExistingParent(path, "item/generated");
                var layers = type.getLayerTextures();
                for (int i = 0; i < layers.size(); i++) {
                    builder.texture("layer" + i, modLoc(layers.get(i)));
                }
                continue;
            }

            // 网络调试仪：暂复用扳手贴图，后续可替换为专属贴图
            if ("network_tool".equals(path)) {
                withExistingParent(path, "item/generated")
                        .texture("layer0", modLoc("item/wrench"));
                continue;
            }

            // 情况2: 普通物品 → 使用 basicItem（需要独立纹理）
            if (isNormalItem(path)) {
                basicItem(item);
                continue;
            }

            // 情况3: 方块物品或其他 → 跳过（由 BlockStateProvider 处理）
            Polymech.LOGGER.debug("Skipped model generation for block item: {}", path);
        }

        // 流体桶物品
        for (var entry : ModFluids.FLUID_BUCKET_ITEMS.getEntries()) {
            basicItem(entry.get());
        }

        // 化学流体桶：暂共用原版桶贴图（layer0），后续可替换为各流体专属贴图
        for (var entry : ModChemicalFluids.BUCKETS.getEntries()) {
            withExistingParent(entry.getId().getPath(), "item/generated")
                    .texture("layer0", mcLoc("item/bucket"));
        }

        // 熔融金属桶：同样暂用原版桶贴图（仅液体有桶，等离子体无桶）
        for (var entry : ModElementFluids.BUCKETS.getEntries()) {
            withExistingParent(entry.getId().getPath(), "item/generated")
                    .texture("layer0", mcLoc("item/bucket"));
        }
    }
}