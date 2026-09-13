package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个编辑器的拖尾动画控制器。
 *
 * <p>职责有三：
 * <ol>
 *   <li>跟踪光标位置，维护动画目标点</li>
 *   <li>驱动物理模拟</li>
 *   <li>把角点坐标写入绘制面板并触发重绘</li>
 * </ol>
 *
 * <p><b>坐标系</b>：全程使用 contentComponent 坐标系。
 * {@code editor.visualPositionToXY()} 返回的正是该坐标系下的点，
 * 而绘制面板也挂在 contentComponent 上，因此<b>完全不需要坐标转换</b>。
 *
 * <p><b>为什么不依赖 caretPositionChanged</b>：实测发现删除文本等场景下，
 * 光标事件可能在 {@code visualPositionToXY()} 数据更新之前就触发 ——
 * 此时读到的仍是旧位置，而之后不会再有新事件，拖尾就会永久卡在旧位置。
 * 因此改为<b>每个 tick 都重新读取光标位置</b>，事件仅用于"唤醒"。
 *
 * <h3>性能设计（四项，缺一不可）</h3>
 *
 * <p><b>1. 高精度调度</b>：不用 {@code javax.swing.Timer}。
 * 在 Windows 上实测，Timer 设 8ms 的实际触发间隔是 15.61ms（仅 64fps）——
 * 根源在于它内部走 {@code Object.wait}，拿不到系统的高精度时钟；
 * 而 {@code Thread.sleep} 实测精度可达 1.56ms（JDK 在 Windows 上使用了高精度定时器）。
 * 因此改为「后台线程 sleep 调度 + {@code invokeLater} 切回 EDT 绘制」。
 *
 * <p><b>2. 局部重绘</b>：只重绘拖尾占据的包围盒（并合并上一帧区域以擦除残影），
 * 而不是重绘覆盖整个编辑器的面板。
 *
 * <p><b>3. 快速路径</b>：用轻量的 {@code getVisualPosition()} 先判断光标是否移动，
 * 避免每帧都做昂贵的 {@code visualPositionToXY()} 换算。
 *
 * <p><b>4. 零分配热路径</b>：复用 Rectangle 等对象，避免每帧产生垃圾触发 GC。
 *
 * <p><b>5. 积压保护</b>：若上一帧尚未渲染完成，本次调度直接跳过，
 * 避免 EDT 队列堆积导致动画越跑越滞后。
 */
public class CaretAnimator implements Disposable, CaretListener {

    /** 运动时的调度间隔（毫秒）—— 叠加 Thread.sleep 的 ~1.5ms 误差后约 100fps */
    private static final int ACTIVE_DELAY_MS = 8;

    /** 静止时的轮询间隔（低频看门狗） */
    private static final int IDLE_DELAY_MS = 120;

    /** 单帧最大时间步长，防止 IDE 卡顿后弹簧"跳变" */
    private static final double MAX_DT = 1.0 / 30;

    /** 四个角点的相对位置（左上 → 右上 → 右下 → 左下，顺时针） */
    private static final double[][] CORNER_RELATIVE = {
            {-0.5, -0.5},
            {0.5, -0.5},
            {0.5, 0.5},
            {-0.5, 0.5},
    };

    private final Editor editor;
    private final JComponent content;
    private final CaretTrailPanel panel;
    private final NeovideConfig config;
    private final TrailCorner[] corners;

    /** 辉光最大外扩量，用于计算重绘区域需要留出的余量 */
    private final int glowPadding;

    // ===== 渲染调度 =====

    /** 调度线程：以高精度 sleep 驱动渲染节奏 */
    private Thread scheduler;

    /** 是否有尚未执行的渲染任务（用于跳过积压帧） */
    private final AtomicBoolean framePending = new AtomicBoolean(false);

    /** 当前调度间隔：EDT 侧更新，调度线程读取 */
    private volatile int currentDelayMs = IDLE_DELAY_MS;

    /** 是否已销毁 */
    private volatile boolean disposed = false;

    // ===== 性能统计（每秒输出一次，用于客观定位卡顿来源）=====

    private static final Logger LOG = Logger.getInstance(CaretAnimator.class);

    private int frameCount = 0;
    private int peakRepaintArea = 0;
    private long fpsWindowStart = 0L;

    // ===== 重绘区域复用对象（避免每帧分配）=====

