package com.mss.polymech.client.gui.widget.planet;

/**
 * 星球表面材质类型：预计算阶段与 albedo 一起生成，
 * 渲染阶段据此决定地形起伏（海洋压平）和镜面高光掩码。
 */
public enum SurfaceMaterial {
    /** 普通岩石/陆地（起伏，无高光） */
    ROCK,
    /** 类地行星陆地（起伏，无高光） */
    LAND,
    /** 海洋（压平，高光） */
    OCEAN,
    /** 冰面（可轻微起伏，高光） */
    ICE,
    /** 气态巨行星色带（光滑，无高光） */
    GAS
}
