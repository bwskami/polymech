package com.mss.polymech.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public abstract class BaseIOSideBlockEntity extends BlockEntity {

    private BlockPos parentPos;

    public BaseIOSideBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public BlockPos getParentPos() { return parentPos; }
    public void setParentPos(BlockPos pos) { this.parentPos = pos; }

    @Nullable
    public <T extends BlockEntity> T getParentBlock() {
        if (level == null || parentPos == null) return null;
        BlockEntity be = level.getBlockEntity(parentPos);
        try {
            return (T) be;
        } catch (ClassCastException e) {
            return null;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (parentPos != null) {
            tag.putLong("ParentPos", parentPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ParentPos")) {
            parentPos = BlockPos.of(tag.getLong("ParentPos"));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (parentPos != null) {
            tag.putLong("ParentPos", parentPos.asLong());
        }
        return tag;
    }
}
