package com.mss.polymech.powergrid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 混凝土电杆。
 * <p>
 * 高耸的输电线支撑杆（模型移植自Create-Electro-Energetics），
 * 纯支撑结构，本身不提供电气节点，电线需经连接器等节点设备接入。
 * </p>
 */
public class ConcretePoleBlock extends Block {

    private static final VoxelShape SHAPE = Shapes.box(0.25, 0.0, 0.25, 0.75, 1.0, 0.75);

    public ConcretePoleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
