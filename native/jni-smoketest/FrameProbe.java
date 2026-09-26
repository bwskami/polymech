// ⚠️ 不能用 `import org.joml.*`：joml 自己也有一个 org.joml.Math，会把 java.lang.Math 遮蔽掉，
//    于是每一处 Math.abs/cos/PI 都变成"引用不明确"而编译失败（本探针第一版就是这样挂的）。
import org.joml.Matrix3f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * 离线核对 {@code CelestialWorld.getRotateFromWorldPos} 里那段"三轴钉死姿态帧"的
 * <b>JOML 语义</b>——不是核对代数（代数已手算过），而是核对三件无法靠读代码确定的事：
 *
 * <ol>
 *   <li>{@code new Matrix3f(a,b,c, d,e,f, g,h,i)} 到底是<b>列主序</b>（三根列 = 三个像）
 *       还是行主序？猜错的话三根轴会整体转置，帧就变成了它的逆 —— 症状正是"天空滚了"。</li>
 *   <li>{@code Quaterniond.setFromNormalized(Matrix3f)} 把这个矩阵当成
 *       <b>local→world</b> 还是 world→local？</li>
 *   <li>{@code .invert()} 之后再作用到"物理东/上/南"上，是否得到世界 +X/+Y/+Z？</li>
 * </ol>
 *
 * <p>用的是与模组<b>同一个 joml 版本</b>（1.10.5），构造方式与源码逐字一致。
 * 运行：
 * <pre>java -cp "&lt;joml.jar&gt;" native/jni-smoketest/FrameProbe.java</pre>
 * 全 PASS 才说明源码里那个写法是对的；任何一条 FAIL 都说明要改写法（而不是去改游戏里的观测）。</p>
 */
