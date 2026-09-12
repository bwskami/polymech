package com.mss.polymech.physics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实体接管：让 Minecraft 实体由 Rapier 刚体驱动。
 *
 * <p>关键约定（与 space 模组的 PhysicalEntity 一致）：</p>
 * <ul>
 *   <li>MC 实体的 Y 坐标是<b>脚底</b>，而刚体位置是碰撞盒<b>中心</b>
 *       → 建体时 {@code +halfHeight}，同步回来时 {@code -halfHeight}；</li>
 *   <li>接管期间由 {@code EntityPhysicsMoveMixin} 取消原版 {@code move()}，
 *       避免原版重力/碰撞与物理结果互相打架；</li>
 *   <li>每 tick 把刚体变换写回实体：位置用 setPos，速度用 setDeltaMovement
 *       （让客户端能平滑插值，而不是瞬移）。</li>
 * </ul>
 *
 * <p>当前仅接管<b>非玩家</b>实体：玩家移动是客户端权威的（ServerboundMovePlayerPacket），
 * 要接管玩家必须同时改造网络协议与客户端预测，属于后续阶段。</p>
 */
public final class PhysicsEntityManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Entity");

    /** 默认密度（kg/格³），用于由碰撞盒体积估算质量。 */
    private static final double MASS_DENSITY = 40.0;
    /** 质量下限，避免极小实体质量趋近 0 导致数值不稳定。 */
    private static final double MIN_MASS = 2.0;

    private static final Map<UUID, Attachment> ATTACHED = new HashMap<>();

    private record Attachment(long body, float halfHeight) {
    }

    private PhysicsEntityManager() {
    }

    public static boolean isAttached(Entity entity) {
        return ATTACHED.containsKey(entity.getUUID());
    }

    public static int attachedCount() {
        return ATTACHED.size();
    }

    public static List<UUID> attachedIds() {
        return new ArrayList<>(ATTACHED.keySet());
    }

    /**
     * 把实体挂到物理世界（动态刚体 + 盒碰撞体，尺寸取自实体碰撞箱）。
     *
     * @return 是否成功
     */
    public static boolean attach(Entity entity) {
        if (!PhysicsNatives.isAvailable() || entity.level().isClientSide()) {
            return false;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        if (ATTACHED.containsKey(entity.getUUID())) {
            return true;
        }
        long world = PhysicsWorldManager.world(level);
        if (world <= 0) {
            return false;
        }

        float halfWidth = Math.max(0.05f, entity.getBbWidth() * 0.5f);
        float halfHeight = Math.max(0.05f, entity.getBbHeight() * 0.5f);
        double mass = Math.max(MIN_MASS,
                entity.getBbWidth() * entity.getBbHeight() * entity.getBbWidth() * MASS_DENSITY);

        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                entity.getX(), entity.getY() + halfHeight, entity.getZ(),
                0.0, 0.0, 0.0, 1.0, mass);
        if (body <= 0) {
            return false;
        }
        long collider = NativePhysics.colliderAttachCuboid(world, body,
                halfWidth, halfHeight, halfWidth, 0.6, 0.0);
        if (collider <= 0) {
            NativePhysics.bodyDestroy(world, body);
            return false;
        }
        // 锁定旋转：MC 实体视觉上不会翻滚，放任物理翻滚只会让高瘦实体莫名其妙倒下去
        NativePhysics.bodyLockRotations(world, body, true);
        ATTACHED.put(entity.getUUID(), new Attachment(body, halfHeight));
        return true;
    }

    /** 取实体当前刚体句柄；未接管返回 0（供诊断/测试使用）。 */
    public static long bodyHandle(Entity entity) {
        Attachment attachment = ATTACHED.get(entity.getUUID());
        return attachment == null ? 0L : attachment.body();
    }

    /** 解除接管（保留实体当前位置）。 */
    public static void detach(Entity entity) {
        Attachment attachment = ATTACHED.remove(entity.getUUID());
        if (attachment == null) {
            return;
        }
        if (entity.level() instanceof ServerLevel level) {
            long world = PhysicsWorldManager.world(level);
            if (world > 0) {
                NativePhysics.bodyDestroy(world, attachment.body());
            }
        }
    }

    /** 每 tick：把刚体变换写回实体。同时清理已无效应被移除的实体。 */
    public static void tick() {
        if (ATTACHED.isEmpty() || !PhysicsNatives.isAvailable()) {
            return;
        }
        List<UUID> stale = new ArrayList<>();
        double[] pos = new double[3];
        double[] vel = new double[3];

        for (Map.Entry<UUID, Attachment> entry : ATTACHED.entrySet()) {
            Entity entity = findEntity(entry.getKey());
            if (entity == null || !entity.isAlive() || entity.isRemoved()) {
                stale.add(entry.getKey());
                continue;
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                continue;
            }
            long world = PhysicsWorldManager.world(level);
            if (world <= 0) {
                continue;
            }
            long body = entry.getValue().body();
            float halfHeight = entry.getValue().halfHeight();
            if (!NativePhysics.bodyReadTranslation(world, body, pos)
                    || !NativePhysics.bodyReadVelocity(world, body, vel)) {
                continue;
            }
            entity.setPos(pos[0], pos[1] - halfHeight, pos[2]);
            entity.setDeltaMovement(new Vec3(vel[0], vel[1], vel[2]));
            entity.fallDistance = 0.0f;
        }

        for (UUID id : stale) {
            Attachment attachment = ATTACHED.remove(id);
            if (attachment != null) {
                LOGGER.debug("[PolyMech] 实体 {} 已失效，解除物理接管", id);
            }
        }
    }

    /** 服务端停止/世界切换时清空。 */
    public static void clear() {
        ATTACHED.clear();
    }

    private static Entity findEntity(UUID id) {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }
}
