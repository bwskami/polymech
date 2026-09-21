package com.mss.polymech.mps.kelvin.physical.celestial_world;

import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端天体世界 —— <b>与
 * {@code org.cn_grass_block.kelvin.physical.celestial_world.ClientCelestialWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 客户端<b>只保留"我当前所在的那一个"天体世界</b>，并提供带自愈的读取。
 *
 * <h2>为什么和 {@link ServerCelestialWorld} 长得完全不同（照 space 0.1.3）</h2>
 * 服务端要同时服务所有维度，所以是"池 + 多种反查"；客户端同一时刻<b>只有</b>一个
 * 已加载维度，维护一个池没有意义，反而要处理"切维度时哪几个该留"。
 * 于是客户端退化成<b>单例缓存</b>。
 *
 * <p>关键是 {@link #getCelestialWorld()} 里的<b>自愈</b>：缓存失效有两种成因，
 * 都不可能靠事件可靠捕捉 ——
 * <ol>
 *   <li>{@code Minecraft.level == null}（退回主菜单 / 换存档途中），</li>
 *   <li>当前维度已经<b>不是</b>缓存里那个（走出地表、进了别的维度）。</li>
 * </ol>
 * 两种情况都<b>顺手把缓存置空再返回 null</b>，而不是抛异常或返回过期对象。
 * 这样调用方永远只需判 {@code == null}，不必自己管失效 —— 把"状态可能过期"这件事
 * 收进读取路径，是这一层的设计要点。</p>
 */
public class ClientCelestialWorld extends CelestialWorld {

    private static ClientCelestialWorld celestialWorld = null;

    public ClientCelestialWorld(Planet celestialBody, ResourceLocation WorldID, ResourceLocation SpaceWorldID) {
        super(celestialBody, WorldID, SpaceWorldID);
    }

    public static void init() {
        celestialWorld = null;
    }

    /** 取当前维度的天体世界；不在行星地表（或未初始化）时返回 null，并清掉过期缓存。 */
    public static ClientCelestialWorld getCelestialWorld() {
        if (celestialWorld == null) {
            return null;
        } else if (Minecraft.getInstance().level == null) {
            celestialWorld = null;
            return null;
        } else if (!Minecraft.getInstance().level.dimension().location().equals(celestialWorld.WorldID)) {
            celestialWorld = null;
            return null;
        } else {
            return celestialWorld;
        }
    }

    /** 由同步包在收到"进入天体世界"时写入。 */
    public static void setCelestialWorld(ClientCelestialWorld celestialWorld) {
        ClientCelestialWorld.celestialWorld = celestialWorld;
    }
}
