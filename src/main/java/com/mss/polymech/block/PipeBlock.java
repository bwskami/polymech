package com.mss.polymech.block;

import com.mss.polymech.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class PipeBlock extends Block {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    private static final VoxelShape CORE_SHAPE = Block.box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape NORTH_ARM = Block.box(5, 5, 0, 11, 11, 5);
    private static final VoxelShape SOUTH_ARM = Block.box(5, 5, 11, 11, 11, 16);
    private static final VoxelShape EAST_ARM = Block.box(11, 5, 5, 16, 11, 11);
    private static final VoxelShape WEST_ARM = Block.box(0, 5, 5, 5, 11, 11);
    private static final VoxelShape UP_ARM = Block.box(5, 11, 5, 11, 16, 11);
    private static final VoxelShape DOWN_ARM = Block.box(5, 0, 5, 11, 5, 11);

    public PipeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(EAST, false)
                .setValue(WEST, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity instanceof Player player) {
                if (player.getMainHandItem().is(ModItems.WRENCH.get())
                        || player.getOffhandItem().is(ModItems.WRENCH.get())) {
                    return Block.box(0, 0, 0, 16, 16, 16);
                }
            }
        }
        return getPipeShape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getPipeShape(state);
    }

    private VoxelShape getPipeShape(BlockState state) {
        VoxelShape shape = CORE_SHAPE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, NORTH_ARM);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, SOUTH_ARM);
        if (state.getValue(EAST)) shape = Shapes.or(shape, EAST_ARM);
        if (state.getValue(WEST)) shape = Shapes.or(shape, WEST_ARM);
        if (state.getValue(UP)) shape = Shapes.or(shape, UP_ARM);
        if (state.getValue(DOWN)) shape = Shapes.or(shape, DOWN_ARM);
        return shape;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !oldState.is(this)) {
            for (Direction dir : Direction.values()) {
                if (canConnect(level, pos, dir)) {
                    setConnection(level, pos, dir, true);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide() && state.is(this) && !newState.is(this)) {
            for (Direction dir : Direction.values()) {
                if (state.getValue(getProperty(dir))) {
                    BlockPos neighborPos = pos.relative(dir);
                    BlockState neighborState = level.getBlockState(neighborPos);
                    if (neighborState.getBlock() instanceof PipeBlock) {
                        neighborState = neighborState.setValue(getProperty(dir.getOpposite()), false);
                        level.setBlock(neighborPos, neighborState, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean isMoving) {
        if (level.isClientSide()) return;

        Direction dir = getDirection(pos, neighborPos);
        if (dir == null) return;

        BlockState neighborState = level.getBlockState(neighborPos);
        boolean neighborIsPipe = neighborState.getBlock() instanceof PipeBlock;
        boolean connected = state.getValue(getProperty(dir));

        if (neighborIsPipe && !connected) {
            setConnection(level, pos, dir, true);
        } else if (!neighborIsPipe && connected) {
            setConnection(level, pos, dir, false);
        }
    }

    private void setConnection(Level level, BlockPos pos, Direction dir, boolean connected) {
        BlockState state = level.getBlockState(pos);
        BooleanProperty prop = getProperty(dir);
        if (state.getValue(prop) == connected) return;

        level.setBlock(pos, state.setValue(prop, connected), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);

        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.getBlock() instanceof PipeBlock) {
            BooleanProperty neighborProp = getProperty(dir.getOpposite());
            if (neighborState.getValue(neighborProp) != connected) {
                level.setBlock(neighborPos, neighborState.setValue(neighborProp, connected),
                        Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            }
        }
    }

    private static BooleanProperty getProperty(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    @org.jetbrains.annotations.Nullable
    private static Direction getDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        for (Direction dir : Direction.values()) {
            if (dir.getStepX() == dx && dir.getStepY() == dy && dir.getStepZ() == dz) {
                return dir;
            }
        }
        return null;
    }

    private boolean canConnect(Level level, BlockPos pos, Direction direction) {
        BlockPos neighborPos = pos.relative(direction);
        BlockState neighborState = level.getBlockState(neighborPos);
        return neighborState.getBlock() instanceof PipeBlock;
    }
}
