package com.mss.polymech.client.space;

import com.mss.polymech.Polymech;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * 简化版 UBO（Uniform Buffer Object），用于把天体数据传给屏幕空间大气着色器。
 *
 * <p>binding point 的分配/占用在同一个锁内原子完成，并同时检查当前 OpenGL 的真实绑定状态，
 * 避免与其他 mod / 原版渲染共用一个 binding point。更新/绑定前会再次检查当前 GL binding，
 * 避免被其他渲染代码改绑后失效。</p>
 */
public final class UniformBuffer {
    /** 查询索引绑定点的 target，等价于 GL_UNIFORM_BUFFER_BINDING。 */
    private static final int GL_UNIFORM_BUFFER_BINDING = 0x8A28;

    private static final Set<Integer> USED_BINDING_POINTS = new HashSet<>();

    private final int uboId;
    private final int bindingPoint;
    private final int size;
    private boolean loggedBind;

    public UniformBuffer(int size) {
        this(allocateBindingPoint(), size, true);
    }

    public UniformBuffer(int bindingPoint, int size) {
        this(bindingPoint, size, false);
    }

    private UniformBuffer(int bindingPoint, int size, boolean alreadyReserved) {
        if (size > GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE)) {
            throw new RuntimeException("UBO size exceeds GL_MAX_UNIFORM_BLOCK_SIZE: " + size);
        }
        int maxBindings = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        if (bindingPoint < 0 || bindingPoint >= maxBindings) {
            throw new IllegalArgumentException("Invalid binding point: " + bindingPoint);
        }

        synchronized (USED_BINDING_POINTS) {
            if (!alreadyReserved) {
                if (USED_BINDING_POINTS.contains(bindingPoint)) {
                    throw new IllegalStateException("Binding point " + bindingPoint + " is already in use");
                }
                if (isBindingPointUsedByGl(bindingPoint)) {
                    throw new IllegalStateException(
                            "Binding point " + bindingPoint + " is already bound to another GL buffer");
                }
                USED_BINDING_POINTS.add(bindingPoint);
            }
        }

        this.bindingPoint = bindingPoint;
        this.size = size;
        this.uboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.uboId);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, (long) size, GL31.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, bindingPoint, this.uboId);
    }

    /**
     * 选出一个当前未被本 mod 占用、且 GL 当前没有绑定其他 buffer 的 binding point。
     * 从高位开始寻找：原版/其他 mod 更习惯从 0 开始分配，反向查找可降低撞车概率。
     */
    private static int allocateBindingPoint() {
        int maxBindings = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        synchronized (USED_BINDING_POINTS) {
            for (int i = maxBindings - 1; i >= 0; i--) {
                if (!USED_BINDING_POINTS.contains(i) && !isBindingPointUsedByGl(i)) {
                    USED_BINDING_POINTS.add(i);
                    return i;
                }
            }
        }
        throw new RuntimeException("No available uniform buffer binding points");
    }

    private static boolean isBindingPointUsedByGl(int bindingPoint) {
        int[] boundBuffer = new int[1];
        GL30.glGetIntegeri_v(GL_UNIFORM_BUFFER_BINDING, bindingPoint, boundBuffer);
        return boundBuffer[0] != 0;
    }

    /** 保证当前 binding point 仍绑定着本 UBO（有些渲染路径会改绑其它 UBO）。 */
    private void ensureBinding() {
        int[] boundBuffer = new int[1];
        GL30.glGetIntegeri_v(GL_UNIFORM_BUFFER_BINDING, this.bindingPoint, boundBuffer);
        if (boundBuffer[0] != this.uboId) {
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, this.bindingPoint, this.uboId);
        }
    }

    public void update(FloatBuffer data) {
        update(data, false);
    }

    public void update(ByteBuffer data) {
        update(data, false);
    }

    public void update(FloatBuffer data, boolean autoFree) {
        ensureBinding();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.uboId);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, data);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        if (autoFree) MemoryUtil.memFree(data);
    }

    public void update(ByteBuffer data, boolean autoFree) {
        ensureBinding();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.uboId);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, data);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        if (autoFree) MemoryUtil.memFree(data);
    }

    public void bindToShader(int shaderProgramId, String blockName) {
        ensureBinding();
        int blockIndex = GL31.glGetUniformBlockIndex(shaderProgramId, blockName);
        if (!loggedBind) {
            Polymech.LOGGER.info("[poly_mech] UBO bind program={}, block={}, blockIndex={}, binding={}",
                    shaderProgramId, blockName, blockIndex, this.bindingPoint);
            loggedBind = true;
        }
        if (blockIndex < 0) {
            throw new IllegalStateException("Uniform block not found: " + blockName);
        }
        GL31.glUniformBlockBinding(shaderProgramId, blockIndex, this.bindingPoint);
    }

    /** 释放 UBO 并解绑 binding point。 */
    public void delete() {
        synchronized (USED_BINDING_POINTS) {
            USED_BINDING_POINTS.remove(this.bindingPoint);
        }
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, this.bindingPoint, 0);
        GL15.glDeleteBuffers(this.uboId);
    }

    public int getBindingPoint() {
        return bindingPoint;
    }
}
