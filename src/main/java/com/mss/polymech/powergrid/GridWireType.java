package com.mss.polymech.powergrid;

import com.mss.polymech.item.ModItems;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

/**
 * 电线类型。
 * <p>
 * 定义电线的传输属性：最大拉线长度、单位电阻、弧垂系数、粗细与渲染颜色。
 * 每种金属有两个变体：裸线（细、灰度绞线模板）与绝缘线
 * （比裸线粗但仍比裸线旧模型细、颜色加深、用白色绝缘模板染色）。
 * 世界内电线渲染走"白色/灰度模板 + 本颜色顶点tint"的染色方案；
 * 每种类型注册一个线轴物品（见ModItems的数据驱动循环）。
 * NBT按枚举name持久化，新增类型不破坏旧存档。
 * </p>
 */
public enum GridWireType {
    // ===== 裸线（基础变体，粗细为调细后的新模型） =====
    /** 铜线：价格适中、电阻较低，入门级电线 */
    COPPER("copper", 48, 0.02, 1.0f, 0.03f, 0xFFB87333),
    /** 铁线：成本低、电阻高，应急/短距传输 */
    IRON("iron", 32, 0.06, 1.0f, 0.035f, 0xFF9AA0A6),
    /** 银金合金（琥珀金）线：低电阻长距离输电，高级电线 */
    ELECTRUM("electrum", 64, 0.008, 1.0f, 0.025f, 0xFFE6C35C),
    /** 银线：电阻最低档的常见金属 */
    SILVER("silver", 48, 0.012, 1.0f, 0.028f, 0xFFD8DEE4),
    /** 金线：电阻低、成本高 */
    GOLD("gold", 48, 0.014, 1.0f, 0.028f, 0xFFFFD700),
    /** 铝线：轻、电阻低 */
    ALUMINIUM("aluminium", 40, 0.03, 1.0f, 0.025f, 0xFFD9E4EE),
    /** 铂线：低电阻、耐腐蚀 */
    PLATINUM("platinum", 40, 0.03, 1.0f, 0.025f, 0xFFB5C7C9),
    /** 青铜线：均衡的早期合金线 */
    BRONZE("bronze", 44, 0.035, 1.0f, 0.03f, 0xFFCD7F32),
    /** 黄铜线 */
    BRASS("brass", 40, 0.04, 1.0f, 0.03f, 0xFFCBA32B),
    /** 锡线 */
    TIN("tin", 36, 0.05, 1.0f, 0.03f, 0xFFAEBFC0),
    /** 白铜线 */
    CUPRONICKEL("cupronickel", 40, 0.05, 1.0f, 0.03f, 0xFFBCA99B),
    /** 镍线 */
    NICKEL("nickel", 36, 0.055, 1.0f, 0.03f, 0xFFCFD6C3),
    /** 锌线 */
    ZINC("zinc", 32, 0.06, 1.0f, 0.03f, 0xFFBCC9C2),
    /** 殷钢合金线 */
    INVAR("invar", 40, 0.07, 1.0f, 0.03f, 0xFFA9B2A8),
    /** 钢线：强度高、弧垂小 */
    STEEL("steel", 40, 0.08, 0.8f, 0.03f, 0xFF8FA1B0),
    /** 不锈钢线：弧垂小、耐环境 */
    STAINLESS_STEEL("stainless_steel", 44, 0.09, 0.8f, 0.03f, 0xFFC6CED4),
    /** 铅线：重、电阻高、便宜 */
    LEAD("lead", 24, 0.09, 1.2f, 0.035f, 0xFF5E6673),
    /** 钨线：耐高温、短距 */
    TUNGSTEN("tungsten", 24, 0.05, 0.8f, 0.025f, 0xFF474747),

