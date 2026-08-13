package com.mss.polymech.powergrid;

import net.minecraft.network.chat.Component;

/**
 * GT 风格电压等级（范围制）。
 * <p>
 * 每个等级是一个电压区间 (minVoltage, maxVoltage]，1 EU = 4 FE 的 FE 缩放：
 * <pre>
 *   ULV: (0, 32]        LV: (32, 128]        MV: (128, 512]      HV: (512, 2048]
 *   EV:  (2048, 8192]   IV: (8192, 32768]    LuV: (32768, 131072] ZPM: (131072, 524288]
 *   UV:  (524288, 2097152]  UHV: (2097152, 8388608]
 * </pre>
 * 相邻等级上限差 4 倍。等级只是电压数值的"归类标签"：
 * 任意电压可通过 {@link #fromVoltage(int)} 归类到所在区间，
 * 线缆的电压上限可以是任意零散数值（如 64 落在 LV 区间内，
 * 但不完全支持 LV 全范围）。
 * </p>
 */
public enum VoltageTier {

    ULV("ULV", 0, 32),
    LV("LV", 32, 128),
    MV("MV", 128, 512),
    HV("HV", 512, 2048),
    EV("EV", 2048, 8192),
    IV("IV", 8192, 32768),
    LuV("LuV", 32768, 131072),
    ZPM("ZPM", 131072, 524288),
    UV("UV", 524288, 2097152),
    UHV("UHV", 2097152, 8388608);

    private final String name;
    /** 区间下界（不含，等于上一档上限） */
    private final int minVoltage;
    /** 区间上界（含） */
    private final int maxVoltage;

    /** values() 缓存 */
    public static final VoltageTier[] VALUES = values();

    VoltageTier(String name, int minVoltage, int maxVoltage) {
        this.name = name;
        this.minVoltage = minVoltage;
        this.maxVoltage = maxVoltage;
    }

    /** 等级显示名（如 "LV"） */
    public String getName() {
        return name;
    }

    /** 该等级电压区间下界（不含，FE/t） */
    public int getMinVoltage() {
        return minVoltage;
    }

    /** 该等级电压区间上界（含，FE/t） */
    public int getMaxVoltage() {
        return maxVoltage;
    }

    /**
     * 将任意电压数值归类到对应等级（区间制）。
     * 返回第一个 maxVoltage >= voltage 的等级；
     * 超出 UHV 的仍返回 UHV；非正数返回 ULV。
     */
    public static VoltageTier fromVoltage(int voltage) {
        if (voltage <= 0) return ULV;
        for (VoltageTier tier : VALUES) {
            if (voltage <= tier.maxVoltage) return tier;
        }
        return UHV;
    }

    /**
     * 将任意电压数值归类到对应等级（long 版本）。
     */
    public static VoltageTier fromVoltage(long voltage) {
        if (voltage <= 0) return ULV;
        for (VoltageTier tier : VALUES) {
            if (voltage <= tier.maxVoltage) return tier;
        }
        return UHV;
    }

    /** 获取用于聊天显示的 Component（如 "LV (32-128 FE/t)"） */
    public Component getDisplayComponent() {
        return Component.literal(name + " (" + minVoltage + "-" + maxVoltage + " FE/t)");
    }

    @Override
    public String toString() {
        return name;
    }
}
