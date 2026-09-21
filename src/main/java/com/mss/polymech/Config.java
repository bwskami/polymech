package com.mss.polymech;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    // ==================== kelvin 天体物理线程（space: SpaceModCommonConfig） ====================
    // 这两个值决定天体物理线程的推进节奏：core_tick_speed 是每秒步数（Hz），
    // core_tick_time 是每步的 dt（秒）。space 的默认值同为 100 / 0.01。
    // 注意它们必须与物理线程（OrbitPhysicalThread）读到的值一致：
    // dt 直接进积分器，改了会同时改变轨道速度与数值稳定性。

    /** 天体物理线程每秒步数（Hz）。 */
    public static final ModConfigSpec.IntValue CORE_TICK_SPEED = BUILDER
            .comment("天体物理线程每秒步数（Hz）。\nCelestial physics thread tick speed.")
            .defineInRange("coreTickSpeed", 100, 1, 1000);

    /** 天体物理线程每步 dt（秒）。 */
    public static final ModConfigSpec.DoubleValue CORE_TICK_TIME = BUILDER
            .comment("天体物理线程每步 dt（秒）。\nCelestial physics thread tick time (seconds).")
            .defineInRange("coreTickTime", 0.01, 1.0E-4, 1.0);

    /**
     * 是否让 kelvin 的积分结果成为天体位置的权威来源。
     *
     * <p>{@code false}（默认）时位置全部来自静态的 {@code RealAstroData}，
     * 行为与迁移前逐位相同；{@code true} 时渲染 / 光照 / 星图 / HUD / 传送
     * 一起改读 kelvin 的轨道 —— 于是行星会随时间真的移动（因为服务端在跑 N 体积分）。</p>
     *
     * <p>默认关闭是<b>刻意的</b>：这是唯一会改变玩家看得见行为的开关，
     * 打开后若观感不对，改配置即可立刻回到原状，不必回滚代码。</p>
     */
    public static final ModConfigSpec.BooleanValue KELVIN_AUTHORITATIVE = BUILDER
            .comment("是否让 kelvin 的积分结果成为天体位置的权威来源（行星会随时间真的移动）。\n"
                    + "Whether kelvin's integrated orbits become the authoritative body positions.")
            .define("kelvinAuthoritative", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
