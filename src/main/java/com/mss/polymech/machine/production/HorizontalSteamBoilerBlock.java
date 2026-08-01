package com.mss.polymech.machine.production;

import com.mss.polymech.machine.common.LargeMachineBlock;
import com.mss.polymech.machine.common.MachineConfig;
import net.minecraft.core.Vec3i;

public class HorizontalSteamBoilerBlock extends LargeMachineBlock {

    public HorizontalSteamBoilerBlock(MachineConfig config) {
        super(config);
    }

    @Override
    public Vec3i[] getSideOffsets() {
        return new Vec3i[]{
                new Vec3i(0, 0, 1),
            // 你的自定义偏移...
        };
    }

    @Override
    public Vec3i[][] getFillRegions() {
        return new Vec3i[][]{
            // 你的自定义填充区域...
        };
    }
}
