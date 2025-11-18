package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FindTopoLoop {


    public List<List<String>> recognizeLoopNew(List<List<Integer>> adj, List<String> allPoint,
                                               List<Map<String, Object>> edgeList) {
        List<List<String>> recognizeLoop = new ArrayList<>();
//        对分支进行循环
        for (Map<String, Object> objectMap : edgeList) {
            if (objectMap.get("分支打断").toString().equalsIgnoreCase("S") || objectMap.get("分支打断").toString().equalsIgnoreCase("B")) {
                continue;
            }
//            获取到分支起点和分支终点
            String startPointName = (String) objectMap.get("分支起点名称");
            String endPointName = (String) objectMap.get("分支终点名称");
//            判断分支起点或者分支终点是否存在在allpoint里面  不存在直接跳出当前循环
            if (!allPoint.contains(startPointName) || !allPoint.contains(endPointName)) {
                continue;
            }
            Integer startPointNumber = allPoint.indexOf(startPointName);
            Integer endPointNumber = allPoint.indexOf(endPointName);
//            拷贝一份adj
            List<List<Integer>> copyAdj = new ArrayList<>(adj);
//            删除当前与他有关的分支有关的路劲关系
            copyAdj.get(startPointNumber).remove(endPointNumber);
            copyAdj.get(endPointNumber).remove(startPointNumber);
//            查看删除后的adj的是否存在最短路劲的状况
            FindShortestPath findShortestPath = new FindShortestPath();
            List<Integer> shortestPathBetweenTwoPoint = findShortestPath.findShortestPathBetweenTwoPoint(copyAdj, startPointNumber, endPointNumber);
            if (shortestPathBetweenTwoPoint != null) {
//                recognizeLoop.add(objectMap.get("分支名称").toString());
                recognizeLoop.add(findPathLoop(allPoint, shortestPathBetweenTwoPoint));
            }
        }
        return recognizeLoop;

    }

    //    将对应的点数字编号转为名称
    public List<String> findPathLoop(List<String> allPoints, List<Integer> path) {
        List<String> list = new ArrayList<>();
        for (Integer integer : path) {
            list.add(allPoints.get(integer));
        }
        return list;
    }


}
