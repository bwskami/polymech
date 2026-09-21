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
 * <p>数据格式（NBT）：{@code {NextId:int, Bodies:[{Id, Dim, Pos[3], Rot[4], Vel[3], Angvel[3],
 * Mem, Fil, Slot, Blocks:[...]}]}}，其中方块用 {@code [dx, dy, dz, stateId]} 四元组扁平存储。</p>
 *
 * <p><b>为什么连速度一起存</b>：物理体的位置每 40 tick 才写一次档，只存位置的话，
 * 重启后一艘正在巡航的船会"原地停住"（速度归零）。space 0.1.3 的
 * {@code ServerPhysicalBody.saveToTag} 同样存 linvel/angvel。</p>
 */
public class PhysicsBodySavedData extends SavedData {

    public static final String NAME = "polymech_physics_bodies";

    /** 碰撞组未设置：等于 Rapier 默认（membership/filter 全 1，与所有组交互）。 */
    public static final int GROUP_UNSET = -1;

    public static final SavedData.Factory<PhysicsBodySavedData> FACTORY =
            new SavedData.Factory<>(PhysicsBodySavedData::new, PhysicsBodySavedData::load);

    /**
     * 单个物理体的持久化记录。
     *
     * @param slot 投影维度的地皮槽位；{@code -1} 表示未分配（投影维度当时不可用）。
     *             <b>必须持久化</b>：槽位决定方块实体活在哪块地皮上，
     *             重启后重新从 0 分配会串位、把旧地皮连同里面的机器状态一起变成孤儿。
     * @param vx/vy/vz    线速度（m/s），重启后接着飞
     * @param avx/avy/avz 角速度（rad/s）
     * @param membership/filter 碰撞组；{@link #GROUP_UNSET} = 没设过（默认与所有组交互）
     */
    public record Entry(long id, ResourceKey<Level> dimension,
                        double x, double y, double z,
                        float qx, float qy, float qz, float qw,
                        double vx, double vy, double vz,
                        double avx, double avy, double avz,
                        int membership, int filter,
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

    /**
     * 只更新变换与速度（每 N tick 调用一次，避免每 tick 都标脏）。
     *
     * <p>速度一并写：上一版只写 pos/rot，重启后"飞着的船停在半空"。</p>
     */
    public void updateTransform(long id, double x, double y, double z,
                                float qx, float qy, float qz, float qw,
                                double vx, double vy, double vz,
                                double avx, double avy, double avz) {
        Entry old = entries.get(id);
        if (old == null) {
            return;
        }
        entries.put(id, new Entry(id, old.dimension(), x, y, z, qx, qy, qz, qw,
                vx, vy, vz, avx, avy, avz,
                old.membership(), old.filter(), old.slot(), old.blocks()));
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
            t.putDouble("VX", entry.vx());
            t.putDouble("VY", entry.vy());
            t.putDouble("VZ", entry.vz());
            t.putDouble("AVX", entry.avx());
            t.putDouble("AVY", entry.avy());
            t.putDouble("AVZ", entry.avz());
            t.putInt("Mem", entry.membership());
            t.putInt("Fil", entry.filter());
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
            // 老存档也没有速度与碰撞组：速度 0、组未设置，行为与升级前一致
            double vx = t.contains("VX") ? t.getDouble("VX") : 0.0;
            double vy = t.contains("VY") ? t.getDouble("VY") : 0.0;
            double vz = t.contains("VZ") ? t.getDouble("VZ") : 0.0;
            double avx = t.contains("AVX") ? t.getDouble("AVX") : 0.0;
            double avy = t.contains("AVY") ? t.getDouble("AVY") : 0.0;
            double avz = t.contains("AVZ") ? t.getDouble("AVZ") : 0.0;
            int membership = t.contains("Mem") ? t.getInt("Mem") : GROUP_UNSET;
            int filter = t.contains("Fil") ? t.getInt("Fil") : GROUP_UNSET;
            data.entries.put(id, new Entry(id, ResourceKey.create(Registries.DIMENSION, dim),
                    t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"),
                    t.getFloat("QX"), t.getFloat("QY"), t.getFloat("QZ"), t.getFloat("QW"),
                    vx, vy, vz, avx, avy, avz,
                    membership, filter, slot, blocks));
            if (id >= data.nextId) {
                data.nextId = id + 1;
            }
        }
        return data;
    }
}
