package com.mss.polymech.physics;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端物理世界管理：每个维度一个 Rapier 世界。
 *
 * <p>重力按维度取 {@link PlanetDimensions#gravity(ResourceKey)}（地球 1.0、月球 0.166、太空 0），
 * 与游戏内的重力 mixin 保持一致，避免"人往下掉、箱子不往下掉"的割裂感。</p>
 *
 * <p>步进策略：每服务端 tick（20 TPS）走 5 个 1/100 秒子步 ≈ 100 Hz 定步长，
 * 与 space 模组的物理线程频率一致（后续可改为独立线程）。</p>
 */
public final class PhysicsWorldManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics");
    private static final int SUBSTEPS_PER_TICK = 5;

    private static final Map<ResourceKey<Level>, ServerLevel> LEVELS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> WORLDS = new HashMap<>();
    private static final Map<ResourceKey<Level>, PhysicsTerrain> TERRAINS = new HashMap<>();

    private PhysicsWorldManager() {
    }

    /** 取（或创建）该维度的物理世界句柄；原生层不可用时返回 0。 */
    public static long world(ServerLevel level) {
        if (!PhysicsNatives.isAvailable()) {
            return 0;
        }
        ResourceKey<Level> key = level.dimension();
        Long existing = WORLDS.get(key);
        if (existing != null) {
            return existing;
        }
        float gravityFactor = PlanetDimensions.gravity(key);
        long handle = NativePhysics.worldCreate(0.0, -9.8 * gravityFactor, 0.0);
        if (handle > 0) {
            NativePhysics.worldSetTimestep(handle, PhysicsStepThread.STEP_SECONDS);
            WORLDS.put(key, handle);
            LEVELS.put(key, level);
            // 步进交给独立线程（10ms/步），照 space/MPS
            PhysicsStepThread.add(handle);
            LOGGER.info("[PolyMech] 维度 {} 物理世界已创建: 重力 {} m/s², 句柄 {}",
                    key.location(), -9.8 * gravityFactor, handle);
        }
        return handle;
    }

    /** 取该维度的地形快照管理器（惰性创建）。 */
    public static PhysicsTerrain terrain(ServerLevel level) {
        return TERRAINS.computeIfAbsent(level.dimension(), key -> new PhysicsTerrain(level));
    }

    /** 只查询，不创建（用于 setBlockState 等热路径）。 */
    public static PhysicsTerrain terrainIfPresent(ServerLevel level) {
        return TERRAINS.get(level.dimension());
    }

    /** 每服务端 tick 步进所有世界。 */
    public static void tick() {
        if (!PhysicsNatives.isAvailable() || WORLDS.isEmpty()) {
            return;
        }
        for (PhysicsTerrain terrain : TERRAINS.values()) {
            terrain.tick();
        }
        // 步进由 PhysicsStepThread 按 10ms 独立推进（照 space/MPS），这里只做地形/回写/同步。
        // 物理 -> 实体回写
        PhysicsEntityManager.tick();
        // 物理 -> 客户端同步（抠出来的建筑/飞船）
        PhysicsBodyTracker.tick();
    }

    /** 服务端停止：销毁所有世界与地形碰撞体。 */
    public static void shutdown() {
        for (PhysicsTerrain terrain : TERRAINS.values()) {
            terrain.clear();
        }
        TERRAINS.clear();
        PhysicsEntityManager.clear();
        PhysicsBodyTracker.clear();
        ServerPlayerPhysics.clear();
        for (long handle : WORLDS.values()) {
            PhysicsStepThread.remove(handle);
            NativePhysics.worldDestroy(handle);
        }
        WORLDS.clear();
        LEVELS.clear();
    }

    /** 诊断：已创建物理世界的维度数量。 */
    public static int worldCount() {
        return WORLDS.size();
    }
}
