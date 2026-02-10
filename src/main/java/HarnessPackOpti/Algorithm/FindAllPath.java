package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/*
用ShortestPathSearch从adj中获得最短路径shortestPath后,
举例：findShortestPath(adj,0, 134) 获得shortestPath:[0, 130, 158, 127, 113, 75, 116, 108, 79, 88, 93, 50, 77, 40, 134]

开始找其他路径，先找出shortestPath中第i=1的元素130，然后复制一个新的adj出来，adj中第j-130项的list中元素清空，
这样最短路径中就找不到130了。
然后再从path第i=0元素找最短路otherShortestPath。
以此类推，依次删除path中第二、三、四、五个元素，只在shortestPath总元素个数≥3个的情况下操作，最多删到第“shortestPath中总元素个数-2”个元素。

举例：adj:[[130, 37], [47, 66, 147, 56], [133, 153], [97, 13, 113, 9], [131, 60, 25], [96, 46],
 [98, 71], [137, 90, 59], [53], [3, 118], [163, 79, 108, 76], [52, 55], [73, 113], [140, 3],
 [168, 59, 109, 152], [77, 156], [122, 99], [118, 112], [23, 44], [91, 36], [82, 60, 162],
 [160], [116, 53], [94, 18], [129, 131, 142], [4, 141, 122], [81, 100, 163], [86, 28], [27, 47],
 [32, 152], [65, 63], [159, 36], [110, 29, 92], [164], [145], [87, 77], [19, 31, 115],
 [0, 138, 155], [140], [128, 153], [77, 134, 80], [72, 164], [97, 104], [67], [109, 18, 47],
 [132, 106, 54, 114], [83, 5], [44, 28, 1], [84, 112, 167, 115], [162], [93, 151, 77, 120],
 [129, 62, 72], [11], [8, 136, 22], [45], [11, 87], [1], [149, 147, 146], [79], [152, 7, 14],
 [4, 20], [120, 66], [141, 166, 51], [30, 160], [113, 95, 124], [106, 30], [61, 1], [43, 157],
 [105, 138], [73], [144, 160], [6, 146], [115, 51, 41, 152], [69, 12], [101, 132],[113, 116, 91, 124],
 [10, 121, 114], [50, 35, 40, 15], [124, 102], [58, 108, 10, 88],[40, 139], [26], [20, 166],
 [143, 46, 112], [155, 89, 48], [156, 147], [93, 27], [119, 55, 35], [79, 93, 94], [84, 155, 144],
 [123, 7], [75, 125, 19], [32, 141],[88, 50, 86], [88, 23], [64, 167], [150, 5], [111, 3, 42],
 [134, 6], [128, 16, 154], [161, 26], [127, 158, 74], [78, 167, 115], [109], [42, 143], [130, 68],
 [45, 65],[135, 159], [116, 10, 79, 163, 114, 110], [103, 152, 14, 44], [108, 32], [97, 150],
 [83, 17, 48], [127, 3, 12, 75, 64], [108, 76, 45], [48, 36, 102, 72], [75, 22, 108, 166],
 [159], [9, 17], [87], [50, 61], [76, 128], [25, 16], [90], [75, 64, 78], [91], [142], [158, 101, 113],
 [121, 157, 39, 99], [24, 51], [105, 158, 0], [4, 24], [74, 45], [2], [40, 98], [107], [148, 53],
 [7], [68, 37], [80, 149], [38, 13], [92, 25, 62],[126, 24], [104, 83], [89, 70], [34, 164],
 [71, 57], [1, 85, 57], [136], [139, 57],[111, 96], [50], [29, 72, 14, 109, 59, 165], [39, 2],
 [99, 168], [37, 84, 89],[15, 85], [67, 128], [130, 127, 101], [117, 107, 31], [63, 70, 21, 165],
  [100],[49, 20], [108, 26, 10], [33, 145, 41], [152, 168, 160], [82, 116, 62],[95, 48, 102], [154, 14, 165]]


 */
public class FindAllPath {
    //adj：邻接表
    //start：路径起点所在的数字编号
    //end：路径终点所在的数字编号
    //findAllPath是起点到终点的所有路径的端点索引
    public List<List<Integer>> findAllPathBetweenTwoPoint(List<List<Integer>> adj, int start, int end) {


        //获得最短路径
        FindShortestPath shortestPathSearch = new FindShortestPath();
        List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adj, start, end);
//        System.out.println("shortestPath:"+shortestPath);
        //allPath存放所有路径
        List<List<Integer>> allPath = new ArrayList<>();
        //先把最短路径加入
        allPath.add(shortestPath);

