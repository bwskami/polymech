/**
 * 子块粒度 64³ → 16³ 的**分区性质**探针（离线，无依赖）。
 *
 * <h2>为什么需要它</h2>
 * 粒度是<b>全局耦合</b>的：子块边长同时决定子块数、数组长度、方块容器的索引位移、
 * 投影偏移、脏块位布局、网络清理宽度。改这种常量时编译器**帮不上忙** ——
 * 每个 64 都是合法的 int，全改错了也照样 BUILD SUCCESSFUL，只是方块出现在错误的位置。
 *
 * <p>所以这里钉的不是"某个常量等于几"，而是三条**结构性不变量**：</p>
 * <ol>
 *   <li><b>覆盖性/无重叠</b>：8×8×8 个子块在局部空间里必须<b>恰好一次</b>铺满 [−64, 64)³。
 *       这条能抓出最阴的错误 —— 偏移量写成 {@code -CHUNK_WIDTH}（= −16）而不是
 *       {@code -MAX_WIDTH/2}（= −64）时，整体只铺到 [−16, 112)，编译完全正常。</li>
 *   <li><b>索引互逆</b>：{@code PalettedChunk.index(x,y,z)} 与
 *       {@code PhysicalBody.voxelBounds} 的位移解码必须互为逆运算
 *       （写方 {@code <<4/<<8}、读方 {@code &15/>>4&15/>>8}）。</li>
 *   <li><b>位布局单射</b>：脏块键对 512 个子块必须无碰撞。
 *       0.1.3 的"1 位/坐标"在 8 个子块下会碰撞 ⇒ 标脏标到别的子块（不报错，只是不更新）。</li>
 * </ol>
 *
 * <p>常量直接取自 {@code PhysicalBody} / {@code PalettedChunk}（{@code static final int}
 * 会被 javac 内联，所以运行期不需要加载那两个类）。<b>但静态方法不能这么取</b> ——
 * 调用 {@code PalettedChunk.index} / {@code PhysicalBody.chunkIndex} 会让 JVM 去加载
 * 那两个类，而它们的签名里带 MC 类型（{@code BlockGetter} 等），离线跑会
 * {@code NoClassDefFoundError}。所以这两个方法在这里<b>按 space 0.3.0 的原文复刻</b>，
 * 再由 {@code check-artifact.ps1} 的源码契约保证"复刻 == 实际实现"。
 * 同样的理由适用于派生量（CHUNK_BITS、packDirtyKey、投影偏移公式）。</p>
 *
 * <p>跑法：{@code javac -encoding UTF-8 -d <临时目录> -cp <build\classes\java\main> SubChunkGranularityProbe.java}
 * 然后 {@code java -cp <临时目录>;<build\classes\java\main> -Dstdout.encoding=UTF-8 SubChunkGranularityProbe}</p>
 */
public final class SubChunkGranularityProbe {

    private static int passed = 0;
    private static int failed = 0;

    // ── 从实际常量取（javac 内联）────────────────────────────────────────────
    static final int CHUNK_WIDTH = com.mss.polymech.mps.physical.physical_body.PhysicalBody.CHUNK_WIDTH;
    static final int CHUNK_SIZE = com.mss.polymech.mps.physical.physical_body.PhysicalBody.CHUNK_SIZE;
    static final int CHUNK_COUNT = com.mss.polymech.mps.physical.physical_body.PhysicalBody.CHUNK_COUNT;
    static final int CHUNK_TOTAL = com.mss.polymech.mps.physical.physical_body.PhysicalBody.CHUNK_TOTAL;
    static final int MAX_WIDTH = com.mss.polymech.mps.physical.physical_body.PhysicalBody.MAX_WIDTH;

