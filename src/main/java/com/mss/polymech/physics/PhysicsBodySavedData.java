package com.mss.polymech.physics;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物理体存档数据。
 *
 * <p>背景：{@code grab} 会把方块<b>从世界里移除</b>，之后结构只存在于 Rapier 刚体里。
 * 如果不持久化，退出存档后"方块没了、刚体也没了" —— 结构永久丢失。
 * 因此这里把每个物理体的方块快照与变换写进世界存档（存在主世界的数据存储里，
 * 每条记录自带维度 id）。</p>
 *
 * <p>数据格式（NBT）：{@code {NextId:int, Bodies:[{Id, Dim, Pos[3], Rot[4], Blocks:[...]}]}}，
 * 其中方块用 {@code [dx, dy, dz, stateId]} 四元组扁平存储。</p>
 */
public class PhysicsBodySavedData extends SavedData {

    public static final String NAME = "polymech_physics_bodies";

    public static final SavedData.Factory<PhysicsBodySavedData> FACTORY =
            new SavedData.Factory<>(PhysicsBodySavedData::new, PhysicsBodySavedData::load);

    /**
     * 单个物理体的持久化记录。
     *
     * @param slot 投影维度的地皮槽位；{@code -1} 表示未分配（投影维度当时不可用）。
     *             <b>必须持久化</b>：槽位决定方块实体活在哪块地皮上，
     *             重启后重新从 0 分配会串位、把旧地皮连同里面的机器状态一起变成孤儿。
     */
    public record Entry(long id, ResourceKey<Level> dimension,
                        double x, double y, double z,
                        float qx, float qy, float qz, float qw,
                        int slot,
                        int[] blocks) {
    }

    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private long nextId = 1L;

    public static PhysicsBodySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 分配一个物理体 id，<b>优先复用已销毁体留下的空号</b>。
     *
     * <p>不能只用自增计数器：id 直接决定投影维度里的地皮槽位，而每块地皮要
     * <b>强制加载 8×8 个区块</b>。号只涨不补 = 地皮只增不减，
     * 撸掉一个体就永久漏掉一块地皮（以及那 64 个区块的加载开销）。</p>
     */
    public long allocateId() {
        long id = 1L;
        while (entries.containsKey(id)) {
            id++;
        }
        if (id >= nextId) {
            nextId = id + 1;
        }
        setDirty();
        return id;
    }

    public void put(Entry entry) {
        entries.put(entry.id(), entry);
        if (entry.id() >= nextId) {
            nextId = entry.id() + 1;
        }
        setDirty();
    }

    public void remove(long id) {
        if (entries.remove(id) != null) {
            setDirty();
        }
    }

    /** 只更新变换（每 N tick 调用一次，避免每 tick 都标脏）。 */
    public void updateTransform(long id, double x, double y, double z,
                                float qx, float qy, float qz, float qw) {
        Entry old = entries.get(id);
        if (old == null) {
            return;
        }
        entries.put(id, new Entry(id, old.dimension(), x, y, z, qx, qy, qz, qw, old.slot(), old.blocks()));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextId", nextId);
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag t = new CompoundTag();
            t.putLong("Id", entry.id());
            t.putString("Dim", entry.dimension().location().toString());
            t.putDouble("X", entry.x());
            t.putDouble("Y", entry.y());
            t.putDouble("Z", entry.z());
            t.putFloat("QX", entry.qx());
            t.putFloat("QY", entry.qy());
            t.putFloat("QZ", entry.qz());
            t.putFloat("QW", entry.qw());
            t.putInt("Slot", entry.slot());
            t.putIntArray("Blocks", entry.blocks());
            list.add(t);
        }
        tag.put("Bodies", list);
        return tag;
    }

    public static PhysicsBodySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PhysicsBodySavedData data = new PhysicsBodySavedData();
        data.nextId = Math.max(1L, tag.getLong("NextId"));
        ListTag list = tag.getList("Bodies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("Dim"));
            if (dim == null) {
                continue;
            }
            int[] blocks = t.getIntArray("Blocks");
            if (blocks.length == 0 || blocks.length % 4 != 0) {
                continue;
            }
            long id = t.getLong("Id");
            // 老存档没有 Slot 字段：给 -1，让 restore 重新分配并回写一份方块
            int slot = t.contains("Slot") ? t.getInt("Slot") : -1;
            data.entries.put(id, new Entry(id, ResourceKey.create(Registries.DIMENSION, dim),
                    t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"),
                    t.getFloat("QX"), t.getFloat("QY"), t.getFloat("QZ"), t.getFloat("QW"),
                    slot, blocks));
            if (id >= data.nextId) {
                data.nextId = id + 1;
            }
        }
        return data;
    }
}
