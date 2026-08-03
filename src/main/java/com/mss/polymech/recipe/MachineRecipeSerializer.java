package com.mss.polymech.recipe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import com.mojang.serialization.MapCodec;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 通用机器配方序列化器：每种机器配方类型注册一个实例，
 * 共用 {@link MachineRecipe} 的数据编解码器，解码后回填对应的配方类型。
 */
public class MachineRecipeSerializer implements RecipeSerializer<MachineRecipe> {

    private final Supplier<RecipeType<MachineRecipe>> type;
    private MapCodec<MachineRecipe> cachedCodec;
    private StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> cachedStreamCodec;

    public MachineRecipeSerializer(Supplier<RecipeType<MachineRecipe>> type) {
        this.type = type;
    }

    @Override
    public MapCodec<MachineRecipe> codec() {
        if (cachedCodec == null) {
            cachedCodec = MachineRecipe.DATA_CODEC.xmap(r -> r.withType(type.get()), Function.identity());
        }
        return cachedCodec;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec() {
        if (cachedStreamCodec == null) {
            cachedStreamCodec = new StreamCodec<>() {
                @Override
                public MachineRecipe decode(RegistryFriendlyByteBuf buf) {
                    return MachineRecipe.fromNetwork(buf).withType(type.get());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MachineRecipe recipe) {
                    recipe.toNetwork(buf);
                }
            };
        }
        return cachedStreamCodec;
    }
}
