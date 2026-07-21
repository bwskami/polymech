package com.mss.polymech.block.entity.large;

import com.mss.polymech.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;
import org.jetbrains.annotations.NotNull;

public class HorizontalSteamBoilerBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public HorizontalSteamBoilerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HORIZONTAL_STEAM_BOILER.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 暂时没有动画
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
