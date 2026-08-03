package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.recipe.MachineRecipe;
import com.mss.polymech.recipe.ModRecipeTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * 火焰反射炉：燃料驱动熔炼。
 * <p>
 * 特殊实现：不读取自有配方表，而是在运行时代理原版熔炼配方
 * （参考 GTM MULTI_SMELTER 的 proxyRecipes 思路），
 * 将匹配到的熔炼配方包装为 {@link MachineRecipe} 走统一加工流程。
 * </p>
 * <p>
 * 布局：槽位 0=输入, 1=输出；无储罐。
 * </p>
 */
public class FlameReverberatoryFurnaceBlockEntity extends AbstractProcessingBlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;

    public FlameReverberatoryFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FLAME_REVERBERATORY_FURNACE.get(), pos, state,
                ModRecipeTypes.FLAME_REVERBERATORY_FURNACE.type(), 200);
    }

    // -- 布局声明 --
    @Override public int[] getInputSlots() { return new int[]{INPUT_SLOT}; }
    @Override public int[] getOutputSlots() { return new int[]{OUTPUT_SLOT}; }
    @Override protected int getInvSize() { return 2; }

    // -- 动力：燃料驱动（熔炉风格），跳过电力检查 --
    @Override protected boolean hasFuelPower() { return true; }
    @Override protected int getPowerCostPerTick() { return 0; }

    /**
     * 代理原版熔炼配方：按输入槽物品查找可熔炼配方，
     * 包装为合成 MachineRecipe（耗时取熔炼时长，无能耗、无流体）。
     */
    @Override
    @Nullable
    protected MachineRecipe findRecipe(Level world) {
        if (world == null) return null;
        if (lastRecipe != null && lastRecipe.matches(buildInput(), world)) {
            return lastRecipe;
        }
        var inputStack = itemStackHandler.getStackInSlot(INPUT_SLOT);
        if (inputStack.isEmpty()) return null;
        for (RecipeHolder<SmeltingRecipe> holder :
                world.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
            SmeltingRecipe cooking = holder.value();
            var ingredients = cooking.getIngredients();
            if (ingredients.isEmpty() || !ingredients.get(0).test(inputStack)) continue;
            var result = cooking.getResultItem(world.registryAccess());
            if (result.isEmpty()) continue;
            lastRecipe = new MachineRecipe(recipeType,
                    List.of(new SizedIngredient(ingredients.get(0), 1)),
                    List.of(),
                    List.of(result.copy()),
                    List.of(),
                    cooking.getCookingTime(), 0, false);
            return lastRecipe;
        }
        return null;
    }

    // -- GeckoLib 动画 --
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0,
                state -> state.setAndContinue(RawAnimation.begin().thenLoop("working"))));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.flame_reverberatory_furnace");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }
}
