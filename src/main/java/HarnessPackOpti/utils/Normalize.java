package HarnessPackOpti.utils;

import HarnessPackOpti.Algorithm.*;
import HarnessPackOpti.CircuitInfoCalculate.CalculateInlineWet;
import HarnessPackOpti.CircuitInfoCalculate.CalculatePathBreakNumber;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileWriter;
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
     *
     * @param serviceableEdge
     * @return
     */
    public static float[][] normalizeData(List<Map<String, Object>> serviceableEdge,
                                          List<Map<String, Object>> loopInfos,
                                          Map<String, Map<String, String>> elecPosition,
                                          Map<String, Object> jsonMap,
                                          List<Map<String, String>> pointList,
                                          List<String> normList, Map<String, List<String>> multiLoopInfos, Map<String, String> pointMap,
                                          Integer sampleId) {
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        //成本信息
        Map<String, Map<String, String>> elecFixedLocationLibrary = ProjectCircuitInfoOutput.elecFixedLocationLibrary;
        //分支点-湿区成本加成
        Map<String, Float> result = new HashMap<>();
        Set<String> test = new HashSet<>();
        List<String> startName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        Map<String, String> positionNameMap = new HashMap<>();
        Map<String, String> namePositionMap = new HashMap<>();
        List<Point> coordinateList = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : serviceableEdge) {
            startName.add(edge.get("startPointName").toString());
            endPointName.add(edge.get("endPointName").toString());
            test.add(edge.get("startPointName").toString());
            test.add(edge.get("endPointName").toString());
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
            if (edge.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        //获取有向图之间的索引，起点到终点之间的关系
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(startName, endPointName,branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        Map<String, String> multiLocation = new HashMap<>();
        //有顺序的分支点名称列表
        Set<String> branchPointNameList = new LinkedHashSet<>();
        for (int i = 0; i < normList.size(); i++) {
            for (Map<String, Object> k : serviceableEdge) {
                if (k.get("id").equals(normList.get(i))) {
                    String startPointName = k.get("startPointName").toString();
                    String endName = k.get("endPointName").toString();
                    //名称添加
                    branchPointNameList.add(startPointName);
                    branchPointNameList.add(endName);
                    break;
                }
            }
        }
        List<String> allNameList = new ArrayList<>(branchPointNameList);
        //焊点位置选择
        multiLoopInfos.forEach((k, v) -> {
            //寻找焊点最优位置
//            List<String> centerPoint = findCenterPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint(), v);
            Point centerPoint = findCenterPointTwo(v, namePositionMap, coordinateList);
            String s = positionNameMap.get(centerPoint.x + "&" + centerPoint.y);
            if (centerPoint != null) {
                multiLocation.put(k, s);
            }
        });
        //更改appposition焊点位置
        appPositions.forEach(k -> {
            String s = k.get("appName");
            if (s.startsWith("[")) {
                k.put("unregularPointName", multiLocation.get(s));
                k.put("unregularPointId", pointMap.get(s));
            }
        });

        float[][] matrix = new float[branchPointNameList.size()][branchPointNameList.size()];
        //位置点-成本总和
        Map<String, Float> locationPrice = new HashMap<>();
        //导线选型-单价(体现数量)
        for (Map<String, Object> loopInfo : loopInfos) {
            String loopWireway = loopInfo.get("loopWireway").toString();
            //读取线径excel文件
            Map<String, String> materialsMsg = elecFixedLocationLibrary.get(loopWireway);
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            String price = materialsMsg.get("导线单位商务价（元/米）");
            //拿到用电器对应的位置
            String startPosition = null;
            String endPosition = null;
            if (startApp.startsWith("[")) {
                startPosition = multiLocation.get(startApp);
            } else {
                startPosition = elecPosition.get(startApp).values().iterator().next();
            }
            if (endApp.startsWith("[")) {
                endPosition = multiLocation.get(endApp);
            } else {
                endPosition = elecPosition.get(endApp).values().iterator().next();
            }
            String startParam = getWaterParam(startPosition, pointList);
            String endParam = getWaterParam(endPosition, pointList);
            if("w".toUpperCase().equals(startParam) || "w".toUpperCase().equals(endParam)){
                if(result.get(startPosition) == null){
                    result.put(startPosition,Float.parseFloat( price));
                }else {
                    result.put(startPosition,result.get(startPosition) + Float.parseFloat(price));
                }
            }

            if (allNameList.indexOf(startPosition) == -1 || allNameList.indexOf(endPosition) == -1) {
//                System.out.println("startApp:" + startApp + ":" + startPosition + ":" +  allNameList.indexOf(startPosition) + "  endApp:" + endPosition + ":" + allNameList.indexOf(endPosition));
                continue;
            }
            if (locationPrice.get(startPosition + ":" + endPosition) == null) {
                locationPrice.put(startPosition + ":" + endPosition, Float.parseFloat(price));
            } else {
                locationPrice.put(startPosition + ":" + endPosition, locationPrice.get(startPosition + ":" + endPosition) + Float.parseFloat(price));
            }
        }
        //塞入matrix
        locationPrice.forEach((k, v) -> {
            String[] split = k.split(":");
            matrix[allNameList.indexOf(split[0])][allNameList.indexOf(split[1])] = v;
        });
        //统计每个分支点单价总和代替湿区成本
//        List<Float> rowSums = new ArrayList<>(matrix.length);
//        for (int i = 0; i < matrix.length; i++) {
//            float sum = 0;
//            for (int j = 0; j < matrix[i].length; j++) {
//                sum += matrix[i][j];
//            }
//            rowSums.add(sum);
//        }
        // 只收集非0的值
        double[] nonZeroData = new double[matrix.length * matrix[0].length];
        int idx = 0;
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if (matrix[i][j] != 0) {
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
                if (matrix[i][j] != 0) {
                    matrix[i][j] = (float) ((matrix[i][j] - mean) / std);
                }
            }
        }

        //计算每个分支点对应的湿区成本
//        long wetTime = System.currentTimeMillis();
//        float[] originalWetCost = new float[branchPointNameList.size()];
//        for (Map<String, Object> loopInfo : loopInfos) {
//            String startApp = loopInfo.get("startApp").toString();
//            String endApp = loopInfo.get("endApp").toString();
//
//            //起点用电器位置
//            String startAppPosition = null;
//            String endAppPosition = null;
//            if (startApp.startsWith("[")) {
//                startAppPosition = multiLocation.get(startApp);
//            } else {
//                startAppPosition = elecPosition.get(startApp).values().iterator().next();
//            }
//            if (endApp.startsWith("[")) {
//                endAppPosition = multiLocation.get(endApp);
//            } else {
//                endAppPosition = elecPosition.get(endApp).values().iterator().next();
//            }
//            if (result.get(startAppPosition) == null) {
//                Float v = wetCost(startAppPosition, endAppPosition, loopInfo,pointList);
//                if(v == 0){
//                    continue;
//                }
//                if (v != null) {
//                    result.put(startAppPosition, v);
//                }
//            } else {
//                Float v = result.get(startAppPosition);
//                Float tempV = wetCost(startAppPosition, endAppPosition, loopInfo, pointList);
//                if(v ==0 && tempV == 0){
//                    continue;
//                }
//                if (tempV != null) {
//                    result.put(startAppPosition, v + tempV);
//                }
//            }
//        }
//
//        result.forEach((k, v) -> originalWetCost[allNameList.indexOf(k)] = v);
//        System.out.println("湿区成本计算耗时：" + (System.currentTimeMillis() - wetTime));
//        List<Float> wetCostList = new ArrayList<>();
//        long normalizationTime1 = System.currentTimeMillis();
//        result.forEach((k, v) -> wetCostList.add(v));
        // 计算 result 中非0值的均值和标准差
        double[] data = result.values().stream()
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

        // 对 result 中非0的数值进行标准化
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

        // 构建 wet 列表：按 allNameList 顺序从 result 中取值
        List<Float> wet = new ArrayList<>();
        for (int i = 0; i < allNameList.size(); i++) {
            Float v = result.get(allNameList.get(i));
            if (v != null) {
                wet.add(v);
            } else {
                wet.add(0f);
            }
        }

        long projectCircuitInfoOutputTime = System.currentTimeMillis();
        //拼接175*176矩阵
        int newDim = matrix[0].length + 1;
        float[][] newMatrix = new float[matrix.length][newDim];

        for (int i = 0; i < matrix.length; i++) {
            // 复制原来的176维
            System.arraycopy(matrix[i], 0, newMatrix[i], 0, matrix[i].length);
            // 添加标准化后的湿区成本
            newMatrix[i][newDim - 1] = wet.get(i); // 每个节点对应的新特征值
        }
        System.out.println("拼接矩阵耗时：" + (System.currentTimeMillis() - projectCircuitInfoOutputTime));
        return newMatrix;
    }

    /**
     * 单个回路湿区成本
     *
     * @param startAppPosition
     * @param endAppPosition
     * @param loopInfo
     * @param pointList
     * @return
     */
    public static Float wetCost(String startAppPosition,
                                String endAppPosition, Map<String, Object> loopInfo,
                                List<Map<String, String>> pointList) {
//        List<Map<String, String>> temp = serviceableEdge.stream()
//                .map(map -> {
//                    Map<String, String> stringMap = new HashMap<>();
//                    map.forEach((k, v) -> {
//                        if (v != null) {
//                            stringMap.put(k, v.toString());
//                        }
//                    });
//                    return stringMap;
//                })
//                .collect(Collectors.toList());
        Map<String, Map<String, String>> elecFixedLocationLibrary = ProjectCircuitInfoOutput.elecFixedLocationLibrary;
        String loopWireway = loopInfo.get("loopWireway").toString();
        //读取线径excel文件
        Map<String, String> materialsMsg = elecFixedLocationLibrary.get(loopWireway);
        DecimalFormat df = new DecimalFormat("0.00");

//
//        //如果回路起点或终点不在导线矩阵中，则返回null
//        if (adjacencyMatrixGraph.getAllPoint().indexOf(startAppPosition) == -1 || adjacencyMatrixGraph.getAllPoint().indexOf(endAppPosition) == -1) {
//            return null;
//        }
//        //拿到回路最短路径索引，通过边数查找
//        List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(startAppPosition), adjacencyMatrixGraph.getAllPoint().indexOf(endAppPosition));
//        //路径数字转换为对应名称
//        List<String> listname = projectCircuitInfoOutput.convertPathToNumbers(shortestPath, adjacencyMatrixGraph.getAllPoint());
//        Map<String, Object> MapbranchByNode = findBranchByNode(listname, temp);
//        List<String> edgeIdList = (List<String>) MapbranchByNode.get("idList");
//        Map<String, Object> objectMap = calculatePathBreakNumber(edgeIdList, jsonMap);
//        //分支打断名称
//        List<String> topologyStatusCodeNameList = (List<String>) objectMap.get("nameList");
//        Map<String, String> inlineWet = calculateInlineWet(topologyStatusCodeNameList, jsonMap);
//        int count = 0;
//        for (Object mapValue : inlineWet.values()) {
//            if ("w".toUpperCase().equals(mapValue.toString())) {
//                count++;
//            }
//        }
//        if(startAppPosition.equals("仪表线左侧顶棚inline点")){
//            System.out.println("仪表线左侧顶棚inline点inline次数：" + count);
//        }
//        if(startAppPosition.equals("车身线右前inline点")){
//            System.out.println("车身线右前inline点inline次数：" + count);
//        }
//
//        //        回路湿区成本
//        Float connectPrice = Float.parseFloat(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）"));
//        Float defensePrice = Float.parseFloat(materialsMsg.get("湿区成本补偿——防水赛（元/个）"));
//        //inline湿区连接器成本补偿
//        double connectCost = count * connectPrice * 2;
//        //inline湿区防水赛成本补偿
//        double defenseCost = count * defensePrice * 2;
        //计算回路两端连接器干湿
        String startParam = getWaterParam(startAppPosition, pointList);
        String endParam = getWaterParam(endAppPosition, pointList);
        //            回路两端湿区数量
        Integer wetNumber = 0;
        if ("w".toUpperCase().equals(startParam)) {
            wetNumber++;
        }
        if ("w".toUpperCase().equals(endParam)) {
            wetNumber++;
        }
        return Float.parseFloat(df.format(wetNumber * Float.parseFloat(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) + wetNumber * Float.parseFloat(materialsMsg.get("湿区成本补偿——防水赛（元/个）"))));
    }

    /**
     * 判断回路干湿
     *
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
        if (bestWeldPoint == -1) {
            return null;
        }
        return convertPathToNumbers(Arrays.asList(bestWeldPoint), allPoint);
    }

    /**
     * @param v               与焊点关联的用电器
     * @param namePositionMap 用电器名称位置
     * @param coordinateList  所有位置点为C的
     * @return
     * @Description 寻找中心点位置
     */
    public static Point findCenterPointTwo(List<String> v, Map<String, String> namePositionMap, List<Point> coordinateList) {
        List<Point> tempPoint = new ArrayList<>();
        for (String s : v) {
            String position = namePositionMap.get(s);
            String[] split = position.split("&");
            tempPoint.add(new Point(Double.parseDouble(split[0]), Double.parseDouble(split[1])));
        }
        return Point.findMinSumDistancePointTwo(coordinateList, tempPoint);
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
     * @inputExample: [199eecf0-3320-4b2a-86e6-036442fdc317, 199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return: List<String> 打断的分支id   {199eecf0-3320-4b2a-86e6-036442fdc317，199eecf0-3320-4b2a-86e6-036442fdc317}
     */
    public static Map<String, Object> calculatePathBreakNumber(List<String> id, Map<String, Object> map) {

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
     * @Description 根据途径点找到所有的分支  返回分支id
     * @input node 途径点名称
     * @inputExample [前围板外中点, 前围板外中点]
     * @input edges 所有的分支信息
     * @Return 所有分支id [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     */

    public static Map<String, Object> findBranchByNode(List<String> node, List<Map<String, String>> edges) {


        Map<String, Object> resultMap = new HashMap<>();

        List<String> idList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
//        可能存在只有一个点的情况   这种情况分支id就显示为""
        if (node.size() > 1) {
            for (int i = 0; i < node.size() - 1; i++) {
                String start = node.get(i);
                String end = node.get(i + 1);
                for (Map<String, String> edge : edges) {
                    if ((edge.get("startPointName").equals(start) && edge.get("endPointName").equals(end)) || (edge.get("endPointName").equals(start) && edge.get("startPointName").equals(end))) {
                        idList.add(edge.get("id"));
                        nameList.add(edge.get("edgeName"));
                    }
                }
            }
        } else {
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
     * @inputExample: [199eecf0-3320-4b2a-86e6-036442fdc317, 199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return: Map<String, String>  id-> inline干湿   [{199eecf0-3320-4b2a-86e6-036442fdc317:"W"}]
     */
    public static Map<String, String> calculateInlineWet(List<String> branchName, Map<String, Object> map) {
        Map<String, String> stringMap = new HashMap<>();

        for (String s : branchName) {
            List<Map<String, String>> edgeList = (List<Map<String, String>>) map.get("edges");
            for (Map<String, String> stringStringMap : edgeList) {
                if (s.equals(stringStringMap.get("edgeName"))) {
                    String startPointName = stringStringMap.get("startPointName");
                    String endPointName = stringStringMap.get("endPointName");
                    String startPoint = getWaterParam(startPointName, (List<Map<String, String>>) map.get("points"));
                    String endPoint = getWaterParam(endPointName, (List<Map<String, String>>) map.get("points"));
                    if ("D".equals(startPoint)) {
                        stringMap.put(s, "D");
                        continue;
                    } else {
                        if ("D".equals(endPoint)) {
                            stringMap.put(s, "D");
                            continue;
                        } else {
                            stringMap.put(s, "W");
                            continue;
                        }
                    }
                }
            }
        }
        return stringMap;
    }
}