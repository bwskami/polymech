package com.mss.polymech.mixin.datapack;

import com.llamalad7.mixinextras.sugar.Local;
import com.mss.polymech.mps.space.util.manger.SpaceModDataPackManger;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldLoader.InitConfig;
import net.minecraft.server.WorldLoader.ResultFactory;
import net.minecraft.server.WorldLoader.WorldDataSupplier;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 在世界加载期读入 {@code space_data/**} —— <b>与
 * {@code org.deep_space_studio.space.mixin.common.datapack.MixinWorldLoader} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>为什么要注入在 {@code RegistryLayer.createRegistryAccess()} 之前</h2>
 * 天体是数据包定义的，但"某颗行星有没有地表维度"只有<b>读完数据</b>才知道，
 * 而维度/维度类型/生物群系都是注册表、必须在注册表构建时就齐备。
 * 所以顺序被<b>卡死</b>成这样：
 * <pre>
 *   WorldLoader.load(...)
 *      ↓  ← 本注入点：readSpaceData(resourceManager)  把数据包读成内存 JSON 串
 *   RegistryLayer.createRegistryAccess()
 *      ↓  ← RegistryDataLoaderSpaceDataMixin 把这些 JSON 串当作"真实文件"塞进待加载集合
 *   注册表构建（维度/维度类型/biome 都在这时确定）
 *      ↓
 *   世界加载
 * </pre>
 * 注入点选在 {@code createRegistryAccess()} 的<b>第 0 次</b>调用处
 * （{@code ordinal = 0}），此时 {@code CloseableResourceManager} 已建好、
 * 注册表还没开始读盘 —— 早一步资源管理器还没有，晚一步注册表已经定型。
 *
 * <p>这一注入是<b>公共</b>代码路径（{@code WorldLoader} 同时服务单人与专用服务器），
 * 所以客户端与服务器都会走到；客户端那边读到的数据只用于本地登记，真正同步
 * 由服务端发包完成。</p>
 */
@Mixin(WorldLoader.class)
public class WorldLoaderSpaceDataMixin {

    @Inject(
            method = "load(Lnet/minecraft/server/WorldLoader$InitConfig;"
                    + "Lnet/minecraft/server/WorldLoader$WorldDataSupplier;"
                    + "Lnet/minecraft/server/WorldLoader$ResultFactory;"
                    + "Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)"
                    + "Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/RegistryLayer;createRegistryAccess()"
                            + "Lnet/minecraft/core/LayeredRegistryAccess;",
                    ordinal = 0))
    private static void polymech$loadSpaceData(
            InitConfig initConfig,
            WorldDataSupplier<?> worldDataSupplier,
            ResultFactory<?, ?> resultFactory,
            Executor backgroundExecutor,
            Executor mainThreadExecutor,
            CallbackInfoReturnable<CompletableFuture<?>> cir,
            @Local CloseableResourceManager resourceManager) {
        SpaceModDataPackManger.readSpaceData(resourceManager);
    }
}
