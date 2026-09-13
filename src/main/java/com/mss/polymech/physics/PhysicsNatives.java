package com.mss.polymech.physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 物理原生库加载器。
 *
 * <p>加载策略（按顺序）：</p>
 * <ol>
 *   <li>{@code -Djava.library.path} / 开发环境已有库：直接 {@link System#loadLibrary}；</li>
 *   <li>从 mod 资源 {@code /natives/<平台>_<架构>/polymech_physics.<ext>} 解压到缓存目录后加载。</li>
 * </ol>
 *
 * <p><b>失败时不影响游戏启动</b>：把 {@code available} 置为 false 并记录原因，
 * 物理相关功能自行降级（对比 space 模组在非 Windows 平台直接抛异常的做法）。</p>
 *
 * <p>安全：加载后立即校验 {@link NativePhysics#abiVersion()}，与 {@link #EXPECTED_ABI} 不一致
 * 则拒绝使用（防御原生库与 Java 代码版本错配导致的崩溃）。</p>
 */
public final class PhysicsNatives {

    /**
     * Java 侧要求的<b>最低</b>原生 ABI 版本（不是"必须相等"）。
     *
     * <p>为什么用"最低"而不是"严格相等"：原生层新增函数时，Java 与 dll 的更新是分开的
     * （dll 要装 Rust + MinGW 重编）。严格相等会让"Java 已更新、dll 还没重编"这段窗口里
     * <b>整个物理层被停用</b>，代价太大。改成最低版本后：</p>
     * <ul>
     *   <li>dll 低于最低要求 → 拒绝（真的不兼容，必须重编）；</li>
     *   <li>dll 高于最低要求 → 放行（按约定原生层只做加法，向后兼容）；</li>
     *   <li>新增函数用独立的 {@code MIN_ABI_*} 常量逐个把关，例如
     *       {@link #hasCollisionGroups()}。</li>
     * </ul>
     *
     * <p>这个数同时决定原生库的解压目录（见下方 {@code abi<版本>} 路径）。</p>
     */
    public static final int EXPECTED_ABI = 3;

    /** 碰撞组函数（{@code colliderAttachCuboidGrouped} 等）需要的最低 ABI。 */
    public static final int MIN_ABI_COLLISION_GROUPS = 4;

    /** 实际加载到的原生 ABI；未加载为 -1。 */
    private static volatile int loadedAbi = -1;

    /** 原生层是否提供碰撞组函数（需要 ABI ≥ {@link #MIN_ABI_COLLISION_GROUPS}）。 */
    public static boolean hasCollisionGroups() {
        return available && loadedAbi >= MIN_ABI_COLLISION_GROUPS;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics");
    private static final String LIB_BASE_NAME = "polymech_physics";

    private static volatile boolean attempted = false;
    private static volatile boolean available = false;
    private static volatile String status = "未尝试加载";
    private static volatile Path loadedPath = null;

    private PhysicsNatives() {
    }

    /** 是否可用（会尝试加载一次）。 */
    public static boolean isAvailable() {
        if (!attempted) {
            ensureLoaded();
        }
        return available;
    }

    /** 人类可读的状态描述（命令/日志用）。 */
    public static String status() {
        if (!attempted) {
            ensureLoaded();
        }
        return status;
    }

    /** 已加载的库路径；未加载返回 null。 */
    public static Path loadedPath() {
        return loadedPath;
    }

    /** 幂等加载。 */
    public static synchronized boolean ensureLoaded() {
        if (attempted) {
            return available;
        }
        attempted = true;
        try {
            loadFromLibraryPath();
        } catch (Throwable fromPath) {
            try {
                loadFromResources();
            } catch (Throwable fromResources) {
                available = false;
                status = "原生库不可用（" + platformKey() + "）：" + fromResources.getMessage();
                LOGGER.warn("[PolyMech] 物理原生库加载失败，物理功能将不可用: {}", fromResources.toString());
                return false;
            }
        }

        // ABI 校验：判"低于最低要求"而不是"严格相等"——
        // 这样"Java 先加了新函数、dll 还没重编"时物理层仍能照常跑，
        // 新函数由 MIN_ABI_* 单独把关（见 hasCollisionGroups）。
        // 反之 dll 比 Java 新则天然兼容（按约定只做加法）。
        try {
            int abi = NativePhysics.abiVersion();
            loadedAbi = abi;
            if (abi < EXPECTED_ABI) {
                available = false;
                status = "ABI 过低：原生=" + abi + "，Java 最低要求=" + EXPECTED_ABI;
                LOGGER.error("[PolyMech] {}", status);
                return false;
            }
            available = true;
            status = "可用（ABI " + abi + "，来自 " + loadedPath + "）";
            LOGGER.info("[PolyMech] 物理原生库加载成功: {}", status);
            return true;
        } catch (Throwable t) {
            available = false;
            status = "ABI 校验失败：" + t;
            LOGGER.error("[PolyMech] 物理原生库 ABI 校验异常", t);
            return false;
        }
    }

    private static void loadFromLibraryPath() {
        System.loadLibrary(LIB_BASE_NAME);
        loadedPath = Path.of("java.library.path");
    }

    private static void loadFromResources() throws Exception {
        String key = platformKey();
        String fileName = System.mapLibraryName(LIB_BASE_NAME);
        String resource = "/natives/" + key + "/" + fileName;

        try (InputStream in = PhysicsNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("资源缺失: " + resource);
            }
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "polymech-physics", "abi" + EXPECTED_ABI, key);
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            Path tmp = dir.resolve(fileName + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            System.load(target.toAbsolutePath().toString());
            loadedPath = target;
        }
    }

    /** 平台键，例如 {@code windows_amd64}、{@code linux_aarch64}、{@code macos_amd64}。 */
    public static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osKey;
        if (os.contains("win")) {
            osKey = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osKey = "macos";
        } else if (os.contains("linux")) {
            osKey = "linux";
        } else {
            osKey = os.replaceAll("[^a-z0-9]+", "_");
        }
        String archKey = switch (arch) {
            case "amd64", "x86_64", "x86-64" -> "amd64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch.replaceAll("[^a-z0-9]+", "_");
        };
        return osKey + "_" + archKey;
    }
}
