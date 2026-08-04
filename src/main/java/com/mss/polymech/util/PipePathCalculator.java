package com.mss.polymech.util;

import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 管道路径计算（最多两轴的 L 型路径）。
 * <p>
 * 默认优先沿最长轴走；若 L 型的中间格子被非管道方块阻挡，
 * 则换用镜像朝向（先走次长轴）；两种朝向都被阻挡时回退到默认朝向
 * （铺设时会跳过被占用的格子）。管道不视为阻挡。
 * </p>
 */
public class PipePathCalculator {

    /**
     * 端点吸附解析：若锚点是声明了有效面的侧面方块代理，
     * 将路径端点换到首个声明面（世界方向）所对的相邻格子，
     * 使管道自动绕到机器模型上真正的输出口/输入口那一格连接。
     * <p>
     * 未声明面、目标格被非管道方块占用、或目标格与另一端点重合时，保持原锚点。
     * 接线锚点仍为原代理格，不受影响。
     * </p>
     */
    public static BlockPos resolveEndpoint(BlockGetter level, BlockPos anchor, BlockPos otherEnd) {
        if (level == null) return anchor;
        BlockEntity be = level.getBlockEntity(anchor);
        if (!(be instanceof BaseIOSideBlockEntity sideEntity)) return anchor;
        BlockPos parentPos = sideEntity.getParentPos();
        if (parentPos == null) return anchor;
        if (!(level.getBlockState(parentPos).getBlock() instanceof BaseMachineBlock machineBlock)) return anchor;
        Direction facing = level.getBlockState(parentPos).getValue(BaseMachineBlock.FACING);
        Vec3i offset = new Vec3i(anchor.getX() - parentPos.getX(), anchor.getY() - parentPos.getY(), anchor.getZ() - parentPos.getZ());
        Vec3i local = BaseMachineBlock.unrotateVec3i(offset, facing);
        BaseMachineBlock.FluidProxy fluidProxy = machineBlock.getFluidProxy(local);
        Direction[] faces = fluidProxy != null ? fluidProxy.faces()
                : (machineBlock.getItemProxy(local) != null ? machineBlock.getItemProxy(local).faces() : null);
        if (faces == null || faces.length == 0) return anchor;
        for (Direction localFace : faces) {
            Direction worldFace = BaseMachineBlock.rotateDirection(localFace, facing);
            BlockPos snapped = anchor.relative(worldFace);
            if (snapped.equals(otherEnd)) continue;
            BlockState st = level.getBlockState(snapped);
            if (st.isAir() || st.getBlock() instanceof PipeBlock) {
                return snapped;
            }
        }
        return anchor;
    }