public class FrameProbe {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  [%s] %-58s %s%n", ok ? "PASS" : "FAIL", what, detail);
        if (!ok) fails++;
    }

    /** 与源码逐字一致的三轴构造（world→space 的那一版）。 */
    static Quaterniond frameWorldToSpace(Vector3d pole, Vector3d up) {
        Vector3d east = new Vector3d(pole).cross(up);
        if (east.lengthSquared() < 1e-20) {
            throw new IllegalStateException("极点上东无定义");
        }
        east.normalize();
        Vector3d south = new Vector3d(east).cross(up).normalize();
        // ⚠️⚠️ JOML 的 9 参构造是**列主序**：参数顺序 = (列0行0, 列0行1, 列0行2, 列1…)。
        //   也就是**三根列向量依次平铺**：把 (east, up, south) 的分量按"列"排进去。
        //   第一版按"行"排（east.x, up.x, south.x, east.y, …）⇒ 得到的矩阵是目标的**转置**，
        //   而旋转矩阵的转置就是逆 ⇒ 姿态帧整个反了。实测症状（本探针在赤道 lon=0 抓到）：
        //   "物理东"被映射成世界 +Y（天顶）—— 就是"上午的太阳跑偏 90°"这一族的病。
        Matrix3f m = new Matrix3f(
                (float) east.x, (float) east.y, (float) east.z,
                (float) up.x, (float) up.y, (float) up.z,
                (float) south.x, (float) south.y, (float) south.z);
        // 打印 JOML 自己认为的每一列 —— 这一行就把"列主序/行主序"的猜测彻底钉死
        Vector3f c0 = m.getColumn(0, new Vector3f());
        Vector3f c1 = m.getColumn(1, new Vector3f());
        Vector3f c2 = m.getColumn(2, new Vector3f());
        System.out.printf("        传入(东,上,南) = (%s) (%s) (%s)%n",
                fmt(east), fmt(up), fmt(south));
        System.out.printf("        JOML 的列 0/1/2 = (%s) (%s) (%s)%n", fmt(c0), fmt(c1), fmt(c2));
        check("Matrix3f 构造是列主序（列=传入的三根轴）",
                same(c0, east) && same(c1, up) && same(c2, south),
                "若列对不上 ⇒ 构造是行主序，源码必须改");
        Quaterniond q = new Quaterniond().setFromNormalized(m);
        System.out.printf("        setFromNormalized → q=(%.6f, %.6f, %.6f, %.6f)%n",
                q.x, q.y, q.z, q.w);
        return q;
    }

    static boolean same(Vector3f a, Vector3d b) {
        return Math.abs(a.x - b.x) < 1e-5 && Math.abs(a.y - b.y) < 1e-5 && Math.abs(a.z - b.z) < 1e-5;
    }

    static String fmt(Vector3d v) {
        return String.format("%+.4f, %+.4f, %+.4f", v.x, v.y, v.z);
    }

    static String fmt(Vector3f v) {
        return String.format("%+.4f, %+.4f, %+.4f", v.x, v.y, v.z);
    }

    /** 模组里那个方法：返回 frame 的逆，渲染直接把它当 spaceRotation 用。 */
    static Quaterniond getRotateFromWorldPos(Vector3d pole, Vector3d up) {
        return frameWorldToSpace(pole, up).invert();
    }

    public static void main(String[] args) {
        Vector3d pole = new Vector3d(0.0, 1.0, 0.0);

        // 取几个有代表性的观察者位置：赤道 lon=0（映射的坐标原点）、赤道 lon=90°、
        // 中纬度（≈45°）、以及南半球 —— 极点附近另外单列。
        double[][] cases = {
                {0.0, 0.0},        // lat, lon（弧度）
                {0.0, Math.PI / 2},
                {Math.toRadians(45.0), 0.0},
                {Math.toRadians(-30.0), Math.toRadians(120.0)},
        };

        for (double[] c : cases) {
            double lat = c[0];
            double lon = c[1];
            Vector3d up = new Vector3d(
                    Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon)).normalize();
            System.out.printf("%n=== 观察者 lat=%+.1f° lon=%+.1f° ===%n",
                    Math.toDegrees(lat), Math.toDegrees(lon));

            Vector3d east = new Vector3d(pole).cross(up).normalize();
            Vector3d south = new Vector3d(east).cross(up).normalize();

            Quaterniond q = getRotateFromWorldPos(pole, up); // 渲染用的那个（space→世界轴）

            // 渲染里就是 q·(space 向量) = 该方向在 MC 世界轴下的分量。
            Vector3d xImg = q.transform(new Vector3d(east), new Vector3d());
            Vector3d yImg = q.transform(new Vector3d(up), new Vector3d());
            Vector3d zImg = q.transform(new Vector3d(south), new Vector3d());

            check("物理东 → 世界 +X（MC 东）", xImg.x > 0.999 && Math.abs(xImg.y) < 1e-6 && Math.abs(xImg.z) < 1e-6, fmt(xImg));
            check("物理上 → 世界 +Y（上）", yImg.y > 0.999 && Math.abs(yImg.x) < 1e-6 && Math.abs(yImg.z) < 1e-6, fmt(yImg));
            check("物理南 → 世界 +Z（MC 南）", zImg.z > 0.999 && Math.abs(zImg.x) < 1e-6 && Math.abs(zImg.y) < 1e-6, fmt(zImg));

            // 顺带验"没有反射"：行列式为 +1 ⇒ 右手系到右手系，不会镜像。
            Vector3d prod = new Vector3d(east).cross(up);
            check("东 × 上 = 南（右手系，无镜像）", prod.dot(south) > 0.999, String.format("%+.6f", prod.dot(south)));

            // 再验"上午的太阳画在哪边"：上午太阳在物理东 ⇒ 摊到"面朝北站"的人身上，
            // 世界 +X 必须落在他的右手边。用 MC 相机约定从零算一遍：
            //   Camera.setRotation: rotationYXZ(π − yaw·π/180, −pitch·π/180, 0)
            //   yaw：MC 里朝北 = 180°、朝东 = −90°（等价 270°）
            //   view = rotation(cameraRot) · rotation(spaceRotation)，cameraRot = rotation.conjugate()
            // 北 = −Z、东 = +X ⇒ 朝北时相机的前向量应为 (0,0,−1)。
            Quaternionf cam = new Quaternionf().rotationYXZ(
                    (float) Math.PI - (float) Math.toRadians(180.0), 0.0f, 0.0f);
            Vector3f fwd = cam.transform(new Vector3f(0.0f, 0.0f, -1.0f));
            check("相机约定自检：yaw=180° 时前向量 = 世界 −Z（北）",
                    Math.abs(fwd.x) < 1e-5 && Math.abs(fwd.y) < 1e-5 && fwd.z < -0.999,
                    fmt(fwd));

            Vector3f left = cam.transform(new Vector3f(-1.0f, 0.0f, 0.0f)); // 朝北时左手边应为西(−X)
            check("相机约定自检：朝北时左手边 = 世界 −X（西）",
                    left.x < -0.999 && Math.abs(left.y) < 1e-5 && Math.abs(left.z) < 1e-5,
                    fmt(left));

            // 上午的太阳在物理东 = 世界 +X ⇒ 面朝北的人应当把它看在右手边（ndc.x > 0）。
            Vector3d sunInView = rightMultiply(cam, q, east); // 东 → 世界轴 → 相机视空间
            check("面朝北看上午的太阳：在屏幕右侧（ndc.x > 0）", sunInView.x > 0.999, fmt(sunInView));
        }

        System.out.printf("%n%s（失败 %d 条）%n", fails == 0 ? "全部通过 ✔" : "★有失败", fails);
        if (fails != 0) {
            System.exit(1);
        }
    }

    /**
     * 把"空间系方向"经 {@code spaceRotation}（space→世界轴）再经 {@code cameraRot}（世界→视空间）
     * 送进视空间 —— 与渲染的 {@code view = rotation(cameraRot)·rotation(spaceRotation)} 等价。
     */
    static Vector3d rightMultiply(Quaternionf cameraRot, Quaterniond spaceRot, Vector3d spaceDir) {
        Quaterniond a = new Quaterniond(cameraRot.x, cameraRot.y, cameraRot.z, cameraRot.w);
        Vector3d v = spaceRot.transform(new Vector3d(spaceDir), new Vector3d());
        return a.transform(v, new Vector3d());
    }
}
