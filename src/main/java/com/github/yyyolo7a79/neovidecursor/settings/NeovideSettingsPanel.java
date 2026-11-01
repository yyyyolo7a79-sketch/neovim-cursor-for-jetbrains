package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.core.CursorSettings;
import com.github.yyyolo7a79.neovidecursor.core.NeovideColors;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 设置页表单：顶部固定实时预览，下方是可滚动的参数区。
 *
 * <p>表单只读写 {@link CursorSettings} 快照，任何改动都立刻同步到预览；
 * 「恢复默认」只是把表单填回 0.5.1 的推荐值，同样要点 Apply 才生效。
 */
final class NeovideSettingsPanel {

    /** 一键配色：点一下同时设置拖尾色与辉光色（第一个即 0.5.1 默认的樱花粉） */
    private static final String[][] PRESETS = {
            {"樱花粉（默认）", "#FFC0CB"},
            {"薰衣草紫", "#BD93F9"},
            {"冰川蓝", "#8BE9FD"},
            {"薄荷绿", "#50FA7B"},
            {"琥珀橙", "#FFB86C"},
            {"柠檬黄", "#F1FA8C"},
            {"月光白", "#F8F8F2"},
    };

    private final TrailPreview preview = new TrailPreview();
    private final JPanel root = new JPanel(new BorderLayout());

    // ===== 基本 =====
    private final JBCheckBox enabledBox = new JBCheckBox("启用拖尾光标");
    private final JBCheckBox hideNativeBox = new JBCheckBox("隐藏原生光标，由插件常驻绘制（不闪烁，但繁忙时跟随会滞后）");
    private final JBRadioButton springRadio = new JBRadioButton("弹簧拉伸（Neovide 原版效果）");
    private final JBRadioButton afterimageRadio = new JBRadioButton("残影（低帧率更稳）");

    // ===== 颜色与辉光 =====
    private final ColorPanel tailColorPanel = new ColorPanel();
    private final ValueSlider tailOpacity = ValueSlider.percent(10, 100);
    private final JBCheckBox useShadowBox = new JBCheckBox("启用辉光");
    private final ColorPanel shadowColorPanel = new ColorPanel();
    private final ValueSlider glowOpacity = ValueSlider.percent(0, 95);
    private final ValueSlider glowSize = new ValueSlider(0, 60, 100f, v -> String.format("%.2f", v / 100f));

    // ===== 动画 =====
    private final ValueSlider animationLength = ValueSlider.millis(20, 300);
    private final ValueSlider shortAnimationLength = ValueSlider.millis(10, 200);
    private final ValueSlider afterimageLifetime = ValueSlider.millis(50, 300);
    private final ValueSlider disappearDelay = new ValueSlider(0, 500, 1f, v -> v + " ms");
    private final ValueSlider fadeOutDuration = ValueSlider.millis(0, 500);

    // ===== 高级 =====
    private final ValueSlider cursorWidth = new ValueSlider(1, 12, 1f, v -> v + " px");
    private final ValueSlider maxTrailDistance = new ValueSlider(2, 60, 1f, v -> v + " 倍行高");
    private final ValueSlider glowLayers = new ValueSlider(1, 16, 1f, v -> v + " 层");

    private final ValueSlider[] sliders = {tailOpacity, glowOpacity, glowSize, animationLength,
            shortAnimationLength, afterimageLifetime, disappearDelay, fadeOutDuration,
            cursorWidth, maxTrailDistance, glowLayers};

    /** 最近一次载入的快照：未改动的字段原样回写，避免刻度取整造成「假修改」 */
    private CursorSettings base = new CursorSettings();

    /** 批量载入期间屏蔽监听，载入完再统一刷新一次预览 */
    private boolean loading = false;

