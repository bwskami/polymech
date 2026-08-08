package com.mss.polymech.tooltip;

import com.mss.polymech.tooltip.MoleculeStructure.Builder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openscience.cdk.aromaticity.Kekulization;
import org.openscience.cdk.graph.Cycles;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IBond;
import org.openscience.cdk.interfaces.IElement;
import org.openscience.cdk.interfaces.IMolecularFormula;
import org.openscience.cdk.layout.StructureDiagramGenerator;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.manipulator.MolecularFormulaManipulator;

import javax.vecmath.Point2d;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SMILES → {@link MoleculeStructure} 转换器（基于CDK化学信息学库）。
 * <p>
 * 每个物质只需登记一行SMILES字符串（见{@link MoleculeStructures}），
 * 本类负责：SMILES解析 → 凯库勒化（芳香环转交替单双键） → 2D坐标自动生成
 * → 转换为GT风格键线式渲染模型。结果懒加载缓存，首次Shift悬停时构建一次。
 * </p>
 * <p>
 * 骨架式标签规则：碳原子一律不显示标签（键线式折线即代表碳骨架）；
 * 例外：孤立碳（如甲烷，无任何键）显示CHn以免结构图空白。
 * 杂原子显示元素符号+隐式氢数（如OH、NH2，电荷忽略）。
 * 凯库勒式画法：环边统一画单键，环内双键以向环心收缩0.8的内侧短线（RingLine）表示。
 * </p>
 */
public final class SmilesStructures {

    private static final Logger LOGGER = LogManager.getLogger(SmilesStructures.class);

    /** 环内双键示意线向环心收缩比例 */
    private static final float RING_LINE_INSET = 0.8f;
    /** 星型配位重排的配体圆半径（网格单位）：大于默认键长1.0，
     *  给键线两端被原子标签裁剪后仍留出可见线段 */
    private static final float STAR_RADIUS = 1.5f;

    /** 生成成功的结构缓存（按化学式） */
    private static final Map<String, MoleculeStructure> CACHE = new HashMap<>();
    /** 生成失败的化学式（避免重复尝试与刷屏日志） */
    private static final Set<String> FAILED = new HashSet<>();

    private SmilesStructures() {
    }

    /**
     * 按化学式获取结构（首次调用时用CDK从SMILES构建并缓存）。
     * 解析失败或分子式校验不通过时返回null并记录错误日志。
     */
    public static synchronized MoleculeStructure get(String formula, String smiles) {
        MoleculeStructure cached = CACHE.get(formula);
        if (cached != null) return cached;
        if (FAILED.contains(formula)) return null;
        try {
            MoleculeStructure structure = build(formula, smiles);
            if (structure == null) {
                FAILED.add(formula);
                return null;
            }
            CACHE.put(formula, structure);
            return structure;
        } catch (Exception e) {
            LOGGER.error("从SMILES生成结构式失败 [{} -> {}]: {}", formula, smiles, e.toString());
            FAILED.add(formula);
            return null;
        }
    }

