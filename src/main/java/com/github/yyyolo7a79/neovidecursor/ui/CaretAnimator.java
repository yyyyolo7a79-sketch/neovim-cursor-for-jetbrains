package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 参与拖尾动画的光标数量上限（自我保护，对齐原版 MAX_CURSORS = 40）。
     *
     * <p>Alt + 拖拽列选跨上千行时会产生上千个光标，全部带辉光绘制会拖垮 EDT。
     * 超出部分不做拖尾 —— 叠加模式下它们的原生光标仍正常显示，不影响使用。
     */
    private static final int MAX_TRAILS = 64;

    private final Editor editor;
    private final JComponent content;
    private final CaretTrailPanel panel;
    private final NeovideConfig config;

    /**
     * 每个光标一份弹簧拖尾状态（多光标支持）。
     *
     * <p>以 {@link Caret} 对象<b>身份</b>为键：IntelliJ 在光标移动时复用同一个 Caret 实例，
     * 因此新增/删除某个光标时，其余光标的拖尾不会因列表下标错位而集体跳一行。
     */
    private final Map<Caret, SpringTrail> trails = new IdentityHashMap<>();

    /** 本帧参与动画的拖尾（按光标列表顺序），复用以避免每帧分配 */
    private final List<SpringTrail> activeTrails = new ArrayList<>();

    /** 绘制时复用的角点坐标缓冲 */
    private final double[] quadX = new double[4];
    private final double[] quadY = new double[4];

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

    /**
     * 下一次希望被 tick 的时刻（System.nanoTime）。
     * 由 EDT 写入、{@link RenderScheduler} 的调度线程读取。
     */
    volatile long nextDueNanos = System.nanoTime();

    /** 当前调度间隔（毫秒） */
    private int currentDelayMs = IDLE_DELAY_MS;

    /** 是否已销毁 */
    private volatile boolean disposed = false;

    // ===== 性能统计（每秒输出一次，用于客观定位卡顿来源）=====

    private static final Logger LOG = Logger.getInstance(CaretAnimator.class);

    private int frameCount = 0;
    private int peakRepaintArea = 0;
    private long fpsWindowStart = 0L;

    /** 上一个活动帧的时刻：两次活动间隔过长时重开统计窗口，避免空闲时间拉低 fps */
    private long lastActiveFrameNanos = 0L;

    /** 空闲超过该时长（纳秒）即视为一段新的运动，统计窗口重新开始 */
    private static final long FPS_IDLE_GAP_NANOS = 200_000_000L;

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
     * 叠加模式（保留原生光标）下最后一次处于运动状态的时刻。
     * 停止运动后经过 cursorDisappearDelay 再用 cursorFadeOutDuration 淡出，
     * 对齐原版 canvas 的 opacity 过渡。初值 0 = 启动时不显示覆盖层。
     */
    private long lastAnimatingNanos = 0L;

    // ===== 动画目标状态 =====

    /** 主光标矩形中心点（contentComponent 坐标系），残影模式使用 */
    private double centerX;
    private double centerY;

    private double cursorWidth;
    private double cursorHeight;

    /**
     * 半角字符宽（像素），用于短距离判定的横向下限（见 NeovideConfig#shortMoveMinChars）。
     * 字体/字号变化时 lineHeight 一并变化，届时随之刷新。
     */
    private double charWidth;

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

        RenderScheduler.register(this);
    }

    // ==================== 渲染调度 ====================

    /** 由 {@link RenderScheduler} 在 EDT 上调用：执行一帧并登记下一次到期时刻 */
    void runTick() {
        if (disposed) {
            return;
        }
        try {
            tick();
        } finally {
            nextDueNanos = System.nanoTime() + currentDelayMs * 1_000_000L;
        }
    }

    /**
     * 立即切到高帧率并唤醒调度线程（否则最长要等一个 IDLE 周期才响应）。
     *
     * <p><b>必须同时重置 {@link #lastNanos}</b>：空闲档（120ms / 失焦 600ms）期间
     * {@code tick()} 不跑，{@code lastNanos} 就停在最后一次 tick 上。若只把
     * {@code currentDelayMs} / {@code nextDueNanos} 归位，唤醒后的<b>第一帧</b>
     * 仍会用 {@code dt = now - lastNanos} = 整段空闲期来计算，被 {@code MAX_DT}
     * 截断到约 33.3ms —— 弹簧一帧就走完绝大部分位移，视觉上是"瞬移"。
     *
     * <p>以 {@code animationLength = 0.05}、{@code o = 4/animationLength = 80} 为例：
     * <pre>
     *   dt = 0.008s（正常）  → c = exp(-0.64) ≈ 0.527  → 一帧后剩 47%
     *   dt = 0.0333s（空闲后）→ c = exp(-2.67) ≈ 0.070  → 一帧后仅剩 7%
     * </pre>
     * 这正是「停手几秒后第一次删除到上一行，会卡一下」的成因；
     * 而连续快速删除反而流畅 —— 因为那时 {@code lastNanos} 每 8ms 都在更新。
     *
     * <p><b>同理还要重置帧间隔估计量 {@link #smoothedIntervalMs}</b>：空闲档的 tick
     * 间隔（120 / 600ms）也被当作"真实帧间隔"喂给了它。停顿 1 秒后它会涨到约 50ms，
     * 唤醒后 {@code computeNextDelay()} 据此把调度间隔算成 40ms（25fps），
     * 还要 0.5~0.7s 才能逐帧收敛回 8ms —— 而一次拖尾动画本身只有 0.05~0.1s，
     * 整段动画都是以 25fps 播完的。跨越空闲期的那一次测量同样要丢弃。
     *
     * <p><b>只在「从空闲档唤醒」时重置</b>（判据是唤醒前所处的档位，而非估计量本身）：
     * {@code wakeUp()} 每次击键都会被调用，活跃档下 {@code lastNanos} 本就是新的；
     * 而编辑器繁忙时偏大的估计量正是自适应调度要跟踪的真实输入，不能每次击键都按回 8ms。
     */
    private void wakeUp() {
        long now = System.nanoTime();
        boolean fromIdleTier = currentDelayMs > MAX_ADAPTIVE_DELAY_MS;
        if (fromIdleTier) {
            lastNanos = now;
            smoothedIntervalMs = ACTIVE_DELAY_MS;
            lastTickNanos = 0L;
        }
        currentDelayMs = ACTIVE_DELAY_MS;
        nextDueNanos = now;
        RenderScheduler.wakeUp();
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

    /** 多光标新增（Alt + 拖拽列选、Alt+Shift+点击等） */
    @Override
    public void caretAdded(@NotNull CaretEvent event) {
        wakeUp();
    }

    @Override
    public void caretRemoved(@NotNull CaretEvent event) {
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

        boolean anyAnimating;
        if (staticCaret) {
            // 控制台编辑器：只补画静止光标，不参与拖尾
            anyAnimating = tickStaticCaret(moved);
        } else if (config.trailMode == NeovideConfig.TrailMode.AFTERIMAGE) {
            anyAnimating = tickAfterimage(now, moved);
        } else {
            anyAnimating = tickSpring(now, dt);
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
        // 距上一个活动帧太久：这是一段新的运动，重开窗口。
        // 修复前空闲时间也计入分母 —— 按一下键动画 10 帧、停 2 秒再按，
        // 日志会报 fps≈5，而真实帧率是 100。
        if (fpsWindowStart == 0L || now - lastActiveFrameNanos > FPS_IDLE_GAP_NANOS) {
            fpsWindowStart = now;
            frameCount = 0;
            peakRepaintArea = 0;
        }
        lastActiveFrameNanos = now;
        frameCount++;

        int area = currentBounds.width * currentBounds.height;
        if (area > peakRepaintArea) {
            peakRepaintArea = area;
        }

        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1_000_000_000L && frameCount > 1) {
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

        for (SpringTrail trail : activeTrails) {
            for (var corner : trail.corners) {
                double x = corner.getCurrentX();
                double y = corner.getCurrentY();
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (activeTrails.isEmpty()) {
            out.setBounds(0, 0, 0, 0);
            return;
        }

        int pad = glowPadding;
        out.setBounds(
                (int) Math.floor(minX) - pad,
                (int) Math.floor(minY) - pad,
                (int) Math.ceil(maxX - minX) + pad * 2,
                (int) Math.ceil(maxY - minY) + pad * 2);
    }

    /**
     * 重新读取所有光标的位置并更新各自的动画目标点。
     *
     * <p>多光标（Alt + 鼠标拖拽列选等）时每个光标各有一份 {@link SpringTrail}。
     * 新出现的光标从<b>相邻光标</b>的当前形状起飞 —— 列选向下拖时，
     * 新光标会从上一行"滑"下来，对应原版为新 {@code .cursor} 指定 jumpSource 的做法。
     *
     * @return true 表示有光标位置变化，或光标集合发生了增删
     */
    private boolean refreshTarget() {
        try {
            List<Caret> carets = editor.getCaretModel().getAllCarets();
            Caret primary = editor.getCaretModel().getPrimaryCaret();
            double height = editor.getLineHeight();
            double width = config.cursorWidth;
            long stamp = editor.getDocument().getModificationStamp();

            // forceResync：布局刚变化，视觉位置可能没变但像素坐标已失效，必须绕过快速路径
            boolean resync = forceResync;
            forceResync = false;

            int count = Math.min(carets.size(), MAX_TRAILS);
            boolean changed = count != activeTrails.size();

            activeTrails.clear();
            SpringTrail previous = null;
            for (int i = 0; i < count; i++) {
                Caret caret = carets.get(i);
                SpringTrail trail = trails.get(caret);
                if (trail == null) {
                    trail = new SpringTrail(config);
                    SpringTrail source = previous != null ? previous : firstInitializedTrail();
                    if (source != null) {
                        trail.startFrom(source);
                    }
                    trails.put(caret, trail);
                    changed = true;
                }
                activeTrails.add(trail);
                previous = trail;

                // 快速路径：视觉位置与文档都没变就跳过昂贵的 visualPositionToXY 换算 ——
                // 高帧率下绝大多数帧光标根本没动。修改戳的作用见 SpringTrail#isAt
                VisualPosition vp = caret.getVisualPosition();
                if (resync || !trail.isInitialized() || !trail.isAt(vp.line, vp.column, stamp)) {
                    Point p = editor.visualPositionToXY(vp);
                    trail.markAt(vp.line, vp.column, stamp);
                    if (trail.setTarget(p.x + width / 2.0, p.y + height / 2.0, width, height)) {
                        changed = true;
                    }
                }

                if (caret == primary) {
                    centerX = trail.centerX;
                    centerY = trail.centerY;
                }
            }

            // 回收已消失的光标（或超出上限的光标）
            if (trails.size() > activeTrails.size()) {
                trails.values().removeIf(t -> !activeTrails.contains(t));
                changed = true;
            }

            if (height != cursorHeight || width != cursorWidth) {
                cursorWidth = width;
                cursorHeight = height;
                charWidth = EditorUtil.getPlainSpaceWidth(editor);
                // 辉光半径与重绘余量随光标尺寸同步更新
                panel.updateGlowRadius(width, height);
                glowPadding = (int) Math.ceil(panel.getGlowWidth()) + 4;
            }

            initialized = !activeTrails.isEmpty();
            return changed;

        } catch (Throwable t) {
            // 编辑器正在销毁或重绘中：本次跳过，下一帧会自动重试
            return false;
        }
    }

    /** 任取一个已就位的拖尾，作为新光标的起飞点 */
    private SpringTrail firstInitializedTrail() {
        for (SpringTrail t : trails.values()) {
            if (t.isInitialized()) {
                return t;
            }
        }
        return null;
    }

    // ==================== 两种渲染模式 ====================

    /**
     * 静态光标模式的一帧：把四角直接吸附到目标位置，不产生任何拖尾。
     *
     * <p>仅用于控制台类编辑器（Build / Run / Services / Problems 等输出区）——
     * 它们的光标被全局隐藏了，必须有人补画，详见 {@link #staticCaret}。
     *
     * <p>只在光标移动（或布局变化）时重绘：修复前每个 tick 都无条件重绘，
     * 每个控制台每 120ms 重绘一次、永不停止。
     *
     * <p>返回值恒为 false，让调度器保持在空闲频率。
     */
    private boolean tickStaticCaret(boolean moved) {
        if (!moved && panel.hasContent()) {
            return false;
        }
        for (SpringTrail trail : activeTrails) {
            trail.snapToTarget();
        }

        // 不绘制辉光：控制台不需要，也省掉这部分重绘开销
        panel.setOverlayAlpha(1f);
        pushQuads(false);
        computeTrailBounds(currentBounds);
        submitRepaint();
        return false;
    }

    /**
     * 弹簧模式的一帧：推动物理模拟并绘制四边形。
     *
     * <p>叠加模式（{@code hideNativeCaret = false}，默认）下对齐原版 canvas 的显隐：
     * 运动中覆盖层不透明度为 1；停止后等待 cursorDisappearDelay，
     * 再用 cursorFadeOutDuration 线性淡出 —— 此后只剩原生光标。
     *
     * @return true 表示仍需高帧率（弹簧在动，或正在淡出）
     */
    private boolean tickSpring(long now, double dt) {
        boolean anyAnimating = false;
        for (SpringTrail trail : activeTrails) {
            if (trail.update(dt, charWidth)) {
                anyAnimating = true;
            }
        }

        boolean fading = false;
        if (config.hideNativeCaret) {
            // 常驻模式：覆盖层就是光标本体，始终可见
            panel.setOverlayAlpha(1f);
        } else {
            if (anyAnimating) {
                lastAnimatingNanos = now;
            }
            float alpha = overlayAlpha(now);
            if (alpha <= 0f) {
                // 已完全淡出：只需擦掉最后一帧，此后不再重绘
                if (panel.hasContent()) {
                    panel.clearCorners();
                    submitClear();
                }
                return false;
            }
            panel.setOverlayAlpha(alpha);
            fading = alpha < 1f || !anyAnimating;
        }

        pushQuads(true);
        computeTrailBounds(currentBounds);
        submitRepaint();
        return anyAnimating || fading;
    }

    /** 叠加模式下覆盖层的不透明度：停止后延迟 → 线性淡出 → 0 */
    private float overlayAlpha(long now) {
        if (lastAnimatingNanos == 0L) {
            return 0f;
        }
        double sinceStop = (now - lastAnimatingNanos) / 1_000_000_000.0
                - config.cursorDisappearDelay / 1000.0;
        if (sinceStop <= 0) {
            return 1f;
        }
        double fade = config.cursorFadeOutDuration;
        if (fade <= 0 || sinceStop >= fade) {
            return 0f;
        }
        return (float) (1.0 - sinceStop / fade);
    }

    /**
     * 把所有拖尾的四边形写入绘制面板。
     *
     * @param withGlow 是否按拖尾展开程度点亮辉光 —— 静止的光标顶着一圈光晕并不好看，
     *                 那是"运动中"才该有的效果。强度由最大偏移量映射，边界见 GLOW_FADE_*
     */
    private void pushQuads(boolean withGlow) {
        panel.beginQuads();
        for (SpringTrail trail : activeTrails) {
            for (int j = 0; j < 4; j++) {
                quadX[j] = trail.corners[j].getCurrentX();
                quadY[j] = trail.corners[j].getCurrentY();
            }
            float glow = withGlow
                    ? (float) ((trail.maxOffset() - GLOW_FADE_START) / (GLOW_FADE_END - GLOW_FADE_START))
                    : 0f;
            panel.addQuad(quadX, quadY, glow);
        }
    }

    /** 擦除上一帧绘制的区域（覆盖层淡出完毕时调用） */
    private void submitClear() {
        if (NeovideCaretManager.isPaintDisabled()) {
            return;
        }
        repaintRegion(lastDirtyRegion);
        lastDirtyRegion.setBounds(0, 0, 0, 0);
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

        // ---- 第二部分：常驻光标（仅隐藏原生光标时）----
        // 残影会在 lifetime 到期后淡出，若只画残影，光标静止一会儿画面就空了。
        // 因此额外绘制一个始终完全不透明的"光标本体"。
        // 叠加模式下原生光标仍在，再画本体就成了双光标，故跳过。
        if (config.hideNativeCaret) {
            ghostBuffer.add(new float[]{
                    (float) (centerX - cursorWidth / 2.0),
                    (float) (centerY - cursorHeight / 2.0),
                    (float) cursorWidth,
                    (float) cursorHeight,
                    1.0f
            });
        }

        if (ghostBuffer.isEmpty()) {
            // 尾巴已全部淡出且无本体：擦掉最后一帧即可
            if (panel.hasContent()) {
                panel.clearCorners();
                submitClear();
            }
            return false;
        }

        panel.setOverlayAlpha(1f);
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

        for (SpringTrail trail : trails.values()) {
            trail.snapToTarget();
        }

        panel.clearCorners();
        panel.refreshStyles();

        // 让下一帧强制重算位置，避免快速路径把旧状态固化
        forceResync = true;
        lastDirtyRegion.setBounds(0, 0, 0, 0);
        wakeUp();
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

        RenderScheduler.unregister(this);

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
