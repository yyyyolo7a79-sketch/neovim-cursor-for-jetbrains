package com.github.yyyolo7a79.neovidecursor.core;

import java.util.Objects;

/**
 * 设置页（Settings | Tools | Neovide Cursor）可调的参数集合。
 *
 * <p>同时承担两个角色：
 * <ul>
 *   <li><b>持久化结构</b> —— 由 IntelliJ 序列化到 {@code neovide-cursor.xml}，
 *       与默认值相同的字段不会落盘</li>
 *   <li><b>表单快照</b> —— 设置页读写的都是它，点 Apply 才写回 {@link NeovideConfig}</li>
 * </ul>
 *
 * <p>只收录值得用户调的参数。硬吸附、四角滞后系数、短距离判定等内部调校值不在此列：
 * 它们与原版算法强绑定，改了只会把手感调坏，始终沿用 {@link NeovideConfig} 的默认值。
 *
 * <p><b>默认值一律取自 {@link NeovideConfig}</b>，即 0.5.1 实测手感最好的那套粉色配置 ——
 * 设置页的「恢复默认」回到的就是这里。
 */
public class CursorSettings {

    private static final NeovideConfig DEFAULTS = new NeovideConfig();

    // ===== 基本 =====
    public boolean enabled = true;
    public boolean hideNativeCaret = DEFAULTS.hideNativeCaret;
    public NeovideConfig.TrailMode trailMode = DEFAULTS.trailMode;

    // ===== 颜色与辉光 =====
    public String tailColor = DEFAULTS.tailColor;
    public float tailOpacity = DEFAULTS.tailOpacity;
    public boolean useShadow = DEFAULTS.useShadow;
    public String shadowColor = DEFAULTS.shadowColor;
    public float glowOpacity = DEFAULTS.glowOpacity;
    public float shadowBlurFactor = DEFAULTS.shadowBlurFactor;

    // ===== 动画 =====
    public float animationLength = DEFAULTS.animationLength;
    public float shortAnimationLength = DEFAULTS.shortAnimationLength;
    public float afterimageLifetime = DEFAULTS.afterimageLifetime;
    public int cursorDisappearDelay = DEFAULTS.cursorDisappearDelay;
    public float cursorFadeOutDuration = DEFAULTS.cursorFadeOutDuration;

    // ===== 高级 =====
    public float cursorWidth = DEFAULTS.cursorWidth;
    public float maxTrailDistanceFactor = DEFAULTS.maxTrailDistanceFactor;
    public int glowLayers = DEFAULTS.glowLayers;

    /** 从运行时配置抓取一份快照（{@code enabled} 由 NeovideCaretManager 单独持有，需另传） */
    public static CursorSettings capture(NeovideConfig c, boolean enabled) {
        CursorSettings s = new CursorSettings();
        s.enabled = enabled;
        s.hideNativeCaret = c.hideNativeCaret;
        s.trailMode = c.trailMode;
        s.tailColor = c.tailColor;
        s.tailOpacity = c.tailOpacity;
        s.useShadow = c.useShadow;
        s.shadowColor = c.shadowColor;
        s.glowOpacity = c.glowOpacity;
        s.shadowBlurFactor = c.shadowBlurFactor;
        s.animationLength = c.animationLength;
        s.shortAnimationLength = c.shortAnimationLength;
        s.afterimageLifetime = c.afterimageLifetime;
        s.cursorDisappearDelay = c.cursorDisappearDelay;
        s.cursorFadeOutDuration = c.cursorFadeOutDuration;
        s.cursorWidth = c.cursorWidth;
        s.maxTrailDistanceFactor = c.maxTrailDistanceFactor;
        s.glowLayers = c.glowLayers;
        return s;
    }

    /** 写回运行时配置（不含 {@code enabled}：总开关的副作用由 NeovideCaretManager 处理） */
    public void applyTo(NeovideConfig c) {
        c.hideNativeCaret = hideNativeCaret;
        c.trailMode = trailMode;
        c.tailColor = tailColor;
        c.tailOpacity = tailOpacity;
        c.useShadow = useShadow;
        c.shadowColor = shadowColor;
        c.glowOpacity = glowOpacity;
        c.shadowBlurFactor = shadowBlurFactor;
        c.animationLength = animationLength;
        c.shortAnimationLength = shortAnimationLength;
        c.afterimageLifetime = afterimageLifetime;
        c.cursorDisappearDelay = cursorDisappearDelay;
        c.cursorFadeOutDuration = cursorFadeOutDuration;
        c.cursorWidth = cursorWidth;
        c.maxTrailDistanceFactor = maxTrailDistanceFactor;
        c.glowLayers = glowLayers;
    }

