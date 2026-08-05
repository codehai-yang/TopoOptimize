package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// 路径查找器类
public class FindShortestPath {
    private List<List<Integer>> adj;

    /**
     * 带权 Dijkstra：寻找从 start 到 end 的「长度最短」路径（按分支真实长度，而非跳数）。
     *
     * @param adj     节点索引邻接表
     * @param start   起点索引
     * @param end     终点索引
     * @param allPoint 索引 -> 位置名称（与 adj 的索引对应）
     * @param edges   所有分支信息（含 分支起点名称/分支终点名称/参考长度/用户确认的分支长度）
     * @return 长度最短路径的节点索引序列；无法到达返回 null
     */
    public List<Integer> findShortestPathWithWeight(List<List<Integer>> adj, int start, int end,
            List<String> allPoint, List<Map<String, String>> edges) {
        this.adj = adj;
        int n = adj.size();
        // 构建 节点索引对 -> 分支长度(mm) 的权重表（无向图，统一用 minIdx|maxIdx 作 key）
        Map<String, Double> weightMap = buildWeightMap(adj, allPoint, edges);

        // 带权 Dijkstra
        double[] distance = new double[n];
        int[] predecessor = new int[n];
        Arrays.fill(distance, Double.MAX_VALUE);
        Arrays.fill(predecessor, -1);
        distance[start] = 0.0;

        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        pq.offer(new double[]{start, 0.0});
        boolean[] visited = new boolean[n];

        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int node = (int) cur[0];
            if (visited[node]) continue;
            visited[node] = true;

            if (node == end) {
                List<Integer> path = new ArrayList<>();
                int p = end;
                while (p != -1) {
                    path.add(0, p);
                    p = predecessor[p];
                }
                // 去重相邻重复
                for (int i = 0; i < path.size() - 1; i++) {
                    if (path.get(i).equals(path.get(i + 1))) {
                        path.remove(i);
                        i--;
                    }
                }
                return path;
            }

            for (int neighbor : adj.get(node)) {
                if (visited[neighbor]) continue;
                double w = weightOf(weightMap, node, neighbor);
                if (w < 0) continue; // 该边无法匹配到长度，跳过
                double alt = distance[node] + w;
                if (alt < distance[neighbor]) {
                    distance[neighbor] = alt;
                    predecessor[neighbor] = node;
                    pq.offer(new double[]{neighbor, alt});
                }
            }
        }
        return null;
    }

    /**
     * 构建 索引对 -> 长度(mm) 权重表。
     * 分支长度口径：优先「用户确认的分支长度」，其次「参考长度」，都没有则默认 BranchEndFallback。
     */
    private Map<String, Double> buildWeightMap(List<List<Integer>> adj, List<String> allPoint,
            List<Map<String, String>> edges) {
        Map<String, Double> weightMap = new HashMap<>();
        // 名称对 -> 长度(mm)
        Map<String, Double> nameLen = new HashMap<>();
        for (Map<String, String> edge : edges) {
            String s = edge.get("分支起点名称");
            String e = edge.get("分支终点名称");
            if (s == null || e == null) continue;
            Double len = null;
            Object verifyObj = edge.get("用户确认的分支长度");
            Object refObj = edge.get("参考长度");
            String verify = verifyObj == null ? null : verifyObj.toString();
            String ref = refObj == null ? null : refObj.toString();
            if (verify != null && !verify.trim().isEmpty()) {
                len = parseLen(verify);
            } else if (ref != null && !ref.trim().isEmpty()) {
                len = parseLen(ref);
            } else {
                len = HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput.BranchEndFallback;
            }
            if (len == null) continue;
            String key = s + "|" + e;
            // 同名边取最短长度
            nameLen.merge(key, len, Math::min);
        }
        // 把 名称对 -> 索引对（仅在邻接表中有边时建立）
        for (int i = 0; i < adj.size(); i++) {
            String nameI = (i < allPoint.size()) ? allPoint.get(i) : null;
            if (nameI == null) continue;
            for (int j : adj.get(i)) {
                String nameJ = (j < allPoint.size()) ? allPoint.get(j) : null;
                if (nameJ == null) continue;
                Double len = nameLen.get(nameI + "|" + nameJ);
                if (len == null) len = nameLen.get(nameJ + "|" + nameI);
                if (len != null) {
                    weightMap.put(weightKey(i, j), len);
                }
            }
        }
        return weightMap;
    }

    private Double parseLen(Object v) {
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private String weightKey(int a, int b) {
        return Math.min(a, b) + "|" + Math.max(a, b);
    }

    private double weightOf(Map<String, Double> weightMap, int a, int b) {
        Double w = weightMap.get(weightKey(a, b));
        return w == null ? -1.0 : w;
    }

    // 实现Dijkstra算法来寻找从start到end的最短路径
    public List<Integer> findShortestPathBetweenTwoPoint(List<List<Integer>> adj, int start, int end) {
        this.adj = adj;
        int n = this.adj.size(); // 图中节点的数量
        // 使用优先队列（最小堆）存储待处理的节点及其到起点的距离
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);
        pq.offer(new int[]{start, 0}); // 初始节点入队，距离为0
        boolean[] visited = new boolean[n]; // 标记节点是否已被访问
        int[] distance = new int[n]; // 存储到各节点的最短距离，初始化为最大值
        Arrays.fill(distance, Integer.MAX_VALUE);
        distance[start] = 0; // 起点到自身的距离为0

        // 主循环，处理队列直到为空
        while (!pq.isEmpty()) {
            int[] current = pq.poll(); // 弹出距离最小的节点
            int node = current[0];
            if (visited[node]) continue; // 若节点已访问过，则跳过

            visited[node] = true; // 标记当前节点为已访问

            // 如果找到了终点，构建并返回最短路径
            if (node == end) {
                List<Integer> path = new ArrayList<>();
                path.add(end);
                // 逆向追踪前驱节点，构建路径
                while (node != start) {
                    path.add(0, node);
                    node = distance[node] == Integer.MAX_VALUE ? -1 : getPredecessor(node, distance);
                }
                path.add(0, start);
                //删除path中重复的元素
                for (int i = 0; i < path.size() - 1; i++) {
                    if (path.get(i).equals(path.get(i + 1))) {
                        path.remove(i);
                        i--;
                    }
                }

                return path;
            }

            // 遍历当前节点的所有邻居
            for (int neighbor : this.adj.get(node)) {
                if (!visited[neighbor]) {
                    // 计算通过当前节点到达邻居的新距离
                    int alt = distance[node] + 1; // 假设每条边的权重为1
                    // 如果新距离更短，则更新距离并把邻居加入队列
                    if (alt < distance[neighbor]) {
                        distance[neighbor] = alt;
                        pq.offer(new int[]{neighbor, alt});
                    }
                }
            }
        }
        return null; // 无法到达终点，返回null
    }

    // 获取到达某节点的前驱节点，用于构建最短路径
    private int getPredecessor(int node, int[] distance) {
        for (int i = 0; i < adj.size(); i++) {
            // 如果邻接表中包含该节点，并且从i到node的距离加1等于node的已知最短距离
            if (adj.get(i).contains(node) && distance[i] + 1 == distance[node]) {
                return i; // 返回前驱节点
            }
        }
        return -1; // 如果找不到前驱节点，理论上不应该发生
    }

}