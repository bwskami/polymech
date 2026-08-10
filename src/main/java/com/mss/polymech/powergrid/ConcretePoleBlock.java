package com.mss.polymech.powergrid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * 混凝土电杆。
 * <p>
 * 高耸的输电线支撑杆（模型移植自Create-Electro-Energetics），
 * 顶部提供单个电气节点，适合架设跨距离的输电线路。
 * </p>
 */
public class ConcretePoleBlock extends Block implements GridNodeBlock {

    /** 节点本地坐标：电杆顶部 */
    private static final Map<Integer, Vec3> NODES = Map.of(0, new Vec3(0.5, 0.875, 0.5));

    private static final VoxelShape SHAPE = Shapes.box(0.25, 0.0, 0.25, 0.75, 1.0, 0.75);

    public ConcretePoleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(BlockState state) {
        return NODES;
    }

    /** 方块被破坏（或被替换）时，从电网中移除该节点的全部电线连接 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            WorldPowerGrid.get((net.minecraft.server.level.ServerLevel) level).removeNode(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
