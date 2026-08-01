package com.mss.polymech.machine.common;

import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.SideBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 大型机器注册表，统一管理所有大型机器方块及其对应信息。
 * <p>
 * 该注册表在方块注册阶段收集主方块与侧面方块，在方块实体注册阶段补充
 * 方块实体类型，供能力注册、蓝图放置、客户端预览等系统统一访问。
 * </p>
 */
public class MachineRegistry {

    /**
     * 单个大型机器条目，保存注册所需的全部引用。
     */
    public static class MachineEntry {
        private final String id;
        private final Supplier<? extends BaseMachineBlock> mainBlock;
        @Nullable
        private Supplier<? extends SideBlock> sideBlock;
        @Nullable
        private Supplier<? extends BlockEntityType<?>> mainBlockEntity;
        @Nullable
        private Supplier<? extends BlockEntityType<?>> sideBlockEntity;

        /**
         * 创建机器条目（方块注册阶段调用，侧面方块和方块实体尚未注册）。
         */
        public MachineEntry(String id,
                            Supplier<? extends BaseMachineBlock> mainBlock) {
            this.id = id;
            this.mainBlock = mainBlock;
        }

        public String id() {
            return id;
        }

        public Supplier<? extends BaseMachineBlock> mainBlock() {
            return mainBlock;
        }

        @Nullable
        public Supplier<? extends SideBlock> sideBlock() {
            return sideBlock;
        }

        public void setSideBlock(Supplier<? extends SideBlock> sideBlock) {
            this.sideBlock = sideBlock;
        }

        @Nullable
        public Supplier<? extends BlockEntityType<?>> mainBlockEntity() {
            return mainBlockEntity;
        }

        @Nullable
        public Supplier<? extends BlockEntityType<?>> sideBlockEntity() {
            return sideBlockEntity;
        }

        public void setBlockEntities(Supplier<? extends BlockEntityType<?>> mainBlockEntity,
                                     Supplier<? extends BlockEntityType<?>> sideBlockEntity) {
            this.mainBlockEntity = mainBlockEntity;
            this.sideBlockEntity = sideBlockEntity;
        }
    }

    private static final Map<String, MachineEntry> ENTRIES = new LinkedHashMap<>();

    /**
     * 注册一个大型机器条目。
     *
     * @param entry 机器条目
     */
    public static void register(MachineEntry entry) {
        ENTRIES.put(entry.id(), entry);
    }

    /**
     * 根据机器ID获取对应的主方块实例。
     *
     * @param machineId 机器ID
     * @return 主方块，不存在则返回null
     */
    public static BaseMachineBlock getMachineBlock(String machineId) {
        MachineEntry entry = ENTRIES.get(machineId);
        return entry != null ? entry.mainBlock().get() : null;
    }

    /**
     * 获取所有已注册的大型机器ID。
     *
     * @return 不可修改的机器ID集合
     */
    public static Collection<String> getMachineIds() {
        return Collections.unmodifiableCollection(ENTRIES.keySet());
    }

    /**
     * 获取所有已注册的大型机器条目。
     *
     * @return 不可修改的条目集合
     */
    public static Collection<MachineEntry> getEntries() {
        return Collections.unmodifiableCollection(ENTRIES.values());
    }

    /**
     * 根据机器ID获取对应条目。
     *
     * @param id 机器ID
     * @return 条目，不存在则返回null
     */
    public static MachineEntry getEntry(String id) {
        return ENTRIES.get(id);
    }
}
