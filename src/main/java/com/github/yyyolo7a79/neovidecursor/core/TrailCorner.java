package com.github.yyyolo7a79.neovidecursor.core;

/**
 * 拖尾角点 —— 光标矩形的四个角之一，每个角独立挂一组 X/Y 阻尼弹簧。
 *
 * <p><b>这是「拖尾」效果的核心机制。</b>光标移动时，按「运动方向与角点方向的夹角」
 * 给四个角分配不同的滞后系数：位于运动前方的角快速跟上（甚至瞬间硬吸附），
 * 位于后方的角缓慢跟随。于是原本规整的矩形被"拉长"成不规则四边形，
 * 视觉上就形成了拖尾 —— 不是叠加残影，而是光标自身被拉伸变形。
 *
 * <p>移植自 VS Code 版 {@code neovide-cursor.js} 的 {@code Corner} 类（第 103-208 行）。
 */
public class TrailCorner {

    /**
     * 动画至少跨越的帧数（低帧率适配）。
     *
     * <p>{@link DampedSpring#update} 开头有「animationLength 小于一帧就直接归零」的短路，
     * 本意是省掉无意义的极短动画。但在帧率只有 20~30fps 时 dt ≈ 0.03~0.05s，
     * 而相邻两行移动的动画时长恰为 0.015~0.05s —— 动画被整体吃掉，
     * 光标瞬间闪到目标，视觉上就是"卡"。这也是"十几行跳转反而更流畅"的原因。
     */
    private static final double MIN_ANIMATION_FRAMES = 6.0;

    /** 角点相对光标矩形的归一化位置，例如 (-0.5, -0.5) 表示左上角 */
    private final double relativeX;
    private final double relativeY;

    /** 角点方向的单位向量，用于计算方向对齐度 */
    private final double relativeNormX;
    private final double relativeNormY;

    /** 当前实际坐标 */
    private double currentX;
    private double currentY;

    /** 上一次的目标坐标（初始设为极远，保证首帧一定触发位移重算） */
    private double previousDestX = -1e5;
    private double previousDestY = -1e5;

    /** X / Y 两个方向各挂一个独立弹簧 */
    private final DampedSpring springX;
    private final DampedSpring springY;

    private final NeovideConfig config;

    public TrailCorner(double relativeX, double relativeY, NeovideConfig config) {
        this.relativeX = relativeX;
        this.relativeY = relativeY;
        this.config = config;

        // 归一化方向向量：长度为 1，只保留方向信息
        double len = Math.hypot(relativeX, relativeY);
        this.relativeNormX = len != 0 ? relativeX / len : 0;
        this.relativeNormY = len != 0 ? relativeY / len : 0;

        this.springX = new DampedSpring(config.animationLength);
        this.springY = new DampedSpring(config.animationLength);
    }

    // ==================== 目标位置计算 ====================

    private double destX(double centerX, double width) {
        return centerX + relativeX * width;
    }

    private double destY(double centerY, double height) {
        return centerY + relativeY * height;
    }

    /**
     * 计算「角点运动方向」与「角点相对方向」的对齐度。
     *
     * @return 范围 -1 ~ 1：接近 1 表示该角正位于运动前方，接近 -1 表示位于后方
     */
    public double calculateDirectionAlignment(double width, double height,
                                              double centerX, double centerY) {
        double dx = destX(centerX, width) - currentX;
        double dy = destY(centerY, height) - currentY;
        double len = Math.hypot(dx, dy);
        if (len == 0) {
            return 0;
        }
        return (dx / len) * relativeNormX + (dy / len) * relativeNormY;
    }

    // ==================== 弹簧参数分配 ====================

