package com.mss.polymech.tooltip;

/**
 * Unicode下标数字工具（纯字符变换，不依赖任何游戏类，可脱离游戏环境单独使用/测试）。
 */
public final class Subscript {

    private static final int SUBSCRIPT_BASE = '\u2080'; // ₀
    private static final char DIGIT_BASE = '0';

    private Subscript() {
    }

    /** 将字符串中的数字转换为Unicode下标数字，如 "H2SO4" -> "H₂SO₄" */
    public static String toSubscript(String string) {
        return convert(string, true);
    }

    /** 将字符串中的数字转换为Unicode上标数字，如同位素质量数 "238" -> "²³⁸" */
    public static String toSuperscript(String string) {
        return convert(string, false);
    }

    private static String convert(String string, boolean subscript) {
        char[] chars = string.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int relative = chars[i] - DIGIT_BASE;
            if (relative >= 0 && relative <= 9) {
                chars[i] = subscript ? (char) (SUBSCRIPT_BASE + relative) : superscriptOf(relative);
            }
        }
        return new String(chars);
    }

    /** 上标数字无连续码位：¹²³在拉丁补充区，其余在一般标点区 */
    private static char superscriptOf(int digit) {
        return switch (digit) {
            case 1 -> '\u00B9';
            case 2 -> '\u00B2';
            case 3 -> '\u00B3';
            default -> (char) ('\u2070' + digit); // ⁰及⁴⁻⁹恰好为U+2070+数字
        };
    }
}
