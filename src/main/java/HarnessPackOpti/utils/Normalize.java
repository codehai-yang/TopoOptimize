package HarnessPackOpti.utils;

import HarnessPackOpti.Algorithm.FindBranchByNode;
import HarnessPackOpti.Algorithm.FindShortestPath;
import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
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
    public static float[][] normalizeData(List<Map<String, Object>> serviceableEdge,List<Map<String,Object>> loopInfos,Map<String,Map<String,String>> elecPosition,Map<String, Object> jsonMap,List<Map<String, String>> pointList,List<String> normList){
        Map<String, Map<String, String>> elecFixedLocationLibrary = ProjectCircuitInfoOutput.elecFixedLocationLibrary;

        //分支点-湿区成本加成
        Map<String,Float> result = new HashMap<>();
        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        //有顺序的分支点名称列表
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
            Map.Entry<String, String> next = startMap.entrySet().iterator().next();
            String startPosition = next.getValue();
            Map<String, String> endMap = elecPosition.get(endApp);
            Map.Entry<String, String> next1 = endMap.entrySet().iterator().next();
            String endPosition = next1.getValue();

            if(allNameList.indexOf(startPosition) == -1 || allNameList.indexOf(endPosition) == -1){
                System.out.println("startApp:" + startApp + ":" + startPosition + ":" +  allNameList.indexOf(startPosition) + "  endApp:" + endPosition + ":" + allNameList.indexOf(endPosition));
                continue;
            }
            matrix[allNameList.indexOf(startPosition)][allNameList.indexOf(endPosition)] = Float.parseFloat(materialsMsg.get("导线单位商务价（元/米）"));
            priceList.add(Float.parseFloat(materialsMsg.get("导线单位商务价（元/米）")));
        }
        // 只收集非0且非对角线的值
        double[] nonZeroData = new double[matrix.length * matrix[0].length];
        int idx = 0;
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if (i != j && matrix[i][j] != 0) {
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
                if (i != j && matrix[i][j] != 0) {
                    matrix[i][j] = (float) ((matrix[i][j] - mean) / std);
                }
            }
        }
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : serviceableEdge) {
            if (edge.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        //获取有向图之间的索引，起点到终点之间的关系
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        //计算每个分支点对应的湿区成本
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();

            if(result.get(startApp) == null){
                Float v = wetCost(startApp, endApp, loopInfo, adjacencyMatrixGraph, serviceableEdge, elecPosition, jsonMap, pointList);
                if(v != null) {
                    result.put(startApp, v);
                }
            }else {
                Float v = result.get(startApp);
                Float tempV = wetCost(startApp, endApp, loopInfo, adjacencyMatrixGraph, serviceableEdge, elecPosition, jsonMap, pointList);
                if(v != null) {
                    result.put(startApp, v + tempV);
                }
            }
        }
        List<Float> wetCostList = new ArrayList<>();
        result.forEach((k,v) -> wetCostList.add(v));
        //计算均值和标准差
        double[] data = wetCostList.stream()
                .mapToDouble(Float::doubleValue)
                .filter(v -> v != 0)
                .toArray();
        double wetMean = Arrays.stream(data).average().orElse(0.0);

        double wetStd = Math.sqrt(
                Arrays.stream(data)
                        .map(v -> (v - mean) * (v - mean))
                        .average()
                        .orElse(0.0)
        );
        result.replaceAll((k, v) -> {
            if (v != 0) {
                return (float) ((v - wetMean) / wetStd);
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
        //拼接175*176矩阵
        int newDim = matrix[0].length + 1;
        float[][] newMatrix = new float[matrix.length][newDim];

        for (int i = 0; i < matrix.length; i++) {
            // 复制原来的176维
            System.arraycopy(matrix[i], 0, newMatrix[i], 0, matrix[i].length);
            // 添加湿区成本
            newMatrix[i][newDim - 1] = wet.get(i); // 每个节点对应的新特征值
        }
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
    public static Float wetCost(String startApp, String endApp,Map<String, Object> loopInfo,GenerateTopoMatrix adjacencyMatrixGraph, List<Map<String, Object>> serviceableEdge,Map<String,Map<String,String>> elecPosition,Map<String, Object> jsonMap,List<Map<String, String>> pointList){
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
        Map<String, String> startMap = elecPosition.get(startApp);
        Map.Entry<String, String> next = startMap.entrySet().iterator().next();
        //起点用电器位置名称
        String startPosition = next.getValue();
        Map<String, String> endMap = elecPosition.get(endApp);
        Map.Entry<String, String> next1 = endMap.entrySet().iterator().next();
        //终点用电器位置名称
        String endPosition = next1.getValue();
        //如果回路起点或终点不在导线矩阵中，则返回null
        if (adjacencyMatrixGraph.getAllPoint().indexOf(startPosition) == -1 || adjacencyMatrixGraph.getAllPoint().indexOf(endPosition) == -1) {
            return null;
        }
        //拿到回路最短路径索引，通过边数查找
        List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(startPosition), adjacencyMatrixGraph.getAllPoint().indexOf(endPosition));
        //路径数字转换为对应名称
        List<String> listname = projectCircuitInfoOutput.convertPathToNumbers(shortestPath, adjacencyMatrixGraph.getAllPoint());
        Map<String, Object> MapbranchByNode = findBranchByNode.findBranchByNode(listname, temp);
        List<String> edgeIdList = (List<String>)MapbranchByNode.get("idList");
        Map<String, Object> objectMap = calculatePathBreakNumber.calculatePathBreakNumber(edgeIdList, jsonMap);
        //分支打断名称
        List<String> topologyStatusCodeNameList = (List<String>) objectMap.get("nameList");
        Map<String, String> inlineWet = calculateInlineWet.calculateInlineWet(topologyStatusCodeNameList, jsonMap);
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

}