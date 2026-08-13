package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;

/**
 * 创造模式蓄电池方块（无限能源）。
 * <p>
 * 与普通蓄电池共用方块逻辑，但始终输出无限电力，
 * 且豁免过压熔断。
 * </p>
 */
public class CreativeBatteryBlock extends BatteryBlock {

    public static final MapCodec<CreativeBatteryBlock> CODEC = simpleCodec(CreativeBatteryBlock::new);

    public CreativeBatteryBlock(Properties properties) {
        super(properties, true);
    }

    @Override
    protected MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec() {
        return CODEC;
    }
}
