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
 * <p>
 * 速度与物品包容量（多等级传输性能）：
 * <ul>
 *   <li>IRON: 1/16 格/tick，包上限 16</li>
 *   <li>BRONZE: 1/12 格/tick，包上限 32</li>
 *   <li>STAINLESS_STEEL: 1/10 格/tick，包上限 64</li>
 *   <li>BRASS: 1/8 格/tick，包上限 128</li>
 * </ul>
 * </p>
 */
public enum ConveyorMaterial {
    IRON("iron", 2.0F, 3.0F, SoundType.METAL, 1.0D / 16.0D, 16),
    BRONZE("bronze", 2.0F, 3.0F, SoundType.METAL, 1.0D / 12.0D, 32),
    STAINLESS_STEEL("stainless_steel", 2.5F, 4.0F, SoundType.METAL, 1.0D / 10.0D, 64),
    BRASS("brass", 2.0F, 3.0F, SoundType.METAL, 1.0D / 8.0D, 128);

    private final String name;
    private final float strength;
    private final float resistance;
    private final SoundType soundType;

    /** 每 tick 前进的格数（格/tick） */
    private final double beltSpeed;

    /** 单个物品包的同种物品堆叠上限 */
    private final int stackLimit;

    ConveyorMaterial(String name, float strength, float resistance, SoundType soundType,
                     double beltSpeed, int stackLimit) {
        this.name = name;
        this.strength = strength;
        this.resistance = resistance;
        this.soundType = soundType;
        this.beltSpeed = beltSpeed;
        this.stackLimit = stackLimit;
    }

    public String getName() { return name; }
    public float getStrength() { return strength; }
    public float getResistance() { return resistance; }
    public SoundType getSoundType() { return soundType; }

    /** 每 tick 前进的格数（格/tick） */
    public double getBeltSpeed() { return beltSpeed; }

    /** 单个物品包的同种物品堆叠上限 */
    public int getStackLimit() { return stackLimit; }

    /**
     * 传送带方块注册名。默认材质无前缀，其余材质带前缀。
     */
    public String getConveyorRegistryName() {
        return this == IRON ? "conveyor" : name + "_conveyor";
    }

    /**
     * 根据传送带方块反查材质。
     *
     * @return 对应的材质；无法识别时回退为 {@link #IRON}
     */
    public static ConveyorMaterial fromBlock(net.minecraft.world.level.block.Block block) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) return IRON;
        String path = key.getPath();
        if (path.equals("conveyor")) return IRON;
        if (path.endsWith("_conveyor")) {
            String prefix = path.substring(0, path.length() - "_conveyor".length());
            for (ConveyorMaterial material : values()) {
                if (material.getName().equals(prefix)) return material;
            }
        }
        return IRON;
    }
}
