package com.mss.polymech.mps.kelvin.physical.celestial_world;

import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端天体世界注册表 —— <b>与
 * {@code org.cn_grass_block.kelvin.physical.celestial_world.ServerCelestialWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把"哪些维度是行星地表"这件事<b>集中登记</b>在一个静态池里，并提供三种反查：
 * 按维度 id、按 {@link Level}、按 {@link CelestialBody}（行星天体）。
 *
 * <h2>为什么要有这么一层（照 space 0.1.3）</h2>
 * "我在的是不是行星地表"这个问题在玩法里被问得极频繁（重力、卡门线、跳太空维度、
 * 渲染天空盒…）。如果每次都去遍历数据包配置，代价高且容易和已加载的维度不一致。
 * 所以做成<b>数据包加载时登记一次、之后 O(n) 甚至 O(1) 查表</b>（n = 行星数，个位数）。
 *
 * <p>池是 {@link HashSet}，而 {@link CelestialWorld#equals} <b>只比 WorldID</b> ——
 * 于是重复登记同一维度会自动去重；{@link #newCelestialWorld} 相当于幂等 upsert。</p>
 *
 * <p><b>静态池必须显式 {@link #init()} 清空</b>：单人存档切到另一个世界、或服务端
 * reload 时，不清就会把上一个存档的行星带过来。space 在数据包重载与停服时调它。</p>
 */
public class ServerCelestialWorld extends CelestialWorld {

    private static final Set<ServerCelestialWorld> celestialWorld = new HashSet<>();

    private ServerCelestialWorld(Planet celestialBody, ResourceLocation WorldID, ResourceLocation SpaceWorldID) {
        super(celestialBody, WorldID, SpaceWorldID);
    }

    public static void init() {
        celestialWorld.clear();
    }

    /** 按维度 id（字符串形态，与 {@code CelestialBody#level} 一致）反查；无则 null。 */
    public static ServerCelestialWorld getCelestialWorld(String worldID) {
        for (ServerCelestialWorld celestialWorld : getAllCelestialWorld()) {
            if (celestialWorld.WorldID.toString().equals(worldID)) {
                return celestialWorld;
            }
        }
        return null;
    }

    public static ServerCelestialWorld getCelestialWorld(Level level) {
        return getCelestialWorld(level.dimension().location().toString());
    }

    /** 按行星天体反查（名字相等；{@link CelestialBody#equals} 也是按名字）。 */
    public static ServerCelestialWorld getCelestialWorld(CelestialBody celestialBody) {
        for (ServerCelestialWorld celestialWorld : getAllCelestialWorld()) {
            if (celestialWorld.celestialBody.getName().equals(celestialBody.getName())) {
                return celestialWorld;
            }
        }
        return null;
    }

    public static boolean isCelestialWorld(ResourceLocation resourceLocation) {
        return getCelestialWorld(resourceLocation.toString()) != null;
    }

    public static boolean isCelestialWorld(Level level) {
        return isCelestialWorld(level.dimension().location());
    }

    /** 返回副本，避免调用方改到静态池。 */
    public static List<ServerCelestialWorld> getAllCelestialWorld() {
        return new ArrayList<>(celestialWorld);
    }

    public static void newCelestialWorld(Planet celestialBody, ResourceLocation WorldID, ResourceLocation SpaceWorldID) {
        celestialWorld.add(new ServerCelestialWorld(celestialBody, WorldID, SpaceWorldID));
    }
}
