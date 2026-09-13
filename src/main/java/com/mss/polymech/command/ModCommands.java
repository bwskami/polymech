package com.mss.polymech.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceWorld;
import com.mss.polymech.worldgen.ModMinerals;
import com.mss.polymech.worldgen.ModRocks;
import com.mss.polymech.worldgen.ModVeins;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/*
 * PolyMech勘探命令套件：世界生成开发/测试工具。
 * <p>
 * 矿物埋在地下难以目测验证，本套件提供四种勘探手段：
 * <ul>
 *   <li>{@code /polymech rock}：显示当前坐标的噪声预测岩种，
 *       并穿透草方块/泥土等覆盖层报告脚下实际岩层</li>
 *   <li>{@code /polymech veins}：列出全部矿脉定义（稀有度/高度/组成/宿主岩）</li>
 *   <li>{@code /polymech scan [半径]}：扫描范围内全部本模组矿石，
 *       按矿物统计数量并给出每种最近矿块的坐标（点击可填入/tp）</li>
 *   <li>{@code /polymech find <矿物> [半径]}：环形外扩搜索3D距离最近的指定矿石
 *       （通常比全量扫描快得多）</li>
 *   <li>{@code /polymech expose [半径]}：以玩家为中心清除±半径立方体内的地形方块，
 *       直接目视矿脉形态与岩区边界（只保留矿石与基岩，水/岩浆一并清除）。
 *       半径最大128，站在地表也能穿透到深板岩层；大半径会短暂卡顿</li>
 * </ul>
 * 全部命令需要权限等级2（单机开作弊即可）。扫描只覆盖已加载区块。
 * </p>
 */
public class ModCommands {

    /** scan/find最大水平半径 */
    private static final int MAX_RADIUS = 96;

    /** expose最大半径：128格球体，站在地表也能穿透到深板岩层 */
    private static final int EXPOSE_MAX_RADIUS = 128;

    /** 矿石扫描的固定高度带（覆盖全部矿脉高度范围） */
    private static final int SCAN_MIN_Y = -64;
    private static final int SCAN_MAX_Y = 100;

    /** 矿石方块 → 矿物名（延迟构建，注册表冻结后首次使用时生成） */
    private static Map<Block, String> oreToMineral;

    /** 岩石家族判定表（延迟构建；命令只在服务端线程执行，无需volatile） */
    private static Set<Block> rockFamily;

    private ModCommands() {
    }

