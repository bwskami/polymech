package com.mss.polymech.machine.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 机器结构几何工具 —— 多方块机器的偏移枚举、旋转、占据体积计算的<strong>唯一</strong>实现。
 * <p>
 * 抽出来的动机：同一套「展开填充区域 + 离散偏移 + 按朝向旋转」的循环曾在
 * {@code BaseMachineBlock#enumerateLocalOffsets}、{@code BaseMachineBlock#isLocalPartOfStructure}、
 * {@code BaseMachineBlock#getSidePositions}、{@code LargeMachineBlock#setPlacedBy} 中
 * 各写了一份，任何一处改错都会让「放置的侧方块」和「判定为结构的格子」不一致。
 * </p>
 * <p>
 * 本类<strong>只依赖</strong> {@link Vec3i} / {@link BlockPos} / {@link Direction} / {@link AABB}
 * 这些纯数据类，不触碰方块注册表，因此可以在离线校验程序
 * （{@code verifyMachineRenderBox} 任务）中直接调用真实实现做断言，
 * 而不是另写一份等价逻辑去自证。
 * </p>
 */
public final class MachineGeometry {

    /**
     * 以主方块为中心的<strong>最小</strong>渲染覆盖半径（格）。
     * <p>
     * 这个值的依据是随包美术模型的实测尺寸，而不是方块结构：模型按视觉尺寸建模，
     * 远大于结构。实测（{@code origin/size} 按 Geckolib 公式换算后）：
     * </p>
     * <ul>
     *   <li>最大上探：{@code primitive_blast_furnace} 9.38 格（其结构只有 1 格高）</li>
     *   <li>最大横向：{@code beehive_coke_oven} 6.11 格（其结构只是十字）</li>
     * </ul>
     * <p>
     * 取 16 而非贴边取值，是给骨骼静止姿态偏移与动画摆动留余量。
     * 该值被 {@code verifyMachineRenderBox} 用真实 geo.json 逐个模型断言
     * 「四个朝向都被罩住且至少余 2 格」——新增更大的模型会让校验 FAIL 并报出数字，
     * 因此这个常量不会悄悄失效。
     * </p>
     */
    public static final int MODEL_COVERAGE_RADIUS = 16;

    /**
     * 方块实体渲染器的视距上限（格），用于覆写原版默认值。
     *
     * <p>
     * <b>这是「人离大机器远了模型就消失」的直接原因。</b>
     * {@code BlockEntityRenderDispatcher#render} 只有在
     * {@code renderer.shouldRender(be, camera.getPosition())} 为 true 时才真正绘制；
     * 而 {@code shouldRender} 的默认实现是
     * {@code Vec3.atCenterOf(be.getBlockPos()).closerThan(cameraPos, getViewDistance())}，
     * {@code getViewDistance()} 原版默认 <b>64</b> 格。于是机器离相机超过 64 格就整体不画，
     * 地面上只留下隐形方块造成的天光照暗斑，看起来就是「只剩一团阴影」。
     * </p>
     * <p>
     * 取值口径：不小于原版最大渲染距离（32 chunk × 16 = 512 格），
     * 使距离不再构成额外门槛；是否绘制交给
     * {@code shouldRenderOffScreen}（进 globalBlockEntities，绕过 16³ 区块段裁剪）
     * + {@code getRenderBoundingBox}（视锥判据，见 {@link #MODEL_COVERAGE_RADIUS}）。
     * 原版先例：{@code BeaconRenderer#getViewDistance()} 返回 256，信标同样要远距离可见。
     * </p>
     */
    public static final int MODEL_VIEW_DISTANCE = 512;

    private MachineGeometry() {
    }

    // ==================== 旋转 ====================

    /** 把本地偏移（facing=NORTH 声明）旋转到实际朝向。 */
    public static Vec3i rotate(Vec3i offset, Direction facing) {
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

    /** {@link #rotate} 的逆运算：把世界偏移转回本地坐标。 */
    public static Vec3i unrotate(Vec3i offset, Direction facing) {
        // 注意：EAST/WEST 下与 rotate 并不是同一个映射，两者互为逆变换
        //（rotate EAST 是 90° 一个方向，unrotate EAST 是反方向），不能互相替代。
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

    /** 把本地坐标的方向（facing=NORTH 声明）旋转为实际世界方向。 */
    public static Direction rotateDirection(Direction local, Direction facing) {
        if (local.getAxis() == Direction.Axis.Y || facing.getAxis() == Direction.Axis.Y) {
            return local; // 竖直面与水平旋转无关
        }
        Vec3i step = rotate(local.getNormal(), facing);
        return Direction.getNearest(step.getX(), step.getY(), step.getZ());
    }

    // ==================== 本地偏移枚举 ====================

    /**
     * 枚举机器的全部本地格子偏移（离散侧偏移 + 填充区域展开）。
     * <p>
     * 语义与旧实现保持一致：<strong>不去重</strong>，填充区域与离散偏移重叠时会出现重复项
     * （调用方 {@code exportProxyOutputs} 依赖"逐位置尝试"，重复项无副作用）。
     * </p>
     */
    public static List<Vec3i> enumerateLocalOffsets(Vec3i[] sideOffsets, Vec3i[][] fillRegions) {
        List<Vec3i> list = new ArrayList<>();
        if (fillRegions != null) {
            for (Vec3i[] region : fillRegions) {
                if (region == null || region.length < 2) continue;
                expandRegion(region[0], region[1], list::add);
            }
        }
        if (sideOffsets != null) {
            list.addAll(java.util.Arrays.asList(sideOffsets));
        }
        return list;
    }

    /** 展开一个 {min,max} 角点区域（含边界，自动处理 min>max 的反向写法）。 */
    private static void expandRegion(Vec3i a, Vec3i b, java.util.function.Consumer<Vec3i> out) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    out.accept(new Vec3i(x, y, z));
                }
            }
        }
    }

    /**
     * 判断某个本地偏移是否属于机器结构（主方块自身、填充区域内、或声明的离散侧偏移）。
     */
    public static boolean containsLocal(Vec3i local, Vec3i[] sideOffsets, Vec3i[][] fillRegions) {
        if (local.equals(Vec3i.ZERO)) return true;
        if (fillRegions != null) {
            for (Vec3i[] region : fillRegions) {
                if (region == null || region.length < 2) continue;
                Vec3i a = region[0], b = region[1];
                int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
                int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
                int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
                if (local.getX() >= minX && local.getX() <= maxX
                        && local.getY() >= minY && local.getY() <= maxY
                        && local.getZ() >= minZ && local.getZ() <= maxZ) {
                    return true;
                }
            }
        }
        if (sideOffsets != null) {
            for (Vec3i offset : sideOffsets) {
                if (offset.equals(local)) return true;
            }
        }
        return false;
    }

    // ==================== 世界坐标位置 ====================

    /**
     * 计算所有侧方块的世界坐标（已按朝向旋转，去重，且排除主方块自身位置）。
     */
    public static BlockPos[] sidePositions(BlockPos origin, Direction facing, Vec3i[] sideOffsets, Vec3i[][] fillRegions) {
        Set<BlockPos> positions = new HashSet<>();
        for (Vec3i local : enumerateLocalOffsets(sideOffsets, fillRegions)) {
            positions.add(origin.offset(rotate(local, facing)));
        }
        positions.remove(origin);
        return positions.toArray(new BlockPos[0]);
    }

    // ==================== 渲染包围盒 ====================

    /**
     * 计算机器方块实体渲染器的包围盒：结构体积与「主方块中心 ± {@code minCoverageRadius}」
     * 取并集。
     *
     * <h3>为什么不能只按方块结构算</h3>
     * 方块实体的可见性有<b>两道</b>门槛，本方法是第二道的输入：
     * <ol>
     *   <li><b>距离门槛</b>：{@code BlockEntityRenderDispatcher#render} 先问
     *       {@code renderer.shouldRender(be, cameraPos)}，其默认实现拿主方块中心到相机的距离
     *       与 {@code getViewDistance()}（原版默认 64 格）比较 —— 见
     *       {@link #MODEL_VIEW_DISTANCE}。这一道由渲染器覆写视距解决，与本方法无关。</li>
     *   <li><b>视锥门槛</b>：{@code ClientHooks#isBlockEntityRendererVisible} 只做
     *       {@code frustum.isVisible(renderer.getRenderBoundingBox(be))} —— 本方法就是它的输入。
     *       {@code shouldRenderOffScreen} 只决定该实体进不进 globalBlockEntities
     *       （即不受 16³ 区块段可见性裁剪），之后仍要过这道视锥判据。</li>
     * </ol>
     * <p>
     * 而本 mod 的 Geckolib 模型比方块结构大得多（美术按视觉尺寸建模）：
     * 例如 {@code primitive_blast_furnace} 结构只是 1 格高的十字，
     * 模型实际高 9.38 格、横向 8.61 格；{@code beehive_coke_oven} 结构同样是十字，
     * 模型却有 9.97 格宽。旧实现返回 {@code new AABB(pos).inflate(4)}（只有 ±4/5 格），
     * 模型顶部长在盒子之外 —— 于是「模型上半部分还在画面里、主方块的盒子已滑出视锥」
     * 时整台模型被整体剔除。
     * </p>
     * <p>
     * 因此包围半径必须按<strong>模型</strong>而非结构来定。是否足够由离线校验
     * {@code verifyMachineRenderBox} 用随包的真实 geo.json 逐个模型断言（含旋转与余量），
     * 新增更大的模型会让该校验 FAIL 并报出具体数字，而不是靠肉眼看。
     * </p>
     *
     * @param minCoverageRadius 以主方块为中心的<strong>最小</strong>覆盖半径（格）；
     *                          结构比它还大时取结构体积
     */
    public static AABB renderBounds(BlockPos origin, Direction facing,
                                    Vec3i[] sideOffsets, Vec3i[][] fillRegions, int minCoverageRadius) {
        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = origin.getX() + 1, maxY = origin.getY() + 1, maxZ = origin.getZ() + 1;
        for (Vec3i local : enumerateLocalOffsets(sideOffsets, fillRegions)) {
            Vec3i world = rotate(local, facing);
            int x = origin.getX() + world.getX();
            int y = origin.getY() + world.getY();
            int z = origin.getZ() + world.getZ();
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x + 1 > maxX) maxX = x + 1;
            if (y + 1 > maxY) maxY = y + 1;
            if (z + 1 > maxZ) maxZ = z + 1;
        }
        // 与「主方块中心 ± 半径」取并集，保证比结构大得多的美术模型也被罩住
        minX = Math.min(minX, origin.getX() - minCoverageRadius);
        minY = Math.min(minY, origin.getY() - minCoverageRadius);
        minZ = Math.min(minZ, origin.getZ() - minCoverageRadius);
        maxX = Math.max(maxX, origin.getX() + 1 + minCoverageRadius);
        maxY = Math.max(maxY, origin.getY() + 1 + minCoverageRadius);
        maxZ = Math.max(maxZ, origin.getZ() + 1 + minCoverageRadius);
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
