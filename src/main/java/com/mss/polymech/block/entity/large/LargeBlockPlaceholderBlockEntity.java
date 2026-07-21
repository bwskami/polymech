package com.mss.polymech.block.entity.large;

import com.mss.polymech.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LargeBlockPlaceholderBlockEntity extends BlockEntity {
    
    private BlockPos mainPos;
    private LargeBlockStructure.BlockType blockType = LargeBlockStructure.BlockType.PLACEHOLDER;
    
    public LargeBlockPlaceholderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LARGE_BLOCK_PLACEHOLDER.get(), pos, state);
    }
    
    public BlockPos getMainPos() { return mainPos; }
    public void setMainPos(BlockPos pos) { this.mainPos = pos; }
    
    public LargeBlockStructure.BlockType getBlockType() { return blockType; }
    public void setBlockType(LargeBlockStructure.BlockType type) { this.blockType = type; }
    
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (mainPos != null) {
            tag.putLong("MainPos", mainPos.asLong());
        }
        tag.putString("BlockType", blockType.name());
    }
    
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("MainPos")) {
            mainPos = BlockPos.of(tag.getLong("MainPos"));
        }
        if (tag.contains("BlockType")) {
            blockType = LargeBlockStructure.BlockType.valueOf(tag.getString("BlockType"));
        }
    }
    
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (mainPos != null) {
            tag.putLong("MainPos", mainPos.asLong());
        }
        tag.putString("BlockType", blockType.name());
        return tag;
    }
    
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (tag.contains("MainPos")) {
            mainPos = BlockPos.of(tag.getLong("MainPos"));
        }
        if (tag.contains("BlockType")) {
            blockType = LargeBlockStructure.BlockType.valueOf(tag.getString("BlockType"));
        }
    }
}
