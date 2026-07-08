package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.List;

public class FindTopoBreak {

    /*
    输入参数1：adj邻接列表：以序号形式体现，序号对应的端点名称来自allpoint
             adj举例：[[130, 37], [47, 66, 147, 56], [133, 153], [97, 13, 113, 9],......]
            以adj中第0个子list[130, 37]为例，
             0代表分支起点是allPoint列表中的第0个元素，视为父元素parentElement
             37代表终点是allPoint列表中的第130个元素，37代表终点是allPoint列表中的第130个元素，视为子元素childElement

    输入参数2：allpoint：图中所有端点的清单
             allpoint举例：[尾门中上点, 前舱右纵梁中点, 中控线中点, 后围板内左点, 仪表中通道后点, 后保线右中点, 前保线中点, 右前门湿区中点]
     */

    //检查分支之间是否有断点
    public List<List<String>> recognizeBreak(List<List<Integer>> adj, List<String> allPoint) {
        int v = allPoint.size();
        // visited[i] 表示节点 i 是否已并入某个族群（避免 BFS 中重复处理）
        boolean[] visited = new boolean[v];
        // 广度优先搜索队列：父节点索引数组（用头指针推进，避免 ArrayList 的 remove(0) 开销）
        int[] parentQueue = new int[v];
        // 下一轮要处理的子节点索引数组
        int[] nextQueue = new int[v];
        // 每个族群包含的节点索引（按 allPoint 顺序）
        List<Integer> eachFamily = new ArrayList<>();

        List<List<String>> familyPointName = new ArrayList<>();

        for (int start = 0; start < v; start++) {
            if (visited[start]) {
                continue;
            }
            // 从 start 启动 BFS，收集一个连通分量
            visited[start] = true;
            eachFamily.clear();
            eachFamily.add(start);

            int head = 0, tail = 0;
            int nextTail = 0;
            parentQueue[tail++] = start;

            while (head < tail) {
                int parent = parentQueue[head++];
                List<Integer> children = adj.get(parent);
                if (children == null) {
                    continue;
                }
                for (int i = 0, sz = children.size(); i < sz; i++) {
                    int child = children.get(i);
                    if (!visited[child]) {
                        visited[child] = true;
                        eachFamily.add(child);
                        nextQueue[nextTail++] = child;
                    }
                }
                // 当前层处理完毕，下沉到下一层
                if (head == tail) {
                    int[] tmp = parentQueue;
                    parentQueue = nextQueue;
                    nextQueue = tmp;
                    tail = nextTail;
                    head = 0;
                    nextTail = 0;
                }
            }

            // 把当前族群的端点名称追加到结果
            List<String> family = new ArrayList<>(eachFamily.size());
            for (int idx : eachFamily) {
                family.add(allPoint.get(idx));
            }
            familyPointName.add(family);
        }

        return familyPointName;//格式[[族群A内端点1名称、族群A内端点2名称、族群A内端点3名称、...][族群B内端点1名称、族群B内端点2名称、族群B内端点3名称、...]]
    }
}
