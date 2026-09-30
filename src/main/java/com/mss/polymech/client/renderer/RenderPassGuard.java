package com.mss.polymech.client.renderer;

/**
 * 「共享 {@code BufferSource} 同一时刻只允许开一个通道」的守卫。
 *
 * <h2>为什么需要它（2026-09-29 实机崩溃换来的）</h2>
 * 牵引枪第二次重做后，右键抓住物理体**直接崩客户端**，报告的头部只有一句极不好读的话：
 * <pre>
 * java.lang.IllegalStateException: Not building!
 *   at BufferBuilder.ensureBuilding
 *   at PhysgunBeamRenderer.emit → segment → drawBeam → onRenderLevelStage
 * </pre>
 * 根因在 1.21.1 的 {@code MultiBufferSource.BufferSource.getBuffer}：
 * <pre>
 * BufferBuilder bb = this.startedBuilders.get(renderType);
 * if (bb != null &amp;&amp; !renderType.canConsolidateConsecutiveGeometry()) { this.endBatch(renderType, bb); bb = null; }
 * if (bb != null) return bb;
 * else {
 *     ByteBufferBuilder fixed = this.fixedBuffers.get(renderType);
 *     if (fixed != null) bb = new BufferBuilder(fixed, ...);
 *     else {
 *         if (this.lastSharedType != null) this.endBatch(this.lastSharedType);   // ←★ 把上一个"共享"通道提前 flush
 *         bb = new BufferBuilder(this.sharedBuffer, ...);
 *         this.lastSharedType = renderType;
 *     }
 *     this.startedBuilders.put(renderType, bb);
 *     return bb;
 * }
 * </pre>
 * 也就是说：<b>自定义（非 fixed）渲染通道共用一条 {@code sharedBuffer}，取第二个通道会把第一个 flush 掉</b>。
 * 你要是先 {@code getBuffer(A)} 再 {@code getBuffer(B)}，然后才回头往 A 写顶点，那个 builder 已经
 * {@code build()} 过了（不再 building）⇒ 上面那句 "Not building!"。
 *
 * <p>为什么"瞄准高亮"当时没崩：它是往 <b>B</b>（第二个通道）里写的，还开着；只有抓住后往 A 写才炸 ——
 * 于是症状表现为"一右键就崩"，看起来完全不像渲染通道的锅。</p>
 *
 * <h2>它怎么防</h2>
 * 一个三行的状态机：开通道时若已有通道开着 → 立刻抛一条<b>能读懂</b>的异常（点名是哪个通道、并说明原因），
 * 而不是让顶点写到一半才炸 {@code Not building!}。纯状态、零 MC 依赖 ⇒ 离线探针能直接判
 * （{@code PhysgunBeamShapeProbe} 第 10 节）。
 *
 * <p>抛异常时会先把状态清空（fail-open）：否则一旦某帧炸过，之后每一帧都会继续报"重入"，
 * 反而掩盖真正的第一个异常。</p>
 */
public final class RenderPassGuard {

    private static Object open;

    private RenderPassGuard() {
    }

    /** 开一个通道；已有通道开着（或重复开同一个）就抛。 */
    public static void open(Object channel) {
        Object current = open;
        if (current != null) {
            open = null;                    // fail-open：别让一次异常把后面每一帧都连坐
            throw new IllegalStateException("渲染通道重入：还开着 [" + current + "] 就开了 [" + channel
                    + "] —— 共享 BufferSource 同一时刻只允许一个非 fixed 的 builder，"
                    + "否则取第二个通道会把第一个 flush 掉，随后写入即 'Not building!'。"
                    + "正确写法：getBuffer → 写完 → endBatch，一个通道一趟。");
        }
        open = channel;
    }

    /** 关通道；没开、或关错了通道就抛。 */
    public static void close(Object channel) {
        Object current = open;
        open = null;
        if (current == null) {
            throw new IllegalStateException("关了一个没开的渲染通道：[" + channel + "]");
        }
        if (current != channel) {
            throw new IllegalStateException("关错了渲染通道：开着 [" + current + "]，却关 [" + channel + "]");
        }
    }

    /** 当前开着的通道（诊断/探针用）。 */
    public static Object current() {
        return open;
    }

    /** 只给测试用：清空状态。 */
    public static void reset() {
        open = null;
    }
}
