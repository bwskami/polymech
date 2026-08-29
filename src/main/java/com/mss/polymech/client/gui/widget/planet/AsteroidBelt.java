package com.mss.polymech.client.gui.widget.planet;

import java.util.ArrayList;
import java.util.List;

/**
 * 小行星带数据：封装碎石的位置、大小、颜色、形状种子。
 * <p>
 * 替代原先 {@code float[][]} 魔数数组，每个字段语义清晰。
 */
public final class AsteroidBelt {

    /** 单颗碎石数据（不可变值对象） */
    public record Asteroid(
            float angle,      // 初始轨道角度
            float radius,     // 轨道半径
            float yPos,       // 轨道面垂直偏移
            float size,       // 渲染尺寸
            float tiltA,      // 自转角A
            float tiltB,      // 自转角B
            float r, float g, float b,  // 颜色
            int seed          // 形状随机种子
    ) {}

    private final List<Asteroid> asteroids;
    private final float innerRadius;
    private final float outerRadius;

    private AsteroidBelt(List<Asteroid> asteroids, float innerRadius, float outerRadius) {
        this.asteroids = List.copyOf(asteroids);
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
    }

    public List<Asteroid> asteroids() { return asteroids; }
    public int size() { return asteroids.size(); }
    public float innerRadius() { return innerRadius; }
    public float outerRadius() { return outerRadius; }

    /**
     * 获取碎石的轨道半径范围（用于 drawBeltBand 星环渲染）。
     */
    public float[] radiusRange() {
        return new float[]{innerRadius, outerRadius};
    }

    // ==================== 工厂方法 ====================

    /**
     * 生成小行星带（火星与木星之间）。
     */
    public static AsteroidBelt mainBelt(long seed, int count) {
        float inner = 50f, outer = 64f;
        return generate(seed, count, inner, outer, 0.8f,
                bright -> new float[]{bright, bright * 0.85f, bright * 0.70f});
    }

    /**
     * 生成柯伊伯带（海王星外侧）。
     */
    public static AsteroidBelt kuiperBelt(long seed, int count) {
        float inner = 230f, outer = 280f;
        return generate(seed, count, inner, outer, 1.0f,
                bright -> new float[]{bright * 0.7f, bright * 0.75f, bright * 0.85f});
    }

    private interface ColorFactory {
        float[] create(float bright);
    }

    private static AsteroidBelt generate(long seed, int count, float inner, float outer,
                                         float ySpread, ColorFactory colorFactory) {
        List<Asteroid> list = new ArrayList<>(count);
        long rng = seed;
        float range = outer - inner;
        for (int i = 0; i < count; i++) {
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float angle = ((int) (rng >>> 33)) / (float) (1L << 31) * 6.2832f;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float radius = inner + ((int) (rng >>> 33)) / (float) (1L << 31) * range;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float yPos = (((int) (rng >>> 33)) / (float) (1L << 31) - 0.5f) * ySpread;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float sz = 0.10f + ((int) (rng >>> 33)) / (float) (1L << 31) * 0.20f;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float tiltA = ((int) (rng >>> 33)) / (float) (1L << 31) * 6.2832f;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float tiltB = ((int) (rng >>> 33)) / (float) (1L << 31) * 6.2832f;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float bright = 0.30f + ((int) (rng >>> 33)) / (float) (1L << 31) * 0.35f;
            float[] rgb = colorFactory.create(bright);
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            int seedHash = (int) rng;
            list.add(new Asteroid(angle, radius, yPos, sz, tiltA, tiltB,
                    rgb[0], rgb[1], rgb[2], seedHash));
        }
        return new AsteroidBelt(list, inner, outer);
    }
}
