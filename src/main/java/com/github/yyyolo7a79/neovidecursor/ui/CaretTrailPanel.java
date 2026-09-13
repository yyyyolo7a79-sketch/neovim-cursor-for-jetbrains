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
import java.util.ArrayList;
import java.util.List;

/**
 * 拖尾绘制面板 —— 挂在编辑器 contentComponent 上，绘制拖尾。
 *
 * <p>支持两种渲染模式（由 {@link NeovideConfig#trailMode} 决定）：
 * <ul>
 *   <li><b>弹簧模式</b>：绘制由四个角点构成的四边形（Neovide 原版效果）</li>
 *   <li><b>残影模式</b>：绘制一串渐隐的矩形残影</li>
 * </ul>
 *
 * <p><b>为什么覆盖整个编辑器内容区</b>：拖尾会延伸到光标位置之外较远处，
 * 面板若只有光标大小，拖尾会被裁掉。
 *
 * <p><b>POC 阶段踩坑后的两条硬性约束</b>（缺一不可）：
 * <ol>
 *   <li>挂载到 contentComponent —— 它 layout 为 null，不会被 LayoutManager 重新布局</li>
 *   <li>paintComponent 只画图形，绝不画任何背景矩形 ——
 *       万一面板被意外拉伸，也只会显示图形本身，不会铺满整个编辑器</li>
 * </ol>
 */
public class CaretTrailPanel extends JComponent {

    private final NeovideConfig config;

    // ===== 弹簧模式数据 =====

    /** 四个角点的当前坐标（contentComponent 坐标系） */
    private final int[] polyX = new int[4];
    private final int[] polyY = new int[4];

    /** 是否已有可绘制的四边形 */
    private boolean hasQuad = false;

    // ===== 残影模式数据 =====

    /**
     * 待绘制的残影，每项为 {@code [x, y, width, height, alpha]}。
     * 由 animator 每帧写入。
     */
    private final List<float[]> ghosts = new ArrayList<>(32);

    /** 是否已有可绘制的残影 */
    private boolean hasGhosts = false;

    // ===== 样式缓存 =====

    private Color fillColor;
    private Color glowColor;

    /** 辉光最大外扩量：= shadowBlurFactor × 光标较长边，与原版 shadowBlur 语义一致 */
    private float glowWidth = 12f;

    public CaretTrailPanel(@NotNull NeovideConfig config) {
        this.config = config;
        setOpaque(false);
        refreshStyles();
    }

    /** 配置变化后刷新样式缓存 */
    public void refreshStyles() {
        fillColor = NeovideColors.parse(config.tailColor, config.tailOpacity);
        glowColor = NeovideColors.parse(config.shadowColor, 1.0f);
    }

    /**
     * 按光标尺寸更新辉光半径（弹簧模式使用）。
     *
     * <p>与原版语义对齐：{@code shadowBlur = shadowBlurFactor × max(width, height)}。
     * 实际可见的外扩只有该值的一半 —— 光晕用居中描边绘制，
     * 一半压在被填充的主体内部，一半露在外面。
     */
    public void updateGlowRadius(double cursorWidth, double cursorHeight) {
        double maxDim = Math.max(cursorWidth, cursorHeight);
        float newWidth = (float) (config.shadowBlurFactor * maxDim);
        if (Math.abs(newWidth - glowWidth) > 0.5f) {
            glowWidth = newWidth;
        }
    }

    /** 当前辉光外扩量，供调用方计算重绘区域余量 */
    public float getGlowWidth() {
        return glowWidth;
    }

    // ==================== 数据写入 ====================

