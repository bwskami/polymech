package com.mss.polymech.machine;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.machine.common.MachineRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class BaseMachineBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * 获取主方块对应的侧面方块引用。
     */
    public abstract DeferredBlock<?> getSideBlock();

    /**
     * 获取主方块实体类型。
     */
    public abstract BlockEntityType<?> getMachineBlockEntityType();

    /**
     * 获取侧面方块实体类型，用于统一的能力注册等场景。
     */
    @Nullable
    public BlockEntityType<?> getSideBlockEntityType() {
        return null;
    }

    /**
     * 获取侧面方块相对于主方块的本地坐标偏移数组。
     */
    public abstract Vec3i[] getSideOffsets();

    /**
     * 获取填充区域，返回二维数组，每个元素为{min, max}的Vec3i对。
     * 默认返回null表示无填充区域。
     */
    public Vec3i[][] getFillRegions() {
        return null;
    }

    // ========== MachineRegistry 委托 ==========

    /**
     * 根据机器ID获取对应的主方块实例。
     */
    @Nullable
    public static Block getMachineBlock(String machineId) {
        BaseMachineBlock block = MachineRegistry.getMachineBlock(machineId);
        return block;
    }

    /**
     * 获取所有已注册的大型机器ID。
     */
    public static Collection<String> getMachineIds() {
        return MachineRegistry.getMachineIds();
    }

    // ========== 通用方块行为 ==========

    protected BaseMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    public BlockPos[] getSidePositions(BlockState state, BlockPos pos) {
        Set<BlockPos> positions = new HashSet<>();
        
        Vec3i[][] regions = getFillRegions();
        if (regions != null) {
            for (Vec3i[] region : regions) {
                Vec3i min = region[0];
                Vec3i max = region[1];
                for (int x = Math.min(min.getX(), max.getX()); x <= Math.max(min.getX(), max.getX()); x++) {
                    for (int y = Math.min(min.getY(), max.getY()); y <= Math.max(min.getY(), max.getY()); y++) {
                        for (int z = Math.min(min.getZ(), max.getZ()); z <= Math.max(min.getZ(), max.getZ()); z++) {
                            Vec3i rotated = rotateVec3i(new Vec3i(x, y, z), state.getValue(FACING));
                            positions.add(pos.offset(rotated));
                        }
                    }
                }
            }
        }
        
        Vec3i[] offsets = getSideOffsets();
        if (offsets != null) {
            for (Vec3i offset : offsets) {
                Vec3i rotated = rotateVec3i(offset, state.getValue(FACING));
                positions.add(pos.offset(rotated));
            }
        }
        
        positions.remove(pos);
        return positions.toArray(new BlockPos[0]);
    }

    public static Vec3i rotateVec3i(Vec3i offset, Direction facing) {
        int x = offset.getX();
        int z = offset.getZ();
        return switch (facing) {
            case NORTH -> new Vec3i(x, offset.getY(), z);
            case SOUTH -> new Vec3i(-x, offset.getY(), -z);
            case EAST -> new Vec3i(-z, offset.getY(), x);
            case WEST -> new Vec3i(z, offset.getY(), -x);
            default -> offset;
        };
    }
}
