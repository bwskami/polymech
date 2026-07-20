package com.mss.polymech.block.large;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * 多方块机器的占位方块
 * 透明且不可见，但具有交互转发功能
 */
public class MultiblockPlaceholder extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public MultiblockPlaceholder(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    
    // 默认构造函数，使用默认属性
    public MultiblockPlaceholder() {
        this(Properties.of().mapColor(MapColor.NONE).noOcclusion().noLootTable().air());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE; // 不渲染
    }

    // 占位方块没有碰撞箱，允许玩家穿过
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    // 交互转发到主方块
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return forwardInteractionToMaster(level, pos, player, hitResult);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        return forwardItemInteractionToMaster(stack, level, pos, player, hand, hitResult);
    }

    private InteractionResult forwardInteractionToMaster(Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos masterPos = findMasterBlock(level, pos);
        if (masterPos != null) {
            BlockState masterState = level.getBlockState(masterPos);
            return masterState.useWithoutItem(level, player, hitResult.withPosition(masterPos));
        }
        return InteractionResult.PASS;
    }

    private ItemInteractionResult forwardItemInteractionToMaster(ItemStack stack, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        BlockPos masterPos = findMasterBlock(level, pos);
        if (masterPos != null) {
            BlockState masterState = level.getBlockState(masterPos);
            return masterState.useItemOn(stack, level, player, hand, hitResult.withPosition(masterPos));
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    // 查找关联的主方块
    private BlockPos findMasterBlock(Level level, BlockPos placeholderPos) {
        Direction facing = level.getBlockState(placeholderPos).getValue(FACING);

        // 搜索附近的主方块，限制搜索范围
        int searchRadius = 10; // 可配置的搜索半径
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    BlockPos searchPos = placeholderPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(searchPos);

                    if (state.getBlock() instanceof AbstractMultiblockMachine multiBlock) {
                        MultiblockStructure structure = multiBlock.createStructure(facing.getOpposite());
                        if (structure.contains(placeholderPos.subtract(searchPos))) {
                            return searchPos;
                        }
                    }
                }
            }
        }
        return null;
    }

    public static final MapCodec<MultiblockPlaceholder> CODEC = simpleCodec(MultiblockPlaceholder::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null; // 占位方块不需要BlockEntity
    }
}