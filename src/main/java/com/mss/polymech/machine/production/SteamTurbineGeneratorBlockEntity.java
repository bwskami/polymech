package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.recipe.ModRecipeTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 蒸汽涡轮发电机：消耗蒸汽向电网发电。
 * <p>
 * 布局：无物品槽位；储罐 0=蒸汽输入。
 * 配方声明：fluid_inputs 蒸汽 + duration（周期长度）+ power_per_tick（发电量/t）。
 * </p>
 */
public class SteamTurbineGeneratorBlockEntity extends AbstractTurbineGeneratorBlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public static final int TANK_STEAM = 0;

    public SteamTurbineGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_TURBINE_GENERATOR.get(), pos, state,
                ModRecipeTypes.STEAM_TURBINE_GENERATOR.type(), 100);
    }

    // -- 布局声明：纯流体机器 --
    @Override public int[] getInputSlots() { return new int[0]; }
    @Override public int[] getOutputSlots() { return new int[0]; }
    @Override protected int getInvSize() { return 0; }
    @Override protected int getTankCount() { return 1; }
    @Override protected int getTankCapacity(int index) { return 8000; }

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
        return Component.translatable("block.poly_mech.steam_turbine_generator");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }
}
