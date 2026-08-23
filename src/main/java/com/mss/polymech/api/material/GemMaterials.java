package com.mss.polymech.api.material;

import javax.annotation.Nullable;
import java.util.List;

/*
 * 宝石/晶体材料注册表：与金属并列的材料类别。
 * <p>
 * 现实中的宝石是单晶矿物（金刚石、刚玉的红宝石/蓝宝石、绿柱石等），
 * 与金属不同：形态是<b>宝石(gem)</b>而非锭，开采后可直接作为成品，
 * 也可破碎成粉用于研磨/激光等工业用途（宝石粉由DUST前缀自动生成）。
 * </p>
 *
 * <h2>与材料系统的关系：</h2>
 * <p>
 * 宝石名加入{@link MaterialRegistry#MATERIAL_NAMES}，从而自动获得：
 * <ul>
 *   <li>粉（{material}_dust，DUST前缀无过滤器）</li>
 *   <li>宝石（{material}_gem，见{@link com.mss.polymech.api.item.ModItemTypes#GEM}）</li>
 * </ul>
 * 锭/板/齿轮等金属形态因过滤器会跳过宝石，不会出现"钻石锭"。
 * </p>
 *
 * @see com.mss.polymech.api.item.ModItemTypes#GEM
 */
public final class GemMaterials {

    /* 宝石名列表（与colors.json配色、语言文件、标签共用同一ID） */
    public static final List<String> GEMS = List.of(
            "diamond",        // 钻石（金刚石）
            "emerald",        // 绿宝石（绿柱石）
            "ruby",           // 红宝石（刚玉）
            "sapphire",       // 蓝宝石（刚玉）
            "topaz",          // 托帕石（黄玉）
            "amethyst",       // 紫水晶
            "garnet",         // 石榴石
            "opal",           // 蛋白石
            "apatite",        // 磷灰石
            "quartz",         // 石英（水晶）
            "certus_quartz",  // 赛特斯石英（晶体，参考AE/GregTech）
            "lapis_lazuli",   // 青金石（青金石岩主矿物，格雷青金脉）
            "green_sapphire"  // 绿蓝宝石（刚玉，格雷蓝宝石脉伴生）
    );

    private GemMaterials() {
    }

    /** 判断材料名是否为宝石/晶体 */
    public static boolean hasGem(String materialName) {
        return GEMS.contains(materialName);
    }

    /** 按名称查找宝石；不存在返回null */
    @Nullable
    public static String get(String materialName) {
        return GEMS.contains(materialName) ? materialName : null;
    }

    public static List<String> getGems() {
        return GEMS;
    }
}
