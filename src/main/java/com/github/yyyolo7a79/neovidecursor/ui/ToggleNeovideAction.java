package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * 拖尾光标总开关。
 *
 * <p>关闭时会立即恢复原生光标并移除所有覆盖层 —— 这是效果出问题时的
 * 一键逃生入口（例如原生光标颜色没能恢复时，可借此复位）。
 */
public class ToggleNeovideAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.isEnabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setEnabled(state);
    }
}
