package com.mss.polymech.client.gui.machine.editor;

import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.client.Minecraft;

/**
 * 编辑器界面的客户端打开入口。
 *
 * <p>为什么单独一个极小类：ModCommands 是双端共用的命令注册类，服务端也会加载它。
 * 若它直接引用 Minecraft / 编辑器窗口这类客户端类，服务端加载 ModCommands
 * 时会 NoClassDefFoundError（崩服）。把客户端类型全收进这个"只会在客户端被触碰"的类，
 * 命令里用 FMLEnvironment.dist 先判端、只在客户端分支调用本类即可规避。</p>
 *
 * <p>mc.execute：命令回调不保证在渲染线程，开界面须切回客户端主线程。</p>
 *
 * <h3>窗口只有一个</h3>
 * 这里不再"换 Screen"，而是<b>复用已经打开的 {@link EditorWorkspaceScreen}</b>、
 * 往里加标签。所以点加号/点机器都不会把标签栏弄丢。
 */
public final class EditorScreenOpener {

    private EditorScreenOpener() {
    }

    /** 打开（或切到）一个机器项目。 */
    public static void open(String machineId, BaseMachineBlock block) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof EditorWorkspaceScreen ws) {
                ws.openMachine(machineId, block);
                return;
            }
            // 新窗口：先挂"待打开"，setScreen 跑 init 时再加标签，
            // 这样直接开机器不会顺带多一个"主菜单"页
            var ws = new EditorWorkspaceScreen();
            ws.openMachine(machineId, block);
            mc.setScreen(ws);
        });
    }

    /** 打开（或切到）主菜单页。 */
    public static void openPicker() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof EditorWorkspaceScreen ws) {
                ws.openPicker();
                return;
            }
            var ws = new EditorWorkspaceScreen();
            ws.openPicker();
            mc.setScreen(ws);
        });
    }
}