    private final Rectangle lastDirtyRegion = new Rectangle();
    private final Rectangle currentBounds = new Rectangle();

    /** 上一次 tick 的时间戳（纳秒） */
    private long lastNanos;

    /**
     * 上次记录的视觉位置，用于低成本地判断光标是否移动。
     * {@code getVisualPosition()} 远比 {@code visualPositionToXY()} 便宜 ——
     * 后者要换算折叠、制表符宽度等，是高帧率下的主要开销来源。
     */
    private int lastVisualLine = Integer.MIN_VALUE;
    private int lastVisualColumn = Integer.MIN_VALUE;

    // ===== 动画目标状态 =====

    /** 光标矩形中心点（contentComponent 坐标系） */
    private double centerX;
    private double centerY;

    private double cursorWidth;
    private double cursorHeight;

    /** 标记目标点刚发生变化，下一帧需要重新分配角点 rank */
    private boolean jumped = false;

    /** 是否已完成初始化（首帧直接吸附到位，不做入场动画） */
    private boolean initialized = false;

    /**
     * 强制重新同步标记：布局刚发生变化时置位。
     * 此时视觉位置可能没变，但像素坐标已失效，必须绕过快速路径重算 ——
     * 否则会留下一个停在错误位置的「悬浮光标」。
     */
    private boolean forceResync = false;

    public CaretAnimator(@NotNull Editor editor, @NotNull NeovideConfig config) {
        this.editor = editor;
        this.config = config;
        this.content = editor.getContentComponent();
        this.panel = new CaretTrailPanel(config);

        this.corners = new TrailCorner[CORNER_RELATIVE.length];
        for (int i = 0; i < CORNER_RELATIVE.length; i++) {
            corners[i] = new TrailCorner(CORNER_RELATIVE[i][0], CORNER_RELATIVE[i][1], config);
        }

        // 重绘区域要覆盖辉光的外扩部分，否则光晕边缘会残留脏像素
        float glowWidth = Math.max(3f, config.glowWidthFactor * 24f);
        this.glowPadding = (int) Math.ceil(glowWidth) + 3;

        // 面板覆盖整个内容区（拖尾可能延伸到光标之外）
        panel.setBounds(0, 0, Math.max(content.getWidth(), 1), Math.max(content.getHeight(), 1));
        content.add(panel);

        editor.getCaretModel().addCaretListener(this);

        // 首次定位
        refreshTarget();

        startScheduler();
    }

    // ==================== 渲染调度 ====================

    /**
     * 启动渲染调度线程。
     *
     * <p>用后台线程 {@code Thread.sleep} 控制节奏，再通过 {@code invokeLater}
     * 把绘制切回 EDT —— 绕开了 {@code javax.swing.Timer} 的 15.6ms 精度天花板。
     */
    private void startScheduler() {
        scheduler = new Thread(() -> {
            while (!disposed) {
                try {
                    Thread.sleep(Math.max(1, currentDelayMs));
                } catch (InterruptedException e) {
                    // 被 wakeUp() 主动唤醒：立即进入下一轮，不退出线程
                    if (disposed) {
                        return;
                    }
                }
                if (disposed) {
                    return;
                }

                // 上一帧还没渲染完就跳过本次调度，避免任务在 EDT 队列里堆积
                if (!framePending.compareAndSet(false, true)) {
                    continue;
                }

                SwingUtilities.invokeLater(() -> {
                    framePending.set(false);
                    if (!disposed) {
                        tick();
                    }
                });
            }
        }, "neovide-cursor-render");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    /** 立即切到高帧率并唤醒调度线程（否则最长要等一个 IDLE 周期才响应） */
    private void wakeUp() {
        currentDelayMs = ACTIVE_DELAY_MS;
        Thread s = scheduler;
        if (s != null) {
            s.interrupt();
        }
    }

    // ==================== 光标监听 ====================

    /**
     * 光标事件只用于"唤醒"高帧率。
     * 真正的目标点更新在 {@link #tick()} 中每帧进行，避免事件时序问题。
     */
    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        wakeUp();
    }

    // ==================== 动画循环 ====================

    /** 每帧：重新读取光标位置 → 推进物理 → 局部重绘 */
    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min((now - lastNanos) / 1_000_000_000.0, MAX_DT);
        lastNanos = now;

        boolean moved = refreshTarget();

        if (!initialized) {
            return;
        }

