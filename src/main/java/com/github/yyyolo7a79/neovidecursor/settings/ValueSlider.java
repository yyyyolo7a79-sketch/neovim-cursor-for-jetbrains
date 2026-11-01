package com.github.yyyolo7a79.neovidecursor.settings;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.IntFunction;

/**
 * 带数值标签的滑块：滑块走整数刻度，读写时按 scale 换算成实际值。
 * 例如时长以 1ms 为刻度（scale = 1000，实际值单位为秒），百分比以 1% 为刻度（scale = 100）。
 */
final class ValueSlider {

    private final JSlider slider;
    private final JBLabel label = new JBLabel();
    private final JPanel component = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    private final float scale;
    private final IntFunction<String> text;

    ValueSlider(int min, int max, float scale, IntFunction<String> text) {
        this.slider = new JSlider(min, max, min);
        this.scale = scale;
        this.text = text;

        // 按最长的文本定宽，避免数值变化时滑块被挤得忽长忽短
        label.setText(text.apply(max));
        Dimension d = label.getPreferredSize();
        label.setPreferredSize(new Dimension(Math.max(d.width, JBUI.scale(56)), d.height));

        slider.addChangeListener(e -> label.setText(text.apply(slider.getValue())));
        component.add(slider, BorderLayout.CENTER);
        component.add(label, BorderLayout.EAST);
    }

    static ValueSlider percent(int min, int max) {
        return new ValueSlider(min, max, 100f, v -> v + "%");
    }

    /** 以秒为单位的时长，滑块以毫秒为刻度 */
    static ValueSlider millis(int min, int max) {
        return new ValueSlider(min, max, 1000f, v -> v + " ms");
    }

    void set(float value) {
        slider.setValue(toTick(value));
        label.setText(text.apply(slider.getValue()));
    }

    /**
     * 读出实际值。滑块仍停在 original 对应的刻度上时原样返回 original ——
     * 否则手改过的配置（如 0.205）会被取整成 0.21，一打开设置页就显示「已修改」。
     */
    float get(float original) {
        int value = slider.getValue();
        return value == toTick(original) ? original : value / scale;
    }

    void onChange(Runnable listener) {
        slider.addChangeListener(e -> listener.run());
    }

    /** 当前刻度（同为毫秒刻度的两个滑块可以直接比较） */
    int tick() {
        return slider.getValue();
    }

    /** 直接设置刻度，会触发 onChange 监听 */
    void setTick(int tick) {
        slider.setValue(tick);
    }

    void setEnabled(boolean on) {
        slider.setEnabled(on);
        label.setEnabled(on);
    }

    JComponent getComponent() {
        return component;
    }

    private int toTick(float value) {
        return Math.round(value * scale);
    }
}
