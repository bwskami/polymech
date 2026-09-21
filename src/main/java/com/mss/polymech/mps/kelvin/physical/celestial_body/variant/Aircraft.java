package com.mss.polymech.mps.kelvin.physical.celestial_body.variant;

import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * 载具（把物理体当成天体）—— <b>与
 * {@code org.cn_grass_block.kelvin.physical.celestial_body.variant.Aircraft} 同形</b>
 * 的自有实现（clean-room）。
 *
 * <h2>职责</h2>
 * 让一个 <b>MPS 物理体</b>（飞船/建筑）同时作为一个天体参与天体世界：它能被记录、能随包同步、
 * 也能出现在天体池里（于是引力/力下发那条线对它同样适用）。
 *
 * <h2>为什么这样"包装"而不是让物理体继承天体（照 space 0.1.3）</h2>
 * 两条继承线是正交的：物理体关心刚体/碰撞/方块快照，天体关心轨道与力池。
 * 硬把一方塞进另一方会得到一个巨型类；space 用<b>适配器</b>（组合）把它们接起来 ——
 * 身份取物理体的 uuid、位置/姿态/质量每步从物理体读，名字固定成
 * {@code "MPS_PhysicalBody:" + uuid} 以便按名字配对（天体的相等只看名字）。
 *
 * <p><b>位置/姿态不是快照</b>：这里只在构造时取一次，之后由上层每步
 * {@code SpaceWorld.step} 把物理体的真实位姿写回来（见 {@code PhysicalBodySpaceEvent} 的语义）。</p>
 *
 * <p><b>与 MPS 的差异</b>：{@code decode} 补了空世界/找不到体时的返回 null ——
 * 原版在 {@code getPhysicalWorld()} 为 null 时会 NPE（包先到的时序问题）。</p>
 */
public class Aircraft extends CelestialBody {

    private final PhysicalBody physicalBody;

    public PhysicalBody getPhysicalBody() {
        return this.physicalBody;
    }

    public Aircraft(PhysicalBody physicalBody) {
        super(physicalBody.getLevel().toString(),
                "MPS_PhysicalBody:" + physicalBody.getUuid(),
                physicalBody.getPos(), physicalBody.getRotation(), 1.0);
        this.setMass(physicalBody.getMass());
        this.physicalBody = physicalBody;
    }

    @Override
    public void encode(FriendlyByteBuf buffer) {
        super.encode(buffer);
        buffer.writeUUID(this.physicalBody.getUuid());
    }

    /** 客户端按 uuid 找回物理体后才建得出载具 —— 找不到就返回 null（调用方跳过）。 */
    public static Aircraft decode(FriendlyByteBuf buffer) {
        CelestialBody.decode(buffer);
        UUID uuid = buffer.readUUID();
        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        if (physicalWorld == null) {
            return null;
        }
        PhysicalBody physicalBody = physicalWorld.getPhysicalBody(uuid);
        return physicalBody == null ? null : new Aircraft(physicalBody);
    }

    @Override
    public void tick() {
    }
}
