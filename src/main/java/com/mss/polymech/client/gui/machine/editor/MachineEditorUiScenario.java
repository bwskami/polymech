package com.mss.polymech.client.gui.machine.editor;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.mss.polymech.machine.common.MachineRegistry;
import net.minecraft.client.Minecraft;

/**
 * 编辑器窗口的<strong>UI 自动化测试</strong>（LDLib2 {@code UIScenario}）。
 *
 * <p>覆盖的是架构本身：<b>一个窗口 + 一条标签栏 + 标签内容区</b>。
 * 点「+」不会把标签栏弄丢（这是之前被反复报的 bug），以及「+ 组」这条
 * 按钮 → 纯逻辑 → 快照 → 标签重渲染的完整链路。</p>
 *
 * <p>运行：{@code gradlew runClient -PldTest=polymech_machine_editor}
 * （报告在 {@code run/ldlib2-uitest/}）。</p>
 */
@LDLRegisterClient(name = "polymech_machine_editor", group = "polymech",
        registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class MachineEditorUiScenario implements UIScenario {

    /** 用内置的蒸汽锤：1 个组、4 个 cube，结构小且确定。 */
    private static final String MACHINE = "steam_hammer";

    @Override
    public void configure(ScenarioOptions options) {
        // 编辑器要读客户端世界来搭 DummyWorld，所以必须有世界
        options.tags("machine", "editor").requiresWorld(true).guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.openScreen("打开编辑器窗口", ctx -> new EditorWorkspaceScreen())
                .awaitScreen(EditorWorkspaceScreen.class)
                .awaitModularUI()

                // 窗口骨架：标签栏和「＋」必须一直在
                .awaitElement("#ws_tabs")
                .checkExists("#ws_add_tab")
                .screenshot("01_window")

                // 打开一个项目标签
                .step("打开 steam_hammer", ctx -> {
                    var ws = (EditorWorkspaceScreen) Minecraft.getInstance().screen;
                    ws.openMachine(MACHINE, MachineRegistry.getMachineBlock(MACHINE));
                })
                .ticks(2)
                .checkExists("#me_outline")

                // 大纲真的把结构渲染出来了（组头 1 个、cube 行 4 个）
                .checkCount(".me-outline-group", 1)
                .checkCount(".me-outline-cube", 4)
                .screenshot("02_opened")

                // 什么都没选时点「+ 组」→ 顶层建组，组数 1 → 2
                .click("#me_add_group")
                .ticks(2)
                .checkCount(".me-outline-group", 2)
                .screenshot("03_added_group");
    }
}