    NeovideSettingsPanel() {
        ButtonGroup modes = new ButtonGroup();
        modes.add(springRadio);
        modes.add(afterimageRadio);

        JButton resetButton = new JButton("恢复默认（0.5.1 推荐配置）");
        resetButton.setToolTipText("把表单填回已验证手感最好的樱花粉配置；点 Apply 后生效");
        resetButton.addActionListener(e -> load(new CursorSettings()));

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("基本"))
                .addComponent(row(enabledBox, resetButton))
                .addComponent(hideNativeBox)
                .addLabeledComponent("拖尾模型：", row(springRadio, afterimageRadio))
                .addComponent(new TitledSeparator("颜色与辉光"))
                .addLabeledComponent("拖尾颜色：", row(tailColorPanel, presetChips()))
                .addLabeledComponent("拖尾不透明度：", tailOpacity.getComponent())
                .addComponent(useShadowBox)
                .addLabeledComponent("辉光颜色：", row(shadowColorPanel))
                .addLabeledComponent("辉光强度：", glowOpacity.getComponent())
                .addLabeledComponent("辉光大小（× 行高）：", glowSize.getComponent())
                .addComponent(new TitledSeparator("动画"))
                .addLabeledComponent("拖尾时长：", animationLength.getComponent())
                .addTooltip("越大拖尾越长；短距离移动（如逐字输入）用下面的短时长")
                .addLabeledComponent("短距离拖尾时长：", shortAnimationLength.getComponent())
                .addLabeledComponent("残影时长：", afterimageLifetime.getComponent())
                .addLabeledComponent("停止后淡出延迟：", disappearDelay.getComponent())
                .addLabeledComponent("淡出时长：", fadeOutDuration.getComponent())
                .addComponent(new TitledSeparator("高级"))
                .addLabeledComponent("拖尾宽度：", cursorWidth.getComponent())
                .addTooltip("块状光标可调到与字符同宽")
                .addLabeledComponent("拖尾最大长度：", maxTrailDistance.getComponent())
                .addTooltip("跨多行跳转时若明显掉帧可调小，相邻行等常规移动不受影响")
                .addLabeledComponent("辉光层数：", glowLayers.getComponent())
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        form.setBorder(JBUI.Borders.empty(0, 0, 8, 12));

        JBLabel hint = new JBLabel("实时预览：点进去可以亲手试（含中文输入），点 Apply 后作用到所有编辑器");
        hint.setForeground(UIUtil.getContextHelpForeground());
        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        top.add(preview.getComponent(), BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);
        top.setBorder(JBUI.Borders.empty(0, 0, 8, 12));

