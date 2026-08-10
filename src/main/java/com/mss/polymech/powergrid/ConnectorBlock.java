package com.mss.polymech.powergrid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 电气连接器。
 * <p>
 * 可贴在任意面的小挂墙连接端子（模型移植自Create-Electro-Energetics），
 * 提供单个电气节点（接线点），是电网中最基本的接入点。
 * 节点位置位于碰撞盒顶面处，电线从该点向外拉出。
 * </p>
 * <p>
 * 支持像原版海泡菜一样在同一格内堆叠多个（count=1~4，默认1）：
 * 手持连接器对已放置的连接器右键即可在格内增加数量，每个个体都是独立的
 * 电气节点（nodeId=个体序号），可分别接线；破坏时整格掉落对应数量。
 * </p>
 */
public class ConnectorBlock extends Block implements GridNodeBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 格内个体数量（1~4，同原版海泡菜上限） */
    public static final IntegerProperty COUNT = IntegerProperty.create("count", 1, 4);

    /** 格内可堆叠的最大数量 */
    public static final int MAX_COUNT = 4;

    /** 个体布局偏移：h=沿墙面横向，v=沿墙面纵向（单位格，以格中心为原点） */
    private record Offset(double h, double v) {}

    /** 各数量下的个体布局（与模型文件 connector_2/3/4 一一对应，nodeId 顺序=模型 groups 顺序；
     *  偏移 = (模型个体中心 - 8) / 16，Blockbench 16×16×16 方块中心为 (8,8,8)） */
    private static final Offset[][] OFFSETS = {
            { new Offset(0, 0) },                                                // 1：居中
            { new Offset(0.25, 0), new Offset(-0.25, 0) },                       // 2：水平左右（模型中心 12/4）
            { new Offset(-0.25, -0.25), new Offset(0.25, -0.25),
              new Offset(0, 0.25) },                                             // 3：左下+右下+上中（模型中心 (4,4)/(12,4)/(8,12)）
            { new Offset(-0.3125, -0.3125), new Offset(-0.3125, 0.3125),
              new Offset(0.3125, 0.3125), new Offset(0.3125, -0.3125) }          // 4：2×2 四角（模型中心 3/13）
    };

    /** 个体半宽（格）：模型个体为 6.5 单位宽的方块，半宽 3.25/16 */
    private static final double HALF = 0.203125;
    /** 个体沿墙伸出深度（格）：模型 y 0~13 单位 */
    private static final double DEPTH = 0.8125;

    /** 各朝向×数量下的碰撞形状缓存 */
    private static final Map<Direction, VoxelShape[]> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction dir : Direction.values()) {
            VoxelShape[] perCount = new VoxelShape[MAX_COUNT];
            for (int c = 1; c <= MAX_COUNT; c++) {
                VoxelShape shape = Shapes.empty();
                for (Offset off : OFFSETS[c - 1]) {
                    shape = Shapes.or(shape, individual(dir, off));
                }
                perCount[c - 1] = shape.optimize();
            }
            SHAPES.put(dir, perCount);
        }
    }

    public ConnectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.UP).setValue(COUNT, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COUNT);
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext useContext) {
        // 同原版海泡菜：手持本方块物品右键已有方块时，允许在格内合并堆叠
        return useContext.getItemInHand().is(this.asItem()) && state.getValue(COUNT) < MAX_COUNT;
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
        if (existing.is(this) && existing.getValue(COUNT) < MAX_COUNT) {
            return existing.setValue(COUNT, existing.getValue(COUNT) + 1);
        }
        return defaultBlockState().setValue(FACING, context.getClickedFace()).setValue(COUNT, 1);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING))[state.getValue(COUNT) - 1];
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(BlockState state) {
        int count = state.getValue(COUNT);
        Direction facing = state.getValue(FACING);
        Map<Integer, Vec3> nodes = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            nodes.put(i, nodeAt(facing, OFFSETS[count - 1][i]));
        }
        return Map.copyOf(nodes);
    }

    /** 方块被破坏（或被替换）时，从电网中移除该格全部个体的电线连接 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            WorldPowerGrid.get((net.minecraft.server.level.ServerLevel) level).removeNode(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 单个体的碰撞盒（个体中心 0.5+off，模型空间坐标按 facing 旋转映射到世界坐标） */
    private static VoxelShape individual(Direction facing, Offset off) {
        double x1 = 0.5 + off.h() - HALF;
        double x2 = 0.5 + off.h() + HALF;
        double z1 = 0.5 + off.v() - HALF;
        double z2 = 0.5 + off.v() + HALF;
        return switch (facing) {
            case UP    -> Shapes.box(x1, 0.0, z1, x2, DEPTH, z2);
            case DOWN  -> Shapes.box(x1, 1.0 - DEPTH, z1, x2, 1.0, z2);
            case NORTH -> Shapes.box(x1, z1, 1.0 - DEPTH, x2, z2, 1.0);
            case SOUTH -> Shapes.box(1.0 - x2, z1, 0.0, 1.0 - x1, z2, DEPTH);
            case EAST  -> Shapes.box(0.0, z1, x1, DEPTH, z2, x2);
            case WEST  -> Shapes.box(1.0 - DEPTH, z1, 1.0 - x2, 1.0, z2, 1.0 - x1);
        };
    }

    /** 个体节点位置（模型空间接线点 (0.5, 0.65, 0.5)+偏移 → 世界坐标） */
    private static Vec3 nodeAt(Direction facing, Offset off) {
        double x = 0.5 + off.h();
        double y = 0.65;
        double z = 0.5 + off.v();
        return switch (facing) {
            case UP    -> new Vec3(x, y, z);
            case DOWN  -> new Vec3(x, 1.0 - y, 1.0 - z);
            case NORTH -> new Vec3(x, z, 1.0 - y);
            case SOUTH -> new Vec3(1.0 - x, z, y);
            case EAST  -> new Vec3(y, z, x);
            case WEST  -> new Vec3(1.0 - y, z, 1.0 - x);
        };
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
