package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.*;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.conditions.IConditionBuilder;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModRecipesProvider extends RecipeProvider implements IConditionBuilder {
    public ModRecipesProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        oreSmelting(recipeOutput, TEST_ITEM1, RecipeCategory.MISC, ModItems.TEST_ITEM1, 0.1f, 200, "test");
        oreBlasting(recipeOutput, TEST_ITEM1, RecipeCategory.MISC, ModItems.TEST_ITEM1, 0.1f, 200, "test");

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
                //如果涉及原版相关物品合成但没有声明命名空间，则会顶替掉原版配方

        ShapelessRecipeBuilder.shapeless(RecipeCategory.BUILDING_BLOCKS, ModBlocks.TEST_ORE)
                .requires(ModItems.TEST_RAW)
                .requires(Blocks.STONE)
                .unlockedBy(getHasName(ModItems.TEST_RAW), has(ModItems.TEST_RAW))
                .unlockedBy(getHasName(Blocks.STONE), has(Blocks.STONE))
                .save(recipeOutput);
    }

    public static final List<ItemLike> TEST_ITEM1 = List.of(ModItems.TEST_RAW, ModBlocks.TEST_ORE);

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
