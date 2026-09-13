package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * 性能诊断开关：跳过全部重绘，只保留物理计算。
 *
 * <p>用于回答一个关键问题 —— <b>卡顿到底来自「重绘」还是「EDT 调度」</b>：
 * <ul>
 *   <li>勾选后 fps <b>大幅上升</b> → 瓶颈在重绘，值得改造成独立窗口方案</li>
 *   <li>勾选后 fps <b>依然很低</b> → 瓶颈在 EDT 负载，换窗口方案也没用</li>
 * </ul>
 *
 * <p>注意：勾选后拖尾会消失（不再绘制），这是预期行为，取消勾选即恢复。
 */
public class TogglePaintDiagAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.isPaintDisabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setPaintDisabled(state);
        Messages.showInfoMessage(
                state
                        ? "已开启诊断模式：拖尾不再绘制。\n\n请连续快速移动光标，然后告诉我卡顿是否消失。"
                        : "已关闭诊断模式，拖尾恢复绘制。",
                "Neovide Cursor 性能诊断");
    }
}
