package com.mss.polymech.recipe;

import com.mss.polymech.Polymech;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 机器配方类型注册中心（参考 GTM：每台机器一个配方类型，共用通用配方类）。
 * <p>
 * 每个 {@link Entry} 绑定一个 {@link RecipeType} 和对应的 {@link MachineRecipeSerializer}。
 * </p>
 */
public class ModRecipeTypes {

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Polymech.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Polymech.MOD_ID);

    /** 配方类型 + 序列化器绑定 */
    public record Entry(Supplier<RecipeType<MachineRecipe>> type,
                        Supplier<RecipeSerializer<MachineRecipe>> serializer) {
    }

    private record PendingEntry(Supplier<RecipeType<MachineRecipe>> type,
                                Supplier<RecipeSerializer<MachineRecipe>> serializer) {
    }

    /* 延迟解析表：注册阶段 Supplier 尚未可用，首次查询时回填 */
    private static final Map<String, PendingEntry> PENDING = new HashMap<>();
    /* 类型 → 序列化器反查表（MachineRecipe.getSerializer 使用） */
    private static final Map<RecipeType<MachineRecipe>, Supplier<RecipeSerializer<MachineRecipe>>> SERIALIZER_LOOKUP =
            new HashMap<>();

    /* 蜂巢焦炉：无动力，纯耗时（煤→焦煤） */
    public static final Entry BEEHIVE_COKE_OVEN = register("beehive_coke_oven");
    /* 原始高炉：无动力（铁+煤/焦→钢） */
    public static final Entry PRIMITIVE_BLAST_FURNACE = register("primitive_blast_furnace");
    /* 火焰反射炉：燃料驱动熔炼（运行时代理原版熔炼配方，此类型预留） */
    public static final Entry FLAME_REVERBERATORY_FURNACE = register("flame_reverberatory_furnace");
    /* 蒸汽锤：蒸汽驱动（锭→板） */
    public static final Entry STEAM_HAMMER = register("steam_hammer");
    /* 蒸汽辊式破碎机：蒸汽驱动（粉碎） */
    public static final Entry STEAM_ROLLER_CRUSHER = register("steam_roller_crusher");
    /* 蒸汽双联矿物跳汰机：蒸汽驱动（原矿洗选） */
    public static final Entry STEAM_DUPLEX_MINERAL_JIG = register("steam_duplex_mineral_jig");
    /* 灌装机：电力驱动（空容器+流体→满容器） */
    public static final Entry FILLING_UNIT = register("filling_unit");
    /* 蒸汽涡轮发电机：消耗蒸汽发电 */
    public static final Entry STEAM_TURBINE_GENERATOR = register("steam_turbine_generator");
    /* 燃气涡轮发电机：消耗燃气发电（燃气流体待添加，配方暂空） */
    public static final Entry GAS_TURBINE_GENERATOR = register("gas_turbine_generator");

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Entry register(String name) {
        Supplier<RecipeType<MachineRecipe>> type = (Supplier) RECIPE_TYPES.register(name,
                () -> new RecipeType<MachineRecipe>() {
                    @Override
                    public String toString() {
                        return Polymech.MOD_ID + ":" + name;
                    }
                });
        Supplier<RecipeSerializer<MachineRecipe>> serializer = (Supplier) SERIALIZERS.register(name,
                () -> new MachineRecipeSerializer(type));
        PENDING.put(name, new PendingEntry(type, serializer));
        return new Entry(type, serializer);
    }

    /** 根据配方类型查找对应序列化器 */
    public static Supplier<RecipeSerializer<MachineRecipe>> serializerOf(RecipeType<MachineRecipe> type) {
        Supplier<RecipeSerializer<MachineRecipe>> direct = SERIALIZER_LOOKUP.get(type);
        if (direct != null) return direct;
        for (PendingEntry pending : PENDING.values()) {
            if (pending.type().get() == type) {
                SERIALIZER_LOOKUP.put(type, pending.serializer());
                return pending.serializer();
            }
        }
        throw new IllegalStateException("No serializer registered for recipe type: " + type);
    }

    public static void register(IEventBus eventBus) {
        RECIPE_TYPES.register(eventBus);
        SERIALIZERS.register(eventBus);
    }
}