    /** 复刻 ProjectionManager 的派生常量（非编译期常量，无法内联）。 */
    static final int CHUNK_BITS = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, CHUNK_COUNT - 1)));
    static final int CHUNK_MASK = (1 << CHUNK_BITS) - 1;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [PASS] %-52s %s%n", name, detail); }
        else    { failed++; System.out.printf("  [FAIL] %-52s %s%n", name, detail); }
    }

    /** 复刻 PhysicalBody.updateProjectionAABB 的偏移（0.3.0 原文：cx*16 - 64）。 */
    static int chunkBase(int c) {
        return c * CHUNK_WIDTH - MAX_WIDTH / 2;
    }

    /** 复刻 space 0.3.0 的脏块键。 */
    static long packDirtyKey(int slot, int cx, int cy, int cz) {
        return (long) slot << CHUNK_BITS * 3
                | (long) (cx & CHUNK_MASK) << CHUNK_BITS * 2
                | (long) (cy & CHUNK_MASK) << CHUNK_BITS
                | (long) (cz & CHUNK_MASK);
    }

    /** 复刻 {@code PalettedChunk.index}（0.3.0 原文：{@code x + (z<<4) + (y<<8)}）。 */
    static int indexOf(int x, int y, int z) {
        return x + (z << 4) + (y << 8);
    }

    /** 复刻 {@code PhysicalBody.chunkIndex}（{@code cx*8*8 + cy*8 + cz}）。 */
    static int chunkIndexOf(int cx, int cy, int cz) {
        return cx * CHUNK_COUNT * CHUNK_COUNT + cy * CHUNK_COUNT + cz;
    }

    /** 复刻 0.1.3 的旧脏块键（1 位/坐标、无掩码）—— 用作反例。 */
    static long packDirtyKeyLegacy(int slot, int cx, int cy, int cz) {
        return (long) slot << 8 | (long) (cx << 2) | (long) (cy << 1) | (long) cz;
    }

    public static void main(String[] args) {
        System.out.println("=== SubChunkGranularityProbe: 子块粒度 64³ -> 16³ 的结构不变量 ===");
        System.out.println();

        System.out.println("--- A 组：常量自洽（改粒度时任何一处漏改都会在这里露出来）---");
        check("CHUNK_WIDTH == 16", CHUNK_WIDTH == 16, "实测=" + CHUNK_WIDTH);
        check("CHUNK_COUNT == 8", CHUNK_COUNT == 8, "实测=" + CHUNK_COUNT);
        check("MAX_WIDTH == CHUNK_WIDTH * CHUNK_COUNT", MAX_WIDTH == CHUNK_WIDTH * CHUNK_COUNT,
                MAX_WIDTH + " == " + CHUNK_WIDTH + " * " + CHUNK_COUNT);
        check("CHUNK_SIZE == CHUNK_WIDTH³", CHUNK_SIZE == CHUNK_WIDTH * CHUNK_WIDTH * CHUNK_WIDTH,
                CHUNK_SIZE + " == " + (CHUNK_WIDTH * CHUNK_WIDTH * CHUNK_WIDTH));
        check("CHUNK_TOTAL == CHUNK_COUNT³", CHUNK_TOTAL == CHUNK_COUNT * CHUNK_COUNT * CHUNK_COUNT,
                CHUNK_TOTAL + " == " + (CHUNK_COUNT * CHUNK_COUNT * CHUNK_COUNT));
        check("PalettedChunk.WIDTH/SIZE 与 PhysicalBody 一致",
                com.mss.polymech.mps.physical.helper.PalettedChunk.WIDTH == CHUNK_WIDTH
                        && com.mss.polymech.mps.physical.helper.PalettedChunk.SIZE == CHUNK_SIZE,
                "WIDTH=" + com.mss.polymech.mps.physical.helper.PalettedChunk.WIDTH
                        + " SIZE=" + com.mss.polymech.mps.physical.helper.PalettedChunk.SIZE);
        check("CHUNK_BITS == 3（8 个子块需 3 位）", CHUNK_BITS == 3, "实测=" + CHUNK_BITS);
        check("CHUNK_MASK == 7", CHUNK_MASK == 7, "实测=" + CHUNK_MASK);

        System.out.println();
        System.out.println("--- B 组：★ 覆盖性 —— 512 个子块必须恰好一次铺满 [-64, 64)³ ---");
        // 用 long 位图记录每个局部坐标被覆盖的次数（64³ = 262144 位，用 4096 个 long）
        long[] hits = new long[MAX_WIDTH * MAX_WIDTH * MAX_WIDTH / 64];
        int cells = 0;
        boolean outOfRange = false;
        for (int cx = 0; cx < CHUNK_COUNT; cx++) {
            for (int cy = 0; cy < CHUNK_COUNT; cy++) {
                for (int cz = 0; cz < CHUNK_COUNT; cz++) {
                    for (int lx = 0; lx < CHUNK_WIDTH; lx++) {
                        for (int ly = 0; ly < CHUNK_WIDTH; ly++) {
                            for (int lz = 0; lz < CHUNK_WIDTH; lz++) {
                                int wx = chunkBase(cx) + lx;
                                int wy = chunkBase(cy) + ly;
                                int wz = chunkBase(cz) + lz;
                                if (wx < -64 || wx >= 64 || wy < -64 || wy >= 64 || wz < -64 || wz >= 64) {
                                    outOfRange = true;
                                    continue;
                                }
                                int idx = (wx + 64) * MAX_WIDTH * MAX_WIDTH + (wy + 64) * MAX_WIDTH + (wz + 64);
                                hits[idx >>> 6] |= 1L << (idx & 63);
                                cells++;
                            }
                        }
                    }
                }
            }
        }
        check("所有子块格子都落在 [-64, 64) 内", !outOfRange, outOfRange ? "有格子越界" : "无越界");
        check("格子总数 == MAX_WIDTH³", cells == MAX_WIDTH * MAX_WIDTH * MAX_WIDTH,
                cells + " == " + (MAX_WIDTH * MAX_WIDTH * MAX_WIDTH));
        long covered = 0;
        for (long h : hits) {
            covered += Long.bitCount(h);
        }
        check("被覆盖的局部坐标数 == MAX_WIDTH³（无重叠、无空洞）",
                covered == (long) MAX_WIDTH * MAX_WIDTH * MAX_WIDTH,
                covered + " / " + ((long) MAX_WIDTH * MAX_WIDTH * MAX_WIDTH));

        System.out.println();
        System.out.println("--- C 组：反例有效性 —— 偏移若误用 -CHUNK_WIDTH 就会漏掉一大片 ---");
        {
            int wrongBase = 0 * CHUNK_WIDTH - CHUNK_WIDTH; // 误写成 -16
            int minCovered = wrongBase;
            int maxCovered = (CHUNK_COUNT - 1) * CHUNK_WIDTH - CHUNK_WIDTH + CHUNK_WIDTH - 1;
            boolean covers = minCovered <= -64 && maxCovered >= 63;
            check("误用 -CHUNK_WIDTH 时覆盖范围确实不足（反例有效）", !covers,
                    "覆盖 [" + minCovered + ", " + maxCovered + "]，应覆盖 [-64, 63]");
        }

        System.out.println();
        System.out.println("--- D 组：PalettedChunk.index 与 voxelBounds 的位移解码必须互逆 ---");
        {
            boolean ok = true;
            int checked = 0;
            for (int x = 0; x < CHUNK_WIDTH && ok; x++) {
                for (int y = 0; y < CHUNK_WIDTH && ok; y++) {
                    for (int z = 0; z < CHUNK_WIDTH && ok; z++) {
                        int i = indexOf(x, y, z);
                        // 复刻 PhysicalBody.voxelBounds 的解码
                        int dx = i & 15;
                        int dz = (i >> 4) & 15;
                        int dy = i >> 8;
                        if (dx != x || dy != y || dz != z) {
                            ok = false;
                            System.out.printf("        不互逆: (%d,%d,%d) -> i=%d -> (%d,%d,%d)%n", x, y, z, i, dx, dy, dz);
                        }
                        checked++;
                    }
                }
            }
            check("index 与其位移解码互逆（全 " + checked + " 格）", ok, ok ? "全部一致" : "见上");
            int maxIndex = indexOf(CHUNK_WIDTH - 1, CHUNK_WIDTH - 1, CHUNK_WIDTH - 1);
            check("index 最大值 == SIZE-1", maxIndex == CHUNK_SIZE - 1, maxIndex + " == " + (CHUNK_SIZE - 1));
        }

        System.out.println();
        System.out.println("--- E 组：chunkIndex 单射 + 与 0.3.0 的 cx*64+cy*8+cz 同值 ---");
        {
            boolean[] seen = new boolean[CHUNK_TOTAL];
            boolean injective = true;
            boolean matches = true;
            for (int cx = 0; cx < CHUNK_COUNT; cx++) {
                for (int cy = 0; cy < CHUNK_COUNT; cy++) {
                    for (int cz = 0; cz < CHUNK_COUNT; cz++) {
                        int i = chunkIndexOf(cx, cy, cz);
                        if (i < 0 || i >= CHUNK_TOTAL || seen[i]) {
                            injective = false;
                        } else {
                            seen[i] = true;
                        }
                        if (i != cx * 64 + cy * 8 + cz) {
                            matches = false;
                        }
                    }
                }
            }
            check("chunkIndex 在 512 个子块上单射", injective, injective ? "无碰撞" : "有碰撞");
            check("chunkIndex == cx*64 + cy*8 + cz（0.3.0 原文）", matches, matches ? "逐值一致" : "不一致");
        }

        System.out.println();
        System.out.println("--- F 组：★ 脏块位布局必须单射（旧 1 位布局在 8 子块下会碰撞）---");
        {
            java.util.Set<Long> keys = new java.util.HashSet<>();
            boolean injective = true;
            for (int cx = 0; cx < CHUNK_COUNT; cx++) {
                for (int cy = 0; cy < CHUNK_COUNT; cy++) {
                    for (int cz = 0; cz < CHUNK_COUNT; cz++) {
                        if (!keys.add(packDirtyKey(7, cx, cy, cz))) {
                            injective = false;
                        }
                    }
                }
            }
            check("0.3.0 位布局：512 个子块键无碰撞", injective && keys.size() == CHUNK_TOTAL,
                    keys.size() + " / " + CHUNK_TOTAL);

            java.util.Set<Long> legacy = new java.util.HashSet<>();
            for (int cx = 0; cx < CHUNK_COUNT; cx++) {
                for (int cy = 0; cy < CHUNK_COUNT; cy++) {
                    for (int cz = 0; cz < CHUNK_COUNT; cz++) {
                        legacy.add(packDirtyKeyLegacy(7, cx, cy, cz));
                    }
                }
            }
            check("旧 1 位布局确实碰撞（反例有效，这就是必须改的理由）",
                    legacy.size() < CHUNK_TOTAL, "旧布局只有 " + legacy.size() + " 个不同键 / " + CHUNK_TOTAL);
            // 逐个举例：0.1.3 的 (1,0,0) 与 (0,2,0) 会撞
            check("具体碰撞例：旧布局 (cx=1,cy=0) 与 (cx=0,cy=2) 同键",
                    packDirtyKeyLegacy(7, 1, 0, 0) == packDirtyKeyLegacy(7, 0, 2, 0),
                    packDirtyKeyLegacy(7, 1, 0, 0) + " == " + packDirtyKeyLegacy(7, 0, 2, 0));
        }

        System.out.println();
        System.out.printf("=== SubChunkGranularityProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
