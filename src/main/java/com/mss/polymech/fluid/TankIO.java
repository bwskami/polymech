package com.mss.polymech.fluid;

/**
 * 储罐/流体槽位的 IO 模式（参考 GregTech 的 IO 枚举）。
 * <p>
 * 用于区分机器内不同流体槽位的访问权限：
 * <ul>
 *   <li>{@link #IN}：只写（只进）——外部只能灌入流体，不能抽走（如水输入罐）</li>
 *   <li>{@link #OUT}：只读（只出）——外部只能抽走流体，不能灌入（如蒸汽输出罐），天然防止倒灌</li>
 *   <li>{@link #BOTH}：读写——双向自由（如储液罐）</li>
 *   <li>{@link #NONE}：完全封闭</li>
 * </ul>
 * </p>
 */
public enum TankIO {

    /** 只进（只写）：允许 fill，禁止 drain */
    IN,
    /** 只出（只读）：允许 drain，禁止 fill */
    OUT,
    /** 读写：fill 与 drain 均允许 */
    BOTH,
    /** 封闭：fill 与 drain 均禁止 */
    NONE;

    /** 是否允许外部灌入（fill） */
    public boolean canFill() {
        return this == IN || this == BOTH;
    }

    /** 是否允许外部抽取（drain） */
    public boolean canDrain() {
        return this == OUT || this == BOTH;
    }

    /** 本模式是否包含给定方向的能力 */
    public boolean support(TankIO io) {
        if (io == this) return true;
        if (io == NONE) return false;
        return this == BOTH;
    }
}
