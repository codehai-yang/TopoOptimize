package HarnessPackOpti.ProjectInfoOutPut;

import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.Algorithm.GenerateTopoMatrixConnector;
import HarnessPackOpti.Algorithm.IntergateCircuitInfo;
import HarnessPackOpti.CircuitInfoCalculate.CalculateInlineWet;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 配电驱动优化整车信息计算
 */
public class PowerProjectCircuitInfoOutput {

    public static Map<String, Map<String, String>> elecFixedLocationLibrary = null;
    public static Map<String, Double> elecBusinessPrice = null;

    // 构造函数中读取Excel
    public PowerProjectCircuitInfoOutput()  {
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        this.elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        this.elecBusinessPrice = readWireInfoLibrary.getElecBusinessPrice();
    }

    public String powerOptimize(String fileStringFormat) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
        List<Map<String, Object>> points = (List<Map<String, Object>>) projectInfo.get("所有端点信息");
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
        List<Map<String, String>> appposition = (List<Map<String, String>>) projectInfo.get("用电器信息");
        List<Map<String, String>> edges = (List<Map<String, String>>) projectInfo.get("所有分支信息");
        Map<String, Object> caseInfo = (Map<String, Object>) projectInfo.get("方案信息");

        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, String> edge : edges) {
            strPointName.add(edge.get("分支起点名称"));
            endPointName.add(edge.get("分支终点名称"));
            if (edge.get("分支打断").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("分支起点名称").toString());
                interruptedEdgelist.add(edge.get("分支终点名称").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        //获取有向图之间的索引，起点到终点之间的关系
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();

        //分支全部打通情况下的邻接矩阵和邻接列表
        GenerateTopoMatrixConnector adjacencyMatrixGraphConnector = new GenerateTopoMatrixConnector(strPointName, endPointName);
        adjacencyMatrixGraphConnector.adjacencyMatrix();
        adjacencyMatrixGraphConnector.addEdge();
        adjacencyMatrixGraphConnector.getAdj();

        //      读取线径excel文件
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        if(elecFixedLocationLibrary == null) {
            elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        }
        //获取导线无聊单价   商务成本
        if(elecBusinessPrice == null) {
            elecBusinessPrice = readWireInfoLibrary.getElecBusinessPrice();
        }

        //对所有回路进行成本计算,添加到loopdetails
        Map<String, Object> loopdetails = new HashMap<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            Map<String, String> loopInfoStrMap = loopInfo.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue() == null ? null : entry.getValue().toString()
                    ));
            Map<String, Object> twoPointInfo = projectCircuitInfoOutput.findTwoPointInfo(loopInfoStrMap, projectInfo, adjacencyMatrixGraph, elecFixedLocationLibrary, true, null, null, elecBusinessPrice);
            loopdetails.put(twoPointInfo.get("回路id").toString(), twoPointInfo);
        }

        //对所有回路进行分类统计
//       对txt文件里面的回路进行一个分类
        //        系统  系统分类索引
        Map<String, List<String>> systemMap = new HashMap<>();
//        用电器 焊点   回路分类 用电器分类索引
        Map<String, List<String>> elecMap = new HashMap<>();
