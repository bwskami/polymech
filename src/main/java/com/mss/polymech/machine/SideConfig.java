package com.mss.polymech.machine;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mekanism 风格面配置数据模型。
 * <p>
 * 管理方块 6 个面的 IO 方向配置，支持能源/物品/流体三种能力类型独立配置。
 * 每个面的配置值为 {@link SideIO} 枚举：NONE（无）、IN（输入）、OUT（输出）。
 * </p>
 * <p>
 * 与侧面代理（ItemProxy/FluidProxy）的关系：
 * 侧面代理定义"哪些槽位/储罐在哪个位置"，
 * 面配置定义"该面的 IO 方向"（玩家可覆盖）。
 * </p>
 */
public class SideConfig {

    /** 面 IO 方向枚举 */
    public enum SideIO {
        NONE,   // 不交互
        IN,     // 输入
        OUT;    // 输出

        /** 循环切换: NONE → IN → OUT → NONE */
        public SideIO next() {
            return switch (this) {
                case NONE -> IN;
                case IN -> OUT;
                case OUT -> NONE;
            };
        }

        /** 反向循环切换: OUT → IN → NONE → OUT（Mekanism DataType.getPrevious 语义） */
        public SideIO previous() {
            return switch (this) {
                case NONE -> OUT;
                case IN -> NONE;
                case OUT -> IN;
            };
        }
    }

    /** 能力类型 */
    public enum CapabilityType {
        ENERGY,
        ITEM,
        FLUID
    }

    private final EnumMap<Direction, SideIO> energyConfig = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, SideIO> itemConfig = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, SideIO> fluidConfig = new EnumMap<>(Direction.class);

    /** 各能力类型的自动输出开关（Mekanism ConfigInfo.isEjecting 语义，默认开启） */
    private final EnumMap<CapabilityType, Boolean> autoEject = new EnumMap<>(CapabilityType.class);

    /** 配置变更监听器（用于触发方块更新/能力刷新） */
    private Runnable changeListener;

    public SideConfig() {
        // 默认所有面 NONE
        for (Direction dir : Direction.values()) {
            energyConfig.put(dir, SideIO.NONE);
            itemConfig.put(dir, SideIO.NONE);
            fluidConfig.put(dir, SideIO.NONE);
        }
        // 默认所有类型自动输出开启
        for (CapabilityType type : CapabilityType.values()) {
            autoEject.put(type, true);
        }
    }

    // ==================== 访问器 ====================

    public SideIO getEnergyConfig(Direction dir) { return energyConfig.get(dir); }
    public SideIO getItemConfig(Direction dir) { return itemConfig.get(dir); }
    public SideIO getFluidConfig(Direction dir) { return fluidConfig.get(dir); }

    public void setEnergyConfig(Direction dir, SideIO value) {
        energyConfig.put(dir, value);
        fireChanged();
    }

    public void setItemConfig(Direction dir, SideIO value) {
        itemConfig.put(dir, value);
        fireChanged();
    }

    public void setFluidConfig(Direction dir, SideIO value) {
        fluidConfig.put(dir, value);
        fireChanged();
    }

    /** 获取指定能力类型的面配置 */
    public SideIO getConfig(CapabilityType type, Direction dir) {
        return switch (type) {
            case ENERGY -> getEnergyConfig(dir);
            case ITEM -> getItemConfig(dir);
            case FLUID -> getFluidConfig(dir);
        };
    }

    /** 设置指定能力类型的面配置 */
    public void setConfig(CapabilityType type, Direction dir, SideIO value) {
        switch (type) {
            case ENERGY -> setEnergyConfig(dir, value);
            case ITEM -> setItemConfig(dir, value);
            case FLUID -> setFluidConfig(dir, value);
        }
    }

    /** 循环切换指定面的配置 */
    public void cycleConfig(CapabilityType type, Direction dir) {
        SideIO current = getConfig(type, dir);
        setConfig(type, dir, current.next());
    }

    // ==================== 自动输出 ====================

