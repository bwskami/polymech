package com.mss.polymech.machine;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class BaseMachineBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

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

    public abstract Vec3i[] getSideOffsets();

    public Vec3i[][] getFillRegions() {
        return null;
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

    private static Vec3i rotateVec3i(Vec3i offset, Direction facing) {
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
