package HarnessPackOpti.utils;

import java.util.List;

public class Point {
    public double x, y;
    public Point(double x, double y) { this.x = x; this.y = y; }

    /**
     * 确定焊点位置
     * @param allPoints 所有的点的坐标
     * @param queryPoints 要查询的点的坐标
     * @return 距离最短的坐标点
     */
    public static Point findMinSumDistancePoint(List<Point> allPoints, List<Point> queryPoints) {
        if (allPoints == null || allPoints.isEmpty()) return null;

        Point best = null;
        double bestDistSqSum = Double.POSITIVE_INFINITY;

        for (Point p : allPoints) {
            double sumSq = 0.0;
            for (Point q : queryPoints) {
                double dx = p.x - q.x;
                double dy = p.y - q.y;
                sumSq += dx * dx + dy * dy;
                // 早停：若当前累加和已经超过已知最优，可提前跳出内层循环（小幅优化）
                if (sumSq >= bestDistSqSum) break;
            }
            if (sumSq < bestDistSqSum) {
                bestDistSqSum = sumSq;
                best = p;
            }
        }
        return best;
    }
}