    /**
     * 该格是否为流体锚点：机器声明的流体代理格，或带流体能力的容器/机器主方块。
     * 只有流体锚点才允许特殊铺设（整格选取为端点、面向锚点设为抽取/连接）；
     * 物品代理等普通侧面方块不在此列。
     */
    public static boolean isFluidAnchor(BlockGetter level, BlockPos pos) {
        if (level == null) return false;
        // 侧面方块：解析父机器在该局部偏移声明的代理类型
        if (level.getBlockEntity(pos) instanceof BaseIOSideBlockEntity sideEntity) {
            BlockPos parentPos = sideEntity.getParentPos();
            if (parentPos == null) return false;
            if (!(level.getBlockState(parentPos).getBlock() instanceof BaseMachineBlock machineBlock)) return false;
            Direction facing = level.getBlockState(parentPos).getValue(BaseMachineBlock.FACING);
            Vec3i offset = new Vec3i(pos.getX() - parentPos.getX(), pos.getY() - parentPos.getY(), pos.getZ() - parentPos.getZ());
            Vec3i local = BaseMachineBlock.unrotateVec3i(offset, facing);
            return machineBlock.getFluidProxy(local) != null;
        }
        // 储罐等普通容器 / 机器主方块：走流体能力查询
        if (level instanceof net.minecraft.world.level.Level fullLevel) {
            return fullLevel.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, null) != null;
        }
        return false;
    }

    /**
     * 带阻挡检测的路径计算：默认朝向被非管道方块阻挡时换用镜像朝向。
     */
    public static List<BlockPos> calculatePath(BlockGetter level, BlockPos start, BlockPos end) {
        List<BlockPos> primary = buildPath(start, end, true);
        // 直线（或退化）路径没有可替换的朝向
        if (primary.size() <= 2 || level == null) return primary;

        if (!isBlocked(level, primary, start, end)) return primary;

        List<BlockPos> alternate = buildPath(start, end, false);
        if (!alternate.isEmpty() && !isBlocked(level, alternate, start, end)) {
            return alternate;
        }
        // 两种朝向都被阻挡：回退默认朝向（铺设时跳过占用格）
        return primary;
    }

    /**
     * 无阻挡检测的原始路径计算（默认最长轴优先）。
     */
    public static List<BlockPos> calculatePath(BlockPos start, BlockPos end) {
        return buildPath(start, end, true);
    }

    /**
     * 路径是否被阻挡：端点（可能是容器/机器）与管道格不算阻挡，
     * 其余格子被非空气且非管道的方块占据即为阻挡。
     */
    private static boolean isBlocked(BlockGetter level, List<BlockPos> path, BlockPos start, BlockPos end) {
        for (BlockPos pos : path) {
            if (pos.equals(start) || pos.equals(end)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.getBlock() instanceof PipeBlock) continue; // 管道不阻挡
            return true;
        }
        return false;
    }

    /**
     * 构建 L 型路径。
     *
     * @param longestFirst true=先走最长轴（默认朝向）；false=先走次长轴（镜像朝向）
     */
    private static List<BlockPos> buildPath(BlockPos start, BlockPos end, boolean longestFirst) {
        List<BlockPos> path = new ArrayList<>();

        int dx = end.getX() - start.getX();
        int dy = end.getY() - start.getY();
        int dz = end.getZ() - start.getZ();

        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);
        int absDz = Math.abs(dz);

        int nonZeroAxes = 0;
        if (absDx > 0) nonZeroAxes++;
        if (absDy > 0) nonZeroAxes++;
        if (absDz > 0) nonZeroAxes++;

        if (nonZeroAxes > 2) {
            return path;
        }

        path.add(start);

        if (nonZeroAxes == 1) {
            Direction dir = getDirection(dx, dy, dz);
            if (dir != null) {
                for (int i = 1; i <= getDistance(absDx, absDy, absDz); i++) {
                    path.add(start.relative(dir, i));
                }
            }
        } else if (nonZeroAxes == 2) {
            Direction longDir = getPrimaryDirection(absDx, absDy, absDz, dx, dy, dz);
            Direction shortDir = getSecondaryDirection(absDx, absDy, absDz, dx, dy, dz);

            if (longDir != null && shortDir != null) {
                int longDist = getFirstDistance(absDx, absDy, absDz);
                int shortDist = getSecondDistance(absDx, absDy, absDz);

                Direction firstDir = longestFirst ? longDir : shortDir;
                Direction secondDir = longestFirst ? shortDir : longDir;
                int firstDist = longestFirst ? longDist : shortDist;
                int secondDist = longestFirst ? shortDist : longDist;

                BlockPos current = start;
                for (int i = 1; i <= firstDist; i++) {
                    current = current.relative(firstDir);
                    path.add(current);
                }

                for (int i = 1; i <= secondDist; i++) {
                    current = current.relative(secondDir);
                    path.add(current);
                }
            }
        }

        return path;
    }

    private static Direction getDirection(int dx, int dy, int dz) {
        if (dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (dy != 0) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (dz != 0) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return null;
    }

    private static int getDistance(int absDx, int absDy, int absDz) {
        return Math.max(absDx, Math.max(absDy, absDz));
    }

    private static Direction getPrimaryDirection(int absDx, int absDy, int absDz, int dx, int dy, int dz) {
        if (absDx >= absDy && absDx >= absDz) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (absDy >= absDx && absDy >= absDz) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static Direction getSecondaryDirection(int absDx, int absDy, int absDz, int dx, int dy, int dz) {
        if (absDx >= absDy && absDx >= absDz) {
            if (absDy >= absDz) {
                return dy > 0 ? Direction.UP : Direction.DOWN;
            } else {
                return dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
        } else if (absDy >= absDx && absDy >= absDz) {
            if (absDx >= absDz) {
                return dx > 0 ? Direction.EAST : Direction.WEST;
            } else {
                return dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
        } else {
            if (absDx >= absDy) {
                return dx > 0 ? Direction.EAST : Direction.WEST;
            } else {
                return dy > 0 ? Direction.UP : Direction.DOWN;
            }
        }
    }

    private static int getFirstDistance(int absDx, int absDy, int absDz) {
        return Math.max(absDx, Math.max(absDy, absDz));
    }

    private static int getSecondDistance(int absDx, int absDy, int absDz) {
        int max = Math.max(absDx, Math.max(absDy, absDz));
        if (max == absDx) return Math.max(absDy, absDz);
        if (max == absDy) return Math.max(absDx, absDz);
        return Math.max(absDx, absDy);
    }
}
