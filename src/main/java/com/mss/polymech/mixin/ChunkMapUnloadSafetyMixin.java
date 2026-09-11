package com.mss.polymech.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Queue;

/**
 * 修复 ChunkMap.processUnloads 在服务端关闭时的无限循环。
 * 对 unloadQueue.poll() 加计数器，单次 processUnloads 最多消费 500 个 Runnable。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapUnloadSafetyMixin {

    @Unique
    private static final int POLYMECH_MAX_UNLOAD_PER_TICK = 500;

    @Unique
    private int polymech$unloadCount;

    @Redirect(
        method = "processUnloads",
        at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;")
    )
    private Object polymech$safePoll(Queue<?> self) {
        if (++polymech$unloadCount > POLYMECH_MAX_UNLOAD_PER_TICK) {
            return null;
        }
        return self.poll();
    }

    @Redirect(
        method = "processUnloads",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongIterator;hasNext()Z")
    )
    private boolean polymech$resetCounter(it.unimi.dsi.fastutil.longs.LongIterator self) {
        polymech$unloadCount = 0;
        return self.hasNext();
    }
}