    /** 注册命令树（挂NeoForge游戏总线的RegisterCommandsEvent） */
    public static void register(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("polymech")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("rock")
                        .executes(ctx -> showRock(ctx.getSource())))
                .then(Commands.literal("veins")
                        .executes(ctx -> showVeins(ctx.getSource())))
                .then(Commands.literal("scan")
                        .executes(ctx -> scan(ctx.getSource(), 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, MAX_RADIUS))
                                .executes(ctx -> scan(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("find")
                        .then(Commands.argument("mineral", StringArgumentType.word())
                                .suggests(ModCommands::suggestMinerals)
                                .executes(ctx -> find(ctx.getSource(), StringArgumentType.getString(ctx, "mineral"), 64))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, MAX_RADIUS))
                                        .executes(ctx -> find(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mineral"),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("expose")
                        .executes(ctx -> expose(ctx.getSource(), 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, EXPOSE_MAX_RADIUS))
                                .executes(ctx -> expose(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(physics())
                .then(Commands.literal("space")
                        .executes(ctx -> spaceSpawn(ctx.getSource()))
                        .then(Commands.literal("probe")
                                .executes(ctx -> spaceProbe(ctx.getSource())))
                        .then(Commands.literal("far")
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> spaceFar(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                        DoubleArgumentType.getDouble(ctx, "z"))))))
                        .then(Commands.literal("chunk")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(ctx -> spaceForceload(ctx.getSource(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "z"), true)))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(ctx -> spaceForceload(ctx.getSource(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "z"), false)))))
                                .then(Commands.literal("list")
                                        .executes(ctx -> spaceForceloadList(ctx.getSource()))))
                        .then(Commands.argument("body", StringArgumentType.word())
                                .suggests(ModCommands::suggestBodies)
                                .executes(ctx -> spaceAbove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "body"))))));
    }

    // ==================== /polymech space ====================

    /** /polymech space：进入太空维度（默认地球昼面观测点；原点 0,0,0 是太阳本体，半径 7 万格，不能作出生点）。 */
    private static int spaceSpawn(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (player.server.getLevel(PlanetDimensions.SPACE) == null) {
            source.sendFailure(Component.literal("太空维度不存在"));
            return 0;
        }
        if (!PlanetDimensions.teleportToSpaceAbove(player, 3)) { // 3 = 地球
            source.sendFailure(Component.literal("传送失败"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已进入太空维度（地球观测点）"), false);
        return 1;
    }

    /** /polymech space <body>：传送到太空维度中该天体上方的宇宙空间。 */
    private static int spaceAbove(CommandSourceStack source, String bodyId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int idx = RealAstroData.indexOf(bodyId);
        if (idx < 0) {
            source.sendFailure(Component.literal("未知天体: " + bodyId + "（用 Tab 补全查看可用名称）"));
            return 0;
        }
        if (!PlanetDimensions.teleportToSpaceAbove(player, idx)) {
            source.sendFailure(Component.literal("传送失败（太空维度不存在或索引无效）"));
            return 0;
        }
        RealAstroData body = RealAstroData.BODIES.get(idx);
        source.sendSuccess(() -> Component.literal("已传送到 " + body.name() + " 上方宇宙空间"), false);
        return 1;
    }

    /** 天体 id 补全（sun/mercury/...）。 */
    private static CompletableFuture<Suggestions> suggestBodies(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(RealAstroData.bodyIds(), builder);
    }

    /**
     * /polymech space probe：大坐标系自检。
     * 报告当前维度的世界边界、深空方块访问守卫是否生效、以及最近天体位置，
     * 用于验证"拆墙 + 深空真空"是否按预期工作。
     */
    private static int spaceProbe(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        net.minecraft.world.phys.Vec3 pos = source.getPosition();

        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        source.sendSuccess(() -> Component.literal(
                String.format("维度=%s 坐标=(%.1f, %.1f, %.1f)",
                        level.dimension().location(), pos.x, pos.y, pos.z)), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "世界边界: X[%.0f, %.0f] Z[%.0f, %.0f] (半宽 %.0f)",
                border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ(), border.getSize() / 2.0)), false);

        boolean deepGuard = SpaceWorld.isSpace(level)
                && level.getBlockState(new BlockPos(SpaceWorld.DEEP_SPACE_LIMIT + 100, 64, 0)).isAir()
                && !level.hasChunk((SpaceWorld.DEEP_SPACE_LIMIT + 100) >> 4, 0);
        source.sendSuccess(() -> Component.literal(
                deepGuard ? "深空守卫: 生效（守卫线外返回真空且未加载区块）"
                          : "深空守卫: 不生效（当前维度=" + level.dimension().location() + "）"), false);

        // P2 基础框架自检：天体数据驱动注册表是否加载 + 同步
        int bodyCount = com.mss.polymech.space.data.ModCelestialBodies.size(source.registryAccess());
        source.sendSuccess(() -> Component.literal("天体注册表 poly_mech:celestial_body: " + bodyCount + " 个"), false);

        int entityCountRaw = 0;
        for (net.minecraft.world.entity.Entity ignored : level.getEntities().getAll()) {
            entityCountRaw++;
        }
        final int entityCount = entityCountRaw;
        final int playerCount = level.players().size();
        source.sendSuccess(() -> Component.literal(String.format(
                "实体: %d 个（玩家 %d） | 建造高度 [%d, %d)",
                entityCount, playerCount, level.getMinBuildHeight(), level.getMaxBuildHeight())), false);

        RealAstroData nearest = null;
        double[] nearestPos = null;
        double best = Double.MAX_VALUE;
        double seconds = SpaceWorld.j2000Seconds();
        for (RealAstroData body : RealAstroData.BODIES) {
            double[] gp = SpaceWorld.gamePosMc(body);
            double dx = gp[0] - pos.x, dy = gp[1] - pos.y, dz = gp[2] - pos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < best) { best = d2; nearest = body; nearestPos = gp; }
        }
        if (nearest != null) {
            final RealAstroData nearestBody = nearest;
            final double[] nearestMc = nearestPos;
            final double dist = Math.sqrt(best);
            source.sendSuccess(() -> Component.literal(String.format(
                    "最近天体: %s (mc=%.0f, %.0f, %.0f) 距离=%.0f 格 = %.3e 米",
                    nearestBody.name(), nearestMc[0], nearestMc[1], nearestMc[2], dist, dist * SpaceWorld.ZOOM)), false);
        }
        return 1;
    }

    /**
     * /polymech physics —— 原生物理层（Rust + Rapier f64）自检与状态。
     * <ul>
     *   <li>{@code status}：原生库是否可用、ABI 版本、加载路径</li>
     *   <li>{@code selftest}：在原生侧建一个世界跑自由落体，验证整条链路</li>
     * </ul>
     */
    private static ArgumentBuilder<CommandSourceStack, ?> physics() {
        return Commands.literal("physics")
                .executes(ctx -> physicsStatus(ctx.getSource()))
                .then(Commands.literal("status")
                        .executes(ctx -> physicsStatus(ctx.getSource())))
                .then(Commands.literal("selftest")
                        .executes(ctx -> physicsSelftest(ctx.getSource(), 300))
                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> physicsSelftest(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "steps")))))
                .then(Commands.literal("voxeltest")
                        .executes(ctx -> physicsVoxelTest(ctx.getSource())))
                .then(Commands.literal("grouptest")
                        .executes(ctx -> physicsGroupTest(ctx.getSource())))
                .then(Commands.literal("terrain")
                        .executes(ctx -> physicsTerrainStatus(ctx.getSource()))
                        .then(Commands.literal("stop")
                                .executes(ctx -> physicsTerrainStop(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> physicsTerrainStatus(ctx.getSource())))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                .executes(ctx -> physicsTerrainBuild(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("attach")
                        .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities())
                                .executes(ctx -> physicsAttach(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getEntities(ctx, "targets")))))
                .then(Commands.literal("detach")
                        .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities())
                                .executes(ctx -> physicsDetach(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getEntities(ctx, "targets")))))
                .then(Commands.literal("grab")
                        .then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .executes(ctx -> physicsGrab(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "from"),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "to"))))))
                .then(Commands.literal("drop")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> physicsDrop(ctx.getSource(), LongArgumentType.getLong(ctx, "id")))))
                .then(Commands.literal("push")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("vx", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("vy", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("vz", DoubleArgumentType.doubleArg())
                                                        .executes(ctx -> physicsPush(ctx.getSource(),
                                                                LongArgumentType.getLong(ctx, "id"),
                                                                DoubleArgumentType.getDouble(ctx, "vx"),
                                                                DoubleArgumentType.getDouble(ctx, "vy"),
                                                                DoubleArgumentType.getDouble(ctx, "vz"))))))))
                .then(Commands.literal("synctest")
                        .executes(ctx -> physicsSyncTest(ctx.getSource())))
                .then(Commands.literal("breakblock")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("dx", IntegerArgumentType.integer(-64, 64))
                                        .then(Commands.argument("dy", IntegerArgumentType.integer(-64, 64))
                                                .then(Commands.argument("dz", IntegerArgumentType.integer(-64, 64))
                                                        .executes(ctx -> physicsBreakBlock(ctx.getSource(),
                                                                LongArgumentType.getLong(ctx, "id"),
                                                                IntegerArgumentType.getInteger(ctx, "dx"),
                                                                IntegerArgumentType.getInteger(ctx, "dy"),
                                                                IntegerArgumentType.getInteger(ctx, "dz"))))))))
                .then(Commands.literal("placeblock")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("dx", IntegerArgumentType.integer(-64, 64))
                                        .then(Commands.argument("dy", IntegerArgumentType.integer(-64, 64))
                                                .then(Commands.argument("dz", IntegerArgumentType.integer(-64, 64))
                                                        .then(Commands.argument("block", StringArgumentType.word())
                                                                .executes(ctx -> physicsPlaceBlock(ctx.getSource(),
                                                                        LongArgumentType.getLong(ctx, "id"),
                                                                        IntegerArgumentType.getInteger(ctx, "dx"),
                                                                        IntegerArgumentType.getInteger(ctx, "dy"),
                                                                        IntegerArgumentType.getInteger(ctx, "dz"),
                                                                        StringArgumentType.getString(ctx, "block")))))))))
                .then(Commands.literal("projection")
                        .executes(ctx -> physicsProjection(ctx.getSource(), -1L))
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> physicsProjection(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "id"))))
                        .then(Commands.literal("wipe")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 4096))
                                        .executes(ctx -> physicsProjectionWipe(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "slot"))))))
                .then(Commands.literal("placeat")
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .then(Commands.argument("block", StringArgumentType.word())
                                        .executes(ctx -> physicsPlaceAt(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos"),
                                                StringArgumentType.getString(ctx, "block"))))))
                .then(Commands.literal("lockrot")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("locked", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> physicsLockRot(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "id"),
                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "locked"))))))
                .then(Commands.literal("collisiongroups")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> physicsCollisionGroupsQuery(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "id")))
                                .then(Commands.argument("membership", IntegerArgumentType.integer())
                                        .then(Commands.argument("filter", IntegerArgumentType.integer())
                                                .executes(ctx -> physicsCollisionGroups(ctx.getSource(),
                                                        LongArgumentType.getLong(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "membership"),
                                                        IntegerArgumentType.getInteger(ctx, "filter")))))))
                .then(Commands.literal("bodies")
                        .executes(ctx -> physicsBodyList(ctx.getSource())))
                .then(Commands.literal("entities")
                        .executes(ctx -> physicsEntityStatus(ctx.getSource())))
                .then(Commands.literal("entitytest")
                        .executes(ctx -> physicsEntityTest(ctx.getSource(), 600))
                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> physicsEntityTest(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "steps")))))
                .then(Commands.literal("terraintest")
                        .executes(ctx -> physicsTerrainTest(ctx.getSource(), 400))
                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> physicsTerrainTest(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "steps")))));
    }

    private static int physicsStatus(CommandSourceStack source) {
        boolean ok = com.mss.polymech.physics.PhysicsNatives.isAvailable();
        String status = com.mss.polymech.physics.PhysicsNatives.status();
        source.sendSuccess(() -> Component.literal("物理原生层: " + (ok ? "§a可用" : "§c不可用")), false);
        source.sendSuccess(() -> Component.literal("  " + status), false);
        if (ok) {
            source.sendSuccess(() -> Component.literal("  平台键: "
                    + com.mss.polymech.physics.PhysicsNatives.platformKey()), false);
            source.sendSuccess(() -> Component.literal(
                    "  物理体同步：待客户端确认的快照 "
                            + com.mss.polymech.physics.PhysicsBodyTracker.pendingAckCount()
                            + " 个（>0 说明握手还没完成，服务端会每 2 秒重发一次、最多 5 次）"), false);
        }
        return ok ? 1 : 0;
    }

    /** /polymech physics grab <from> <to>：把区域方块抠出来变成可见的动态刚体。 */
    private static int physicsGrab(CommandSourceStack source, BlockPos from, BlockPos to) throws CommandSyntaxException {
        if (!com.mss.polymech.physics.PhysicsNatives.isAvailable()) {
            source.sendFailure(Component.literal("物理原生层不可用: "
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        long id = com.mss.polymech.physics.PhysicsBodyTracker.createFromRegion(level, from, to);
        if (id < 0) {
            source.sendFailure(Component.literal("创建失败（区域为空、过大或超过方块上限 "
                    + com.mss.polymech.physics.PhysicsBodyTracker.MAX_BLOCKS + "）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已创建物理体 id=" + id
                + "（方块已从世界抠出，客户端将渲染它）"), true);
        return 1;
    }

    /** /polymech physics breakblock <id> <dx> <dy> <dz>：敲掉物理体上的一个方块（服务端链路测试）。 */
    private static int physicsBreakBlock(CommandSourceStack source, long id, int dx, int dy, int dz) {
        int before = com.mss.polymech.physics.PhysicsBodyTracker.blocksOf(id).size();
        if (!com.mss.polymech.physics.PhysicsBodyTracker.breakBlock(id, dx, dy, dz)) {
            source.sendFailure(Component.literal("破坏失败：物理体不存在或该位置没有方块"));
            return 0;
        }
        int after = com.mss.polymech.physics.PhysicsBodyTracker.blocksOf(id).size();
        source.sendSuccess(() -> Component.literal(String.format(
                "已破坏物理体 %d 的方块 (%d,%d,%d)：方块数 %d -> %d（碰撞体已重建）", id, dx, dy, dz, before, after)), true);
        return 1;
    }

    /** /polymech physics placeblock <id> <dx> <dy> <dz> <block>：在物理体上放一个方块。 */
    private static int physicsPlaceBlock(CommandSourceStack source, long id, int dx, int dy, int dz, String blockId) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (rl == null) {
            source.sendFailure(Component.literal("无法解析方块 id: " + blockId));
            return 0;
        }
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            source.sendFailure(Component.literal("未知方块: " + blockId));
            return 0;
        }
        int stateId = net.minecraft.world.level.block.Block.getId(block.defaultBlockState());
        if (!com.mss.polymech.physics.PhysicsBodyTracker.placeBlock(id, dx, dy, dz, stateId)) {
            source.sendFailure(Component.literal("放置失败：物理体不存在/该位置已有方块/超过方块上限"));
            return 0;
        }
        int after = com.mss.polymech.physics.PhysicsBodyTracker.blocksOf(id).size();
        source.sendSuccess(() -> Component.literal(String.format(
                "已在物理体 %d 放置方块 (%d,%d,%d) = %s：方块数 %d", id, dx, dy, dz, rl, after)), true);
        return 1;
    }

    /**
     * /polymech physics projection [id]：投影维度自检。
     * 带 id 时进一步报告该物理体的地皮里<b>实际</b>有多少非空气方块与方块实体 ——
     * 这是验证"机器连同内部状态一起进了投影"的直接手段。
     */
    private static int physicsProjection(CommandSourceStack source, long bodyId) {
        boolean ready = com.mss.polymech.physics.ProjectionManager.isReady();
        source.sendSuccess(() -> Component.literal("投影维度 "
                + com.mss.polymech.physics.ProjectionManager.DIMENSION_ID + ": "
                + (ready ? "§a就绪" : "§c缺失（检查 datapack 里的 dimension/projection_world.json）")), false);
        if (ready) {
            source.sendSuccess(() -> Component.literal(
                    "  待回写脏区块 " + com.mss.polymech.physics.ProjectionManager.dirtyChunkCount()
                            + "（投影里被改过、还没搬到刚体的区块）"), false);
            source.sendSuccess(() -> Component.literal(
                    "  掉落物搬运：成功 " + com.mss.polymech.physics.ProjectionManager.relocatedDropCount()
                            + " / 经验球 " + com.mss.polymech.physics.ProjectionManager.relocatedOrbCount()
                            + " / 销毁垃圾 " + com.mss.polymech.physics.ProjectionManager.discardedDropCount()
                            + "（销毁=归属不到任何物理体，留在隐藏维度也没人要）"), false);
            source.sendSuccess(() -> Component.literal(String.format(
                    "  地皮 %d³、间距 %d、底部 Y=%d；占用槽位 %d（最高到 %d）；实际维度键 %s",
                    com.mss.polymech.physics.ProjectionManager.SLOT_SIZE,
                    com.mss.polymech.physics.ProjectionManager.SLOT_SPACING,
                    com.mss.polymech.physics.ProjectionManager.SLOT_MIN_Y,
                    com.mss.polymech.physics.ProjectionManager.allocatedSlots(),
                    com.mss.polymech.physics.ProjectionManager.slotCeiling(),
                    com.mss.polymech.physics.ProjectionManager.level().dimension().location())), false);
        }
        if (ready && bodyId > 0) {
            int slot = com.mss.polymech.physics.ProjectionManager.slotOf(bodyId);
            if (slot < 0) {
                source.sendFailure(Component.literal("物理体 " + bodyId + " 没有投影地皮（不存在，或建体时投影维度未就绪）"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("  物理体 " + bodyId + " → "
                    + com.mss.polymech.physics.ProjectionManager.describeSlot(slot)), false);
        }
        return ready ? 1 : 0;
    }

    /**
     * /polymech physics projection wipe &lt;slot&gt;：把整块地皮清成空气（清理孤儿方块）。
     *
     * <p>分配地皮时已自动清一遍，这里是手动兜底（比如怀疑某块地皮有看不见的导电残留）。</p>
     */
    private static int physicsProjectionWipe(CommandSourceStack source, int slot) {
        if (!com.mss.polymech.physics.ProjectionManager.isReady()) {
            source.sendFailure(Component.literal("投影维度未就绪"));
            return 0;
        }
        int cleared = com.mss.polymech.physics.ProjectionManager.wipeSlot(slot);
        if (cleared < 0) {
            source.sendFailure(Component.literal("清理失败：槽位无效"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已清空地皮 " + slot + "：清掉 " + cleared + " 个方块"), true);
        return 1;
    }

    /**
     * /polymech physics placeat &lt;pos&gt; &lt;block&gt;：按"物理体建造"语义在该位置放一块 ——
     * 有面相邻的物理体就并进去，没有就由这一块新建一个物理体（{@link PhysicsBodyTracker#placeBlockAt}）。
     * 用于验证"太空里摆出来的方块就是物理体"这条链路。
     */
    private static int physicsPlaceAt(CommandSourceStack source, BlockPos pos, String blockId) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (rl == null) {
            source.sendFailure(Component.literal("无法解析方块 id: " + blockId));
            return 0;
        }
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            source.sendFailure(Component.literal("未知方块: " + blockId));
            return 0;
        }
        int stateId = net.minecraft.world.level.block.Block.getId(block.defaultBlockState());
        long id = com.mss.polymech.physics.PhysicsBodyTracker.placeBlockAt(source.getLevel(), pos, stateId);
        if (id < 0) {
            source.sendFailure(Component.literal("放置失败：该格已被方块占用、原生层不可用或超限"));
            return 0;
        }
        int count = com.mss.polymech.physics.PhysicsBodyTracker.blocksOf(id).size();
        source.sendSuccess(() -> Component.literal(String.format(
                "已放置 %s 到 %s → 物理体 %d（方块数 %d）", rl, pos.toShortString(), id, count)), true);
        return 1;
    }

    /** /polymech physics lockrot <id> <true|false>：冻结/解冻刚体旋转。 */
    private static int physicsLockRot(CommandSourceStack source, long id, boolean locked) {
        if (!com.mss.polymech.physics.PhysicsBodyTracker.lockRotations(id, locked)) {
            source.sendFailure(Component.literal("操作失败：找不到物理体 id=" + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("物理体 " + id + " 旋转"
                + (locked ? "已冻结" : "已解锁")), true);
        return 1;
    }

    /** /polymech physics drop <id>：把物理体按当前姿态放回世界。 */
    private static int physicsDrop(CommandSourceStack source, long id) {
        boolean ok = com.mss.polymech.physics.PhysicsBodyTracker.drop(id);
        if (!ok) {
            source.sendFailure(Component.literal("找不到物理体 id=" + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("物理体 " + id + " 已放回世界"), true);
        return 1;
    }

    /** /polymech physics push <id> <vx> <vy> <vz>：给物理体施加速度。 */
    private static int physicsPush(CommandSourceStack source, long id, double vx, double vy, double vz) {
        if (!com.mss.polymech.physics.PhysicsBodyTracker.push(id, vx, vy, vz)) {
            source.sendFailure(Component.literal("推动失败：找不到物理体 id=" + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "已给物理体 %d 设置速度 (%.2f, %.2f, %.2f)", id, vx, vy, vz)), true);
        return 1;
    }

    /**
     * /polymech physics synctest —— 同步包编解码往返测试。
     * 客户端渲染依赖这个包，序列化错误在真实联机里表现为"看不见/错位"，很难查，所以单测一遍。
     */
    private static int physicsSyncTest(CommandSourceStack source) {
        try {
            java.util.List<com.mss.polymech.network.PhysicsBodySyncPacket.BlockEntry> blocks = java.util.List.of(
                    new com.mss.polymech.network.PhysicsBodySyncPacket.BlockEntry((short) 0, (short) 0, (short) 0,
                            net.minecraft.world.level.block.Block.getId(Blocks.STONE.defaultBlockState())),
                    new com.mss.polymech.network.PhysicsBodySyncPacket.BlockEntry((short) 2, (short) 1, (short) -3,
                            net.minecraft.world.level.block.Block.getId(Blocks.OAK_PLANKS.defaultBlockState())));

            var created = com.mss.polymech.network.PhysicsBodySyncPacket.create(7L,
                    1.5, 64.25, -3.75, 0.0f, 0.0f, 0.0f, 1.0f, blocks);
            var update = com.mss.polymech.network.PhysicsBodySyncPacket.update(7L, 2.5, 65.25, -4.75,
                    0.1f, 0.2f, 0.3f, 0.9f);
            var remove = com.mss.polymech.network.PhysicsBodySyncPacket.remove(7L);

            int failures = 0;
            failures += roundTrip(source, created) ? 0 : 1;
            failures += roundTrip(source, update) ? 0 : 1;
            failures += roundTrip(source, remove) ? 0 : 1;

            final int failed = failures;
            source.sendSuccess(() -> Component.literal(failed == 0
                    ? "§a✔ 同步包编解码往返正常（CREATE/UPDATE/REMOVE 三种动作）"
                    : "§c✘ 同步包往返失败 " + failed + " 项"), false);
            return failed == 0 ? 1 : 0;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("同步包测试异常: " + t));
            return 0;
        }
    }

    private static boolean roundTrip(CommandSourceStack source, com.mss.polymech.network.PhysicsBodySyncPacket packet) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()), source.registryAccess());
        com.mss.polymech.network.PhysicsBodySyncPacket.STREAM_CODEC.encode(buffer, packet);
        var decoded = com.mss.polymech.network.PhysicsBodySyncPacket.STREAM_CODEC.decode(buffer);
        boolean ok = decoded.action() == packet.action()
                && decoded.bodyId() == packet.bodyId()
                && Math.abs(decoded.x() - packet.x()) < 1e-9
                && Math.abs(decoded.y() - packet.y()) < 1e-9
                && Math.abs(decoded.z() - packet.z()) < 1e-9
                && Math.abs(decoded.qw() - packet.qw()) < 1e-6
                && decoded.blocks().size() == packet.blocks().size();
        if (ok && !packet.blocks().isEmpty()) {
            for (int i = 0; i < packet.blocks().size(); i++) {
                var a = packet.blocks().get(i);
                var b = decoded.blocks().get(i);
                if (a.dx() != b.dx() || a.dy() != b.dy() || a.dz() != b.dz() || a.stateId() != b.stateId()) {
                    ok = false;
                    break;
                }
            }
        }
        return ok;
    }

    private static int physicsBodyList(CommandSourceStack source) {
        var described = com.mss.polymech.physics.PhysicsBodyTracker.describe();
        source.sendSuccess(() -> Component.literal("物理体数量: " + described.size()), false);
        for (String line : described) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return described.size();
    }

    /**
     * /polymech physics collisiongroups &lt;id&gt; &lt;membership&gt; &lt;filter&gt;：
     * 设置物理体的碰撞组并立刻重建它的碰撞体。
     *
     * <p>Rapier 的判定是<b>双向</b>的：A 与 B 交互 ⟺ {@code (A.mem & B.filter) != 0 且 (B.mem & A.filter) != 0}，
     * 两边都得放行才碰得上。拿两个极端值验证最直观：</p>
     * <ul>
     *   <li>{@code 1 -1} —— 默认：membership=1、filter=全 1，与所有组交互（等价于没设过）；</li>
     *   <li>{@code 1 0}  —— 幽灵：filter 空集，跟谁都碰不上，人可以直接穿过去。</li>
     * </ul>
     *
     * <p>只对物理体（方块构成的刚体）生效，玩家/实体的碰撞体不走这里。
     * 设置只活在本次运行内，不会写进存档 —— 重启服务器后全部回到默认（调试用足够了）。</p>
     */
    private static int physicsCollisionGroups(CommandSourceStack source, long id, int membership, int filter) {
        if (!com.mss.polymech.physics.PhysicsNatives.hasCollisionGroups()) {
            source.sendFailure(Component.literal("原生库不支持碰撞组（需要 ABI ≥ "
                    + com.mss.polymech.physics.PhysicsNatives.MIN_ABI_COLLISION_GROUPS + "）："
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        if (!com.mss.polymech.physics.PhysicsBodyTracker.setCollisionGroups(id, membership, filter)) {
            source.sendFailure(Component.literal("设置失败：没有 id=" + id + " 的物理体（用 /polymech physics bodies 看看现有哪些）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("物理体 " + id + " 碰撞组已设为 membership=" + membership
                + " filter=" + filter + "（碰撞体已重建，立即生效）"), true);
        return 1;
    }

    /** /polymech physics collisiongroups &lt;id&gt;：查该物理体当前登记的碰撞组。 */
    private static int physicsCollisionGroupsQuery(CommandSourceStack source, long id) {
        if (com.mss.polymech.physics.PhysicsBodyTracker.levelOf(id) == null) {
            source.sendFailure(Component.literal("没有 id=" + id + " 的物理体（用 /polymech physics bodies 看看现有哪些）"));
            return 0;
        }
        int[] groups = com.mss.polymech.physics.PhysicsBodyTracker.collisionGroupsOf(id);
        String text = groups == null
                ? "物理体 " + id + " 未登记碰撞组 → 默认（与所有组交互）"
                : "物理体 " + id + " 碰撞组 membership=" + groups[0] + " filter=" + groups[1];
        source.sendSuccess(() -> Component.literal(text + "；原生支持="
                + com.mss.polymech.physics.PhysicsNatives.hasCollisionGroups()), false);
        return 1;
    }

    /** /polymech physics attach <目标>：把实体交给物理层驱动。 */
    private static int physicsAttach(CommandSourceStack source, java.util.Collection<? extends net.minecraft.world.entity.Entity> targets) {
        int ok = 0;
        for (net.minecraft.world.entity.Entity entity : targets) {
            if (entity instanceof net.minecraft.server.level.ServerPlayer) {
                source.sendFailure(Component.literal("玩家接管尚未实现（客户端权威移动），请用非玩家实体"));
                continue;
            }
            if (com.mss.polymech.physics.PhysicsEntityManager.attach(entity)) {
                ok++;
            }
        }
        final int count = ok;
        source.sendSuccess(() -> Component.literal("已接管 " + count + " 个实体到物理层"), true);
        return count;
    }

    /** /polymech physics detach <目标>：解除物理接管。 */
    private static int physicsDetach(CommandSourceStack source, java.util.Collection<? extends net.minecraft.world.entity.Entity> targets) {
        int ok = 0;
        for (net.minecraft.world.entity.Entity entity : targets) {
            if (com.mss.polymech.physics.PhysicsEntityManager.isAttached(entity)) {
                com.mss.polymech.physics.PhysicsEntityManager.detach(entity);
                ok++;
            }
        }
        final int count = ok;
        source.sendSuccess(() -> Component.literal("已解除 " + count + " 个实体的物理接管"), true);
        return count;
    }

    private static int physicsEntityStatus(CommandSourceStack source) {
        int count = com.mss.polymech.physics.PhysicsEntityManager.attachedCount();
        source.sendSuccess(() -> Component.literal("物理接管实体数: " + count), false);
        return count;
    }

    /**
     * /polymech physics entitytest —— 实体接管端到端验证：
     * 在地形上方生成一个盔甲架并交给物理层，检查它是否停在
     * 「最高方块顶面 + 半个碰撞箱高度」，再施加冲量看它是否被推动。
     */
    private static int physicsEntityTest(CommandSourceStack source, int steps) {
        if (!com.mss.polymech.physics.PhysicsNatives.isAvailable()) {
            source.sendFailure(Component.literal("物理原生层不可用: "
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        level.getChunk(center.getX() >> 4, center.getZ() >> 4);
        var terrain = com.mss.polymech.physics.PhysicsWorldManager.terrain(level);
        terrain.update(center, 1);

        int topY = Integer.MIN_VALUE;
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(center.getX(), y, center.getZ()));
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                topY = y;
                break;
            }
        }
        if (topY == Integer.MIN_VALUE) {
            source.sendFailure(Component.literal("该列没有可用于碰撞的方块"));
            return 0;
        }

        double bx = center.getX() + 0.5;
        double bz = center.getZ() + 0.5;
        net.minecraft.world.entity.decoration.ArmorStand stand =
                new net.minecraft.world.entity.decoration.ArmorStand(level, bx, topY + 9.0, bz);
        stand.setNoGravity(false);
        level.addFreshEntity(stand);

        if (!com.mss.polymech.physics.PhysicsEntityManager.attach(stand)) {
            stand.discard();
            source.sendFailure(Component.literal("实体接管失败"));
            return 0;
        }

        long world = com.mss.polymech.physics.PhysicsWorldManager.world(level);
        // 手动步进以得到确定性结果（服务器 tick 仍会继续步进，不影响收敛）
        for (int i = 0; i < steps; i++) {
            com.mss.polymech.physics.NativePhysics.worldStep(world);
        }
        com.mss.polymech.physics.PhysicsEntityManager.tick();

        float halfHeight = stand.getBbHeight() * 0.5f;
        double expectedY = topY + 1.0;
        double actualY = stand.getY();
        double actualX = stand.getX();
        double actualZ = stand.getZ();

        // 施加横向冲量，验证实体被物理推动
        long body = 0;
        double pushedX = actualX;
        if (true) {
            // 通过管理器内部 body 句柄施加冲量：复用 attach 时创建的世界
            double[] pos = new double[3];
            // 直接对刚体施力：先找到实体的 body（管理器提供接口更干净，这里用速度设置代替冲量）
            com.mss.polymech.physics.NativePhysics.bodySetVelocity(world, bodyHandleOf(stand), 5.0, 0.0, 0.0);
            for (int i = 0; i < 40; i++) {
                com.mss.polymech.physics.NativePhysics.worldStep(world);
            }
            com.mss.polymech.physics.PhysicsEntityManager.tick();
            pushedX = stand.getX();
        }

        final double fExpected = expectedY;
        final double fActual = actualY;
        final double fPushed = pushedX - actualX;
        final float fHalf = halfHeight;
        source.sendSuccess(() -> Component.literal(String.format(
                "实体接管测试: 盔甲架 y=%.3f（脚底期望 %.3f，半高 %.3f）| x=%.3f z=%.3f | 横向推动后 Δx=%.3f",
                fActual, fExpected, fHalf, actualX, actualZ, fPushed)), false);
        boolean pass = Math.abs(actualY - expectedY) < 0.15 && Math.abs(fPushed) > 0.5;
        source.sendSuccess(() -> Component.literal(
                pass ? "§a✔ 实体由物理驱动，正确停在真实地形上，且可被推动"
                     : "§e⚠ 落点或推动不符合预期"), false);
        com.mss.polymech.physics.PhysicsEntityManager.detach(stand);
        stand.discard();
        return pass ? 1 : 0;
    }

    /** 取实体当前刚体句柄（仅测试用）。 */
    private static long bodyHandleOf(net.minecraft.world.entity.Entity entity) {
        return com.mss.polymech.physics.PhysicsEntityManager.bodyHandle(entity);
    }

    /**
     * /polymech physics terrain [radius] —— 启用/更新地形体素快照（以命令执行位置为中心）。
     */
    private static int physicsTerrainBuild(CommandSourceStack source, int radius) {
        if (!com.mss.polymech.physics.PhysicsNatives.isAvailable()) {
            source.sendFailure(Component.literal("物理原生层不可用: "
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        long start = System.nanoTime();
        com.mss.polymech.physics.PhysicsWorldManager.terrain(level).update(center, radius);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        com.mss.polymech.physics.PhysicsTerrain terrain =
                com.mss.polymech.physics.PhysicsWorldManager.terrain(level);
        source.sendSuccess(() -> Component.literal(String.format(
                "地形快照已更新: 中心=(%d, %d, %d) 半径=%d | 追踪 %d 区块 / %d 体素 | 跳过未加载 %d | %.1f ms",
                center.getX(), center.getY(), center.getZ(), radius,
                terrain.chunkCount(), terrain.totalCells(), terrain.lastSkippedUnloaded(), ms)), true);
        return 1;
    }

    private static int physicsTerrainStop(CommandSourceStack source) {
        com.mss.polymech.physics.PhysicsTerrain terrain =
                com.mss.polymech.physics.PhysicsWorldManager.terrain(source.getLevel());
        terrain.clear();
        source.sendSuccess(() -> Component.literal("地形快照已停止并清除。"), true);
        return 1;
    }

    private static int physicsTerrainStatus(CommandSourceStack source) {
        com.mss.polymech.physics.PhysicsTerrain terrain =
                com.mss.polymech.physics.PhysicsWorldManager.terrainIfPresent(source.getLevel());
        if (terrain == null || !terrain.isActive()) {
            source.sendSuccess(() -> Component.literal("地形快照: 未启用"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "地形快照: 半径=%d 区块 | %d 区块 / %d 体素 | 重建 %d 次，平均 %.1f ms | 物理世界数=%d",
                terrain.radius(), terrain.chunkCount(), terrain.totalCells(),
                terrain.buildCount(), terrain.averageBuildMs(),
                com.mss.polymech.physics.PhysicsWorldManager.worldCount())), false);
        return 1;
    }

    /**
     * /polymech physics terraintest —— 地形快照端到端验证：把真实地形变成体素碰撞体，
     * 在其正上方丢一个物理箱子，检查它是否停在「该列最高非空气方块顶面 + 半高」上。
     */
    private static int physicsTerrainTest(CommandSourceStack source, int steps) {
        if (!com.mss.polymech.physics.PhysicsNatives.isAvailable()) {
            source.sendFailure(Component.literal("物理原生层不可用: "
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        var terrain = com.mss.polymech.physics.PhysicsWorldManager.terrain(level);
        // 关键：先确保落点区块已加载，否则地形快照会（正确地）跳过它，箱子会穿地
        level.getChunk(center.getX() >> 4, center.getZ() >> 4);
        long start = System.nanoTime();
        terrain.update(center, 1);
        double buildMs = (System.nanoTime() - start) / 1_000_000.0;
        long world = com.mss.polymech.physics.PhysicsWorldManager.world(level);
        if (world <= 0) {
            source.sendFailure(Component.literal("物理世界创建失败"));
            return 0;
        }

        // 该列上"参与碰撞"的最高方块（与体素快照同一过滤条件：非空气且非流体）
        int minY = level.getMinBuildHeight();
        int topY = Integer.MIN_VALUE;
        for (int y = level.getMaxBuildHeight() - 1; y >= minY; y--) {
            BlockState state = level.getBlockState(new BlockPos(center.getX(), y, center.getZ()));
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                topY = y;
                break;
            }
        }
        if (topY == Integer.MIN_VALUE) {
            source.sendFailure(Component.literal("该列没有可用于碰撞的方块"));
            return 0;
        }

        double bx = center.getX() + 0.5;
        double bz = center.getZ() + 0.5;
        double spawnY = topY + 8.0;
        long cube = com.mss.polymech.physics.NativePhysics.bodyCreate(world,
                com.mss.polymech.physics.NativePhysics.BODY_DYNAMIC, bx, spawnY, bz, 0, 0, 0, 1, 0);
        com.mss.polymech.physics.NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.6, 0.0);

        double[] pos = new double[3];
        for (int i = 0; i < steps; i++) {
            com.mss.polymech.physics.NativePhysics.worldStep(world);
        }
        com.mss.polymech.physics.NativePhysics.bodyReadTranslation(world, cube, pos);
        com.mss.polymech.physics.NativePhysics.bodyDestroy(world, cube);

        double expected = topY + 1.0 + 0.5;
        final double restY = pos[1];
        final int finalTopY = topY;
        final int trackedChunks = terrain.chunkCount();
        final long cells = terrain.totalCells();
        source.sendSuccess(() -> Component.literal(String.format(
                "地形碰撞测试: %d 区块 / %d 体素（建表 %.1f ms）| 落点 y=%.3f，期望 %.3f（最高方块 y=%d + 1 + 半高）| dx=%.3f dz=%.3f",
                trackedChunks, cells, buildMs, restY, expected, finalTopY, pos[0] - bx, pos[2] - bz)), false);
        boolean pass = Math.abs(restY - expected) < 0.1;
        source.sendSuccess(() -> Component.literal(
                pass ? "§a✔ 物理箱体正确停在真实地形上（区块 → 体素碰撞体 → 碰撞）"
                     : "§e⚠ 落点偏离，可能落到了旁边的方块或穿透"), false);
        return pass ? 1 : 0;
    }

    /**
     * /polymech physics voxeltest —— 体素碰撞体端到端测试。
     *
     * <p>用方块网格造一个 6×2×6 的实心平台（体素碰撞体），上方 5 格丢一个动态立方体：
     * 期望它落在平台顶面（y = 2.0）上，最终停在 y ≈ 2.5（+半边长 0.5）。</p>
     */
    private static int physicsVoxelTest(CommandSourceStack source) {
        if (!com.mss.polymech.physics.PhysicsNatives.isAvailable()) {
            source.sendFailure(Component.literal("物理原生层不可用: "
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        long start = System.nanoTime();
        long world = com.mss.polymech.physics.NativePhysics.worldCreate(0.0, -9.8, 0.0);
        if (world <= 0) {
            source.sendFailure(Component.literal("worldCreate 失败"));
            return 0;
        }
        try {
            com.mss.polymech.physics.NativePhysics.worldSetTimestep(world, 1.0 / 60.0);

            // 平台：网格 (0..5, 0..1, 0..5) 全部填充 → 占据 [0,6]x[0,2]x[0,6]
            java.util.List<Long> cells = new java.util.ArrayList<>();
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 6; z++) {
                        cells.add(com.mss.polymech.physics.NativePhysics.packCell(x, y, z));
                    }
                }
            }
            long[] packed = new long[cells.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = cells.get(i);
            }

            long platform = com.mss.polymech.physics.NativePhysics.bodyCreate(
                    world, com.mss.polymech.physics.NativePhysics.BODY_FIXED, 0, 0, 0, 0, 0, 0, 1, 0);
            long voxelCollider = com.mss.polymech.physics.NativePhysics.colliderAttachVoxels(
                    world, platform, 1.0, 1.0, 1.0, packed, 0.8, 0.0);

            // 落体：半边长 0.5 的立方体，放在平台中心正上方 y = 7
            long cube = com.mss.polymech.physics.NativePhysics.bodyCreate(
                    world, com.mss.polymech.physics.NativePhysics.BODY_DYNAMIC, 3.0, 7.0, 3.0, 0, 0, 0, 1, 0);
            com.mss.polymech.physics.NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.6, 0.0);

            double[] pos = new double[3];
            for (int i = 0; i < 600; i++) {
                com.mss.polymech.physics.NativePhysics.worldStep(world);
            }
            com.mss.polymech.physics.NativePhysics.bodyReadTranslation(world, cube, pos);
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            final double cubeY = pos[1];
            final long voxels = packed.length;
            final long vc = voxelCollider;
            source.sendSuccess(() -> Component.literal(String.format(
                    "体素碰撞体: 体素数=%d id=%d | 方块最终 y=%.4f（期望 2.5）| x=%.3f z=%.3f | %.1f ms",
                    voxels, vc, cubeY, pos[0], pos[2], ms)), false);
            boolean pass = Math.abs(cubeY - 2.5) < 0.08 && vc > 0;
            source.sendSuccess(() -> Component.literal(
                    pass ? "§a✔ 体素碰撞体工作正常（方块区域 → Rapier Voxels → 正确落点）"
                         : "§e⚠ 落点偏离预期（体素坐标约定或形状参数需校正）"), false);
            return pass ? 1 : 0;
        } finally {
            com.mss.polymech.physics.NativePhysics.worldDestroy(world);
        }
    }

    /**
     * /polymech physics grouptest —— 用一对 A/B 世界验证碰撞组<b>真的生效</b>
     * （而不是"符号存在、参数被忽略"）。
     *
     * <p>A：平台 filter=0（空集，与谁都交互不了）→ 落体应当<b>穿过去</b>；<br>
     * B：平台 filter=-1（全放行，默认）→ 落体应当<b>停在平台上</b>。</p>
     *
     * <p>注意这里只改了<b>平台一侧</b>的 filter 就让交互失效 —— 这正是 Rapier 的双向语义
     * （{@code (A.mem & B.filter) != 0 && (B.mem & A.filter) != 0}）在起作用。</p>
     */
    private static int physicsGroupTest(CommandSourceStack source) {
        if (!com.mss.polymech.physics.PhysicsNatives.hasCollisionGroups()) {
            source.sendFailure(Component.literal("原生层没有碰撞组能力（需要 ABI ≥ "
                    + com.mss.polymech.physics.PhysicsNatives.MIN_ABI_COLLISION_GROUPS + "）："
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        // 落体组固定 (2, -1)：它愿意碰平台（1 & -1 != 0），成不成全看平台那一侧
        double ghostY = groupProbe(1, 0, 2, -1);
        double normalY = groupProbe(1, -1, 2, -1);

        boolean passedThrough = ghostY < -10.0;               // 穿过去了（5 秒自由落体 ≈ -116）
        boolean restedOnTop = Math.abs(normalY - 0.5) < 0.08; // 停在平台顶面（顶面 y=0，半边长 0.5）
        boolean pass = passedThrough && restedOnTop;

        source.sendSuccess(() -> Component.literal(String.format(
                "碰撞组: 幽灵平台(filter=0) 落体 y=%.2f（期望 < -10，穿过）| 默认平台(filter=-1) 落体 y=%.4f（期望 0.5，停住）",
                ghostY, normalY)), false);
        source.sendSuccess(() -> Component.literal(pass
                ? "§a✔ 碰撞组生效（filter=0 确实「谁都碰不到」）"
                : "§e⚠ 碰撞组没生效或语义不符（查 InteractionGroups 的 membership/filter 顺序与 InteractionTestMode）"), false);
        return pass ? 1 : 0;
    }

    /**
     * 建一个"平台 + 落体"世界跑 300 步（5 秒），返回落体的最终 y。
     *
     * @param platformMem    平台 membership
     * @param platformFilter 平台 filter（0 = 谁都不碰）
     * @param cubeMem        落体 membership
     * @param cubeFilter     落体 filter
     */
    private static double groupProbe(int platformMem, int platformFilter, int cubeMem, int cubeFilter) {
        long world = com.mss.polymech.physics.NativePhysics.worldCreate(0.0, -9.8, 0.0);
        if (world <= 0) {
            return Double.NaN;
        }
        try {
            com.mss.polymech.physics.NativePhysics.worldSetTimestep(world, 1.0 / 60.0);
            // 平台：半长 10×0.5×10，中心 y=-0.5 → 顶面正好在 y=0
            long ground = com.mss.polymech.physics.NativePhysics.bodyCreate(world,
                    com.mss.polymech.physics.NativePhysics.BODY_FIXED, 0, -0.5, 0, 0, 0, 0, 1, 0);
            com.mss.polymech.physics.NativePhysics.colliderAttachCuboidGrouped(world, ground,
                    10, 0.5, 10, 0.8, 0.0, platformMem, platformFilter);
            // 落体：半边长 0.5，从 y=6 自由落体
            long cube = com.mss.polymech.physics.NativePhysics.bodyCreate(world,
                    com.mss.polymech.physics.NativePhysics.BODY_DYNAMIC, 0, 6, 0, 0, 0, 0, 1, 0);
            com.mss.polymech.physics.NativePhysics.colliderAttachCuboidGrouped(world, cube,
                    0.5, 0.5, 0.5, 0.6, 0.0, cubeMem, cubeFilter);
            for (int i = 0; i < 300; i++) {
                com.mss.polymech.physics.NativePhysics.worldStep(world);
            }
            double[] pos = new double[3];
            com.mss.polymech.physics.NativePhysics.bodyReadTranslation(world, cube, pos);
            return pos[1];
        } finally {
            com.mss.polymech.physics.NativePhysics.worldDestroy(world);
        }
    }

    private static int physicsSelftest(CommandSourceStack source, int steps) {
        if (!com.mss.polymech.physics.PhysicsNatives.isAvailable()) {
            source.sendFailure(Component.literal("物理原生层不可用: "
                    + com.mss.polymech.physics.PhysicsNatives.status()));
            return 0;
        }
        long start = System.nanoTime();
        double restY = com.mss.polymech.physics.NativePhysics.selftest(steps);
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        // 独立世界：验证 Java 侧驱动 API（不只是 Rust 内部自检）
        long world = com.mss.polymech.physics.NativePhysics.worldCreate(0.0, -9.8, 0.0);
        if (world <= 0) {
            source.sendFailure(Component.literal("worldCreate 失败"));
            return 0;
        }
        try {
            com.mss.polymech.physics.NativePhysics.worldSetTimestep(world, 1.0 / 60.0);
            long ground = com.mss.polymech.physics.NativePhysics.bodyCreate(
                    world, com.mss.polymech.physics.NativePhysics.BODY_FIXED, 0, -0.5, 0, 0, 0, 0, 1, 0);
            com.mss.polymech.physics.NativePhysics.colliderAttachCuboid(world, ground, 10, 0.5, 10, 0.8, 0.0);
            long cube = com.mss.polymech.physics.NativePhysics.bodyCreate(
                    world, com.mss.polymech.physics.NativePhysics.BODY_DYNAMIC, 0, 10, 0, 0, 0, 0, 1, 0);
            com.mss.polymech.physics.NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.5, 0.0);

            double[] pos = new double[3];
            for (int i = 0; i < 360; i++) {
                com.mss.polymech.physics.NativePhysics.worldStep(world);
            }
            com.mss.polymech.physics.NativePhysics.bodyReadTranslation(world, cube, pos);

            final double cubeY = pos[1];
            final int bodyCount = com.mss.polymech.physics.NativePhysics.worldBodyCount(world);
            source.sendSuccess(() -> Component.literal(String.format(
                    "物理自检: 原生落体 y=%.4f（期望 0.5） | Java 驱动落体 y=%.4f | 刚体数=%d | %.1f ms",
                    restY, cubeY, bodyCount, ms)), false);
            boolean pass = Math.abs(restY - 0.5) < 0.05 && Math.abs(cubeY - 0.5) < 0.05 && bodyCount == 2;
            source.sendSuccess(() -> Component.literal(
                    pass ? "§a✔ 物理链路正常（Rust + Rapier f64 + JNI + Java）"
                         : "§e⚠ 数值偏离预期，请检查"), false);
            return pass ? 1 : 0;
        } finally {
            com.mss.polymech.physics.NativePhysics.worldDestroy(world);
        }
    }

    /**
     * /polymech space chunk add|remove|list：太空维度的区块强加载 ——
     * 原版 /forceload 内部硬编码 ±3e7 判定（changeForceLoad 里直接比较），
     * 深空坐标会被 "That position is out of this world!" 拒绝。
     * 这里直接调用 {@link ServerLevel#setChunkForced}，让空间站/着陆点能常驻加载。
     */
    private static int spaceForceload(CommandSourceStack source, double x, double z, boolean add) {
        ServerLevel space = source.getServer().getLevel(PlanetDimensions.SPACE);
        if (space == null) {
            source.sendFailure(Component.literal("太空维度不存在"));
            return 0;
        }
        int cx = net.minecraft.util.Mth.floor(x) >> 4;
        int cz = net.minecraft.util.Mth.floor(z) >> 4;
        boolean changed = space.setChunkForced(cx, cz, add);
        source.sendSuccess(() -> Component.literal(String.format(
                "%s区块 [%d, %d]（方块 %.0f, %.0f）%s",
                add ? "强加载 " : "取消强加载 ", cx, cz, x, z,
                changed ? "成功" : "（状态未变化）")), true);
        return changed ? 1 : 0;
    }

    /** /polymech space chunk list：列出太空维度当前强加载的区块。 */
    private static int spaceForceloadList(CommandSourceStack source) {
        ServerLevel space = source.getServer().getLevel(PlanetDimensions.SPACE);
        if (space == null) {
            source.sendFailure(Component.literal("太空维度不存在"));
            return 0;
        }
        var forced = space.getForcedChunks();
        source.sendSuccess(() -> Component.literal("太空维度强加载区块数: " + forced.size()), false);
        int shown = 0;
        for (long packed : forced) {
            if (shown++ >= 20) {
                source.sendSuccess(() -> Component.literal("...（仅显示前 20 个）"), false);
                break;
            }
            final int fx = net.minecraft.world.level.ChunkPos.getX(packed);
            final int fz = net.minecraft.world.level.ChunkPos.getZ(packed);
            source.sendSuccess(() -> Component.literal(String.format("  [%d, %d] → 方块 (%d, %d)", fx, fz, fx << 4, fz << 4)), false);
        }
        return 1;
    }

    /**
     * /polymech space far <x> <z>：深空坐标压力测试 —— 把玩家丢进太空维度的
     * 任意坐标（可超过原版 ±3000 万），验证实体/边界/方块访问在大坐标下稳定。
     */
    private static int spaceFar(CommandSourceStack source, double x, double z) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel space = player.server.getLevel(PlanetDimensions.SPACE);
        if (space == null) {
            source.sendFailure(Component.literal("太空维度不存在"));
            return 0;
        }
        float yaw = player.getYRot(), pitch = player.getXRot();
        double y = net.minecraft.util.Mth.clamp(player.getY(),
                space.getMinBuildHeight() + 16.0, space.getMaxBuildHeight() - 16.0);
        if (player.level() == space) {
            player.teleportTo(x, y, z);
        } else {
            DimensionTransition transition = new DimensionTransition(
                    space, new Vec3(x, y, z), Vec3.ZERO, yaw, pitch, DimensionTransition.DO_NOTHING);
            player.changeDimension(transition);
        }
        com.mss.polymech.space.SpacePlayerData.get(player).initFromVanilla(yaw, pitch);
        source.sendSuccess(() -> Component.literal(String.format(
                "已到达太空维度 (%.0f, %.1f, %.0f)", x, y, z)), false);
        return 1;
    }

    // ==================== /polymech rock ====================

    private static int showRock(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos pos = player.blockPosition();

        ModRocks.RockType predicted = ModRocks.rockTypeAt(pos.getX(), pos.getZ(), pos.getY(), level.getSeed(), level.getBiome(pos));
        source.sendSuccess(() -> Component.translatable("command.poly_mech.rock.predicted",
                predicted.block().get().getName()).withStyle(ChatFormatting.AQUA), false);

        // 向下找第一个"岩石家族"方块（原版石/深层石/模组岩种/本模组矿石）——
        // 跳过草方块、泥土、沙等覆盖层，否则报告的永远是脚下的草而不是岩层
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos firstSolid = null;
        for (int dy = 0; dy >= -64; dy--) {
            cursor.set(pos.getX(), pos.getY() + dy, pos.getZ());
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            if (firstSolid == null) {
                firstSolid = cursor.immutable();
            }
            if (isRockFamily(state.getBlock())) {
                final BlockPos hit = cursor.immutable();
                source.sendSuccess(() -> Component.translatable("command.poly_mech.rock.actual",
                        state.getBlock().getName(), coordsComponent(hit)), false);
                return 1;
            }
        }
        // 64格内没有岩石：退而报告第一个实体方块；连实体方块都没有则提示
        if (firstSolid != null) {
            final BlockPos hit = firstSolid;
            final BlockState fallback = level.getBlockState(hit);
            source.sendSuccess(() -> Component.translatable("command.poly_mech.rock.actual",
                    fallback.getBlock().getName(), coordsComponent(hit)), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.poly_mech.rock.none")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ==================== /polymech veins ====================

    private static int showVeins(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.poly_mech.veins.header")
                .withStyle(ChatFormatting.GOLD), false);
        for (ModVeins.VeinDefinition vein : ModVeins.getDefinitions()) {
            String hosts = vein.allowedRocks().isEmpty() ? "*" : String.join(" ", vein.allowedRocks());
            source.sendSuccess(() -> Component.translatable("command.poly_mech.veins.entry",
                    Component.translatable("command.poly_mech.vein." + vein.id()),
                    vein.rarity(), vein.minY(), vein.maxY(), vein.size(),
                    Component.literal(String.format("%.2f", vein.density())),
                    hosts), false);
            String between = vein.between() == null ? "-" : vein.between();
            String sporadic = vein.sporadic() == null ? "-" : vein.sporadic();
            source.sendSuccess(() -> Component.translatable("command.poly_mech.veins.shape",
                    ModVeins.shapeOf(vein.id()).name())
                    .withStyle(ChatFormatting.YELLOW), false);
            source.sendSuccess(() -> Component.translatable("command.poly_mech.veins.composition",
                    vein.primary(), vein.secondary(), between, sporadic)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ==================== /polymech scan ====================

    private static int scan(CommandSourceStack source, int radius) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();
        Map<Block, String> oreMap = oreToMineral();

        Map<String, Integer> counts = new HashMap<>();
        Map<String, BlockPos> nearest = new HashMap<>();
        Map<String, Long> nearestDist = new HashMap<>();
        int skippedColumns = 0;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                    skippedColumns++;
                    continue;
                }
                for (int y = SCAN_MAX_Y; y >= SCAN_MIN_Y; y--) {
                    cursor.set(x, y, z);
                    String mineral = oreMap.get(level.getBlockState(cursor).getBlock());
                    if (mineral == null) continue;
                    counts.merge(mineral, 1, Integer::sum);
                    long dist = squaredDistance(dx, y - center.getY(), dz);
                    if (dist < nearestDist.getOrDefault(mineral, Long.MAX_VALUE)) {
                        nearestDist.put(mineral, dist);
                        nearest.put(mineral, cursor.immutable());
                    }
                }
            }
        }

        if (counts.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.poly_mech.scan.none")
                    .withStyle(ChatFormatting.RED), false);
        } else {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            for (var entry : counts.entrySet()) {
                String mineral = entry.getKey();
                source.sendSuccess(() -> Component.translatable("command.poly_mech.scan.result",
                        mineral, entry.getValue(), coordsComponent(nearest.get(mineral))), false);
            }
            source.sendSuccess(() -> Component.translatable("command.poly_mech.scan.total", total)
                    .withStyle(ChatFormatting.GREEN), false);
        }
        if (skippedColumns > 0) {
            final int skipped = skippedColumns;
            source.sendSuccess(() -> Component.translatable("command.poly_mech.scan.unloaded", skipped)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ==================== /polymech find ====================

    private static int find(CommandSourceStack source, String mineral, int radius) throws CommandSyntaxException {
        if (!ModMinerals.hasMineral(mineral)) {
            String valid = String.join(", ", ModMinerals.getDefinitions().stream()
                    .map(ModMinerals.MineralDefinition::mineral).toList());
            source.sendFailure(Component.translatable("command.poly_mech.find.invalid", mineral, valid));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();
        Map<Block, String> oreMap = oreToMineral();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 环形外扩：按切比雪夫距离逐圈搜索，记录全局3D距离最近的矿。
        // 圈层r内任何方块的3D距离都≥r，因此一旦当前最近矿比整圈更近即可终止
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (int ring = 0; ring <= radius; ring++) {
            if (best != null && (long) ring * ring > bestDist) break;

            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                        continue;
                    }
                    for (int y = SCAN_MAX_Y; y >= SCAN_MIN_Y; y--) {
                        cursor.set(x, y, z);
                        if (!mineral.equals(oreMap.get(level.getBlockState(cursor).getBlock()))) continue;
                        long dist = squaredDistance(dx, y - center.getY(), dz);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }

        if (best != null) {
            final BlockPos found = best;
            final int distance = (int) Math.round(Math.sqrt(bestDist));
            source.sendSuccess(() -> Component.translatable("command.poly_mech.find.found",
                    mineral, distance, coordsComponent(found))
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendFailure(Component.translatable("command.poly_mech.find.none", mineral, radius));
        return 0;
    }

    // ==================== /polymech expose ====================

    /*
     * 以玩家为中心的立方体透视坑：清除±半径立方范围内全部地形方块，
     * 只保留矿石（要看的目标）与基岩，流体（水/岩浆）一并清除。
     * 按柱遍历：先做区块加载检查，再沿Y轴按立方体上下界扫描。
     */
    private static int expose(CommandSourceStack source, int radius) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();
        Map<Block, String> oreMap = oreToMineral();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int yBottom = Math.max(center.getY() - radius, minY);
        int yTop = Math.min(center.getY() + radius, maxY);
        int removed = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            int x = center.getX() + dx;
            for (int dz = -radius; dz <= radius; dz++) {
                int z = center.getZ() + dz;
                if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                    continue;
                }
                for (int y = yBottom; y <= yTop; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    // 只保留矿石（要看的目标）与基岩；水/岩浆一并清除
                    if (oreMap.containsKey(state.getBlock()) || state.is(Blocks.BEDROCK)) continue;
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    removed++;
                }
            }
        }

        final int count = removed;
        source.sendSuccess(() -> Component.translatable("command.poly_mech.expose.done",
                count, radius).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    // ==================== 辅助 ====================

    /**
     * 岩石家族判定表：原版石头类（石头/深层石/凝灰岩/花岗岩/闪长岩/安山岩）
     * + 全部模组岩种 + 本模组矿石。供/polymech rock穿透覆盖层定位真正岩层。
     */
    private static Set<Block> rockFamilyBlocks() {
        Set<Block> blocks = rockFamily;
        if (blocks == null) {
            blocks = new HashSet<>();
            blocks.add(Blocks.STONE);
            blocks.add(Blocks.DEEPSLATE);
            blocks.add(Blocks.TUFF);
            blocks.add(Blocks.GRANITE);
            blocks.add(Blocks.DIORITE);
            blocks.add(Blocks.ANDESITE);
            for (ModRocks.RockType rock : ModRocks.ROCK_TYPES) {
                blocks.add(rock.block().get());
            }
            blocks.addAll(oreToMineral().keySet());
            rockFamily = blocks;
        }
        return blocks;
    }

    private static boolean isRockFamily(Block block) {
        return rockFamilyBlocks().contains(block);
    }

    /** 矿物名补全 */
    private static CompletableFuture<Suggestions> suggestMinerals(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                ModMinerals.getDefinitions().stream().map(ModMinerals.MineralDefinition::mineral), builder);
    }

    /** 延迟构建矿石方块→矿物名映射（注册表冻结后才可用） */
    private static Map<Block, String> oreToMineral() {
        if (oreToMineral == null) {
            Map<Block, String> map = new IdentityHashMap<>();
            // 全部岩种变体（石头/深板岩/21种群峦岩种）都映射回矿物名
            for (var entry : ModBlocks.MINERAL_ORES.entrySet()) {
                for (var oreBlock : entry.getValue().all()) {
                    map.put(oreBlock.get(), entry.getKey());
                }
            }
            oreToMineral = map;
        }
        return oreToMineral;
    }

    /** 坐标文本组件：绿色显示，点击把/tp命令填入输入框 */
    private static Component coordsComponent(BlockPos pos) {
        String tp = "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp)));
    }

    private static long squaredDistance(int dx, int dy, int dz) {
        return (long) dx * dx + (long) dy * dy + (long) dz * dz;
    }
}
