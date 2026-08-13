package com.mss.polymech.powergrid;

import com.mss.polymech.item.ModItems;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

/**
 * 电线类型。
 * <p>
 * 定义电线的传输属性：最大拉线长度、单位电阻、弧垂系数、粗细与渲染颜色，
 * 以及电气属性：<b>电压上限（任意数值，FE/t）</b>与最大载流量（安培数）。
 * </p>
 * <p>
 * 本模组线缆的独特之处：<b>电压上限不是绑定等级，而是任意数值</b>。
 * 例如铁线电压上限 64 —— 落在 LV 区间 (32,128] 内（归类为 LV 等级），
 * 但它并不能完整支持 LV 的 128 电压，只是"LV 中的低配线"。
 * 电网电压超过线缆电压上限时过压熔断。
 * </p>
 * <p>
 * 每种金属有两个变体：裸线（细、灰度绞线模板）与绝缘线
 * （比裸线粗但仍比裸线旧模型细、颜色加深、用白色绝缘模板染色）。
 * 世界内电线渲染走"白色/灰度模板 + 本颜色顶点tint"的染色方案；
 * 每种类型注册一个线轴物品（见ModItems的数据驱动循环）。
 * NBT按枚举name持久化，新增类型不破坏旧存档。
 * </p>
 */
public enum GridWireType {
    // ===== 裸线（基础变体，粗细为调细后的新模型） =====
    // 参数: metalName, maxLength, resistance, sag, thickness, color, maxVoltage, maxAmperage
    /** 铜线：价格适中、电阻较低，入门级电线（满 LV） */
    COPPER("copper", 48, 0.02, 1.0f, 0.03f, 0xFFB87333, 128, 16),
    /** 铁线：成本低、电阻高，应急/短距传输（LV 低配，电压上限 64） */
    IRON("iron", 32, 0.06, 1.0f, 0.035f, 0xFF9AA0A6, 64, 8),
    /** 银金合金（琥珀金）线：低电阻长距离输电（满 MV） */
    ELECTRUM("electrum", 64, 0.008, 1.0f, 0.025f, 0xFFE6C35C, 512, 32),
    /** 银线：电阻最低档的常见金属（MV 中配） */
    SILVER("silver", 48, 0.012, 1.0f, 0.028f, 0xFFD8DEE4, 256, 24),
    /** 金线：电阻低、成本高（MV 中配） */
    GOLD("gold", 48, 0.014, 1.0f, 0.028f, 0xFFFFD700, 256, 24),
    /** 铝线：轻、电阻低（MV 低配，电压上限 192） */
    ALUMINIUM("aluminium", 40, 0.03, 1.0f, 0.025f, 0xFFD9E4EE, 192, 16),
    /** 铂线：低电阻、耐腐蚀（HV 中配） */
    PLATINUM("platinum", 40, 0.03, 1.0f, 0.025f, 0xFFB5C7C9, 1024, 16),
    /** 青铜线：均衡的早期合金线（满 LV） */
    BRONZE("bronze", 44, 0.035, 1.0f, 0.03f, 0xFFCD7F32, 128, 16),
    /** 黄铜线（LV 低配，电压上限 96） */
    BRASS("brass", 40, 0.04, 1.0f, 0.03f, 0xFFCBA32B, 96, 12),
    /** 锡线（满 ULV） */
    TIN("tin", 36, 0.05, 1.0f, 0.03f, 0xFFAEBFC0, 32, 8),
    /** 白铜线（LV 中配，电压上限 160） */
    CUPRONICKEL("cupronickel", 40, 0.05, 1.0f, 0.03f, 0xFFBCA99B, 160, 12),
    /** 镍线（LV 低配，电压上限 96） */
    NICKEL("nickel", 36, 0.055, 1.0f, 0.03f, 0xFFCFD6C3, 96, 12),
    /** 锌线（满 ULV） */
    ZINC("zinc", 32, 0.06, 1.0f, 0.03f, 0xFFBCC9C2, 32, 8),
    /** 殷钢合金线（满 LV） */
    INVAR("invar", 40, 0.07, 1.0f, 0.03f, 0xFFA9B2A8, 128, 16),
    /** 钢线：强度高、弧垂小（满 MV） */
    STEEL("steel", 40, 0.08, 0.8f, 0.03f, 0xFF8FA1B0, 512, 16),
    /** 不锈钢线：弧垂小、耐环境（HV 中配） */
    STAINLESS_STEEL("stainless_steel", 44, 0.09, 0.8f, 0.03f, 0xFFC6CED4, 1024, 16),
    /** 铅线：重、电阻高、便宜（满 ULV） */
    LEAD("lead", 24, 0.09, 1.2f, 0.035f, 0xFF5E6673, 32, 4),
    /** 钨线：耐高温、短距（满 EV） */
    TUNGSTEN("tungsten", 24, 0.05, 0.8f, 0.025f, 0xFF474747, 8192, 8),

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
    /** 电压上限（FE/t），任意零散数值，不绑定等级 */
    private final int maxVoltage;
    private final int maxAmperage;

    /** 裸线（基础变体）构造 */
    GridWireType(String metalName, int maxLength, double resistance, float sag, float thickness, int color,
                 int maxVoltage, int maxAmperage) {
        this.metalName = metalName;
        this.insulated = false;
        this.spoolItemName = metalName + "_wire_spool";
        this.maxLength = maxLength;
        this.resistance = resistance;
        this.sag = sag;
        this.thickness = thickness;
        this.color = color;
        this.maxVoltage = maxVoltage;
        this.maxAmperage = maxAmperage;
    }

    /** 绝缘变体构造：复用对应裸线的全部参数，颜色加深、半径按系数放大 */
    GridWireType(GridWireType base) {
        this.metalName = base.metalName;
        this.insulated = true;
        this.spoolItemName = base.metalName + "_insulated_wire_spool";
        this.maxLength = base.maxLength;
        this.resistance = base.resistance;
        this.sag = base.sag;
        this.thickness = base.thickness * INSULATED_THICKNESS_FACTOR;
        this.color = darken(base.color, INSULATED_COLOR_FACTOR);
        this.maxVoltage = base.maxVoltage;
        this.maxAmperage = base.maxAmperage;
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

    /**
     * 电压上限（FE/t）—— 本模组线缆独特之处：任意零散数值，不绑定等级。
     * 电网电压超过此值时过压熔断。
     */
    public int getMaxVoltage() {
        return maxVoltage;
    }

    /**
     * 电压上限所属的电压等级（归类标签）。
     * 由 {@link VoltageTier#fromVoltage(int)} 区间归类，
     * 例如电压上限 64 的线缆归类为 LV，但并不能完整支持 LV 的 128 电压。
     */
    public VoltageTier getVoltageTier() {
        return VoltageTier.fromVoltage(maxVoltage);
    }

    /** 最大载流量（安培数），总功率超过 voltage × amperage 时过载熔断 */
    public int getMaxAmperage() {
        return maxAmperage;
    }

    /** 最大传输功率（FE/t）= 电压上限 × 载流量 */
    public int getMaxPower() {
        return maxVoltage * maxAmperage;
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
