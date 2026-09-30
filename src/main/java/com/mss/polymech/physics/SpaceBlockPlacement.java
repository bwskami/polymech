package com.mss.polymech.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.mps.physical.helper.PhysicalRaycast;
import com.mss.polymech.mps.physical.manger.ProjectionManager;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
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
 * ⇒ 前置判据见 {@link SpaceBuildRules#decide}：
 * 目标格必须<b>面相邻于某个物理体</b>（那种情况是"并进去"），或者<b>贴着玩家自己</b>（起步第一块）；
 * 两者都不满足就拒绝 —— 挡掉"远处凭空冒出一块没人找得到的单块体"。
 *
 * <h2>为什么整件事可以纯服务端做</h2>
 * 物理体的方块活在 {@code poly_mech:projection_world}，**不在客户端的 level 里** ⇒ 客户端射线必然 miss
 * ⇒ 客户端发的是原版"用物品"包，服务端在 {@code ServerPlayerGameMode} 里触发
 * {@link PlayerInteractEvent.RightClickItem}（取证：全 jar 常量池扫描显示
 * {@code ServerPlayerGameMode} 调用 {@code CommonHooks.onItemRightClick}）。
 * 于是"对着物理体"与"对着空处"都落到同一个处理器里，不会出现两条路各触发一次。
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
        ServerPhysicalWorld world = ServerPhysicalWorld.getPhysicalWorld(level);
        if (world == null) {
            return;
        }
        int now = player.tickCount;
        Integer last = LAST_PLACE_TICK.get(player.getUUID());
        if (last != null && now - last < SpaceBuildRules.COOLDOWN_TICKS) {
            return;                                  // 冷却：防连点刷体
        }

        // ---------- 目标格：**世界坐标**（局部坐标全交给 PhysicsBodyTracker）----------
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        PhysicalRaycast.Hit hit = PhysicalRaycast.cast(world,
                new Vector3d(eye.x, eye.y, eye.z),
                new Vector3d(look.x, look.y, look.z), REACH);

        BlockPos target;
        if (hit != null && hit.physicalBody() != null) {
            // 命中体 ⇒ 取"被命中那一格"沿**世界方向**的面相邻格
            Direction worldFace = worldFace(hit);
            Vector3d wl = hit.worldLocation();          // 注意：是 joml 的 Vector3d，不是 MC 的 Vec3
            BlockPos hitCell = BlockPos.containing(
                    wl.x - worldFace.getStepX() * INSIDE_EPSILON,
                    wl.y - worldFace.getStepY() * INSIDE_EPSILON,
                    wl.z - worldFace.getStepZ() * INSIDE_EPSILON);
            target = hitCell.relative(worldFace);
        } else {
            target = BlockPos.containing(eye.x + look.x * SpaceBuildRules.PLACE_DISTANCE,
                    eye.y + look.y * SpaceBuildRules.PLACE_DISTANCE,
                    eye.z + look.z * SpaceBuildRules.PLACE_DISTANCE);
        }

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

        // ---------- 唯一实现入口：项目已有原语（并体 or 建体，含碰撞体/广播/存档/地皮）----------
        long bodyId = PhysicsBodyTracker.placeBlockAt(level, target, Block.getId(state));
        if (bodyId <= 0) {
            tell(player, "放置失败（原生层不可用 / 该格被占用 / 超出上限）", ChatFormatting.RED);
        } else {
            consume(player, stack);
            String how = adjacent ? "并入物理体" : "新建物理体";
            tell(player, String.format(java.util.Locale.ROOT, "%s %s：(%d, %d, %d) %s",
                    how, Long.toHexString(bodyId).substring(0, 8),
                    target.getX(), target.getY(), target.getZ(), state.getBlock().getName().getString()),
                    ChatFormatting.GREEN);
            Polymech.LOGGER.info("[太空建造] {} 在 {} {} body={} 于 ({}, {}, {}) state={}",
                    player.getGameProfile().getName(), level.dimension().location(), how, bodyId,
                    target.getX(), target.getY(), target.getZ(), Block.getId(state));
            LAST_PLACE_TICK.put(player.getUUID(), now);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 命中面的**世界方向**：物理体可能被转过（牵引枪），所以要把局部面按刚体旋转转过去。
     *
     * <p>不转的话，体一转，方块就会放到"体的另一侧"—— 这正是离线判据里"体转 90°"那组要防的错。
     * 拿不到刚体时退回局部面（≈未旋转的常见情况）。</p>
     */
    private static Direction worldFace(PhysicalRaycast.Hit hit) {
        Direction local = hit.localFace();
        if (local == null) {
            return Direction.UP;
        }
        PhysicalBody body = hit.physicalBody();
        if (body == null) {
            return local;
        }
        // 旋转数学在 SpaceBuildRules.rotateFace（纯函数）⇒ 离线判据能直接钉"体转 90°"那组
        int[] world = SpaceBuildRules.rotateFace(local.getStepX(), local.getStepY(), local.getStepZ(),
                body.getRotation());
        Direction dir = Direction.getNearest(world[0], world[1], world[2]);
        return dir == null ? local : dir;
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
