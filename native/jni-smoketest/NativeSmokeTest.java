import com.mss.polymech.physics.NativePhysics;

/**
 * 原生库 JNI 冒烟测试（不依赖 Minecraft，供 CI 与本地手动验证使用）。
 *
 * 用法：java -cp <classes> NativeSmokeTest &lt;原生库绝对路径&gt;
 * 退出码 0 表示通过。
 */
public class NativeSmokeTest {

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("FAIL: " + message);
            System.exit(1);
        }
        System.out.println("ok: " + message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: NativeSmokeTest <原生库路径>");
            System.exit(2);
        }
        System.load(args[0]);

        int abi = NativePhysics.abiVersion();
        check(abi == 1, "ABI 版本 = " + abi);

        double restY = NativePhysics.selftest(300);
        check(Math.abs(restY - 0.5) < 0.05, String.format("原生落体静止高度 %.4f ≈ 0.5", restY));

        long world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        check(world > 0, "worldCreate -> " + world);
        try {
            NativePhysics.worldSetTimestep(world, 1.0 / 60.0);

            // 体素平台：6 x 2 x 6
            long[] cells = new long[6 * 2 * 6];
            int i = 0;
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 6; z++) {
                        cells[i++] = NativePhysics.packCell(x, y, z);
                    }
                }
            }
            long platform = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    0, 0, 0, 0, 0, 0, 1, 0);
            long voxelCollider = NativePhysics.colliderAttachVoxels(world, platform,
                    1.0, 1.0, 1.0, cells, 0.8, 0.0);
            check(voxelCollider > 0, "体素碰撞体创建 -> " + voxelCollider);

            long cube = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    3.0, 7.0, 3.0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.6, 0.0);

            double[] pos = new double[3];
            for (int s = 0; s < 600; s++) {
                NativePhysics.worldStep(world);
            }
            NativePhysics.bodyReadTranslation(world, cube, pos);
            check(Math.abs(pos[1] - 2.5) < 0.08,
                    String.format("体素平台落点 y=%.4f ≈ 2.5（平台顶 2.0 + 半高 0.5）", pos[1]));

            int bodies = NativePhysics.worldBodyCount(world);
            check(bodies == 2, "刚体数 = " + bodies);
        } finally {
            NativePhysics.worldDestroy(world);
        }

        System.out.println("SMOKE TEST PASSED");
    }
}
