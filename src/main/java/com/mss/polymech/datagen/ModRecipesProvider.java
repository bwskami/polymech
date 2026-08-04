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
        saveMachine(out, ModRecipeTypes.STEAM_ROLLER_CRUSHER, "raw_iron_to_dust",
                new MachineRecipe(ModRecipeTypes.STEAM_ROLLER_CRUSHER.type().get(),
                        List.of(si(Items.RAW_IRON)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 100)),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.DUST, "steel").get())),
                        List.of(), 120, 0, false));

        // ===== 蒸汽双联矿物跳汰机：原矿→双倍粉（耗汽更高） =====
        saveMachine(out, ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG, "raw_iron_to_dust_x2",
                new MachineRecipe(ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG.type().get(),
                        List.of(si(Items.RAW_IRON)),
                        List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 200)),
                        List.of(new ItemStack(ModItems.getMaterialItem(ModItemTypes.DUST, "steel").get(), 2)),
                        List.of(), 160, 0, false));
        var copperDust = ModItems.getMaterialItem(ModItemTypes.DUST, "copper");
        if (copperDust != null) {
            saveMachine(out, ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG, "raw_copper_to_dust_x2",
                    new MachineRecipe(ModRecipeTypes.STEAM_DUPLEX_MINERAL_JIG.type().get(),
                            List.of(si(Items.RAW_COPPER)),
                            List.of(new MachineRecipe.FluidInput(ModFluids.STEAM_SOURCE.get(), 200)),
                            List.of(new ItemStack(copperDust.get(), 2)),
                            List.of(), 160, 0, false));
        }

        // ===== 灌装机：空桶+蒸汽→蒸汽桶（电力） =====
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
