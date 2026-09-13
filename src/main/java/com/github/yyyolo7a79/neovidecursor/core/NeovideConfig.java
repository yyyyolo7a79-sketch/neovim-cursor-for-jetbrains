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

    /** 辉光强度系数：实际模糊半径 = 该系数 × 光标较长边 */
    public float shadowBlurFactor = 0.6f;

    /**
     * 辉光宽度系数：实际宽度 = 该系数 × 24 像素。
     * 配合多层平方衰减绘制，可得到接近 Canvas shadowBlur 的柔和光晕。
     */
    public float glowWidthFactor = 0.35f;

    /**
     * 辉光层数：层数越多渐变越平滑。
     * 层数太少会露出"硬边"，看起来像给光标套了个壳。
     */
    public int glowLayers = 10;

    /** 辉光基础不透明度 */
    public float glowOpacity = 0.5f;

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
     * <p>实测经验：原版 JS 取值 60（约 1920px），在 Canvas 全屏重绘下没问题，
     * 但 Swing 里覆盖层必须局部重绘 —— 拖尾越长，需要重绘的包围盒越大，
     * 跨行跳转时会直接退化成接近全屏的重绘并明显掉帧。
     * 收紧到 4（约 128px）后，正常移动的拖尾长度完全不受影响，
     * 只有极端跳转才会被截断。
     */
    public float maxTrailDistanceFactor = 4f;

    /** 硬吸附时的动画时长（秒） */
    public float snapAnimationLength = 0.02f;

    /** 光标宽度（像素）—— IntelliJ 默认光标较细 */
    public float cursorWidth = 2f;

    /** 是否隐藏原生光标（改 CARET_COLOR 为透明） */
    public boolean hideNativeCaret = true;

    /** 全局开关：关掉后立即恢复原生光标与干净界面 */
    public boolean enabled = true;
}
