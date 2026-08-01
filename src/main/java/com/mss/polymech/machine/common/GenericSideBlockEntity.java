package com.mss.polymech.machine.common;

import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 通用侧面方块实体，服务于所有大型机器的侧面方块。
 * <p>
 * 不再需要为每台机器单独创建 SideBlockEntity 类——
 * 只需在注册时传入对应的 BlockEntityType 即可。
 * </p>
 */
public class GenericSideBlockEntity extends BaseIOSideBlockEntity {

    public GenericSideBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
}
