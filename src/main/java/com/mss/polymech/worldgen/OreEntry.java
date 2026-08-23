package com.mss.polymech.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;
import java.util.List;

/*
 * 矿脉组成条目：一种矿物在矿脉中的"宿主→矿石变体"映射表（群峦式）。
 * <p>
 * 每种矿物在每种宿主岩中都有独立方块（格雷/群峦式岩种变体）：
 * 替换花岗岩→{mineral}_granite_ore，替换深层石→deepslate_{mineral}_ore，
 * 以此类推。矿脉替换宿主方块时按宿主查表选择对应的岩种矿石变体。
 * </p>
 *
 * @param mappings 宿主方块→矿石方块状态映射列表（配置序列化单元）
 */
public record OreEntry(List<OreEntry.HostMapping> mappings) {

    /*
     * 单条宿主映射。
     *
     * @param host 宿主方块（被替换的岩石/石头/深层石）
     * @param ore 替换生成的矿石方块状态（对应岩种变体）
     */
    public record HostMapping(Block host, BlockState ore) {
        public static final Codec<HostMapping> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("host").forGetter(HostMapping::host),
                BlockState.CODEC.fieldOf("ore").forGetter(HostMapping::ore)
        ).apply(instance, HostMapping::new));
    }

    public static final Codec<OreEntry> CODEC = HostMapping.CODEC
            .listOf()
            .fieldOf("mappings")
            .xmap(OreEntry::new, OreEntry::mappings)
            .codec();

    /*
     * 按被替换的宿主方块选择对应岩种的矿石变体。
     * <p>
     * 宿主不在映射表内返回null（矿脉宿主过滤已保证只替换表内宿主，
     * 此处为保险；Feature侧对null跳过放置）。
     * </p>
     */
    @Nullable
    public BlockState forState(BlockState host) {
        Block hostBlock = host.getBlock();
        for (HostMapping mapping : mappings) {
            if (mapping.host() == hostBlock) {
                return mapping.ore();
            }
        }
        return null;
    }
}
