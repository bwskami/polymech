package com.mss.polymech.physics;

import com.mss.polymech.Polymech;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>音效搬运</b>：把<b>投影维度</b>里发出的声音搬到物理体所在的世界。
 *
 * <h2>为什么必须有这一层（用户 2026-10-06 需求："部分互动原本应该有的音效没用"）</h2>
 * 物理体的方块活在隐藏的投影维度里，破坏/放置/使用（箱子、按钮、拉杆、门、熔炉…）
 * 全部在<b>那边</b>跑原版逻辑 —— 于是它们的 {@code level.playSound(...)} 也发生在那边。
 * 而 {@code ServerLevel.playSeededSound} 是按 {@code this.dimension()} 广播的，
 * 投影维度里<b>一个玩家都没有</b> ⇒ 音全都发给了空气。
 * 更糟的是我们的客户端把左键/右键都吞掉转发给服务端了（见 {@code PhysicsBodyInteractionClient}），
 * <b>没有原版那份本地预测</b>来补声音 —— 表现就是"点了，但什么声都没有"。
 *
 * <h2>为什么用 {@link PlayLevelSoundEvent} 而不是逐个交互补 playSound</h2>
 * 逐个补（放置补一声、开箱补一声）永远补不全：方块自己的交互音在原版方块代码里、
 * 机器/红石/流体的随机音在 tick 里、第三方模组的方块更是没法枚举。
 * {@code PlayLevelSoundEvent.AtPosition} 是 <b>Level#playSound 的唯一咽喉</b>
 * （{@code ServerLevel.playSeededSound} 头上那句 NeoForge 钩子），在这里拦一次 = 全部覆盖。
 * 属于"补原生/补机制，不要在上层逐点打补丁"。
 *
 * <h2>归属与坐标</h2>
 * 投影坐标 →（槽位 → 物理体）→ 刚体姿态 → 世界坐标，全部走
 * {@link ProjectionManager#mapSoundPoint}，与掉落物搬运同一套判据（{@code nearestBodyFor}）、
 * 同一条映射（{@code RayBox.toWorldPoint}）。
 *
 * <h2>已知边界（诚实记录）</h2>
 * <ul>
 *   <li>{@link PlayLevelSoundEvent.AtEntity} 只在<b>实体本身在投影维度里</b>时能被认出来
 *       （事件只带 {@code entity.level()}）。方块代码几乎都用"按坐标播"的重载，
 *       所以这条已覆盖方块交互；"投影里播、锚点是世界里的玩家"这种组合没有钩子可辨，
 *       本项目当前也没有这条调用路径。</li>
 *   <li>粒子的另一条路 {@code ServerLevel.sendParticles} 不在此层（本次需求是音效）；
 *       常见的破坏/交互粒子走的是 {@code levelEvent}，那条由
 *       {@code ProjectionLevelEventMixin} 负责。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ProjectionSoundRelocation {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Sound");

    private ProjectionSoundRelocation() {
    }

    /** 投影维度里按坐标播的声音 → 搬到物理体所在世界。 */
    @SubscribeEvent
    public static void onSoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (!ProjectionManager.isProjectionLevel(event.getLevel())) {
            return; // 普通世界：原版照常（这条判据在非投影存档里恒 false，零开销）
        }
        // 先取消再搬：留在投影里 0 个听众，取消与放行在原地等价，但能被计数看见
        event.setCanceled(true);
        if (event.getSound() == null) {
            return;
        }
        ProjectionManager.SoundTarget target = ProjectionManager.mapSoundPoint(
                event.getPosition().x, event.getPosition().y, event.getPosition().z);
        if (target == null) {
            ProjectionManager.countOrphanSound();
            LOGGER.debug("[音效搬运] 声音 {} 投影 {} 归属不到物理体 → 静音",
                    event.getSound().unwrapKey().map(k -> k.location().toString()).orElse("?"),
                    event.getPosition());
            return;
        }
        // except 传 null：服务端的"跳过发起者"依赖客户端本地预测补那一份，而客户端碰不到投影维度
        target.level().playSound(null, target.x(), target.y(), target.z(),
                event.getSound(), event.getSource(), event.getNewVolume(), event.getNewPitch());
        ProjectionManager.countRelocatedSound();
        LOGGER.debug("[音效搬运] 声音 {} 投影 {} → 世界 {}（{}）",
                event.getSound().unwrapKey().map(k -> k.location().toString()).orElse("?"),
                event.getPosition(),
                String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", target.x(), target.y(), target.z()),
                target.level().dimension().location());
    }

    /** 投影维度里的实体播的声音（在投影里活动的实体才有这条）→ 同样搬到世界。 */
    @SubscribeEvent
    public static void onSoundAtEntity(PlayLevelSoundEvent.AtEntity event) {
        if (!ProjectionManager.isProjectionLevel(event.getLevel())) {
            return;
        }
        event.setCanceled(true);
        if (event.getSound() == null) {
            return;
        }
        var entity = event.getEntity();
        ProjectionManager.SoundTarget target =
                ProjectionManager.mapSoundPoint(entity.getX(), entity.getY(), entity.getZ());
        if (target == null) {
            ProjectionManager.countOrphanSound();
            return;
        }
        // 实体型包（ClientboundSoundEntityPacket）绑的是投影维度里的实体 id，客户端拿不到；
        // 搬运后退化成"按坐标播"——位置一致，只是不再跟着实体动（投影实体本来就不可见）。
        target.level().playSound(null, target.x(), target.y(), target.z(),
                event.getSound(), event.getSource(), event.getNewVolume(), event.getNewPitch());
        ProjectionManager.countRelocatedSound();
        LOGGER.debug("[音效搬运] 实体声音 {} 投影实体 {} → 世界 ({}, {}, {})",
                event.getSound().unwrapKey().map(k -> k.location().toString()).orElse("?"),
                entity.getId(), target.x(), target.y(), target.z());
    }
}
