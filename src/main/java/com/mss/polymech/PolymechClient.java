package com.mss.polymech;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.client.space.ClientSpaceTransition;
import com.mss.polymech.client.space.SpaceAttitudeOverlay;
import com.mss.polymech.client.space.SpaceDimensionEffects;
import com.mss.polymech.client.space.SpaceRenderer;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.client.model.conveyor.ConveyorModelLoader;
import com.mss.polymech.client.model.pipe.PipeModelLoader;
import com.mss.polymech.client.renderer.ConveyorBlockEntityRenderer;
import com.mss.polymech.client.renderer.MachineGeoRenderer;
import com.mss.polymech.client.tooltip.ClientCompositionPieTooltipComponent;
import com.mss.polymech.client.tooltip.ClientMoleculeStructureTooltipComponent;
import com.mss.polymech.fluid.ModChemicalFluids;
import com.mss.polymech.fluid.ModElementFluids;
import com.mss.polymech.fluid.ModFluidBuckets;
import com.mss.polymech.fluid.ModFluids;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.tooltip.CompositionPieTooltipComponent;
import com.mss.polymech.tooltip.CompositionStructureTooltipComponent;
import com.mss.polymech.tooltip.ModTooltipCenter;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.model.DynamicFluidContainerModel;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Polymech.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class PolymechClient {
    public PolymechClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // 注册成分饼图tooltip组件工厂（RegisterClientTooltipComponentFactoriesEvent在MOD总线）
        container.getEventBus().addListener(RegisterClientTooltipComponentFactoriesEvent.class, event -> {
            event.register(CompositionPieTooltipComponent.class, ClientCompositionPieTooltipComponent::new);
            event.register(CompositionStructureTooltipComponent.class, ClientMoleculeStructureTooltipComponent::new);
        });
        // GatherComponents是游戏总线事件：@EventBusSubscriber默认挂在MOD总线收不到，需手动注册
        NeoForge.EVENT_BUS.addListener(ModTooltipCenter::onGatherTooltipComponents);
        NeoForge.EVENT_BUS.addListener(ClientSpaceTransition::onClientTick);
        NeoForge.EVENT_BUS.addListener(SpaceRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(SpaceRenderer::onRenderGui);
        // 注册太空维度特效（维度天空盒走 MC 原生管线）
        container.getEventBus().addListener(RegisterDimensionSpecialEffectsEvent.class, event -> {
            event.register(PlanetDimensions.SPACE.location(), new SpaceDimensionEffects());
            // ★ 行星地表维度也必须挂上我们自己的特效。
            // 不挂的话它们会回落到 `minecraft:overworld`（我们合成的 dimension_type 写的就是它），
            // 而主世界特效的 `getBrightnessDependentFogColor` 会按太阳高度给雾色**加日落红染** ——
            // 在我们已经取消原版天空（见 LevelRendererCelestialSkyMixin）之后，
            // 那层红染就成了"转视角到某个方向天空变暗红"的直接来源。
            // SpaceDimensionEffects 的雾色调制是恒等、且 isFoggyAt=false / 无云无雨，
            // 正是地表想要的"干净天幕"。
            for (int i = 0; i < com.mss.polymech.space.RealAstroData.BODIES.size(); i++) {
                if (PlanetDimensions.isTeleportable(i)) {
                    event.register(PlanetDimensions.dimension(i).location(), new SpaceDimensionEffects());
                }
            }
        });
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 方案 B（§30）的守门断言：每个坐标改动步骤前后都要有这一行可对。
        // S1–S3 的目标是"blockPos 校验和逐位不变"；S4 换恒等后才允许它变。
        Polymech.LOGGER.info("[坐标自检] {}", com.mss.polymech.space.SpaceWorld.coordinateSelfCheck());
        // Mixin 注入成功是静默的（失败才报错），所以这里主动确认"太空不画实体阴影"的锚点真的进了目标类。
        // 没有这一行，"太空里没卡死"就分不清是守卫生效、还是第一人称下压根没走到阴影路径（§31.8）。
        // 自检必须在普通类里做：mixin 类不允许非 private 的 static 方法，
        // 而 mixin 包（com.mss.polymech.mixin.*）里的类又禁止被直接引用（两条都会打崩启动）。
        // 用 enqueueWork 回到主线程再查：client setup 跑在并行 worker 上（日志里是 [Worker-Main-*]），
        // 在 worker 里首次加载 Minecraft 客户端类有并行类加载死锁的风险。
        event.enqueueWork(() -> Polymech.LOGGER.info("[太空阴影] 锚点自检：{}",
                com.mss.polymech.client.space.SpaceShadowAnchor.applied()
                ? "已注入 EntityRenderDispatcher（太空世界不画实体阴影；进太空后切第三人称可验证跳过那条日志）"
                : "★ 未注入 —— 锚点没生效，太空里被渲染的实体仍会走原版 int 阴影循环并卡死"));
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(com.mss.polymech.client.BlueprintInputHandler.BLUEPRINT_CANCEL_KEY);
        event.register(com.mss.polymech.client.BlueprintInputHandler.BLUEPRINT_CYCLE_MODE_KEY);
        event.register(com.mss.polymech.client.BlueprintInputHandler.BLUEPRINT_CYCLE_AXIS_KEY);
    }

    /** 注册太空姿态仪为 NeoForge GUI 分层（Mekanism 同款 LayeredDraw.Layer 写法）。 */
    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "attitude_hud"),
                SpaceAttitudeOverlay.INSTANCE);
    }

    @SubscribeEvent
    static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(PipeModelLoader.ID, PipeModelLoader.INSTANCE);
        event.register(ConveyorModelLoader.ID, ConveyorModelLoader.INSTANCE);
    }

    /*
     * 注册流体单元（四种规格）的流体层颜色。
     * <p>
     * NeoForge的fluid_container模型不会自动染色，需要注册
     * DynamicFluidContainerModel.Colors：tintIndex==1时返回所含流体的tint颜色。
     * </p>
     */
    @SubscribeEvent
    static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        // 流体单元：tintIndex==1 时用所含流体的 tint 颜色
        event.register(new DynamicFluidContainerModel.Colors(),
                ModItems.ALL_FLUID_CELLS.stream().map(def -> (net.minecraft.world.item.Item) def.get())
                        .toArray(net.minecraft.world.item.Item[]::new));

        // 所有流体桶：统一从 ModFluidBuckets 数据驱动获取，按所含流体着色，保证和流体单元一致
        var bucketItems = new java.util.ArrayList<net.minecraft.world.item.Item>();
        for (var entry : ModFluidBuckets.getAll()) {
            bucketItems.add(entry.item());
        }
        if (!bucketItems.isEmpty()) {
            event.register(new DynamicFluidContainerModel.Colors(),
                    bucketItems.toArray(net.minecraft.world.item.Item[]::new));
        }
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 传送带物品包渲染（数据驱动，无实体）
        event.registerBlockEntityRenderer(ModBlockEntities.CONVEYOR.get(), ConveyorBlockEntityRenderer::new);

        // 自动注册所有大型机器的方块实体渲染器（从 MachineRegistry 遍历）
        for (MachineRegistry.MachineEntry entry : MachineRegistry.getEntries()) {
            var beSupplier = entry.mainBlockEntity();
            if (beSupplier == null) continue;

            ResourceLocation modelPath, texturePath, animationPath;
            if ("filling_unit".equals(entry.id())) {
                // FillingUnit 不使用 block/ 子目录约定
                modelPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/filling_unit.geo.json");
                texturePath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/filling_unit.png");
                animationPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/filling_unit.animation.json");
            } else {
                String id = entry.id();
                modelPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/" + id + ".geo.json");
                texturePath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/" + id + "/" + id + ".png");
                animationPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/" + id + ".animation.json");
            }

            event.registerBlockEntityRenderer(
                    (net.minecraft.world.level.block.entity.BlockEntityType) beSupplier.get(),
                    ctx -> new MachineGeoRenderer<>(ctx, modelPath, texturePath, animationPath));
        }
    }
}
