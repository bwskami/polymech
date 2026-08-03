package com.mss.polymech.api.material;

import net.minecraft.world.level.block.SoundType;

/**
 * 传送带材质枚举。
 * <p>
 * 与 {@link PipeMaterial} 相同的数据驱动模式：
 * 每种材质生成一个传送带方块变体，默认材质（IRON）注册名为 {@code conveyor}，
 * 其余材质注册名为 {@code <material>_conveyor}（如 bronze_conveyor）。
 * </p>
 * <p>
 * 方块/物品的染色由 {@code assets/poly_mech/config/colors.json} 按注册名映射，
 * 模型纹理使用 {@code material_sets} 下的灰度染色贴图 + tintindex。
 * </p>
 */
public enum ConveyorMaterial {
    IRON("iron", 2.0F, 3.0F, SoundType.METAL),
    BRONZE("bronze", 2.0F, 3.0F, SoundType.METAL),
    STAINLESS_STEEL("stainless_steel", 2.5F, 4.0F, SoundType.METAL),
    BRASS("brass", 2.0F, 3.0F, SoundType.METAL);

    private final String name;
    private final float strength;
    private final float resistance;
    private final SoundType soundType;

    ConveyorMaterial(String name, float strength, float resistance, SoundType soundType) {
        this.name = name;
        this.strength = strength;
        this.resistance = resistance;
        this.soundType = soundType;
    }

    public String getName() { return name; }
    public float getStrength() { return strength; }
    public float getResistance() { return resistance; }
    public SoundType getSoundType() { return soundType; }

    /**
     * 传送带方块注册名。默认材质无前缀，其余材质带前缀。
     */
    public String getConveyorRegistryName() {
        return this == IRON ? "conveyor" : name + "_conveyor";
    }
}
