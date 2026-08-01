package com.mss.polymech.machine.common;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.item.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 大型机器统一注册中心——两阶段注册。
 *
 * <h3>Phase 1（方块注册，在 ModBlocks 中）</h3>
 * <pre>{@code
 * public static final MachineRegistrar.MachineRegistration BEEHIVE_COKE_OVEN =
 *     MachineRegistrar.registerBlock(
 *         MachineConfig.builder("beehive_coke_oven")
 *             .sideOffsets(MachineConfig.crossOffsets())
 *             .blockProperties(machineProps())
 *             .blockEntityFactory(BeehiveCokeOvenBlockEntity::new)
 *             .build(),
 *         BeehiveCokeOvenItem::new);
 * }</pre>
 *
 * <h3>Phase 2（BE 注册 + 连线，在 ModBlockEntities 中）</h3>
 * <pre>{@code
 * // 主 BE 类型（带泛型，保持类型安全）
 * public static final Supplier<BlockEntityType<BeehiveCokeOvenBlockEntity>> BEEHIVE_COKE_OVEN =
 *     MachineRegistrar.registerMainBE("beehive_coke_oven",
 *         BeehiveCokeOvenBlockEntity::new,
 *         ModBlocks.BEEHIVE_COKE_OVEN.mainBlock());
 *
 * static {
 *     // 自动注册 side BE + side block + 连线
 *     MachineRegistrar.wireMachine(ModBlocks.BEEHIVE_COKE_OVEN, BEEHIVE_COKE_OVEN);
 * }
 * }</pre>
 */
public class MachineRegistrar {

    /**
     * Phase 1：注册主方块 + 物品 + MachineRegistry 条目。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static MachineRegistration registerBlock(
            MachineConfig config,
            BiFunction<Block, Item.Properties, ? extends BlockItem> itemFactory) {

        DeferredBlock<LargeMachineBlock> mainBlock =
                ModBlocks.BLOCKS.register(config.id(), () -> new LargeMachineBlock(config));

        ModItems.ITEMS.register(config.id(),
                () -> itemFactory.apply(mainBlock.get(), new Item.Properties()));

        MachineRegistry.MachineEntry entry = new MachineRegistry.MachineEntry(
                config.id(), mainBlock);
        MachineRegistry.register(entry);

        return new MachineRegistration(config, mainBlock, entry);
    }

    /**
     * Phase 2a：注册主方块实体类型（带泛型，保持类型安全）。
     *
     * @param id          机器 ID
     * @param factory     方块实体工厂（如 {@code FillingUnitBlockEntity::new}）
     * @param validBlock  有效方块引用
     * @return 带类型的方块实体类型供应器
     */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> Supplier<BlockEntityType<T>> registerMainBE(
            String id,
            BlockEntityType.BlockEntitySupplier<T> factory,
            DeferredBlock<? extends Block> validBlock) {
        return ModBlockEntities.BLOCK_ENTITIES.register(id, () ->
                BlockEntityType.Builder.of(factory, validBlock.get()).build(null));
    }

    /**
     * Phase 2b：注册侧面方块 (GenericSideBlockEntity)、侧面方块，并连线 MachineRegistry。
     * 必须在主 BE 注册之后调用。
     * <p>
     * 使用 lazy holder 模式打破侧面方块与侧面 BE 之间的循环依赖：
     * 侧面方块先注册（持有懒加载的 BE 类型供应器），侧面 BE 后注册（可引用已注册的侧面方块为有效方块）。
     * </p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void wireMachine(MachineRegistration registration,
                                   Supplier<? extends BlockEntityType<?>> mainBESupplier) {
        MachineConfig config = registration.config();
        DeferredBlock<LargeMachineBlock> mainBlock = registration.mainBlock();
        MachineRegistry.MachineEntry entry = registration.entry();

        // 侧面方块属性
        BlockBehaviour.Properties sideProps = BlockBehaviour.Properties.of()
                .strength(3.5F, 4.8F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .dynamicShape();

        // Lazy holder：打破侧面方块与侧面 BE 的循环依赖
        // 侧面方块构造时持有懒加载的 Supplier，在游戏中调用 newBlockEntity 时才真正取值
        var sideBEHolder = new Object() { Supplier<BlockEntityType<?>> supplier; };

        // 1. 先注册侧面方块（持有懒加载的 BE 类型供应器）
        DeferredBlock<LargeMachineSideBlock> sideBlock =
                ModBlocks.BLOCKS.register(config.id() + "_side",
                        () -> new LargeMachineSideBlock(sideProps, mainBlock,
                                () -> sideBEHolder.supplier.get()));

        // 2. 再注册侧面 BE（此时可引用已注册的侧面方块为有效方块）
        Supplier<BlockEntityType<?>> sideBESupplier = ModBlockEntities.BLOCK_ENTITIES.register(
                config.id() + "_side", () -> {
                    BlockEntityType<?>[] holder = new BlockEntityType<?>[1];
                    holder[0] = BlockEntityType.Builder.of(
                            (pos, state) -> new GenericSideBlockEntity(holder[0], pos, state),
                            mainBlock.get(),
                            sideBlock.get()  // 侧面方块已注册，可安全引用
                    ).build(null);
                    return holder[0];
                });

        // 3. 回填 lazy holder，完成循环引用
        sideBEHolder.supplier = sideBESupplier;

        // 4. 连线 MachineRegistry 和 MachineConfig
        entry.setSideBlock(sideBlock);
        entry.setBlockEntities(
                (Supplier) mainBESupplier,
                (Supplier) sideBESupplier);
        config.setMainBlock(mainBlock);
        config.setSideBlock(sideBlock);
    }

    /**
     * Phase 1 结果，保存引用供 Phase 2 使用。
     */
    public record MachineRegistration(
            MachineConfig config,
            DeferredBlock<LargeMachineBlock> mainBlock,
            MachineRegistry.MachineEntry entry
    ) {}
}
