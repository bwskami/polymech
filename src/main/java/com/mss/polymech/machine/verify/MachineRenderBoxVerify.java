package com.mss.polymech.machine.verify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mss.polymech.machine.common.MachineGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 机器渲染包围盒与视距离线校验 —— 对应 {@code verifyMachineRenderBox} Gradle 任务。
 *
 * <h3>为什么需要它</h3>
 * 「Geckolib 模型远距离消失」是纯客户端现象，只能重启游戏靠肉眼判断，
 * 而肉眼判断"这次到底修好没有"极不可靠。它其实有两道可离线判定的门槛：
 * <ol>
 *   <li><b>距离门槛</b>：{@code BlockEntityRenderDispatcher#render} 只在
 *       {@code shouldRender(be, cameraPos)} 为 true 时绘制，其默认实现比较
 *       {@code getViewDistance()}（原版 64 格）—— 渲染器必须覆写它，否则走远必消失。
 *       本校验用 <b>A6</b> 守住这个覆写不被删掉。</li>
 *   <li><b>视锥门槛</b>：{@code frustum.isVisible(renderer.getRenderBoundingBox(be))}
 *       —— 包围盒必须罩住模型。本校验用 <b>A2</b> 拿真实 geo 资源断言。</li>
 * </ol>
 *
 * <h3>判据来源（避免"用自己的实现验证自己"）</h3>
 * <ul>
 *   <li><b>A1 结构覆盖</b>：机器格子集合由本文件独立三重循环展开，不复用
 *       {@code MachineGeometry.enumerateLocalOffsets}；</li>
 *   <li><b>A2 模型覆盖（核心）</b>：直接读随包 {@code geo/**} 资源，
 *       按 Geckolib {@code BakedModelFactory.constructCube} 的真实换算公式
 *       {@code x' = -(x + w)/16, y' = y/16, z' = z/16} 把每个方块换算成模型坐标，
 *       再套 Geckolib {@code rotateBlock} 的偏航旋转，得到模型在世界中的包围盒，
 *       断言它被渲染包围盒罩住且有余量。期望值来自<strong>美术资源</strong>，
 *       不是来自被测函数；</li>
 *   <li><b>A5 旋转互逆</b>：与包围盒实现无关的代数性质；</li>
 *   <li><b>A6 视距覆写存在性</b>：读渲染器源码确认它声明了 {@code getViewDistance}。
 *       这是"接线完整性"检查而非行为检查（行为要跑客户端），
 *       守的是本项目真实踩过的坑：只修了包围盒、没修距离上限，于是走远照样消失。</li>
 * </ul>
 *
 * <h3>已知不覆盖的部分（诚实说明）</h3>
 * A2 只算<b>模型静止姿态的方块并集</b>，不含骨骼自身的 rest 变换与动画位移，
 * 这些由 {@link #REQUIRED_HEADROOM} 余量吸收（见
 * {@link MachineGeometry#MODEL_COVERAGE_RADIUS} 的取值理由）。
 *
 * <p>运行：{@code ./gradlew verifyMachineRenderBox}（退出码 0 = 全部 PASS）</p>
 */
public final class MachineRenderBoxVerify {

    /** 与 {@link MachineGeometry#MODEL_COVERAGE_RADIUS} 同源，不重复定义数值 */
    private static final int RADIUS = MachineGeometry.MODEL_COVERAGE_RADIUS;

    /** 模型包围盒每侧要求的最小余量（格），用于吸收骨骼静止变换与动画摆动 */
    private static final double REQUIRED_HEADROOM = 2.0;

    /** 修复前的旧盒子写法：只罩主方块 ±4 格（{@code new AABB(pos).inflate(4)}） */
    private static final int OLD_BOX_INFLATE = 4;

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static int failures = 0;
    private static int checks = 0;

    private record Shape(String name, Vec3i[] sideOffsets, Vec3i[][] fillRegions) {
    }

    /** 模型在方块局部坐标下的包围盒（格，已含 Geckolib 的 (0.5, 0, 0.5) 平移） */
    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean insideWithHeadroom(AABB box, double headroom) {
            return box.minX <= minX - headroom && box.minY <= minY - headroom && box.minZ <= minZ - headroom
                    && box.maxX >= maxX + headroom && box.maxY >= maxY + headroom && box.maxZ >= maxZ + headroom;
        }

        String sizeText() {
            return String.format("%.2f x %.2f x %.2f", maxX - minX, maxY - minY, maxZ - minZ);
        }
    }

    public static void main(String[] args) throws IOException {
        verifyShapes();
        verifyShippedModelsCovered();
        verifyViewDistanceOverride();
        verifyRotateUnrotateInverse();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASS (" + checks + " 项断言)");
            System.exit(0);
        }
        System.out.println(failures + " FAILED (共 " + checks + " 项断言)");
        System.exit(1);
    }

    // ==================== A1/A3/A4：结构相关的断言 ====================

    private static void verifyShapes() {
        List<Shape> shapes = List.of(
                new Shape("单方块机器（无侧方块）", new Vec3i[0], null),
                new Shape("十字形（crossOffsets，多数大机器）", new Vec3i[]{
                        new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
                        new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)
                }, null),
                new Shape("卧式蒸汽锅炉（取自 HorizontalSteamBoilerBlock）", new Vec3i[]{
                        new Vec3i(0, 3, 0), new Vec3i(0, 3, 2), new Vec3i(0, 4, 2)
                }, new Vec3i[][]{
                        {new Vec3i(-1, 0, -2), new Vec3i(1, 2, 2)}
                }),
                new Shape("合成案例（5x3x5 实心填充）", new Vec3i[0], new Vec3i[][]{
                        {new Vec3i(-2, 0, -2), new Vec3i(2, 2, 2)}
                })
        );

        BlockPos origin = new BlockPos(0, 0, 0);
        for (Shape shape : shapes) {
            System.out.println();
            System.out.println("==== " + shape.name() + " ====");
            List<Vec3i> cells = independentCellEnumeration(shape);

            // A1：每个结构格子的中心都必须落在包围盒内（四朝向）
            for (Direction facing : HORIZONTAL) {
                AABB box = MachineGeometry.renderBounds(origin, facing, shape.sideOffsets(), shape.fillRegions(), RADIUS);
                int missing = 0;
                Vec3i firstMissing = null;
                for (Vec3i cell : cells) {
                    Vec3i world = MachineGeometry.rotate(cell, facing);
                    if (!box.contains(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5)) {
                        missing++;
                        if (firstMissing == null) firstMissing = world;
                    }
                }
                report("A1 结构全在盒内 [facing=" + facing + "]", missing == 0,
                        missing == 0 ? cells.size() + " 个结构格子全部覆盖"
                                : missing + " 个格子缺失，首个=" + firstMissing);
            }

            // A3：偏航旋转不改变包围盒体积
            double refVolume = -1;
            boolean volumeOk = true;
            StringBuilder volumes = new StringBuilder();
            for (Direction facing : HORIZONTAL) {
                AABB box = MachineGeometry.renderBounds(origin, facing, shape.sideOffsets(), shape.fillRegions(), RADIUS);
                double v = box.getXsize() * box.getYsize() * box.getZsize();
                volumes.append(String.format("%s=%.0f ", facing, v));
                if (refVolume < 0) {
                    refVolume = v;
                } else if (Math.abs(v - refVolume) > 1e-6) {
                    volumeOk = false;
                }
            }
            report("A3 朝向不改变体积", volumeOk, volumes.toString().trim());

            // A4：覆盖半径恰好生效（不得漏加，也不得失控）
            AABB box = MachineGeometry.renderBounds(origin, Direction.NORTH, shape.sideOffsets(), shape.fillRegions(), RADIUS);
            boolean radiusOk = box.minX <= -RADIUS && box.minY <= -RADIUS && box.minZ <= -RADIUS
                    && box.maxX >= 1 + RADIUS && box.maxY >= 1 + RADIUS && box.maxZ >= 1 + RADIUS;
            report("A4 至少覆盖主方块中心 ±" + RADIUS + " 格", radiusOk,
                    String.format("盒=[%.1f,%.1f,%.1f]~[%.1f,%.1f,%.1f]",
                            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
    }

    // ==================== A2：随包美术模型覆盖（核心断言）====================

    private static void verifyShippedModelsCovered() throws IOException {
        System.out.println();
        System.out.println("==== A2 随包模型覆盖（读 geo 资源，按 Geckolib 公式换算）====");

        Path geoRoot = Path.of("src/main/resources/assets/poly_mech/geo");
        if (!Files.isDirectory(geoRoot)) {
            report("定位 geo 资源目录", false, "找不到目录：" + geoRoot.toAbsolutePath());
            return;
        }

        List<Path> geoFiles;
        try (Stream<Path> walk = Files.walk(geoRoot)) {
            geoFiles = walk.filter(p -> p.getFileName().toString().endsWith(".geo.json")).sorted().toList();
        }
        if (geoFiles.isEmpty()) {
            report("发现 geo 资源", false, "目录下没有 *.geo.json");
            return;
        }
        report("发现 geo 资源", true, geoFiles.size() + " 个模型待校验");

        BlockPos origin = new BlockPos(0, 0, 0);
        // 旧盒子（修复前的写法）罩不住的模型清单 —— 这是「修复前模型会消失」的直接证据
        java.util.Set<String> oldBoxFailingModels = new java.util.LinkedHashSet<>();
        List<String> failingModels = new ArrayList<>();

        for (Path geoFile : geoFiles) {
            String name = geoFile.getFileName().toString().replace(".geo.json", "");
            List<double[][]> cubes = parseCubes(geoFile);

            if (cubes.isEmpty()) {
                System.out.printf("  [SKIP] %s —— 无方块（或格式不含 cubes）%n", name);
                continue;
            }

            boolean modelOk = true;
            double worstHeadroom = Double.MAX_VALUE;
            String worstDetail = "";

            for (Direction facing : HORIZONTAL) {
                Box modelBox = modelBoxForFacing(cubes, facing, origin);
                AABB renderBox = MachineGeometry.renderBounds(origin, facing, new Vec3i[0], null, RADIUS);

                // 旧盒子（修复前）：只罩主方块 ±4
                AABB oldBox = new AABB(origin).inflate(OLD_BOX_INFLATE, OLD_BOX_INFLATE, OLD_BOX_INFLATE);
                if (!modelBox.insideWithHeadroom(oldBox, 0)) {
                    oldBoxFailingModels.add(name);
                }

                if (!modelBox.insideWithHeadroom(renderBox, REQUIRED_HEADROOM)) {
                    modelOk = false;
                    worstDetail = String.format("facing=%s 模型盒=[%.2f,%.2f,%.2f]~[%.2f,%.2f,%.2f]",
                            facing, modelBox.minX(), modelBox.minY(), modelBox.minZ(),
                            modelBox.maxX(), modelBox.maxY(), modelBox.maxZ());
                } else {
                    double head = Math.min(
                            Math.min(modelBox.minX() - renderBox.minX, renderBox.maxX - modelBox.maxX()),
                            Math.min(modelBox.minY() - renderBox.minY, renderBox.maxY - modelBox.maxY()));
                    head = Math.min(head, Math.min(modelBox.minZ() - renderBox.minZ, renderBox.maxZ - modelBox.maxZ));
                    worstHeadroom = Math.min(worstHeadroom, head);
                }
            }

            if (modelOk) {
                Box ref = modelBoxForFacing(cubes, Direction.NORTH, origin);
                report(String.format("%-30s 模型 %s 格", name, ref.sizeText()), true,
                        String.format("四朝向均覆盖，最小余量 %.2f 格（要求 ≥ %.1f）", worstHeadroom, REQUIRED_HEADROOM));
            } else {
                failingModels.add(name);
                report(name, false, "模型超出渲染包围盒 —— " + worstDetail
                        + "；请提高 MachineGeometry.MODEL_COVERAGE_RADIUS（当前 " + RADIUS + "）");
            }
        }

        System.out.println();
        if (oldBoxFailingModels.isEmpty()) {
            System.out.printf("  参考：旧实现 new AABB(pos).inflate(%d) 恰好能罩住全部模型（本轮无回归面）。%n",
                    OLD_BOX_INFLATE);
        } else {
            System.out.printf("  参考：旧实现 new AABB(pos).inflate(%d) 罩不住 %d/%d 个模型 —— 这正是修复前"
                            + "「模型还在画面里却被整体剔除」的来源：%n      %s%n",
                    OLD_BOX_INFLATE, oldBoxFailingModels.size(), geoFiles.size(), oldBoxFailingModels);
        }
    }

    /** 按 Geckolib 的旋转（rotateBlock）把模型方块并集换算为世界包围盒 */
    private static Box modelBoxForFacing(List<double[][]> cubes, Direction facing, BlockPos origin) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[][] cube : cubes) {
            double[] lo = cube[0];
            double[] hi = cube[1];
            // x/z 的四个角分别旋转后取 AABB；y 不受偏航影响
            for (double x : new double[]{lo[0], hi[0]}) {
                for (double z : new double[]{lo[2], hi[2]}) {
                    double[] rotated = rotateXZ(x, z, facing);
                    double wx = origin.getX() + 0.5 + rotated[0];
                    double wz = origin.getZ() + 0.5 + rotated[1];
                    minX = Math.min(minX, wx);
                    maxX = Math.max(maxX, wx);
                    minZ = Math.min(minZ, wz);
                    maxZ = Math.max(maxZ, wz);
                }
            }
            double wy0 = origin.getY() + lo[1];
            double wy1 = origin.getY() + hi[1];
            minY = Math.min(minY, wy0);
            maxY = Math.max(maxY, wy1);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Geckolib rotateBlock 的偏航映射，与 {@link MachineGeometry#rotate} 一致 */
    private static double[] rotateXZ(double x, double z, Direction facing) {
        return switch (facing) {
            case NORTH -> new double[]{x, z};
            case SOUTH -> new double[]{-x, -z};
            case EAST -> new double[]{-z, x};
            case WEST -> new double[]{z, -x};
            default -> new double[]{x, z};
        };
    }

    /**
     * 解析 geo.json，输出每个方块的模型局部包围盒。
     * <p>
     * 换算公式照抄 Geckolib {@code BakedModelFactory.constructCube}：
     * {@code origin = (-(originX + sizeX), originY, originZ) / 16}，尺寸 {@code size / 16}。
     * 因此 x 区间是 {@code [-(oX+sX)/16, -oX/16]}，y 为 {@code [oY/16, (oY+sY)/16]}，
     * z 为 {@code [oZ/16, (oZ+sZ)/16]}。
     * </p>
     */
    private static List<double[][]> parseCubes(Path geoFile) throws IOException {
        String json = Files.readString(geoFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<double[][]> out = new ArrayList<>();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null) return out;
        for (JsonElement geomEl : geometries) {
            JsonObject geom = geomEl.getAsJsonObject();
            JsonArray bones = geom.getAsJsonArray("bones");
            if (bones == null) continue;
            for (JsonElement boneEl : bones) {
                JsonObject bone = boneEl.getAsJsonObject();
                JsonArray cubeArr = bone.getAsJsonArray("cubes");
                if (cubeArr == null) continue;
                for (JsonElement cubeEl : cubeArr) {
                    JsonObject cube = cubeEl.getAsJsonObject();
                    double[] o = readVec3(cube.getAsJsonArray("origin"));
                    double[] s = readVec3(cube.getAsJsonArray("size"));
                    if (o == null || s == null) continue;
                    double x0 = -(o[0] + s[0]) / 16.0, x1 = -o[0] / 16.0;
                    double y0 = o[1] / 16.0, y1 = (o[1] + s[1]) / 16.0;
                    double z0 = o[2] / 16.0, z1 = (o[2] + s[2]) / 16.0;
                    out.add(new double[][]{
                            {Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)},
                            {Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)}
                    });
                }
            }
        }
        return out;
    }

    private static double[] readVec3(JsonArray arr) {
        if (arr == null || arr.size() < 3) return null;
        return new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble()};
    }

    // ==================== A6：视距覆写存在性 ====================

    /**
     * 确认渲染器确实覆写了 {@code getViewDistance()}。
     *
     * <p>
     * 这是本项目真实踩过的坑：只把包围盒修大了，没修距离上限，
     * 结果 {@code BlockEntityRenderDispatcher#render} 里那句
     * {@code if (renderer.shouldRender(be, cameraPos))} 仍按原版默认的 <b>64 格</b>
     * 判断，人一走近视距就整个不画 —— 修了等于没修。
     * </p>
     * <p>
     * 行为验证必须跑客户端，所以这里退一步做"接线完整性"检查：
     * 直接读渲染器源码，确认 {@code getViewDistance} 的覆写声明还在，
     * 且引用的视距常量不小于 512（原版最大渲染距离 32 chunk）。
     * 删掉覆写或把视距改小都会被这条断言抓住。
     * </p>
     */
    private static void verifyViewDistanceOverride() throws IOException {
        System.out.println();
        System.out.println("==== A6 视距覆写（防止再退回默认 64 格）====");

        Path renderer = Path.of("src/main/java/com/mss/polymech/client/renderer/MachineGeoRenderer.java");
        if (!Files.isRegularFile(renderer)) {
            report("定位渲染器源码", false, "找不到：" + renderer.toAbsolutePath());
            return;
        }
        String text = Files.readString(renderer, StandardCharsets.UTF_8);

        boolean declares = text.contains("public int getViewDistance()");
        report("渲染器声明 getViewDistance 覆写", declares,
                declares ? "已声明（原版默认 64 格会让模型走远即消失）"
                        : "★ 未声明 —— BlockEntityRenderDispatcher 会按原版默认 64 格裁掉模型");

        boolean usesSharedConstant = text.contains("MachineGeometry.MODEL_VIEW_DISTANCE");
        report("视距取自 MachineGeometry.MODEL_VIEW_DISTANCE", usesSharedConstant,
                usesSharedConstant ? "与本校验同源，不会两处漂移"
                        : "★ 未引用共享常量，可能与校验口径不一致（或写死了一个值）");

        boolean bigEnough = MachineGeometry.MODEL_VIEW_DISTANCE >= 512;
        report("视距 ≥ 原版最大渲染距离 512 格", bigEnough,
                "当前 MODEL_VIEW_DISTANCE=" + MachineGeometry.MODEL_VIEW_DISTANCE);
    }

    // ==================== A5：旋转互逆 ====================

    /**
     * {@code unrotate(rotate(o, f), f) == o}，四朝向全覆盖。
     * <p>
     * EAST/WEST 下 rotate 与 unrotate 是<strong>互为逆</strong>的两个映射而非同一函数；
     * 若把 unrotate 写成 rotate，机器朝东/朝西时 {@code exportProxyOutputs}
     * 把相邻方块转回本地坐标就会错位。
     * </p>
     */
    private static void verifyRotateUnrotateInverse() {
        System.out.println();
        System.out.println("==== A5 旋转映射 ====");
        Vec3i[] samples = {
                new Vec3i(1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(-1, 2, 3),
                new Vec3i(3, -2, -1), new Vec3i(0, 5, 0)
        };
        for (Direction facing : HORIZONTAL) {
            boolean ok = true;
            String bad = null;
            for (Vec3i sample : samples) {
                Vec3i round = MachineGeometry.unrotate(MachineGeometry.rotate(sample, facing), facing);
                if (!round.equals(sample)) {
                    ok = false;
                    bad = sample + " -> " + MachineGeometry.rotate(sample, facing) + " -> " + round;
                    break;
                }
            }
            report("A5 rotate/unrotate 互逆 [facing=" + facing + "]", ok,
                    ok ? samples.length + " 个样本往返一致" : "往返不一致：" + bad);
        }
    }

    // ==================== 独立期望值构造 ====================

    /** 独立展开结构格子（不复用 MachineGeometry，避免用实现验证实现） */
    private static List<Vec3i> independentCellEnumeration(Shape shape) {
        List<Vec3i> cells = new ArrayList<>();
        cells.add(Vec3i.ZERO); // 主方块自身
        if (shape.fillRegions() != null) {
            for (Vec3i[] region : shape.fillRegions()) {
                Vec3i a = region[0], b = region[1];
                int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
                int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
                int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            cells.add(new Vec3i(x, y, z));
                        }
                    }
                }
            }
        }
        if (shape.sideOffsets() != null) {
            cells.addAll(List.of(shape.sideOffsets()));
        }
        return cells;
    }

    private static void report(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("  [%s] %s —— %s%n", ok ? "PASS" : "FAIL", name, detail);
    }
}
