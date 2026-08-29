package com.mss.polymech.client.gui.widget.planet;

/** 星球图层类型：按半径从内到外排列，决定渲染方式。 */
public enum PlanetLayerType {
    /** 星球底层（地表/内核） */
    BASE,
    /** 云层 */
    CLOUD,
    /** 大气层 */
    ATMOSPHERE,
    /** 最外层线框/网格 */
    WIREFRAME,
    /** 科技悬浮层 */
    TECH,
    /** 光环/星环（太阳系扩展用） */
    RING
}