        JBScrollPane scroll = new JBScrollPane(form);
        scroll.setBorder(JBUI.Borders.empty());
        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);

        for (AbstractButton b : new AbstractButton[]{enabledBox, hideNativeBox, springRadio,
                afterimageRadio, useShadowBox}) {
            b.addActionListener(e -> onChanged());
        }
        tailColorPanel.addActionListener(e -> onChanged());
        shadowColorPanel.addActionListener(e -> onChanged());
        for (ValueSlider s : sliders) {
            s.onChange(this::onChanged);
        }
    }

    JComponent getComponent() {
        return root;
    }

    /** 把快照填进表单（Configurable.reset / 恢复默认） */
    void load(CursorSettings s) {
        base = s.copy();
        loading = true;
        try {
            enabledBox.setSelected(s.enabled);
            hideNativeBox.setSelected(s.hideNativeCaret);
            springRadio.setSelected(s.trailMode != NeovideConfig.TrailMode.AFTERIMAGE);
            afterimageRadio.setSelected(s.trailMode == NeovideConfig.TrailMode.AFTERIMAGE);
            tailColorPanel.setSelectedColor(rgb(s.tailColor));
            tailOpacity.set(s.tailOpacity);
            useShadowBox.setSelected(s.useShadow);
            shadowColorPanel.setSelectedColor(rgb(s.shadowColor));
            glowOpacity.set(s.glowOpacity);
            glowSize.set(s.shadowBlurFactor);
            animationLength.set(s.animationLength);
            shortAnimationLength.set(s.shortAnimationLength);
            afterimageLifetime.set(s.afterimageLifetime);
            disappearDelay.set(s.cursorDisappearDelay);
            fadeOutDuration.set(s.cursorFadeOutDuration);
            cursorWidth.set(s.cursorWidth);
            maxTrailDistance.set(s.maxTrailDistanceFactor);
            glowLayers.set(s.glowLayers);
        } finally {
            loading = false;
        }
        onChanged();
    }

    /** 从表单读出快照：未动过的字段沿用载入值（见 ValueSlider#get） */
    CursorSettings save() {
        CursorSettings s = base.copy();
        s.enabled = enabledBox.isSelected();
        s.hideNativeCaret = hideNativeBox.isSelected();
        s.trailMode = afterimageRadio.isSelected()
                ? NeovideConfig.TrailMode.AFTERIMAGE : NeovideConfig.TrailMode.SPRING;
        s.tailColor = saveColor(tailColorPanel, base.tailColor);
        s.tailOpacity = tailOpacity.get(base.tailOpacity);
        s.useShadow = useShadowBox.isSelected();
        s.shadowColor = saveColor(shadowColorPanel, base.shadowColor);
        s.glowOpacity = glowOpacity.get(base.glowOpacity);
        s.shadowBlurFactor = glowSize.get(base.shadowBlurFactor);
        s.animationLength = animationLength.get(base.animationLength);
        s.shortAnimationLength = shortAnimationLength.get(base.shortAnimationLength);
        s.afterimageLifetime = afterimageLifetime.get(base.afterimageLifetime);
        s.cursorDisappearDelay = Math.round(disappearDelay.get(base.cursorDisappearDelay));
        s.cursorFadeOutDuration = fadeOutDuration.get(base.cursorFadeOutDuration);
        s.cursorWidth = cursorWidth.get(base.cursorWidth);
        s.maxTrailDistanceFactor = maxTrailDistance.get(base.maxTrailDistanceFactor);
        s.glowLayers = Math.round(glowLayers.get(base.glowLayers));
        return s;
    }

    void dispose() {
        preview.dispose();
    }

    /** 表单任何改动：刷新联动的可用状态，并同步到预览 */
    private void onChanged() {
        if (loading) {
            return;
        }
        // 短距离时长不超过常规时长，否则短距离移动反而比长距离更慢。
        // 回拨滑块会再触发一次本方法，由那一次刷新预览
        if (shortAnimationLength.tick() > animationLength.tick()) {
            shortAnimationLength.setTick(animationLength.tick());
            return;
        }
        updateEnabledStates();
        preview.update(save());
    }

    /** 只点亮当前配置下真正起作用的参数，免得调了半天发现没效果 */
    private void updateEnabledStates() {
        boolean spring = springRadio.isSelected();
        boolean glow = useShadowBox.isSelected();
        shadowColorPanel.setEnabled(glow);
        glowOpacity.setEnabled(glow);
        glowSize.setEnabled(glow);
        glowLayers.setEnabled(glow && spring);
        animationLength.setEnabled(spring);
        shortAnimationLength.setEnabled(spring);
        maxTrailDistance.setEnabled(spring);
        afterimageLifetime.setEnabled(!spring);
        // 停止后淡出只用于叠加模式（保留原生光标）下的弹簧拖尾
        boolean fade = spring && !hideNativeBox.isSelected();
        disappearDelay.setEnabled(fade);
        fadeOutDuration.setEnabled(fade);
    }

    /** 横排若干组件（间距 8px，首个组件前不留空，保证与上下行左对齐） */
    private static JPanel row(JComponent... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(JBUI.scale(8)));
            }
            p.add(items[i]);
        }
        return p;
    }

    /** 一键配色色块：点击同时设置拖尾色与辉光色 */
    private JPanel presetChips() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        for (String[] preset : PRESETS) {
            Color c = rgb(preset[1]);
            p.add(new PresetChip(preset[0] + "  " + preset[1], c, () -> {
                tailColorPanel.setSelectedColor(c);
                shadowColorPanel.setSelectedColor(c);
                onChanged();
            }));
        }
        return p;
    }

    /** HEX → 不透明颜色：取色器只管色相，透明度由单独的滑块控制 */
    private static Color rgb(String hex) {
        return new Color(NeovideColors.parse(hex, 1f).getRGB() & 0xFFFFFF);
    }

    /** 颜色没动过就保留原写法（如手写的 #RRGGBBAA），否则写成 #RRGGBB */
    private static String saveColor(ColorPanel panel, String original) {
        Color c = panel.getSelectedColor();
        if (c == null || (c.getRGB() & 0xFFFFFF) == (rgb(original).getRGB() & 0xFFFFFF)) {
            return original;
        }
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    /** 一键配色的小色块 */
    private static final class PresetChip extends JComponent {
        private final Color color;

        PresetChip(String tooltip, Color color, Runnable onClick) {
            this.color = color;
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(JBUI.scale(18), JBUI.scale(18)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    onClick.run();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = JBUI.scale(5);
                g2.setColor(color);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
                g2.setColor(JBColor.border());
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
            } finally {
                g2.dispose();
            }
        }
    }
}
