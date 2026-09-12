package com.mss.polymech.client.physics;

import com.mss.polymech.network.PhysicsBodySyncPacket;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端物理体注册表：保存服务端同步来的物理体（方块快照 + 变换），供渲染器绘制。
 *
 * <p>位置/旋转做客户端插值：服务端每 tick 发一次 UPDATE，渲染时按 partialTick 在
 * 上一次与本次变换之间插值，避免"一格一格跳"。</p>
 */
public final class ClientPhysicsWorld {

    private static final Map<Long, ClientBody> BODIES = new HashMap<>();

    private ClientPhysicsWorld() {
    }

    /** 客户端物理体。 */
    public static final class ClientBody {
        private final long id;
        private final List<BlockEntry> blocks;
        private double x, y, z;
        private double prevX, prevY, prevZ;
        private float qx, qy, qz, qw;
        private float prevQx, prevQy, prevQz, prevQw;
        /** 上一个 tick 的位置（用于"随船携带"：把物理体的每 tick 位移传给站在上面的玩家）。 */
        private double lastTickX, lastTickY, lastTickZ;
        private double deltaX, deltaY, deltaZ;
        /** 服务端同步来的线速度（m/s）。接触求解要用：零速度的"瞬移墙"会把玩家弹得乱七八糟。 */
        private double vx, vy, vz;
        /** 服务端同步来的角速度（rad/s）：客户端 DYNAMIC 刚体靠它自己转，碰撞体姿态才会跟着船转。 */
        private double avx, avy, avz;

        /**
         * 渲染用的"客户端刚体自己的状态"。
         *
         * <p>这是 space(MPS) 平滑的关键：客户端物理世界本地按 100Hz 积分，
         * 渲染直接取刚体的变换，网络包（20Hz）只作为修正去拉回权威位置/速度/角速度。
         * 若改成"两个网络包之间插值"，无论怎么插都只有 20Hz 的信息量 ——
         * 表现就是移动一抽一抽、姿态一跳一跳。</p>
         */
        private boolean physValid;
        private double physX, physY, physZ;
        private double prevPhysX, prevPhysY, prevPhysZ;
        private float physQx = 0.0f, physQy = 0.0f, physQz = 0.0f, physQw = 1.0f;
        private float prevPhysQx = 0.0f, prevPhysQy = 0.0f, prevPhysQz = 0.0f, prevPhysQw = 1.0f;

        public ClientBody(long id, double x, double y, double z,
                          float qx, float qy, float qz, float qw,
                          List<BlockEntry> blocks) {
            this.id = id;
            this.blocks = blocks;
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.z = this.prevZ = z;
            this.lastTickX = x;
            this.lastTickY = y;
            this.lastTickZ = z;
            this.qx = this.prevQx = qx;
            this.qy = this.prevQy = qy;
            this.qz = this.prevQz = qz;
            this.qw = this.prevQw = qw;
        }

        public long id() {
            return id;
        }

        public List<BlockEntry> blocks() {
            return blocks;
        }

        /** 应用服务端同步来的线速度。 */
        public void applyVelocity(double nvx, double nvy, double nvz) {
            this.vx = nvx;
            this.vy = nvy;
            this.vz = nvz;
        }

        public double vx() {
            return vx;
        }

        public double vy() {
            return vy;
        }

        public double vz() {
            return vz;
        }

        /** 应用服务端同步来的角速度。 */
        public void applyAngularVelocity(double nax, double nay, double naz) {
            this.avx = nax;
            this.avy = nay;
            this.avz = naz;
        }

        public double avx() {
            return avx;
        }

        public double avy() {
            return avy;
        }

        public double avz() {
            return avz;
        }

        /** 步进前：把当前物理状态记为"上一帧"（渲染插值起点）。 */
        public void snapshotPhysics(double px, double py, double pz,
                                    float nqx, float nqy, float nqz, float nqw) {
            this.prevPhysX = px;
            this.prevPhysY = py;
            this.prevPhysZ = pz;
            this.prevPhysQx = nqx;
            this.prevPhysQy = nqy;
            this.prevPhysQz = nqz;
            this.prevPhysQw = nqw;
            this.physValid = true;
        }

        /** 步进后：更新本帧物理状态。 */
        public void updatePhysics(double px, double py, double pz,
                                  float nqx, float nqy, float nqz, float nqw) {
            // 最短弧：q 与 −q 表示同一旋转，直接分量插值会"绕远路"转一大圈
            float dot = this.prevPhysQx * nqx + this.prevPhysQy * nqy
                    + this.prevPhysQz * nqz + this.prevPhysQw * nqw;
            if (dot < 0.0f) {
                nqx = -nqx;
                nqy = -nqy;
                nqz = -nqz;
                nqw = -nqw;
            }
            this.physX = px;
            this.physY = py;
            this.physZ = pz;
            this.physQx = nqx;
            this.physQy = nqy;
            this.physQz = nqz;
            this.physQw = nqw;
        }

        /** 本地预测：移除一个方块（服务端很快会回发权威快照覆盖）。 */
        public void predictBreak(int dx, int dy, int dz) {
            blocks.removeIf(e -> e.dx() == dx && e.dy() == dy && e.dz() == dz);
        }

        /** 本地预测：放置一个方块。 */
        public void predictPlace(int dx, int dy, int dz, BlockState state) {
            for (BlockEntry e : blocks) {
                if (e.dx() == dx && e.dy() == dy && e.dz() == dz) {
                    return;
                }
            }
            blocks.add(new BlockEntry((short) dx, (short) dy, (short) dz, state));
        }

