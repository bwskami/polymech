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
 * 原始高炉：无动力炼钢（铁锭+煤/焦煤→钢锭+灰烬）。
 * <p>
 * 布局：槽位 0-2=输入（主料+燃料）, 3-4=输出；无储罐。
 * </p>
 */
public class PrimitiveBlastFurnaceBlockEntity extends AbstractProcessingBlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final int[] INPUT_SLOTS = {0, 1, 2};
    private static final int[] OUTPUT_SLOTS = {3, 4};

    public PrimitiveBlastFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRIMITIVE_BLAST_FURNACE.get(), pos, state,
                ModRecipeTypes.PRIMITIVE_BLAST_FURNACE.type(), 600);
    }

    // -- 布局声明 --
    @Override public int[] getInputSlots() { return INPUT_SLOTS; }
    @Override public int[] getOutputSlots() { return OUTPUT_SLOTS; }
    @Override protected int getInvSize() { return 5; }

    // -- 动力：无动力机器 --
    @Override protected boolean hasFuelPower() { return true; }
    @Override protected int getPowerCostPerTick() { return 0; }

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
        return Component.translatable("block.poly_mech.primitive_blast_furnace");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }
}
