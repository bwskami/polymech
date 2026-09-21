package com.mss.polymech.mps.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.network.packet.PhysicalBodyInteractionPacket;
import com.mss.polymech.mps.physical.helper.PhysicalRaycast;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 物理体交互的客户端半边 —— <b>与
 * {@code org.polaris2023.mps.client.PhysicalBodyInteractionClient} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 把玩家的"攻击/使用/选取方块"翻译成打在<b>物理体方块</b>上的动作，
 * 并画出瞄准框与挖掘裂纹。
 *
 * <h2>为什么整条交互必须走自己的射线（照 space 0.1.3）</h2>
 * 物理体上的方块**已经不在世界里了**（搬进了投影维度），世界里那一片是空气。
 * 原版射线打过去什么也碰不到，所以攻击/使用都<b>不会触发</b>。
 * 于是这里主动：
 * <ol>
 *   <li>{@link PhysicalRaycast#cast} 打物理体（局部方块网格）；</li>
 *   <li>命中后<b>取消原版事件</b>（{@code event.setCanceled(true)}）——
 *       否则原版会在"空气"上再走一遍逻辑（挥手、放方块到空气里）；</li>
 *   <li>把 {@code (体 uuid, 动作字节, 手)} 发给服务端，由
 *       {@code ProjectionManager#handleInteraction} 在投影维度里跑<b>原版</b>破坏/放置逻辑。</li>
 * </ol>
 * <b>权威在服务端</b>：客户端只负责"打中了什么"和"画出来"，进度与掉落都由服务端算。
 *
 * <h2>三个动作字节</h2>
 * {@code 0=ATTACK}、{@code 2=USE}、{@code 1=ATTACK_STOP}（松手）。
 * 松手那个是必需的：原版没有对应包，服务端只能靠它主动清掉裂纹与挖掘进度。
 * 所以 {@link #resetMiningFeedback(boolean)} 在"射线打空/切物品/开界面/松开按键"时
 * 都会补发一次 ATTACK_STOP。
 *
 * <h2>挖掘裂纹为什么要用服务端的进度</h2>
 * 生存模式按硬度挖，进度由服务端算（它才知道工具、附魔、方块硬度）。
 * 客户端本地推一份只用于"手感"，两者不一致时以服务端的
 * {@link #syncMiningProgress} 为准 —— 所以裂纹显示的是 {@code serverMiningProgress}
 * 而不是本地累加值。创造模式则直接瞬破（{@code destroyDelay} 节流防连点）。
 *
 * <p><b>克隆差异（已记录）</b>：MPS 里有 4 处判断 {@code SpaceModItems.PHYSICAL_SELECTION_WAND}
 * （space 模组的选体魔杖玩法，含 {@code handleSelection} 与
 * {@code PhysicalSelectionActionPacket}）。该物品属于 <b>space 模组的玩法内容</b>，
 * 不在物理机制的关键路径上，本项目也没有它 —— 因此<b>该分支未移植</b>，
 * 其余逻辑逐行同形。详见 {@code docs/mps-clone-plan.md} §20。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class PhysicalBodyInteractionClient {

    /** 上一次"攻击"发生在哪个游戏刻——同一刻只发一次，避免连点刷包。 */
    private static long lastAttackTick = Long.MIN_VALUE;
    private static UUID miningBodyId;
    private static BlockPos miningBlockPos;
    private static int miningTicks;
    /** 服务端来的真实挖掘进度（裂纹按它画，见类注释）。 */
    private static float serverMiningProgress;

    private PhysicalBodyInteractionClient() {
    }

    /** 攻击/使用按键：命中物理体就吞掉原版事件并转发服务端。 */
    @SubscribeEvent
    public static void onInteraction(InteractionKeyMappingTriggered event) {
        if (event.isPickBlock()) {
            handlePickBlock(event);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        PhysicalRaycast.Hit hit = findHit();
        if (hit == null) {
            return;
        }
        byte action = (byte) (event.isAttack() ? 0 : 2);
        if (event.isAttack()) {
            updateMiningFeedback(minecraft, hit);
        }

        PacketDistributor.sendToServer(
                new PhysicalBodyInteractionPacket(hit.physicalBody().getUuid(), action, event.getHand()));
        if (event.isAttack() && minecraft.level != null) {
            lastAttackTick = minecraft.level.getGameTime();
        }
        // 必须取消：否则原版会在空气上再跑一遍（挥手、往空气里放方块）
        event.setCanceled(true);
    }

    /** 选取方块：从物理体的局部方块取物品（Ctrl 时连方块实体 NBT 一起取）。 */
    private static void handlePickBlock(InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        PhysicalRaycast.Hit hit = findHit();
        if (hit == null) {
            return;
        }
        BlockState state = hit.physicalBody().getBlockState(hit.localBlockPos().getX(),
                hit.localBlockPos().getY(), hit.localBlockPos().getZ());
        if (state.isAir()) {
            return;
        }
        BlockHitResult blockHit = new BlockHitResult(
                new Vec3(hit.worldLocation().x, hit.worldLocation().y, hit.worldLocation().z),
                hit.localFace(), hit.localBlockPos(), false);
        ItemStack stack = state.getCloneItemStack(blockHit, minecraft.level, hit.localBlockPos(), minecraft.player);
        if (stack.isEmpty()) {
            return;
        }

        BlockEntity blockEntity = null;
        if (Screen.hasControlDown() && state.hasBlockEntity()) {
            blockEntity = hit.physicalBody().getBlockEntity(hit.localBlockPos());
        }

        Inventory inventory = minecraft.player.getInventory();
        if (blockEntity != null) {
            addCustomNbtData(stack, blockEntity, minecraft.level.registryAccess());
        }

        int slot = inventory.findSlotMatchingItem(stack);
        if (minecraft.player.getAbilities().instabuild) {
            inventory.setPickedItem(stack);
            minecraft.gameMode.handleCreativeModeItemAdd(
                    minecraft.player.getItemInHand(InteractionHand.MAIN_HAND), 36 + inventory.selected);
        } else if (slot != -1) {
            if (Inventory.isHotbarSlot(slot)) {
                inventory.selected = slot;
            } else {
                minecraft.gameMode.handlePickItem(slot);
            }
        }
        event.setCanceled(true);
    }

    /** 把方块实体的自定义 NBT 与组件写进被选取的物品（Ctrl 选取时用）。 */
    private static void addCustomNbtData(ItemStack stack, BlockEntity blockEntity, RegistryAccess registryAccess) {
        CompoundTag tag = blockEntity.saveCustomAndMetadata(registryAccess);
        blockEntity.removeComponentsFromTag(tag);
        BlockItem.setBlockEntityData(stack, blockEntity.getType(), tag);
        stack.applyComponents(blockEntity.collectComponents());
    }

    /** 客户端每 tick：按住攻击键持续挖（同一刻只发一次）。 */
    @SubscribeEvent
    public static void onClientTick(Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null && minecraft.screen == null
                && minecraft.options.keyAttack.isDown()) {
            long tick = minecraft.level.getGameTime();
            if (tick != lastAttackTick) {
                PhysicalRaycast.Hit hit = findHit();
                if (hit == null) {
                    resetMiningFeedback(true);
                } else {
                    updateMiningFeedback(minecraft, hit);
                    PacketDistributor.sendToServer(new PhysicalBodyInteractionPacket(
                            hit.physicalBody().getUuid(), (byte) 0, InteractionHand.MAIN_HAND));
                    lastAttackTick = tick;
                }
            }
        } else {
            resetMiningFeedback(true);
        }
    }

    /** 渲染瞄准框 + 服务端挖掘进度对应的裂纹（半透明阶段之后画）。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        PhysicalRaycast.Hit hit = findHit();
        if (hit == null) {
            return;
        }
        PhysicalBody physicalBody = hit.physicalBody();
        BlockPos localPos = hit.localBlockPos();
        BlockState state = physicalBody.getBlockState(localPos.getX(), localPos.getY(), localPos.getZ());
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }

        Vec3 camera = event.getCamera().getPosition();
        Vector3d relativeBodyPosition = new Vector3d(physicalBody.getPos()).sub(camera.x, camera.y, camera.z);
        PoseStack poseStack = event.getPoseStack();
        BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(relativeBodyPosition.x, relativeBodyPosition.y, relativeBodyPosition.z);
        poseStack.mulPose(physicalBody.getRotation().get(new Quaternionf()));

        for (AABB box : shape.toAabbs()) {
            LevelRenderer.renderLineBox(poseStack, lines,
                    box.move(localPos.getX(), localPos.getY(), localPos.getZ()).inflate(0.002),
                    0.0F, 0.0F, 0.0F, 0.4F);
        }

        renderBreakingProgress(minecraft, poseStack, hit, state);
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** 服务端来的挖掘进度（只认当前正在挖的那一格，见类注释）。 */
    public static void syncMiningProgress(UUID bodyId, BlockPos localPos, float progress) {
        if (bodyId.equals(miningBodyId) && localPos.equals(miningBlockPos)) {
            serverMiningProgress = progress;
        }
    }

    /** 按服务端进度画裂纹；进度 ≤ 0 或不是当前格就不画。 */
    private static void renderBreakingProgress(Minecraft minecraft, PoseStack poseStack,
                                               PhysicalRaycast.Hit hit, BlockState state) {
        if (serverMiningProgress <= 0.0F
                || !hit.physicalBody().getUuid().equals(miningBodyId)
                || !hit.localBlockPos().equals(miningBlockPos)) {
            return;
        }
        // 原版裂纹共 10 级（0..9），进度 0..1 映射上去
        int stage = Math.min(9, (int) (serverMiningProgress * 10.0F));
        BlockPos localPos = hit.localBlockPos();
        Vector3d center = blockCenter(hit.physicalBody(), localPos);
        BlockPos worldPos = BlockPos.containing(center.x, center.y, center.z);
        poseStack.pushPose();
        poseStack.translate(localPos.getX(), localPos.getY(), localPos.getZ());
        VertexConsumer breaking = new SheetedDecalTextureGenerator(
                minecraft.renderBuffers().crumblingBufferSource()
                        .getBuffer(ModelBakery.DESTROY_TYPES.get(stage)),
                poseStack.last(), 1.0F);
        minecraft.getBlockRenderer().renderBreakingTexture(state, worldPos, minecraft.level,
                poseStack, breaking, ModelData.EMPTY);
        poseStack.popPose();
        minecraft.renderBuffers().crumblingBufferSource().endBatch();
    }

    /** 切换了挖掘目标就重置本地反馈（并把服务端进度清零）。 */
    private static void updateMiningFeedback(Minecraft minecraft, PhysicalRaycast.Hit hit) {
        UUID bodyId = hit.physicalBody().getUuid();
        BlockPos localPos = hit.localBlockPos();
        if (!bodyId.equals(miningBodyId) || !localPos.equals(miningBlockPos)) {
            resetMiningFeedback(true);
            miningBodyId = bodyId;
            miningBlockPos = localPos.immutable();
            miningTicks = 0;
            serverMiningProgress = 0.0F;
        }
        // 每 4 tick 敲一次音效（原版节奏）
        if (miningTicks % 4 == 0) {
            playHitSound(minecraft, hit);
        }
        miningTicks++;
    }

    private static void playHitSound(Minecraft minecraft, PhysicalRaycast.Hit hit) {
        BlockPos localPos = hit.localBlockPos();
        BlockState state = hit.physicalBody().getBlockState(localPos.getX(), localPos.getY(), localPos.getZ());
        Vector3d soundPosition = blockCenter(hit.physicalBody(), localPos);
        BlockPos worldPos = BlockPos.containing(soundPosition.x, soundPosition.y, soundPosition.z);
        SoundType soundType = state.getSoundType(minecraft.level, worldPos, minecraft.player);
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getHitSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 8.0F, soundType.getPitch() * 0.5F,
                SoundInstance.createUnseededRandom(),
                soundPosition.x, soundPosition.y, soundPosition.z));
    }

    /** 局部格中心 → 世界坐标（体旋转 + 平移）。 */
    private static Vector3d blockCenter(PhysicalBody physicalBody, BlockPos localPos) {
        Vector3d center = new Vector3d(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5);
        physicalBody.getRotation().transform(center);
        return center.add(physicalBody.getPos());
    }

    /**
     * 清掉本地挖掘反馈；{@code notifyServer} 为真时补发
     * {@code ATTACK_STOP}（动作 1）—— 原版没有"松手"包，服务端靠它清裂纹与进度。
     */
    private static void resetMiningFeedback(boolean notifyServer) {
        if (notifyServer && miningBodyId != null && Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new PhysicalBodyInteractionPacket(
                    miningBodyId, (byte) 1, InteractionHand.MAIN_HAND));
        }
        miningBodyId = null;
        miningBlockPos = null;
        miningTicks = 0;
        serverMiningProgress = 0.0F;
    }

    /** 从相机沿视线打物理体；不在物理世界所在维度时返回 null。 */
    private static PhysicalRaycast.Hit findHit() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        if (physicalWorld == null || !physicalWorld.getLevel().equals(minecraft.level.dimension().location())) {
            return null;
        }
        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 view = minecraft.player.getViewVector(1.0F);
        return PhysicalRaycast.cast(physicalWorld, new Vector3d(eye.x, eye.y, eye.z),
                new Vector3d(view.x, view.y, view.z), minecraft.player.blockInteractionRange());
    }
}
