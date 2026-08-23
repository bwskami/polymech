package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.OreDynamicResourcePack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/*
 * 矿石动态资源包注册（AddPackFindersEvent，MOD总线）。
 * <p>
 * 在客户端资源仓库创建时注入一个位于BOTTOM的内存资源包，
 * 运行时提供矿石方块状态与物品模型（2714个一行指针JSON）。
 * 这使datagen不再写这些重复文件，同时保持与静态方案完全相同的资源加载路径。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class OreDynamicPackEvents {

    @SubscribeEvent
    public static void registerPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        event.addRepositorySource(new OrePackSource("poly_mech:dynamic_ore_assets",
                PackType.CLIENT_RESOURCES, Pack.Position.BOTTOM, OreDynamicResourcePack::new));
    }

    /** 极简RepositorySource：等价于GT的GTPackSource */
    private record OrePackSource(
            String name,
            PackType type,
            Pack.Position position,
            Function<PackLocationInfo, PackResources> resources
    ) implements RepositorySource {
        @Override
        public void loadPacks(Consumer<Pack> onLoad) {
            onLoad.accept(Pack.readMetaAndCreate(
                    new PackLocationInfo(name, Component.literal(name), PackSource.BUILT_IN, Optional.empty()),
                    new Pack.ResourcesSupplier() {
                        @Override
                        public PackResources openPrimary(PackLocationInfo info) {
                            return resources.apply(info);
                        }

                        @Override
                        public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                            return resources.apply(info);
                        }
                    },
                    type,
                    new PackSelectionConfig(true, position, false)));
        }
    }
}
