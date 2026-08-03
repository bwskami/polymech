---
name: neoforge-modding
description: NeoForge Minecraft mod development guidance based on the official NeoForge docs. Use when working on NeoForge mods, Minecraft mod loaders, Java modding projects, Gradle MDK/ModDevGradle/NeoGradle setup, registries, DeferredRegister, events, client/server sides, items, blocks, data generation, resources, custom payload networking, or dedicated-server safety.
license: MIT
metadata:
  version: "1.0.0"
---

# NeoForge Modding

Use this skill to implement or review Minecraft mods targeting NeoForge. Prefer project-local versions, mappings, Gradle plugins, and existing conventions over generic examples. When behavior depends on a specific Minecraft or NeoForge version, verify against the matching page on `https://docs.neoforged.net/` before coding.

## First Checks

- Confirm the Minecraft and NeoForge versions from `gradle.properties`, `build.gradle`, `settings.gradle`, or generated MDK files.
- Confirm the mod id, package, and Java version; modern NeoForge 1.21.x expects Java 21.
- Prefer the generated Gradle workflow: `./gradlew build`, `./gradlew runClient`, `./gradlew runServer`, and datagen runs configured by the project.
- Reload Gradle after changing Gradle files; avoid editing `build.gradle`/`settings.gradle` when `gradle.properties` is sufficient.
- Always test server safety for common code, even for client-focused mods.

## Project Structure

- Keep common code free of `net.minecraft.client` imports.
- Put client-only setup behind physical-client gates: a separate `@Mod(value = MOD_ID, dist = Dist.CLIENT)` class or client-only event subscriber.
- Use `src/main/java` for code, `src/main/resources/assets/<modid>` for client assets, and `src/main/resources/data/<modid>` for gameplay data.
- Use generated resources under `src/generated/resources` when the Gradle project is configured for datagen.
- Keep registration classes small and grouped by registry type, e.g. `ModItems`, `ModBlocks`, `ModCreativeTabs`.

## Registration

- Prefer `DeferredRegister` over raw `RegisterEvent` unless the project needs low-level control.
- Create one deferred register per registry and attach it to the mod event bus in the mod constructor.
- Store registered objects as `Supplier<T>`, `DeferredHolder<R, T>`, `DeferredItem<T>`, or `DeferredBlock<T>` according to the API expected by surrounding code.
- Do not instantiate registry objects outside registration; blocks, items, entities, tabs, data components, and similar entries must be singleton registry entries.
- Use the mod id namespace for every custom `Identifier`/resource location and keep registry names lowercase snake_case.

Typical registration shape:

```java
public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.register(
    "example_item",
    registryName -> new Item(new Item.Properties()
        .setId(ResourceKey.create(Registries.ITEM, registryName)))
);

public ExampleMod(IEventBus modBus) {
    ModItems.ITEMS.register(modBus);
}
```

## Items And Blocks

- For items, always set the resource key through `Item.Properties#setId`; configure stack size, durability, rarity, food, cooldowns, and data components through properties when possible.
- Treat `ItemStack` as mutable; call `copy()` or `copyWithCount()` before mutating stacks that may be shared or treated as immutable.
- For blocks, use `DeferredRegister.createBlocks(MOD_ID)` and set the resource key through `BlockBehaviour.Properties#setId`.
- Register a matching `BlockItem` if a block must appear in inventories or be placeable by players.
- Add items to existing creative tabs with `BuildCreativeModeTabContentsEvent`; register custom `CreativeModeTab` entries through a deferred register.
- Put models, blockstates, item models, lang entries, recipes, tags, and loot tables in assets/data or datagen providers instead of hardcoding them.

## Events

- Register game events on `NeoForge.EVENT_BUS`; register mod lifecycle/registry/setup/datagen/network payload events on the mod event bus.
- Event handlers must be `void` methods with a single event parameter.
- Use `IEventBus#addListener`, `@SubscribeEvent`, or `@EventBusSubscriber(modid = MOD_ID)` consistently with the project style.
- Do not subscribe to abstract event classes such as base `LivingEvent`, `PlayerEvent`, or `BlockEvent`; subscribe to concrete subevents.
- For cancellable events, use `setCanceled` only when cancellation is documented for that event; for tri-state/result events, prefer the event’s explicit result setter.
- For parallel lifecycle events, use `event.enqueueWork(...)` when work must run on the main thread.

## Sides

- Use `level.isClientSide()` for logical-side game logic decisions; run authoritative gameplay logic only when it is `false`.
- Use `FMLEnvironment.getDist()`, `Dist`, or `@Mod(..., dist = Dist.CLIENT)` for physical-side/client-class isolation.
- Never assume singleplayer means server-only code can touch client classes; singleplayer has both a logical client and logical server inside a physical client.
- Transfer state between logical sides with networking payloads, not static fields.
- Run `runServer` or a dedicated-server test path to catch `NoClassDefFoundError` from client-only imports.

## Networking

- Register custom payloads during `RegisterPayloadHandlersEvent` on the mod event bus using `event.registrar("<protocol-version>")`.
- Implement payloads as `CustomPacketPayload` records/classes with a unique `Type` and a `StreamCodec`.
- Choose `playToServer`, `playToClient`, or `playBidirectional` according to direction; register clientbound handlers with `RegisterClientPayloadHandlersEvent` in client-only code.
- Keep handlers on the main thread by default; use `HandlerThread.NETWORK` only for expensive work, then return to the game thread with `IPayloadContext#enqueueWork` and handle exceptions.
- Send client-to-server payloads with `ClientPacketDistributor.sendToServer`; send server-to-client payloads with `PacketDistributor` helpers.
- Respect payload size limits: clientbound payloads are at most 1 MiB, serverbound payloads are less than 32 KiB.

## Resources And Datagen

- Put client resources under `assets/<modid>` and server data under `data/<modid>`.
- Remember NeoForge generates built-in resource/data packs for mods and modern NeoForge handles `pack.mcmeta` at runtime.
- Use vanilla and NeoForge external resources in the IDE as the source of truth for JSON formats.
- Prefer datagen for repetitive or fragile JSON: models, blockstates, lang, tags, recipes, loot tables, sounds, particles, data maps, and datapack registries.
- Register datagen providers from `GatherDataEvent.Client`; use event helpers such as `createProvider`, `createBlockAndItemTags`, and `createDatapackRegistryObjects` when available.
- Use `RegistrySetBuilder` and `BootstrapContext` for datapack registry entries that need generated JSON.

## Validation Checklist

- Run the narrowest relevant Gradle task first, then `./gradlew build` if practical.
- Run client and dedicated-server paths when touching sides, events, networking, rendering, or resources.
- Verify generated JSON is included in the resource output and checked for namespace/path correctness.
- Check logs for missing models, missing translations, registry freeze errors, packet version mismatches, and client-only class loading on server.
- When unsure about an API, inspect existing project usage and the matching NeoForge docs version before inventing patterns.

## Official References

- Main docs: `https://docs.neoforged.net/`
- Getting started: `https://docs.neoforged.net/docs/gettingstarted/`
- Registries: `https://docs.neoforged.net/docs/concepts/registries/`
- Events: `https://docs.neoforged.net/docs/concepts/events/`
- Sides: `https://docs.neoforged.net/docs/concepts/sides/`
- Items: `https://docs.neoforged.net/docs/items/`
- Blocks: `https://docs.neoforged.net/docs/blocks/`
- Resources and datagen: `https://docs.neoforged.net/docs/resources/`
- Networking payloads: `https://docs.neoforged.net/docs/networking/payload/`
