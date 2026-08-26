package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.fluid.ModFluids;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.recipe.MachineRecipe;
import com.mss.polymech.recipe.ModRecipeTypes;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.conditions.IConditionBuilder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModRecipesProvider extends RecipeProvider implements IConditionBuilder {
    public ModRecipesProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {

        ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.COKE_OVEN_BRICK)
                .pattern("###")
                .pattern("###")
                .pattern("###")
                .define('#', Items.DIRT)
                .unlockedBy(getHasName(Items.DIRT), has(Items.DIRT))
                .save(recipeOutput);

        ShapedRecipeBuilder.shaped(RecipeCategory.FOOD, Items.SUGAR, 3)
                .pattern("###")
                .define('#', Items.BEETROOT)
                .unlockedBy(getHasName(Items.BEETROOT), has(Items.BEETROOT))
                .save(recipeOutput, Polymech.MOD_ID + ":" + "sugar_from_beetroot");

        buildMachineRecipes(recipeOutput);
    }

    // ==================== 机器配方 ====================

    private void buildMachineRecipes(RecipeOutput out) {
        // ===== 蜂巢焦炉：无动力 =====
        saveMachine(out, ModRecipeTypes.BEEHIVE_COKE_OVEN, "coal_to_coke",
                new MachineRecipe(ModRecipeTypes.BEEHIVE_COKE_OVEN.type().get(),
                        List.of(si(Items.COAL)), List.of(),
                        List.of(new ItemStack(ModItems.COKE.get())), List.of(),
                        900, 0, false));
        saveMachine(out, ModRecipeTypes.BEEHIVE_COKE_OVEN, "log_to_charcoal",
                new MachineRecipe(ModRecipeTypes.BEEHIVE_COKE_OVEN.type().get(),
                        List.of(siTag(ItemTags.LOGS_THAT_BURN)), List.of(),
                        List.of(new ItemStack(Items.CHARCOAL)), List.of(),
                        450, 0, false));

        // ===== 原始高炉：无动力炼钢 =====
        saveMachine(out, ModRecipeTypes.PRIMITIVE_BLAST_FURNACE, "steel_from_iron_and_coal",
                new MachineRecipe(ModRecipeTypes.PRIMITIVE_BLAST_FURNACE.type().get(),
                        List.of(si(Items.IRON_INGOT), si(Items.COAL)),
                        List.of(),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.INGOT, "steel").get())),
                        List.of(), 600, 0, false));
        saveMachine(out, ModRecipeTypes.PRIMITIVE_BLAST_FURNACE, "steel_from_iron_and_coke",
                new MachineRecipe(ModRecipeTypes.PRIMITIVE_BLAST_FURNACE.type().get(),
                        List.of(si(Items.IRON_INGOT), si(ModItems.COKE.get())),
                        List.of(),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.INGOT, "steel").get())),
                        List.of(), 400, 0, false));

        // ===== 蒸汽锤：锭→板（蒸汽经流体输入消耗） =====
        for (String material : MaterialRegistry.getMaterialNames()) {
            var ingot = ModItems.getMaterialItem(ModItemTypes.INGOT, material);
            var plate = ModItems.getMaterialItem(ModItemTypes.PLATE, material);
            if (ingot == null || plate == null) continue;
            saveMachine(out, ModRecipeTypes.STEAM_HAMMER, "plate_" + material,
                    new MachineRecipe(ModRecipeTypes.STEAM_HAMMER.type().get(),
                            List.of(si(ingot.get())),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                            List.of(new ItemStack(plate.get())), List.of(),
                            100, 0, false));
        }

        // ===== 蒸汽辊式破碎机：粉碎链 + 单倍粉 =====
        saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "cobblestone_to_gravel",
                new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                        List.of(si(Blocks.COBBLESTONE)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 50)),
                        List.of(new ItemStack(Blocks.GRAVEL)), List.of(), 60, 0, false));
        saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "gravel_to_flint",
                new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                        List.of(si(Blocks.GRAVEL)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 50)),
                        List.of(new ItemStack(Items.FLINT)), List.of(), 60, 0, false));
        // 原版粗铁→铁粉（真实矿物链：选矿得铁粉，熔炼回原版铁锭；不再错误地产出钢粉）
        saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "vanilla_raw_iron_to_iron_dust",
                new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                        List.of(si(Items.RAW_IRON)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.DUST, "iron").get())),
                        List.of(), 120, 0, false));

        // ===== 蒸汽双联矿物跳汰机：原矿→双倍粉（耗汽更高） =====
        saveMachine(out, ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG, "vanilla_raw_iron_to_iron_dust_x2",
                new MachineRecipe(ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG.type().get(),
                        List.of(si(Items.RAW_IRON)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 200)),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.DUST, "iron").get(), 2)),
                        List.of(), 160, 0, false));
        // 原版粗金→金粉（单倍破碎）
        saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "vanilla_raw_gold_to_gold_dust",
                new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                        List.of(si(Items.RAW_GOLD)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.DUST, "gold").get())),
                        List.of(), 120, 0, false));
        var copperDust = ModItems.getMaterialItem(ModItemTypes.DUST, "copper");
        if (copperDust != null) {
            saveMachine(out, ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG, "vanilla_raw_copper_to_copper_dust_x2",
                    new MachineRecipe(ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG.type().get(),
                            List.of(si(Items.RAW_COPPER)),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 200)),
                            List.of(new ItemStack(copperDust.get(), 2)),
                            List.of(), 160, 0, false));
        }

        // ===== 真实矿物加工链：粗矿物→破碎机单倍金属粉 / 跳汰机双倍金属粉 =====
        // 化学事实：金属以矿物（化合物）形式存在，必须先选矿再冶炼，
        // 因此不存在"粗矿物/矿石直接熔炼成锭"的捷径（金属粉→锭的熔炼保留在金属配方侧）
        for (com.mss.polymech.worldgen.ModMinerals.MineralDefinition def : com.mss.polymech.worldgen.ModMinerals.getDefinitions()) {
            String mineral = def.mineral();
            var rawItem = ModItems.getRawMineral(mineral);
            var dust = ModItems.getMaterialItem(ModItemTypes.DUST, def.metal());
            if (rawItem == null || dust == null) continue;

            // 蒸汽辊式破碎机：粗矿物→1金属粉（低耗汽，基础倍率）
            saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "raw_" + mineral + "_to_dust",
                    new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                            List.of(si(rawItem.get())),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                            List.of(new ItemStack(dust.get())), List.of(),
                            byproductsFor(mineral),
                            120, 0, false));

            // 蒸汽双联矿物跳汰机：粗矿物→2金属粉（高耗汽，双倍倍率）
            saveMachine(out, ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG, "raw_" + mineral + "_to_dust_x2",
                    new MachineRecipe(ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG.type().get(),
                            List.of(si(rawItem.get())),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 200)),
                            List.of(new ItemStack(dust.get(), 2)), List.of(),
                            byproductsFor(mineral),
                            160, 0, false));
        }

        // ===== 矿物加工链（格雷式三级选矿）：粗矿/宝石→粉碎矿→洗净矿→粉 =====
        // 粗矿破碎成粉碎矿；粉碎矿跳汰洗选成洗净矿；洗净矿再破碎成纯粉。
        // 煤炭直接掉煤、粉末类矿物直接掉粉，不进粉碎/洗净链。
        // METAL 从 raw_{mineral} 进入；GEM 从 {gem}_gem 进入，同样可以制粉。
        for (com.mss.polymech.worldgen.ModMinerals.MineralDefinition def : com.mss.polymech.worldgen.ModMinerals.getDefinitions()) {
            if (def.kind() == com.mss.polymech.worldgen.ModMinerals.ProductKind.COAL
                    || def.kind() == com.mss.polymech.worldgen.ModMinerals.ProductKind.DUST) continue;
            var raw = ModItems.getRawMineral(def.mineral());
            var gem = def.kind() == com.mss.polymech.worldgen.ModMinerals.ProductKind.GEM
                    ? ModItems.getMaterialItem(ModItemTypes.GEM, def.metal()) : null;
            ItemLike entryItem = raw != null ? raw.get() : (gem != null ? gem.get() : null);
            var crushed = ModItems.getMineralItem(ModItemTypes.CRUSHED, def.mineral());
            var purified = ModItems.getMineralItem(ModItemTypes.PURIFIED, def.mineral());
            var dust = ModItems.getMaterialItem(ModItemTypes.DUST, def.metal());
            if (entryItem == null || crushed == null || purified == null || dust == null) continue;

            // 破碎机：粗矿/宝石 → 粉碎矿
            String entryName = raw != null ? "raw_" + def.mineral() : "gem_" + def.mineral();
            saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, entryName + "_to_crushed",
                    new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                            List.of(si(entryItem)),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                            List.of(new ItemStack(crushed.get())), List.of(),
                            120, 0, false));

            // 跳汰机：粉碎矿 → 洗净矿
            saveMachine(out, ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG, "crushed_" + def.mineral() + "_to_purified",
                    new MachineRecipe(ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG.type().get(),
                            List.of(si(crushed.get())),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 200)),
                            List.of(new ItemStack(purified.get())), List.of(),
                            byproductsFor(def.mineral()),
                            160, 0, false));

            // 破碎机：洗净矿 → 金属/宝石粉
            saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "purified_" + def.mineral() + "_to_dust",
                    new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                            List.of(si(purified.get())),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                            List.of(new ItemStack(dust.get())), List.of(),
                            120, 0, false));
        }

        // ===== 原版三大金属：金属粉熔炼成原版锭 =====
        // 铁/铜/金锭沿用原版物品（不重复造锭），粉碎选矿后的粉回到原版熔炉
        for (String vanillaMetal : new String[]{"iron", "copper", "gold"}) {
            var dust = ModItems.getMaterialItem(ModItemTypes.DUST, vanillaMetal);
            if (dust == null) continue;
            ItemLike vanillaIngot = switch (vanillaMetal) {
                case "iron" -> Items.IRON_INGOT;
                case "copper" -> Items.COPPER_INGOT;
                default -> Items.GOLD_INGOT;
            };
            oreSmelting(out, List.of(dust.get()), RecipeCategory.MISC, vanillaIngot, 0.7F, 200, vanillaMetal + "_ingot");
            oreBlasting(out, List.of(dust.get()), RecipeCategory.MISC, vanillaIngot, 0.7F, 100, vanillaMetal + "_ingot");
        }

        // ===== 硅的碳热还原（现实路线简化）：石英粉 → 硅粉 =====
        var quartzDust = ModItems.getMaterialItem(ModItemTypes.DUST, "quartz");
        var siliconDust = ModItems.getMaterialItem(ModItemTypes.DUST, "silicon");
        if (quartzDust != null && siliconDust != null) {
            oreSmelting(out, List.of(quartzDust.get()), RecipeCategory.MISC, siliconDust.get(), 0.5F, 200, "silicon_dust");
            oreBlasting(out, List.of(quartzDust.get()), RecipeCategory.MISC, siliconDust.get(), 0.5F, 100, "silicon_dust");
        }

        // ===== 冶炼闭环：全部含锭材料的金属粉 → 对应锭 =====
        // 单质金属产 INGOT，合金产 ALLOY_INGOT；原版三大金属已在上面单独处理。
        // 火焰反射炉代理原版熔炼配方，因此这些粉→锭配方也会自动在该机器中可用。
        for (String material : MaterialRegistry.getMaterialNames()) {
            if (!ModItemTypes.hasIngot(material)) continue;
            var dust = ModItems.getMaterialItem(ModItemTypes.DUST, material);
            var ingot = ModItems.getMaterialItem(ModItemTypes.INGOT, material);
            if (ingot == null) {
                ingot = ModItems.getMaterialItem(ModItemTypes.ALLOY_INGOT, material);
            }
            if (dust == null || ingot == null) continue;
            oreSmelting(out, List.of(dust.get()), RecipeCategory.MISC, ingot.get(), 0.7F, 200, material + "_ingot");
            oreBlasting(out, List.of(dust.get()), RecipeCategory.MISC, ingot.get(), 0.7F, 100, material + "_ingot");
        }

        // ===== 合金配方：组分金属粉混合成合金粉（合金粉再熔炼成合金锭）=====
        // 组成照抄 MaterialRegistry.MATERIAL_FORMULAS 的化学计量比，
        // 输出数量 = 组分材料单位总数（如青铜 Cu3Sn → 3+1 = 4 个青铜粉）。
        alloyMixing(out, "brass", 2, new String[][]{{"copper", "1"}, {"zinc", "1"}});
        alloyMixing(out, "bronze", 4, new String[][]{{"copper", "3"}, {"tin", "1"}});
        alloyMixing(out, "invar", 3, new String[][]{{"iron", "2"}, {"nickel", "1"}});
        alloyMixing(out, "cupronickel", 2, new String[][]{{"copper", "1"}, {"nickel", "1"}});
        alloyMixing(out, "electrum", 2, new String[][]{{"gold", "1"}, {"silver", "1"}});
        alloyMixing(out, "stainless_steel", 9, new String[][]{{"iron", "6"}, {"chromium", "1"}, {"manganese", "1"}, {"nickel", "1"}});

        // ===== 灌装机：空桶+蒸汽→蒸汽桶（假流体桶，不产生世界流体方块） =====
        saveMachine(out, ModRecipeTypes.FILLING_UNIT, "fill_steam_bucket",
                new MachineRecipe(ModRecipeTypes.FILLING_UNIT.type().get(),
                        List.of(si(Items.BUCKET)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 1000)),
                        List.of(new ItemStack(ModFluids.STEAM_BUCKET.get())), List.of(),
                        80, 4, false));

        // ===== 蒸汽涡轮发电机：蒸汽→发电（周期 100 tick，每 tick 发电 8） =====
        saveMachine(out, ModRecipeTypes.STEAM_TURBINE_GENERATOR, "steam_fuel",
                new MachineRecipe(ModRecipeTypes.STEAM_TURBINE_GENERATOR.type().get(),
                        List.of(),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 500)),
                        List.of(), List.of(), 100, 8, true));

        // ===== 燃气涡轮发电机：燃气流体待添加，配方暂空 =====
    }

    /** 数量 1 的物品原料 */
    private static SizedIngredient si(ItemLike item) {
        return new SizedIngredient(Ingredient.of(item), 1);
    }

    /** 数量 1 的标签原料 */
    private static SizedIngredient siTag(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) {
        return new SizedIngredient(Ingredient.of(tag), 1);
    }

    private static void saveMachine(RecipeOutput out, ModRecipeTypes.Entry entry, String name, MachineRecipe recipe) {
        out.accept(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, name), recipe, null);
    }

    /*
     * 多金属矿物选矿副产物表：矿物名 → {伴生材料, 产出概率} 数组。
     * <p>
     * 参考真实矿床学常见伴生组合：
     * 方铅矿→银/硫、黄铜矿/斑铜矿→铁/硫、黝铜矿→锑/硫、
     * 镍黄铁矿→铁/钴/硫、铬铁矿→铁、钛铁矿→铁、黑钨矿→锰、
     * 辉锑矿→硫、硫铂矿→钯/镍/硫、沥青铀矿→钍/铅。
     * 未列出的矿物不产副产物（List.of() 空表）。
     * </p>
     */
    private static final java.util.Map<String, Object[][]> BYPRODUCT_TABLE = java.util.Map.ofEntries(
            java.util.Map.entry("galena", new Object[][]{{"silver", 0.5F}, {"sulfur", 0.25F}, {"bismuth", 0.1F}, {"antimony", 0.1F}}),
            java.util.Map.entry("chalcopyrite", new Object[][]{{"iron", 0.33F}, {"sulfur", 0.25F}, {"gold", 0.05F}, {"silver", 0.05F}}),
            java.util.Map.entry("bornite", new Object[][]{{"iron", 0.33F}, {"sulfur", 0.25F}, {"gold", 0.05F}, {"silver", 0.05F}}),
            java.util.Map.entry("chalcocite", new Object[][]{{"sulfur", 0.25F}, {"silver", 0.1F}}),
            java.util.Map.entry("tetrahedrite", new Object[][]{{"antimony", 0.33F}, {"sulfur", 0.25F}, {"silver", 0.15F}}),
            java.util.Map.entry("sphalerite", new Object[][]{{"sulfur", 0.25F}, {"iron", 0.15F}, {"cadmium", 0.2F}, {"gallium", 0.1F}, {"indium", 0.1F}}),
            java.util.Map.entry("pentlandite", new Object[][]{{"iron", 0.33F}, {"cobalt", 0.15F}, {"sulfur", 0.25F}, {"copper", 0.1F}}),
            java.util.Map.entry("garnierite", new Object[][]{{"iron", 0.2F}, {"cobalt", 0.1F}}),
            java.util.Map.entry("cobaltite", new Object[][]{{"sulfur", 0.25F}, {"iron", 0.15F}, {"nickel", 0.1F}}),
            java.util.Map.entry("chromite", new Object[][]{{"iron", 0.33F}, {"vanadium", 0.1F}}),
            java.util.Map.entry("ilmenite", new Object[][]{{"iron", 0.5F}, {"vanadium", 0.05F}}),
            java.util.Map.entry("vanadium_magnetite", new Object[][]{{"vanadium", 0.33F}, {"iron", 0.2F}}),
            java.util.Map.entry("wolframite", new Object[][]{{"manganese", 0.33F}, {"iron", 0.2F}, {"tantalum", 0.1F}, {"niobium", 0.1F}}),
            java.util.Map.entry("stibnite", new Object[][]{{"sulfur", 0.25F}, {"gold", 0.1F}}),
            java.util.Map.entry("cooperite", new Object[][]{{"palladium", 0.2F}, {"nickel", 0.2F}, {"sulfur", 0.25F}, {"osmium", 0.1F}, {"iridium", 0.1F}, {"ruthenium", 0.05F}, {"rhodium", 0.05F}}),
            java.util.Map.entry("pitchblende", new Object[][]{{"thorium", 0.2F}, {"lead", 0.1F}}),
            java.util.Map.entry("uraninite", new Object[][]{{"thorium", 0.2F}, {"lead", 0.1F}}),
            java.util.Map.entry("monazite", new Object[][]{{"thorium", 0.2F}, {"cerium", 0.25F}, {"lanthanum", 0.15F}, {"yttrium", 0.1F}, {"samarium", 0.05F}, {"europium", 0.03F}}),
            java.util.Map.entry("bastnasite", new Object[][]{{"cerium", 0.3F}, {"lanthanum", 0.2F}, {"yttrium", 0.1F}, {"samarium", 0.05F}}),
            java.util.Map.entry("bauxite", new Object[][]{{"gallium", 0.08F}, {"iron", 0.2F}}),
            java.util.Map.entry("molybdenite", new Object[][]{{"rhenium", 0.1F}, {"sulfur", 0.3F}}),
            java.util.Map.entry("powellite", new Object[][]{{"molybdenum", 0.2F}, {"sulfur", 0.1F}}),
            java.util.Map.entry("wulfenite", new Object[][]{{"lead", 0.1F}, {"sulfur", 0.1F}}),
            java.util.Map.entry("cassiterite", new Object[][]{{"tantalum", 0.05F}, {"niobium", 0.05F}, {"tungsten", 0.05F}}),
            java.util.Map.entry("pyrite", new Object[][]{{"gold", 0.05F}, {"copper", 0.1F}, {"sulfur", 0.3F}}),
            java.util.Map.entry("realgar", new Object[][]{{"sulfur", 0.2F}})
    );

    /**
     * 取某矿物的选矿副产物 Byproduct 列表。
     * 材料名解析为对应金属粉物品；不存在则跳过该项。
     */
    private List<MachineRecipe.Byproduct> byproductsFor(String mineral) {
        Object[][] spec = BYPRODUCT_TABLE.get(mineral);
        if (spec == null) return List.of();
        List<MachineRecipe.Byproduct> result = new java.util.ArrayList<>();
        for (Object[] entry : spec) {
            var dust = ModItems.getMaterialItem(ModItemTypes.DUST, (String) entry[0]);
            if (dust == null) continue;
            result.add(new MachineRecipe.Byproduct(new ItemStack(dust.get()), (Float) entry[1]));
        }
        return result;
    }

    /**
     * 合金粉混合配方（无序合成）：组分金属粉 → 合金粉。
     * <p>
     * 合金粉随后经上面的粉→锭熔炼产出合金锭，构成完整的合金冶炼链。
     * 组分与数量取自{@code components}（材料名 → 数量字符串对），
     * 解锁条件挂在第一个组分粉上。
     * </p>
     *
     * @param out         配方输出
     * @param alloy       合金材料名（需存在合金粉）
     * @param resultCount 产出的合金粉数量（= 组分材料单位总数）
     * @param components  组分：{材料名, 数量} 对
     */
    private void alloyMixing(RecipeOutput out, String alloy, int resultCount, String[][] components) {
        var result = ModItems.getMaterialItem(ModItemTypes.DUST, alloy);
        if (result == null) return;
        ShapelessRecipeBuilder builder = ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, result.get(), resultCount);
        String unlockName = null;
        ItemLike unlockItem = null;
        for (String[] comp : components) {
            var dust = ModItems.getMaterialItem(ModItemTypes.DUST, comp[0]);
            if (dust == null) return;
            builder.requires(dust.get(), Integer.parseInt(comp[1]));
            if (unlockItem == null) {
                unlockItem = dust.get();
                unlockName = comp[0];
            }
        }
        builder.unlockedBy("has_" + unlockName + "_dust", has(unlockItem));
        builder.save(out, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "alloy_dust_" + alloy));
    }

    protected static void oreSmelting(
            RecipeOutput recipeOutput, List<ItemLike> ingredients, RecipeCategory category, ItemLike result, float experience, int cookingTime, String group
    ) {
        oreCooking(
                recipeOutput,
                RecipeSerializer.SMELTING_RECIPE,
                SmeltingRecipe::new,
                ingredients,
                category,
                result,
                experience,
                cookingTime,
                group,
                "_from_smelting"
        );
    }

    protected static void oreBlasting(
            RecipeOutput recipeOutput, List<ItemLike> ingredients, RecipeCategory category, ItemLike result, float experience, int cookingTime, String group
    ) {
        oreCooking(
                recipeOutput,
                RecipeSerializer.BLASTING_RECIPE,
                BlastingRecipe::new,
                ingredients,
                category,
                result,
                experience,
                cookingTime,
                group,
                "_from_blasting"
        );
    }

    protected static <T extends AbstractCookingRecipe> void oreCooking(
            RecipeOutput recipeOutput,
            RecipeSerializer<T> serializer,
            AbstractCookingRecipe.Factory<T> recipeFactory,
            List<ItemLike> ingredients,
            RecipeCategory category,
            ItemLike result,
            float experience,
            int cookingTime,
            String group,
            String suffix
    ) {
        for (ItemLike itemlike : ingredients) {
            SimpleCookingRecipeBuilder.generic(Ingredient.of(itemlike), category, result, experience, cookingTime, serializer, recipeFactory)
                    .group(group)
                    .unlockedBy(getHasName(itemlike), has(itemlike))
                    .save(recipeOutput, Polymech.MOD_ID + ":" + getItemName(result) + suffix + "_" + getItemName(itemlike));
        }
    }
}
