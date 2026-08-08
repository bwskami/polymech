import org.openscience.cdk.aromaticity.Kekulization;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IMolecularFormula;
import org.openscience.cdk.layout.StructureDiagramGenerator;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.manipulator.MolecularFormulaManipulator;

/** 临时冒烟测试：验证离子SMILES能否被CDK解析+凯库勒化+生成2D坐标（不依赖Minecraft类） */
public class IonSmokeTest {
    public static void main(String[] args) {
        // 与 molecule_smiles.json 完全一致的条目
        check("H2F+", "[H][F+][H]");
        check("SbF6-", "F[Sb-](F)(F)(F)(F)F");
        check("SO42-", "O=S(=O)([O-])[O-]");
        check("NO3-", "O=[N+]([O-])[O-]");
        check("OH-", "[OH-]");
        check("S2O82-", "[O-]S(=O)(=O)OOS(=O)(=O)[O-]");
        // 备用变体（若上面失败用于挑选）
        check("SbF6-v2", "[F-][Sb](F)(F)(F)(F)(F)F");
        check("SbF6-v3", "F[Sb](F)(F)(F)(F)F");
        check("SbF6-v4", "[Sb-](F)(F)(F)(F)(F)F");
    }

    private static void check(String key, String smiles) {
        try {
            SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());
            IAtomContainer mol = parser.parseSmiles(smiles);
            IMolecularFormula mf = MolecularFormulaManipulator.getMolecularFormula(mol);
            try {
                Kekulization.kekulize(mol);
            } catch (Exception e) {
                System.out.println("[WARN] " + key + " kekulize: " + e);
            }
            StructureDiagramGenerator sdg = new StructureDiagramGenerator();
            sdg.setMolecule(mol);
            sdg.generateCoordinates();
            mol = sdg.getMolecule();
            System.out.println("[OK] " + key + " <- " + smiles + " | " + MolecularFormulaManipulator.getString(mf)
                    + " atoms=" + mol.getAtomCount() + " bonds=" + mol.getBondCount());
        } catch (Exception | Error e) {
            System.out.println("[FAIL] " + key + " <- " + smiles + " : " + e);
        }
    }
}
