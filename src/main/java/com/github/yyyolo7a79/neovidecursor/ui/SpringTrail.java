package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/**
 * 单个光标的弹簧拖尾状态 —— 四个角点 + 目标点。
 *
 * <p>多光标（Alt + 鼠标拖拽列选、Alt+Shift+点击等）时，每个光标各持有一个实例，
 * 对应原版 {@code createNeovideCursor()} 为每个 {@code .cursor} 元素创建的独立实例。
 */
final class SpringTrail {

    /** 四个角点的相对位置（左上 → 右上 → 右下 → 左下，顺时针） */
    private static final double[][] CORNER_RELATIVE = {
            {-0.5, -0.5},
            {0.5, -0.5},
            {0.5, 0.5},
            {-0.5, 0.5},
    };

    final TrailCorner[] corners;

    /** 光标矩形中心点与尺寸（contentComponent 坐标系） */
    double centerX;
    double centerY;
    private double width;
    private double height;

    /**
     * 快速路径的缓存键：上次换算坐标时的视觉位置 + 文档修改戳。
     *
     * <p>只看 (行, 列) 不够：文字变了但列号恰好没变时（第三方输入法把光标放进
     * 拼音音节内部，上屏后列号相同但像素位置已变，issue #6），会误判"没移动"、
     * 覆盖层停在半个字宽处。加上修改戳后，文档一变就强制重算。
     */
    private int lastLine = Integer.MIN_VALUE;
    private int lastColumn = Integer.MIN_VALUE;
    private long lastStamp = Long.MIN_VALUE;

    /** 目标点刚变化，下一帧需要重新分配角点 rank */
    private boolean jumped = false;

    /** 是否已摆放到位（首次定位直接吸附，不做入场动画） */
    private boolean initialized = false;

    /** 本帧四角相对目标的最大偏移（像素），用于驱动辉光强度 */
    private double maxOffset = 0;

    SpringTrail(NeovideConfig config) {
        corners = new TrailCorner[CORNER_RELATIVE.length];
        for (int i = 0; i < CORNER_RELATIVE.length; i++) {
            corners[i] = new TrailCorner(CORNER_RELATIVE[i][0], CORNER_RELATIVE[i][1], config);
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    boolean isAt(int line, int column, long stamp) {
        return line == lastLine && column == lastColumn && stamp == lastStamp;
    }

    void markAt(int line, int column, long stamp) {
        lastLine = line;
        lastColumn = column;
        lastStamp = stamp;
    }

    /**
     * 让本光标从另一个光标的当前形状起飞（多光标新增时调用）。
     * 源光标尚未就位时不做处理，交给首次 {@link #setTarget} 直接吸附。
     */
    void startFrom(SpringTrail source) {
        if (!source.initialized) {
            return;
        }
        for (int i = 0; i < corners.length; i++) {
            corners[i].placeAt(source.corners[i].getCurrentX(), source.corners[i].getCurrentY());
        }
        width = source.width;
        height = source.height;
        initialized = true;
    }

    /**
     * 更新目标点。
     *
     * @return true 表示目标确实发生了变化
     */
    boolean setTarget(double cx, double cy, double w, double h) {
        if (initialized && cx == centerX && cy == centerY && w == width && h == height) {
            return false;
        }
        centerX = cx;
        centerY = cy;
        width = w;
        height = h;
        if (!initialized) {
            snapToTarget();
            initialized = true;
        } else {
            jumped = true;
        }
        return true;
    }

    /** 四角直接吸附到目标（静态光标 / 模式切换时用） */
    void snapToTarget() {
        for (TrailCorner corner : corners) {
            corner.snapTo(width, height, centerX, centerY);
        }
        jumped = false;
        maxOffset = 0;
    }

    /**
     * 推进一帧物理。
     *
     * @return true 表示仍在运动
     */
    boolean update(double dt, double charWidth) {
        if (jumped) {
            assignRanks(charWidth);
            jumped = false;
        }
        boolean animating = false;
        maxOffset = 0;
        for (TrailCorner corner : corners) {
            if (corner.update(width, height, centerX, centerY, dt, false)) {
                animating = true;
            }
            maxOffset = Math.max(maxOffset, corner.getOffsetMagnitude());
        }
        return animating;
    }

    double maxOffset() {
        return maxOffset;
    }

    /**
     * 按「运动方向对齐度」给四个角点名次。
     *
     * <p>与原 JS 版一致按对齐度<b>升序</b>：rank 0 是最靠后的角，拿 trailFactors[0]=1
     * （最长时长、最慢）；越靠前 rank 越大、时长越短，最前缘还会触发硬吸附 ——
     * 于是前缘紧跟、后缘拖沓，矩形被拉长成拖尾形状。
     */
    private void assignRanks(double charWidth) {
        double[] alignment = new double[corners.length];
        for (int i = 0; i < corners.length; i++) {
            alignment[i] = corners[i].calculateDirectionAlignment(width, height, centerX, centerY);
        }

        // 求名次：对齐度越低（越靠后）rank 越小
        for (int i = 0; i < corners.length; i++) {
            int rank = 0;
            for (int j = 0; j < corners.length; j++) {
                if (alignment[j] < alignment[i]) {
                    rank++;
                }
            }
            corners[i].jump(width, height, centerX, centerY, rank, charWidth);
        }
    }
}
