package com.mss.polymech.client.gui.widget.planet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 地球表色离线预览：等距圆柱投影（经度 x 纬度）渲染 EARTH colorProvider 的输出。
 * 与 PlanetRenderObject.precomputeSurface 相同的输入：
 *   noise seed = 0x5EED1234 + 3*0x1234567（earth surfaceSeed=3）
 *   height     = PlanetHeight(3, mesh, 0.120f, freqOverride).rawHeight()
 * 用法：java EarthPreview <freq> <out.png>
 */
public final class EarthPreview {
    public static void main(String[] args) throws Exception {
        float freq = args.length > 0 ? Float.parseFloat(args[0]) : 2.5f;
        String out = args.length > 1 ? args[1] : "earth_preview.png";

        // PlanetHeight 的构造逻辑（freqOverride>0 时直接用），pi=3, heightScale=0.120
        Noise3 hNoise = new Noise3(0x5EED1234L + 3 * 0x1234567L + 0x9E3779B9L);
        Noise3 cNoise = new Noise3(0x5EED1234L + 3 * 0x1234567L);
        PlanetColorProvider provider = PlanetColorProvider.EARTH;

        int W = 1024, H = 512;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < H; py++) {
            double latA = Math.PI * (0.5 - (double) py / H); // +90..-90
            float cy = (float) Math.sin(latA);
            float cosLat = (float) Math.cos(latA);
            for (int px = 0; px < W; px++) {
                double lon = 2 * Math.PI * ((double) px / W - 0.5);
                float cx = (float) (Math.cos(lon) * cosLat);
                float cz = (float) (Math.sin(lon) * cosLat);
                // PlanetHeight.rawHeight: noise.fbm(x*freq+7.7, y*freq+13.3, z*freq+5.1) - 0.5
                float height = hNoise.fbm(cx * freq + 7.7f, cy * freq + 13.3f, cz * freq + 5.1f) - 0.5f;
                float[] c = provider.compute(0, cx, cy, cz, Math.abs(cy), height, cNoise);
                int rr = (int) (clamp(c[0]) * 255);
                int gg = (int) (clamp(c[1]) * 255);
                int bb = (int) (clamp(c[2]) * 255);
                img.setRGB(px, py, (rr << 16) | (gg << 8) | bb);
            }
        }
        ImageIO.write(img, "png", new File(out));
        System.out.println("written: " + out);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