//         用电器 接口   用电器接口分类
        Map<String, Map<String, Object>> appMap = new HashMap<>();

        for (Map<String, Object> loopInfo : loopInfos) {
            if (elecMap.containsKey(loopInfo.get("回路起点用电器").toString())) {
                elecMap.get(loopInfo.get("回路起点用电器").toString()).add(loopInfo.get("回路id").toString());
            } else {
                List<String> list = new ArrayList<>();
                list.add(loopInfo.get("回路id").toString());
                elecMap.put(loopInfo.get("回路起点用电器").toString(), list);
            }


            if (elecMap.containsKey(loopInfo.get("回路终点用电器").toString())) {
                elecMap.get(loopInfo.get("回路终点用电器").toString()).add(loopInfo.get("回路id").toString());
            } else {
                List<String> list = new ArrayList<>();
                list.add(loopInfo.get("回路id").toString());
                elecMap.put(loopInfo.get("回路终点用电器").toString(), list);
            }

//按照系统分类，建立系统名到回路id列表的映射
            if (loopInfo.get("所属系统") != null && loopInfo.get("所属系统").toString().length() > 0) {
                if (systemMap.containsKey(loopInfo.get("所属系统"))) {
                    systemMap.get(loopInfo.get("所属系统")).add(loopInfo.get("回路id").toString());
                } else {
                    List<String> list = new ArrayList<>();
                    list.add(loopInfo.get("回路id").toString());
                    systemMap.put(loopInfo.get("所属系统").toString(), list);
                }
            }

//            分别查看起点用电器和终点用电器
//            首先判断这个用电器是否是一个焊点
//            不是的情况下查看集合中是否存在这样的用电器 有的情况下直接回路id添加进去  没有创建list 将id添加进去
//            接着查看用电器接口是否为空  不为空的情况下  判断是否存在这个接口名称 存在直接将id添加进去 不存在新建添加进去
            String startApp = "";
            String endApp = "";
            if (!loopInfo.get("回路起点用电器").toString().startsWith("[")) {
                startApp = loopInfo.get("回路起点用电器").toString();
            }
            if (!loopInfo.get("回路终点用电器").toString().startsWith("[")) {
                endApp = loopInfo.get("回路终点用电器").toString();
            }

            if (!startApp.isEmpty()) {
//                集合中还不存在该用电器的情况
                if (!appMap.containsKey(startApp)) {
                    Map<String, Object> electricalMap = new HashMap<>();
//                   用电器接口id
                    Set<String> electricalId = new HashSet<>();
//                    用电器接口集合
                    Map<String, Set<String>> interfaceMap = new HashMap<>();
                    electricalId.add(loopInfo.get("回路id").toString());
//                    用电器接口
                    String startAppPort = "";
                    if (loopInfo.get("回路起点用电器接口编号") != null) {
                        startAppPort = loopInfo.get("回路起点用电器接口编号").toString();
                    }

                    if (!startAppPort.isEmpty()) {
                        Map<String, Set<String>> idList = new HashMap<>();
                        Set<String> list1 = new HashSet<>();
                        list1.add(loopInfo.get("回路id").toString());
                        interfaceMap.put(startAppPort, list1);
                    }
                    electricalMap.put("electricalList", electricalId);
                    electricalMap.put("interfaceMap", interfaceMap);
                    appMap.put(startApp, electricalMap);
                } else {
//                    找出该用电器集合
                    Set<String> stringSet = (Set<String>) appMap.get(startApp).get("electricalList");
                    stringSet.add(loopInfo.get("回路id").toString());
//                    String startAppPort = loopInfo.get("回路起点用电器接口编号").toString();
                    String startAppPort = "";
                    if (loopInfo.get("回路起点用电器接口编号") != null) {
                        startAppPort = loopInfo.get("回路起点用电器接口编号").toString();
                    }
                    if (!startAppPort.isEmpty()) {
//                        找到用电器接口对应的map集合
                        Map<String, Set<String>> interfaceMap = (Map<String, Set<String>>) appMap.get(startApp).get("interfaceMap");
                        if (interfaceMap.containsKey(startAppPort)) {
                            Set<String> stringSet1 = interfaceMap.get(startAppPort);
                            stringSet1.add(loopInfo.get("回路id").toString());
                        } else {
                            Map<String, Set<String>> idList = new HashMap<>();
                            Set<String> list1 = new HashSet<>();
                            list1.add(loopInfo.get("回路id").toString());
                            idList.put(startAppPort, list1);
                            interfaceMap.putAll(idList);
                        }
                    }
                }
            }
            if (!endApp.isEmpty()) {
                if (!appMap.containsKey(endApp)) {
                    Map<String, Object> electricalMap = new HashMap<>();
//                   用电器接口id
                    Set<String> electricalId = new HashSet<>();
//                    用电器接口集合
                    Map<String, Set<String>> interfaceMap = new HashMap<>();
                    electricalId.add(loopInfo.get("回路id").toString());
//                    用电器接口
//                    String startAppPort = loopInfo.get("回路终点用电器接口编号").toString();
                    String startAppPort = "";
                    if (loopInfo.get("回路终点用电器接口编号") != null) {
                        startAppPort = loopInfo.get("回路终点用电器接口编号").toString();
                    }

                    if (!startAppPort.isEmpty()) {
                        Map<String, Set<String>> idList = new HashMap<>();
                        Set<String> list1 = new HashSet<>();
                        list1.add(loopInfo.get("回路id").toString());
                        interfaceMap.put(startAppPort, list1);
                    }
                    electricalMap.put("electricalList", electricalId);
                    electricalMap.put("interfaceMap", interfaceMap);
                    appMap.put(endApp, electricalMap);
                } else {
                    Set<String> stringSet = (Set<String>) appMap.get(endApp).get("electricalList");
                    stringSet.add(loopInfo.get("回路id").toString());
//                    String endAppPort = loopInfo.get("回路终点用电器接口编号").toString();
                    String endAppPort = "";
                    if (loopInfo.get("回路终点用电器接口编号") != null) {
                        endAppPort = loopInfo.get("回路终点用电器接口编号").toString();
                    }
                    if (!endAppPort.isEmpty()) {
                        Map<String, Set<String>> interfaceMap = (Map<String, Set<String>>) appMap.get(endApp).get("interfaceMap");
                        if (interfaceMap.containsKey(endAppPort)) {
                            Set<String> stringSet1 = interfaceMap.get(endAppPort);
                            stringSet1.add(loopInfo.get("回路id").toString());
                        } else {
                            Map<String, Set<String>> idList = new HashMap<>();
                            Set<String> list1 = new HashSet<>();
                            list1.add(loopInfo.get("回路id").toString());
                            idList.put(endAppPort, list1);
                            interfaceMap.putAll(idList);
                        }
                    }
                }
            }
        }

        //统计所有导线选型单价
        Map<String,Double> wirePriceMap = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : elecFixedLocationLibrary.entrySet()) {
            String wireType = entry.getKey();  // 导线选型名称
            Map<String, String> wireTypeInfo = entry.getValue();  // 该导线的详细信息
            String unitPrice = wireTypeInfo.get("导线单位商务价（元/米）");  // 单价
            wirePriceMap.put(wireType, Double.parseDouble(unitPrice));
        }

        Map<String, Object> systemCircuitInfo = new HashMap<>();
        Map<String, Object> elecRelatedCircuitInfo = new HashMap<>();
        Map<String, Object> elecInterfaceRelatedCircuitInfo = new HashMap<>();
        Map<String, Object> bundeleRelatedCircuitInfo = new HashMap<>();


        List<Map<String, Object>> circuitInfo = new LinkedList<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            Map<String, Object> objectMap = (Map<String, Object>) loopdetails.get(loopInfo.get("回路id").toString());
            Double price = null;
            Object wire = objectMap.get("导线选型");
            if(wire != null){
                price = wirePriceMap.get(wire.toString());
            }
            objectMap.put("导线单价", price);
            circuitInfo.add(objectMap);
        }
        //回路绕线长度计算
        projectCircuitInfoOutput.circuitCoilingLength(loopdetails,edges,adjacencyMatrixGraphConnector,projectInfo);
        //所有回路信息的总和
        Map<String, Object> projectCircuitInfo = projectCircuitInfoOutput.circuitProjectInfo(loopdetails);
        //            对分支进行计算
        Set<String> systemMapset = systemMap.keySet();
        IntergateCircuitInfo circuitInfoIntergation = new IntergateCircuitInfo();
        for (String name : systemMapset) {
            List<String> list = systemMap.get(name);
            Map<String, Object> objectMap = circuitInfoIntergation.intergateCircuitInfo(list, loopdetails);
            Map<String, Object> cloneMap = (Map<String, Object>) objectMap.get("circuitInfoIntergation");
            cloneMap.remove("总理论直径");
            cloneMap.remove("分支直径RGB坐标");
            objectMap.put("circuitInfoIntergation", cloneMap);
            //系统回路信息整合
            systemCircuitInfo.put(name, objectMap);
        }

        //        进行用电器计算
        Set<String> appSet = appMap.keySet();

        for (String name : elecMap.keySet()) {
            List<String> listSet = elecMap.get(name);
            Map<String, Object> objectMap1 = circuitInfoIntergation.intergateCircuitInfo(listSet.stream().collect(Collectors.toList()), loopdetails);
            elecRelatedCircuitInfo.put(name, objectMap1);
        }

        for (String name : appSet) {
            Map<String, Object> objectMap = appMap.get(name);
//            用电器接口集合
            Map<String, Object> interfaceDetailList = (Map<String, Object>) objectMap.get("interfaceMap");
            int size = interfaceDetailList.size();
            if (size > 0) {
                Map<String, Object> objectMap2 = new HashMap<>();
                for (String key : interfaceDetailList.keySet()) {
                    Set<String> list1 = (Set<String>) interfaceDetailList.get(key);
                    Map<String, Object> interfaceCost = circuitInfoIntergation.intergateCircuitInfo(list1.stream().collect(Collectors.toList()), loopdetails);
                    objectMap2.put(key, interfaceCost);
                }
                elecInterfaceRelatedCircuitInfo.put(name, objectMap2);
            }
        }

