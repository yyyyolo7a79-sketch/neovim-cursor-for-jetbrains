package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.core.CursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * 设置的持久化（应用级，保存在 IDE 配置目录的 {@code options/neovide-cursor.xml}）。
 *
 * <p><b>本类不另存一份状态</b>：唯一的真值是 {@link NeovideCaretManager} 持有的运行时配置。
 * 保存时现场抓取快照，加载时直接写回 —— 于是设置页的 Apply 与 Tools 菜单里的开关
 * 都会自动落盘，不存在「两份状态不同步」的问题。
 *
 * <p>关闭漫游（{@link RoamingType#DISABLED}）：SDK 文档规定应用级 light service
 * 做持久化时必须如此（Light Service Restrictions）。
 */
@Service(Service.Level.APP)
@State(name = "NeovideCursorSettings",
        storages = @Storage(value = "neovide-cursor.xml", roamingType = RoamingType.DISABLED))
public final class NeovideSettings implements PersistentStateComponent<CursorSettings> {

    public static NeovideSettings getInstance() {
        return ApplicationManager.getApplication().getService(NeovideSettings.class);
    }

    @Override
    public @NotNull CursorSettings getState() {
        return NeovideCaretManager.currentSettings();
    }

    @Override
    public void loadState(@NotNull CursorSettings state) {
        NeovideCaretManager.loadSettings(state.sanitize());
    }
}
