package com.github.yyyolo7a79.neovidecursor.core;

import java.awt.Color;

/**
 * 颜色解析工具 —— 对应原 JS 版的 {@code cursorHexToRgba}。
 *
 * <p>支持 {@code #RGB}、{@code #RRGGBB}、{@code #RRGGBBAA} 三种 HEX 写法（{@code #} 可省略），
 * 解析结果再乘以外部传入的不透明度系数。
 */
public final class NeovideColors {

    private NeovideColors() {
    }

    /**
     * 解析 HEX 颜色字符串。
     *
     * @param hex     颜色文本，如 {@code #FFC0CB}
     * @param opacity 额外的不透明度系数（0~1），与颜色自带的 alpha 相乘
     * @return 解析后的 Color；解析失败时返回不透明黑色，保证不影响绘制流程
     */
    public static Color parse(String hex, float opacity) {
        try {
            if (hex == null) {
                return Color.BLACK;
            }

            String h = hex.startsWith("#") ? hex.substring(1) : hex;

            // #RGB 缩写展开为 #RRGGBB
            if (h.length() == 3) {
                StringBuilder sb = new StringBuilder();
                for (char c : h.toCharArray()) {
                    sb.append(c).append(c);
                }
                h = sb.toString();
            }

            // 无 alpha 通道时补全为 FF
            if (h.length() == 6) {
                h = h + "FF";
            }
            if (h.length() != 8) {
                return Color.BLACK;
            }

            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            int a = Integer.parseInt(h.substring(6, 8), 16);

            float alpha = (a / 255.0f) * clamp01(opacity);
            return new Color(clamp255(r), clamp255(g), clamp255(b), Math.round(alpha * 255));

        } catch (Exception e) {
            // 颜色解析失败不应导致动画中断，退回黑色
            return Color.BLACK;
        }
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
