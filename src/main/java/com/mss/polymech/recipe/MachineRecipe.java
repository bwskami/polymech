package com.mss.polymech.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * 通用机器配方：物品/流体输入 → 物品/流体输出 + 耗时 + 每 tick 能耗。
 * <p>
 * 每台机器对应一个独立的 {@link RecipeType}（见 {@link ModRecipeTypes}），
 * 共用本配方类与序列化器。蒸汽等流体动力机器把蒸汽声明为流体输入即可。
 * </p>
 * <p>
 * 发电机模式（{@code generator=true}）：{@code powerPerTick} 表示每 tick 发电量，
 * 配方耗时为一次燃料周期的长度。
 * </p>
 */
public class MachineRecipe implements Recipe<MachineRecipe.MachineInput> {

    /** 流体输入定义 */
    public record FluidInput(Fluid fluid, int amount) {
        public static final Codec<FluidInput> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.FLUID.byNameCodec().fieldOf("fluid").forGetter(FluidInput::fluid),
                ExtraCodecs.POSITIVE_INT.fieldOf("amount").forGetter(FluidInput::amount)
        ).apply(instance, FluidInput::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, FluidInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.idMapper(BuiltInRegistries.FLUID), FluidInput::fluid,
                ByteBufCodecs.VAR_INT, FluidInput::amount,
                FluidInput::new);

