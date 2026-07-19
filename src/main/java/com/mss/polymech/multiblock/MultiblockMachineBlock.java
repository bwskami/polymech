
package com.mss.polymech.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 多方块机器基类 - 类似床的多方块结构
 * 本质上是一个占据多个方块空间的方块
 */
public abstract class MultiblockMachineBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<PartType> PART = EnumProperty.create("part", PartType.class);

    public enum PartType {
        MAIN,   // 主部分（控制器）
        SECONDARY // 次要部分
    }

    public MultiblockMachineBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, PartType.MAIN));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    /**
     * 获取该多方块机器占用的所有方块位置
     * @param pos 机器的主位置
     * @param facing 机器的朝向
     * @return 占用的所有方块位置列表
     */
    public abstract List<BlockPos> getOccupiedPositions(BlockPos pos, Direction facing);

    /**
     * 获取该多方块机器的完整碰撞箱
     */
    public abstract VoxelShape getFullShape(Direction facing);

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        if (state.getValue(PART) == PartType.MAIN) {
            return getFullShape(facing);
        } else {
            // 次要部分使用空形状，防止重复碰撞检测
            return Shapes.empty();
        }
    }

    /**
     * 获取该多方块机器的边界框
     */
    public abstract AABB getMultiblockBounds(BlockPos pos, Direction facing);

    /**
     * 检查是否可以在指定位置放置此多方块机器
     */
    public boolean canPlaceAt(Level level, BlockPos pos, Direction facing) {
        List<BlockPos> occupiedPositions = getOccupiedPositions(pos, facing);

        for (BlockPos occupiedPos : occupiedPositions) {
            if (!level.isEmptyBlock(occupiedPos)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection();

        // 检查是否可以放置
        if (!canPlaceAt(context.getLevel(), pos, facing)) {
            return null; // 不能放置
        }

        return this.defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, PartType.MAIN);
    }

    /**
     * 放置多方块机器
     */
    public boolean placeMultiblock(Level level, BlockPos mainPos, Direction facing) {
        if (!canPlaceAt(level, mainPos, facing)) {
            return false;
        }

        List<BlockPos> occupiedPositions = getOccupiedPositions(mainPos, facing);

        for (int i = 0; i < occupiedPositions.size(); i++) {
            BlockPos occupiedPos = occupiedPositions.get(i);
            BlockState stateToPlace;

            if (i == 0) {
                // 主部分
                stateToPlace = this.defaultBlockState()
                        .setValue(FACING, facing)
                        .setValue(PART, PartType.MAIN);
            } else {
                // 次要部分
                stateToPlace = this.defaultBlockState()
                        .setValue(FACING, facing)
                        .setValue(PART, PartType.SECONDARY);
            }

            level.setBlock(occupiedPos, stateToPlace, 3);

            // 如果是主部分，创建BlockEntity
            if (i == 0) {
                BlockEntity blockEntity = createBlockEntity(occupiedPos, stateToPlace);
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                }
            }
        }

        return true;
    }

    /**
     * 创建BlockEntity（仅在主部分）
     */
    protected abstract BlockEntity createBlockEntity(BlockPos pos, BlockState state);

    /**
     * 破坏多方块机器（全部）
     */
    public void breakMultiblock(Level level, BlockPos masterPos, Direction facing) {
        List<BlockPos> occupiedPositions = getOccupiedPositions(masterPos, facing);

        // 先处理掉落物（从主部分）
        BlockPos mainPos = occupiedPositions.get(0);
        popResource(level, mainPos, getCloneItemStack(level, mainPos, level.getBlockState(mainPos)));

        // 然后移除所有部分
        for (BlockPos occupiedPos : occupiedPositions) {
            level.removeBlockEntity(occupiedPos);
            level.setBlock(occupiedPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * 当多方块机器的任何一个部分被破坏时调用
     */
    public void onPartBroken(Level level, BlockPos pos, BlockState state) {
        // 获取机器的主位置
        BlockPos masterPos = getMasterPos(level, pos, state);
        Direction facing = state.getValue(FACING);

        // 整体破坏
        breakMultiblock(level, masterPos, facing);
    }

    /**
     * 获取主位置
     */
    protected BlockPos getMasterPos(Level level, BlockPos pos, BlockState state) {
        // 根据占用位置查找主位置
        Direction facing = state.getValue(FACING);
        List<BlockPos> occupiedPositions = getOccupiedPositions(pos, facing);
        return occupiedPositions.get(0); // 第一个位置通常是主位置
    }

    @Override
    public void destroy(Level level, BlockPos pos, BlockState state) {
        if (state.getValue(PART) == PartType.MAIN) {
            // 如果是主部分被破坏，整体摧毁机器
            onPartBroken(level, pos, state);
        } else {
            // 如果是次要部分被破坏，找到主部分并摧毁整个机器
            onPartBroken(level, pos, state);
        }

        super.destroy(level, pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // 当玩家右键点击时，打开机器GUI或其他交互
        return handleInteraction(state, level, pos, player, hand, hit);
    }

    /**
     * 处理玩家交互
     */
    protected abstract InteractionResult handleInteraction(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit);
}