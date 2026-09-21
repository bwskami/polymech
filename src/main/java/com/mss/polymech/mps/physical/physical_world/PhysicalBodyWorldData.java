package com.mss.polymech.mps.physical.physical_world;

import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_body.ServerPhysicalBody;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 物理体的维度存档 —— <b>与
 * {@code org.polaris2023.mps.physical.physical_world.PhysicalBodyWorldData} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 把一个维度里所有服务端物理体存成 NBT，并在世界加载时重建它们。
 *
 * <h2>为什么"存档记录是唯一副本"（照 space 0.1.3）</h2>
 * 物理体上的方块<b>已经从世界里移除了</b>（搬进了投影维度，而地皮是<b>共享的临时空间</b>：
 * 槽位会被回收、重分配、{@code wipeSlot} 会清空）。
 * 所以真正可靠的那一份是<b>这里存下的体数据</b>，而不是地皮上的方块。
 * 重建流程因此是：读 NBT → 建体（沿用存档里的槽位号）→ 再把方块写回那块地皮。
 *
 * <h2>两个"看起来多余"但其实必要的地方</h2>
 * <ul>
 *   <li><b>{@code load} 里 {@code tag.getCompound("bodies").copy()}</b>：
 *       MC 传进来的 tag 随后会被回收/复用，不 copy 就会拿到一个之后被改写的引用。</li>
 *   <li><b>{@link #loadBodies} 按 {@code level} 字段过滤</b>：
 *       所有维度的体存在同一份 {@code physical_bodies} 里（因为 {@link #get} 是
 *       按维度取的，但键空间是共享的），不过滤就会把别的维度的体建到当前维度。</li>
 * </ul>
 *
 * <p>{@link #saveBodies} 只收 {@link ServerPhysicalBody} ——
 * 客户端镜像体不该进存档。整体<b>重建而不是增量合并</b>：
 * 先建一个空 CompoundTag 再逐个 put，这样"这一轮被删掉的体"不会残留。</p>
 */
public class PhysicalBodyWorldData extends SavedData {

    private CompoundTag bodies = new CompoundTag();

    public static final SavedData.Factory<PhysicalBodyWorldData> FACTORY =
            new SavedData.Factory<>(PhysicalBodyWorldData::new, PhysicalBodyWorldData::load);

    public static PhysicalBodyWorldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "physical_bodies");
    }

    public static PhysicalBodyWorldData load(CompoundTag tag, HolderLookup.Provider provider) {
        PhysicalBodyWorldData data = new PhysicalBodyWorldData();
        data.bodies = tag.getCompound("bodies").copy();
        return data;
    }

    /** 整体重建（见类注释：不能增量合并，否则已删的体会残留）。 */
    public void saveBodies(List<PhysicalBody> physicalBodies) {
        CompoundTag newBodies = new CompoundTag();
        for (PhysicalBody physicalBody : physicalBodies) {
            if (physicalBody instanceof ServerPhysicalBody serverPhysicalBody) {
                newBodies.put(serverPhysicalBody.getUuid().toString(), serverPhysicalBody.saveToTag());
            }
        }
        this.bodies = newBodies;
        this.setDirty();
    }

    /** 只重建属于这个维度的体（见类注释）。 */
    public void loadBodies(ServerPhysicalWorld physicalWorld) {
        for (String key : this.bodies.getAllKeys()) {
            CompoundTag tag = this.bodies.getCompound(key);
            if (physicalWorld.getLevel().toString().equals(tag.getString("level"))) {
                ServerPhysicalBody.loadFromTag(tag, physicalWorld);
            }
        }
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag compoundTag, @NotNull HolderLookup.Provider provider) {
        compoundTag.put("bodies", this.bodies);
        return compoundTag;
    }
}
