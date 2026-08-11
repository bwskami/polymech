package com.mss.polymech.machine;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.powergrid.GridNodeBlock;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class BaseMachineBlock extends BaseEntityBlock implements GridNodeBlock {

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

    // ========== 侧面方块代理配置 ==========

    /**
     * 代理 IO 方向：INPUT 被动接收外部输入；
     * OUTPUT 由机器 BE 周期性主动向外推送。
     */
    public enum ProxyIO {
        INPUT,
        OUTPUT
    }

    /**
     * 物品代理声明：暴露的内部槽位索引 + IO 方向 + 有效面。
     * 槽位索引直接指向主方块 BE 的 itemStackHandler，
     * 能力层会将其包装为过滤视图，外部设备只能看到/操作这些槽位。
     * <p>
     * {@code faces}（本地坐标，facing=NORTH）声明代理仅生效的面：
     * null 或空数组表示六面全通；否则只有声明的面才暴露能力/参与主动推送，
     * 管道自动铺设也会吸附到首个声明面所对的格子。
     */
    public record ItemProxy(int[] slots, ProxyIO io, @Nullable Direction[] faces) {
        public ItemProxy(int[] slots, ProxyIO io) {
            this(slots, io, null);
        }

        /** 世界方向 side 是否允许访问该代理（未声明面时六面全通；null 查询面视为通过） */
        public boolean allowsWorldFace(@Nullable Direction worldSide, Direction facing) {
            if (faces == null || faces.length == 0) return true;
            if (worldSide == null) return true;
            for (Direction f : faces) {
                if (rotateDirection(f, facing) == worldSide) return true;
            }
            return false;
        }
    }

    /**
     * 流体代理声明：暴露的逻辑储罐索引 + IO 方向 + 有效面。
     * 储罐索引指向 BE 的逻辑储罐列表（{@code BaseIOBlockEntity#getFluidTank(int)}），
     * 支持多进多出的任意储罐数量；单个位置可暴露多个储罐（能力层拼接为组合 handler）。
     * <p>
     * {@code faces} 语义同 {@link ItemProxy}：null/空=六面全通，否则仅声明面生效。
     */
    public record FluidProxy(int[] tanks, ProxyIO io, @Nullable Direction[] faces) {
        public FluidProxy(int[] tanks, ProxyIO io) {
            this(tanks, io, null);
        }

        /** 世界方向 side 是否允许访问该代理（未声明面时六面全通；null 查询面视为通过） */
        public boolean allowsWorldFace(@Nullable Direction worldSide, Direction facing) {
            if (faces == null || faces.length == 0) return true;
            if (worldSide == null) return true;
            for (Direction f : faces) {
                if (rotateDirection(f, facing) == worldSide) return true;
            }
            return false;
        }
    }

    /**
     * 将本地坐标的方向（facing=NORTH 声明）旋转为实际世界方向。
     */
    public static Direction rotateDirection(Direction local, Direction facing) {
        if (local.getAxis() == Direction.Axis.Y || facing.getAxis() == Direction.Axis.Y) {
            return local; // 竖直面与水平旋转无关
        }
        Vec3i step = rotateVec3i(local.getNormal(), facing);
        return Direction.getNearest(step.getX(), step.getY(), step.getZ());
    }

    /**
     * 获取指定本地偏移位置的物品代理声明（槽位 + IO 方向）。
     * 返回 null 表示该位置不代理物品能力（纯占位）。
     *
     * @param relativeOffset 侧面方块相对于主方块的本地偏移（未旋转）
     */
    @Nullable
    public ItemProxy getItemProxy(Vec3i relativeOffset) {
        return null;
    }

    /**
     * 获取指定本地偏移位置的流体代理声明（储罐 + IO 方向）。
     * 返回 null 表示该位置不代理流体能力（纯占位）。
     *
     * @param relativeOffset 侧面方块相对于主方块的本地偏移（未旋转）
     */
    @Nullable
    public FluidProxy getFluidProxy(Vec3i relativeOffset) {
        return null;
    }

    /**
     * 枚举所有侧面方块的本地偏移（填充区域展开 + sideOffsets），
     * 供 BE 遍历代理配置使用。
     */
    public List<Vec3i> enumerateLocalOffsets() {
        List<Vec3i> list = new ArrayList<>(java.util.Arrays.asList(getSideOffsets()));
        Vec3i[][] regions = getFillRegions();
        if (regions != null) {
            for (Vec3i[] region : regions) {
                Vec3i min = region[0];
                Vec3i max = region[1];
                for (int x = Math.min(min.getX(), max.getX()); x <= Math.max(min.getX(), max.getX()); x++) {
                    for (int y = Math.min(min.getY(), max.getY()); y <= Math.max(min.getY(), max.getY()); y++) {
                        for (int z = Math.min(min.getZ(), max.getZ()); z <= Math.max(min.getZ(), max.getZ()); z++) {
                            list.add(new Vec3i(x, y, z));
                        }
                    }
                }
            }
        }
        return list;
    }

    /**
     * 判断本地偏移是否属于机器结构（主方块/填充区域/侧面偏移）。
     * 供 BE 主动输出时跳过结构内部相邻方块。
     */
    public boolean isLocalPartOfStructure(Vec3i local) {
        if (local.equals(Vec3i.ZERO)) return true;
        Vec3i[][] regions = getFillRegions();
        if (regions != null) {
            for (Vec3i[] region : regions) {
                Vec3i min = region[0];
                Vec3i max = region[1];
                int minX = Math.min(min.getX(), max.getX());
                int maxX = Math.max(min.getX(), max.getX());
                int minY = Math.min(min.getY(), max.getY());
                int maxY = Math.max(min.getY(), max.getY());
                int minZ = Math.min(min.getZ(), max.getZ());
                int maxZ = Math.max(min.getZ(), max.getZ());
                if (local.getX() >= minX && local.getX() <= maxX
                        && local.getY() >= minY && local.getY() <= maxY
                        && local.getZ() >= minZ && local.getZ() <= maxZ) {
                    return true;
                }
            }
        }
        for (Vec3i offset : getSideOffsets()) {
            if (offset.equals(local)) return true;
        }
        return false;
    }

    /**
     * {@link #rotateVec3i} 的逆运算：将世界坐标偏移转回本地坐标（未旋转）。
     */
    public static Vec3i unrotateVec3i(Vec3i offset, Direction facing) {
        int x = offset.getX();
        int z = offset.getZ();
        return switch (facing) {
            case NORTH -> new Vec3i(x, offset.getY(), z);
            case SOUTH -> new Vec3i(-x, offset.getY(), -z);
            case EAST -> new Vec3i(z, offset.getY(), -x);
            case WEST -> new Vec3i(-z, offset.getY(), x);
            default -> offset;
        };
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

    // ========== 电网节点 ==========

    /**
     * 机器默认提供一个电网节点（ID=0，位于主方块中心），
     * 支持线轴拉线连接与电力接入。需要多节点的机器子类可覆盖此方法。
     */
    @Override
    public Map<Integer, Vec3> getNodePositions(BlockState state) {
        return Map.of(0, new Vec3(0.5, 0.5, 0.5));
    }

    /**
     * 机器被破坏/替换时，从电网移除该格节点的全部电线连接。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            WorldPowerGrid.get((ServerLevel) level).removeNode(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
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

    /**
     * 打开机器 UI，供侧面方块右键时委托给主方块。
     * 默认使用原版 MenuProvider；使用 LDLib2 的机器子类应重写此方法。
     */
    public void openMachineUI(ServerPlayer player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof MenuProvider menuProvider) {
            player.openMenu(menuProvider, pos);
        }
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
