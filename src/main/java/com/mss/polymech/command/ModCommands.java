package com.mss.polymech.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mss.polymech.block.ModBlocks;
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
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
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
