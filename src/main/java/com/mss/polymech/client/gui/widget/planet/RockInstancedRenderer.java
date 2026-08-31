package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.techtree.Polyhedron;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 岩石带实例化渲染：静态网格 VBO + 每实例矩阵/颜色 VBO，每带最多 2 次 instanced draw。
 * 若着色器未就绪或初始化失败，退回 {@link OrbitalDrawer#drawScatteredRocksBatched}。
 */
final class RockInstancedRenderer {
    private static final int FLOATS_PER_INSTANCE = 20; // 4 color + 16 matrix
    private static final int INSTANCE_STRIDE = FLOATS_PER_INSTANCE * 4;
    private static final int STATIC_STRIDE = 24; // 3 pos + 3 normal

    private final SolarSystemView v;
    private final Polyhedron highMesh = Polyhedron.rock(0L, 0.8f, 1);
    private final Polyhedron lowMesh = Polyhedron.rock(0L, 0.8f, 0);

    private int vao = -1;
    private int staticVbo = -1;
    private int instanceVbo = -1;
    private int highVertexCount, lowVertexCount;
    private int matLoc0 = -1;
    private boolean useArbDivisor = false;
    private boolean setupTried = false;
    private boolean ready = false;

    private final ByteBuffer instanceBuf = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());
    private final Matrix4f tmpMat = new Matrix4f();

    RockInstancedRenderer(SolarSystemView v) { this.v = v; }

    boolean ready() {
        if (setupTried) return ready;
        setupTried = true;
        try {
            setup();
            ready = true;
        } catch (RuntimeException e) {
            ready = false;
        }
        return ready;
    }

    private void setup() {
        ShaderInstance sh = PlanetShaders.rockShader();
        if (sh == null) throw new IllegalStateException("rock shader not ready");
        if (!GL.getCapabilities().OpenGL31)
            throw new IllegalStateException("instanced rendering not supported on this GL context");
        if (GL.getCapabilities().OpenGL33) {
            useArbDivisor = false;
        } else if (GL.getCapabilities().GL_ARB_instanced_arrays) {
            useArbDivisor = true;
        } else {
            throw new IllegalStateException("glVertexAttribDivisor not available");
        }
        int prog = sh.getId();
        matLoc0 = GL20.glGetAttribLocation(prog, "InstanceMat");
        if (matLoc0 < 0) throw new IllegalStateException("InstanceMat attribute not found");

        vao = GlStateManager._glGenVertexArrays();
        staticVbo = GlStateManager._glGenBuffers();
        instanceVbo = GlStateManager._glGenBuffers();

        // 静态网格：高模在前，低模在后，交错 [px,py,pz, nx,ny,nz]
        float[] highData = buildStaticMesh(highMesh);
        float[] lowData = buildStaticMesh(lowMesh);
        highVertexCount = highMesh.faces.length * 3;
        lowVertexCount = lowMesh.faces.length * 3;
        ByteBuffer staticBuf = ByteBuffer.allocateDirect((highData.length + lowData.length) * 4).order(ByteOrder.nativeOrder());
        for (float f : highData) staticBuf.putFloat(f);
        for (float f : lowData) staticBuf.putFloat(f);
        staticBuf.flip();

        GlStateManager._glBindVertexArray(vao);

        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, staticVbo);
        RenderSystem.glBufferData(GL15.GL_ARRAY_BUFFER, staticBuf, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STATIC_STRIDE, 0L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STATIC_STRIDE, 12L);

        // 实例 VBO：Color(loc1) + InstanceMat0..3
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        ByteBuffer empty = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        RenderSystem.glBufferData(GL15.GL_ARRAY_BUFFER, empty, GL15.GL_DYNAMIC_DRAW);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, INSTANCE_STRIDE, 0L);
        setDivisor(1, 1);
        for (int i = 0; i < 4; i++) {
            int loc = matLoc0 + i;
            GL20.glEnableVertexAttribArray(loc);
            GL20.glVertexAttribPointer(loc, 4, GL11.GL_FLOAT, false, INSTANCE_STRIDE, 16L + i * 16L);
            setDivisor(loc, 1);
        }

        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static float[] buildStaticMesh(Polyhedron mesh) {
        int n = mesh.faces.length * 3 * 6;
        float[] data = new float[n];
        int p = 0;
        for (int[] f : mesh.faces) {
            for (int k = 0; k < 3; k++) {
                float[] vv = mesh.vertices[f[k]];
                data[p++] = vv[0];
                data[p++] = vv[1];
                data[p++] = vv[2];
                float l = (float) Math.sqrt(vv[0] * vv[0] + vv[1] * vv[1] + vv[2] * vv[2]);
                if (l < 1e-5f) l = 1;
                data[p++] = vv[0] / l;
                data[p++] = vv[1] / l;
                data[p++] = vv[2] / l;
            }
        }
        return data;
    }

    /** 渲染一整个带（按高/低模分两批 instanced draw）。 */
    void draw(float[][] particles) {
        if (!ready()) return;
        ShaderInstance sh = PlanetShaders.rockShader();
        if (sh == null) return;
        float fX = v.camera.focalX(), fZ = v.camera.focalZ();
        v.buildTransformMatrix(-fX, -fZ, 1, 0, 0, v.mvTmp);

        int highCount = fillInstanceBuffer(particles, false);
        if (highCount > 0) drawBatch(sh, highCount, 0, highVertexCount);
        int lowCount = fillInstanceBuffer(particles, true);
        if (lowCount > 0) drawBatch(sh, lowCount, highVertexCount, lowVertexCount);
    }

    private int fillInstanceBuffer(float[][] particles, boolean low) {
        instanceBuf.clear();
        int count = 0;
        for (int i = 0; i < particles.length; i++) {
            float[] p = particles[i];
            float sz = p[3];
            boolean isLow = (particles == v.kuiperPos) || (sz < 0.18f);
            if (isLow != low) continue;
            float angle = p[0] + v.simTime * 0.006f;
            float radius = p[1], yPos = p[2];
            float tiltA = p[4], tiltB = p[5];
            float cr = p[6], cg = p[7], cb = p[8];
            float wxCenter = (float) Math.cos(angle) * radius;
            float wzCenter = (float) Math.sin(angle) * radius;
            tmpMat.identity().translation(wxCenter, yPos, wzCenter).rotateY(tiltB).rotateZ(tiltA).scale(sz);
            instanceBuf.putFloat(cr).putFloat(cg).putFloat(cb).putFloat(1f);
            int pos = instanceBuf.position();
            tmpMat.get(instanceBuf);
            instanceBuf.position(pos + 64);
            count++;
        }
        instanceBuf.flip();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        RenderSystem.glBufferData(GL15.GL_ARRAY_BUFFER, instanceBuf, GL15.GL_DYNAMIC_DRAW);
        return count;
    }

    private void setDivisor(int index, int divisor) {
        if (useArbDivisor) {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        } else {
            GL33.glVertexAttribDivisor(index, divisor);
        }
    }

    private void drawBatch(ShaderInstance sh, int instanceCount, int firstVertex, int vertexCount) {
        GlStateManager._glBindVertexArray(vao);
        sh.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, v.mvTmp, RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
        sh.apply();
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, firstVertex, vertexCount, instanceCount);
        sh.clear();
        GlStateManager._glBindVertexArray(0);
    }

    void close() {
        if (vao >= 0) GlStateManager._glDeleteVertexArrays(vao);
        if (staticVbo >= 0) GlStateManager._glDeleteBuffers(staticVbo);
        if (instanceVbo >= 0) GlStateManager._glDeleteBuffers(instanceVbo);
        vao = staticVbo = instanceVbo = -1;
        ready = false;
        setupTried = false;
    }
}
