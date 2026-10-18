package com.mss.polymech.machine;

/**
 * "别播建造动画"能力。
 *
 * <h3>为什么需要它</h3>
 * 机器的 Geckolib 模型在<strong>真实放置</strong>时会播一段建造动画（从无到有拼起来）。
 * 但有两种场合只是"看一眼长什么样"，不是真实放置：
 * <ul>
 *   <li>蓝图预览虚影（{@code MachinePreviewRenderer}）——跟着鼠标走，每次移动都重播会闪；</li>
 *   <li>结构编辑器（{@code MachineEditorScreen}）——每次打开都新建方块实体，
 *       建造动画会<strong>每次打开都重播一遍</strong>。</li>
 * </ul>
 * 这两种场合都要直接显示机器的正常状态。
 *
 * <h3>放在哪一层</h3>
 * 标记字段放在 {@link BaseIOBlockEntity}（所有机器方块实体的共同基类），
 * 所以<strong>以后新增机器自动支持</strong>：它的动画控制器只要用
 * {@link #shouldPlayBuildingAnimation} 判一下即可，不必各自再存一个字段。
 *
 * <h3>可离线校验</h3>
 * {@link #shouldPlayBuildingAnimation} 是纯函数（不碰游戏状态），
 * 所以"抑制后到底还播不播"这条规则能在离线下用真值表钉死
 * （见 {@code verifyMachineEditorModel} 的 M7）——不需要进游戏肉眼看。
 */
public interface BuildAnimationSuppressible {

    /** 标记/取消"抑制建造动画"。预览虚影与结构编辑器都设为 true。 */
    void setBuildAnimationSuppressed(boolean suppressed);

    boolean isBuildAnimationSuppressed();

    /**
     * 是否该播建造动画 —— 动画控制器决策的<strong>唯一规则</strong>（纯函数，离线可校验）。
     *
     * @param suppressed     是否已标记抑制（预览虚影 / 结构编辑器）
     * @param alreadyPlayed  建造动画是否已经播过（NBT 持久化，区块重载不重播）
     * @return true 才播建造动画；否则直接显示正常状态
     */
    static boolean shouldPlayBuildingAnimation(boolean suppressed, boolean alreadyPlayed) {
        return !suppressed && !alreadyPlayed;
    }
}
