package com.mss.polymech.techtree;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 科技树中的一个节点（研究科技 / 机器 / 解锁项）。
 * <p>
 * 节点只声明自身信息与前置（prerequisites），连线与布局由 {@link TechTree} 在运行时推导，
 * 因此“新增一个节点 + 一行前置声明”即可动态拓展整棵树，无需硬编码整张图或像素坐标。
 * </p>
 */
public final class TechNode {

    private final String id;
    private final Component title;
    private final Supplier<ItemStack> icon;
    private final int tier;
    private final String category;
    private final List<String> prerequisites;
    @Nullable
    private final String machineId;
    private final List<Component> description;
    private final List<Component> steps;

    private TechNode(Builder b) {
        this.id = b.id;
        this.title = b.title != null ? b.title : Component.literal(b.id);
        this.icon = b.icon != null ? b.icon : () -> ItemStack.EMPTY;
        this.tier = b.tier;
        this.category = b.category != null ? b.category : "default";
        this.prerequisites = List.copyOf(b.prerequisites);
        this.machineId = b.machineId;
        this.description = List.copyOf(b.description);
        this.steps = List.copyOf(b.steps);
    }

    public String id() {
        return id;
    }

    public Component title() {
        return title;
    }

    public ItemStack icon() {
        return icon.get();
    }

    public int tier() {
        return tier;
    }

    public String category() {
        return category;
    }

    public List<String> prerequisites() {
        return prerequisites;
    }

    @Nullable
    public String machineId() {
        return machineId;
    }

    public List<Component> description() {
        return description;
    }

    public List<Component> steps() {
        return steps;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private Component title;
        private Supplier<ItemStack> icon;
        private int tier = 0;
        private String category;
        private final List<String> prerequisites = new ArrayList<>();
        private String machineId;
        private final List<Component> description = new ArrayList<>();
        private final List<Component> steps = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        public Builder icon(Supplier<ItemStack> icon) {
            this.icon = icon;
            return this;
        }

        public Builder tier(int tier) {
            this.tier = tier;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder prerequisite(String... ids) {
            for (var i : ids) prerequisites.add(i);
            return this;
        }

        public Builder machineId(String machineId) {
            this.machineId = machineId;
            return this;
        }

        public Builder description(Component... lines) {
            description.addAll(List.of(lines));
            return this;
        }

        public Builder steps(Component... lines) {
            steps.addAll(List.of(lines));
            return this;
        }

        /**
         * 构建并注册到 {@link TechTree}（声明式：构建即注册）。
         */
        public TechNode build() {
            TechNode node = new TechNode(this);
            TechTree.register(node);
            return node;
        }
    }
}