    public boolean isAutoEject(CapabilityType type) {
        return autoEject.getOrDefault(type, true);
    }

    public void setAutoEject(CapabilityType type, boolean eject) {
        autoEject.put(type, eject);
        fireChanged();
    }

    /** 批量设置某类型所有面的 IO（Mekanism PacketBatchConfiguration 语义） */
    public void setAllConfig(CapabilityType type, SideIO value) {
        switch (type) {
            case ENERGY -> {
                for (Direction dir : Direction.values()) energyConfig.put(dir, value);
            }
            case ITEM -> {
                for (Direction dir : Direction.values()) itemConfig.put(dir, value);
            }
            case FLUID -> {
                for (Direction dir : Direction.values()) fluidConfig.put(dir, value);
            }
        }
        fireChanged();
    }

    /** 批量设置所有类型所有面的 IO（Shift 清空语义） */
    public void setAllConfigAllTypes(SideIO value) {
        for (Direction dir : Direction.values()) {
            energyConfig.put(dir, value);
            itemConfig.put(dir, value);
            fluidConfig.put(dir, value);
        }
        fireChanged();
    }

    /** 检查某面是否允许特定方向的能量交互 */
    public boolean canInputEnergy(Direction dir) { return energyConfig.get(dir) == SideIO.IN; }
    public boolean canOutputEnergy(Direction dir) { return energyConfig.get(dir) == SideIO.OUT; }

    public boolean canInputItem(Direction dir) { return itemConfig.get(dir) == SideIO.IN; }
    public boolean canOutputItem(Direction dir) { return itemConfig.get(dir) == SideIO.OUT; }

    public boolean canInputFluid(Direction dir) { return fluidConfig.get(dir) == SideIO.IN; }
    public boolean canOutputFluid(Direction dir) { return fluidConfig.get(dir) == SideIO.OUT; }

    // ==================== 变更监听 ====================

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void fireChanged() {
        if (changeListener != null) changeListener.run();
    }

    // ==================== NBT 序列化 ====================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        saveMap(tag, "Energy", energyConfig);
        saveMap(tag, "Item", itemConfig);
        saveMap(tag, "Fluid", fluidConfig);
        CompoundTag eject = new CompoundTag();
        for (CapabilityType type : CapabilityType.values()) {
            eject.putBoolean(type.name(), isAutoEject(type));
        }
        tag.put("AutoEject", eject);
        return tag;
    }

    public void load(CompoundTag tag) {
        loadMap(tag, "Energy", energyConfig);
        loadMap(tag, "Item", itemConfig);
        loadMap(tag, "Fluid", fluidConfig);
        if (tag.contains("AutoEject")) {
            CompoundTag eject = tag.getCompound("AutoEject");
            for (CapabilityType type : CapabilityType.values()) {
                if (eject.contains(type.name())) {
                    autoEject.put(type, eject.getBoolean(type.name()));
                }
            }
        }
    }

    private void saveMap(CompoundTag tag, String key, EnumMap<Direction, SideIO> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<Direction, SideIO> entry : map.entrySet()) {
            sub.putString(entry.getKey().getSerializedName(), entry.getValue().name());
        }
        tag.put(key, sub);
    }

    private void loadMap(CompoundTag tag, String key, EnumMap<Direction, SideIO> map) {
        if (!tag.contains(key)) return;
        CompoundTag sub = tag.getCompound(key);
        for (Direction dir : Direction.values()) {
            String name = dir.getSerializedName();
            if (sub.contains(name)) {
                try {
                    map.put(dir, SideIO.valueOf(sub.getString(name)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    // ==================== 复制 ====================

    public SideConfig copy() {
        SideConfig copy = new SideConfig();
        copy.energyConfig.putAll(this.energyConfig);
        copy.itemConfig.putAll(this.itemConfig);
        copy.fluidConfig.putAll(this.fluidConfig);
        copy.autoEject.putAll(this.autoEject);
        return copy;
    }
}
