package com.mss.polymech.mps.kelvin.physical.celestial_body;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mss.polymech.mps.kelvin.physical.CelestialBodyForce;
import com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 天体 —— <b>与 {@code org.cn_grass_block.kelvin.physical.celestial_body.CelestialBody}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 一个天体（行星/恒星/卫星/小行星）的<b>身份 + 力学状态</b>：名字、位置、姿态、半径、
 * 可算性、速度、自转速度、质量，以及"当前受哪些力"的力池。
 *
 * <h2>核心设计：所有"改动"都排队，不直接改（照 space 0.1.3）</h2>
 * {@link #moveTo}/{@link #rotateTo}/{@link #setRotateSpeed}/{@link #addForce} 都长这样：
 * <pre>
 * if (spaceWorld == null) 直接改;
 * else spaceWorld.putOperation(new OperationBuffer(类型, this, 参数));
 * </pre>
 * <b>为什么必须这样</b>：天体世界由<b>物理线程</b>每 10ms 推进，而这些都是<b>游戏线程</b>
 * （方块/实体/事件）调用的。直接改 {@code pos} 就是与积分器并发写同一个 {@code Vector3d}
 * —— 轻则一帧跳变，重则撕裂读。入队后由物理线程在步进前统一 flush，
 * 等于把改动<b>交接给拥有它的线程</b>。{@code *Direct} 变体是给物理线程自己用的，
 * <b>不要在游戏线程调</b>。
 *
 * <h2>其余形态要点（别改）</h2>
 * <ul>
 *   <li>{@code pos}/{@code old_pos}、{@code rotate}/{@code old_rotate} +
 *       {@link #getSmoothPos}/{@link #getSmoothRotate}：物理 20Hz、渲染 60fps，
 *       渲染必须插值（lerp/slerp），否则天体一顿一顿。</li>
 *   <li>力池每个访问都 {@code synchronized}，{@link #getForcesSnapshot} 返回<b>副本</b>：
 *       它被两个线程读写。</li>
 *   <li>{@link #addForceDirect} 用 {@code contains}/{@code indexOf} → {@code set}：
 *       <b>同名即替换</b>（与 {@link CelestialBodyForce#equals} 只比名字配套），否则力会叠加。</li>
 *   <li>{@link #init} 与构造分离：构造只给"静态身份"，可算性与力学量由数据包/存档再喂。</li>
 * </ul>
 *
 * <p><b>一处刻意保留的原样行为</b>：{@link #forceTimeUpdata} 遍历时把"超时的那个"记进一个变量
 * （会被后续覆盖），最后只移除一次 —— 即<b>一次只清一个过期力</b>。多个力同时过期时分几步清完。
 * 对结果无影响，但请理解为"惰性清理"，别"顺手"改成 {@code removeIf}。</p>
 */
public class CelestialBody {

    /** 所属维度 id（字符串形态，MPS 用 String 而非 ResourceLocation）。 */
    public final String level;
    /** 所属天体世界；为 null 时所有改动走 {@code *Direct}（见类注释）。 */
    public SpaceWorld spaceWorld;
    private final String name;
    private final Vector3d pos;
    private final Vector3d old_pos;
    private final Quaterniond rotate;
    private final Quaterniond old_rotate;
    private double radius;
    /** 附加数据（数据包/玩法自定义）；解析失败不致命。 */
    public CompoundTag Tag = new CompoundTag();
    private boolean compute;
    private final Vector3d speed = new Vector3d();
    private double rotate_speed = 0.0;
    private double mass;
    private final List<CelestialBodyForce> forces = new ArrayList<>();

    public CelestialBody(String level, String name, Vector3d pos, Quaterniond rotate, double radius) {
        this.level = level;
        this.name = name;
        this.pos = new Vector3d(pos);
        this.old_pos = new Vector3d(pos);
        this.rotate = new Quaterniond(rotate);
        this.old_rotate = new Quaterniond(rotate);
        this.radius = radius;
    }

    // ==================== 位姿（读） ====================

    public Vector3d getPos() {
        return new Vector3d(this.pos);
    }

    /** 渲染插值用：上一 tick 位置 → 当前位置（lerp）。 */
    public Vector3d getSmoothPos(float partialTick) {
        return new Vector3d(this.old_pos).lerp(this.pos, partialTick);
    }

    public Quaterniond getRotate() {
        return new Quaterniond(this.rotate);
    }

    /** 渲染插值用：姿态用 slerp（四元数不能线性插值）。 */
    public Quaterniond getSmoothRotate(float partialTick) {
        return new Quaterniond(this.old_rotate).slerp(this.rotate, partialTick);
    }

    // ==================== 位姿（写：排队 or 直改） ====================

    public void moveTo(Vector3d vector3d) {
        if (this.spaceWorld == null) {
            this.moveToDirect(vector3d);
        } else {
            this.spaceWorld.putOperation(new SpaceWorld.OperationBuffer(
                    SpaceWorld.OperationBuffer.Type.MOVE_TO, this, new Vector3d(vector3d)));
        }
    }

    /** 物理线程用：立即改（{@code old_pos} 留给渲染插值）。 */
    public void moveToDirect(Vector3d vector3d) {
        this.old_pos.set(this.pos);
        this.pos.set(vector3d);
    }

    public void moveTo(double x, double y, double z) {
        this.moveTo(new Vector3d(x, y, z));
    }

    public void rotateTo(Quaterniond rotate) {
        if (this.spaceWorld == null) {
            this.rotateToDirect(rotate);
        } else {
            this.spaceWorld.putOperation(new SpaceWorld.OperationBuffer(
                    SpaceWorld.OperationBuffer.Type.ROTATE_TO, this, new Quaterniond(rotate)));
        }
    }

    public void rotateToDirect(Quaterniond rotate) {
        this.old_rotate.set(this.rotate);
        this.rotate.set(rotate);
    }

    public void rotateTo(double x, double y, double z, double w) {
        this.rotateTo(new Quaterniond(x, y, z, w));
    }

    // ==================== 身份与力学量 ====================

    /** 由数据包/存档喂入可算性与力学量（构造只给静态身份，见类注释）。 */
    public void init(boolean compute, Vector3d speed, double rotate_speed, double mass) {
        this.compute = compute;
        this.speed.set(speed);
        this.rotate_speed = rotate_speed;
        this.mass = mass;
    }

    public Vector3d speed() {
        return this.speed;
    }

    public double getRotateSpeed() {
        return this.rotate_speed;
    }

    public void setRotateSpeed(double rotateSpeed) {
        if (this.spaceWorld == null) {
            this.rotate_speed = rotateSpeed;
        } else {
            this.spaceWorld.putOperation(new SpaceWorld.OperationBuffer(
                    SpaceWorld.OperationBuffer.Type.SET_ROTATE_SPEED, this, rotateSpeed));
        }
    }

    /** 每步由天体世界调用（子类可覆写做自己的逻辑）。 */
    public void tick() {
    }

    // ==================== 力池 ====================

    public boolean addForce(CelestialBodyForce force) {
        if (this.spaceWorld == null) {
            return this.addForceDirect(force);
        }
        this.spaceWorld.putOperation(new SpaceWorld.OperationBuffer(
                SpaceWorld.OperationBuffer.Type.ADD_FORCE, this, force));
        return true;
    }

    /**
     * 物理线程用：加力。<b>同名即替换</b>（{@link CelestialBodyForce} 的相等只看名字）——
     * 否则"这一步又给了一次推力"会变成叠加、推力越按越大。
     */
    public boolean addForceDirect(CelestialBodyForce force) {
        synchronized (this.forces) {
            if (this.forces.contains(force)) {
                this.forces.set(this.forces.indexOf(force), force);
                return false;
            }
            return this.forces.add(force);
        }
    }

    public boolean removeForce(CelestialBodyForce force) {
        synchronized (this.forces) {
            return this.forces.remove(force);
        }
    }

    public List<CelestialBodyForce> getForcesSnapshot() {
        synchronized (this.forces) {
            return new ArrayList<>(this.forces);
        }
    }

    public void removeAllForce() {
        synchronized (this.forces) {
            this.forces.clear();
        }
    }

    /** 把力池合成一股（名字 {@code all-force}，时长 0 —— 只作向量用途）。 */
    public CelestialBodyForce getAllForce() {
        CelestialBodyForce allForce = new CelestialBodyForce("all-force", 0.0, 0.0, 0.0, 0.0);
        for (CelestialBodyForce force : this.forces) {
            allForce.add(force);
        }
        return allForce;
    }

    /**
     * 消耗力池的时间并清理过期力。
     *
     * <p><b>照 space 原样</b>：变量会被覆盖，最后只移除一次 —— 即一次只清一个。
     * 见类注释"一处刻意保留的原样行为"。</p>
     */
    public void forceTimeUpdata(double time) {
        CelestialBodyForce rforce = null;
        for (CelestialBodyForce force : this.forces) {
            force.doTime(time);
            if (force.getTime() < 0.0) {
                rforce = force;
            }
        }
        this.removeForce(rforce);
    }

    /** 质量归一后的加速度；质量为 0（如恒星核）时恒为零向量。 */
    public Vector3d getAcceleration() {
        if (this.mass == 0.0) {
            return new Vector3d(0.0, 0.0, 0.0);
        }
        CelestialBodyForce all = this.getAllForce();
        return new Vector3d(all.getX() / this.mass, all.getY() / this.mass, all.getZ() / this.mass);
    }

    // ==================== 序列化 ====================

    @Override
    public String toString() {
        return this.name;
    }

    /** 相等只按名字（与力池的"同名替换"同一套语义）。 */
    @Override
    public int hashCode() {
        return this.name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CelestialBody celestialBody && celestialBody.name.equals(this.name);
    }

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("class", this.getClass().getName());
        json.addProperty("compute", this.compute);
        JsonArray pos = new JsonArray();
        pos.add(this.pos.x());
        pos.add(this.pos.y());
        pos.add(this.pos.z());
        json.add("pos", pos);
        json.addProperty("mass", this.mass);
        JsonArray speed = new JsonArray();
        speed.add(this.speed.x());
        speed.add(this.speed.y());
        speed.add(this.speed.z());
        json.add("speed", speed);
        JsonArray rotate = new JsonArray();
        rotate.add(this.rotate.x);
        rotate.add(this.rotate.y);
        rotate.add(this.rotate.z);
        rotate.add(this.rotate.w);
        json.add("rotate", rotate);
        json.addProperty("rotate_speed", this.rotate_speed);
        JsonObject forceJson = new JsonObject();
        for (CelestialBodyForce force : this.forces) {
            forceJson.add(force.getName(), force.toJsonObject());
        }
        json.add("force", forceJson);
        json.addProperty("tag", this.Tag.toString());
        return json;
    }

    public void ReadDataFromJsonObject(JsonObject jsonObject) {
        this.compute = jsonObject.get("compute").getAsBoolean();
        this.mass = jsonObject.get("mass").getAsDouble();
        List<JsonElement> posData = jsonObject.get("pos").getAsJsonArray().asList();
        if (posData.size() == 3) {
            this.pos.set(new Vector3d(posData.get(0).getAsDouble(),
                    posData.get(1).getAsDouble(), posData.get(2).getAsDouble()));
        }
        List<JsonElement> speedData = jsonObject.get("speed").getAsJsonArray().asList();
        if (speedData.size() == 3) {
            this.speed.set(new Vector3d(speedData.get(0).getAsDouble(),
                    speedData.get(1).getAsDouble(), speedData.get(2).getAsDouble()));
        }
        List<JsonElement> rotateData = jsonObject.get("rotate").getAsJsonArray().asList();
        if (rotateData.size() == 4) {
            this.rotate.set(new Quaterniond(rotateData.get(0).getAsDouble(),
                    rotateData.get(1).getAsDouble(), rotateData.get(2).getAsDouble(),
                    rotateData.get(3).getAsDouble()));
        }
        this.rotate_speed = jsonObject.get("rotate_speed").getAsDouble();
        JsonObject forceJson = jsonObject.get("force").getAsJsonObject();
        for (String key : forceJson.keySet()) {
            CelestialBodyForce force = CelestialBodyForce.getFromJsonObject(forceJson.getAsJsonObject(key));
            if (force != null) {
                this.addForceDirect(force);
            }
        }
        try {
            this.Tag = TagParser.parseTag(jsonObject.get("tag").getAsString());
        } catch (Exception ignored) {
            // 附加数据坏了不该让天体加载失败（照 space 原样吞掉）
        }
    }

    /** 网络编码：只发"身份"（名字/位姿/半径/维度），不发力学状态。 */
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(this.name);
        buffer.writeDouble(this.pos.x());
        buffer.writeDouble(this.pos.y());
        buffer.writeDouble(this.pos.z());
        buffer.writeDouble(this.rotate.x());
        buffer.writeDouble(this.rotate.y());
        buffer.writeDouble(this.rotate.z());
        buffer.writeDouble(this.rotate.w());
        buffer.writeDouble(this.radius);
        buffer.writeUtf(this.level);
    }

    public static CelestialBody decode(FriendlyByteBuf buffer) {
        String name = buffer.readUtf();
        Vector3d pos = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Quaterniond rotate = new Quaterniond(buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
        double radius = buffer.readDouble();
        return new CelestialBody(buffer.readUtf(), name, pos, rotate, radius);
    }

    // ==================== 访问器 ====================

    public String getName() {
        return this.name;
    }

    public double getRadius() {
        return this.radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public boolean isCompute() {
        return this.compute;
    }

    public void setCompute(boolean compute) {
        this.compute = compute;
    }

    public double getMass() {
        return this.mass;
    }

    public void setMass(double mass) {
        this.mass = mass;
    }
}
