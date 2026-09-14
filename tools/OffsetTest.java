import java.awt.Polygon;

/**
 * 独立验算程序：验证「四边形精确外扩」的几何正确性。
 *
 * 逻辑与 CaretTrailPanel.computeOffsetDirections 完全一致（该方法为 private，
 * 故此处复制一份），用于在改动绘制代码前先确认数学无误 —— 参见 README 踩坑 #4。
 *
 * 检查项：
 *   1. 静止矩形外扩后是否仍为矩形、外扩量是否精确等于目标
 *   2. 外扩后中心是否漂移（应恒为 0）
 *   3. 锐角处是否被 √2 上限压住（抑制尖刺）
 *   4. 外扩后是否仍为凸四边形（不自交）
 */
public class OffsetTest {

    private static final double SQRT_2 = Math.sqrt(2.0);

    /** 与 CaretTrailPanel.computeOffsetDirections 逐行一致 */
    static void computeOffsetDirections(Polygon polygon, double[] outDirX, double[] outDirY) {
        double[] nx = new double[4];
        double[] ny = new double[4];

        double cx = 0;
        double cy = 0;
        for (int j = 0; j < 4; j++) {
            cx += polygon.xpoints[j];
            cy += polygon.ypoints[j];
        }
        cx /= 4.0;
        cy /= 4.0;

        for (int j = 0; j < 4; j++) {
            int next = (j + 1) % 4;
            double ex = polygon.xpoints[next] - polygon.xpoints[j];
            double ey = polygon.ypoints[next] - polygon.ypoints[j];
            double len = Math.hypot(ex, ey);
            if (len < 1e-6) {
                continue;
            }
            double vx = -ey / len;
            double vy = ex / len;

            double mx = (polygon.xpoints[j] + polygon.xpoints[next]) / 2.0 - cx;
            double my = (polygon.ypoints[j] + polygon.ypoints[next]) / 2.0 - cy;
            if (vx * mx + vy * my < 0) {
                vx = -vx;
                vy = -vy;
            }
            nx[j] = vx;
            ny[j] = vy;
        }

        for (int j = 0; j < 4; j++) {
            int prev = (j + 3) % 4;
            double n1x = nx[prev];
            double n1y = ny[prev];
            double n2x = nx[j];
            double n2y = ny[j];

            double denom = 1.0 + (n1x * n2x + n1y * n2y);
            double dirX;
            double dirY;
            if (Math.abs(denom) < 1e-6) {
                dirX = n1x + n2x;
                dirY = n1y + n2y;
            } else {
                dirX = (n1x + n2x) / denom;
                dirY = (n1y + n2y) / denom;
            }

            double mag = Math.hypot(dirX, dirY);
            if (mag > SQRT_2) {
                double scale = SQRT_2 / mag;
                dirX *= scale;
                dirY *= scale;
            }
            outDirX[j] = dirX;
            outDirY[j] = dirY;
        }
    }

    static void check(String name, Polygon p, double radius) {
        double[] dx = new double[4];
        double[] dy = new double[4];
        computeOffsetDirections(p, dx, dy);

        System.out.println("=== " + name + " ===");
        System.out.print("  原四边形:   ");
        for (int i = 0; i < 4; i++) {
            System.out.printf("(%.0f,%.0f) ", (double) p.xpoints[i], (double) p.ypoints[i]);
        }
        System.out.println();

        double[] ox = new double[4];
        double[] oy = new double[4];
        System.out.printf("  外扩%.1f后: ", radius);
        for (int i = 0; i < 4; i++) {
            ox[i] = p.xpoints[i] + dx[i] * radius;
            oy[i] = p.ypoints[i] + dy[i] * radius;
            System.out.printf("(%.2f,%.2f) ", ox[i], oy[i]);
        }
        System.out.println();

        System.out.print("  |dir|:      ");
        for (int i = 0; i < 4; i++) {
            System.out.printf("%.3f ", Math.hypot(dx[i], dy[i]));
        }
        System.out.println("  (上限 √2 = 1.414)");

        // 凸性 / 自交：连续三点叉积应同号
        boolean convex = true;
        double prevCross = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            int k = (i + 2) % 4;
            double cross = (ox[j] - ox[i]) * (oy[k] - oy[j])
                    - (oy[j] - oy[i]) * (ox[k] - ox[j]);
            if (i == 0) {
                prevCross = cross;
            } else if (cross * prevCross < 0) {
                convex = false;
            }
        }
        System.out.println("  凸且不自交: " + (convex ? "是" : "否  <-- 异常"));

        double cx0 = (p.xpoints[0] + p.xpoints[1] + p.xpoints[2] + p.xpoints[3]) / 4.0;
        double cy0 = (p.ypoints[0] + p.ypoints[1] + p.ypoints[2] + p.ypoints[3]) / 4.0;
        double cx1 = (ox[0] + ox[1] + ox[2] + ox[3]) / 4.0;
        double cy1 = (oy[0] + oy[1] + oy[2] + oy[3]) / 4.0;
        System.out.printf("  中心漂移:   (%.4f, %.4f)%n%n", cx1 - cx0, cy1 - cy0);
    }

    public static void main(String[] args) {
        check("① 静止矩形 2×32（用户最关注的场景）", new Polygon(
                new int[]{0, 2, 2, 0}, new int[]{0, 0, 32, 32}, 4), 6.4);

        check("② 拖尾中间态（斜四边形）", new Polygon(
                new int[]{10, 12, 40, 38}, new int[]{0, 0, 28, 28}, 4), 6.4);

        check("③ 极端跳转（细长斜条）", new Polygon(
                new int[]{100, 102, 104, 102}, new int[]{0, 0, 300, 300}, 4), 6.4);

        check("④ 退化：四角重合", new Polygon(
                new int[]{50, 50, 50, 50}, new int[]{50, 50, 50, 50}, 4), 6.4);
    }
}