    /**
     * 光标跳动时调用：根据位移大小与运动方向，为该角点选择合适的弹簧时长。
     *
     * <p>分配规则（与原 JS 版一致）：
     * <ul>
     *   <li>位移小 → 用 shortAnimationLength，动作干脆</li>
     *   <li>角点位于运动最前方 → 硬吸附，瞬间到位，避免前缘拖糊</li>
     *   <li>其余按 rank 名次取对应滞后系数，越靠后越慢</li>
     * </ul>
     *
     * @param rank    按对齐度排序后的名次（0 = 最靠前，最跟手）
     * @param frameDt 当前帧间隔（秒），用于低帧率下的动画时长补偿；传 0 表示不补偿
     */
    public void jump(double width, double height, double centerX, double centerY,
                     int rank, double frameDt) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);

        // 用「光标自身尺寸」归一化位移，得到以"光标个数"为单位的位移量，
        // 这样阈值语义与光标字体大小无关
        double jumpX = (destX - previousDestX) / width;
        double jumpY = (destY - previousDestY) / height;

        double len = Math.hypot(jumpX, jumpY);
        double jumpNormX = len != 0 ? jumpX / len : 0;
        double jumpNormY = len != 0 ? jumpY / len : 0;

        boolean isShortMove = len <= config.shortMoveThreshold;
        double baseTime = isShortMove ? config.shortAnimationLength : config.animationLength;

        // 运动方向与该角方向的夹角余弦
        double alignment = jumpNormX * relativeNormX + jumpNormY * relativeNormY;

        // 位于运动最前方的角：硬吸附，瞬间到位
        boolean useSnap = config.useHardSnap && alignment > config.leadingSnapThreshold;

        double factor = useSnap
                ? config.leadingSnapFactor
                : (rank < config.trailFactors.length ? config.trailFactors[rank] : 1.0f);

        double animationLength = useSnap
                ? config.snapAnimationLength
                : baseTime * clamp(factor, 0, 1);

        // 【低帧率适配】保证动画至少跨越若干帧。
        //
        // DampedSpring.update() 开头的短路条件 `animationLength <= dt` 本意是
        // "动画比一帧还短就没必要做"，但帧率降到 20~30fps 时 dt ≈ 0.03~0.05s，
        // 恰好把相邻两行移动的短动画（0.015~0.05s）整体吃掉 ——
        // 表现为光标瞬间闪到目标位置、看起来"卡住"。
        //
        // 硬吸附（useSnap）的角本就该瞬间到位，不做延长。
        if (!useSnap && frameDt > 0) {
            animationLength = Math.max(animationLength, frameDt * MIN_ANIMATION_FRAMES);
        }

        springX.setAnimationLength(animationLength);
        springY.setAnimationLength(animationLength);

        // 只有动画足够长才重置弹簧：短动画重置反而会引入抖动
        if (animationLength > config.animationResetThreshold) {
            springX.reset();
            springY.reset();
        }
    }

    // ==================== 物理推进 ====================

    /**
     * 推进该角点的物理状态。
     *
     * @param immediate true = 立即到位（用于滚动等需要"贴死"的场景）
     * @return true 表示仍在运动，需要继续重绘
     */
    public boolean update(double width, double height, double centerX, double centerY,
                          double dt, boolean immediate) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);

        // 目标变化时，用「目标 - 当前实际」重算弹簧的偏移基准
        if (destX != previousDestX || destY != previousDestY) {
            springX.setPosition(destX - currentX);
            springY.setPosition(destY - currentY);
            previousDestX = destX;
            previousDestY = destY;
        }

        if (immediate) {
            currentX = destX;
            currentY = destY;
            springX.reset();
            springY.reset();
            return false;
        }

        springX.update(dt);
        springY.update(dt);

        // 限制最大偏移，防止跨屏跳转等极端情况产生夸张拉伸
        double maxDistance = Math.max(width, height) * config.maxTrailDistanceFactor;
        clampSpring(springX, maxDistance);
        clampSpring(springY, maxDistance);

        currentX = destX - springX.getPosition();
        currentY = destY - springY.getPosition();

        return Math.abs(springX.getPosition()) > 0.5 || Math.abs(springY.getPosition()) > 0.5;
    }

    /** 立即把角点摆到目标位置（初始化用） */
    public void snapTo(double width, double height, double centerX, double centerY) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);
        currentX = destX;
        currentY = destY;
        previousDestX = destX;
        previousDestY = destY;
        springX.reset();
        springY.reset();
    }

    private static void clampSpring(DampedSpring spring, double max) {
        double p = spring.getPosition();
        if (p > max) {
            spring.setPosition(max);
        } else if (p < -max) {
            spring.setPosition(-max);
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ==================== 访问器 ====================

    public double getCurrentX() {
        return currentX;
    }

    public double getCurrentY() {
        return currentY;
    }
}
