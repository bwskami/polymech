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
 * 燃气涡轮发电机：消耗燃气向电网发电。
 * <p>
 * 布局：无物品槽位；储罐 0=燃气输入。
 * 注意：燃气流体尚未添加，配方表暂为空，机器框架与 UI 已就绪。
 * </p>
 */
public class GasTurbineGeneratorBlockEntity extends AbstractTurbineGeneratorBlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public static final int TANK_GAS = 0;

    public GasTurbineGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GAS_TURBINE_GENERATOR.get(), pos, state,
                ModRecipeTypes.GAS_TURBINE_GENERATOR.type(), 100);
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
        return Component.translatable("block.poly_mech.gas_turbine_generator");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }
}