//        分支
        for (Map<String, String> edge : edges) {
            String id = (String) edge.get("分支id编号");
            Map<String, Object> objectMap = projectCircuitInfoOutput.circuitInfoByEdge(id, loopdetails, (String) edge.get("分支名称"));
            bundeleRelatedCircuitInfo.put(id, objectMap);
        }

        //        对每一个分支进行成本计算
        Set<String> bundleIdList = bundeleRelatedCircuitInfo.keySet();
        for (String s : bundleIdList) {
            Map<String, Object> objectMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
            List<String> list = (List<String>) objectMap.get("circuitList");
            CalculateInlineWet calculateInlineWet = new CalculateInlineWet();
            String wet = "";
//           判断这条分支的干湿
            for (Map<String, String> stringStringMap : edges) {
                if (s.equals(stringStringMap.get("分支id编号"))) {
                    String startPointName = stringStringMap.get("分支起点名称").toString();
                    String endPointNameString = stringStringMap.get("分支终点名称").toString();
                    String startPoint = projectCircuitInfoOutput.getWaterParam(startPointName, (List<Map<String, String>>) projectInfo.get("所有端点信息"));
                    String endPoint = projectCircuitInfoOutput.getWaterParam(endPointNameString, (List<Map<String, String>>) projectInfo.get("所有端点信息"));
                    if ("D".equals(startPoint)) {
                        wet = "D";
                    } else {
                        if ("D".equals(endPoint)) {
                            wet = "D";
                        } else {
                            wet = "W";
                        }
                    }
                }
            }
            Map<String, String> colorMap = projectCircuitInfoOutput.getColorByEdges(list, circuitInfo, wet.equals("W"), elecFixedLocationLibrary);
            Map<String, Object> objectMap1 = (Map<String, Object>) objectMap.get("circuitInfoIntergation");
            objectMap1.put("分支打断代价RGB坐标", colorMap.get("color"));
            objectMap1.put("分支打断代价", colorMap.get("cost"));
        }
        //根据回路信息构建分支点对应的分支直径(颜色),放入分支集合里
        Map<String, Map<String, String>> connectedEdgesByMatrix = projectCircuitInfoOutput.findConnectedEdgesByMatrix(points, bundeleRelatedCircuitInfo, adjacencyMatrixGraph, edges);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("systemCircuitInfo", systemCircuitInfo);
        resultMap.put("elecRelatedCircuitInfo", elecRelatedCircuitInfo);
        resultMap.put("elecInterfaceRelatedCircuitInfo", elecInterfaceRelatedCircuitInfo);
        resultMap.put("bundeleRelatedCircuitInfo", bundeleRelatedCircuitInfo);
        resultMap.put("circuitInfo", circuitInfo);
        resultMap.put("projectCircuitInfo", projectCircuitInfo);
        resultMap.put("edgeColorInfo", connectedEdgesByMatrix);
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(resultMap);// 将Map转换为JSON字符串
//        System.out.println("信息汇总:\n" +json);
        return json;
    }
}
