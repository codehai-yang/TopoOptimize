package HarnessPackOpti.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

public class Point {
    public double x, y;
    public Point(double x, double y) { this.x = x; this.y = y; }


    /**
     * 找到距离查询点集合最近的点，并返回距离该最近点最近的K个点
     * @param allPoints 所有候选点
     * @param queryPoints 查询点集合
     * @param k 返回的最近点数量（包括最近点本身）
     * @return 距离中心最近点最近的K个点
     */
    public static List<Point> findKNearestPointsToCenter(List<Point> allPoints, List<Point> queryPoints, int k) {
        if (allPoints == null || allPoints.isEmpty() || queryPoints == null || queryPoints.isEmpty()) {
            return new ArrayList<>();
        }

        if (k <= 0) {
            return new ArrayList<>();
        }

        // 第一阶段：找到距离查询点集合最近的中心点
        Point centerPoint = findMinSumDistancePointTwo(allPoints, queryPoints);
        if (centerPoint == null) {
            return new ArrayList<>();
        }

        // 第二阶段：找到距离中心点最近的K个点
        return findKNearestPoints(allPoints, centerPoint, k);
    }

    /**
     * 找到距离目标点最近的K个点（使用最大堆优化，时间复杂度 O(n log k)）
     * @param allPoints 所有候选点
     * @param target 目标点
     * @param k 返回的最近点数量
     * @return 距离目标点最近的K个点
     */
    public static List<Point> findKNearestPoints(List<Point> allPoints, Point target, int k) {
        if (allPoints == null || allPoints.isEmpty() || target == null || k <= 0) {
            return new ArrayList<>();
        }

        // 如果 k >= 总点数，直接排序返回
        if (k >= allPoints.size()) {
            List<Point> result = new ArrayList<>(allPoints);
            result.sort((p1, p2) -> Double.compare(
                    distanceSquared(p1, target),
                    distanceSquared(p2, target)
            ));
            return result;
        }

        // 使用最大堆维护最近的K个点（堆顶是距离最大的）
        PriorityQueue<Point> maxHeap = new PriorityQueue<>((p1, p2) ->
                Double.compare(distanceSquared(p2, target), distanceSquared(p1, target))
        );

        for (Point p : allPoints) {
            double dist = distanceSquared(p, target);

            if (maxHeap.size() < k) {
                maxHeap.offer(p);
            } else if (dist < distanceSquared(maxHeap.peek(), target)) {
                maxHeap.poll();
                maxHeap.offer(p);
            }
        }

        // 将堆中的点转换为列表并按距离排序
        List<Point> result = new ArrayList<>(maxHeap);
        result.sort((p1, p2) -> Double.compare(
                distanceSquared(p1, target),
                distanceSquared(p2, target)
        ));

        return result;
    }

    /**
     * 计算两点之间的平方距离（避免开方运算，提升性能）
     */
    private static double distanceSquared(Point p1, Point p2) {
        double dx = p1.x - p2.x;
        double dy = p1.y - p2.y;
        return dx * dx + dy * dy;
    }

    /**
     * 找到距离查询点集合最近的点（保留原有方法）
     */
    public static Point findMinSumDistancePointTwo(List<Point> allPoints, List<Point> queryPoints) {
        if (allPoints == null || allPoints.isEmpty()) return null;

        Point best = null;
        double bestDistSqSum = Double.POSITIVE_INFINITY;

        for (Point p : allPoints) {
            double sumSq = 0.0;
            for (Point q : queryPoints) {
                double dx = p.x - q.x;
                double dy = p.y - q.y;
                sumSq += dx * dx + dy * dy;
                // 早停优化
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
