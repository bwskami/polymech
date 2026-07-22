package com.mss.polymech.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

public class FillingUnitRecipe implements Recipe<RecipeInput> {

    private final ItemStack result;
    private final NonNullList<Ingredient> ingredients;
    private final int craftTime;

    public FillingUnitRecipe(ItemStack result, NonNullList<Ingredient> ingredients, int craftTime) {
        this.result = result;
        this.ingredients = ingredients;
        this.craftTime = craftTime;
    }

    @Override
    public boolean matches(RecipeInput container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(RecipeInput container, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) { return true; }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) { return result; }

    @Override
    public RecipeSerializer<?> getSerializer() { return null; }

    @Override
    public RecipeType<?> getType() { return null; }
}
