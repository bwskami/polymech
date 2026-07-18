package com.mss.polymech.block.entity;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.FluidSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mojang.serialization.MapCodec;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class FluidTankBlock extends BaseEntityBlock implements BlockUIMenuType.BlockUI {
    public static final MapCodec<FluidTankBlock> CODEC = simpleCodec(FluidTankBlock::new);

    public FluidTankBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FluidTankBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level instanceof ServerLevel) {
            return createTickerHelper(type, ModBlockEntities.FLUID_TANK.get(), FluidTankBlockEntity::serverTick);
        }
        return null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockUIMenuType.openUI((net.minecraft.server.level.ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        FluidTankBlockEntity be = (FluidTankBlockEntity) Objects.requireNonNull(holder.player.level()).getBlockEntity(holder.pos);

        var root = new UIElement();
        root.layout(l -> l.width(176).paddingAll(7).gapAll(4));
        root.addClass("panel_bg");

        root.addChildren(
                new Label().setText(Component.translatable("block.poly_mech.fluid_tank")),

                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4)).addChildren(
                        new FluidSlot().bind(be.getFluidHandler(), 0)
                                .layout(l -> l.width(36).height(54)),

                        new UIElement().layout(l -> l.gapAll(2)).addChildren(
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    var fluid = be.getFluidStack();
                                    return fluid.isEmpty() ? Component.literal("空") : fluid.getHoverName();
                                }).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    var fluid = be.getFluidStack();
                                    if (fluid.isEmpty()) return Component.literal("");
                                    return Component.literal(BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString());
                                }).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    int amount = be.getFluidAmount();
                                    int capacity = FluidTankBlockEntity.CAPACITY;
                                    int percent = capacity > 0 ? (int) ((float) amount / capacity * 100) : 0;
                                    if (amount >= 10000) return Component.literal(percent + "% (" + (amount / 1000) + "B)");
                                    return Component.literal(percent + "% (" + amount + "mB)");
                                }).build())
                        )
                ),

                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4)).addChildren(
                        new ItemSlot().bind(be.getBucketHandler(), 0),
                        new ItemSlot().bind(be.getBucketHandler(), 1)
                ),

                new InventorySlots()
        );

        return ModularUI.of(UI.of(root, StylesheetManager.MC), holder.player);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof FluidTankBlockEntity tank)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (FluidUtil.interactWithFluidHandler(player, hand, tank.getFluidHandler())) {
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {

        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
