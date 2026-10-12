package com.mss.polymech.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线回归探针：验证关键类在编译产物里都还在。
 *
 * <p>用 {@code Class.forName(name, false, loader)} —— 第二个参数 {@code false}
 * 表示<b>不初始化静态块</b>，所以不会加载原生库、不会起物理/天体线程，
 * 也不需要 Minecraft 启动。因此它能在 CI 里以 {@code ./gradlew verifyClasses} 跑，
 * 秒级出 PASS/FAIL。</p>
 *
 * <p><b>为什么不用 NeoForge GameTest</b>：见 {@code docs/testing-strategy.md} ——
 * 本项目的 GameTestServer 起不来（模组维度进不了注册表，而
 * {@code mps.ProjectionManager.init} 在维度缺失时直接抛），所以回归只能走这条路。</p>
 *
 * <p>退出码：全部通过 = 0，任一失败 = 1。</p>
 */
public final class ClassProbe {

    /** CLONE 组：MPS 物理体克隆层。 */
    private static final String[] CLONE = {
            "com.mss.polymech.mps.physical.physical_world.PhysicalWorld",
            "com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld",
            "com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld",
            "com.mss.polymech.mps.physical.physical_body.PhysicalBody",
            "com.mss.polymech.mps.physical.physical_body.ServerPhysicalBody",
            "com.mss.polymech.mps.physical.physical_body.ClientPhysicalBody",
            "com.mss.polymech.mps.rapier.helper.RapierWorld",
            "com.mss.polymech.mps.rapier.helper.RigidBody",
            "com.mss.polymech.mps.rapier.helper.ColliderBody",
            "com.mss.polymech.mps.physical.manger.ProjectionManager",
            "com.mss.polymech.mps.physical.manger.PhysicalChunkManager",
            "com.mss.polymech.mps.network.MPSNetworkHandler",
            "com.mss.polymech.mps.network.packet.SyncPhysicalWorldCreate",
            "com.mss.polymech.mps.network.packet.SyncPhysicalBodyCreate",
            "com.mss.polymech.mps.network.packet.SyncPhysicalBodyMoveBatch",
            "com.mss.polymech.mps.network.packet.SyncPhysicalBodyRemove",
            "com.mss.polymech.mps.network.event.PhysicalWorldUpdateSyncEvent",
            "com.mss.polymech.mps.event.CollisionPhysicalServerAction",
            "com.mss.polymech.mps.thread.ServerCollisionPhysicalThread",
            "com.mss.polymech.mps.space.util.manger.SpaceModDataPackManger",
    };

    /** KELVIN 组：天体力学层。 */
    private static final String[] KELVIN = {
            "com.mss.polymech.mps.kelvin.physical.KelvinConstants",
            "com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld",
            "com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld",
            "com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld",
            "com.mss.polymech.mps.kelvin.physical.celestial_world.CelestialWorld",
            "com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld",
            "com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld",
            "com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody",
            "com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet",
            "com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Star",
            "com.mss.polymech.mps.kelvin.physical.celestial_body.variant.BlackHole",
            "com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Meteoroid",
            "com.mss.polymech.mps.kelvin.physical.CelestialBodyForce",
            "com.mss.polymech.mps.kelvin.OrbitPhysicalThread",
            "com.mss.polymech.mps.kelvin.network.KelvinNetworkHandler",
            "com.mss.polymech.mps.kelvin.network.event.SpaceWorldUpdateSyncEvent",
            "com.mss.polymech.mps.kelvin.network.event.CelestialBodyMoveSyncEvent",
            "com.mss.polymech.mps.kelvin.event.OrbitPhysicalServerAction",
            "com.mss.polymech.mps.kelvin.event.PhysicalBodySpaceEvent",
    };

    /** PHYSICS 组：本项目自有的碰撞物理层（玩家/物理体驱动）。 */
    private static final String[] PHYSICS = {
            "com.mss.polymech.physics.PhysicalEntityCollision",
            "com.mss.polymech.physics.PhysicalEntityCollider",
            "com.mss.polymech.physics.PhysicsWorldManager",
            "com.mss.polymech.physics.PhysgunRotation",
            "com.mss.polymech.physics.PhysicsNatives",
            "com.mss.polymech.physics.ProjectionManager",
    };

    /** SPACE 组：6DOF 玩家与无缝过渡。 */
    private static final String[] SPACE = {
            "com.mss.polymech.space.SpacePlayerData",
            "com.mss.polymech.space.SpaceTransitionHandler",
            "com.mss.polymech.space.SpaceWorld",
            "com.mss.polymech.space.SpaceRotationSync",
            "com.mss.polymech.space.SpaceAttachments",
    };

    public static void main(String[] args) {
        Map<String, String[]> groups = new LinkedHashMap<>();
        groups.put("SMOKE(base)", new String[] {
                "com.mss.polymech.Polymech",
                "com.mss.polymech.PolymechClient",
                "com.mss.polymech.Config",
        });
        groups.put("PHYSICS", PHYSICS);
        groups.put("SPACE", SPACE);
        groups.put("CLONE", CLONE);
        groups.put("KELVIN", KELVIN);

        ClassLoader loader = ClassProbe.class.getClassLoader();
        List<String> failures = new ArrayList<>();
        int total = 0;

        System.out.println("=== Poly Mech 离线类探针 ===");
        for (Map.Entry<String, String[]> group : groups.entrySet()) {
            List<String> missing = new ArrayList<>();
            for (String name : group.getValue()) {
                total++;
                try {
                    Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    missing.add(name + "  (" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
                }
            }
            if (missing.isEmpty()) {
                System.out.printf("PASS  %-14s %d/%d%n", group.getKey(),
                        group.getValue().length, group.getValue().length);
            } else {
                System.out.printf("FAIL  %-14s %d/%d%n", group.getKey(),
                        group.getValue().length - missing.size(), group.getValue().length);
                for (String m : missing) {
                    System.out.println("        missing: " + m);
                    failures.add(group.getKey() + " -> " + m);
                }
            }
        }

        System.out.println("-----------------------------------------");
        if (failures.isEmpty()) {
            System.out.println("RESULT: PASS  (" + total + " classes loadable)");
            System.exit(0);
        } else {
            System.out.println("RESULT: FAIL  (" + failures.size() + " of " + total + " missing)");
            System.exit(1);
        }
    }

    private ClassProbe() {
    }
}
