package com.mss.polymech.tooltip.verify;

import com.mss.polymech.tooltip.MoleculeStructure;
import com.mss.polymech.tooltip.SmilesStructures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 临时验证程序：把polymer_formulas.json全部条目跑一遍真实CDK管线
 * （解析→凯库勒化→坐标生成→锚点隐式氢扣减），输出标签/键统计与ASCII示意图，
 * 用于在进游戏前目视确认每个聚合物结构式正确。验证完成后删除本文件。
 */
public final class PolymerVerify {

    public static void main(String[] args) throws Exception {
        Path json = Path.of("src/main/resources/assets/poly_mech/config/polymer_formulas.json");
        List<String[]> entries = new ArrayList<>();
        for (String line : Files.readAllLines(json)) {
            String t = line.trim();
            if (!t.startsWith("\"")) continue;
            int k1 = t.indexOf('"'), k2 = t.indexOf('"', k1 + 1);
            int v1 = t.indexOf('"', k2 + 1), v2 = t.indexOf('"', v1 + 1);
            if (v1 < 0 || v2 < 0) continue;
            String key = t.substring(k1 + 1, k2);
            if (key.equals("comment")) continue;
            entries.add(new String[]{key, t.substring(v1 + 1, v2)});
        }
        int fail = 0;
        for (String[] e : entries) {
            System.out.println();
            System.out.println("==== " + e[0] + "  [" + e[1] + "] ====");
            MoleculeStructure s = SmilesStructures.getPolymer(e[0], e[1]);
            if (s == null) {
                fail++;
                System.out.println("FAIL（构建失败，见上方ERROR日志）");
                continue;
            }
            printInfo(s);
            printAscii(s);
        }
        System.out.println();
        System.out.println(fail == 0 ? "ALL PASS (" + entries.size() + ")" : fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void printInfo(MoleculeStructure s) {
        List<MoleculeStructure.Atom> atoms = s.atoms();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < atoms.size(); i++) {
            String label = atoms.get(i).label();
            if (!label.isEmpty()) labels.append(i).append(':').append(label).append(' ');
        }
        long doubles = s.bonds().stream().filter(b -> b.type() == MoleculeStructure.BondType.DOUBLE).count();
        System.out.printf("原子%d 键%d(双键%d) 环内双键线%d 标签[%s]%n",
                atoms.size(), s.bonds().size(), doubles, s.ringLines().size(), labels.toString().trim());
        for (MoleculeStructure.Anchor a : s.anchors()) {
            MoleculeStructure.Atom atom = atoms.get(a.atom());
            System.out.printf("锚点 %d(%s) %.2f,%.2f 方向%s%n", a.atom(), a.dir(), atom.x(), atom.y(), a.dir());
        }
    }

    private static void printAscii(MoleculeStructure s) {
        int W = 6, H = 3; // 每个网格单位的字符数
        float minX = s.minX(), minY = s.minY();
        int cols = (int) Math.ceil((s.maxX() - minX) * W) + 8;
        int rows = (int) Math.ceil((s.maxY() - minY) * H) + 4;
        char[][] g = new char[rows][cols];
        for (char[] row : g) java.util.Arrays.fill(row, ' ');

        for (MoleculeStructure.Atom atom : s.atoms()) {
            int px = px(atom.x(), minX, W), py = py(atom.y(), minY, H);
            g[py][px] = '*'; // 碳骨架点位
        }
        for (MoleculeStructure.Bond bond : s.bonds()) {
            MoleculeStructure.Atom a = s.atoms().get(bond.a()), b = s.atoms().get(bond.b());
            char ch = bond.type() == MoleculeStructure.BondType.DOUBLE ? '=' : '-';
            drawLine(g, a.x(), a.y(), b.x(), b.y(), minX, minY, W, H, ch);
        }
        for (MoleculeStructure.RingLine rl : s.ringLines()) {
            drawLineRaw(g, rl.x1(), rl.y1(), rl.x2(), rl.y2(), minX, minY, W, H, '.');
        }
        for (MoleculeStructure.Anchor a : s.anchors()) {
            MoleculeStructure.Atom atom = s.atoms().get(a.atom());
            int px = px(atom.x(), minX, W), py = py(atom.y(), minY, H);
            switch (a.dir()) {
                case LEFT -> put(g, px - 3, py, '<');
                case RIGHT -> put(g, px + 3, py, '>');
                case UP -> put(g, px, py - 2, '^');
                case DOWN -> put(g, px, py + 2, 'v');
            }
        }
        // 标签最后覆盖（含Unicode下标）
        for (MoleculeStructure.Atom atom : s.atoms()) {
            if (atom.label().isEmpty()) continue;
            int px = px(atom.x(), minX, W), py = py(atom.y(), minY, H);
            String label = atom.label();
            for (int i = 0; i < label.length(); i++) put(g, px + i, py, label.charAt(i));
        }
        for (char[] row : g) System.out.println(new String(row));
    }

    private static int px(float x, float minX, int W) {
        return 3 + Math.round((x - minX) * W);
    }

    private static int py(float y, float minY, int H) {
        return 2 + Math.round((y - minY) * H);
    }

    private static void drawLine(char[][] g, float x1, float y1, float x2, float y2,
                                 float minX, float minY, int W, int H, char ch) {
        drawLineRaw(g, x1, y1, x2, y2, minX, minY, W, H, ch);
    }

    private static void drawLineRaw(char[][] g, float x1, float y1, float x2, float y2,
                                    float minX, float minY, int W, int H, char ch) {
        float dist = (float) Math.hypot((x2 - x1) * W, (y2 - y1) * H);
        int steps = Math.max(1, (int) (dist * 2));
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            int px = px(x1 + (x2 - x1) * t, minX, W);
            int py = py(y1 + (y2 - y1) * t, minY, H);
            if (g[py][px] == ' ' || ch == '=') g[py][px] = ch;
        }
    }

    private static void put(char[][] g, int x, int y, char ch) {
        if (y >= 0 && y < g.length && x >= 0 && x < g[y].length) g[y][x] = ch;
    }
}