    /** 弹簧模式：写入四边形角点 */
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
        hasQuad = true;
        hasGhosts = false;
    }

    /** 残影模式：写入残影列表（每项 [x, y, width, height, alpha]） */
    public void updateGhosts(@NotNull List<float[]> shapes) {
        ghosts.clear();
        ghosts.addAll(shapes);
        hasGhosts = true;
        hasQuad = false;
    }

    /** 清除全部绘制数据（光标不可见 / 模式切换时调用） */
    public void clearCorners() {
        hasQuad = false;
        hasGhosts = false;
        ghosts.clear();
    }

    // ==================== 绘制 ====================

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
        if (!hasQuad && !hasGhosts) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (hasGhosts) {
                paintGhosts(g2);
            }
            if (hasQuad) {
                paintQuad(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    // ==================== 弹簧模式绘制 ====================

    private void paintQuad(Graphics2D g2) {
        Polygon polygon = new Polygon(polyX, polyY, 4);

        drawQuadGlow(g2, polygon);

        // 主体实心填充（画在光晕之上，保证光标本身清晰锐利）
        g2.setColor(fillColor);
        g2.fillPolygon(polygon);
    }

    /**
     * 绘制弹簧模式的辉光。
     *
     * <p>Canvas 的 {@code shadowBlur} 是真正的高斯模糊，Swing 没有等价物。
     * 这里用 <b>多层递减宽度的半透明描边</b> 逼近。
     *
     * <p><b>为什么用描边而不是填充膨胀轮廓</b>：填充会把膨胀后的实心块叠加起来，
     * 结果是「光标被撑大一圈、糊成一团」而不是边缘发光。描边居中绘制，
     * 一半压在主体内部（被填充覆盖），只有一半露在外面。
     */
    private void drawQuadGlow(Graphics2D g2, Polygon polygon) {
        if (!config.useShadow || config.glowLayers <= 0 || glowWidth <= 0.5f) {
            return;
        }

        int red = glowColor.getRed();
        int green = glowColor.getGreen();
        int blue = glowColor.getBlue();
        float colorAlpha = glowColor.getAlpha() / 255f;

        int layers = config.glowLayers;
        for (int i = layers; i >= 1; i--) {
            float t = (float) i / layers;
            float strokeWidth = Math.max(1f, glowWidth * t);

            float falloff = 1f - t * 0.85f;
            float alpha = config.glowOpacity * falloff * falloff * colorAlpha;

            int argb = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
            if (argb < 3) {
                continue;
            }

            g2.setStroke(new BasicStroke(strokeWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(red, green, blue, argb));
            g2.drawPolygon(polygon);
        }
    }

    // ==================== 残影模式绘制 ====================

    /**
     * 绘制残影。
     *
     * <p>残影本身就是按时间渐隐叠加的，视觉上已经足够柔和，
     * 因此不额外绘制光晕 —— 既省开销，也避免多个残影的光晕互相叠加变糊。
     * 仅对最浓的几个残影（alpha 较高者）补一点边缘辉光，保持与弹簧模式的一致性。
     */
    private void paintGhosts(Graphics2D g2) {
        int red = glowColor.getRed();
        int green = glowColor.getGreen();
        int blue = glowColor.getBlue();

        for (float[] shape : ghosts) {
            int x = Math.round(shape[0]);
            int y = Math.round(shape[1]);
            int w = Math.max(1, Math.round(shape[2]));
            int h = Math.max(1, Math.round(shape[3]));
            float alpha = Math.max(0f, Math.min(1f, shape[4]));

            if (alpha <= 0.02f) {
                continue;
            }

            // 头部残影（alpha 高）补一圈辉光，使其更接近"光标本体"的观感
            if (config.useShadow && alpha > 0.55f) {
                int glowAlpha = Math.round(alpha * config.glowOpacity * 60f);
                if (glowAlpha > 3) {
                    g2.setStroke(new BasicStroke(
                            Math.max(2f, glowWidth * 0.6f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(red, green, blue, Math.min(255, glowAlpha)));
                    g2.drawRect(x, y, w, h);
                }
            }

            int a = Math.round(alpha * fillColor.getAlpha());
            g2.setColor(new Color(fillColor.getRed(), fillColor.getGreen(),
                    fillColor.getBlue(), a));
            g2.fillRect(x, y, w, h);
        }
    }
}
