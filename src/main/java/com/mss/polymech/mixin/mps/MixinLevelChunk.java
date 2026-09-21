package com.mss.polymech.mixin.mps;

import com.mss.polymech.mps.physical.manger.ProjectionManager;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块变化的脏标记 —— <b>与 {@code org.polaris2023.mps.mixin.MixinLevelChunk} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 在 {@code LevelChunk.setBlockState} 返回后按"这一格属于谁"分派脏标记：
 * <table border="1">
 *   <tr><th>所在维度</th><th>标记目标</th><th>为什么</th></tr>
 *   <tr><td>投影维度</td><td>{@code ProjectionManager.onBlockUpload(pos)}</td>
 *       <td>地皮上的改动要回写给客户端</td></tr>
 *   <tr><td>普通服务端维度</td><td>{@code PhysicalChunkManager.markDirty(pos)}</td>
 *       <td>地形兴趣点（"是否实心"变了）</td></tr>
 *   <tr><td>客户端维度</td><td>{@code ClientPhysicalWorld.onChunkBlockChanged}</td>
 *       <td>客户端地形碰撞体要重建</td></tr>
 * </table>
 *
 * <h2>为什么必须挂在这里，而不是靠显式调用</h2>
 * 本项目自己的破坏路径确实会显式调 {@code onBlockUpload}，但<b>只覆盖玩家破坏</b>。
 * 地皮上的方块还会被<b>别的代码</b>改：
 * 红石通断、活塞推拉、漏斗/机器自改结构、其它模组直接 {@code setBlockState}。
 * 这些改动若不被标脏，客户端就永远看不到 ——
 * 表现是"船上的灯在服务端亮了，客户端还是黑的"。
 * 挂在 {@code setBlockState} 的 <b>RETURN</b> 是唯一能全覆盖的位置。
 *
 * <h2>两次判断的理由</h2>
 * <ul>
 *   <li>{@code previous != null && previous != state}：没真变就跳过。
 *       {@code setBlockState} 相同状态会直接返回旧值，不筛就会把每次空写都当脏。</li>
 *   <li>普通维度还要 {@code previous.isAir() != state.isAir()}：
 *       只有"实心/空"翻转才影响地形兴趣点；换一种实心方块不影响。</li>
 * </ul>
 *
 * <p><b>与本项目既有 mixin 的关系</b>：项目已有 {@code LevelChunkPhysicsDirtyMixin} 与
 * {@code ProjectionChunkDirtyMixin}（都挂在 {@code LevelChunk}），标记的是项目自己那套
 * {@code physics/ProjectionManager} 与体跟踪。本 mixin 标的是<b>克隆层</b>的投影与物理世界
 * —— 两套并存期间各标各的（这是方案 A 的既定代价）。</p>
 *
 * <p><b>方法描述符写在注解里而不是只写方法名</b>：实测 1.21.1 的
 * {@code LevelChunk.setBlockState(BlockPos, BlockState, boolean)} <b>只有一个重载</b>
 * （{@code javap} 核对过），所以只写方法名当下也能跑。
 * 但 Mixin 的"只写方法名"语义是<b>匹配全部同名方法</b>，
 * 一旦将来原版加重载（历史上确实加过），处理器签名会对不上而注入失败
 * （本项目 {@code required: true} + {@code defaultRequire: 1} → 直接启动崩溃）。
 * 写全描述符把这个隐患钉死。</p>
 */
@Mixin(LevelChunk.class)
public class MixinLevelChunk {

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Z)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void polymech$trackProjectionBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                                    CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        LevelChunk self = (LevelChunk) (Object) this;
        if (previous == null || previous == state) {
            return;
        }
        if (self.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension().location().equals(ProjectionManager.PROJECTION_WORLD)) {
                // 地皮改动 → 攒脏，由 ProjectionManager.tick() 批量回写
                ProjectionManager.onBlockUpload(pos);
                return;
            }
            ServerPhysicalWorld physicalWorld = ServerPhysicalWorld.getPhysicalWorld(serverLevel);
            if (physicalWorld != null && previous.isAir() != state.isAir()) {
                physicalWorld.getChunkManager().markDirty(pos);
            }
        } else if (self.getLevel() instanceof ClientLevel && previous.isAir() != state.isAir()) {
            ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
            if (physicalWorld != null) {
                physicalWorld.onChunkBlockChanged(new ChunkPos(pos));
            }
        }
    }
}
