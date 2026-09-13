package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideColors;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;

/**
 * 拖尾绘制面板 —— 挂在编辑器 contentComponent 上，绘制由四个角点构成的多边形。
 *
 * <p><b>为什么覆盖整个编辑器内容区</b>：拖尾会延伸到光标位置之外较远处，
 * 面板若只有光标大小，拖尾会被裁掉。
 *
 * <p><b>POC 阶段踩坑后的两条硬性约束</b>（缺一不可）：
 * <ol>
 *   <li>挂载到 contentComponent —— 它 layout 为 null，不会被 LayoutManager 重新布局</li>
 *   <li>paintComponent 只画多边形，绝不画任何背景矩形 ——
 *       万一面板被意外拉伸，也只会显示多边形本身，不会铺满整个编辑器</li>
 * </ol>
 */
public class CaretTrailPanel extends JComponent {

    private final NeovideConfig config;

    /** 四个角点的当前坐标（contentComponent 坐标系），由 animator 每帧写入 */
    private final int[] polyX = new int[4];
    private final int[] polyY = new int[4];

    /** 是否已有可绘制数据（光标不可见时应为 false） */
    private boolean hasData = false;

    /** 缓存解析后的颜色与辉光宽度，避免每帧重复解析字符串 */
    private Color fillColor;
    private Color glowColor;
    private float glowWidth;

    public CaretTrailPanel(@NotNull NeovideConfig config) {
        this.config = config;
        setOpaque(false);
        refreshStyles();
    }

    /** 配置变化后刷新样式缓存 */
    public void refreshStyles() {
        fillColor = NeovideColors.parse(config.tailColor, config.tailOpacity);
        glowColor = NeovideColors.parse(config.shadowColor, 1.0f);
        // 辉光宽度刻意取小值：单层描边过宽会让光标看起来"套了个壳"
        glowWidth = Math.max(3f, config.glowWidthFactor * 24f);
    }

    /** 由 animator 每帧写入最新角点坐标 */
    public void updateCorners(double x0, double y0,
                              double x1, double y1,
                              double x2, double y2,
                              double x3, double y3) {
        polyX[0] = (int) Math.round(x0);
        polyY[0] = (int) Math.round(y0);
        polyX[1] = (int) Math.round(x1);
        polyY[1] = (int) Math.round(y1);
        polyX[2] = (int) Math.round(x2);
        polyY[2] = (int) Math.round(y2);
        polyX[3] = (int) Math.round(x3);
        polyY[3] = (int) Math.round(y3);
        hasData = true;
    }

    /** 清除绘制数据（光标不可见时调用） */
    public void clearCorners() {
        hasData = false;
    }

    /**
     * 返回 false 让鼠标事件穿透到下层编辑器。
     * 否则这个覆盖整个编辑器的面板会挡住所有点击与文本选择操作。
     */
    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!hasData) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            Polygon polygon = new Polygon(polyX, polyY, 4);

            drawGlow(g2, polygon);

            // 主体实心填充
            g2.setColor(fillColor);
            g2.fillPolygon(polygon);

        } finally {
            g2.dispose();
        }
    }

    /**
     * 绘制辉光。
     *
     * <p>Swing 没有 Canvas 的 {@code shadowBlur}，这里用<b>多层递减宽度的半透明描边</b>
     * 叠加来逼近高斯光晕：外层宽而极淡、内层窄而浓。
     *
     * <p>两个关键参数决定观感：
     * <ul>
     *   <li><b>层数</b>：太少会露出可见的硬边，看起来像给光标套了个壳（10 层足够平滑）</li>
     *   <li><b>衰减曲线</b>：线性衰减在边缘会有"台阶感"，改用<b>平方衰减</b>过渡自然得多</li>
     * </ul>
     */
    private void drawGlow(Graphics2D g2, Polygon polygon) {
        if (!config.useShadow || config.glowLayers <= 0 || glowWidth <= 0) {
            return;
        }

        int red = glowColor.getRed();
        int green = glowColor.getGreen();
        int blue = glowColor.getBlue();

        int layers = config.glowLayers;
        for (int i = layers; i >= 1; i--) {
            // t: 1.0（最外层）→ 1/layers（最内层）
            float t = (float) i / layers;
            float strokeWidth = Math.max(1f, glowWidth * t);

            // 平方衰减：外层接近透明，内层逐渐变浓，过渡无明显台阶
            float falloff = 1f - t * 0.85f;
            float alphaFactor = config.glowOpacity * falloff * falloff;

            int alpha = Math.round(Math.max(0f, Math.min(1f, alphaFactor)) * 255);
            if (alpha < 3) {
                continue;
            }

            g2.setStroke(new BasicStroke(strokeWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(red, green, blue, alpha));
            g2.drawPolygon(polygon);
        }
    }
}
