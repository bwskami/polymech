package com.mss.polymech.mps.space.network;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 可靠创建发送器 —— <b>与
 * {@code org.deep_space_studio.space.network.ReliableCreateSender} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把"创建/同步"类数据包发出去，并<b>一直重发到客户端回执为止</b>。
 *
 * <h2>为什么必须"可靠"（照 space 0.1.3）</h2>
 * 这些包描述的是客户端的<b>世界状态</b>（太空世界、天体、地表维度参数）。
 * 丢一个包的后果不是"少一帧"，而是客户端的宇宙与服务器<b>结构性不一致</b>：
 * 天体少一个 → 渲染缺一颗行星、着陆点算不出来；地表维度参数丢了 →
 * 地表⇄太空坐标换算全错。普通包丢了大不了下一 tick 再来，
 * 这类包<b>没有"下一次"</b>（只在新玩家登录/换维度时发一次），所以必须自己保证送达。
 *
 * <h2>三个参数的设计意图</h2>
 * <ul>
 *   <li><b>每 40 tick（2 秒）重发一次</b>：给客户端足够的处理与回执时间，
 *       又不至于把重发本身变成刷屏。用 {@code gameTime/tickCount} 而不是真实时间，
 *       因为服务器可以暂停（单机 Esc），真实时间会误判"超时"。</li>
 *   <li><b>普通包 5 次、关键包 10 次</b>：10 次 ≈ 20 秒，对网络抖动足够宽容。
 *       {@code sendCritical} 还带 {@code kickOnFail} —— 同步不完整时宁可踢掉玩家，
 *       也不要把一个状态错乱的客户端留在世界里（它看到的行星位置是错的，
 *       而且会继续基于错状态算落点）。</li>
 *   <li><b>{@code IdentityHashMap} 按玩家身份而非 {@code equals} 分表</b>：
 *       {@code ServerPlayer} 的 {@code equals} 不可靠（重连会换实例），
 *       用身份表才能保证"这份待确认表就是这个连接实例的"。</li>
 * </ul>
 *
 * <h2>与本项目既有重发机制的关系（重要，别搞混）</h2>
 * 项目里已有一套重发：{@code PhysicsBodyTracker} 的 {@code PENDING_ACKS}
 * （见 {@code docs/mps-clone-plan.md} §12）。两者<b>不是重复</b>：
 * <ul>
 *   <li>{@code PENDING_ACKS} 是<b>物理体专用</b>的：键是 {@code long} 体 id，
 *       值里存的是体快照，重发要走物理体的创建逻辑；</li>
 *   <li>本类是<b>载荷无关</b>的：键是自增 {@code int}，值是
 *       {@code IntFunction<CustomPacketPayload>} 造出来的原始包，
 *       谁都能用（kelvin 的天体通道就是第一个使用者）。</li>
 * </ul>
 * 所以 §12 那条"二选一"约束针对的是<b>物理体创建握手</b>（我们保留项目自己那套），
 * 天体同步则只有这一条通路，不存在两套并跑。
 */
public class ReliableCreateSender {

    private static final int MAX_ATTEMPTS = 5;
    private static final int CRITICAL_MAX_ATTEMPTS = 10;
    /** 重发间隔（tick）——2 秒。 */
    private static final int RESEND_INTERVAL_TICKS = 40;
    private static final Map<ServerPlayer, Map<Integer, Pending>> PENDING = new IdentityHashMap<>();
    private static int nextId = 0;

    /** 发一个普通创建包：最多重发 {@link #MAX_ATTEMPTS} 次，失败只放弃、不踢人。 */
    public static synchronized void send(ServerPlayer player, IntFunction<CustomPacketPayload> factory) {
        sendInternal(player, factory, MAX_ATTEMPTS, false);
    }

    /** 发一个关键创建包：重发更多次，仍失败则断开玩家（状态错乱的客户端比掉线更糟）。 */
    public static synchronized void sendCritical(ServerPlayer player, IntFunction<CustomPacketPayload> factory) {
        sendInternal(player, factory, CRITICAL_MAX_ATTEMPTS, true);
    }

    private static synchronized void sendInternal(ServerPlayer player, IntFunction<CustomPacketPayload> factory,
                                                  int maxAttempts, boolean kickOnFail) {
        int id = ++nextId;
        // id 先给工厂：包体自己把 id 编进去，客户端回执时原样带回
        CustomPacketPayload payload = factory.apply(id);
        PENDING.computeIfAbsent(player, k -> new HashMap<>())
                .put(id, new Pending(payload, 1, player.serverLevel().getGameTime(), maxAttempts, kickOnFail));
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 客户端回执：把该 id 从待确认表里摘掉（不存在则忽略 —— 迟到的回执是正常的）。 */
    public static synchronized void ack(ServerPlayer player, int id) {
        Map<Integer, Pending> map = PENDING.get(player);
        if (map != null) {
            map.remove(id);
            if (map.isEmpty()) {
                PENDING.remove(player);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(Post event) {
        long now = event.getServer().getTickCount();
        PENDING.entrySet().removeIf(playerEntry -> {
            Map<Integer, Pending> map = playerEntry.getValue();
            map.entrySet().removeIf(entry -> {
                Pending pending = entry.getValue();
                if (now - pending.lastSentTick() < RESEND_INTERVAL_TICKS) {
                    return false;
                } else if (pending.attempts() >= pending.maxAttempts()) {
                    if (pending.kickOnFail()) {
                        playerEntry.getKey().connection.disconnect(Component.literal("太空数据同步失败"));
                    }
                    return true;
                } else {
                    PacketDistributor.sendToPlayer(playerEntry.getKey(), pending.payload());
                    map.put(entry.getKey(), new Pending(pending.payload(), pending.attempts() + 1, now,
                            pending.maxAttempts(), pending.kickOnFail()));
                    return false;
                }
            });
            return map.isEmpty();
        });
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            synchronized (ReliableCreateSender.class) {
                PENDING.remove(serverPlayer);
            }
        }
    }

    private record Pending(CustomPacketPayload payload, int attempts, long lastSentTick,
                           int maxAttempts, boolean kickOnFail) {
    }
}
