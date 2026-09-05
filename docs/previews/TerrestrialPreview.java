package com.mss.polymech.client.gui.widget.planet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 类地引擎多样性预览：同一引擎、不同 (seed, dryness, ice)，
 * 验证每颗类地星球布局不同但细节水准一致。
 * 用法：java TerrestrialPreview <freq> <outPrefix>
 */
public final class TerrestrialPreview {
    record Params(long seed, float dryness, float ice, String name) {}

    public static void main(String[] args) throws Exception {
        float freq = args.length > 0 ? Float.parseFloat(args[0]) : 2.5f;
        String prefix = args.length > 1 ? args[1] : "terr";
        Params[] cases = {
                new Params(0L,   0.50f, 0.50f, "earth_std"),   // 地球（必须与之前逐像素一致）
                new Params(42L,  0.50f, 0.50f, "earthlike_b"), // 另一颗"地球型"：布局应完全不同
                new Params(7L,   0.90f, 0.10f, "desert"),      // 沙漠世界
                new Params(99L,  0.30f, 0.90f, "iceball"),     // 冰封世界
                new Params(5L,   0.15f, 0.40f, "jungle"),      // 湿润丛林世界
        };
        Noise3 hNoise = new Noise3(0x5EED1234L + 3 * 0x1234567L + 0x9E3779B9L);
        Noise3 cNoise = new Noise3(0x5EED1234L + 3 * 0x1234567L);
        int caseIdx = 0;
        int W = 720, H = 360;
        for (Params p : cases) {
            // 每颗星球独立的高度场/色彩噪声种子（模拟游戏内 PlanetHeight / surfaceSeed 按行星区分）
            Noise3 hN = caseIdx == 0 ? hNoise : new Noise3(0x5EED1234L + (3 + caseIdx * 7) * 0x1234567L + 0x9E3779B9L);
            Noise3 cN = caseIdx == 0 ? cNoise : new Noise3(0x5EED1234L + (3 + caseIdx * 7) * 0x1234567L);
            caseIdx++;
            PlanetColorProvider provider = PlanetColorProvider.terrestrial(p.seed, p.dryness, p.ice);
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            for (int py = 0; py < H; py++) {
                double latA = Math.PI * (0.5 - (double) py / H);
                float cy = (float) Math.sin(latA), cl = (float) Math.cos(latA);
                for (int px = 0; px < W; px++) {
                    double lon = 2 * Math.PI * ((double) px / W - 0.5);
                    float cx = (float) (Math.cos(lon) * cl), cz = (float) (Math.sin(lon) * cl);
                    float height = hN.fbm(cx * freq + 7.7f, cy * freq + 13.3f, cz * freq + 5.1f) - 0.5f;
                    float[] c = provider.compute(0, cx, cy, cz, Math.abs(cy), height, cN);
                    int rr = (int) (Math.max(0, Math.min(1, c[0])) * 255);
                    int gg = (int) (Math.max(0, Math.min(1, c[1])) * 255);
                    int bb = (int) (Math.max(0, Math.min(1, c[2])) * 255);
                    img.setRGB(px, py, (rr << 16) | (gg << 8) | bb);
                }
            }
            String out = prefix + "_" + p.name + ".png";
            ImageIO.write(img, "png", new File(out));
            System.out.println("written: " + out);
        }
    }
}
