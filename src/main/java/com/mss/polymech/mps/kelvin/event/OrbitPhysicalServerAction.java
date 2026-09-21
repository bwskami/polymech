package com.mss.polymech.mps.kelvin.event;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.mps.kelvin.OrbitPhysicalThread;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 天体模拟的服务器生命周期 —— <b>与
 * {@code org.cn_grass_block.kelvin.event.OrbitPhysicalServerAction}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 两个时机：<b>开服</b>时把每个太空维度的天体状态从存档读回并启动物理线程；
 * <b>停服</b>时先停线程，再把状态写回存档。
 *
 * <h2>为什么先停线程再存（顺序不能换）</h2>
 * 天体数据由 {@link OrbitPhysicalThread} 在<b>另一个线程</b>里持续改写。
 * 若先存后停，存档的瞬间物理线程可能正写到一半 —— 存下来的半张状态会让下次开服
 * 的天体位置错乱（表现为"行星瞬移"）。先停线程 = 先把写者冻住，读才是稳定的。
 *
 * <h2>为什么状态按维度分开存（{@link OrbitSimulationWorldAction}）</h2>
 * 存档格式用的是 {@link SavedData}：MC 会把它归属到某个 {@link ServerLevel} 下，
 * 于是"这个太空维度"的天体状态天然跟着这个维度的存档走 ——
 * 删掉某个维度不会带走别的维度的天体，复制存档也不会把两个宇宙的轨道串起来。
 * <p>粒度上它<b>整个 {@link SpaceWorld} 序列化成一段 JSON 字符串</b>
 * （{@code toJsonObject()} / {@code readDataFromJsonObject()}），而不是给每个天体
 * 建一条 SavedData：天体数量会随玩法变化，用一段不透明 JSON 可以让天体种类
 * （行星/恒星/流星…）自由增删而<b>不用改存档结构</b>。代价是存档不可读、
 * 也无法局部迁移 —— 这是 space 的取舍，照抄。</p>
 *
 * <p>名字固定为 {@code orbit_data}，且 {@code jsonData} 为 null 时视为"没有存档"
 * （首次进世界、或该维度还没有太空世界）。</p>
 */
public class OrbitPhysicalServerAction {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerStart(ServerStartedEvent event) {
        event.getServer().getAllLevels().forEach(serverLevel -> {
            OrbitSimulationWorldAction worldAction = OrbitSimulationWorldAction.get(serverLevel);
            SpaceWorld spaceWorld = ServerSpaceWorld.getSpaceWorld(serverLevel);
            if (spaceWorld != null) {
                if (worldAction.getJsonData() != null) {
                    spaceWorld.readDataFromJsonObject(new Gson().fromJson(worldAction.getJsonData(), JsonObject.class));
                }
            }
        });
        OrbitPhysicalThread.startThread();
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        // 顺序不能换：先冻住写者，再读（见类注释）
        OrbitPhysicalThread.stopThread();
        event.getServer().getAllLevels().forEach(serverLevel -> {
            SpaceWorld spaceWorld = ServerSpaceWorld.getSpaceWorld(serverLevel);
            if (spaceWorld != null) {
                OrbitSimulationWorldAction worldAction = OrbitSimulationWorldAction.get(serverLevel);
                worldAction.setJsonData(spaceWorld.toJsonObject().toString());
            }
        });
    }

    /**
     * 单个维度下的天体状态存档：一段不透明 JSON（见类注释为什么不做成结构化 NBT）。
     *
     * <p>{@code get()} 用 {@code computeIfAbsent} 而不是"先查后建"：数据存储由 MC
     * 管理并可能被并发访问，两步走会有竞态。</p>
     */
    public static class OrbitSimulationWorldAction extends SavedData {
        private String jsonData;

        public static final SavedData.Factory<OrbitSimulationWorldAction> FACTORY =
                new SavedData.Factory<>(OrbitSimulationWorldAction::new, OrbitSimulationWorldAction::load);

        public static OrbitSimulationWorldAction load(CompoundTag tag, HolderLookup.Provider provider) {
            OrbitSimulationWorldAction data = new OrbitSimulationWorldAction();
            data.jsonData = tag.getString("jsonData");
            return data;
        }

        public static OrbitSimulationWorldAction get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(FACTORY, "orbit_data");
        }

        public void setJsonData(String json) {
            this.jsonData = json;
            this.setDirty(); // 不标脏 MC 不会写盘
        }

        public String getJsonData() {
            return this.jsonData;
        }

        @Override
        public @NotNull CompoundTag save(@NotNull CompoundTag compoundTag, @NotNull HolderLookup.Provider provider) {
            compoundTag.putString("jsonData", this.jsonData);
            return compoundTag;
        }
    }
}
