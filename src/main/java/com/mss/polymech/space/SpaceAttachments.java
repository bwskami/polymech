package com.mss.polymech.space;

import com.mss.polymech.Polymech;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * {@link SpacePlayerData} 的 <b>FML 注册管道</b>（DataAttachment 类型）。
 *
 * <h2>为什么注册要和状态分开（2026-10-03 拆）</h2>
 * 原来 {@code REGISTRAR} / {@code TYPE} 是 {@code SpacePlayerData} 的静态字段。静态字段会在
 * <b>类初始化</b>时构造 {@code DeferredRegister}，而那要求整套 FML 加载环境
 * （实测：离线探针里第一次碰到就 {@code Not bootstrapped}，bootstrap 之后又
 * {@code LoadingModList.get() is null}）⇒ <b>离线跑不了真的 {@code SpacePlayerData}</b>。
 *
 * <p>后果是实打实的：{@code RollInputBasisProbe} 只能<b>复刻</b>它的公式来验，
 * 而"复刻对复刻"必然自洽 ⇒ 那条判据永远 3/3，**真实现错了它也看不见**
 * （用户 2026-10-03 反馈"滚转还是反的"时，探针仍然全过 —— 这就是死判据）。
 * 拆完之后 {@code SpacePlayerData} 是纯状态类，探针可以 {@code new} 它、直接驱动
 * {@code initFromVanilla/turn/rollBody} 并用**真方法**对表。</p>
 *
 * <p>这也符合单一职责：状态类管状态，注册管道管注册。</p>
 */
public final class SpaceAttachments {

    public static final DeferredRegister<AttachmentType<?>> REGISTRAR =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Polymech.MOD_ID);

    public static final Supplier<AttachmentType<SpacePlayerData>> SPACE_ROTATION =
            REGISTRAR.register("space_rotation", () ->
                    AttachmentType.<SpacePlayerData>builder(SpacePlayerData::new).build());

    private SpaceAttachments() {
    }

    public static void register(net.neoforged.bus.api.IEventBus bus) {
        REGISTRAR.register(bus);
    }
}
