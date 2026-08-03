package com.mss.polymech.machine.common;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * 大型机器主方块（配置驱动），不再需要子类。
 * <p>
 * 所有机器特定的行为（侧面偏移、填充区域、方块实体创建等）
 * 均通过 {@link MachineConfig} 注入，彻底消除 Block 子类样板代码。
 * </p>
 */
public class LargeMachineBlock extends BaseMachineBlock {

    private final MachineConfig config;
    private final MapCodec<LargeMachineBlock> codec;

    public LargeMachineBlock(MachineConfig config) {
        super(config.blockProperties());
        this.config = config;
        // 使用 config.blockFactory() 以支持子类重建（子类重写偏移时，codec 会调用其构造函数）
        this.codec = simpleCodec(p -> config.blockFactory().apply(config));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return codec;
    }

    // ========== 从 MachineConfig 读取机器属性 ==========

    @Override
    public DeferredBlock<?> getSideBlock() {
        return config.sideBlock();
    }

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        MachineRegistry.MachineEntry entry = MachineRegistry.getEntry(config.id());
        return entry != null && entry.mainBlockEntity() != null ? entry.mainBlockEntity().get() : null;
    }

    @Nullable
    @Override
    public BlockEntityType<?> getSideBlockEntityType() {
        MachineRegistry.MachineEntry entry = MachineRegistry.getEntry(config.id());
        return entry != null && entry.sideBlockEntity() != null ? entry.sideBlockEntity().get() : null;
    }

    @Override
    public Vec3i[] getSideOffsets() {
        return config.sideOffsets();
    }

    @Override
    public Vec3i[][] getFillRegions() {
        return config.fillRegions();
    }

    // ========== 方块实体 ==========

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return config.blockEntityFactory().create(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        MachineRegistry.MachineEntry entry = MachineRegistry.getEntry(config.id());
        if (entry != null && entry.mainBlockEntity() != null) {
            BlockEntityType<?> expectedType = entry.mainBlockEntity().get();
            return createTickerHelper(type, (BlockEntityType) expectedType,
                    (level1, pos1, state1, be1) ->
                            BaseIOBlockEntity.tick(level1, pos1, state1, (BaseIOBlockEntity) be1));
        }
        return null;
    }

    // ========== 通用大型机器行为 ==========

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOBlockEntity machineBE) {
                player.openMenu(machineBE, pos);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            
            // 收集所有需要放置的侧面位置
            Set<BlockPos> sidePositions = new HashSet<>();
            
            // 1. 处理离散偏移
            Vec3i[] rawOffsets = getSideOffsets();
            if (rawOffsets != null) {
                for (Vec3i rawOffset : rawOffsets) {
                    Vec3i rotated = BaseMachineBlock.rotateVec3i(rawOffset, facing);
                    sidePositions.add(pos.offset(rotated));
                }
            }
            
            // 2. 处理填充区域（角点填充）
            Vec3i[][] fillRegions = getFillRegions();
            if (fillRegions != null) {
                for (Vec3i[] region : fillRegions) {
                    Vec3i min = region[0];
                    Vec3i max = region[1];
                    for (int x = Math.min(min.getX(), max.getX()); x <= Math.max(min.getX(), max.getX()); x++) {
                        for (int y = Math.min(min.getY(), max.getY()); y <= Math.max(min.getY(), max.getY()); y++) {
                            for (int z = Math.min(min.getZ(), max.getZ()); z <= Math.max(min.getZ(), max.getZ()); z++) {
                                Vec3i rotated = BaseMachineBlock.rotateVec3i(new Vec3i(x, y, z), facing);
                                sidePositions.add(pos.offset(rotated));
                            }
                        }
                    }
                }
            }
            
            // 放置所有侧面方块（排除主方块自身位置）
            sidePositions.remove(pos);
            for (BlockPos sidePos : sidePositions) {
                BlockState sideState = getSideBlock().get().defaultBlockState()
                        .setValue(FACING, facing);
                level.setBlock(sidePos, sideState, Block.UPDATE_ALL);
                BlockEntity be = level.getBlockEntity(sidePos);
                if (be instanceof BaseIOSideBlockEntity sideBE) {
                    sideBE.setParentPos(pos);
                    sideBE.setChanged();
                }
                level.sendBlockUpdated(sidePos, sideState, sideState, Block.UPDATE_ALL);
            }
            
            // 同步主方块
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            BlockEntity mainBE = level.getBlockEntity(pos);
            if (mainBE != null) {
                mainBE.setChanged();
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOBlockEntity machineBE) {
                Containers.dropContents(level, pos, machineBE.getItems());
            }
            BlockPos[] sidePositions = getSidePositions(state, pos);
            for (BlockPos sidePos : sidePositions) {
                if (level.getBlockState(sidePos).is(getSideBlock().get())) {
                    level.destroyBlock(sidePos, false);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // 检查侧面位置是否被其他方块占用（空气和本机侧面方块都允许）
        DeferredBlock<?> sideBlock = getSideBlock();
        BlockPos[] sidePositions = getSidePositions(state, pos);
        for (BlockPos sidePos : sidePositions) {
            BlockState sideState = level.getBlockState(sidePos);
            if (!sideState.isAir() && (sideBlock == null || !sideState.is(sideBlock.get()))) {
                return false;
            }
        }
        return true;
    }
}
