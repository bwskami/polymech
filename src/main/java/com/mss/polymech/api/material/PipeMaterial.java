package com.mss.polymech.api.material;

import net.minecraft.world.level.block.SoundType;

public enum PipeMaterial {
    IRON("iron", 3.0F, 6.0F, 1.0F, SoundType.METAL),
    BRONZE("bronze", 3.0F, 6.0F, 1.0F, SoundType.METAL),
    STAINLESS_STEEL("stainless_steel", 4.0F, 8.0F, 1.5F, SoundType.METAL),
    BRASS("brass", 3.0F, 6.0F, 1.25F, SoundType.METAL);

    private final String name;
    private final float strength;
    private final float resistance;
    /** 流体吞吐倍率，与尺寸基准流速相乘得到实际流速 */
    private final float throughputMultiplier;
    private final SoundType soundType;

    PipeMaterial(String name, float strength, float resistance, float throughputMultiplier, SoundType soundType) {
        this.name = name;
        this.strength = strength;
        this.resistance = resistance;
        this.throughputMultiplier = throughputMultiplier;
        this.soundType = soundType;
    }

    public String getName() { return name; }
    public float getStrength() { return strength; }
    public float getResistance() { return resistance; }
    public float getThroughputMultiplier() { return throughputMultiplier; }
    public SoundType getSoundType() { return soundType; }

    /**
     * 按注册名查找材质，找不到返回 null（用于序列化反查）。
     */
    public static PipeMaterial byName(String name) {
        for (PipeMaterial m : values()) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }
}