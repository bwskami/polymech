package com.mss.polymech.client.gui.widget.planet;

/**
 * 一个恒星系的目录数据：真实名称 + 恒星光谱 + 位置 + 超空间航道连接。
 * 渲染时每个 StarSystem 生成独立的 SolarSystem 场景（只保留当前星系）。
 */
public final class StarSystem {
    public final String name;        // 中文名
    public final String nameEn;      // 英文名
    public final long seed;          // 程序化生成种子
    public final StarSpec star;
    public final float x, y, z;      // 星图位置（光年，用于绘制航道箭头）
    public final int[] hyperlanes;   // 连接的星系索引（目录内下标）

    public StarSystem(String name, String nameEn, long seed, StarSpec star,
                      float x, float y, float z, int[] hyperlanes) {
        this.name = name;
        this.nameEn = nameEn;
        this.seed = seed;
        this.star = star;
        this.x = x; this.y = y; this.z = z;
        this.hyperlanes = hyperlanes;
    }
}