    // ===== 绝缘变体：电气参数与对应裸线相同，颜色加深、绝缘外皮略粗 =====
    COPPER_INSULATED(COPPER),
    IRON_INSULATED(IRON),
    ELECTRUM_INSULATED(ELECTRUM),
    SILVER_INSULATED(SILVER),
    GOLD_INSULATED(GOLD),
    ALUMINIUM_INSULATED(ALUMINIUM),
    PLATINUM_INSULATED(PLATINUM),
    BRONZE_INSULATED(BRONZE),
    BRASS_INSULATED(BRASS),
    TIN_INSULATED(TIN),
    CUPRONICKEL_INSULATED(CUPRONICKEL),
    NICKEL_INSULATED(NICKEL),
    ZINC_INSULATED(ZINC),
    INVAR_INSULATED(INVAR),
    STEEL_INSULATED(STEEL),
    STAINLESS_STEEL_INSULATED(STAINLESS_STEEL),
    LEAD_INSULATED(LEAD),
    TUNGSTEN_INSULATED(TUNGSTEN);

    /** 绝缘线粗细系数：绝缘线半径 = 同金属裸线半径 × 该系数（仍比裸线旧模型细） */
    public static final float INSULATED_THICKNESS_FACTOR = 1.4F;
    /** 绝缘线颜色加深系数：RGB各通道 × 该系数（线轴染色与世界电线渲染共用） */
    public static final float INSULATED_COLOR_FACTOR = 0.7F;

    private final String metalName;
    private final boolean insulated;
    private final String spoolItemName;
    private final int maxLength;
    private final double resistance;
    private final float sag;
    private final float thickness;
    private final int color;

    /** 裸线（基础变体）构造 */
    GridWireType(String metalName, int maxLength, double resistance, float sag, float thickness, int color) {
        this.metalName = metalName;
        this.insulated = false;
        this.spoolItemName = metalName + "_wire_spool";
        this.maxLength = maxLength;
        this.resistance = resistance;
        this.sag = sag;
        this.thickness = thickness;
        this.color = color;
    }

    /** 绝缘变体构造：复用对应裸线的电气参数，颜色加深、半径按系数放大 */
    GridWireType(GridWireType base) {
        this.metalName = base.metalName;
        this.insulated = true;
        this.spoolItemName = base.metalName + "_insulated_wire_spool";
        this.maxLength = base.maxLength;
        this.resistance = base.resistance;
        this.sag = base.sag;
        this.thickness = base.thickness * INSULATED_THICKNESS_FACTOR;
        this.color = darken(base.color, INSULATED_COLOR_FACTOR);
    }

    /** ARGB颜色RGB各通道加深（×factor），保留alpha */
    public static int darken(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.round(((argb >> 16) & 0xFF) * factor);
        int g = Math.round(((argb >> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 金属材料名（与锭/染色材质/翻译共用，如 invar） */
    public String metalName() {
        return metalName;
    }

    /** 是否为绝缘变体 */
    public boolean isInsulated() {
        return insulated;
    }

    /** 对应的线轴物品注册名 */
    public String spoolItemName() {
        return spoolItemName;
    }

    /** 最大拉线距离（格） */
    public int getMaxLength() {
        return maxLength;
    }

    /** 每格电阻（Ω） */
    public double getResistance() {
        return resistance;
    }

    /** 弧垂系数（传给弧垂算法，dip=sag 时最大下坠≈0.05*distance*sag 格） */
    public float getSag() {
        return sag;
    }

    /** 电线半径（格） */
    public float getThickness() {
        return thickness;
    }

    /** 渲染颜色（ARGB；世界内电线与线轴物品均按此颜色对模板染色，绝缘变体已加深） */
    public int getColor() {
        return color;
    }

    /** 获取对应的线轴物品（注册完成后有效） */
    public Item getSpoolItem() {
        return ModItems.getWireSpoolItem(this);
    }

    /** 网络流编解码器 */
    public static final StreamCodec<RegistryFriendlyByteBuf, GridWireType> STREAM_CODEC = StreamCodec.of(
            (buf, type) -> buf.writeByte(type.ordinal()),
            buf -> values()[buf.readByte()]
    );
}
