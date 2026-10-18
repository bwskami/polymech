package com.mss.polymech.machine.definition;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseMachineBlock;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器定义的加载/保存中心。
 *
 * <h3>加载优先级（后者覆盖前者）</h3>
 * <ol>
 *   <li><b>内置默认</b>：classpath 里的 {@code /data/poly_mech/machine/<id>.json}，
 *       随 mod 发布、进版本库、<strong>客户端与服务端都读得到</strong>
 *       （必须两端都读得到：{@code RegisterCapabilitiesEvent} 双端都会调
 *       {@code getItemProxy/getFluidProxy} 来注册侧面方块能力）。</li>
 *   <li><b>config 覆盖</b>：{@code config/polymech/machines/<id>.json}，存在则整份覆盖内置。
 *       游戏内编辑器就写这里 —— 单人游戏里客户端和集成服务端是同一份文件，两端自然一致。</li>
 * </ol>
 * 找不到任何一份 → {@link MachineDefinition#EMPTY}（单方块机器本就该没有定义）。
 *
 * <h3>为什么用 classpath 而不是 ResourceManager</h3>
 * 内置默认走 classpath 读取：不依赖资源重载生命周期，客户端/服务端任何时刻都拿得到同一份，
 * 也不会因为数据包覆盖而在两端产生分歧。需要数据包级覆盖时走 config 层即可。
 *
 * <h3>重载</h3>
 * 首次访问惰性加载；{@code /polymech machinereload} 或编辑器保存后调用 {@link #reload()} 即时生效。
 */
public final class MachineDefinitionManager {

    /** 定义来源，用于日志与编辑器显示。 */
    public enum Source {
        BUILTIN("内置"),
        CONFIG("config"),
        MISSING("缺失");

        private final String cn;

        Source(String cn) {
            this.cn = cn;
        }

        public String cn() {
            return cn;
        }
    }

    private static final String BUILTIN_ROOT = "/data/" + Polymech.MOD_ID + "/machine/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, MachineDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static volatile boolean loaded = false;

    private MachineDefinitionManager() {
    }

    // ==================== 查询 ====================

    /**
     * 取某台机器的定义；没有则返回 {@link MachineDefinition#EMPTY}（不会返回 null）。
     * <p>未命中时会<strong>重读一次</strong>：可能是首次访问时注册表还没填完
     * （那样会把空结果钉死，机器就变成没有侧面方块的空壳），重读即可自愈。
     * 命中路径只有一次 map 查询，逐 tick 调用（如 {@code exportProxyOutputs}）也够快。</p>
     */
    public static MachineDefinition get(String machineId) {
        MachineDefinition def = DEFINITIONS.get(machineId);
        if (def != null) return def;
        reload();
        def = DEFINITIONS.get(machineId);
        return def != null ? def : MachineDefinition.EMPTY;
    }

    public static Source sourceOf(String machineId) {
        ensureLoaded();
        return SOURCES.getOrDefault(machineId, Source.MISSING);
    }

    /** 已加载的机器 id → 来源（保序）。 */
    public static Map<String, Source> sources() {
        ensureLoaded();
        return new LinkedHashMap<>(SOURCES);
    }

    /** 本次加载遇到的错误（格式坏掉的 JSON 等）。非空即说明有定义没生效。 */
    public static List<String> errors() {
        ensureLoaded();
        return new ArrayList<>(ERRORS);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    // ==================== 加载 ====================

    private static void ensureLoaded() {
        if (loaded) return;
        reload();
    }

    /**
     * 重新读取全部机器定义。
     * <p>注册表还没填好时（过早调用）不会把结果钉死，等下次再读。</p>
     */
    public static synchronized void reload() {
        var ids = BaseMachineBlock.getMachineIds();
        if (ids.isEmpty()) {
            Polymech.LOGGER.warn("[机器定义] 注册表为空，暂不加载（稍后重试）");
            return;
        }

        DEFINITIONS.clear();
        SOURCES.clear();
        ERRORS.clear();

        int builtin = 0, config = 0, missing = 0;
        for (String id : ids) {
            MachineDefinition def;
            Path cfg = configDir().resolve(id + ".json");
            if (Files.isRegularFile(cfg)) {
                def = readFile(cfg, id);
                if (def != null) {
                    DEFINITIONS.put(id, def);
                    SOURCES.put(id, Source.CONFIG);
                    config++;
                    continue;
                }
                // 读坏了：记录错误，退回内置（不让一个坏 config 把机器变成空壳）
            }
            def = readBuiltin(id);
            if (def != null) {
                DEFINITIONS.put(id, def);
                SOURCES.put(id, Source.BUILTIN);
                builtin++;
            } else {
                DEFINITIONS.put(id, MachineDefinition.EMPTY);
                SOURCES.put(id, Source.MISSING);
                missing++;
                ERRORS.add(id + "：内置与 config 都没有定义");
            }
        }

        loaded = true;
        Polymech.LOGGER.info("[机器定义] 已加载 {} 台机器（内置 {} / config {} / 缺失 {}）",
                ids.size(), builtin, config, missing);
        for (String err : ERRORS) {
            Polymech.LOGGER.error("[机器定义] {}", err);
        }
    }

    @Nullable
    private static MachineDefinition readBuiltin(String id) {
        String path = BUILTIN_ROOT + id + ".json";
        try (InputStream in = MachineDefinitionManager.class.getResourceAsStream(path)) {
            if (in == null) return null;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
                validateAgainstStandard("内置 " + path, id, json);
                return MachineDefinition.parse(id, json);
            }
        } catch (Exception e) {
            ERRORS.add("内置 " + path + " 解析失败：" + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static MachineDefinition readFile(Path file, String id) {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            validateAgainstStandard("config " + file.getFileName(), id, json);
            return MachineDefinition.parse(id, json);
        } catch (Exception e) {
            ERRORS.add("config " + file.getFileName() + " 解析失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 按 {@link MachineStandard} 校验一份<strong>原始 JSON</strong>，问题记进 {@link #errors()}。
     *
     * <h3>为什么必须在这里校验</h3>
     * 编辑器保存的 JSON 由编辑器保证合规，但<strong>整合包作者手改的
     * {@code config/poly_mech/machines/*.json} 没人管</strong>。而
     * {@link MachineDefinition#parse} <strong>不会拒绝未知键</strong>（多写一个键被静静忽略），
     * 所以作者把 {@code side_offsets} 敲成 {@code side_offset}、或把 {@code behavior}
     * 写成词表外的值，都会<strong>正常加载、机器照常出现</strong>——只是悄悄少了一半数据，
     * 且没有任何提示。加载期校验是这类问题唯一的反馈渠道，
     * 结果通过 {@code /polymech machinereload} 打印出来。
     *
     * <p>内置文件同样校验：内置的合规性已由离线断言保证，这里多一道运行时兜底，
     * 顺带让"离线过了但线上坏了"这种不一致立刻可见。</p>
     */
    private static void validateAgainstStandard(String where, String id, JsonObject json) {
        for (String issue : MachineStandard.validate(json, id)) {
            ERRORS.add(where + "：" + issue);
        }
    }

    // ==================== 保存 ====================

    /** 编辑器保存目标：{@code config/polymech/machines/}。 */
    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve(Polymech.MOD_ID).resolve("machines");
    }

    /**
     * 开发环境的仓库源码目录 {@code src/main/resources/data/<modid>/machine/}。
     * <p>非开发环境返回 null。注意：写这里等于改仓库源码，需要重新构建才会进 classpath，
     * 想立刻看效果请保存到 config。</p>
     */
    @Nullable
    public static Path sourceDir() {
        if (FMLEnvironment.production) return null;
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path project = gameDir.getParent();
        if (project == null) return null;
        if (!Files.isDirectory(project.resolve("src/main/resources"))) return null;
        return project.resolve("src/main/resources/data").resolve(Polymech.MOD_ID).resolve("machine");
    }

    /**
     * 保存一份定义并（保存到 config 时）立即重载。
     *
     * @param toSource true=写回仓库源码目录（开发环境，需重新构建）；false=写 config（立即生效）
     * @return 实际写入的文件
     */
    public static Path save(MachineDefinition def, boolean toSource) throws IOException {
        Path dir = toSource ? sourceDir() : configDir();
        if (dir == null) {
            throw new IOException("源码目录不可用（仅开发环境可用），请改用保存到 config");
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(def.id() + ".json");
        Files.writeString(file, GSON.toJson(def.toJson()) + System.lineSeparator(), StandardCharsets.UTF_8);
        if (!toSource) {
            reload();
        }
        Polymech.LOGGER.info("[机器定义] 已保存 {} → {}", def.id(), file);
        return file;
    }
}
