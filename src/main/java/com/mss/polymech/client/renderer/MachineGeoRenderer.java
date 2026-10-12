package com.mss.polymech.client.renderer;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.model.MachineGeoModel;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.common.MachineGeometry;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 通用机器方块渲染器，所有机器共用。
 * <p>
 * 在构造时注入模型路径，无需为每台机器单独创建 Renderer 子类。
 * </p>
 *
 * <h3>方块实体到底受哪几道门槛管制（已逐行核对 1.21.1 NeoForge 源码）</h3>
 * <ol>
 *   <li><b>距离门槛</b>（{@code BlockEntityRenderDispatcher#render} 第 69-78 行）：
 * <pre>
 *   if (renderer.shouldRender(be, camera.getPosition())) { tryRender(...); }   // 不满足就完全不画
 * </pre>
 *       {@code shouldRender} 的默认实现是
 *       {@code Vec3.atCenterOf(be.getBlockPos()).closerThan(cameraPos, getViewDistance())}，
 *       而 {@code getViewDistance()} 原版默认 <b>64 格</b>。
 *       <b>这就是「人离大机器远了模型就消失」的直接原因。</b>
 *       修复：覆写 {@link #getViewDistance()}（见 {@link MachineGeometry#MODEL_VIEW_DISTANCE}），
 *       照原版 {@code BeaconRenderer} 的做法（它返回 256）。</li>
 *   <li><b>视锥门槛</b>（{@code LevelRenderer} 全局方块实体循环 →
 *       {@code ClientHooks#isBlockEntityRendererVisible}）：
 * <pre>
 *   renderer != null &amp;&amp; frustum.isVisible(renderer.getRenderBoundingBox(be))
 * </pre>
 *       修复：覆写 {@link #getRenderBoundingBox}，按<strong>模型</strong>而非方块结构给足体积。</li>
 *   <li>{@link #shouldRenderOffScreen} 返回 true 只影响 {@code SectionCompiler#handleBlockEntity}
 *       的分流：实体进入 globalBlockEntities，因而不受 16³ 区块段可见性裁剪；
 *       它<b>不</b>绕过上面两道门槛。</li>
 * </ol>
 *
 * <h3>两个坑都踩过，记录以免重犯</h3>
 * <ul>
 *   <li>旧实现是 {@code new AABB(pos).inflate(4)}，只罩主方块周边 ±4/5 格，
 *       而模型最大上探 9.38 格（{@code primitive_blast_furnace}）、
 *       最大横向 6.11 格（{@code beehive_coke_oven}）—— 表现为离得近时
 *       侧面/顶面看过去模型也会整台消失。</li>
 *   <li>曾误判 {@code shouldRender(be, camera)} / {@code getViewDistance()} 是"无调用点的死代码"，
 *       原因是只在 {@code LevelRenderer.java} 内部 grep，漏了
 *       {@code BlockEntityRenderDispatcher} 这个真正调用点，于是修了包围盒却没修距离上限。
 *       教训：找调用点必须全库检索，不能只看一个文件。</li>
 * </ul>
 */
public class MachineGeoRenderer<T extends BlockEntity & GeoBlockEntity> extends GeoBlockRenderer<T> {

    /** 覆盖半径，与离线校验 {@code verifyMachineRenderBox} 共用同一份取值，避免两处漂移 */
    private static final int MODEL_COVERAGE_RADIUS = MachineGeometry.MODEL_COVERAGE_RADIUS;

    /** 视距上限，同上共用 */
    private static final int MODEL_VIEW_DISTANCE = MachineGeometry.MODEL_VIEW_DISTANCE;

    public MachineGeoRenderer(BlockEntityRendererProvider.Context context,
                               ResourceLocation modelPath, ResourceLocation texturePath, ResourceLocation animationPath) {
        super(new MachineGeoModel<>(modelPath, texturePath, animationPath));
        // 每次构建渲染器时打一行：既确认新构建已生效，也把两个裁剪口径留在日志里便于对账
        Polymech.LOGGER.info("[机器渲染] {} 视距={}格 包围半径={}格（原版默认视距 64 格会让模型走远即消失）",
                modelPath.getPath(), MODEL_VIEW_DISTANCE, MODEL_COVERAGE_RADIUS);
    }

    /**
     * 视距上限 —— 覆写原版默认的 64 格。
     * <p>
     * 这是「走远了模型消失」的直接修复点：{@code BlockEntityRenderDispatcher#render}
     * 会先问 {@code shouldRender(be, cameraPos)}，其默认实现就是与本方法返回值比较距离，
     * 不满足时<b>连渲染都不会调用</b>。原版信标为同样目的覆写为 256。
     * </p>
     */
    @Override
    public int getViewDistance() {
        return MODEL_VIEW_DISTANCE;
    }

    /**
     * 视锥裁剪用的包围盒 —— 必须罩住<b>模型</b>，而不是只罩方块结构。
     * <p>
     * 模型按视觉尺寸建模，远大于结构（最高 9.38 格 vs 结构 1 格高），
     * 只罩主方块会让「模型还在画面里、盒子已出视锥」时整台被剔除。
     * </p>
     */
    @Override
    public AABB getRenderBoundingBox(T blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        BlockState state = blockEntity.getBlockState();
        if (state.getBlock() instanceof BaseMachineBlock machineBlock) {
            return MachineGeometry.renderBounds(pos, state.getValue(BaseMachineBlock.FACING),
                    machineBlock.getSideOffsets(), machineBlock.getFillRegions(), MODEL_COVERAGE_RADIUS);
        }
        // 非多方块机器（理论上不会走到这里）
        return MachineGeometry.renderBounds(pos, net.minecraft.core.Direction.NORTH,
                new net.minecraft.core.Vec3i[0], null, MODEL_COVERAGE_RADIUS);
    }

    /**
     * 进 globalBlockEntities：绕过 16³ 区块段的可见性裁剪。
     * <p>注意它只影响分流，不绕过距离门槛与视锥门槛（见类注释）。</p>
     */
    @Override
    public boolean shouldRenderOffScreen(T blockEntity) {
        return true;
    }
}
