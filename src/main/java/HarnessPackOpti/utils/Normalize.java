package HarnessPackOpti.utils;

import HarnessPackOpti.Algorithm.*;
import HarnessPackOpti.CircuitInfoCalculate.CalculateInlineWet;
import HarnessPackOpti.CircuitInfoCalculate.CalculatePathBreakNumber;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 标准化数据回路单价与湿区成本,分支长度特征
 */
public class Normalize {
    public static FindShortestPath shortestPathSearch = new FindShortestPath();
    public static CalculateInlineWet calculateInlineWet = new CalculateInlineWet();
    public static CalculatePathBreakNumber calculatePathBreakNumber = new CalculatePathBreakNumber();
    public static ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();

    public static FindBranchByNode findBranchByNode = new FindBranchByNode();
    /**
     * 标准化175*176矩阵
     * @param serviceableEdge
     * @return
     */
    public static float[][] normalizeData(List<Map<String, Object>> serviceableEdge,
                                          List<Map<String,Object>> loopInfos,
                                          Map<String,Map<String,String>> elecPosition,
                                          Map<String, Object> jsonMap,
                                          List<Map<String, String>> pointList,
                                          List<String> normList,Map<String, List<String>> multiLoopInfos,Map<String,String> pointMap){
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        //成本信息
        Map<String, Map<String, String>> elecFixedLocationLibrary = ProjectCircuitInfoOutput.elecFixedLocationLibrary;
        //分支点-湿区成本加成
        Map<String,Float> result = new HashMap<>();
        List<String> startName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        Map<String,String> positionNameMap = new HashMap<>();
        Map<String,String> namePositionMap = new HashMap<>();
        List<Point> coordinateList = new ArrayList<>();
        long prepareTime = System.currentTimeMillis();
        for (Map<String, Object> edge : serviceableEdge) {
            startName.add(edge.get("startPointName").toString());
            endPointName.add(edge.get("endPointName").toString());
            if (edge.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
            if (edge.get("topologyStatusCode").equals("C")) {
                //起点xy坐标
                double startXCoordinate = Double.parseDouble(edge.get("startXCoordinate").toString());
                double startYCoordinate = Double.parseDouble(edge.get("startYCoordinate").toString());
                //终点xy坐标
                double endXCoordinate = Double.parseDouble(edge.get("endXCoordinate").toString());
                double endYCoordinate = Double.parseDouble(edge.get("endYCoordinate").toString());
                coordinateList.add(new Point(startXCoordinate, startYCoordinate));
                coordinateList.add(new Point(endXCoordinate, endYCoordinate));
                positionNameMap.put(startXCoordinate + "&" + startYCoordinate, edge.get("startPointName").toString());
                positionNameMap.put(endXCoordinate + "&" + endYCoordinate, edge.get("endPointName").toString());
                namePositionMap.put(edge.get("startPointName").toString(), startXCoordinate + "&" + startYCoordinate);
                namePositionMap.put(edge.get("endPointName").toString(), endXCoordinate + "&" + endYCoordinate);
            }
        }
        //获取有向图之间的索引，起点到终点之间的关系
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(startName, endPointName, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        Map<String,String> multiLocation = new HashMap<>();
        System.out.println("构建每个方案邻接矩阵以及确定焊点所需要的位置耗时：" + (System.currentTimeMillis() - prepareTime));
        long mulltiTime = System.currentTimeMillis();
        //焊点位置选择
        multiLoopInfos.forEach((k, v) -> {
            //寻找焊点最优位置
//            List<String> centerPoint = findCenterPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint(), v);
            Point centerPoint = findCenterPointTwo(v, namePositionMap, coordinateList);
            String s = positionNameMap.get(centerPoint.x + "&" + centerPoint.y);
            if(centerPoint != null){
                multiLocation.put(k,s);
            }
        });
        //更改appposition焊点位置
        appPositions.forEach(k -> {
            String s = k.get("appName");
            if(s.startsWith("[")){
                k.put("unregularPointName",multiLocation.get(s));
                k.put("unregularPointId",pointMap.get(s));
            }
        });
        System.out.println("寻找焊点位置以及更新焊点位置耗时：" + (System.currentTimeMillis() - mulltiTime));
        //有顺序的分支点名称列表
        long branchTime = System.currentTimeMillis();
        Set<String> branchPointNameList = new LinkedHashSet<>();
        for (int i = 0; i < normList.size(); i++) {
            for (Map<String, Object> k : serviceableEdge) {
                if(k.get("id").equals(normList.get(i))){
                    String startPointName = k.get("startPointName").toString();
                    String endName = k.get("endPointName").toString();
                    //名称添加
                    branchPointNameList.add(startPointName);
                    branchPointNameList.add(endName);
                    break;
                }
            }
        }
        System.out.println("构建有顺序的分支，用于构建175特征耗时：" + (System.currentTimeMillis() - branchTime));
        long priceTime = System.currentTimeMillis();
        List<String> allNameList = new ArrayList<>(branchPointNameList);
        float[][] matrix = new float[branchPointNameList.size()][branchPointNameList.size()];
        List<Float> priceList = new ArrayList<>();
        //回路单价统计
        for (Map<String, Object> loopInfo : loopInfos) {
            String loopWireway = loopInfo.get("loopWireway").toString();
            //读取线径excel文件
            Map<String, String> materialsMsg = elecFixedLocationLibrary.get(loopWireway);
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            //拿到用电器对应的位置
            Map<String, String> startMap = elecPosition.get(startApp);
            Map<String, String> endMap = elecPosition.get(endApp);
            String startPosition = null;
            String endPosition = null;
            if(startMap == null || startMap.isEmpty()) {
                startPosition = multiLocation.get(startApp);
            }else {
                Map.Entry<String, String> next = startMap.entrySet().iterator().next();
                startPosition = next.getValue();
            }
            if(endMap == null || endMap.isEmpty()){
                endPosition = multiLocation.get(endApp);
            }else {
                Map.Entry<String, String> next1 = endMap.entrySet().iterator().next();
                endPosition = next1.getValue();
            }

            if(allNameList.indexOf(startPosition) == -1 || allNameList.indexOf(endPosition) == -1){
//                System.out.println("startApp:" + startApp + ":" + startPosition + ":" +  allNameList.indexOf(startPosition) + "  endApp:" + endPosition + ":" + allNameList.indexOf(endPosition));
                continue;
            }
            matrix[allNameList.indexOf(startPosition)][allNameList.indexOf(endPosition)] = Float.parseFloat(materialsMsg.get("导线单位商务价（元/米）"));
            priceList.add(Float.parseFloat(materialsMsg.get("导线单位商务价（元/米）")));
        }
        System.out.println("回路单价统计耗时：" + (System.currentTimeMillis() - priceTime));
        long normalizationTime = System.currentTimeMillis();
        // 只收集非0的值
        double[] nonZeroData = new double[matrix.length * matrix[0].length];
        int idx = 0;
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if ( matrix[i][j] != 0) {
                    nonZeroData[idx++] = matrix[i][j];
                }
            }
        }
        nonZeroData = Arrays.copyOf(nonZeroData, idx);

        // 用非0值算 mean 和 std
        double mean = Arrays.stream(nonZeroData).average().orElse(0.0);
        double std = Math.sqrt(
                Arrays.stream(nonZeroData)
                        .map(v -> (v - mean) * (v - mean))
                        .average()
                        .orElse(0.0)
        );

        // 只对非0且非对角线的值标准化，0保持不动
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if ( matrix[i][j] != 0) {
                    matrix[i][j] = (float) ((matrix[i][j] - mean) / std);
                }
            }
        }
        System.out.println("175*175特征标准化耗时：" + (System.currentTimeMillis() - normalizationTime));
        //计算每个分支点对应的湿区成本
        long wetTime = System.currentTimeMillis();
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();

            if(result.get(startApp) == null){
                Float v = wetCost(startApp, endApp, loopInfo, adjacencyMatrixGraph, serviceableEdge, elecPosition, jsonMap,pointList, multiLocation);
                if(v != null) {
                    result.put(startApp, v);
                }
            }else {
                Float v = result.get(startApp);
                Float tempV = wetCost(startApp, endApp, loopInfo, adjacencyMatrixGraph, serviceableEdge, elecPosition, jsonMap, pointList,multiLocation);
                if(tempV != null) {
                    result.put(startApp, v + tempV);
                }
            }
        }
        System.out.println("湿区成本计算耗时：" + (System.currentTimeMillis() - wetTime));
        List<Float> wetCostList = new ArrayList<>();
        long normalizationTime1 = System.currentTimeMillis();
        result.forEach((k,v) -> wetCostList.add(v));
        //计算均值和标准差
        double[] data = wetCostList.stream()
                .mapToDouble(Float::doubleValue)
                .filter(v -> v != 0)
                .toArray();
        double wetMean = Arrays.stream(data).average().orElse(0.0);

        double wetStd = Math.sqrt(
                Arrays.stream(data)
                        .map(v -> (v - wetMean) * (v - wetMean))
                        .average()
                        .orElse(0.0)
        );
        result.replaceAll((k, v) -> {
            if (v != 0) {
                if (wetStd > 0) {
                    return (float) ((v - wetMean) / wetStd);
                } else {
                    return 1.0f;
                }
            }
            return v;
        });
        List<Float> wet = new ArrayList<>();
        for (int i = 0; i < allNameList.size(); i++) {
            Float v = result.get(allNameList.get(i));
            if(v != null){
                wet.add(v);
            }else {
                wet.add(0f);
            }
        }
        System.out.println("湿区成本标准化耗时：" + (System.currentTimeMillis() - normalizationTime1));
        long projectCircuitInfoOutputTime = System.currentTimeMillis();
        //拼接175*176矩阵
        int newDim = matrix[0].length + 1;
        float[][] newMatrix = new float[matrix.length][newDim];

        for (int i = 0; i < matrix.length; i++) {
            // 复制原来的176维
            System.arraycopy(matrix[i], 0, newMatrix[i], 0, matrix[i].length);
            // 添加湿区成本
            newMatrix[i][newDim - 1] = wet.get(i); // 每个节点对应的新特征值
        }
        System.out.println("拼接矩阵耗时：" + (System.currentTimeMillis() - projectCircuitInfoOutputTime));
        return newMatrix;
    }

    /**
     * 单个回路湿区成本
     * @param startApp
     * @param endApp
     * @param loopInfo
     * @param adjacencyMatrixGraph
     * @param serviceableEdge
     * @param elecPosition
     * @param jsonMap
     * @param pointList
     * @return
     */
    public static Float wetCost(String startApp,
                                String endApp,Map<String, Object> loopInfo,
                                GenerateTopoMatrix adjacencyMatrixGraph,
                                List<Map<String, Object>> serviceableEdge,
                                Map<String,Map<String,String>> elecPosition,
                                Map<String, Object> jsonMap,
                                List<Map<String, String>> pointList,
                                Map<String,String> multiLocation){
        List<Map<String, String>> temp = serviceableEdge.stream()
                .map(map -> {
                    Map<String, String> stringMap = new HashMap<>();
                    map.forEach((k, v) -> {
                        if (v != null) {
                            stringMap.put(k, v.toString());
                        }
                    });
                    return stringMap;
                })
                .collect(Collectors.toList());
        Map<String, Map<String, String>> elecFixedLocationLibrary = ProjectCircuitInfoOutput.elecFixedLocationLibrary;
        String loopWireway = loopInfo.get("loopWireway").toString();
        //读取线径excel文件
        Map<String, String> materialsMsg = elecFixedLocationLibrary.get(loopWireway);
        DecimalFormat df = new DecimalFormat("0.00");
        //计算这个用电器相关回路的湿区成本加成
        //拿到用电器对应的位置
        Map<String, String> startMap = elecPosition.get(startApp);
        Map<String, String> endMap = elecPosition.get(endApp);
        String startPosition = null;
        String endPosition = null;
        if(startMap == null || startMap.isEmpty()) {
            startPosition = multiLocation.get(startApp);
        }else {
            Map.Entry<String, String> next = startMap.entrySet().iterator().next();
            startPosition = next.getValue();
        }
        if(endMap == null || endMap.isEmpty()){
            endPosition = multiLocation.get(endApp);
        }else {
            Map.Entry<String, String> next1 = endMap.entrySet().iterator().next();
            endPosition = next1.getValue();
        }
        //如果回路起点或终点不在导线矩阵中，则返回null
        if (adjacencyMatrixGraph.getAllPoint().indexOf(startPosition) == -1 || adjacencyMatrixGraph.getAllPoint().indexOf(endPosition) == -1) {
            return null;
        }
        //拿到回路最短路径索引，通过边数查找
        List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(startPosition), adjacencyMatrixGraph.getAllPoint().indexOf(endPosition));
        //路径数字转换为对应名称
        List<String> listname = projectCircuitInfoOutput.convertPathToNumbers(shortestPath, adjacencyMatrixGraph.getAllPoint());
        Map<String, Object> MapbranchByNode = findBranchByNode(listname, temp);
        List<String> edgeIdList = (List<String>)MapbranchByNode.get("idList");
        Map<String, Object> objectMap = calculatePathBreakNumber(edgeIdList, jsonMap);
        //分支打断名称
        List<String> topologyStatusCodeNameList = (List<String>) objectMap.get("nameList");
        Map<String, String> inlineWet = calculateInlineWet(topologyStatusCodeNameList, jsonMap);
        int count = 0;
        for (Object mapValue : inlineWet.values()) {
            if ("w".toUpperCase().equals(mapValue.toString())) {
                count++;
            }
        }
        //        回路湿区成本
        Float connectPrice = Float.parseFloat(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）"));
        Float defensePrice = Float.parseFloat(materialsMsg.get("湿区成本补偿——防水赛（元/个）"));
        //inline湿区连接器成本补偿
        double connectCost = count * connectPrice * 2;
        //inline湿区防水赛成本补偿
        double defenseCost = count * defensePrice * 2;
        //计算回路两端连接器干湿
        String startParam = getWaterParam(startApp, pointList);
        String endParam = getWaterParam(endApp, pointList);
        //            回路两端湿区数量
        Integer wetNumber = 0;
        if ("w".toUpperCase().equals(startParam)) {
            wetNumber++;
        }
        if ("w".toUpperCase().equals(endParam)) {
            wetNumber++;
        }
        return Float.parseFloat(df.format(wetNumber * Float.parseFloat(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) + wetNumber * Float.parseFloat(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) + connectCost + defenseCost));
    }

    /**
     * 判断回路干湿
     * @return
     */
    public static String getWaterParam(String name, List<Map<String, String>> maps) {
        for (Map<String, String> map : maps) {
            if (name.equals(map.get("pointName"))) {
                return map.get("waterParam");
            }
        }
        return null;
    }

    /**
     * @Description 寻找中心点的位置
     * @input adj 邻接矩阵
     * @input allPoint  GenerateTopoMatrix构成的allpoint所有的节点
     * @input terminal 用电器节点
     * @inputExample {空调箱右点，前挡右出点}
     * @Return 返回中心点的名称   [仪表线左中点, 前顶横梁左中点, 前舱右纵梁中点, 前舱中部右点, 空调箱中点, 仪表线左侧inline点, 仪表线左侧顶棚inline点, 仪表线右侧inline点, 前舱线左后inline点, 前挡右出点, 前舱中后部左点, 前舱左纵梁后点, 车身线右前inline点, 仪表右侧点, 前舱中后部中点, 前舱中后部右点, 空调箱左点, 前舱左纵梁后中点, 前围板内右点, 前顶横梁右中点, 顶棚右前点, 前围板内中点, 仪表线右中点, 顶棚右前inline点, 仪表线右侧顶棚inline点, 前顶横梁左点, 空调箱右点, 前舱右纵梁后点, 车身线左前inline点, 顶棚左前点, 前舱线右后inline点]
     */
    public static List<String> findCenterPoint(List<List<Integer>> adj, List<String> allPoint, List<String> terminal) {
        //焊点相关回路所有用电器之间最短路径的为支点
//        Set<Integer> set = new HashSet<>();
        Map<Integer, Integer> pointCount = new HashMap<>();

//        FindAllPath findAllPath = new FindAllPath();
//        Map<String,List<Integer>> path = new HashMap<>();
        FindShortestPath findShortestPath = new FindShortestPath();
        for (int i = 0; i < terminal.size(); i++) {
            for (int j = i + 1; j < terminal.size(); j++) {
                int start = allPoint.indexOf(terminal.get(i));
                int endpoint = allPoint.indexOf(terminal.get(j));
                if (start == -1 || endpoint == -1) {
                    return null;
                }
                List<Integer> shortestPathBetweenTwoPoint = findShortestPath.findShortestPathBetweenTwoPoint(adj, start, endpoint);
                for (Integer point : shortestPathBetweenTwoPoint) {
                    // 跳过用电器本身，只统计中间分支点
                    pointCount.merge(point, 1, Integer::sum);
                }
            }
        }
        int bestWeldPoint = pointCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(allPoint.indexOf(terminal.get(0)));         //没有交点默认返回一个为支点
        if(bestWeldPoint == -1){
            return null;
        }
        return convertPathToNumbers(Arrays.asList(bestWeldPoint), allPoint);
    }

    /**
     * @Description 寻找中心点位置
     * @param v 与焊点关联的用电器
     * @param namePositionMap 用电器名称位置
     * @param coordinateList 所有位置点为C的
     * @return
     */
    public static Point findCenterPointTwo(List<String> v,Map<String,String> namePositionMap,List<Point> coordinateList) {
        List<Point> tempPoint = new ArrayList<>();
        for (String s : v) {
            String position = namePositionMap.get(s);
            String[] split = position.split("&");
            tempPoint.add(new Point(Double.parseDouble(split[0]), Double.parseDouble(split[1])));
        }
        return Point.findMinSumDistancePoint(coordinateList, tempPoint);
    }


        /**
         * @Description 将路径数字转为点
         * @input numberPath 数字路径
         * @inputExample [23, 17, 135]
         * @input adjacencyMatrixGraph中的allPoint
         * @Return 返回数字对应的点 [仪表线左中点, 前顶横梁左中点, 前舱右纵梁中点]
         */
    public static List<String> convertPathToNumbers(List<Integer> numberPath, List<String> allPoint) {
        List<String> points = new ArrayList<>();
        for (Integer point : numberPath) {
            points.add(allPoint.get(point));
        }
        return points;
    }

    /**
     * @Description: 分支打断的状况   分支打断为S为打断
     * @input: id 分支id
     * @inputExample:  [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return:   List<String> 打断的分支id   {199eecf0-3320-4b2a-86e6-036442fdc317，199eecf0-3320-4b2a-86e6-036442fdc317}
     */
    public static   Map<String, Object> calculatePathBreakNumber(List<String> id, Map<String, Object> map) {

        Map<String, Object> resultMap = new HashMap<>();
//        id
        List<String> idList = new ArrayList<>();
//        name
        List<String> nameList = new ArrayList<>();
        for (String s : id) {
            List<Map<String, String>> edgeList = (List<Map<String, String>>) map.get("edges");
            for (Map<String, String> stringMap : edgeList) {
                if (s.equals(stringMap.get("id"))) {
                    String topologyStatusCode = stringMap.get("topologyStatusCode");
                    if ("S".toUpperCase().equals(topologyStatusCode)) {
                        idList.add(stringMap.get("id"));
                        nameList.add(stringMap.get("edgeName"));
                    }
                }
            }
        }

        resultMap.put("idList", idList);
        resultMap.put("nameList", nameList);

        return resultMap;
    }
    /**
     * @Description  根据途径点找到所有的分支  返回分支id
     * @input   node 途径点名称
     * @inputExample   [前围板外中点,前围板外中点]
     * @input  edges 所有的分支信息
     * @Return 所有分支id [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     */

    public static Map<String, Object> findBranchByNode(List<String> node,List<Map<String, String>> edges){


        Map<String, Object> resultMap = new HashMap<>();

        List<String> idList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
//        可能存在只有一个点的情况   这种情况分支id就显示为""
        if (node.size()>1){
            for (int i = 0; i < node.size()-1 ; i++) {
                String start = node.get(i);
                String end = node.get(i + 1);
                for (Map<String, String> edge : edges) {
                    if ((edge.get("startPointName").equals(start) && edge.get("endPointName").equals(end)) || (edge.get("endPointName").equals(start) && edge.get("startPointName").equals(end))) {
                        idList.add(edge.get("id"));
                        nameList.add(edge.get("edgeName"));
                    }
                }
            }
        }else {
            idList.add("");
            nameList.add("");
        }


        resultMap.put("idList", idList);
        resultMap.put("nameList", nameList);
        return resultMap;
    }

    /**
     * @Description: 根据excel表格中导线选型获取湿区成本补偿——连接器塑壳（元/端） 两端全为w为湿  否则就是干
     * @input: id 分支id
     * @inputExample:  [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return:  Map<String,String>  id-> inline干湿   [{199eecf0-3320-4b2a-86e6-036442fdc317:"W"}]
     */
    public static Map<String ,String> calculateInlineWet(List<String> branchName, Map<String, Object> map) {
        Map<String,String> stringMap=new HashMap<>();

        for (String s : branchName) {
            List<Map<String, String>> edgeList = (List<Map<String, String>>) map.get("edges");
            for (Map<String, String> stringStringMap : edgeList) {
                if (s.equals(stringStringMap.get("edgeName"))){
                    String startPointName =  stringStringMap.get("startPointName");
                    String endPointName =  stringStringMap.get("endPointName");
                    String startPoint = getWaterParam(startPointName, (List<Map<String, String>>) map.get("points"));
                    String endPoint = getWaterParam(endPointName, (List<Map<String, String>>) map.get("points"));
                    if ("D".equals(startPoint)){
                        stringMap.put(s,"D");
                        continue;
                    }else {
                        if ("D".equals(endPoint)){
                            stringMap.put(s,"D");
                            continue;
                        }else {
                            stringMap.put(s,"W");
                            continue;
                        }
                    }
                }
            }
        }
        return stringMap;
    }
}