    private static MoleculeStructure build(String formula, String smiles) throws Exception {
        SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());
        IAtomContainer mol = parser.parseSmiles(smiles);
        // 安全校验：解析产物分子式必须与登记化学式一致，防止SMILES写错渲染出错误结构
        if (!formulaMatches(formula, mol)) {
            LOGGER.error("SMILES与登记化学式不符，跳过结构式 [{} -> {}]", formula, smiles);
            return null;
        }
        // 芳香环凯库勒化：c1ccccc1等芳香键转成交替单双键
        Kekulization.kekulize(mol);
        // 2D坐标自动生成（CDK默认键长1.0，正好匹配渲染模型的网格单位）
        StructureDiagramGenerator sdg = new StructureDiagramGenerator();
        sdg.setMolecule(mol);
        sdg.generateCoordinates();
        mol = sdg.getMolecule();
        // 星型配位离子（如SbF6^-）CDK坐标生成不均匀（缺角/重叠），改为均匀圆形重排
        relayoutStar(mol);
        return convert(mol);
    }

    /**
     * 星型拓扑重排：单一中心原子连接所有其它原子（且其它原子均为端基）时，
     * 将中心置于原点、周边原子自正上方起逆时针均匀分布在单位圆上，
     * 保证SbF6^-等超价配位离子各配体均匀环绕中心。
     */
    private static void relayoutStar(IAtomContainer mol) {
        int n = mol.getAtomCount();
        if (n < 5) return; // 至少4个配体才需要重排
        int center = -1;
        for (int i = 0; i < n; i++) {
            if (mol.getConnectedBondsCount(mol.getAtom(i)) == n - 1) {
                center = i;
                break;
            }
        }
        if (center < 0) return;
        // 确认纯星型：其余原子均为单键端基
        for (int i = 0; i < n; i++) {
            if (i != center && mol.getConnectedBondsCount(mol.getAtom(i)) != 1) return;
        }
        mol.getAtom(center).setPoint2d(new Point2d(0, 0));
        int leaves = n - 1, idx = 0;
        for (int i = 0; i < n; i++) {
            if (i == center) continue;
            double theta = Math.PI / 2 + 2 * Math.PI * idx / leaves;
            mol.getAtom(i).setPoint2d(new Point2d(
                    Math.cos(theta) * STAR_RADIUS, Math.sin(theta) * STAR_RADIUS));
            idx++;
        }
    }

    /** 校验解析产物的分子式与登记化学式逐元素一致（含隐式氢） */
    private static boolean formulaMatches(String formula, IAtomContainer mol) {
        // 离子结构键带"^电荷"后缀（如 SO4^2-、H2F^+）：先去掉，
        // 避免电荷数字与原子个数混淆；中性化学式无此后缀，不受影响
        String neutral = formula.replaceAll("\\^\\d*[+-]$", "");
        Map<String, Integer> expected = ModTooltipCenter.parseFormula(neutral);
        IMolecularFormula mf = MolecularFormulaManipulator.getMolecularFormula(mol);
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            IElement element = mf.getBuilder().newInstance(IElement.class, e.getKey());
            int count = MolecularFormulaManipulator.getElementCount(mf, element);
            if (count != e.getValue()) return false;
        }
        // 不允许出现登记化学式之外的元素
        for (IElement element : MolecularFormulaManipulator.elements(mf)) {
            if (!expected.containsKey(element.getSymbol())) return false;
        }
        return true;
    }

    /** CDK分子容器 → GT风格渲染模型（y轴取反转为屏幕坐标） */
    private static MoleculeStructure convert(IAtomContainer mol) {
        Builder builder = MoleculeStructure.builder();
        int n = mol.getAtomCount();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            Point2d p = mol.getAtom(i).getPoint2d();
            xs[i] = p != null ? (float) p.x : 0;
            ys[i] = p != null ? (float) -p.y : 0;
        }

        // 环内双键（凯库勒化产物）→ 向环心收缩的内侧短线；环边本身仍画单键
        Set<IBond> ringDoubleBonds = new HashSet<>();
        for (IAtomContainer ring : Cycles.sssr(mol).toRingSet().atomContainers()) {
            float cx = 0, cy = 0;
            int count = 0;
            for (IAtom atom : ring.atoms()) {
                int idx = mol.indexOf(atom);
                cx += xs[idx];
                cy += ys[idx];
                count++;
            }
            cx /= count;
            cy /= count;
            for (IBond bond : ring.bonds()) {
                if (bond.getOrder() != IBond.Order.DOUBLE) continue;
                ringDoubleBonds.add(bond);
                int ia = mol.indexOf(bond.getAtom(0));
                int ib = mol.indexOf(bond.getAtom(1));
                builder.ringLine(
                        cx + (xs[ia] - cx) * RING_LINE_INSET, cy + (ys[ia] - cy) * RING_LINE_INSET,
                        cx + (xs[ib] - cx) * RING_LINE_INSET, cy + (ys[ib] - cy) * RING_LINE_INSET);
            }
        }

        // 原子（骨架式标签规则）
        for (int i = 0; i < n; i++) {
            IAtom atom = mol.getAtom(i);
            builder.atom(labelOf(atom, mol.getConnectedBondsCount(atom)), xs[i], ys[i]);
        }

        // 键：环内双键的环边画单键（双键由RingLine表示），其余双键交渲染器画平行双线
        for (IBond bond : mol.bonds()) {
            int ia = mol.indexOf(bond.getAtom(0));
            int ib = mol.indexOf(bond.getAtom(1));
            if (bond.getOrder() == IBond.Order.DOUBLE && !ringDoubleBonds.contains(bond)) {
                builder.doubleBond(ia, ib);
            } else {
                builder.bond(ia, ib);
            }
        }
        return builder.build();
    }

    /** 骨架式原子标签：碳不显示标签（孤立碳例外，显示CHn），杂原子显示符号+隐式氢（H个数为1时省略数字，多个用下标） */
    private static String labelOf(IAtom atom, int bondCount) {
        String symbol = atom.getSymbol();
        Integer hObj = atom.getImplicitHydrogenCount();
        int h = hObj == null ? 0 : hObj;
        if ("C".equals(symbol)) {
            // 孤立碳（如甲烷）无键可画，必须显示完整CHn标签
            if (bondCount == 0) return appendH("C", h);
            return "";
        }
        return appendH(symbol, h);
    }

    /** 拼接隐式氢：0个不写，1个只写H（化学惯例省略1），多个写H加下标数字 */
    private static String appendH(String symbol, int h) {
        if (h <= 0) return symbol;
        if (h == 1) return symbol + "H";
        return symbol + "H" + ModTooltipCenter.toSubscript(String.valueOf(h));
    }
}