        // 编辑器不可见（后台标签页、尚未完成初始化）时不绘制。
        // 否则拖尾会停在一个错误的位置，看起来就是"光标莫名悬浮"。
        if (!content.isShowing()) {
            panel.clearCorners();
            return;
        }

        // 内容区尺寸变化时同步面板大小（窗口缩放、分屏调整等）。
        // 同时置位 forceResync：布局刚变过，此前缓存的像素坐标可能已失效。
        if (panel.getWidth() != content.getWidth() || panel.getHeight() != content.getHeight()) {
            panel.setBounds(0, 0, Math.max(content.getWidth(), 1), Math.max(content.getHeight(), 1));
            lastDirtyRegion.setBounds(0, 0, 0, 0);
            forceResync = true;
        }

        // 目标点刚变化：按运动方向重新分配四个角点的滞后名次
        if (jumped) {
            assignRanks();
            jumped = false;
        }

        boolean anyAnimating = false;
        for (TrailCorner corner : corners) {
            if (corner.update(cursorWidth, cursorHeight, centerX, centerY, dt, false)) {
                anyAnimating = true;
            }
        }

        if (moved || anyAnimating) {
            panel.updateCorners(
                    corners[0].getCurrentX(), corners[0].getCurrentY(),
                    corners[1].getCurrentX(), corners[1].getCurrentY(),
                    corners[2].getCurrentX(), corners[2].getCurrentY(),
                    corners[3].getCurrentX(), corners[3].getCurrentY());
            repaintTrail();
        }

        // 动态帧率：有运动则保持高帧率，静止后降频轮询
        currentDelayMs = (moved || anyAnimating) ? ACTIVE_DELAY_MS : IDLE_DELAY_MS;