        void applyUpdate(double nx, double ny, double nz,
                         float nqx, float nqy, float nqz, float nqw) {
            // 四元数双重覆盖：q 与 −q 表示的是同一个旋转。服务端物理积分跨过符号边界时，
            // 若原样存下，客户端按分量插值就会"绕远路"转一大圈 ——
            // 表现正是"物理体上的方块朝向一直在变/乱转"。
            // 这里在入口先取最短弧（等价于 space 的 MathUtil.slerpSafe：dot < 0 时目标取负）。
            float dot = this.qx * nqx + this.qy * nqy + this.qz * nqz + this.qw * nqw;
            if (dot < 0.0f) {
                nqx = -nqx;
                nqy = -nqy;
                nqz = -nqz;
                nqw = -nqw;
            }
            this.deltaX = nx - this.lastTickX;
            this.deltaY = ny - this.lastTickY;
            this.deltaZ = nz - this.lastTickZ;
            this.lastTickX = nx;
            this.lastTickY = ny;
            this.lastTickZ = nz;
            this.prevX = this.x;
            this.prevY = this.y;
            this.prevZ = this.z;
            this.prevQx = this.qx;
            this.prevQy = this.qy;
            this.prevQz = this.qz;
            this.prevQw = this.qw;
            this.x = nx;
            this.y = ny;
            this.z = nz;
            this.qx = nqx;
            this.qy = nqy;
            this.qz = nqz;
            this.qw = nqw;
        }

        public float qx() {
            return qx;
        }

        public float qy() {
            return qy;
        }

        public float qz() {
            return qz;
        }

        public float qw() {
            return qw;
        }

        /** 本 tick 的位置（服务端最近一次同步的权威值）。 */
        public double tickX() {
            return lastTickX;
        }

        public double tickY() {
            return lastTickY;
        }

        public double tickZ() {
            return lastTickZ;
        }

        /** 上一次网络更新带来的位移（用于随船携带）。 */
        public double deltaX() {
            return deltaX;
        }

        public double deltaY() {
            return deltaY;
        }

        public double deltaZ() {
            return deltaZ;
        }


        public double renderX(float partialTick) {
            if (physValid) {
                return prevPhysX + (physX - prevPhysX) * partialTick;
            }
            return prevX + (x - prevX) * partialTick;
        }

        public double renderY(float partialTick) {
            if (physValid) {
                return prevPhysY + (physY - prevPhysY) * partialTick;
            }
            return prevY + (y - prevY) * partialTick;
        }

        public double renderZ(float partialTick) {
            if (physValid) {
                return prevPhysZ + (physZ - prevPhysZ) * partialTick;
            }
            return prevZ + (z - prevZ) * partialTick;
        }

        /** 返回插值后的四元数（x,y,z,w），写入 out。优先用客户端刚体自己的状态。 */
        public void renderRotation(float partialTick, float[] out) {
            float ax;
            float ay;
            float az;
            float aw;
            float bx;
            float by;
            float bz;
            float bw;
            if (physValid) {
                ax = prevPhysQx;
                ay = prevPhysQy;
                az = prevPhysQz;
                aw = prevPhysQw;
                bx = physQx;
                by = physQy;
                bz = physQz;
                bw = physQw;
            } else {
                ax = prevQx;
                ay = prevQy;
                az = prevQz;
                aw = prevQw;
                bx = qx;
                by = qy;
                bz = qz;
                bw = qw;
            }
            // 简单线性插值 + 归一化（量级小、连续，足够平滑）
            float nx = ax + (bx - ax) * partialTick;
            float ny = ay + (by - ay) * partialTick;
            float nz = az + (bz - az) * partialTick;
            float nw = aw + (bw - aw) * partialTick;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw);
            if (len < 1.0e-6f) {
                nx = 0;
                ny = 0;
                nz = 0;
                nw = 1;
                len = 1;
            }
            out[0] = nx / len;
            out[1] = ny / len;
            out[2] = nz / len;
            out[3] = nw / len;
        }
    }

    /** 方块条目：局部整数坐标 + 方块状态。 */
    public record BlockEntry(short dx, short dy, short dz, BlockState state) {
    }

    public static Collection<ClientBody> bodies() {
        return BODIES.values();
    }

    public static int size() {
        return BODIES.size();
    }

    /** 按 id 取刚体（客户端预测用）。 */
    public static ClientBody body(long id) {
        return BODIES.get(id);
    }

    public static void accept(PhysicsBodySyncPacket packet) {
        switch (packet.action()) {
            case CREATE -> {
                List<BlockEntry> blocks = new ArrayList<>(packet.blocks().size());
                for (PhysicsBodySyncPacket.BlockEntry entry : packet.blocks()) {
                    blocks.add(new BlockEntry(entry.dx(), entry.dy(), entry.dz(),
                            PhysicsBodySyncPacket.stateFrom(entry.stateId())));
                }
                ClientBody created = new ClientBody(packet.bodyId(),
                        packet.x(), packet.y(), packet.z(),
                        packet.qx(), packet.qy(), packet.qz(), packet.qw(), blocks);
                created.applyVelocity(packet.vx(), packet.vy(), packet.vz());
                created.applyAngularVelocity(packet.avx(), packet.avy(), packet.avz());
                BODIES.put(packet.bodyId(), created);
            }
            case UPDATE -> {
                ClientBody body = BODIES.get(packet.bodyId());
                if (body != null) {
                    body.applyUpdate(packet.x(), packet.y(), packet.z(),
                            packet.qx(), packet.qy(), packet.qz(), packet.qw());
                    body.applyVelocity(packet.vx(), packet.vy(), packet.vz());
                    body.applyAngularVelocity(packet.avx(), packet.avy(), packet.avz());
                }
            }
            case REMOVE -> BODIES.remove(packet.bodyId());
        }
    }

    /** 退出世界时清空。 */
    public static void clear() {
        BODIES.clear();
    }
}
