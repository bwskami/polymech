package com.mss.polymech.block.entity.large;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.util.GeoModelShapeUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HorizontalSteamBoilerBlock extends BaseEntityBlock {

    public static final MapCodec<HorizontalSteamBoilerBlock> CODEC = simpleCodec(props -> new HorizontalSteamBoilerBlock());

    // 结构布局定义（坐标以核心方块B为原点(0,0,0)）
    // X轴：东西方向（东+X）
    // Y轴：垂直方向（上+Y）
    // Z轴：南北方向（南+Z）
    public static final LargeBlockStructure STRUCTURE = new LargeBlockStructure(
        new char[][][]{
            // Y=0 层（底层）
            {
                "AAAAA".toCharArray(),
                "AABAA".toCharArray(),
                "AAAAA".toCharArray()
            },
            // Y=1 层
            {
                "AAAAA".toCharArray(),
                "CAAAA".toCharArray(),
                "AACAA".toCharArray()
            },
            // Y=2 层
            {
                "AAAAA".toCharArray(),
                "AAAAA".toCharArray(),
                "AAAAA".toCharArray()
            },
            // Y=3 层（顶层）
            {
                "     ".toCharArray(),
                "A E A".toCharArray(),
                "     ".toCharArray()
            }
        },
        'B'
    );

    private static final GeoModelShapeUtils.ModelShapeData shapeData;
    private static final VoxelShape[] shapesByFacing = new VoxelShape[4];

    static {
        GeoModelShapeUtils.ModelShapeData data = null;
        try (var is = HorizontalSteamBoilerBlock.class.getResourceAsStream(
                "/assets/poly_mech/geo/block/horizontal_steam_boiler.geo.json")) {
            if (is != null) {
                data = GeoModelShapeUtils.parseGeoModelStream(is);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        shapeData = data;
        if (data != null) {
            for (int i = 0; i < 4; i++) {
                shapesByFacing[i] = GeoModelShapeUtils.buildShape(data, i);
            }
        }
    }

    public HorizontalSteamBoilerBlock() {
        super(Properties.of()
                .strength(3.5F, 4.8F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .dynamicShape());
        registerDefaultState(this.stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new HorizontalSteamBoilerBlockEntity(pos, state);
    }

    @NotNull
    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    public VoxelShape getShapeForFacing(Direction facing) {
        if (shapeData == null) return Block.box(-16, 0, -16, 32, 32, 32);
        return shapesByFacing[GeoModelShapeUtils.facingToRotationSteps(facing)];
    }

    @NotNull
    @Override
    public VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return getShapeForFacing(facing);
    }

    @NotNull
    @Override
    public VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        VoxelShape fullShape = getShapeForFacing(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        // 只返回主方块1×1×1空间内的形状
        VoxelShape unitBox = Block.box(0, 0, 0, 16, 16, 16);
        return Shapes.joinUnoptimized(fullShape, unitBox, BooleanOp.AND).optimize();
    }

    @Override
    public void onPlace(@NotNull BlockState state, @NotNull Level world, @NotNull BlockPos pos, @NotNull BlockState oldState, boolean isMoving) {
        super.onPlace(state, world, pos, oldState, isMoving);
        if (!world.isClientSide) {
            placeStructureBlocks(world, pos, state);
        }
    }

    private void placeStructureBlocks(Level world, BlockPos corePos, BlockState state) {
        Block placeholderBlock = ModBlocks.LARGE_BLOCK_PLACEHOLDER.get();
        BlockState placeholderState = placeholderBlock.defaultBlockState();
        
        BlockPos coreOffset = STRUCTURE.getCoreOffset();
        
        for (int y = 0; y < STRUCTURE.getHeight(); y++) {
            for (int z = 0; z < STRUCTURE.getDepth(); z++) {
                for (int x = 0; x < STRUCTURE.getWidth(); x++) {
                    LargeBlockStructure.BlockType type = STRUCTURE.getBlockType(x, y, z);
                    if (type == LargeBlockStructure.BlockType.EMPTY || type == LargeBlockStructure.BlockType.CORE) {
                        continue;
                    }
                    
                    BlockPos offsetPos = corePos.offset(x - coreOffset.getX(), y - coreOffset.getY(), z - coreOffset.getZ());
                    if (world.getBlockState(offsetPos).isAir()) {
                        world.setBlock(offsetPos, placeholderState, 3);
                        BlockEntity be = world.getBlockEntity(offsetPos);
                        if (be instanceof LargeBlockPlaceholderBlockEntity placeholder) {
                            placeholder.setMainPos(corePos);
                            placeholder.setBlockType(type);
                            placeholder.setChanged();
                            world.sendBlockUpdated(offsetPos, placeholderState, placeholderState, 3);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level world, @NotNull BlockPos pos, @NotNull BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!world.isClientSide) {
                removeBoundingBlocks(world, pos);
            }
            super.onRemove(state, world, pos, newState, isMoving);
        }
    }

    private void removeBoundingBlocks(Level world, BlockPos mainPos) {
        if (shapeData == null) return;

        int minX = shapeData.minBlockX(), maxX = shapeData.maxBlockX();
        int minY = shapeData.minBlockY(), maxY = shapeData.maxBlockY();
        int minZ = shapeData.minBlockZ(), maxZ = shapeData.maxBlockZ();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos offsetPos = mainPos.offset(x, y, z);
                    BlockState state = world.getBlockState(offsetPos);
                    if (state.getBlock() instanceof LargeBlockPlaceholder) {
                        BlockEntity be = world.getBlockEntity(offsetPos);
                        if (be instanceof LargeBlockPlaceholderBlockEntity placeholder) {
                            if (mainPos.equals(placeholder.getMainPos())) {
                                world.removeBlockEntity(offsetPos);
                                world.setBlock(offsetPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                            }
                        }
                    }
                }
            }
        }
    }
}
