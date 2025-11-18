package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

// 路径查找器类
public class FindShortestPath {
    private List<List<Integer>> adj;

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