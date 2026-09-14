package com.github.yyyolo7a79.neovidecursor.core;

/**
 * Neovide 拖尾光标配置。
 *
 * <p>字段语义与 VS Code 版 {@code neovide-cursor.js} 的 {@code cursorConfig} <b>完全对齐</b>，
 * 便于用户在两个平台间迁移配置、对比效果。命名刻意保留原始风格。
 *
 * <p>原始定义见：neo-cursor-fix/extension/assets/neovide-cursor.js 第 2-26 行
 */
public class NeovideConfig {

    /** 拖尾颜色（HEX 格式） */
    public String tailColor = "#FFC0CB";

    /** 拖尾不透明度（0~1） */
    public float tailOpacity = 1.0f;

    /** 是否启用辉光 */
    public boolean useShadow = true;

    /** 辉光颜色（HEX 格式） */
    public String shadowColor = "#FFC0CB";

    /**
     * 辉光大小系数：实际外扩半径 = 该系数 × 光标较长边。
     *
     * <p><b>本项偏离了原版 JS 的 0.6，这是有意为之。</b>
     * 原版 {@code cursorConfig.shadowBlurFactor = 0.6} 描述的是 Canvas
     * {@code shadowBlur}（高斯模糊的<i>直径</i>）—— 模糊会把能量摊开，
     * 图形越窄、峰值被稀释得越厉害。VS Code 的光标有 8px 宽，尚能撑住；
     * 而 IntelliJ 光标只有 2px 宽，同一数值直接当作膨胀半径使用时，
     * 会得到一团宽约 40px 的椭圆雾（是光标宽度的 20 倍），远谈不上"贴着光标发光"。
     *
     * <p>0.2 是按"光晕总宽与残影模式观感一致"（约 14px）反推得到的。
     * 想还原原版那种夸张的大范围光雾，把它调回 0.6 即可。
     */
    public float shadowBlurFactor = 0.2f;

    /**
     * 辉光层数：层数越多渐变越平滑。
     * 层数太少会露出"硬边"，看起来像给光标套了个壳。
     *
     * <p><b>层数不影响光晕整体亮度</b> —— 每层的独立不透明度会按层数反解，
     * 保证叠加后峰值恒等于 {@link #glowOpacity}。加层只是让过渡更细腻。
     */
    public int glowLayers = 12;

    /**
     * 辉光峰值不透明度（只影响光晕，不影响光标主体）。
     * 即紧贴光标处光晕的最终不透明度，向外的衰减均以此为基准。
     */
    public float glowOpacity = 0.55f;

    /** 光标停止移动后，延迟多久淡出（毫秒） */
    public int cursorDisappearDelay = 50;

    /** 光标淡出时长（秒） */
    public float cursorFadeOutDuration = 0.075f;

    /** 常规动画时长（秒）—— 数值越大拖尾越长 */
    public float animationLength = 0.1f;

    /** 短距离移动时的动画时长（秒）—— 短距离用更短动画，避免拖泥带水 */
    public float shortAnimationLength = 0.05f;

    /** 短距离阈值（像素）：位移小于该值视为短距离移动 */
    public float shortMoveThreshold = 8f;

    /**
     * 四个角点的滞后系数（rank0 = 最靠前，rank3 = 最靠后）。
     * 数值越接近 1 越"跟手"，越小则滞后越明显 —— 这是拖尾拉伸感的来源。
     * 原始值：rank0=1, rank1=0.9, rank2=0.5, rank3=0.3
     */
    public float[] trailFactors = {1.0f, 0.9f, 0.5f, 0.3f};

    /** 是否启用硬吸附：当角点运动方向与光标移动方向高度一致时，让它瞬间归位 */
    public boolean useHardSnap = true;

    /** 硬吸附时的动画时长系数（越小越"硬"） */
    public float leadingSnapFactor = 0.1f;

    /** 触发硬吸附的方向对齐度阈值（0~1，越大越难触发） */
    public float leadingSnapThreshold = 0.5f;

    /** 动画重置阈值：时长小于该值时不重置弹簧，避免抖动 */
    public float animationResetThreshold = 0.09f;

    /**
     * 拖尾最大位移系数：单个角点相对目标的最大偏移 = 该系数 × 光标较长边。
     *
     * <p>本项已与原版 JS <b>严格对齐</b>（60，约 1920px）。
     *
     * <p><b>注意这是一项性能取舍</b>：该系数只做「限幅」，对相邻行等常规移动
     * <b>完全无影响</b>（偏移量远达不到阈值）；它只在跨多行跳转时起作用 ——
     * 值越大，拖尾越完整、越接近原版观感，但 Swing 覆盖层必须局部重绘，
     * 偏移越大重绘包围盒越大，跨十行以上跳转时可能退化成接近全屏的重绘。
     *
     * <p>若跳转时明显掉帧，把本值调小即可（曾用过 4 = 约 128px，
     * 代价是跨行拖尾会被截断，正常移动观感不受影响）。
     */
    public float maxTrailDistanceFactor = 60f;

    /** 硬吸附时的动画时长（秒） */
    public float snapAnimationLength = 0.02f;

    /** 光标宽度（像素）—— IntelliJ 默认光标较细 */
    public float cursorWidth = 2f;

    /** 是否隐藏原生光标（改 CARET_COLOR 为透明） */
    public boolean hideNativeCaret = true;

    /** 全局开关：关掉后立即恢复原生光标与干净界面 */
    public boolean enabled = true;

    // ===== 渲染模式 =====

    /** 拖尾渲染模式：弹簧（Neovide 原版效果）或残影（低帧率更稳） */
    public TrailMode trailMode = TrailMode.SPRING;

    /**
     * 残影存活时长（秒）：仅 {@link TrailMode#AFTERIMAGE} 使用。
     * 数值越大，拖尾越长。
     */
    public float afterimageLifetime = 0.22f;

    /** 拖尾渲染模式 */
    public enum TrailMode {
        /** 弹簧模型：光标被拉伸成四边形 —— Neovide 原版效果 */
        SPRING,
        /**
         * 残影模型：一串渐隐的残影。
         * 它记录的是每帧的真实位置而非帧间插值，因此低帧率下更稳定。
         */
        AFTERIMAGE
    }
}
