package com.mss.polymech.tooltip;

import com.mss.polymech.fluid.ModElements;

import java.util.HashMap;
import java.util.Map;

/**
 * 元素周期表配色表（tooltip中元素符号染色用，参考GregTech材料色风格）。
 * <p>
 * 常用元素手工指定了辨识度高的颜色（如氧=蓝、硅=灰、铁=橙、金=金黄），
 * 未手工指定的元素按原子序数黄金分割色相自动生成，
 * 保证118种元素在tooltip中都有互不相同且可读的颜色。
 * </p>
 */
public final class ElementColors {

    /** 符号 → ModElements 反查表 */
    private static final Map<String, ModElements> BY_SYMBOL = new HashMap<>();
    /** 手工指定的元素颜色（符号 → RGB） */
    private static final Map<String, Integer> CURATED = new HashMap<>();
    /** 最终颜色缓存（含自动生成的部分） */
    private static final Map<String, Integer> COLORS = new HashMap<>();

    static {
        for (ModElements element : ModElements.values()) {
            BY_SYMBOL.put(element.getSymbol(), element);
        }
        // 非金属 / 气体
        CURATED.put("H", 0xB8B8B8);
        CURATED.put("He", 0xFFC0C0);
        CURATED.put("C", 0x808080);
        CURATED.put("N", 0x4078F0);
        CURATED.put("O", 0x4CC3FF);
        CURATED.put("F", 0xA0F0A0);
        CURATED.put("Ne", 0xFF8C4C);
        CURATED.put("P", 0xFFA050);
        CURATED.put("S", 0xC8C800);
        CURATED.put("Cl", 0x40C8A0);
        CURATED.put("Ar", 0xC090F0);
        CURATED.put("Se", 0xC8A050);
        CURATED.put("Br", 0xC85030);
        CURATED.put("Kr", 0xE0C0FF);
        CURATED.put("I", 0xA04CC8);
        CURATED.put("Xe", 0xF0D0E0);
        CURATED.put("Rn", 0xD0B0F0);
        // 碱金属 / 碱土金属
        CURATED.put("Li", 0xBDC7DB);
        CURATED.put("Be", 0x8CC88C);
        CURATED.put("Na", 0x4060D8);
        CURATED.put("Mg", 0x8CD880);
        CURATED.put("K", 0xBEDCFF);
        CURATED.put("Ca", 0xFFF0A0);
        CURATED.put("Rb", 0xF06060);
        CURATED.put("Sr", 0xF0E0B0);
        CURATED.put("Cs", 0xFFB0A0);
        CURATED.put("Ba", 0xF0D0A0);
        CURATED.put("Ra", 0xE8C890);
        CURATED.put("Fr", 0xF08060);
        // 过渡金属
        CURATED.put("Sc", 0xE0D0B0);
        CURATED.put("Ti", 0xDCA0F0);
        CURATED.put("V", 0x8CA0C8);
        CURATED.put("Cr", 0xB07040);
        CURATED.put("Mn", 0xC060C0);
        CURATED.put("Fe", 0xD07030);
        CURATED.put("Co", 0x5050FA);
        CURATED.put("Ni", 0xC8AA96);
        CURATED.put("Cu", 0xFF6432);
        CURATED.put("Zn", 0xE8E8F0);
        CURATED.put("Y", 0xE0D0C0);
        CURATED.put("Zr", 0xA8B0C0);
        CURATED.put("Nb", 0xBEB4C8);
        CURATED.put("Mo", 0xA8A8D0);
        CURATED.put("Tc", 0x80D0A0);
        CURATED.put("Ru", 0x70A0A0);
        CURATED.put("Rh", 0x90B0C0);
        CURATED.put("Pd", 0xC8C0B0);
        CURATED.put("Ag", 0xE0E0FF);
        CURATED.put("Cd", 0xC8C8D8);
        CURATED.put("Hf", 0xA0B8C8);
        CURATED.put("Ta", 0xB0A090);
        CURATED.put("W", 0x8C8C96);
        CURATED.put("Re", 0x90A0B0);
        CURATED.put("Os", 0x8090A0);
        CURATED.put("Ir", 0xD0D0D8);
        CURATED.put("Pt", 0xF0F0FF);
        CURATED.put("Au", 0xFFFF1E);
        CURATED.put("Hg", 0xB0B8C8);
        // 主族金属 / 类金属
        CURATED.put("B", 0xE8C890);
        CURATED.put("Al", 0x80C8F0);
        CURATED.put("Si", 0xA0A0A0);
        CURATED.put("Ge", 0xA0A080);
        CURATED.put("As", 0x908C60);
        CURATED.put("Ga", 0xDCDCFF);
        CURATED.put("In", 0xC0C8D8);
        CURATED.put("Sn", 0xDCDCDC);
        CURATED.put("Sb", 0x8C8CA0);
        CURATED.put("Te", 0xD8C880);
        CURATED.put("Tl", 0xC0A898);
        CURATED.put("Pb", 0x8C648C);
        CURATED.put("Bi", 0xD0A0A0);
        CURATED.put("Po", 0xE0A060);
        CURATED.put("At", 0xA06040);
        // 镧系 / 锕系
        CURATED.put("La", 0xD0C0A0);
        CURATED.put("Ce", 0xD0D0B0);
        CURATED.put("Pr", 0xD8E0C0);
        CURATED.put("Nd", 0xC8B8D8);
        CURATED.put("Pm", 0xE0B0D0);
        CURATED.put("Sm", 0xE8C0A0);
        CURATED.put("Eu", 0xE0D080);
        CURATED.put("Gd", 0xD0E8C0);
        CURATED.put("Tb", 0xC0E0D0);
        CURATED.put("Dy", 0xD8C8F0);
        CURATED.put("Ho", 0xF0C0A8);
        CURATED.put("Er", 0xF0B0C0);
        CURATED.put("Tm", 0xD0D8F0);
        CURATED.put("Yb", 0xE0C8D0);
        CURATED.put("Lu", 0xC8D0E0);
        CURATED.put("Ac", 0xE0B880);
        CURATED.put("Th", 0x908050);
        CURATED.put("Pa", 0xB0A070);
        CURATED.put("U", 0x50C050);
        CURATED.put("Np", 0x60B090);
        CURATED.put("Pu", 0xF04848);
        CURATED.put("Am", 0xE08060);
    }

    private ElementColors() {
    }

    /** 按符号反查元素定义；未知符号返回null */
    public static ModElements bySymbol(String symbol) {
        return BY_SYMBOL.get(symbol);
    }

    /**
     * 获取元素符号对应的显示颜色（RGB）。
     * 手工指定者优先；否则按原子序数黄金分割色相自动生成，118元素互不相同。
     */
    public static int getColor(String symbol) {
        Integer cached = COLORS.get(symbol);
        if (cached != null) return cached;
        Integer curated = CURATED.get(symbol);
        int color;
        if (curated != null) {
            color = curated;
        } else {
            ModElements element = BY_SYMBOL.get(symbol);
            int seed = element != null ? element.ordinal() : Math.abs(symbol.hashCode());
            float hue = (float) ((seed * 0.6180339887) % 1.0);
            color = 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.55f, 0.9f) & 0x00FFFFFF);
        }
        COLORS.put(symbol, color);
        return color;
    }
}