    /** 复制一份 —— 借道上面两张字段表，新增字段时只需改那两处 */
    public CursorSettings copy() {
        NeovideConfig tmp = new NeovideConfig();
        applyTo(tmp);
        return capture(tmp, enabled);
    }

    /**
     * 把持久化值收敛到设置页滑块的范围内（配置文件可能被手改过）。
     *
     * <p>上限按绘制开销与观感定：辉光强度到 1 时每层 alpha 都是 1，光晕退化成实心硬边；
     * 辉光层数超过 16 肉眼已看不出差别，绘制次数却线性增长；残影超过 0.3s 会撞上
     * 残影数量上限，尾巴被硬切；拖尾最大长度以原版的 60 为上限。
     * 光标宽为 0 会让 TrailCorner 的位移归一化除零，下限因此取 1。
     */
    public CursorSettings sanitize() {
        if (trailMode == null) {
            trailMode = DEFAULTS.trailMode;
        }
        if (tailColor == null) {
            tailColor = DEFAULTS.tailColor;
        }
        if (shadowColor == null) {
            shadowColor = DEFAULTS.shadowColor;
        }
        tailOpacity = clamp(tailOpacity, 0.1f, 1f, DEFAULTS.tailOpacity);
        glowOpacity = clamp(glowOpacity, 0f, 0.95f, DEFAULTS.glowOpacity);
        shadowBlurFactor = clamp(shadowBlurFactor, 0f, 0.6f, DEFAULTS.shadowBlurFactor);
        animationLength = clamp(animationLength, 0.02f, 0.3f, DEFAULTS.animationLength);
        // 短距离时长不超过常规时长，否则短距离移动反而更慢
        shortAnimationLength = Math.min(animationLength,
                clamp(shortAnimationLength, 0.01f, 0.2f, DEFAULTS.shortAnimationLength));
        afterimageLifetime = clamp(afterimageLifetime, 0.05f, 0.3f, DEFAULTS.afterimageLifetime);
        cursorFadeOutDuration = clamp(cursorFadeOutDuration, 0f, 0.5f, DEFAULTS.cursorFadeOutDuration);
        cursorDisappearDelay = Math.max(0, Math.min(cursorDisappearDelay, 500));
        cursorWidth = clamp(cursorWidth, 1f, 12f, DEFAULTS.cursorWidth);
        maxTrailDistanceFactor = clamp(maxTrailDistanceFactor, 2f, 60f, DEFAULTS.maxTrailDistanceFactor);
        glowLayers = Math.max(1, Math.min(glowLayers, 16));
        return this;
    }

    private static float clamp(float v, float min, float max, float fallback) {
        if (Float.isNaN(v)) {
            return fallback;
        }
        return v < min ? min : (v > max ? max : v);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CursorSettings s)) {
            return false;
        }
        return enabled == s.enabled
                && hideNativeCaret == s.hideNativeCaret
                && trailMode == s.trailMode
                && sameColor(tailColor, s.tailColor)
                && Float.compare(tailOpacity, s.tailOpacity) == 0
                && useShadow == s.useShadow
                && sameColor(shadowColor, s.shadowColor)
                && Float.compare(glowOpacity, s.glowOpacity) == 0
                && Float.compare(shadowBlurFactor, s.shadowBlurFactor) == 0
                && Float.compare(animationLength, s.animationLength) == 0
                && Float.compare(shortAnimationLength, s.shortAnimationLength) == 0
                && Float.compare(afterimageLifetime, s.afterimageLifetime) == 0
                && cursorDisappearDelay == s.cursorDisappearDelay
                && Float.compare(cursorFadeOutDuration, s.cursorFadeOutDuration) == 0
                && Float.compare(cursorWidth, s.cursorWidth) == 0
                && Float.compare(maxTrailDistanceFactor, s.maxTrailDistanceFactor) == 0
                && glowLayers == s.glowLayers;
    }

    @Override
    public int hashCode() {
        // 颜色按忽略大小写比较，不参与哈希；其余字段足以区分
        return Objects.hash(enabled, hideNativeCaret, trailMode, tailOpacity, useShadow,
                glowOpacity, shadowBlurFactor, animationLength, shortAnimationLength,
                afterimageLifetime, cursorDisappearDelay, cursorFadeOutDuration,
                cursorWidth, maxTrailDistanceFactor, glowLayers);
    }

    /** HEX 颜色按忽略大小写比较：#FFC0CB 与 #ffc0cb 是同一种颜色 */
    private static boolean sameColor(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
