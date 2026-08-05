package com.mss.polymech.api.material;

import com.mss.polymech.api.item.ModItemTypes;
import net.minecraft.world.level.block.SoundType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管道材质注册表（数据驱动）。
 * <p>
 * 不再是手写枚举：原版金属（铁/铜/金）+ {@link MaterialRegistry} 中所有含锭材料
 * （单质金属与合金）在类加载时自动注册为管道材质。
 * 铁保留无前缀注册名（pipe、small_pipe……）以兼容已有存档。
 * </p>
 * <p>
 * 粉状金属（dust-only）无锭形态，不生成管道。
 * </p>
 */
public final class PipeMaterial {
    /** 全部管道材质（注册顺序即创造物品栏顺序） */
    private static final List<PipeMaterial> ALL = new ArrayList<>();
    /** 注册名 → 材质（LinkedHashMap 保持注册顺序） */
    private static final Map<String, PipeMaterial> BY_NAME = new LinkedHashMap<>();

    private final String name;
    private final float strength;
    private final float resistance;
    /** 流体吞吐倍率，与尺寸基准流速相乘得到实际流速 */
    private final float throughputMultiplier;
    private final SoundType soundType;

    private PipeMaterial(String name, float strength, float resistance, float throughputMultiplier, SoundType soundType) {
        this.name = name;
        this.strength = strength;
        this.resistance = resistance;
        this.throughputMultiplier = throughputMultiplier;
        this.soundType = soundType;
    }

    private static PipeMaterial register(String name, float strength, float resistance, float throughputMultiplier) {
        PipeMaterial material = new PipeMaterial(name, strength, resistance, throughputMultiplier, SoundType.METAL);
        ALL.add(material);
        BY_NAME.put(name, material);
        return material;
    }

    static {
        // ========== 原版金属（不在MaterialRegistry中，不注册锭等物品，仅作为管道/零件材质） ==========
        IRON = register("iron", 3.0F, 6.0F, 1.0F);
        COPPER = register("copper", 3.0F, 6.0F, 1.0F);
        GOLD = register("gold", 3.0F, 6.0F, 0.75F); // 金质偏软，流速略低

        // ========== 自动注册：MaterialRegistry中所有含锭材料 ==========
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            if (BY_NAME.containsKey(materialName) || !ModItemTypes.hasIngot(materialName)) continue;
            // 保留原有四种材质的既定属性，其余材质取统一默认值
            float strength = materialName.equals("stainless_steel") ? 4.0F : 3.0F;
            float resistance = materialName.equals("stainless_steel") ? 8.0F : 6.0F;
            float throughput = switch (materialName) {
                case "stainless_steel" -> 1.5F;
                case "brass" -> 1.25F;
                default -> 1.0F;
            };
            register(materialName, strength, resistance, throughput);
        }
    }

    /** 铁（默认材质，注册名无前缀：pipe/small_pipe/big_pipe/huge_pipe） */
    public static final PipeMaterial IRON;
    /** 铜（原版金属） */
    public static final PipeMaterial COPPER;
    /** 金（原版金属） */
    public static final PipeMaterial GOLD;

    public String getName() { return name; }
    public float getStrength() { return strength; }
    public float getResistance() { return resistance; }
    public float getThroughputMultiplier() { return throughputMultiplier; }
    public SoundType getSoundType() { return soundType; }

    /** 全部管道材质的只读列表 */
    public static List<PipeMaterial> getAll() {
        return Collections.unmodifiableList(ALL);
    }

    /**
     * 按注册名查找材质，找不到返回 null（用于序列化反查）。
     */
    public static PipeMaterial byName(String name) {
        return BY_NAME.get(name);
    }
}
