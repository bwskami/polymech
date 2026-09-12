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

    /** Java 侧期望的 ABI 版本，必须与 Rust 侧 ABI_VERSION 一致。 */
    public static final int EXPECTED_ABI = 3;

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

        // ABI 校验
        try {
            int abi = NativePhysics.abiVersion();
            if (abi != EXPECTED_ABI) {
                available = false;
                status = "ABI 不匹配：原生=" + abi + "，Java 期望=" + EXPECTED_ABI;
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