        public boolean matches(FluidStack stack) {
            return stack.is(fluid);
        }
    }

    /**
     * 带概率的副产物输出（选矿伴生元素）。
     * <p>
     * 用于格雷式多金属矿物加工：方铅矿除产铅粉外还有概率产出银/硫等伴生元素。
     * {@code chance} 取值 0~1，表示该副产物每次加工实际产出的概率；
     * 1.0 表示必然产出（等价于普通输出）。
     * </p>
     *
     * @param stack  副产物物品（含数量）
     * @param chance 产出概率（0~1）
     */
    public record Byproduct(ItemStack stack, float chance) {
        public static final Codec<Byproduct> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.CODEC.fieldOf("item").forGetter(Byproduct::stack),
                Codec.FLOAT.optionalFieldOf("chance", 1.0F).forGetter(Byproduct::chance)
        ).apply(instance, Byproduct::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, Byproduct> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Byproduct::stack,
                ByteBufCodecs.FLOAT, Byproduct::chance,
                Byproduct::new);
    }

    /**
     * 配方匹配输入：引用机器的物品槽位与流体储罐。
     *
     * @param items     机器物品处理器
     * @param itemSlots 参与配方的槽位索引
     * @param fluid     机器流体处理器（可为 null）
     */
    public record MachineInput(IItemHandler items, int[] itemSlots, @Nullable IFluidHandler fluid)
            implements net.minecraft.world.item.crafting.RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return items.getStackInSlot(itemSlots[index]);
        }

        @Override
        public int size() {
            return itemSlots.length;
        }
    }

    /** 数据编解码器（不含配方类型，类型由序列化器在解码后回填） */
    public static final MapCodec<MachineRecipe> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            SizedIngredient.FLAT_CODEC.listOf().optionalFieldOf("item_inputs", List.of())
                    .forGetter(r -> r.itemInputs),
            FluidInput.CODEC.listOf().optionalFieldOf("fluid_inputs", List.of())
                    .forGetter(r -> r.fluidInputs),
            ItemStack.CODEC.listOf().optionalFieldOf("item_outputs", List.of())
                    .forGetter(r -> r.itemOutputs),
            FluidStack.CODEC.listOf().optionalFieldOf("fluid_outputs", List.of())
                    .forGetter(r -> r.fluidOutputs),
            Byproduct.CODEC.listOf().optionalFieldOf("byproduct_outputs", List.of())
                    .forGetter(r -> r.byproducts),
            ExtraCodecs.POSITIVE_INT.fieldOf("duration").forGetter(r -> r.duration),
            Codec.INT.optionalFieldOf("power_per_tick", 0).forGetter(r -> r.powerPerTick),
            Codec.BOOL.optionalFieldOf("generator", false).forGetter(r -> r.generator)
    ).apply(instance, (itemInputs, fluidInputs, itemOutputs, fluidOutputs, byproducts, duration, powerPerTick, generator) ->
            new MachineRecipe(null, itemInputs, fluidInputs, itemOutputs, fluidOutputs, byproducts, duration, powerPerTick, generator)));

    private final RecipeType<MachineRecipe> type;
    private final List<SizedIngredient> itemInputs;
    private final List<FluidInput> fluidInputs;
    private final List<ItemStack> itemOutputs;
    private final List<FluidStack> fluidOutputs;
    private final List<Byproduct> byproducts;
    private final int duration;
    private final int powerPerTick;
    private final boolean generator;

    /** 副产物概率掷骰用随机源（每配方实例一个） */
    private final net.minecraft.util.RandomSource byproductRandom = net.minecraft.util.RandomSource.create();

    /** 兼容构造：无副产物 */
    public MachineRecipe(@Nullable RecipeType<MachineRecipe> type,
                         List<SizedIngredient> itemInputs, List<FluidInput> fluidInputs,
                         List<ItemStack> itemOutputs, List<FluidStack> fluidOutputs,
                         int duration, int powerPerTick, boolean generator) {
        this(type, itemInputs, fluidInputs, itemOutputs, fluidOutputs, List.of(), duration, powerPerTick, generator);
    }

    /** 全参构造（含副产物） */
    public MachineRecipe(@Nullable RecipeType<MachineRecipe> type,
                         List<SizedIngredient> itemInputs, List<FluidInput> fluidInputs,
                         List<ItemStack> itemOutputs, List<FluidStack> fluidOutputs,
                         List<Byproduct> byproducts,
                         int duration, int powerPerTick, boolean generator) {
        this.type = type;
        this.itemInputs = itemInputs;
        this.fluidInputs = fluidInputs;
        this.itemOutputs = itemOutputs;
        this.fluidOutputs = fluidOutputs;
        this.byproducts = byproducts;
        this.duration = duration;
        this.powerPerTick = powerPerTick;
        this.generator = generator;
    }

    /** 回填配方类型（序列化器解码后调用） */
    public MachineRecipe withType(RecipeType<MachineRecipe> type) {
        return new MachineRecipe(type, itemInputs, fluidInputs, itemOutputs, fluidOutputs,
                byproducts, duration, powerPerTick, generator);
    }

    // -- 访问器 --

    public List<SizedIngredient> getItemInputs() { return itemInputs; }
    public List<FluidInput> getFluidInputs() { return fluidInputs; }
    public List<ItemStack> getItemOutputs() { return itemOutputs; }
    public List<FluidStack> getFluidOutputs() { return fluidOutputs; }
    public List<Byproduct> getByproducts() { return byproducts; }
    public int getDuration() { return duration; }
    public int getPowerPerTick() { return powerPerTick; }
    public boolean isGenerator() { return generator; }

    // -- 匹配与执行 --

    @Override
    public boolean matches(MachineInput input, Level level) {
        // 物品输入：每种原料在全部输入槽中的总量须满足数量要求
        for (SizedIngredient ingredient : itemInputs) {
            int available = 0;
            for (int i = 0; i < input.size(); i++) {
                ItemStack stack = input.getItem(i);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    available += stack.getCount();
                }
            }
            if (available < ingredient.count()) return false;
        }
        // 流体输入：每种流体在储罐中的总量须满足数量要求
        if (!fluidInputs.isEmpty()) {
            IFluidHandler fluid = input.fluid();
            if (fluid == null) return false;
            for (FluidInput fluidInput : fluidInputs) {
                int available = 0;
                for (int t = 0; t < fluid.getTanks(); t++) {
                    FluidStack stack = fluid.getFluidInTank(t);
                    if (fluidInput.matches(stack)) {
                        available += stack.getAmount();
                    }
                }
                if (available < fluidInput.amount()) return false;
            }
        }
        return true;
    }

    /**
     * 检查输出能否全部放入目标容器（模拟）。
     *
     * @param outItems  输出物品处理器
     * @param outSlots  输出槽位索引
     * @param outFluids 输出流体处理器（可为 null）
     */
    public boolean canFitOutputs(IItemHandler outItems, int[] outSlots, @Nullable IFluidHandler outFluids) {
        // 物品输出：逐个模拟插入
        for (ItemStack output : itemOutputs) {
            ItemStack remaining = output.copy();
            for (int slot : outSlots) {
                if (remaining.isEmpty()) break;
                remaining = insertSimulated(outItems, slot, remaining);
            }
            if (!remaining.isEmpty()) return false;
        }
        // 流体输出：逐个模拟填充
        if (!fluidOutputs.isEmpty()) {
            if (outFluids == null) return false;
            for (FluidStack output : fluidOutputs) {
                int filled = outFluids.fill(output.copy(), IFluidHandler.FluidAction.SIMULATE);
                if (filled < output.getAmount()) return false;
            }
        }
        return true;
    }

    private static ItemStack insertSimulated(IItemHandler handler, int slot, ItemStack stack) {
        ItemStack existing = handler.getStackInSlot(slot);
        if (existing.isEmpty()) {
            return stack.getCount() <= handler.getSlotLimit(slot) ? ItemStack.EMPTY : stack;
        }
        if (!ItemStack.isSameItemSameComponents(existing, stack)) return stack;
        int space = Math.min(handler.getSlotLimit(slot), existing.getMaxStackSize()) - existing.getCount();
        if (space <= 0) return stack;
        int moved = Math.min(space, stack.getCount());
        return moved >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    /** 消耗全部输入（真实执行） */
    public void consume(MachineInput input) {
        for (SizedIngredient ingredient : itemInputs) {
            int needed = ingredient.count();
            for (int i = 0; i < input.size() && needed > 0; i++) {
                int slot = input.itemSlots()[i];
                ItemStack stack = input.items().getStackInSlot(slot);
                if (stack.isEmpty() || !ingredient.test(stack)) continue;
                int toExtract = Math.min(needed, stack.getCount());
                input.items().extractItem(slot, toExtract, false);
                needed -= toExtract;
            }
        }
        if (!fluidInputs.isEmpty() && input.fluid() != null) {
            for (FluidInput fluidInput : fluidInputs) {
                input.fluid().drain(
                        new FluidStack(fluidInput.fluid(), fluidInput.amount()),
                        IFluidHandler.FluidAction.EXECUTE);
            }
        }
    }

    /** 产出全部输出（真实执行） */
    public void produce(IItemHandler outItems, int[] outSlots, @Nullable IFluidHandler outFluids) {
        for (ItemStack output : itemOutputs) {
            ItemStack remaining = output.copy();
            for (int slot : outSlots) {
                if (remaining.isEmpty()) break;
                remaining = insertExecute(outItems, slot, remaining);
            }
        }
        // 副产物：按各自概率掷骰，命中则尽力插入输出槽（输出槽满则丢失，不阻塞主产物）
        for (Byproduct byproduct : byproducts) {
            if (byproductRandom.nextFloat() >= byproduct.chance()) continue;
            ItemStack remaining = byproduct.stack().copy();
            for (int slot : outSlots) {
                if (remaining.isEmpty()) break;
                remaining = insertExecute(outItems, slot, remaining);
            }
        }
        if (outFluids != null) {
            for (FluidStack output : fluidOutputs) {
                outFluids.fill(output.copy(), IFluidHandler.FluidAction.EXECUTE);
            }
        }
    }

    private static ItemStack insertExecute(IItemHandler handler, int slot, ItemStack stack) {
        return handler.insertItem(slot, stack, false);
    }

    // -- Recipe 接口 --

    @Override
    public ItemStack assemble(MachineInput input, net.minecraft.core.HolderLookup.Provider registries) {
        return itemOutputs.isEmpty() ? ItemStack.EMPTY : itemOutputs.get(0).copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(net.minecraft.core.HolderLookup.Provider registries) {
        return itemOutputs.isEmpty() ? ItemStack.EMPTY : itemOutputs.get(0).copy();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeTypes.serializerOf(this.type).get();
    }

    @Override
    public RecipeType<?> getType() {
        return type;
    }

    // -- 网络同步 --

    public void toNetwork(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(itemInputs.size());
        for (SizedIngredient input : itemInputs) SizedIngredient.STREAM_CODEC.encode(buf, input);
        buf.writeVarInt(fluidInputs.size());
        for (FluidInput input : fluidInputs) FluidInput.STREAM_CODEC.encode(buf, input);
        buf.writeVarInt(itemOutputs.size());
        for (ItemStack output : itemOutputs) ItemStack.STREAM_CODEC.encode(buf, output);
        buf.writeVarInt(fluidOutputs.size());
        for (FluidStack output : fluidOutputs) FluidStack.STREAM_CODEC.encode(buf, output);
        buf.writeVarInt(byproducts.size());
        for (Byproduct byproduct : byproducts) Byproduct.STREAM_CODEC.encode(buf, byproduct);
        buf.writeVarInt(duration);
        buf.writeVarInt(powerPerTick);
        buf.writeBoolean(generator);
    }

    public static MachineRecipe fromNetwork(RegistryFriendlyByteBuf buf) {
        List<SizedIngredient> itemInputs = new java.util.ArrayList<>();
        int itemCount = buf.readVarInt();
        for (int i = 0; i < itemCount; i++) itemInputs.add(SizedIngredient.STREAM_CODEC.decode(buf));
        List<FluidInput> fluidInputs = new java.util.ArrayList<>();
        int fluidInputCount = buf.readVarInt();
        for (int i = 0; i < fluidInputCount; i++) fluidInputs.add(FluidInput.STREAM_CODEC.decode(buf));
        List<ItemStack> itemOutputs = new java.util.ArrayList<>();
        int itemOutputCount = buf.readVarInt();
        for (int i = 0; i < itemOutputCount; i++) itemOutputs.add(ItemStack.STREAM_CODEC.decode(buf));
        List<FluidStack> fluidOutputs = new java.util.ArrayList<>();
        int fluidOutputCount = buf.readVarInt();
        for (int i = 0; i < fluidOutputCount; i++) fluidOutputs.add(FluidStack.STREAM_CODEC.decode(buf));
        List<Byproduct> byproducts = new java.util.ArrayList<>();
        int byproductCount = buf.readVarInt();
        for (int i = 0; i < byproductCount; i++) byproducts.add(Byproduct.STREAM_CODEC.decode(buf));
        int duration = buf.readVarInt();
        int powerPerTick = buf.readVarInt();
        boolean generator = buf.readBoolean();
        return new MachineRecipe(null, itemInputs, fluidInputs, itemOutputs, fluidOutputs,
                byproducts, duration, powerPerTick, generator);
    }
}
