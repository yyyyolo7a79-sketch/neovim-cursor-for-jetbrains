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
import java.awt.geom.Path2D;
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

    /** √2 —— 角点偏移向量的模长上限，用于抑制锐角处的尖刺，见 computeOffsetDirections */
    private static final double SQRT_2 = Math.sqrt(2.0);

    /**
     * 辉光强度（0~1），由 animator 每帧按拖尾展开程度写入。
     * 为 0 时完全不绘制光晕 —— 光标静止时就该是一条干净的光标。
     */
    private float glowStrength = 1f;

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
     * <p>外扩半径 = {@code shadowBlurFactor × max(width, height)}，
     * 系数本身为何偏离原版 0.6，见 {@link NeovideConfig#shadowBlurFactor} 的说明。
     * 该半径即光晕的最大外扩距离 —— 最外层膨胀到此处的累积不透明度已降到约 6%，
     * 对应高斯模糊的可见边界。
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

    /**
     * 设置辉光强度（0~1），超出范围会被截断。
     *
     * <p>由 animator 每帧按「拖尾展开程度」写入：完全静止时为 0（不画光晕），
     * 正常移动时为 1（满强度，与之前的观感完全一致）。
     */
    public void setGlowStrength(float strength) {
        this.glowStrength = strength < 0f ? 0f : (strength > 1f ? 1f : strength);
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
     * 绘制弹簧模式的辉光 —— 模拟原版 Canvas 的 {@code shadowBlur} 高斯模糊。
     *
     * <p><b>做法：多层同心外扩 + 四边形填充。</b>
     * 从最外层（外扩量最大）逐层向内绘制，层与层之间由 SrcOver 自然累加，
     * 于是形成「内亮外暗」的连续渐变 —— 即视觉上的发光。
     *
     * <p><b>每层 alpha 的取法</b>：设每层独立不透明度为 A，N 层叠加后最内层的
     * 累积不透明度为 {@code 1 - (1-A)^N}。令其恰好等于 {@link NeovideConfig#glowOpacity}
     * 反解出 {@code A = 1 - (1-peak)^(1/N)}，这样「N 层」与「强度」两个参数互不干扰：
     * 加层数只会让渐变更细腻，不会让光晕整体变亮。
     *
     * <p><b>为什么半径按线性分布</b>：累积不透明度在半径 r 处 = {@code 1-(1-A)^k}，
     * k 为覆盖该点的层数。半径线性分布时 k 也线性，
     * 算出在 {@code r ≈ glowWidth/2} 处刚好衰减到峰值的约 60%，与高斯曲线吻合。
     *
     * <p><b>光晕必须贴着四边形本身，不能用包围盒</b>（曾如此实现，效果失败）：
     * 光标的四个角点在拖尾时会被拉成一条斜向的细长四边形，
     * 其<b>包围盒</b>却能覆盖大半个编辑器 —— 于是跨行跳转时光晕会先撑成一个
     * 巨大方块再收缩，完全不是"贴着拖尾发光"。改为逐边外法线平移 + 相邻边求交
     * 的精确外扩后，光晕始终贴合四边形轮廓。
     *
     * <p><b>为什么不用「多层居中描边」</b>（更早的实现，同样失败）：
     * 描边路径是矩形的<b>边界线</b>而非内部，各层向外只扩 {@code strokeWidth/2}。
     * IntelliJ 光标极窄（2×32），于是最亮的那层只向外扩不到 1px 且随即被
     * 不透明主体盖住；而真正扩得够远的层，alpha 已衰减到 2% 以下。
     */
    private void drawQuadGlow(Graphics2D g2, Polygon polygon) {
        if (!config.useShadow || config.glowLayers <= 0 || glowWidth <= 0.5f) {
            return;
        }

        int layers = config.glowLayers;

        // 峰值再乘上强度：光标静止时 glowStrength 为 0，此处直接返回，光晕完全不绘制
        float peak = config.glowOpacity * (glowColor.getAlpha() / 255f) * glowStrength;
        if (peak <= 0f) {
            return;
        }

        // 反解每层独立 alpha，使 N 层累积后恰好达到 peak
        double layerAlpha = 1.0 - Math.pow(1.0 - peak, 1.0 / layers);
        int argb = (int) Math.round(layerAlpha * 255);
        if (argb < 1) {
            return;
        }

        g2.setColor(new Color(glowColor.getRed(), glowColor.getGreen(),
                glowColor.getBlue(), argb));

        // 角点的外扩方向：沿该方向平移 radius，即"四边形向外偏移 radius"
        double[] dirX = new double[4];
        double[] dirY = new double[4];
        computeOffsetDirections(polygon, dirX, dirY);

        Path2D.Double path = new Path2D.Double();

        // 从最外层向内绘制，半径线性分布
        for (int i = layers; i >= 1; i--) {
            double radius = glowWidth * (double) i / layers;

            path.reset();
            for (int j = 0; j < 4; j++) {
                double x = polygon.xpoints[j] + dirX[j] * radius;
                double y = polygon.ypoints[j] + dirY[j] * radius;
                if (j == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            path.closePath();
            g2.fill(path);
        }
    }

    /**
     * 计算四个角点各自的「外扩方向向量」——沿该方向平移 r，
     * 得到的就是「四边形精确向外偏移 r」的结果。
     *
     * <p>做法：每条边先取指向远离图形中心一侧的单位外法线 n；
     * 角点 j 由相邻两条边（prev→j 与 j→next）共同决定，
     * 偏移向量为 {@code (n1 + n2) / (1 + n1·n2)}。
     *
     * <p>该式的来历：两条平移后的边，其交点到原角点的距离为 {@code r / cos(θ/2)}，
     * 方向沿 {@code n1 + n2}，而 {@code 1 + n1·n2 = 2cos²(θ/2)}，代入即得。
     * 两边正交时（矩形）分母为 1，退化成 {@code n1 + n2} —— 正是对角线方向。
     */
    private static void computeOffsetDirections(Polygon polygon,
                                                double[] outDirX, double[] outDirY) {
        double[] nx = new double[4];
        double[] ny = new double[4];

        double cx = 0;
        double cy = 0;
        for (int j = 0; j < 4; j++) {
            cx += polygon.xpoints[j];
            cy += polygon.ypoints[j];
        }
        cx /= 4.0;
        cy /= 4.0;

        // 逐边求单位外法线
        for (int j = 0; j < 4; j++) {
            int next = (j + 1) % 4;
            double ex = polygon.xpoints[next] - polygon.xpoints[j];
            double ey = polygon.ypoints[next] - polygon.ypoints[j];
            double len = Math.hypot(ex, ey);
            if (len < 1e-6) {
                // 退化边：留零向量，该角点不参与外扩
                continue;
            }

            double vx = -ey / len;
            double vy = ex / len;

            // 两个候选方向里取远离中心的那一个
            double mx = (polygon.xpoints[j] + polygon.xpoints[next]) / 2.0 - cx;
            double my = (polygon.ypoints[j] + polygon.ypoints[next]) / 2.0 - cy;
            if (vx * mx + vy * my < 0) {
                vx = -vx;
                vy = -vy;
            }
            nx[j] = vx;
            ny[j] = vy;
        }

        // 相邻两条边的法线合成出角点的偏移向量
        for (int j = 0; j < 4; j++) {
            int prev = (j + 3) % 4;

            double n1x = nx[prev];
            double n1y = ny[prev];
            double n2x = nx[j];
            double n2y = ny[j];

            double denom = 1.0 + (n1x * n2x + n1y * n2y);
            double dirX;
            double dirY;
            if (Math.abs(denom) < 1e-6) {
                // 相邻两边接近反向（图形已退化）：直接用法线和，避免除零
                dirX = n1x + n2x;
                dirY = n1y + n2y;
            } else {
                dirX = (n1x + n2x) / denom;
                dirY = (n1y + n2y) / denom;
            }

            // 限制偏移向量的模长不超过 √2。
            //
            // 该式的数学解在「锐角」处会失控：夹角 θ 越小，交点越远，
            // 偏移量 ∝ 1/cos(θ/2)。拖尾时光标被拉成细长斜条，前后缘正是锐角，
            // 实测偏移可达目标值的 2.4 倍，光晕会甩出一根长尖刺。
            //
            // 而真实的高斯模糊对锐角是「磨圆」而非「延长」，因此这里按 √2 截断：
            // 正交处（静止矩形）的偏移量恰为 √2，不受影响；锐角处则被压回来。
            double mag = Math.hypot(dirX, dirY);
            if (mag > SQRT_2) {
                double scale = SQRT_2 / mag;
                dirX *= scale;
                dirY *= scale;
            }
            outDirX[j] = dirX;
            outDirY[j] = dirY;
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

            // 头部残影（alpha 高）补一圈辉光，使其更接近"光标本体"的观感。
            // 描边居中绘制，外扩量 = strokeWidth / 2，因此取 2 倍 glowWidth
            // 使外扩恰好等于 glowWidth —— 与弹簧模式同一语义，两者观感一致。
            if (config.useShadow && alpha > 0.55f) {
                int glowAlpha = Math.round(alpha * config.glowOpacity * 60f);
                if (glowAlpha > 3) {
                    g2.setStroke(new BasicStroke(
                            Math.max(2f, glowWidth * 2f),
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
