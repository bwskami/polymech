import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成牵引枪悬停框的**棋盘贴图**（参考用 Create 的 {@code AllSpecialTextures.CHECKERED}，
 * 本项目不依赖 Create，所以自带一张 —— 见 docs §31.30 牵引枪第五轮）。
 *
 * <p>规格：16×16、4px 格、白与全透明交替 ⇒ 贴在一个方块面上是 4×4 的棋盘镂空，
 * 比"用几何拼 3×3"更接近参考，而且每个面只要 1 个四边形（几何拼法要 27 个）。</p>
 *
 * <p>用法（幂等，重复跑结果一致）：</p>
 * <pre>java native/jni-smoketest/MakeCheckerTexture.java [输出路径]</pre>
 */
public final class MakeCheckerTexture {

    private static final int SIZE = 16;
    private static final int CELL = 4;
    /** 白（不透明）与全透明交替。 */
    private static final int ON = 0xFFFFFFFF;
    private static final int OFF = 0x00FFFFFF;

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean on = ((x / CELL) + (y / CELL)) % 2 == 0;
                img.setRGB(x, y, on ? ON : OFF);
            }
        }
        File out = new File(args.length > 0 ? args[0]
                : "src/main/resources/assets/poly_mech/textures/misc/physgun_checker.png");
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(img, "PNG", out);
        System.out.println("wrote " + out.getAbsolutePath() + "  (" + out.length() + " bytes, "
                + SIZE + "x" + SIZE + ", " + CELL + "px 格)");
    }
}
