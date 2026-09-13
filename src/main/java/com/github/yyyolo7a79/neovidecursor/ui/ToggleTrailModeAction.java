package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * 拖尾渲染模式切换。
 *
 * <p>两种模式的取舍：
 * <ul>
 *   <li><b>弹簧模式（默认）</b>：Neovide 原版效果，光标被拉伸成四边形。
 *       观感更接近原版，但依赖帧间插值，低帧率时会有顿挫。</li>
 *   <li><b>残影模式</b>：绘制一串渐隐的历史残影。
 *       位置来自每帧的真实采样而非插值，因此掉帧只会让残影稀疏，轨迹不失真 ——
 *       <b>低帧率环境下明显更稳</b>。</li>
 * </ul>
 */
public class ToggleTrailModeAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.getTrailMode() == NeovideConfig.TrailMode.AFTERIMAGE;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setTrailMode(state
                ? NeovideConfig.TrailMode.AFTERIMAGE
                : NeovideConfig.TrailMode.SPRING);
    }
}
