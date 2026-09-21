package com.mss.polymech.mps.kelvin.physical.space_world;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端太空世界注册表 —— <b>与
 * {@code org.cn_grass_block.kelvin.physical.space_world.ServerSpaceWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 服务端持有"哪些维度是太空"的注册表（维度 id → {@link ServerSpaceWorld}），
 * 并提供按 id / {@link Level} 的反查与判定。
 *
 * <h2>为什么用 Map 而 {@link ServerCelestialWorld} 用 Set</h2>
 * 两者语义不同：天体世界（地表）需要"按行星天体反查"，是集合语义；
 * 太空世界只需"这个维度 id 是不是太空"，且太空世界是多个<b>并列</b>的宇宙
 * （每个太空维度一份天体池与操作队列），所以按 id 直接映射 —— 查表 O(1)。
 *
 * <h2>与 space 0.1.3 的一处<b>有意</b>差异（已记录，非遗漏）</h2>
 * space 在这里 {@code @Override step(double)}，除了推进天体之外还多推一个
 * {@code RadioNetwork}（无线电信标玩法：发射端/接收端/衰减/多普勒，共 16 个类，
 * 只被天线方块使用，与天体力学无任何耦合）。
 * <p>本移植只嫁接<b>力学</b>，无线电不在范围内，因此<b>不覆写 {@code step}</b>，
 * 而不是塞一个空的覆写或造一个空壳 {@code RadioNetwork} —— 后者会引入一个永远
 * 不产生行为的类，属于"把结构抄过来但没有对应功能"的假移植。
 * 若日后要加太空玩法子系统，这里就是 space 设计好的挂载点（覆写 {@code step}）。</p>
 */
public class ServerSpaceWorld extends SpaceWorld {

    private static final Map<ResourceLocation, ServerSpaceWorld> serverSpaceWorld = new HashMap<>();

    private ServerSpaceWorld(ResourceLocation WorldID, ResourceLocation SkyBoxTexture) {
        super(WorldID, SkyBoxTexture);
    }

    /** 停服 / 换存档时清空；不清会把上一个存档的太空维度带过来。 */
    public static void init() {
        serverSpaceWorld.clear();
    }

    public static List<ServerSpaceWorld> getAllSpaceWorld() {
        return new ArrayList<>(serverSpaceWorld.values());
    }

    /** 无此太空维度则返回 null。 */
    public static ServerSpaceWorld getSpaceWorld(ResourceLocation resourceLocation) {
        return serverSpaceWorld.get(resourceLocation);
    }

    public static ServerSpaceWorld getSpaceWorld(Level level) {
        return getSpaceWorld(level.dimension().location());
    }

    public static boolean isSpaceWorld(ResourceLocation resourceLocation) {
        return serverSpaceWorld.containsKey(resourceLocation);
    }

    public static boolean isSpaceWorld(Level level) {
        return isSpaceWorld(level.dimension().location());
    }

    public static void newSpaceWorld(ResourceLocation worldID, ResourceLocation SkyBoxTexture) {
        serverSpaceWorld.put(worldID, new ServerSpaceWorld(worldID, SkyBoxTexture));
    }
}
