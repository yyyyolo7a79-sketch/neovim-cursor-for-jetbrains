package com.github.yyyolo7a79.neovidecursor.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 残影轨迹模型 —— 与弹簧模型并列的另一种拖尾实现。
 *
 * <p><b>与弹簧模型的根本区别</b>：
 * <ul>
 *   <li>弹簧模型在帧间<b>推算</b>光标位置 —— 掉帧时插值误差被放大，产生顿挫</li>
 *   <li>本模型记录每帧的<b>真实位置</b> —— 掉帧只会让残影稀疏一些，
 *       但轨迹本身始终真实、绝不失真</li>
 * </ul>
 * 因此它在低帧率环境下天生比弹簧模型稳定，这正是引入它的原因。
 *
 * <p><b>观感差异</b>：弹簧模型是"光标被拉长成四边形"，本模型是"一串渐隐的残影"。
 */
public class AfterimageTrail {

    /** 历史采样的最大数量，防止极端情况下无限增长 */
    private static final int MAX_GHOSTS = 48;

    /**
     * 相邻残影之间的最大间距（像素），超过则在两者之间补插值点。
     *
     * <p><b>这是拖尾能否成立的关键。</b>低帧率下每帧位移很大
     * （20fps 时一次跳转就可能跨 200px 以上），若只记录采样端点，
     * 得到的会是几个相距几十上百像素的孤立方块 —— 看起来就是"光标在跳"，
     * 完全没有拖尾感。按固定间距补点后，轨迹才是连续的。
     */
    private static final double MAX_GAP_PX = 5.0;

    /** 单次插值的最大补点数，防止极端跳转时瞬间产生上百个残影 */
    private static final int MAX_GAP_STEPS = 28;

    /** 一个历史采样点：某时刻光标所在的位置与尺寸 */
    public static final class Ghost {
        public final double centerX;
        public final double centerY;
        public final double width;
        public final double height;
        public final long timeNanos;

        Ghost(double centerX, double centerY, double width, double height, long timeNanos) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.timeNanos = timeNanos;
        }
    }

    private final Deque<Ghost> history = new ArrayDeque<>();

    private final NeovideConfig config;

    /** 残影存活时长（秒）：越大拖尾越长 */
    private double lifetime = 0.15;

    /** 复用的输出列表，避免每帧产生垃圾 */
    private final List<Ghost> visibleBuffer = new ArrayList<>(MAX_GHOSTS);

    public AfterimageTrail(NeovideConfig config) {
        this.config = config;
    }

    /**
     * 记录一次光标位置（每帧调用）。
     *
     * <p>位置未变化时不重复记录 —— 否则光标静止时会在同一处堆叠出大量重合残影，
     * 白白增加绘制开销。
     */
    public void record(double centerX, double centerY,
                       double width, double height, long nowNanos) {
        Ghost last = history.peekFirst();

        if (last != null) {
            // 位置未变化则不记录（避免静止时在同一处堆叠重合残影）
            if (last.centerX == centerX && last.centerY == centerY
                    && last.width == width && last.height == height) {
                return;
            }

            // 【关键】在两次采样之间补插值点，让残影沿轨迹均匀分布。
            // 队列是"新→旧"排列，因此按 t 递增顺序 addFirst 之后，
            // 队首到队尾自然的顺序就是：新点 → 插值点(靠近新) → … → 旧点
            double dx = centerX - last.centerX;
            double dy = centerY - last.centerY;
            double distance = Math.hypot(dx, dy);

            if (distance > MAX_GAP_PX) {
                int steps = (int) Math.min(MAX_GAP_STEPS,
                        Math.ceil(distance / MAX_GAP_PX));
                for (int i = 1; i < steps; i++) {
                    double t = (double) i / steps;
                    // 时间戳同步插值，使这些补点的 alpha 沿轨迹平滑过渡
                    long interpolatedTime = (long) (last.timeNanos
                            + (nowNanos - last.timeNanos) * t);
                    history.addFirst(new Ghost(
                            last.centerX + dx * t,
                            last.centerY + dy * t,
                            width, height, interpolatedTime));
                }
            }
        }

        history.addFirst(new Ghost(centerX, centerY, width, height, nowNanos));

        // 淘汰过期残影
        long cutoff = nowNanos - (long) (lifetime * 1_000_000_000L);
        while (!history.isEmpty() && history.peekLast().timeNanos < cutoff) {
            history.removeLast();
        }
        // 兜底：限制总量
        while (history.size() > MAX_GHOSTS) {
            history.removeLast();
        }
    }

    /**
     * 取出当前可见的残影（从新到旧排列）。
     *
     * <p>返回的是内部复用列表，调用方不应持有引用。
     */
    public List<Ghost> collectVisible(long nowNanos) {
        visibleBuffer.clear();
        long lifetimeNanos = (long) (lifetime * 1_000_000_000L);
        for (Ghost ghost : history) {
            if (nowNanos - ghost.timeNanos > lifetimeNanos) {
                break;
            }
            visibleBuffer.add(ghost);
        }
        return visibleBuffer;
    }

    /**
     * 计算某个残影的不透明度。
     *
     * <p>最新的残影接近完全不透明（它实际上充当了"光标本体"，
     * 因为原生光标已被隐藏），最老的则淡出到接近透明。
     */
    public float alphaOf(Ghost ghost, long nowNanos) {
        double lifetimeNanos = lifetime * 1_000_000_000.0;
        double age = (nowNanos - ghost.timeNanos) / lifetimeNanos;
        double t = Math.min(1.0, Math.max(0.0, age));

        // 衰减曲线取 0.7 次幂而非平方：低帧率下残影本就稀疏，
        // 平方衰减会让尾巴几乎瞬间消失，观感上更像"光标在跳"而非拖尾。
        double falloff = 1.0 - t;
        return (float) Math.pow(falloff, 0.7);
    }

    /** 清空历史（切换模式或禁用时调用） */
    public void clear() {
        history.clear();
    }

    public boolean isEmpty() {
        return history.isEmpty();
    }

    /** 残影存活时长（秒） */
    public double getLifetime() {
        return lifetime;
    }

    public void setLifetime(double lifetime) {
        this.lifetime = Math.max(0.02, lifetime);
    }
}
