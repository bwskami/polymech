package com.mss.polymech.mps.physical.physical_body;

import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyCreate;
import com.mss.polymech.mps.physical.manger.ProjectionManager;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 服务端物理体 —— <b>与
 * {@code org.polaris2023.mps.physical.physical_body.ServerPhysicalBody} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 在 {@link PhysicalBody} 之上补两件<b>只在服务端成立</b>的事：
 * <ol>
 *   <li><b>绑定一块投影地皮</b>（{@link ProjectionManager.Projection}）——
 *       它上面的方块被搬进投影维度，地皮槽位就是"方块现在住在哪"；</li>
 *   <li><b>创建即广播</b>（{@link SyncPhysicalBodyCreate}）——
 *       让客户端立刻建出运动学镜像。</li>
 * </ol>
 *
 * <h2>为什么"创建"和"存档"是两个分离的路径</h2>
 * 构造只做<b>运行时</b>的事（分配地皮、广播）；存档/读档走
 * {@link #saveToTag()} / {@link #loadFromTag}。区别很关键：
 * <ul>
 *   <li>读档时要额外恢复 {@code projectionAABB}/{@code worldAABB} 与<b>槽位号</b>
 *       —— 槽位必须沿用存档里的值，否则地皮会被重新分配到别处，
 *       而方块还在原槽位，表现为"读档后船变空壳"；</li>
 *   <li>读档还会调用 {@code ProjectionManager.readProjectionData} 把方块从地皮<b>读回体内</b>；</li>
 *   <li>非太空维度的体读档后要 {@code setPhysicalBodyLocked(true)} ——
 *       地表上的建筑"落地生根"，否则存档后会被重力推走。</li>
 * </ul>
 *
 * <p><b>照抄保留的一处 MPS 怪癖</b>：{@code ServerPhysicalBody(Level, pos, rotation)}
 * 委托给 {@code (ResourceLocation, pos, rotation)}（那里已经发过一次创建包），
 * 委托返回后又<b>再发一次</b> —— 同一个体发了两份创建包。
 * 客户端的 {@code addPhysicalBody} 按实例判重（不是按 uuid），
 * 所以这条路径会在客户端建出<b>两个同 uuid 的镜像</b>。
 * 本类照抄不删（用户要求字面照抄），此处记录以便 S6 决定是否修正。</p>
 */
public class ServerPhysicalBody extends PhysicalBody {

    private final ProjectionManager.Projection projection;

    public ServerPhysicalBody(Vector3d pos, Quaterniond rotation) {
        super(pos, rotation);
        this.projection = ProjectionManager.createNewProjection(this);
        PacketDistributor.sendToAllPlayers(new SyncPhysicalBodyCreate(this));
    }

    public ServerPhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation) {
        super(level, pos, rotation);
        this.projection = ProjectionManager.createNewProjection(this);
        PacketDistributor.sendToAllPlayers(new SyncPhysicalBodyCreate(this));
    }

    public ServerPhysicalBody(Level level, Vector3d pos, Quaterniond rotation) {
        this(level.dimension().location(), pos, rotation);
        // 照抄保留：这里会发第二份创建包（见类注释的怪癖记录）
        PacketDistributor.sendToAllPlayers(new SyncPhysicalBodyCreate(this));
    }

    public ServerPhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation, UUID uuid) {
        super(level, pos, rotation, uuid);
        this.projection = ProjectionManager.createNewProjection(this);
        PacketDistributor.sendToAllPlayers(new SyncPhysicalBodyCreate(this));
    }

    /** 读档路径：沿用存档里的槽位号（见类注释，不能重新分配）。 */
    public ServerPhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation, UUID uuid, int slot) {
        super(level, pos, rotation, uuid);
        this.projection = ProjectionManager.createNewProjection(this, slot);
        PacketDistributor.sendToAllPlayers(new SyncPhysicalBodyCreate(this));
    }

    /** 每 tick 钩子（服务端物理体目前无额外逻辑，留给子类）。 */
    public void tick(Level level) {
    }

    /**
     * 读档重建：位姿/速度/AABB/槽位全部从 NBT 恢复，再把方块从地皮读回体内。
     *
     * <p>顺序不能换：先 {@code addPhysicalBody}（建刚体与碰撞体）→ 再设速度
     * （否则速度会被刚体创建时的初值覆盖）→ 最后读地皮。</p>
     */
    public static ServerPhysicalBody loadFromTag(CompoundTag tag, ServerPhysicalWorld physicalWorld) {
        UUID uuid = tag.getUUID("UUID");
        Vector3d pos = new Vector3d(tag.getDouble("pos_x"), tag.getDouble("pos_y"), tag.getDouble("pos_z"));
        Quaterniond rotation = new Quaterniond(tag.getDouble("rot_x"), tag.getDouble("rot_y"),
                tag.getDouble("rot_z"), tag.getDouble("rot_w"));
        ResourceLocation level = ResourceLocation.parse(tag.getString("level"));
        int slot = tag.getInt("slot");
        ServerPhysicalBody body = new ServerPhysicalBody(level, pos, rotation, uuid, slot);
        body.projectionAABB
                .setMin(tag.getInt("proj_min_x"), tag.getInt("proj_min_y"), tag.getInt("proj_min_z"))
                .setMax(tag.getInt("proj_max_x"), tag.getInt("proj_max_y"), tag.getInt("proj_max_z"));
        body.worldAABB
                .setMin(tag.getDouble("world_min_x"), tag.getDouble("world_min_y"), tag.getDouble("world_min_z"))
                .setMax(tag.getDouble("world_max_x"), tag.getDouble("world_max_y"), tag.getDouble("world_max_z"));
        physicalWorld.addPhysicalBody(body);
        body.setLinSpeed(new Vector3d(tag.getDouble("linvel_x"), tag.getDouble("linvel_y"),
                tag.getDouble("linvel_z")));
        body.setAngSpeed(new Vector3d(tag.getDouble("angvel_x"), tag.getDouble("angvel_y"),
                tag.getDouble("angvel_z")));
        ProjectionManager.readProjectionData(body);
        // 非太空维度 = 地表：建筑落地生根（见类注释）
        if (!ServerSpaceWorld.isSpaceWorld(level)) {
            physicalWorld.setPhysicalBodyLocked(body, true);
        }
        return body;
    }

    /** 存档：位姿/速度/AABB/槽位全写进 NBT。 */
    public CompoundTag saveToTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", this.getUuid());
        tag.putString("level", this.getLevel().toString());
        Vector3d pos = this.getPos();
        tag.putDouble("pos_x", pos.x);
        tag.putDouble("pos_y", pos.y);
        tag.putDouble("pos_z", pos.z);
        Quaterniond rotation = this.getRotation();
        tag.putDouble("rot_x", rotation.x);
        tag.putDouble("rot_y", rotation.y);
        tag.putDouble("rot_z", rotation.z);
        tag.putDouble("rot_w", rotation.w);
        Vector3d linvel = this.getLinvel();
        tag.putDouble("linvel_x", linvel.x);
        tag.putDouble("linvel_y", linvel.y);
        tag.putDouble("linvel_z", linvel.z);
        Vector3d angvel = this.getAngvel();
        tag.putDouble("angvel_x", angvel.x);
        tag.putDouble("angvel_y", angvel.y);
        tag.putDouble("angvel_z", angvel.z);
        tag.putInt("slot", this.projection.getSlot());
        tag.putInt("proj_min_x", this.projectionAABB.minX);
        tag.putInt("proj_min_y", this.projectionAABB.minY);
        tag.putInt("proj_min_z", this.projectionAABB.minZ);
        tag.putInt("proj_max_x", this.projectionAABB.maxX);
        tag.putInt("proj_max_y", this.projectionAABB.maxY);
        tag.putInt("proj_max_z", this.projectionAABB.maxZ);
        tag.putDouble("world_min_x", this.worldAABB.minX);
        tag.putDouble("world_min_y", this.worldAABB.minY);
        tag.putDouble("world_min_z", this.worldAABB.minZ);
        tag.putDouble("world_max_x", this.worldAABB.maxX);
        tag.putDouble("world_max_y", this.worldAABB.maxY);
        tag.putDouble("world_max_z", this.worldAABB.maxZ);
        return tag;
    }

    public ProjectionManager.Projection getProjection() {
        return this.projection;
    }
}
