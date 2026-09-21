package com.mss.polymech.mps.kelvin.physical;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;

/**
 * 天体上的"具名持续力" —— <b>与
 * {@code org.cn_grass_block.kelvin.physical.CelestialBodyForce} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 承载"某个天体现在受着哪些力"的最小单元：一个名字、一个力向量、一个剩余时长。
 * 天体每步把力池里的力消耗掉（{@link #doTime}），并把池子交给物理体（推进器、引力）。
 *
 * <h2>为什么是"具名 + 时长"而不是单纯一个向量（照 space 0.1.3，理由是这两条）</h2>
 * <ol>
 *   <li><b>具名</b>：同一股力要能被"续期"而不是叠加。{@link #equals}/{@link #hashCode}
 *       只比名字 —— 于是"这个推进器这一步又给了一次力"是<b>替换</b>而不是再加一股，
 *       否则按住推进器时力池会无限膨胀，推力越按越大。</li>
 *   <li><b>带剩余时长</b>：力是"持续 {@code time} 秒"的，不是瞬时冲量。
 *       这样"这一步到底该施加多少"由消耗侧决定（每步扣步长），
 *       与 {@code RigidBody.Force} 的结算方式同构 —— 换步长不会改变等效冲量。</li>
 * </ol>
 *
 * <p><b>时间用 {@code Double.POSITIVE_INFINITY} 表示"永久力"</b>（引力就是这种：
 * {@code getGravitationForce} 传的就是它）。{@link #doTime} 对无穷大应当无影响 ——
 * 这一点在实现里靠"减一个有限值仍为无穷"自然成立，但<b>不要</b>把它当成"会扣完"。</p>
 */
public class CelestialBodyForce {

    private String name;
    private Vector3d force;
    private double time;

    public CelestialBodyForce(String name, double x, double y, double z, double time) {
        this.name = name;
        this.force = new Vector3d(x, y, z);
        this.time = time;
    }

    public CelestialBodyForce(String name, Vector3d force, double time) {
        this.name = name;
        this.force = new Vector3d(force);
        this.time = time;
    }

    public CelestialBodyForce() {
        this.name = "";
        this.force = new Vector3d();
        this.time = 0.0;
    }

    /** 累加另一股力的**向量**（名字与时长保持本体的；配合 {@link #equals} 的"同名替换"语义使用）。 */
    public void add(CelestialBodyForce other) {
        this.force.add(other.force);
    }

    /**
     * 把"沿本体朝向的推力"分解成世界向量。
     *
     * <p>基准方向是 {@code (0,0,1)} 再经姿态变换 —— 即天体的<b>局部 +Z</b>当作推力方向
     * （恒星/推进器朝前）。用局部轴而不是世界轴，姿态一转推力方向就跟着转。</p>
     */
    public static Vector3d decomposeForce(double forceMagnitude, Quaterniondc orientation) {
        Vector3d direction = new Vector3d(0.0, 0.0, 1.0);
        orientation.transform(direction);
        return direction.mul(forceMagnitude);
    }

    public Vector3d toVector3d() {
        return new Vector3d(this.force);
    }

    /** 消耗掉 {@code time} 秒（力池每步调一次）。永久力（无穷时长）不受影响。 */
    public void doTime(double time) {
        this.time -= time;
    }

    // ==================== 存档（与 MPS 的 JSON 形态一致） ====================

    public JsonObject toJsonObject() {
        JsonObject forceJson = new JsonObject();
        forceJson.addProperty("name", this.name);
        JsonArray force = new JsonArray();
        force.add(this.force.x());
        force.add(this.force.y());
        force.add(this.force.z());
        forceJson.add("force", force);
        forceJson.addProperty("time", this.time);
        return forceJson;
    }

    /** 从存档 JSON 还原；字段缺失/形状不对时返回 {@code null}（调用方跳过这一条）。 */
    public static CelestialBodyForce getFromJsonObject(JsonObject jsonObject) {
        if (jsonObject == null || !jsonObject.has("name") || !jsonObject.has("force")) {
            return null;
        }
        String name = jsonObject.get("name").getAsString();
        List<JsonElement> force = jsonObject.get("force").getAsJsonArray().asList();
        if (force.size() != 3 || !jsonObject.has("time")) {
            return null;
        }
        double x = force.get(0).getAsDouble();
        double y = force.get(1).getAsDouble();
        double z = force.get(2).getAsDouble();
        double time = jsonObject.get("time").getAsDouble();
        return new CelestialBodyForce(name, x, y, z, time);
    }

    /** 相等只按**名字**：同名即同一股力（续期/替换），见类注释第 1 条。 */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof CelestialBodyForce other && Objects.equals(this.name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.name);
    }

    @Override
    public String toString() {
        return "{name:\"%s\",x:%s,y:%s,z:%s,time:%s}".formatted(
                this.name, this.force.x(), this.force.y(), this.force.z(), this.time);
    }

    // ==================== 访问器 ====================

    public double getX() {
        return this.force.x();
    }

    public double getY() {
        return this.force.y();
    }

    public double getZ() {
        return this.force.z();
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Vector3d getForce() {
        return this.force;
    }

    public void setForce(Vector3d force) {
        this.force = force;
    }

    public double getTime() {
        return this.time;
    }

    public void setTime(double time) {
        this.time = time;
    }
}
