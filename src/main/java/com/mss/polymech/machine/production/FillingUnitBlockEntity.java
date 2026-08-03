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
 * 灌装机：电力驱动（空容器+流体→满容器）。
 * <p>
 * 布局：槽位 0=空容器输入, 1=满容器输出；储罐 0=流体输入。
 * 耗电量由配方的 power_per_tick 声明。
 * </p>
 */
public class FillingUnitBlockEntity extends AbstractProcessingBlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    public static final int TANK_INPUT = 0;

    public FillingUnitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FILLING_UNIT.get(), pos, state,
                ModRecipeTypes.FILLING_UNIT.type(), 80);
    }

    // -- 布局声明 --
    @Override public int[] getInputSlots() { return new int[]{INPUT_SLOT}; }
    @Override public int[] getOutputSlots() { return new int[]{OUTPUT_SLOT}; }
    @Override protected int getInvSize() { return 2; }
    @Override protected int getTankCount() { return 1; }
    @Override protected int getTankCapacity(int index) { return 8000; }

    // -- 动力：电力机器，走基类储电流程（hasFuelPower 默认 false） --

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
        return Component.translatable("block.poly_mech.filling_unit");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }
}
