package com.mss.polymech.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.mps.physical.helper.PhysicalRaycast;
import com.mss.polymech.mps.physical.manger.ProjectionManager;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import com.mss.polymech.space.SpaceAimOrigin;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>太空里放方块 = 造/并物理体</b>（用户 2026-09-27 的需求，第二版）。
 *
 * <h2>为什么必须这样（用户给的理由，也是硬约束）</h2>
 * <b>原版方块到了 ~3×10⁷ 格之后就放不出来了</b>：{@code BlockPos} 的 X/Z 只有 26 位
 * （±33,554,431），超出会静默别名；我们另有 {@code LevelSpaceAccessMixin:59-76} 在深空把
 * {@code setBlock} 直接返回 false。缩放约定下木星及以外（7.8e7 格）就在线外 ——
 * 所以"在外行星那边盖东西"只能靠**物理体**。
 *
 * <h2>用户拍板的规则</h2>
 * <blockquote>"只有在物理体旁边和人旁边的空地才能放"</blockquote>
 * ⇒ 前置判据见 {@link SpaceBuildRules#decide}：目标格不得被占、不得与玩家身体相交。
 * <b>目标格只能来自真实命中</b>（见下条）：被命中那一格沿命中面走一格。
 *
 * <h2>⚠️ 2026-10-06：删掉"准星前方 3 格"兜底（用户报"很像基岩版的放置逻辑"）</h2>
 * 原先打空时兜底到"眼睛 + 视线 × 3 格"。后果：
 * 对着<b>空气</b>右键也能放 ⇒ 能在半空"点空气一路搭路"；瞄<b>方块边缘</b>时射线打空 ⇒
 * 方块落到 3 格外（"往方块外延一格"）。这也正是用户最初那句"<b>有时</b>方块不放我瞄准的那个面"
 * 里的"有时"：命中体时走客户端包里那条路（正确），打空时走兜底（3 格外）。
 * space 0.3.0 的 {@code ProjectionManager.handleInteraction} 是
 * {@code if (hit != null && ...) { ... useBlock(...) } return false;} —— <b>打空什么都不做，没有兜底</b>。
 *
 * <h2>两条路（哪条被触发取决于客户端自定义射线有没有命中体）</h2>
 * ① 客户端 {@code PhysicsBodyInteractionClient} 自定义射线命中体 ⇒ 发 {@code PhysicsBodyEditPacket.use}
 *    并 cancel ⇒ 服务端 {@code PhysicsBodyInteraction.useOrPlace}（<b>本类的处理器不会被触发</b>）；
 * ② 客户端没命中（对着空处 / 真实方块）⇒ 走原版"用物品"包 ⇒ 服务端 {@code ServerPlayerGameMode} 触发
 *    {@link PlayerInteractEvent.RightClickItem} ⇒ 本类。
 * （本节旧文写的是"客户端射线必然 miss、不会两条路各触发一次"，那是 2026-10-06 之前的情况。）
 *
 * <h2>⚠️ 第一版的教训（别走回去）</h2>
 * 第一版自己拿 {@code new ServerPhysicalBody(...)} + {@code ProjectionManager.setBlock} +
 * {@code uploadAllChunks} 拼了一条路，**绕过了 {@link PhysicsBodyTracker}**：
 * 体的方块网格（客户端就靠它渲染）没被填、体素碰撞体没被建 ⇒ 实机现象正是用户报的
 * "放出来不显示、也无法对这个物理体再放方块"。现在改为调用项目已有原语
 * {@link PhysicsBodyTracker#placeBlockAt}，它一次做完：并进邻体或新建刚体、建碰撞体、
 * 写存档、广播客户端、落地皮（{@code ProjectionManager.writeBlocks}）。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SpaceBlockPlacement {

    /** 射线够得着多远（格）。 */
    private static final double REACH = 6.0;
    /** 从命中点往法线反方向缩这一点点，保证取到的是"被命中那一格"（与 castTerrain 同招）。 */
    private static final double INSIDE_EPSILON = 1.0e-3;

    private static final Map<UUID, Integer> LAST_PLACE_TICK = new ConcurrentHashMap<>();

    private SpaceBlockPlacement() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // ---------- 守门 ----------
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!PlanetDimensions.SPACE.equals(level.dimension())) {
            return;                                  // 只在太空维度：地表有真实方块空间
        }
        if (ProjectionManager.PROJECTION_WORLD.equals(level.dimension().location())) {
            return;                                  // 不在投影维度里套娃
        }
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof BlockItem blockItem) || player.isSpectator()) {
            return;
        }
        int now = player.tickCount;
        Integer last = LAST_PLACE_TICK.get(player.getUUID());
        if (last != null && now - last < SpaceBuildRules.COOLDOWN_TICKS) {
            return;                                  // 冷却：防连点刷体
        }

        // ---------- 目标格：**只能来自真实命中**（世界坐标；局部坐标全交给 PhysicsBodyTracker）----------
        // ⚠️ 起点必须是**相机锚点**，不是 {@code player.getEyePosition()}（2026-10-06 修）：
        //    太空第一人称相机离眼睛实测 1.313~1.430 格（主世界 0.000），
        //    用眼睛算出来的目标格会整体**平行错开一格多**。算式与客户端相机同源，
        //    见 SpacePlayerData.cameraAnchor 与 SpaceAimOrigin。
        //    服务端读不到相机，只能拿同步来的 facing/left 重算同一份算式（残留 ≤2 tick 朝向滞后）。
        Vec3 eye = SpaceAimOrigin.of(player);
        Vec3 look = player.getLookAngle();
        // 照 space：射线打在**体自己**身上。原来打在 MPS 克隆栈（ServerPhysicalWorld）上，
        // 而那条栈恒空 ⇒ 永远 null。
        PhysicsBodyTracker.BodyVoxelHit hit = PhysicsBodyTracker.raycastBody(level, eye, look, REACH);

        // ⚠️⚠️ **打空就不放**（2026-10-06 修，用户报"很像基岩版的放置逻辑"）：
        //    这里原先有一条"准星前方 3 格"的兜底。它的后果：
        //      · 对着**空气**右键也能放 ⇒ 能在半空一路"点空气搭路"；
        //      · 瞄**方块边缘**时射线打空 ⇒ 兜底把方块放到 3 格外 ⇒ "往方块外延一格"。
        //    这也正是用户最初那句"**有时**方块不放我瞄准的那个面"里的"有时"：
        //    命中体时走客户端包里那条路（格 = 命中格沿命中面走一格，正确），
        //    打空时走兜底（3 格外）——同一次右键两种落点。
        //    space 0.3.0 的 ProjectionManager.handleInteraction 逐字是：
        //        PhysicalRaycast.Hit hit = PhysicalRaycast.cast(...);
        //        if (hit != null && hit.physicalBody().getUuid().equals(physicalBodyId)) { ... useBlock(...) }
        //        return false;                 // ← 打空 = 什么都不做，**没有兜底**
        //    其 useBlock 里 placementPos = clickedPos.relative(hit.getDirection()) —— 与下面同构。
        if (hit == null) {
            Polymech.LOGGER.info("[太空建造] 判据 起点来源={} 起点={} 眼-起点距={} 命中来源=无 ⇒ 不放置（无兜底）",
                    SpaceAimOrigin.isCameraAnchored(player, 1.0F) ? "相机锚点" : "眼睛(退回)",
                    eye, eye.distanceTo(player.getEyePosition()));
            return;                     // 不 cancel：让原版自己去处理"对着真实方块 / 对着空处"的情形
        }

        // 命中体 ⇒ 取"被命中那一格"沿**世界方向**的面相邻格（= 准星真正指着的那个面）
        Direction worldFace = hit.worldFace();
        Vec3 wl = hit.worldLocation();
        BlockPos hitCell = BlockPos.containing(
                wl.x - worldFace.getStepX() * INSIDE_EPSILON,
                wl.y - worldFace.getStepY() * INSIDE_EPSILON,
                wl.z - worldFace.getStepZ() * INSIDE_EPSILON);
        BlockPos target = hitCell.relative(worldFace);

        // 判据：这次放置用的**起点**、**命中面**与**目标格**（以及起点离眼睛多远）。
        // 起点来源=false 表示锚点算不出来、已退回眼睛 —— 那就等于修复前的行为，必须能看出来。
        boolean anchored = SpaceAimOrigin.isCameraAnchored(player, 1.0F);
        Polymech.LOGGER.info("[太空建造] 判据 起点来源={} 起点={} 眼-起点距={} 命中来源=体 命中体={} 命中面={} 目标={}",
                anchored ? "相机锚点" : "眼睛(退回)", eye, eye.distanceTo(player.getEyePosition()),
                hit.bodyId(), worldFace, target);

        // ---------- 前置判据（纯函数，离线有判据表）----------
        BlockState state = blockItem.getBlock().defaultBlockState();
        boolean occupied = !level.getBlockState(target).isAir();
        boolean intersectsPlayer = player.getBoundingBox().inflate(0.05).intersects(new AABB(target));
        boolean adjacent = PhysicsBodyTracker.hasAdjacentBody(level, target);
        SpaceBuildRules.Decision decision = SpaceBuildRules.decide(occupied, intersectsPlayer, adjacent,
                PhysicsBodyTracker.bodyCount());
        if (decision != SpaceBuildRules.Decision.PLACE) {
            tell(player, SpaceBuildRules.message(decision), ChatFormatting.YELLOW);
            // 拒绝也要吃掉这次右键：否则原版会去走它自己的放置（在太空里要么放不出、要么写出一个孤儿方块）
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        // ★ 先把"吃掉这次右键"落地（契约），再做副作用（提示 / 冷却）—— 顺序就是这条教训本身。
        //
        //   2026-10-05 从 run/logs 查到：成功提示里那句
        //       Long.toHexString(bodyId).substring(0, 8)
        //   在体 id 是小数时必炸 —— 原生层分配的 id 是 3、4 这种小整数，
        //   Long.toHexString(3) == "3"（长度 1），substring(0, 8) 直接抛
        //   StringIndexOutOfBoundsException: Range [0, 8) out of bounds for length 1。
        //   取证：18:44 / 18:45 / 19:10 三次单块放置，每次 "物理体 N 由单块创建" 后面 4ms
        //   都跟着这条异常（NeoForge 的 EventBus 把它记成 "Exception caught during firing event"）。
        //
        //   它的杀伤面比"提示没出来"大得多，因为它发生在**建体成功之后**：
        //     ① 成功提示永远不出现（用户以为没放上，于是反复右键）；
        //     ② 冷却不记（可无限连点，一次点击一个体）；
        //     ③ 最要命：下面那两行 event.setCanceled 被跳过 ⇒ 原版放置路径继续跑。
        //   所以修法有两半：把取消提到副作用**之前**（装饰不许带走契约），
        //   并把 id 改成定宽十六进制（%08x 对 3 也给 "00000003"，不再需要 substring）。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        // ---------- 唯一实现入口：项目已有原语（并体 or 建体，含碰撞体/广播/存档/地皮）----------
        long bodyId = PhysicsBodyTracker.placeBlockAt(level, target, Block.getId(state));
        if (bodyId <= 0) {
            tell(player, "放置失败（原生层不可用 / 该格被占用 / 超出上限）", ChatFormatting.RED);
            return;
        }
        consume(player, stack);
        String how = adjacent ? "并入物理体" : "新建物理体";
        tell(player, String.format(java.util.Locale.ROOT, "%s %s：(%d, %d, %d) %s",
                how, String.format(java.util.Locale.ROOT, "%08x", bodyId),
                target.getX(), target.getY(), target.getZ(), state.getBlock().getName().getString()),
                ChatFormatting.GREEN);
        Polymech.LOGGER.info("[太空建造] {} 在 {} {} body={} 于 ({}, {}, {}) state={}",
                player.getGameProfile().getName(), level.dimension().location(), how, bodyId,
                target.getX(), target.getY(), target.getZ(), Block.getId(state));
        LAST_PLACE_TICK.put(player.getUUID(), now);
    }

    /** 创造模式不消耗，生存消耗 1 个。 */
    private static void consume(ServerPlayer player, ItemStack stack) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    private static void tell(ServerPlayer player, String msg, ChatFormatting color) {
        player.displayClientMessage(Component.literal(msg).withStyle(color), true);
    }

    /** 玩家退出时清理冷却表。 */
    public static void forget(UUID playerId) {
        LAST_PLACE_TICK.remove(playerId);
    }
}
