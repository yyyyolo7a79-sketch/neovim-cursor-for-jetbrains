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
     * @param rank      按对齐度升序的名次：0 = 最靠后（拿 trailFactors[0]=1，最慢），
     *                  3 = 最靠前（最跟手）。与原 JS 版排序方式一致
     * @param charWidth 半角字符宽（像素），用于短距离判定的横向下限；传 0 表示退回原版判据
     */
    public void jump(double width, double height, double centerX, double centerY,
                     int rank, double charWidth) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);

        // 用「光标自身尺寸」归一化位移，得到以"光标个数"为单位的位移量。
        // 这组值只用于求运动方向（与原版一致），短距离判定见下方
        double dx = destX - previousDestX;
        double jumpX = dx / width;
        double jumpY = (destY - previousDestY) / height;

        double len = Math.hypot(jumpX, jumpY);
        double jumpNormX = len != 0 ? jumpX / len : 0;
        double jumpNormY = len != 0 ? jumpY / len : 0;

        // 短距离判定：横向单位取「光标宽」与「按字符宽折算」中的较大者，
        // 使纯横向阈值 = max(16px, shortMoveMinChars × 字符宽)，随字号缩放。
        // 否则大字号下单个汉字就会被判成长距离（见 NeovideConfig#shortMoveMinChars）
        double shortUnitX = Math.max(width,
                charWidth * config.shortMoveMinChars / config.shortMoveThreshold);
        boolean isShortMove = Math.hypot(dx / shortUnitX, jumpY) <= config.shortMoveThreshold;
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

        // 【不做低帧率时长补偿】曾强制「至少跨越 6 帧」，结果短距离移动时
        // 四个角的时长被抬到同一值（0.015~0.05s 全变成 ~0.06s），梯度消失，
        // 光标只是整体平移、不再被拉伸。原版在 60fps 下最短的角本就一帧到位，
        // 这是设计行为而非缺陷，此处严格对齐原版。

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

    /**
     * 把角点直接摆到指定坐标，并视其为"上一次目标"（多光标新增时用）。
     *
     * <p>新光标从相邻光标的当前位置起飞：把 previousDest 也设为该点，
     * 下一次 {@link #jump} 算出的位移就是"相邻光标 → 新光标"的真实距离（通常一行），
     * 从而走短距离动画，而不是被初值 -1e5 误判成超远跳转。
     */
    public void placeAt(double x, double y) {
        currentX = x;
        currentY = y;
        previousDestX = x;
        previousDestY = y;
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

    /**
     * 当前角点相对目标的偏移量（像素）——即拖尾的"展开程度"。
     *
     * <p>完全静止时四角归位，偏移为 0；只要光标在动，偏移至少是一个字符宽或一行高。
     * 绘制辉光时据此淡入淡出：辉光是"运动中的光标"才有的效果，
     * 静止的光标不该顶着一圈光。
     */
    public double getOffsetMagnitude() {
        return Math.hypot(springX.getPosition(), springY.getPosition());
    }
}
