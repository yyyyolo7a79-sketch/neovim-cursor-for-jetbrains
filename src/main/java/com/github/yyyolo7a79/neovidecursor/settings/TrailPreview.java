package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.core.CursorSettings;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.fileTypes.LanguageFileType;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;

/**
 * 设置页的实时预览：一个迷你编辑器，挂的是<b>真正的</b> {@link CaretAnimator}。
 *
 * <p>不另写一套绘制逻辑，因此预览与实际编辑器的效果完全一致；
 * 它用独立的 {@link NeovideConfig}，拖动滑块只影响预览，点 Apply 才作用到正在编辑的代码。
 *
 * <p>未聚焦时自动演示光标跳动；点进预览区即可亲手试（含中文输入），此时演示暂停。
 * 设置对话框关闭时由 {@link #dispose()} 释放，不留任何后台开销。
 */
final class TrailPreview {

    /**
     * 示例代码：按顺序取第一个由<b>真正的语言插件</b>支持的（IDEA → Java，PyCharm → Python，
     * WebStorm → JavaScript），都不支持时退回第一个、按纯文本显示。
     * 演示路线 {行, 列} 混合短距离、单个汉字、跨行与长距离跳转，列号超出行长时截断到行尾。
     */
    private record Sample(String fileName, String languageId, String text, int[][] route) {
    }

    private static final Sample[] SAMPLES = {
            new Sample("Demo.java", "JAVA", String.join("\n",
                    "public class Demo {",
                    "    String greeting = \"Hello, Neovide\";",
                    "    // 拖尾光标：你好，世界",
                    "    void run() { System.out.println(greeting); }",
                    "}"),
                    new int[][]{{1, 10}, {1, 19}, {1, 38}, {2, 7}, {2, 8}, {2, 9}, {2, 12}, {3, 4}, {3, 30}, {0, 0}}),
            new Sample("demo.py", "Python", String.join("\n",
                    "class Demo:",
                    "    greeting = \"Hello, Neovide\"",
                    "    # 拖尾光标：你好，世界",
                    "    def run(self):",
                    "        print(self.greeting)"),
                    new int[][]{{1, 12}, {1, 15}, {1, 28}, {2, 6}, {2, 7}, {2, 8}, {2, 11}, {3, 8}, {4, 26}, {0, 0}}),
            new Sample("demo.js", "JavaScript", String.join("\n",
                    "class Demo {",
                    "    greeting = \"Hello, Neovide\";",
                    "    // 拖尾光标：你好，世界",
                    "    run() { console.log(this.greeting); }",
                    "}"),
                    new int[][]{{1, 12}, {1, 15}, {1, 30}, {2, 7}, {2, 8}, {2, 9}, {2, 12}, {3, 4}, {3, 38}, {0, 0}}),
    };

    private static final int DEMO_INTERVAL_MS = 650;

    private final NeovideConfig config = new NeovideConfig();
    private final Sample sample = pickSample();
    private final EditorEx editor;
    private final Timer demoTimer;
    private CaretAnimator animator;
    private int demoIndex = 0;

    TrailPreview() {
        Document doc = EditorFactory.getInstance().createDocument(sample.text());
        editor = (EditorEx) EditorFactory.getInstance().createEditor(doc);
        // 独立配色：只在预览里改光标色，不碰全局配色方案
        editor.setColorsScheme(editor.createBoundColorSchemeDelegate(
                EditorColorsManager.getInstance().getGlobalScheme()));
        // 语法高亮：用当前配色方案、按文件名取语言插件自带的词法高亮。
        // 没有项目，字段、方法调用等语义颜色做不了；失败时保持纯文本，不影响预览
        try {
            editor.setHighlighter(EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(editor.getColorsScheme(), sample.fileName(), null));
        } catch (Throwable ignored) {
            // 保持纯文本
        }

        EditorSettings s = editor.getSettings();
        s.setLineNumbersShown(false);
        s.setFoldingOutlineShown(false);
        s.setLineMarkerAreaShown(false);
        s.setGutterIconsShown(false);
        s.setIndentGuidesShown(false);
        s.setRightMarginShown(false);
        s.setCaretRowShown(false);
        s.setUseSoftWraps(false);
        s.setAdditionalLinesCount(0);
        s.setAdditionalColumnsCount(1);

        editor.getComponent().setPreferredSize(new Dimension(
                420, editor.getLineHeight() * doc.getLineCount() + 12));

        demoTimer = new Timer(DEMO_INTERVAL_MS, e -> demoStep());
        demoTimer.start();
    }

    JComponent getComponent() {
        return editor.getComponent();
    }

    /** 按表单当前值刷新预览（表单任何改动都会调用） */
    void update(CursorSettings s) {
        boolean modeChanged = s.trailMode != config.trailMode;
        s.applyTo(config);
        setNativeCaretHidden(s.enabled && s.hideNativeCaret);

        if (!s.enabled) {
            // 总开关关闭：预览只剩原生光标，与关闭插件后的观感一致
            disposeAnimator();
            return;
        }
        if (animator == null) {
            animator = new CaretAnimator(editor, config);
            return;
        }
        if (modeChanged) {
            animator.onModeChanged();
        }
        animator.onConfigChanged();
    }

    /** 预览编辑器自己的原生光标显隐（独立配色，不影响其他编辑器） */
    private void setNativeCaretHidden(boolean hidden) {
        editor.getColorsScheme().setColor(EditorColors.CARET_COLOR,
                hidden ? new Color(0, 0, 0, 0) : NeovideCaretManager.visibleCaretColor());
        editor.getContentComponent().repaint();
    }

    /** 自动演示一步：预览不可见，或用户正在预览里亲手操作时跳过 */
    private void demoStep() {
        JComponent content = editor.getContentComponent();
        if (!content.isShowing() || content.hasFocus()) {
            return;
        }
        int[][] route = sample.route();
        int[] step = route[demoIndex];
        demoIndex = (demoIndex + 1) % route.length;

        Document doc = editor.getDocument();
        int line = Math.min(step[0], doc.getLineCount() - 1);
        int start = doc.getLineStartOffset(line);
        int end = doc.getLineEndOffset(line);
        editor.getCaretModel().removeSecondaryCarets();
        editor.getCaretModel().moveToOffset(Math.min(start + step[1], end));
    }

    /** 选第一个由真正语言插件支持的示例；TextMate 等通用高亮的语言 ID 对不上，不算支持 */
    private static Sample pickSample() {
        for (Sample s : SAMPLES) {
            FileType type = FileTypeRegistry.getInstance().getFileTypeByFileName(s.fileName());
            if (type instanceof LanguageFileType lft && s.languageId().equals(lft.getLanguage().getID())) {
                return s;
            }
        }
        return SAMPLES[0];
    }

    private void disposeAnimator() {
        if (animator != null) {
            animator.dispose();
            animator = null;
        }
    }

    void dispose() {
        demoTimer.stop();
        disposeAnimator();
        EditorFactory.getInstance().releaseEditor(editor);
    }
}
