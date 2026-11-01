package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * 设置页：Settings | Tools | Neovide Cursor。
 *
 * <p>表单只读写 {@link com.github.yyyolo7a79.neovidecursor.core.CursorSettings} 快照，
 * 点 Apply / OK 时才一次性交给 {@link NeovideCaretManager#applySettings} 生效；
 * 调整过程中只有页内预览跟着变，正在编辑的代码不受影响。
 *
 * <p>实现 {@link NoScroll}：由面板自己滚动下方表单，预览区固定在顶部 ——
 * 拖动页面下方的滑块时也能同时看到效果。
 */
public final class NeovideSettingsConfigurable implements Configurable, Configurable.NoScroll {

    private NeovideSettingsPanel panel;

    @Override
    public String getDisplayName() {
        return "Neovide Cursor";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (panel == null) {
            panel = new NeovideSettingsPanel();
        }
        return panel.getComponent();
    }

    @Override
    public boolean isModified() {
        return panel != null && !panel.save().equals(NeovideCaretManager.currentSettings());
    }

    @Override
    public void apply() {
        if (panel != null) {
            NeovideCaretManager.applySettings(panel.save());
        }
    }

    @Override
    public void reset() {
        if (panel != null) {
            panel.load(NeovideCaretManager.currentSettings());
        }
    }

    @Override
    public void disposeUIResources() {
        if (panel != null) {
            panel.dispose();
            panel = null;
        }
    }
}
