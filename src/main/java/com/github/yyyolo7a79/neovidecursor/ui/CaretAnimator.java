package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
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

    /** 失焦且静止时的轮询间隔：编辑器没有焦点时光标不会动，无需高响应度 */
    private static final int IDLE_UNFOCUSED_DELAY_MS = 600;

    /** 自适应调度的间隔上限（毫秒） */
    private static final int MAX_ADAPTIVE_DELAY_MS = 40;

    /** 单帧最大时间步长，防止 IDE 卡顿后弹簧"跳变" */
    private static final double MAX_DT = 1.0 / 30;

    /**
     * 辉光淡入淡出的偏移量区间（像素）。
     *
     * <p>辉光强度由四角相对目标的最大偏移量（即拖尾展开程度）驱动：
     * 偏移 ≤ {@link #GLOW_FADE_START} 视为静止，不画光晕；
     * 偏移 ≥ {@link #GLOW_FADE_END} 为满强度。
     *
     * <p>区间只有 2px 宽，因为这两个状态本来就离得很近：静止时偏移为 0，
     * 而任何真实移动的偏移至少是一个字符宽（约 8px）或一行高（约 32px）。
     * 窄区间既保证移动时光晕"完全没被动过"，又让停下时的熄灭不突兀。
     */
    private static final double GLOW_FADE_START = 0.5;
    private static final double GLOW_FADE_END = 2.5;

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

    /**
     * 是否为「静态光标」模式 —— 用于控制台类编辑器（{@link EditorKind#CONSOLE}）。
     *
     * <p><b>为什么必须处理它们</b>：原生光标是通过全局配色方案
     * （把 {@code CARET_COLOR} 置为全透明）隐藏的，而这个操作<b>无法按编辑器区分</b> ——
     * {@code Editor} / {@code CaretModel} / {@code Caret} 三个接口均已确认不存在
     * per-editor 的光标隐藏 API。因此 Build / Run / Services / Problems 等工具窗口的
     * 输出区一旦被全局隐藏，若不补画光标，<b>那些位置的光标就会彻底消失</b>。
     *
     * <p>这类编辑器只需要一个静止的光标，不需要拖尾，因此走静态分支：
     * 每帧把四角直接吸附到目标位置（等价于画一个矩形），且不绘制辉光。
     */
    private final boolean staticCaret;

    // ===== 残影模型（trailMode = AFTERIMAGE 时使用）=====

    private final AfterimageTrail afterimageTrail;

    /** 残影绘制数据缓冲（每项 [x, y, w, h, alpha]），避免每帧重建列表 */
    private final List<float[]> ghostBuffer = new ArrayList<>(32);

    /** 辉光最大外扩量，用于计算重绘区域需要留出的余量（随光标尺寸动态更新） */
    private int glowPadding;

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

    /** 上一次 tick 的绝对时刻，用于测量真实帧间隔 */
    private long lastTickNanos = 0L;

    /** 实测帧间隔的平滑值（毫秒），供自适应调度使用 */
    private double smoothedIntervalMs = ACTIVE_DELAY_MS;

    /**
     * 平滑后的帧间隔（秒），用于低帧率下的动画补偿。
     *
     * <p>低帧率时瞬时 dt 波动很大（15~40fps 意味着 0.025~0.067s），
     * 若直接用它计算动画时长，弹簧的时间常数会每帧跳变，运动就不连续了。
     */
    private double smoothedDt = 1.0 / 60.0;

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

        // 控制台类编辑器（Build / Run / Services / Problems 的输出区）只补画静止光标，
        // 不做拖尾 —— 它们的光标被全局隐藏了，必须有人补上，原因见 staticCaret 字段说明。
        this.staticCaret = editor.getEditorKind() == EditorKind.CONSOLE;

        this.corners = new TrailCorner[CORNER_RELATIVE.length];
        for (int i = 0; i < CORNER_RELATIVE.length; i++) {
            corners[i] = new TrailCorner(CORNER_RELATIVE[i][0], CORNER_RELATIVE[i][1], config);
        }

        this.afterimageTrail = new AfterimageTrail(config);
        afterimageTrail.setLifetime(config.afterimageLifetime);

        // 重绘区域必须覆盖辉光的外扩部分，否则光晕边缘会残留脏像素。
        // 辉光半径 = shadowBlurFactor × 光标较长边，这里先用典型行高 32 估算，
        // 之后 refreshTarget 拿到真实光标尺寸时会修正。
        this.glowPadding = (int) Math.ceil(Math.max(3f, config.shadowBlurFactor * 32f)) + 4;

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

        // 测量真实帧间隔（指数平滑）。变快时快速跟随、变慢时缓慢跟随，
        // 避免偶发的一次卡顿把调度频率长期压低。
        if (lastTickNanos != 0L) {
            double actualMs = (now - lastTickNanos) / 1_000_000.0;
            double weight = actualMs < smoothedIntervalMs ? 0.4 : 0.1;
            smoothedIntervalMs += (actualMs - smoothedIntervalMs) * weight;
        }
        lastTickNanos = now;

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
        // 平滑帧间隔：低帧率下 dt 波动大，直接用于计算动画时长会让弹簧的
        // 时间常数每帧跳变、运动不连续。
        //
        // 但必须取「平滑值」与「瞬时值」的较大者：平滑需要好几帧才收敛，
        // 而动画本身可能就只有 4~6 帧 —— 若直接用平滑值，
        // 开头几帧的补偿会严重不足，动画照样被 dt 吃掉（实测帧数反而更少）。
        smoothedDt += (dt - smoothedDt) * 0.25;
        double effectiveDt = Math.max(dt, smoothedDt);

        boolean anyAnimating;
        if (staticCaret) {
            // 控制台编辑器：只补画静止光标，不参与拖尾
            anyAnimating = tickStaticCaret();
        } else if (config.trailMode == NeovideConfig.TrailMode.AFTERIMAGE) {
            anyAnimating = tickAfterimage(now, moved);
        } else {
            anyAnimating = tickSpring(dt, effectiveDt);
        }

        currentDelayMs = computeNextDelay(moved || anyAnimating);

        recordFrameStats(now, moved || anyAnimating);
    }

    /**
     * 计算下一次调度间隔。
     *
     * <p>三项策略叠加：
     * <ol>
     *   <li><b>自适应</b>：AWT 的 EventQueue 中，原生输入事件（键盘/鼠标）的优先级
     *       高于 {@code invokeLater} 提交的任务 —— 编辑器繁忙时我们的渲染任务会被排到后面，
     *       实测帧间隔远大于设定的 8ms。此时若继续按 8ms 提交，绝大多数任务会因
     *       framePending 被直接丢弃，纯粹浪费 EDT 时间。改为跟随实测间隔，减少无效提交。</li>
     *   <li><b>失焦降频</b>：编辑器没有焦点时光标不会移动，无需高响应度。</li>
     *   <li><b>静止降频</b>：完全没有动画时只做低频看门狗轮询。</li>
     * </ol>
     */
    private int computeNextDelay(boolean active) {
        if (active) {
            int adaptive = (int) Math.round(smoothedIntervalMs * 0.85);
            return Math.max(ACTIVE_DELAY_MS, Math.min(adaptive, MAX_ADAPTIVE_DELAY_MS));
        }
        return isEditorFocused() ? IDLE_DELAY_MS : IDLE_UNFOCUSED_DELAY_MS;
    }

    /** 编辑器当前是否持有键盘焦点 */
    private boolean isEditorFocused() {
        try {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return owner != null && SwingUtilities.isDescendingFrom(owner, content);
        } catch (Throwable t) {
            // 判断失败时按「有焦点」处理，确保功能不因此丢失
            return true;
        }
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

            // 辉光半径与重绘余量随光标尺寸同步更新
            panel.updateGlowRadius(width, height);
            glowPadding = (int) Math.ceil(panel.getGlowWidth()) + 4;

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

    // ==================== 两种渲染模式 ====================

    /**
     * 静态光标模式的一帧：把四角直接吸附到目标位置，不产生任何拖尾。
     *
     * <p>仅用于控制台类编辑器（Build / Run / Services / Problems 等输出区）——
     * 它们的光标被全局隐藏了，必须有人补画，详见 {@link #staticCaret}。
     *
     * <p>返回值恒为 false，让调度器保持在空闲频率：控制台光标不需要高帧率，
     * 仅在 caret 事件唤醒时才会短暂提速。
     */
    private boolean tickStaticCaret() {
        for (TrailCorner corner : corners) {
            corner.snapTo(cursorWidth, cursorHeight, centerX, centerY);
        }

        // 不绘制辉光：控制台不需要，也省掉这部分重绘开销
        panel.setGlowStrength(0f);

        panel.updateCorners(
                corners[0].getCurrentX(), corners[0].getCurrentY(),
                corners[1].getCurrentX(), corners[1].getCurrentY(),
                corners[2].getCurrentX(), corners[2].getCurrentY(),
                corners[3].getCurrentX(), corners[3].getCurrentY());

        computeTrailBounds(currentBounds);
        submitRepaint();
        return false;
    }

    /** 弹簧模式的一帧：推动物理模拟并绘制四边形 */
    private boolean tickSpring(double dt, double effectiveDt) {
        if (jumped) {
            assignRanks(effectiveDt);
            jumped = false;
        }

        boolean anyAnimating = false;
        double maxOffset = 0;
        for (TrailCorner corner : corners) {
            if (corner.update(cursorWidth, cursorHeight, centerX, centerY, dt, false)) {
                anyAnimating = true;
            }
            double offset = corner.getOffsetMagnitude();
            if (offset > maxOffset) {
                maxOffset = offset;
            }
        }

        // 辉光只在拖尾展开时点亮 —— 静止的光标顶着一圈光晕并不好看，
        // 那是"运动中"才该有的效果。强度由最大偏移量映射，边界见 GLOW_FADE_*。
        panel.setGlowStrength((float) ((maxOffset - GLOW_FADE_START)
                / (GLOW_FADE_END - GLOW_FADE_START)));

        panel.updateCorners(
                corners[0].getCurrentX(), corners[0].getCurrentY(),
                corners[1].getCurrentX(), corners[1].getCurrentY(),
                corners[2].getCurrentX(), corners[2].getCurrentY(),
                corners[3].getCurrentX(), corners[3].getCurrentY());

        computeTrailBounds(currentBounds);
        submitRepaint();
        return anyAnimating;
    }

    /**
     * 残影模式的一帧：记录当前位置并绘制历史残影。
     *
     * <p>不涉及任何物理模拟或帧间插值 —— 位置直接来自每帧的真实采样，
     * 因此掉帧只会让残影稀疏，轨迹本身绝不失真。
     * 这正是它比弹簧模型更适合低帧率环境的原因。
     */
    private boolean tickAfterimage(long now, boolean moved) {
        afterimageTrail.record(centerX, centerY, cursorWidth, cursorHeight, now);

        List<AfterimageTrail.Ghost> visible = afterimageTrail.collectVisible(now);

        ghostBuffer.clear();

        // ---- 第一部分：残影尾巴 ----
        // 跳过与当前光标位置重合的采样点，否则会和下面那个常驻光标叠在一起、
        // 半透明颜色叠加导致该处明显偏深。
        for (AfterimageTrail.Ghost ghost : visible) {
            if (ghost.centerX == centerX && ghost.centerY == centerY) {
                continue;
            }
            float alpha = afterimageTrail.alphaOf(ghost, now);
            if (alpha <= 0.02f) {
                continue;
            }
            ghostBuffer.add(new float[]{
                    (float) (ghost.centerX - ghost.width / 2.0),
                    (float) (ghost.centerY - ghost.height / 2.0),
                    (float) ghost.width,
                    (float) ghost.height,
                    alpha
            });
        }

        boolean hasTail = !ghostBuffer.isEmpty();

        // ---- 第二部分：常驻光标 ----
        // 残影会在 lifetime 到期后淡出，若只画残影，光标静止一会儿画面就空了。
        // 因此额外绘制一个始终完全不透明的"光标本体"。
        ghostBuffer.add(new float[]{
                (float) (centerX - cursorWidth / 2.0),
                (float) (centerY - cursorHeight / 2.0),
                (float) cursorWidth,
                (float) cursorHeight,
                1.0f
        });

        if (moved || hasTail) {
            panel.updateGhosts(ghostBuffer);
            computeGhostBounds(currentBounds);
            submitRepaint();
        }

        // 只有尾巴还在渐隐时才需要持续高帧率；只剩常驻光标时无需空转
        return hasTail;
    }

    /** 提交重绘：本帧区域 + 上一帧区域（后者用于擦除残影） */
    private void submitRepaint() {
        // 性能诊断模式：跳过全部重绘，只跑计算
        if (NeovideCaretManager.isPaintDisabled()) {
            return;
        }

        repaintRegion(lastDirtyRegion);
        repaintRegion(currentBounds);
        lastDirtyRegion.setBounds(currentBounds);
    }

    /** 计算残影列表的包围盒（含辉光余量），结果写入 out 以避免分配 */
    private void computeGhostBounds(Rectangle out) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (float[] shape : ghostBuffer) {
            if (shape[0] < minX) {
                minX = shape[0];
            }
            if (shape[1] < minY) {
                minY = shape[1];
            }
            if (shape[0] + shape[2] > maxX) {
                maxX = shape[0] + shape[2];
            }
            if (shape[1] + shape[3] > maxY) {
                maxY = shape[1] + shape[3];
            }
        }

        int pad = glowPadding;
        out.setBounds(
                (int) Math.floor(minX) - pad,
                (int) Math.floor(minY) - pad,
                (int) Math.ceil(maxX - minX) + pad * 2,
                (int) Math.ceil(maxY - minY) + pad * 2);
    }

    /** 渲染模式切换后调用：清空两种模型的内部状态，避免残留 */
    public void onModeChanged() {
        afterimageTrail.clear();
        afterimageTrail.setLifetime(config.afterimageLifetime);

        for (TrailCorner corner : corners) {
            corner.snapTo(cursorWidth, cursorHeight, centerX, centerY);
        }

        panel.clearCorners();
        panel.refreshStyles();

        // 让下一帧强制重算位置，避免快速路径把旧状态固化
        forceResync = true;
        lastDirtyRegion.setBounds(0, 0, 0, 0);
        wakeUp();
    }

    /**
     * 按「运动方向对齐度」给四个角点名次。
     *
     * <p>对齐度越高说明该角越位于运动前方，名次越靠前（rank 越小），
     * 它在 {@link TrailCorner#jump} 中拿到的滞后系数越大 —— 于是前缘紧跟、
     * 后缘拖沓，矩形被拉长成拖尾形状。
     */
    private void assignRanks(double dt) {
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
            corners[i].jump(cursorWidth, cursorHeight, centerX, centerY, ranks[i], dt);
        }
    }

    /** 清除已绘制的拖尾并刷新一次（切到诊断模式时调用） */
    public void clearTrail() {
        panel.clearCorners();
        try {
            content.repaint();
        } catch (Throwable ignored) {
            // 忽略
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
