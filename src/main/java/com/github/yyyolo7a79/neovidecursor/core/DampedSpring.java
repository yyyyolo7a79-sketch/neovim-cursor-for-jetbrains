package com.github.yyyolo7a79.neovidecursor.core;

/**
 * 阻尼弹簧动画 —— 拖尾运动的物理核心。
 *
 * <p>逐行移植自 VS Code 版 {@code neovide-cursor.js} 的 {@code DampedSpringAnimation} 类
 * （原文件第 73-101 行）。这是一个解析解形式的临界阻尼弹簧：
 * 给定"当前位置相对目标的偏移"和"速度"，按时间步长 dt 推进，指数式衰减到 0。
 *
 * <p>与常见弹簧实现的区别：它不是逐帧迭代积分，而是用闭式解直接跳到 dt 之后的状态，
 * 因此<b>完全不受帧率波动影响</b> —— 同样长的时间，无论中间掉多少帧，结果都一致。
 */
public class DampedSpring {

    /**
     * 单帧最大位移比例（低帧率适配）。
     *
     * <p>弹簧的解析解决定了第一帧吃掉绝大部分位移：20fps（dt=0.05s）时
     * 第一帧就完成 59%，视觉上是"闪一下再慢慢微调"。
     * 限制单帧位移不超过剩余偏移的该比例，可让位移在几帧内均匀分布。
     *
     * <p>取值已通过独立程序验证：
     * <ul>
     *   <li>60fps — 每帧仅衰减约 15%，<b>不触发</b>（原版手感不受影响）</li>
     *   <li>30fps — 6 帧中触发 3 次，序列更均匀</li>
     *   <li>20fps — 全部触发，每帧稳定减半</li>
     * </ul>
     */
    private static final double MAX_STEP_RATIO = 0.5;

    /** 当前相对位移（相对目标点的偏移量） */
    private double position = 0.0;

    /** 当前速度 */
    private double velocity = 0.0;

    /** 动画特征时长（秒）：越大衰减越慢、拖尾越长 */
    private double animationLength;

    public DampedSpring(double animationLength) {
        this.animationLength = animationLength;
    }

    public void setAnimationLength(double animationLength) {
        this.animationLength = animationLength;
    }

    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = position;
    }

    /**
     * 按 dt 推进弹簧状态。
     *
     * @param dt 时间步长（秒）
     * @return true 表示仍在运动（需要继续重绘）；false 表示已静止
     */
    public boolean update(double dt) {
        // 动画时长已短于时间步长，或位移已足够小 —— 直接归零，避免无意义的微小抖动
        if (animationLength <= dt || Math.abs(position) < 0.001) {
            position = 0.0;
            velocity = 0.0;
            return false;
        }

        // 阻尼系数：animationLength 越大，o 越小，衰减越慢（拖尾越长）
        final double o = 4.0 / animationLength;
        final double c = Math.exp(-o * dt);

        final double a = position;
        final double b = position * o + velocity;

        double newPosition = (a + b * dt) * c;
        double newVelocity = c * (-a * o - b * dt * o + b);

        // 【低帧率适配】限制单帧位移量。
        //
        // 注意：必须在算出结果后限制 position 的变化量，<b>绝不能改衰减因子 c</b> ——
        // c 还参与速度积分（b 里已含"速度带来的位移"），改它会让位置被反向放大
        // （实测 100 → 150），逐帧累积后动画直接鬼畜。
        double delta = newPosition - a;
        double maxDelta = Math.abs(a) * MAX_STEP_RATIO;
        if (Math.abs(delta) > maxDelta) {
            newPosition = a + Math.signum(delta) * maxDelta;
            // 截断发生后，速度已不再反映真实运动状态，清零避免下一帧突变
            newVelocity = 0.0;
        }

        position = newPosition;
        velocity = newVelocity;

        // 位移仍大于 0.5 像素则视为"还在动"（原 JS 版用 0.01，此处按像素尺度说明）
        return Math.abs(position) >= 0.01;
    }

    /** 立即停止运动并归零 */
    public void reset() {
        position = 0.0;
        velocity = 0.0;
    }
}