        //当最短路径的元素个数≥3个，才进一步找出其他路径
        if (shortestPath != null && shortestPath.size() >= 3) {

            for (int i = 1; i < shortestPath.size() - 1; i++) {//从第二个元素开始，到倒数第二个元素结束
                //获取path中第i个元素，命名为breakPoint
                Integer breakPoint = shortestPath.get(i);
//                System.out.println("breakPoint:"+breakPoint);
                //将adj中每一个元素逐一加入newAdj中
                List<List<Integer>> newAdj = new ArrayList<>();
                //将adj中每一个元素复制入newAdj中
                for (int j = 0; j < adj.size(); j++) {
                    List<Integer> newAdjItem = new ArrayList<>();
                    newAdjItem.addAll(adj.get(j));
                    newAdj.add(newAdjItem);
                }
                //将newAdj中第breakPoint项的元素清空
                newAdj.get(breakPoint).clear();
//                System.out.println("adj:"+adj);
//                System.out.println("newAdj:"+newAdj);
                //再找一次最短路
//                System.out.println("shortestPathSearch.findShortestPath(newAdj, start, end):"+shortestPathSearch.findShortestPath(newAdj, start, end));
                //若能找到最短路径
                if (shortestPathSearch.findShortestPathBetweenTwoPoint(newAdj, start, end) != null) {
                    List<Integer> otherShortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(newAdj, start, end);
//                    System.out.println("otherShortestPath:"+otherShortestPath);
                    //若allPath中未包含该最短路径otherShortestPath，则将otherShortestPath加入allPath
                    if (!allPath.contains(otherShortestPath)) {
                        allPath.add(otherShortestPath);
                    }


                    List<List<Integer>> copyAdj = new ArrayList<>();
                    for (int j = 0; j < newAdj.size(); j++) {
                        List<Integer> newAdjItem = new ArrayList<>();
                        newAdjItem.addAll(newAdj.get(j));
                        copyAdj.add(newAdjItem);
                    }

                    Integer breakPointNumber = otherShortestPath.get(1);
                    copyAdj.get(breakPointNumber).clear();
                    if (shortestPathSearch.findShortestPathBetweenTwoPoint(copyAdj, start, end) != null) {
                        List<Integer> shortPath = shortestPathSearch.findShortestPathBetweenTwoPoint(copyAdj, start, end);
                        if (!allPath.contains(shortPath)) {
                            allPath.add(shortPath);
                        }
                    }


                }
            }
        }
//        重点与起点互换位置再次进行计算
        List<Integer> shortestPathreversal = shortestPathSearch.findShortestPathBetweenTwoPoint(adj, end, start);
        List<Integer> copyShortestPath = new ArrayList<>();
        for (Integer integer : shortestPathreversal) {
            copyShortestPath.add(integer);
        }
        Collections.reverse(copyShortestPath);
        if (!allPath.contains(copyShortestPath)) {
            allPath.add(copyShortestPath);
        }
        if (shortestPathreversal.size() >= 3) {
            for (int i = 1; i < shortestPathreversal.size() - 1; i++) {//从第二个元素开始，到倒数第二个元素结束
                Integer breakPoint = shortestPathreversal.get(i);
                List<List<Integer>> newAdj = new ArrayList<>();
                for (int j = 0; j < adj.size(); j++) {
                    List<Integer> newAdjItem = new ArrayList<>();
                    newAdjItem.addAll(adj.get(j));
                    newAdj.add(newAdjItem);
                }
                //将newAdj中第breakPoint项的元素清空
                newAdj.get(breakPoint).clear();
                //若能找到最短路径
                if (shortestPathSearch.findShortestPathBetweenTwoPoint(newAdj,end,start) != null) {
                    List<Integer> otherShortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(newAdj,end,start);
                    List<Integer> copyOtherShortestPath = new ArrayList<>();
                    for (Integer integer : otherShortestPath) {
                        copyOtherShortestPath.add(integer);
                    }
                    Collections.reverse(copyOtherShortestPath);


                    if (!allPath.contains(copyOtherShortestPath)) {
                        allPath.add(copyOtherShortestPath);
                    }


                    List<List<Integer>> copyAdj = new ArrayList<>();
                    for (int j = 0; j < newAdj.size(); j++) {
                        List<Integer> newAdjItem = new ArrayList<>();
                        newAdjItem.addAll(newAdj.get(j));
                        copyAdj.add(newAdjItem);
                    }

                    Integer breakPointNumber = otherShortestPath.get(1);
                    copyAdj.get(breakPointNumber).clear();
                    if (shortestPathSearch.findShortestPathBetweenTwoPoint(copyAdj,end ,start ) != null) {
                        List<Integer> shortPath = shortestPathSearch.findShortestPathBetweenTwoPoint(newAdj, end, start);
                        List<Integer> copyShortPath = new ArrayList<>();
                        for (Integer integer : shortPath) {
                            copyShortPath.add(integer);
                        }
                        Collections.reverse(copyShortPath);
                        if (!allPath.contains(copyShortPath)) {
                            allPath.add(copyShortPath);
                        }
                    }
                }
            }

        }
        return allPath;
    }
}