        recordFrameStats(now, moved || anyAnimating);
    }

    /**
     * 帧率与重绘面积的统计（运动期间每秒输出一次到 IDE 日志）。
     *
     * <p>用于客观区分两类卡顿：
     * <ul>
     *   <li>fps 低但重绘面积小 → 瓶颈在调度或 EDT 负载</li>
     *   <li>重绘面积大 → 瓶颈在重绘区域（拖尾过长或跨度过大）</li>
     * </ul>
     */
    private void recordFrameStats(long now, boolean active) {
        if (!active) {
            return;
        }
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now;
        }
        frameCount++;

        int area = currentBounds.width * currentBounds.height;
        if (area > peakRepaintArea) {
            peakRepaintArea = area;
        }

        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1_000_000_000L) {
            LOG.info(String.format(
                    "neovide-cursor: fps=%.1f  peakRepaintArea=%dpx  delay=%dms",
                    frameCount * 1_000_000_000.0 / elapsed, peakRepaintArea, currentDelayMs));
            frameCount = 0;
            peakRepaintArea = 0;
            fpsWindowStart = now;
        }
    }

    /**
     * 只重绘拖尾占据的小块区域。
     *
     * <p>覆盖整个编辑器的面板若每帧全量重绘（例如 1125×992），
     * 在快速移动光标时会明显掉帧。这里只重绘拖尾的包围盒加辉光余量。
     *
     * <p><b>关键</b>：上一帧区域与当前帧区域必须<b>分别</b>提交重绘请求，
     * 绝不能先 union 成一个大矩形 —— 大跨度移动（Tab 补全跳转、跨行删除）时
     * 两点相距很远，合并后的矩形会覆盖大半个编辑器，直接把优化抵消掉。
     */
    private void repaintTrail() {
        computeTrailBounds(currentBounds);

        // 上一帧的位置：必须重绘才能擦除残影
        repaintRegion(lastDirtyRegion);
        // 当前帧的位置
        repaintRegion(currentBounds);

        lastDirtyRegion.setBounds(currentBounds);
    }

    /** 把区域裁剪到面板范围内后提交重绘，越界部分直接丢弃 */
    private void repaintRegion(Rectangle region) {
        int x1 = Math.max(0, region.x);
        int y1 = Math.max(0, region.y);
        int x2 = Math.min(panel.getWidth(), region.x + region.width);
        int y2 = Math.min(panel.getHeight(), region.y + region.height);

        if (x2 > x1 && y2 > y1) {
            // 【必须用 repaint（异步），不要改成 paintImmediately（同步）】
            //
            // 曾试过 paintImmediately，理由是想省掉一次 EDT 调度往返，结果适得其反：
            // 它除了同步绘制本区域，还会顺带把 RepaintManager 中所有已排队的脏区
            // 一次性处理掉 —— 删除文本等操作会产生大量脏区，于是一次全被同步执行，
            // EDT 被长时间阻塞。
            //
            // 实测对比（同机同场景）：
            //   repaint           → 峰值 105.6 fps
            //   paintImmediately  → 峰值仅 36.9 fps，且重绘面积只有 1680px 时仍低至 8.9 fps
            panel.repaint(x1, y1, x2 - x1, y2 - y1);
        }
    }

    /** 计算四个角点的包围盒（含辉光余量），结果写入 out 以避免分配 */
    private void computeTrailBounds(Rectangle out) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (TrailCorner corner : corners) {
            double x = corner.getCurrentX();
            double y = corner.getCurrentY();
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
        }

        int pad = glowPadding;
        out.setBounds(
                (int) Math.floor(minX) - pad,
                (int) Math.floor(minY) - pad,
                (int) Math.ceil(maxX - minX) + pad * 2,
                (int) Math.ceil(maxY - minY) + pad * 2);
    }

    /**
     * 重新读取光标位置并更新动画目标点。
     *
     * @return true 表示位置确实发生了变化
     */
    private boolean refreshTarget() {
        try {
            VisualPosition vp = editor.getCaretModel().getVisualPosition();

            // 快速路径：视觉位置没变就直接返回，跳过昂贵的坐标换算。
            // 这是高帧率下最关键的性能优化 —— 绝大多数帧其实光标根本没动。
            // forceResync 用于布局刚变化的情形：视觉位置可能没变，
            // 但像素坐标已经失效，必须强制重算。
            if (!forceResync && vp.line == lastVisualLine && vp.column == lastVisualColumn) {
                return false;
            }
            forceResync = false;

            Point p = editor.visualPositionToXY(vp);
            lastVisualLine = vp.line;
            lastVisualColumn = vp.column;

            double height = editor.getLineHeight();
            double width = config.cursorWidth;

            double newCenterX = p.x + width / 2.0;
            double newCenterY = p.y + height / 2.0;

            if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) {
                return false;
            }

            cursorWidth = width;
            cursorHeight = height;
            centerX = newCenterX;
            centerY = newCenterY;

            if (!initialized) {
                // 首次定位：四个角直接吸附到位，避免从 (0,0) 飞入
                for (TrailCorner corner : corners) {
                    corner.snapTo(cursorWidth, cursorHeight, centerX, centerY);
                }
                initialized = true;
                jumped = false;
            } else {
                jumped = true;
            }
            return true;

        } catch (Throwable t) {
            // 编辑器正在销毁或重绘中：本次跳过，下一帧会自动重试
            return false;
        }
    }

    /**
     * 按「运动方向对齐度」给四个角点名次。
     *
     * <p>对齐度越高说明该角越位于运动前方，名次越靠前（rank 越小），
     * 它在 {@link TrailCorner#jump} 中拿到的滞后系数越大 —— 于是前缘紧跟、
     * 后缘拖沓，矩形被拉长成拖尾形状。
     */
    private void assignRanks() {
        double[] alignment = new double[corners.length];
        for (int i = 0; i < corners.length; i++) {
            alignment[i] = corners[i].calculateDirectionAlignment(
                    cursorWidth, cursorHeight, centerX, centerY);
        }

        // 求名次：对齐度更高的角 rank 更小（更跟手）
        int[] ranks = new int[corners.length];
        for (int i = 0; i < corners.length; i++) {
            int rank = 0;
            for (int j = 0; j < corners.length; j++) {
                if (alignment[j] < alignment[i]) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }

        for (int i = 0; i < corners.length; i++) {
            corners[i].jump(cursorWidth, cursorHeight, centerX, centerY, ranks[i]);
        }
    }

    // ==================== 清理 ====================

    @Override
    public void dispose() {
        disposed = true;

        Thread s = scheduler;
        if (s != null) {
            s.interrupt();
        }

        try {
            editor.getCaretModel().removeCaretListener(this);
        } catch (Throwable ignored) {
            // 编辑器可能已关闭
        }
        try {
            content.remove(panel);
            // 只 repaint，不 revalidate：避免触发 contentComponent 之外容器的重新布局
            content.repaint();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
