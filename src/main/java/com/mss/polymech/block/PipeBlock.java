package com.mss.polymech.block;

import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.api.pipenet.IMaterialPipeType;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.pipenet.WorldPipeNet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class PipeBlock extends Block {
    /**
     * 管道某一面的连接状态（三态）：
     * <ul>
     *   <li>NONE：未连接，不与邻接方块做任何流体交互，无管臂</li>
     *   <li>CONNECTED：已连接（管臂出现），管道可主动向邻接推送流体，也接受被动注入</li>
     *   <li>EXTRACT：抽取模式（管臂 + 抽取口），管道主动从邻接抽取流体，不向其推送</li>
     * </ul>
     * 管道与管道之间的连接永远是 CONNECTED/NONE 两态；EXTRACT 只用于设备/储罐侧。
     */
    public enum PipeConnection implements StringRepresentable {
        NONE("none"),
        CONNECTED("connected"),
        EXTRACT("extract");

        /** values() 缓存，避免高频调用重复分配数组 */
        public static final PipeConnection[] VALUES = values();

        private final String name;

        PipeConnection(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /**
         * 扳手切换的下一个状态。
         * 邻接是管道时只在 未连接/已连接 间切换；
         * 邻接是设备/储罐时 未连接→已连接→抽取 循环。
         */
        public PipeConnection next(boolean neighborIsPipe) {
            if (neighborIsPipe) {
                return this == NONE ? CONNECTED : NONE;
            }
            return switch (this) {
                case NONE -> CONNECTED;
                case CONNECTED -> EXTRACT;
                case EXTRACT -> NONE;
            };
        }
    }

    public static final EnumProperty<PipeConnection> NORTH = EnumProperty.create("north", PipeConnection.class);
    public static final EnumProperty<PipeConnection> SOUTH = EnumProperty.create("south", PipeConnection.class);
    public static final EnumProperty<PipeConnection> EAST = EnumProperty.create("east", PipeConnection.class);
    public static final EnumProperty<PipeConnection> WEST = EnumProperty.create("west", PipeConnection.class);
    public static final EnumProperty<PipeConnection> UP = EnumProperty.create("up", PipeConnection.class);
    public static final EnumProperty<PipeConnection> DOWN = EnumProperty.create("down", PipeConnection.class);

    /**
     * 批量铺设标记：期间新放置的管道不与任何邻接管道自动连接（包括旧管道），
     * 铺设数据包会在批量结束后统一接线（新管互连，不接旧管）。
     */
    private static boolean layingBatch = false;

    public static void setLayingBatch(boolean value) {
        layingBatch = value;
    }

    /**
     * 抽取口（input 模型）的碰撞箱：外板 [2,2,0]→[14,14,2] + 内漏斗 [3.3,3.3,1.3]→[12.7,12.7,4.7]，按方向旋转，
     * 按 Direction.get3DDataValue 索引
     */
    private static final VoxelShape[] INPUT_PLATES = new VoxelShape[]{
            Shapes.or(Block.box(2, 0, 2, 14, 2, 14), Block.box(3, 1, 3, 13, 5, 13)),     // DOWN
            Shapes.or(Block.box(2, 14, 2, 14, 16, 14), Block.box(3, 11, 3, 13, 15, 13)), // UP
            Shapes.or(Block.box(2, 2, 0, 14, 14, 2), Block.box(3, 3, 1, 13, 13, 5)),     // NORTH
            Shapes.or(Block.box(2, 2, 14, 14, 14, 16), Block.box(3, 3, 11, 13, 13, 15)), // SOUTH
            Shapes.or(Block.box(0, 2, 2, 2, 14, 14), Block.box(1, 3, 3, 5, 13, 13)),     // WEST
            Shapes.or(Block.box(14, 2, 2, 16, 14, 14), Block.box(11, 3, 3, 15, 13, 13))  // EAST
    };

    // 管道尺寸枚举
    public enum PipeSize implements IMaterialPipeType {
        SMALL("small_pipe", 6, 4, 50, 400),
        NORMAL("pipe", 5, 6, 100, 900),
        BIG("big_pipe", 4, 8, 400, 1600),
        HUGE("huge_pipe", 3, 10, 1600, 2500);

        public static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("poly_mech", "fluid_pipe");

        private final String name;
        private final int start;
        private final int width;
        private final int end;
        /** 单管基准流速（mB/t），实际流速 = 基准 × 材质倍率 */
        private final int baseThroughput;
        /** 单管流体容量（mB），与横截面积成正比 */
        private final int capacityPerPipe;
        private final VoxelShape coreShape;
        private final VoxelShape northArm;
        private final VoxelShape southArm;
        private final VoxelShape eastArm;
        private final VoxelShape westArm;
        private final VoxelShape upArm;
        private final VoxelShape downArm;

        PipeSize(String name, int start, int width, int baseThroughput, int capacityPerPipe) {
            this.name = name;
            this.start = start;
            this.width = width;
            this.end = start + width;
            this.baseThroughput = baseThroughput;
            this.capacityPerPipe = capacityPerPipe;

            this.coreShape = Block.box(start, start, start, end, end, end);
            this.northArm = Block.box(start, start, 0, end, end, start);
            this.southArm = Block.box(start, start, end, end, end, 16);
            this.eastArm = Block.box(end, start, start, 16, end, end);
            this.westArm = Block.box(0, start, start, start, end, end);
            this.upArm = Block.box(start, end, start, end, 16, end);
            this.downArm = Block.box(start, 0, start, end, start, end);
        }

        @Override
        public float getThickness() {
            return width / 16f;
        }

        @Override
        public ResourceLocation type() {
            return TYPE_ID;
        }

        @Override
        public String getName() {
            return name;
        }

        public VoxelShape getCoreShape() { return coreShape; }
        public VoxelShape getNorthArm() { return northArm; }
        public VoxelShape getSouthArm() { return southArm; }
        public VoxelShape getEastArm() { return eastArm; }
        public VoxelShape getWestArm() { return westArm; }
        public VoxelShape getUpArm() { return upArm; }
        public VoxelShape getDownArm() { return downArm; }
        public int getBaseThroughput() { return baseThroughput; }
        public int getCapacityPerPipe() { return capacityPerPipe; }

        /**
         * 实际流速（mB/t）= 尺寸基准流速 × 材质乘数，流速系统的唯一计算入口。
         */
        public int getThroughput(PipeMaterial material) {
            return Math.max(1, Math.round(baseThroughput * material.getThroughputMultiplier()));
        }

        /**
         * 按注册名查找尺寸，找不到返回 null（用于序列化反查）。
         */
        public static PipeSize byName(String name) {
            for (PipeSize s : values()) {
                if (s.name.equals(name)) return s;
            }
            return null;
        }
    }

    private final PipeSize pipeSize;
    private final PipeMaterial pipeMaterial;

    /**
     * 形状缓存（性能修复）：启动期引擎会为全部 11664 个管道状态并行预计算形状缓存，
     * 且每个状态会多次调用 getShape（碰撞/遮挡/面遮挡）。每次现场用 Shapes.or 拼接是加载卡顿的根源。
     * 以三进制连接掩码为键缓存：同尺寸管道只有 3^6=729 种组合，每种只构建一次。
     * 启动期形状预计算是并行的，必须用线程安全容器。
     */
    private final java.util.Map<Integer, VoxelShape> pipeShapeCache = new java.util.concurrent.ConcurrentHashMap<>();

    // 默认构造函数（铁质普通管道）
    public PipeBlock(Properties properties) {
        this(properties, PipeMaterial.IRON, PipeSize.NORMAL);
    }

    // 自定义尺寸的构造函数
    public PipeBlock(Properties properties, PipeSize size) {
        this(properties, PipeMaterial.IRON, size);
    }

    // 完整构造函数（材质 + 尺寸）
    public PipeBlock(Properties properties, PipeMaterial material, PipeSize size) {
        super(properties);
        this.pipeSize = size;
        this.pipeMaterial = material;

        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, PipeConnection.NONE)
                .setValue(SOUTH, PipeConnection.NONE)
                .setValue(EAST, PipeConnection.NONE)
                .setValue(WEST, PipeConnection.NONE)
                .setValue(UP, PipeConnection.NONE)
                .setValue(DOWN, PipeConnection.NONE));
    }

    public PipeSize getPipeSize() {
        return pipeSize;
    }

    public PipeMaterial getPipeMaterial() {
        return pipeMaterial;
    }

    /**
     * 管道连接变化时通知管网重建（扳手/连接切换后调用）。
     */
    public static void notifyConnectionsChanged(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            WorldPipeNet.get(serverLevel).onConnectionsChanged(pos);
        }
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
        int mask = connectionMask(state);
        VoxelShape cached = pipeShapeCache.get(mask);
        if (cached != null) return cached;

        VoxelShape shape = pipeSize.getCoreShape();
        int m = mask;
        for (Direction dir : Direction.values()) {
            PipeConnection conn = PipeConnection.VALUES[m % 3];
            m /= 3;
            if (conn == PipeConnection.NONE) continue;
            // 已连接/抽取：都渲染管臂碰撞箱
            shape = Shapes.or(shape, armShape(dir));
            if (conn == PipeConnection.EXTRACT) {
                // 抽取模式额外加上抽取口板
                shape = Shapes.or(shape, INPUT_PLATES[dir.get3DDataValue()]);
            }
        }
        pipeShapeCache.put(mask, shape);
        return shape;
    }

    /** 把 6 个方向的三态连接编码为三进制整数（0..728），作为形状缓存键 */
    private static int connectionMask(BlockState state) {
        int mask = 0;
        int mul = 1;
        for (Direction dir : Direction.values()) {
            mask += state.getValue(getProperty(dir)).ordinal() * mul;
            mul *= 3;
        }
        return mask;
    }

    private VoxelShape armShape(Direction dir) {
        return switch (dir) {
            case NORTH -> pipeSize.getNorthArm();
            case SOUTH -> pipeSize.getSouthArm();
            case EAST -> pipeSize.getEastArm();
            case WEST -> pipeSize.getWestArm();
            case UP -> pipeSize.getUpArm();
            case DOWN -> pipeSize.getDownArm();
        };
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !oldState.is(this)) {
            if (!layingBatch) {
                for (Direction dir : Direction.values()) {
                    if (canConnect(level, pos, dir)) {
                        setConnection(level, pos, dir, PipeConnection.CONNECTED);
                    }
                }
            }
            // 新管道放置：并入/合并相邻管网（携带原有流体）
            if (level instanceof ServerLevel serverLevel) {
                WorldPipeNet.get(serverLevel).onPipePlaced(pos);
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide() && state.is(this) && !newState.is(this)) {
            for (Direction dir : Direction.values()) {
                if (state.getValue(getProperty(dir)) != PipeConnection.NONE) {
                    BlockPos neighborPos = pos.relative(dir);
                    BlockState neighborState = level.getBlockState(neighborPos);
                    if (neighborState.getBlock() instanceof PipeBlock) {
                        neighborState = neighborState.setValue(getProperty(dir.getOpposite()), PipeConnection.NONE);
                        level.setBlock(neighborPos, neighborState, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                    }
                }
            }
            // 管道移除：拆分管网，管内流体按容量比例守恒分配到新管网
            if (level instanceof ServerLevel serverLevel) {
                WorldPipeNet.get(serverLevel).onPipeRemoved(pos);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean isMoving) {
        if (level.isClientSide()) return;
        // 批量铺设期间：连接变化全部推迟到数据包统一接线，避免新管与旧管自动相连
        if (layingBatch) return;

        Direction dir = getDirection(pos, neighborPos);
        if (dir == null) return;

        BlockState neighborState = level.getBlockState(neighborPos);
        boolean neighborIsPipe = neighborState.getBlock() instanceof PipeBlock;
        PipeConnection current = state.getValue(getProperty(dir));

        if (neighborIsPipe && current != PipeConnection.CONNECTED) {
            // 管道间自动接管：抽取态也被管道顶替为已连接
            setConnection(level, pos, dir, PipeConnection.CONNECTED);
        } else if (!neighborIsPipe && current == PipeConnection.CONNECTED) {
            // 自动连接只针对管道：邻接变成非管道时回退为未连接
            // （EXTRACT 由扳手显式控制，不自动回退，储罐换掉再放回仍保持抽取）
            setConnection(level, pos, dir, PipeConnection.NONE);
        }

        // 邻接方块变化：失效本管道的端点缓存（机器/储罐可能更换）
        if (level instanceof ServerLevel serverLevel) {
            WorldPipeNet.get(serverLevel).onNeighborChanged(pos);
        }
    }

    /**
     * 设置连接状态；邻接是管道时镜像同步对面（EXTRACT 永不镜像，它只作用于设备侧）。
     */
    public static void setConnection(Level level, BlockPos pos, Direction dir, PipeConnection value) {
        BlockState state = level.getBlockState(pos);
        EnumProperty<PipeConnection> prop = getProperty(dir);
        if (state.getValue(prop) == value) return;

        level.setBlock(pos, state.setValue(prop, value), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);

        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (value != PipeConnection.EXTRACT && neighborState.getBlock() instanceof PipeBlock) {
            EnumProperty<PipeConnection> neighborProp = getProperty(dir.getOpposite());
            if (neighborState.getValue(neighborProp) != value) {
                level.setBlock(neighborPos, neighborState.setValue(neighborProp, value),
                        Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            }
        }
    }

    public static EnumProperty<PipeConnection> getProperty(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    /**
     * 设备侧连接状态变化（不涉及管道间拓扑）：只失效端点缓存，避免整网重建。
     */
    public static void notifyEndpointsChanged(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            WorldPipeNet.get(serverLevel).onNeighborChanged(pos);
        }
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
