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
 * 蒸汽锤：蒸汽驱动锻压（锭→板）。
 * <p>
 * 布局：槽位 0=输入, 1=输出；储罐 0=蒸汽输入。
 * 蒸汽消耗通过配方的流体输入声明实现（参考 GTM SteamEnergyRecipeHandler 思路）。
 * </p>
 */
public class SteamHammerBlockEntity extends AbstractProcessingBlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    public static final int TANK_STEAM = 0;

    public SteamHammerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_HAMMER.get(), pos, state,
                ModRecipeTypes.STEAM_HAMMER.type(), 100);
    }

    // -- 布局声明 --
    @Override public int[] getInputSlots() { return new int[]{INPUT_SLOT}; }
    @Override public int[] getOutputSlots() { return new int[]{OUTPUT_SLOT}; }
    @Override protected int getInvSize() { return 2; }
    @Override protected int getTankCount() { return 1; }
    @Override protected int getTankCapacity(int index) { return 8000; }

    // -- 动力：蒸汽经配方流体输入消耗，跳过电力检查 --
    @Override protected boolean hasFuelPower() { return true; }

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
        return Component.translatable("block.poly_mech.steam_hammer");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }
}
