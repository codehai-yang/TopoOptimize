package HarnessPackOpti.ProjectInfoOutPut;

import HarnessPackOpti.Algorithm.*;
import HarnessPackOpti.CircuitInfoCalculate.CalculateCircuitInfo;
import HarnessPackOpti.CircuitInfoCalculate.CalculateInlineWet;
import HarnessPackOpti.CircuitInfoCalculate.CalculatePathBreakNumber;
import HarnessPackOpti.CircuitInfoCalculate.CalculatePathLength;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.collections4.map.LinkedMap;
import org.apache.poi.util.StringUtil;

import java.io.File;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ProjectCircuitInfoOutput {
    public static Map<String, Map<String, String>> elecFixedLocationLibrary = null;
    public static Map<String, Double> elecBusinessPrice = null;

    // 构造函数中读取Excel
    public ProjectCircuitInfoOutput() throws Exception {
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        this.elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        this.elecBusinessPrice = readWireInfoLibrary.getElecBusinessPrice();
    }

    public static void main(String[] args) throws Exception {

        File file = new File("E:\\office\\idea\\ideaProject\\project20251009\\src\\main\\resources\\20250630.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        String json = projectCircuitInfoOutput.projectCircuitInfoOutput(jsonContent);
        File outputFile = new File("E:\\office\\idea\\ideaProject\\project20251009\\src\\main\\resources\\output.txt");
        Files.write(outputFile.toPath(), json.getBytes());
        System.out.println("JSON已成功输出到: " + outputFile.getAbsolutePath());

    }


    public String projectCircuitInfoOutput(String fileStringFormat) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
        List<Map<String, Object>> points = (List<Map<String, Object>>) projectInfo.get("所有端点信息");
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
        List<Map<String, String>> appposition = (List<Map<String, String>>) projectInfo.get("用电器信息");
        List<Map<String, String>> edges = (List<Map<String, String>>) projectInfo.get("所有分支信息");
        Map<String, Object> caseInfo = (Map<String, Object>) projectInfo.get("方案信息");
        Boolean whetherToChange = caseInfo.get("直连接口是否发生变化") == null || caseInfo.get("直连接口是否发生变化").toString().equals("false") ? false : true;

        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        for (Map<String, String> k : edges) {
            strPointName.add(k.get("分支起点名称"));
            endPointName.add(k.get("分支终点名称"));
        }
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, String> edge : edges) {
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

//       在points 找出所有可能发生变化点   并且将同一组的放在一起
        Map<String, List<String>> interfaceCodegroup = new HashMap<>();
        Set<String> pointNameSet = new HashSet<>();
        if (whetherToChange) {
            for (Map<String, Object> point : points) {
                if (point.get("端点接口直连编号") != null && !point.get("端点接口直连编号").toString().trim().isEmpty()) {
                    String interfaceCode = point.get("端点接口直连编号").toString();
                    String pointName = point.get("端点名称").toString();
                    interfaceCode = interfaceCode.substring(0, interfaceCode.length() - 1);
                    if (interfaceCodegroup.containsKey(interfaceCode)) {
                        interfaceCodegroup.get(interfaceCode).add(pointName);
                    } else {
                        List<String> pointNames = new ArrayList<>();
                        pointNames.add(pointName);
                        interfaceCodegroup.put(interfaceCode, pointNames);
                    }
                    pointNameSet.add(pointName);
                }
            }
        }

//        找出所有的焊点
        Set<String> functionPointSet = new HashSet<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            if (loopInfo.get("回路起点用电器").toString().startsWith("[") || loopInfo.get("回路终点用电器").toString().startsWith("[")) {
                if (loopInfo.get("回路起点用电器").toString().startsWith("[")) {
                    functionPointSet.add(loopInfo.get("回路起点用电器").toString());
                }
                if (loopInfo.get("回路终点用电器").toString().startsWith("[")) {
                    functionPointSet.add(loopInfo.get("回路终点用电器").toString());
                }
            }
        }
//        找出用电器存在可变点的集合
        List<String> electricalSet = new ArrayList<>();
        FindElecLocation findElecLocation = new FindElecLocation();
        //用电器名称-用电器固化后位置/用户更改后用电器位置
        List<Map<String, String>> mapList = findElecLocation.getEleclection(projectInfo);
//        用电器对应的可变位置点 {BCM:[位置1、位置2、位置3],CPM:[位置四、位置5]}
        Map<String, List<String>> eleclectionAddress = new HashMap<>();
        for (Map<String, String> map : mapList) {
            if (map.get("value") != null && pointNameSet.contains(map.get("value").toString())) {
                electricalSet.add(map.get("key"));
                String value = map.get("value").toString();
                for (String interfaceCode : interfaceCodegroup.keySet()) {
                    List<String> list = interfaceCodegroup.get(interfaceCode);
                    if (list.contains(value)) {
                        eleclectionAddress.put(map.get("key"), list);
                    }
                }
            }
        }

        ClassifyCircuit classifyCircuit = new ClassifyCircuit();
        Map<String, Object> group = group(projectInfo, electricalSet, elecFixedLocationLibrary, functionPointSet,
                adjacencyMatrixGraph, appposition,edges,  projectInfo);
        //固定回路
        List<Map<String, Object>> fixedLoops = (List<Map<String, Object>>) group.get("fixedLoops");
        //不固定回路进行分组
        List<List<Map<String, Object>>> grouplists = (List<List<Map<String, Object>>>) group.get("groupLoops");
        //不固定回路不参与分组
        List<Map<String, Object>> nonfixedNotGroupLoops = (List<Map<String, Object>>) group.get("nonfixedNotGroupLoops");

//       接下来就是对所有的回路进行一个计算并且返回回路的最终计算结果
        Map<String, Object> loopdetails = new HashMap<>();
//        对已经固定的回路进行计算
        Map<String, Object> fixclassifyCircuit = new HashMap<>();
        fixclassifyCircuit.put("回路用电器信息", fixedLoops);
        //两点回路和多点回路
        Map<String, Object> fixedLoopInfosMap = classifyCircuit.classifyCircuit(fixclassifyCircuit);
//        1、对这些回路进行一个分类 ：两点的    焊点的
        List<Map<String, String>> fixTwoPoints = (List<Map<String, String>>) fixedLoopInfosMap.get("twoPoint");
        Map<String, List<Map<String, String>>> fixMultiLoopInfos = (Map<String, List<Map<String, String>>>) fixedLoopInfosMap.get("multiLoopInfos");

//        进行计算  并且将最终的结果添加到 loopdetails里面
//        对固定两点的进行计算
        for (Map<String, String> list : fixTwoPoints) {
            //单个固定回路，所有回路信息，矩阵对象，导线价格信息，用电器可变位置点，导线无聊单价商务成本
            Map<String, Object> twoPointInfo = findTwoPointInfo(list, projectInfo, adjacencyMatrixGraph, elecFixedLocationLibrary, true, null, electricalSet, elecBusinessPrice);
            loopdetails.put(twoPointInfo.get("回路id").toString(), twoPointInfo);
        }
//        对焊点的进行计算
        Set<String> set = fixMultiLoopInfos.keySet();
        for (String name : set) {
            //焊点名称，拿到该焊点对应的所有回路,矩阵对象，导线价格信息，所有回路信息，是否固定，用电器可变位置点名称，导线物料单价商务成本
            Map<String, Object> groupInfo = findGroupInfo(name, fixMultiLoopInfos.get(name), adjacencyMatrixGraph, elecFixedLocationLibrary, projectInfo, true, null, electricalSet, elecBusinessPrice);
//            将所有的回路都添加到loopdetails里面
            int size = groupInfo.keySet().size();
            for (int i = 1; i < size; i++) {
                Map<String, Object> objectMap = (Map<String, Object>) groupInfo.get("到" + i + "用电器的信息");
                Map<String, Object> map = (Map<String, Object>) objectMap.get("最优路径");
                loopdetails.put(map.get("回路id").toString(), map);
            }
        }


        List<Map<String, Object>> finallyBestLoop = new ArrayList<>();
//        对可变的回路进行计算  并且将最优结果添加到loopdetails里面  对grouplist每一组进行计算
        for (List<Map<String, Object>> grouplist : grouplists) {
//            找出回路中的所有的可变用电器 将接口添加数组中去 并且找出所有的可变点
            Map<String, Set<String>> electricalInterFace = new HashMap<>();
            //收集可变用电器接口信息,记录每个可变用电器涉及的接口
            for (Map<String, Object> map : grouplist) {
                //检查回路起点或终点是否为可变用电器
                if (electricalSet.contains(map.get("回路起点用电器").toString()) || electricalSet.contains(map.get("回路终点用电器").toString())) {
                    if (electricalSet.contains(map.get("回路起点用电器").toString())) {
                        if (map.get("回路起点用电器接口编号") != null) {
                            if (electricalInterFace.containsKey(map.get("回路起点用电器").toString())) {
                                electricalInterFace.get(map.get("回路起点用电器").toString()).add(map.get("回路起点用电器接口编号").toString());
                            } else {
                                Set<String> list = new HashSet<>();
                                //记录设计的接口编号
                                list.add(map.get("回路起点用电器接口编号").toString());
                                electricalInterFace.put(map.get("回路起点用电器").toString(), list);
                            }
                        } else {
                            if (electricalInterFace.containsKey(map.get("回路起点用电器").toString())) {
                                electricalInterFace.get(map.get("回路起点用电器").toString()).add("null");
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add("null");
                                electricalInterFace.put(map.get("回路起点用电器").toString(), list);
                            }
                        }
                    }
                    if (electricalSet.contains(map.get("回路终点用电器").toString())) {
                        if (map.get("回路终点用电器接口编号") != null) {
                            if (electricalInterFace.containsKey(map.get("回路终点用电器").toString())) {
                                electricalInterFace.get(map.get("回路终点用电器").toString()).add(map.get("回路终点用电器接口编号").toString());
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add(map.get("回路终点用电器接口编号").toString());
                                electricalInterFace.put(map.get("回路终点用电器").toString(), list);
                            }
                        } else {
                            if (electricalInterFace.containsKey(map.get("回路终点用电器").toString())) {
                                electricalInterFace.get(map.get("回路终点用电器").toString()).add("null");
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add("null");
                                electricalInterFace.put(map.get("回路终点用电器").toString(), list);
                            }
                        }
                    }
                }
            }
//            对可能存在的情况进行一个排列组合，对所有可变用电器及其可能的位置进行排列组合
            List<Map<String, Object>> allpossibility = new ArrayList<>();
            for (String elec : electricalInterFace.keySet()) {
                List<Map<String, Object>> currentpossibility = new ArrayList<>(allpossibility);
//            用电器对应的接口
                Set<String> list = electricalInterFace.get(elec);
//            用电器对应的位置
                List<String> addressList = eleclectionAddress.get(elec);
                //生成排列组合
                for (String s : list) {
                    if (allpossibility.size() == 0) {
                        for (String address : addressList) {
                            Map<String, Object> map = new HashMap<>();
                            Map<String, String> interfaceMap = new HashMap<>();
                            interfaceMap.put(s, address);
                            map.put(elec, interfaceMap);
                            currentpossibility.add(map);
                        }
                    } else {
                        List<Map<String, Object>> tempPossibility = new ArrayList<>();
                        for (String address : addressList) {
                            for (Map<String, Object> objectMap : currentpossibility) {
                                Map<String, Object> copy = new HashMap<>(objectMap);
                                if (copy.containsKey(elec)) {
                                    Map<String, Object> map1 = (Map<String, Object>) copy.get(elec);
                                    map1.put(s, address);
                                } else {
                                    Map<String, String> interfaceMap = new HashMap<>();
                                    interfaceMap.put(s, address);
                                    copy.put(elec, interfaceMap);
                                }
                                tempPossibility.add(copy);
                            }
                        }
                        currentpossibility = tempPossibility;
                    }
                    allpossibility = currentpossibility;
                }
            }
//            将当前的grouplist进行一个分组   两点直连的   存在焊点的
            Map<String, Object> nonfixclassifyCircuit = new HashMap<>();
            nonfixclassifyCircuit.put("回路用电器信息", grouplist);
            Map<String, Object> nonfixedLoopInfosMap = classifyCircuit.classifyCircuit(nonfixclassifyCircuit);
            List<Map<String, String>> nonfixTwoPoints = (List<Map<String, String>>) nonfixedLoopInfosMap.get("twoPoint");
            Map<String, List<Map<String, String>>> nonfixMultiLoopInfos = (Map<String, List<Map<String, String>>>) nonfixedLoopInfosMap.get("multiLoopInfos");
//            存储当组里面所有的每种可能性对应的回路信息
            List<Map<String, Object>> allpossibilityLoopInfoList = new ArrayList<>();
//            对所有的情况都进行一个计算
            for (Map<String, Object> objectMap : allpossibility) {
                List<Map<String, Object>> currentloopdetails = new ArrayList<>();
//            对两点直连的 进行计算 并将最优的结果添加到loopdetails里面
                Boolean flag = true;//该方案是否可行
                for (Map<String, String> list : nonfixTwoPoints) {
                    //处理两点直连回路
                    Map<String, Object> twoPointInfo = findTwoPointInfo(list, projectInfo, adjacencyMatrixGraph, elecFixedLocationLibrary, false, objectMap, electricalSet, elecBusinessPrice);
                    if (twoPointInfo == null) {
                        flag = false;
                        break;
                    }
                    currentloopdetails.add(twoPointInfo);

                }
                if (!flag) {
                    continue;
                }
//            对焊点的进行一个计算 并将最优的结果添加到loopdetails里面
//            对焊点的进行计算
                Set<String> nonfixMultiLoopInfosSet = nonfixMultiLoopInfos.keySet();
                //处理焊点族群回路
                for (String name : nonfixMultiLoopInfosSet) {
                    //处理焊点族群回路
                    Map<String, Object> groupInfo = findGroupInfo(name, nonfixMultiLoopInfos.get(name), adjacencyMatrixGraph, elecFixedLocationLibrary, projectInfo, false, objectMap, electricalSet, elecBusinessPrice);
                    if (groupInfo == null) {
                        flag = false;
                        break;
                    }
                    int size = groupInfo.keySet().size();
                    for (int i = 1; i < size; i++) {
                        Map<String, Object> groupDetailMap = (Map<String, Object>) groupInfo.get("到" + i + "用电器的信息");
                        currentloopdetails.add((Map<String, Object>) groupDetailMap.get("最优路径"));
                    }
                }
                if (!flag) {
                    continue;
                }
                Map<String, Double> stringDoubleMap = calculateLoppListCost(currentloopdetails);
                Map<String, Object> currentSchemeMap = new HashMap<>();
                currentSchemeMap.put("回路信息", currentloopdetails);
                currentSchemeMap.put("成本", stringDoubleMap);
                currentSchemeMap.put("可变方案", objectMap);
                allpossibilityLoopInfoList.add(currentSchemeMap);
            }
//            在allpossibilityLoopInfoList 找出最优的一个方案
            if (allpossibilityLoopInfoList.size() > 1) {
                //从所有配置中选择最优方案，基于成本差异和重量差异进行评分
                Map<String, Object> bestPossibility = findBestPossibility(allpossibilityLoopInfoList);
                finallyBestLoop.add(bestPossibility);
            } else if (allpossibilityLoopInfoList.size() == 1) {
                Map<String, Object> bestPossibility = allpossibilityLoopInfoList.get(0);
                List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) bestPossibility.get("回路信息");
                finallyBestLoop.add(bestPossibility);
            }
        }

//        将finallyBestLoop 进行一个提取 1、回路添加到loopdetails里面   接口位置点进行一个整合
        Map<String, Object> bestInterFaceInfo = new HashMap<>();
        for (Map<String, Object> map : finallyBestLoop) {
            List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) map.get("回路信息");
            Map<String, Object> elecinterface = (Map<String, Object>) map.get("可变方案");
            //提取回路信息添加到全局结果
            for (Map<String, Object> objectMap : mapList1) {
                loopdetails.put(objectMap.get("回路id").toString(), objectMap);
            }
            //将每个最优方案中用电器与其接口位置对应关系整合到faceinfo中，map记录了每个用电器最佳位置配置
            for (String s : elecinterface.keySet()) {
                Map<String, String> elecinterfacedetail = (Map<String, String>) elecinterface.get(s);
                if (bestInterFaceInfo.containsKey(s)) {
                    Map<String, String> map1 = (Map<String, String>) bestInterFaceInfo.get(s);
                    map1.putAll(elecinterfacedetail);
                } else {
                    bestInterFaceInfo.put(s, elecinterfacedetail);
                }
            }

        }
//        接下来就是对 剔除部分的回路进行计算，处理未分组可变回路
        Map<String, Object> nonfixedNotGroupLoopsMap = new HashMap<>();
        nonfixedNotGroupLoopsMap.put("回路用电器信息", nonfixedNotGroupLoops);
        Map<String, Object> nonfixedLoopInfosMap = classifyCircuit.classifyCircuit(nonfixedNotGroupLoopsMap);
        List<Map<String, String>> nonfixedNotGroupLoopsTwo = (List<Map<String, String>>) nonfixedLoopInfosMap.get("twoPoint");
        Map<String, List<Map<String, String>>> nonfixedNotGroupLoopsMapMultiLoopInfos = (Map<String, List<Map<String, String>>>) nonfixedLoopInfosMap.get("multiLoopInfos");

        List<Map<String, Object>> currentloopdetails = new ArrayList<>();
//            对两点直连的 进行计算 并将最优的结果添加到loopdetails里面
        //使用之前确定的最佳用电器位置配置来计算这些简单回路
        for (Map<String, String> list : nonfixedNotGroupLoopsTwo) {
            Map<String, Object> twoPointInfo = findTwoPointInfo(list, projectInfo, adjacencyMatrixGraph, elecFixedLocationLibrary, false, bestInterFaceInfo, electricalSet, elecBusinessPrice);
            loopdetails.put(twoPointInfo.get("回路id").toString(), twoPointInfo);
        }
//            对焊点的进行一个计算 并将最优的结果添加到loopdetails里面
//            对焊点的进行计算
        //同样使用已确定的最佳位置配置来处理焊点族群回路
        Set<String> nonfixMultiLoopInfosSet = nonfixedNotGroupLoopsMapMultiLoopInfos.keySet();
        for (String name : nonfixMultiLoopInfosSet) {
            Map<String, Object> groupInfo = findGroupInfo(name, nonfixedNotGroupLoopsMapMultiLoopInfos.get(name), adjacencyMatrixGraph, elecFixedLocationLibrary, projectInfo, false, bestInterFaceInfo, electricalSet, elecBusinessPrice);
            int size = groupInfo.keySet().size();
            for (int i = 1; i < size; i++) {
                Map<String, Object> groupDetailMap = (Map<String, Object>) groupInfo.get("到" + i + "用电器的信息");
                Map<String, Object> map = (Map<String, Object>) groupDetailMap.get("最优路径");
                loopdetails.put(map.get("回路id").toString(), map);
            }
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

        Map<String, Object> systemCircuitInfo = new HashMap<>();
        Map<String, Object> elecRelatedCircuitInfo = new HashMap<>();
        Map<String, Object> elecInterfaceRelatedCircuitInfo = new HashMap<>();
        Map<String, Object> bundeleRelatedCircuitInfo = new HashMap<>();


        List<Map<String, Object>> circuitInfo = new LinkedList<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            Map<String, Object> objectMap = (Map<String, Object>) loopdetails.get(loopInfo.get("回路id").toString());
            circuitInfo.add(objectMap);
        }
        //回路绕线长度计算
        circuitCoilingLength(loopdetails,edges,adjacencyMatrixGraphConnector,projectInfo);
        //所有回路信息的总和
        Map<String, Object> projectCircuitInfo = circuitProjectInfo(loopdetails);
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
            Map<String, Object> objectMap = circuitInfoByEdge(id, loopdetails, (String) edge.get("分支名称"));
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
                    String startPoint = getWaterParam(startPointName, (List<Map<String, String>>) projectInfo.get("所有端点信息"));
                    String endPoint = getWaterParam(endPointNameString, (List<Map<String, String>>) projectInfo.get("所有端点信息"));
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
            Map<String, String> colorMap = getColorByEdges(list, circuitInfo, wet.equals("W"), elecFixedLocationLibrary);
            Map<String, Object> objectMap1 = (Map<String, Object>) objectMap.get("circuitInfoIntergation");
            objectMap1.put("分支打断代价RGB坐标", colorMap.get("color"));
            objectMap1.put("分支打断代价", colorMap.get("cost"));
        }


        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("systemCircuitInfo", systemCircuitInfo);
        resultMap.put("elecRelatedCircuitInfo", elecRelatedCircuitInfo);
        resultMap.put("elecInterfaceRelatedCircuitInfo", elecInterfaceRelatedCircuitInfo);
        resultMap.put("bundeleRelatedCircuitInfo", bundeleRelatedCircuitInfo);
        resultMap.put("circuitInfo", circuitInfo);
        resultMap.put("projectCircuitInfo", projectCircuitInfo);
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(resultMap);// 将Map转换为JSON字符串
        //Excel导出
//        ExportExcelUtils exportExcelUtils = new ExportExcelUtils();
//        exportExcelUtils.exportExcel(systemCircuitInfo,elecRelatedCircuitInfo,caseInfo, loopdetails);
        //excel导出
//        System.out.println("信息汇总:\n" +json);
//        System.out.println("算法总耗时" + (System.currentTimeMillis() - startTime));
        return json;
    }

    public void circuitCoilingLength(Map<String, Object> loopdetails,List<Map<String, String>> edges,GenerateTopoMatrixConnector adjacencyMatrixGraphConnector,Map<String, Object> projectInfo) {

        //对所有回路添加回路绕线字段
        FindShortestPath shortestPathSearch = new FindShortestPath();
        FindAllPath findAllPath = new FindAllPath();
        FindBranchByNode findBranchByNode = new FindBranchByNode();
        CalculatePathLength calculatePathLength = new CalculatePathLength();
        Set<String> idSet = loopdetails.keySet();
        DecimalFormat df = new DecimalFormat("0.00");
        for (String id : idSet) {
            Map<String,Object> tempInfo = (Map<String,Object>)loopdetails.get(id);
            //获取起点和终点的位置名称
//            System.out.println("回路信息：" + tempInfo.toString());
            Object startName = tempInfo.get("起点位置名称");
            Object endName = tempInfo.get("终点位置名称");
            //如果起点位置或终点位置有一个为焊点并且位置相同，位置赋值
            if(startName == null || endName == null){
                //如果回路长度为默认的0.2，则判定起点和终点都在同一位置点
                if("0.2".equals(tempInfo.get("回路长度").toString())){
                    tempInfo.put("回路绕线长度", "0.2");
                    continue;
                }
                Object solderName = tempInfo.get("焊点位置名称");
                //终点用电器为焊点
                if(startName != null && solderName != null){
                    endName = solderName;
                }else if(endName != null && solderName != null){ //起点用电器为焊点
                    startName = solderName;
                }
            }


            Double distance = Double.parseDouble(tempInfo.get("回路长度").toString());
            //根据现有邻接列表查找两点的最短路径
            List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adjacencyMatrixGraphConnector.getAdj(), adjacencyMatrixGraphConnector.getAllPoint().indexOf(startName.toString()), adjacencyMatrixGraphConnector.getAllPoint().indexOf(endName.toString()));
            //找两点之间的所有路径
            List<Double> lengthList = new ArrayList<>();
            if(shortestPath != null){
                List<List<Integer>> allPathBetweenPoint = findAllPath.findAllPathBetweenTwoPoint(adjacencyMatrixGraphConnector.getAdj(), adjacencyMatrixGraphConnector.getAllPoint().indexOf(startName.toString()), adjacencyMatrixGraphConnector.getAllPoint().indexOf(endName.toString()));
                for (List<Integer> ids : allPathBetweenPoint) {
                    List<String> listName = convertPathToNumbers(ids, adjacencyMatrixGraphConnector.getAllPoint());
                    Map<String, Object> branchInfo = findBranchByNode.findBranchByNode(listName,edges);
                    List<String> edgeIdList= (List<String>) branchInfo.get("idList");
                    Map<String, Object> pathLength = calculatePathLength.calculatePathLength(edgeIdList, projectInfo);
                    Double length = (Double) pathLength.get("长度") + 200;
                    //两点距离
                    length = Double.parseDouble(df.format(length / 1000));
                    lengthList.add(length);
                }
            }
            Double minLength = Collections.min(lengthList);
            tempInfo.put("回路绕线长度",Double.parseDouble(df.format(distance - minLength)));
        }
    }

    /**
     * @Description: 对当前所有回路进行一个分组
     * @input: projectInfo 解析完成的项目信息
     * @input: electricalSet 可变用电器信息(名称)
     * @input: elecFixedLocationLibrary 导线选型的excel文件中sheet1的信息
     * @input: functionPointSet 所有功能点的集合
     * @Return: Map<String, Object> 返回三个组 ： 固定回路、固定回路不参与分组、不固定回路
     */
    public Map<String, Object> group(Map<String, Object> projectInfo,
                                     List<String> electricalSet,
                                     Map<String, Map<String, String>> elecFixedLocationLibrary,
                                     Set<String> functionPointSet,
                                     GenerateTopoMatrix adjacencyMatrixGraph,
                                     List<Map<String,String>> app,
                                     List<Map<String,String>> edges,
                                     Map<String, Object> mapFile) throws JsonProcessingException {

        Map<String, Object> resultMap = new HashMap<>();
        //        对集合里面的回路进行一个分类 一类是可变的  一类是不可变的
        ClassifyCircuit classifyCircuit = new ClassifyCircuit();
        //两点回路的信息和多点的回路信息
        Map<String, Object> projectMap = classifyCircuit.classifyCircuit(projectInfo);
//        不固定回路参与分组
        List<Map<String, Object>> nonfixedLoops = new ArrayList<>();
//        不固定回路不参与分组
        List<Map<String, Object>> nonfixedNotGroupLoops = new ArrayList<>();
//        固定回路
        List<Map<String, Object>> fixedLoops = new ArrayList<>();
        List<Map<String, Object>> twoPointMaps = (List<Map<String, Object>>) projectMap.get("twoPoint");
        Map<String, Object> multiLoopInfos = (Map<String, Object>) projectMap.get("multiLoopInfos");
        //焊点集合
        Set<String> multiLoopInfosSet = multiLoopInfos.keySet();
//        所有不固定回路
        List<Map<String, Object>> allNonfixedLoops = new ArrayList<>();
//
        Map<String, Object> circuitProjectInfo = new HashMap<>();
//        筛选出当中需要进行分组分的回路 1、起点用电器和终点电器 以及接口编号都一样的 的所有回路  单位价格总和大于三块   2、焊点的单条回路单位价格大于三块  也进行保留
        for (Map<String, Object> objectMap : twoPointMaps) {
            //判断起点或者终点是否是可变，起点和终点用电器是否包含在可变用电器集合里
            if (electricalSet.contains(objectMap.get("回路起点用电器").toString()) || electricalSet.contains(objectMap.get("回路终点用电器").toString())) {
                allNonfixedLoops.add(objectMap);
                if (electricalSet.contains(objectMap.get("回路起点用电器").toString()) && electricalSet.contains(objectMap.get("回路终点用电器").toString())) {
                    String startApp = objectMap.get("回路起点用电器").toString();
                    String endApp = objectMap.get("回路终点用电器").toString();
                    String startAppPort = objectMap.get("回路起点用电器接口编号") == null ? "" : objectMap.get("回路起点用电器接口编号").toString();
                    String endAppPort = objectMap.get("回路终点用电器接口编号") == null ? "" : objectMap.get("回路终点用电器接口编号").toString();
                    if (circuitProjectInfo.containsKey(startApp + startAppPort + "-" + endApp + endAppPort)) {
                        List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) circuitProjectInfo.get(startApp + startAppPort + "-" + endApp + endAppPort);
                        mapList1.add(objectMap);
                    } else if (circuitProjectInfo.containsKey(endApp + endAppPort + "-" + startApp + startAppPort)) {
                        List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) circuitProjectInfo.get(endApp + endAppPort + "-" + startApp + startAppPort);
                        mapList1.add(objectMap);
                    } else {
                        String key = startApp + startAppPort + "-" + endApp + endAppPort;
                        List<Map<String, Object>> mapList1 = new ArrayList<>();
                        mapList1.add(objectMap);
                        circuitProjectInfo.put(key, mapList1);
                    }
                }
            } else {
                //可变用电器集合里不包含起点或者终点的回路，即固定回路
                fixedLoops.add(objectMap);
            }
        }
        FindShortestPath findShortestPath = new FindShortestPath();
//            对回路两端都是可变用电器的回路单位成本进行检查
        List<String> withoutRegardIDList = new ArrayList<>();
        for (String s : circuitProjectInfo.keySet()) {
            List<Map<String, Object>> circuitProjectInfoList = (List<Map<String, Object>>) circuitProjectInfo.get(s);
            Double degression = 0.0;
            List<String> idList = new ArrayList<>();
            for (Map<String, Object> map : circuitProjectInfoList) {
                idList.add(map.get("回路id").toString());
                String loopWireway = map.get("回路导线选型").toString();
                Map<String, String> map1 = elecFixedLocationLibrary.get(loopWireway);
//               找最短距离 起点-终点最短路径列表
//                获取用电器所在位置
                //用电器起点和终点对应的索引列表；回路起点用电器和终点用电器在表头的索引位置
                List<Integer> shortestPathBetweenTwoPoint = findShortestPath.findShortestPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(findNode(map.get("回路起点用电器").toString(),app)), adjacencyMatrixGraph.getAllPoint().indexOf(findNode(map.get("回路终点用电器").toString(),app)));
                //获取最短路径每个点的用电器名称
                List<String> listname = convertPathToNumbers(shortestPathBetweenTwoPoint, adjacencyMatrixGraph.getAllPoint());

                //        根据路径获取途径点获取分支,上面只是拿到了路径点，但是分支信息还是没有获取到
                FindBranchByNode findBranchByNode = new FindBranchByNode();
                //分支id与分支名称，一个回路上的所有分支
                Map<String, Object> MapbranchByNode = findBranchByNode.findBranchByNode(listname, edges);
                List<String> edgeIdList = (List<String>)MapbranchByNode.get("idList");
                List<String> edgeNameList = (List<String>)MapbranchByNode.get("nameList");
//        获取回路长度
                CalculatePathLength calculatePathLength = new CalculatePathLength();
                //分支长度
                Map<String, Object> pathLength = calculatePathLength.calculatePathLength(edgeIdList, mapFile);
                double length = (Double) pathLength.get("长度");

                degression = length * Double.parseDouble(map1.get("导线单位商务价（元/米）"));
            }
            if (degression < 5) {
                withoutRegardIDList.addAll(idList);
            }
        }
//     将符合要求的分支添加里面进行分组
        for (Map<String, Object> allNonfixedLoop : allNonfixedLoops) {
            if (withoutRegardIDList.contains(allNonfixedLoop.get("回路id").toString())) {
                nonfixedNotGroupLoops.add(allNonfixedLoop);
            } else {
                nonfixedLoops.add(allNonfixedLoop);
            }
        }


        for (String s : multiLoopInfosSet) {

//            flag   组团里面是否存在可变的点 存在整个团都扔进去
            Boolean flag = false;
            //拿到对应焊点的指定回路
            List<Map<String, Object>> multiLoopInfoDetails = (List<Map<String, Object>>) multiLoopInfos.get(s);
            for (Map<String, Object> objectMap : multiLoopInfoDetails) {
                //焊点回路是否包含可变用电器
                if (electricalSet.contains(objectMap.get("回路起点用电器").toString()) || electricalSet.contains(objectMap.get("回路终点用电器").toString())) {
                    flag = true;
                }
            }
            if (flag) {
//                nonfixedLoops.addAll(multiLoopInfoDetails);
//                对每一个存在焊点的回路还要具体的查看  当成本大于3的时候再添加进行
                for (Map<String, Object> multiLoopInfoDetail : multiLoopInfoDetails) {
                    String loopWireway = multiLoopInfoDetail.get("回路导线选型").toString();
                    Map<String, String> map1 = elecFixedLocationLibrary.get(loopWireway);
                    if (Double.parseDouble(map1.get("导线单位商务价（元/米）")) > 5) {
                        //导线商务价 大于5的回路进行分组
                        nonfixedLoops.add(multiLoopInfoDetail);

                    } else {
                        nonfixedNotGroupLoops.add(multiLoopInfoDetail);
                    }
                }
            } else {
                //不存在焊点的回路放进固定回路
                fixedLoops.addAll(multiLoopInfoDetails);
            }
        }

        SplitCircuitByInterDirectConn splitCircuitByInterDirectConn = new SplitCircuitByInterDirectConn();
        Set<String> allElecSet = new HashSet<>();
        //放入所有焊点名称
        allElecSet.addAll(functionPointSet);
        //可变用电器集合名称
        allElecSet.addAll(electricalSet);
        //对不固定回路进行分组
        List<List<Map<String, Object>>> grouplists = splitCircuitByInterDirectConn.groupLoops(nonfixedLoops, new ArrayList<>(allElecSet));

        resultMap.put("groupLoops", grouplists);
        //固定回路
        resultMap.put("fixedLoops", fixedLoops);
        //不固定回路不参与分组
        resultMap.put("nonfixedNotGroupLoops", nonfixedNotGroupLoops);
        return resultMap;
    }


    /**
     * @Description 根据传入的传入的回路集合  计算该条分支下面的成本
     * @input listId  对应的回路id
     * @input circuitList   整个回路整合后的信息
     * @input condition  干湿状况
     * @input elecFixedLocationLibrary 导线选型读取的excel文件
     * @Return 当前分支的湿区补偿以及对应的颜色
     */
    public static Map<String, String> getColorByEdges(List<String> listId,
                                                      List<Map<String, Object>> circuitInfo,
                                                      Boolean condition,
                                                      Map<String, Map<String, String>> elecFixedLocationLibrary) {
        Map<String, String> resultMap = new HashMap<>();

        Double cost = 0.0;
        for (String id : listId) {
            for (Map<String, Object> objectMap : circuitInfo) {
                if (id.equals(objectMap.get("回路id").toString())) {
                    String wireType = objectMap.get("导线选型").toString();
                    Map<String, String> wireTypeMap = (Map<String, String>) elecFixedLocationLibrary.get(wireType);
                    double v = Double.parseDouble(wireTypeMap.get("导线打断成本（元/次）"));
                    cost = cost + v;
                    if (condition) {
                        double v1 = Double.parseDouble(wireTypeMap.get("湿区成本补偿——防水赛（元/个）"));
                        double v2 = Double.parseDouble(wireTypeMap.get("湿区成本补偿——连接器塑壳（元/端）"));
                        cost = cost + v1 * 2 + v2 * 2;
                    }
                }
            }
        }
//        对计算的成本进行取色
        String color = getCostColor(cost);
        resultMap.put("cost", cost.toString());
        resultMap.put("color", color);
        return resultMap;
    }

    /**
     * @Description 根据分支id获取回路信息
     * @input edgeId 分支id
     * @inputExample [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input pointList  整车回路整合后的信息
     * @Return 当前分支下面的回路信息
     */
    public static Map<String, Object> circuitInfoByEdge(String edgeId, Map<String, Object> pointList, String edgeName) {

        Map<String, Object> map = new HashMap<>();
//        返回的回路
        List<String> mapList = new ArrayList<>();
//        返回的极值
        Map<String, Object> totalCost = new HashMap<>();
        totalCost.put("总成本", 0.0);
        totalCost.put("回路湿区成本总加成", 0.0);
        totalCost.put("回路打断总成本", 0.0);
        totalCost.put("回路两端端子总成本", 0.0);
        totalCost.put("回路导线总成本", 0.0);
        totalCost.put("回路总重量", 0.0);
        totalCost.put("回路总长度", 0.0);
        totalCost.put("回路分支名称", edgeName);
        totalCost.put("端子总成本", 0.0);
        totalCost.put("连接器塑壳总成本", 0.0);
        totalCost.put("防水塞总成本", 0.0);
        totalCost.put("回路绕线长度总值", 0.0);
        totalCost.put("回路绕线长度均值", 0.0);
        totalCost.put("回路打断总次数", 0);
        totalCost.put("回路打断数量占比", "0.00%");
        totalCost.put("回路打断成本代价均值", 0.0);
        double lenght = 0.0;
        int count = 0;
        int circuitBreakNum = 0;
        DecimalFormat df = new DecimalFormat("0.00");
//        遍历查找分支所经过的回路
        Set<String> stringSet = pointList.keySet();
        for (String s : stringSet) {
            Map<String, Object> objectMap = (Map<String, Object>) pointList.get(s);
            List<String> list = (List<String>) objectMap.get("回路所有分支id");
            if (list.contains(edgeId)) {
                totalCost.put("总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("总成本").toString()) + Double.parseDouble(objectMap.get("回路总成本").toString()))));
                totalCost.put("回路湿区成本总加成", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路湿区成本总加成").toString()) + Double.parseDouble(objectMap.get("回路湿区成本加成").toString()))));
                totalCost.put("回路打断总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路打断总成本").toString()) + Double.parseDouble(objectMap.get("回路打断成本").toString()))));
                totalCost.put("回路导线总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路导线总成本").toString()) + Double.parseDouble(objectMap.get("回路导线成本").toString()))));
                totalCost.put("回路两端端子总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路两端端子总成本").toString()) + Double.parseDouble(objectMap.get("回路两端端子成本").toString()))));
                totalCost.put("回路总重量", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) + Double.parseDouble(objectMap.get("回路重量").toString()))));
                totalCost.put("回路总长度", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) + Double.parseDouble(objectMap.get("回路长度").toString()))));
                totalCost.put("端子总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("端子总成本").toString()) + Double.parseDouble(objectMap.get("端子成本").toString()))));
                totalCost.put("连接器塑壳总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("连接器塑壳总成本").toString()) + Double.parseDouble(objectMap.get("连接器塑壳成本").toString()))));
                totalCost.put("防水塞总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("防水塞总成本").toString()) + Double.parseDouble(objectMap.get("防水塞成本").toString()))));
                totalCost.put("回路绕线长度总值", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路绕线长度总值").toString()) + Double.parseDouble(objectMap.get("回路绕线长度").toString()))));
                lenght += Double.parseDouble(objectMap.get("回路理论直径").toString()) * Double.parseDouble(objectMap.get("回路理论直径").toString());
                if(Double.parseDouble(objectMap.get("回路打断次数").toString()) > 0){
                    circuitBreakNum++;
                }
                mapList.add(objectMap.get("回路id").toString());
            }
        }
        totalCost.put("回路打断总次数", circuitBreakNum);
        totalCost.put("回路数量(打断前)", mapList.size());
        int coiling = 0;
        //回路打断后统计
        for (String id : mapList) {
            Map<String, Object> objectMap = (Map<String, Object>) pointList.get(id);
            int i = Integer.parseInt(objectMap.get("回路打断次数").toString());
            double coilingNum = Double.parseDouble(objectMap.get("回路绕线长度").toString());
            if(coilingNum > 0){
                coiling++;
            }
            i += 1;
            count += i;
        }
        if(coiling > 0){
            totalCost.put("回路绕线长度均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路绕线长度总值").toString()) / coiling)));
        }
        if(mapList.size() > 0){
            double coilingPercent = (double)coiling / mapList.size() * 100;
            double breakNumb = Double.parseDouble(totalCost.get("回路打断总次数").toString()) / mapList.size() * 100;
            totalCost.put("回路打断成本代价均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路打断总成本").toString()) / mapList.size())));
            totalCost.put("回路绕线数量占比",df.format(coilingPercent) + "%");
            totalCost.put("回路打断数量占比",df.format(breakNumb) + "%");
        }else {
            totalCost.put("回路绕线数量占比","0.00%");
        }
        totalCost.put("回路绕线数量",coiling);
        totalCost.put("回路数量(打断后)", count);
        //回路长度均值
        double avgLength = 0.00;
        if(mapList.size() > 0){
            avgLength = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / mapList.size()));
        }
        totalCost.put("回路长度均值(打断前)",avgLength);
        //回路均值打断后
        double avgLength2 = 0.00;
        if(count > 0){
            avgLength2 = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / count));
        }
        totalCost.put("回路长度均值(打断后)",avgLength2);
        totalCost.put("总理论直径", Double.parseDouble(df.format(Math.sqrt(lenght) * 1.3)));
        totalCost.put("分支直径RGB坐标", getlengthColor((Double) totalCost.get("总理论直径")));
        map.put("circuitInfoIntergation", totalCost);
        map.put("circuitList", mapList);
        return map;
    }


    /**
     * @Description 根据分支id获取回路信息
     * @input pointList 需要计算的处理后分支信息
     * @Return 当前分支下面的回路信息 包括：总成本、回路湿区成本总加成、回路打断总成本、回路两端端子总成本、回路导线总成本、回路总重量、总理论直径、回路总长度
     */
    public Map<String, Object> circuitProjectInfo(Map<String, Object> pointList) {
//        总成本
        Map<String, Object> totalCost = new HashMap<>();
        totalCost.put("总成本", 0.0);
        totalCost.put("回路湿区成本总加成", 0.0);
        totalCost.put("回路打断总成本", 0.0);
        totalCost.put("回路两端端子总成本", 0.0);
        totalCost.put("回路导线总成本", 0.0);
        totalCost.put("回路总重量", 0.0);
        totalCost.put("回路总长度", 0.0);
        totalCost.put("端子总成本", 0.0);
        totalCost.put("连接器塑壳总成本", 0.0);
        totalCost.put("防水塞总成本", 0.0);
        totalCost.put("回路绕线总长度",0.0);
        totalCost.put("回路绕线数量", 0);
        totalCost.put("回路绕线数量占比","0.00%");
        totalCost.put("回路绕线长度均值", 0.0);
        totalCost.put("回路打断总次数",0);
        totalCost.put("回路打断数量占比","0.00%");
        totalCost.put("回路打断成本代价均值", 0.0);
        double lenght = 0.0;
        int count = 0;
        int coiling = 0;
        int circuitBreakNum = 0;
        DecimalFormat df = new DecimalFormat("0.00");
        Set multiLoopInfosSet = pointList.keySet();
        for (Object o : multiLoopInfosSet) {
            Map<String, Object> objectMap = (Map<String, Object>) pointList.get(o);
            totalCost.put("总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("总成本").toString()) + Double.parseDouble(objectMap.get("回路总成本").toString()))));
            totalCost.put("回路湿区成本总加成", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路湿区成本总加成").toString()) + Double.parseDouble(objectMap.get("回路湿区成本加成").toString()))));
            totalCost.put("回路打断总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路打断总成本").toString()) + Double.parseDouble(objectMap.get("回路打断成本").toString()))));
            totalCost.put("回路导线总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路导线总成本").toString()) + Double.parseDouble(objectMap.get("回路导线成本").toString()))));
            totalCost.put("回路两端端子总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路两端端子总成本").toString()) + Double.parseDouble(objectMap.get("回路两端端子成本").toString()))));
            totalCost.put("回路总重量", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) + Double.parseDouble(objectMap.get("回路重量").toString()))));
            totalCost.put("回路总长度", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) + Double.parseDouble(objectMap.get("回路长度").toString()))));
            totalCost.put("端子总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("端子总成本").toString()) + Double.parseDouble(objectMap.get("端子成本").toString()))));
            totalCost.put("连接器塑壳总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("连接器塑壳总成本").toString()) + Double.parseDouble(objectMap.get("连接器塑壳成本").toString()))));
            totalCost.put("防水塞总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("防水塞总成本").toString()) + Double.parseDouble(objectMap.get("防水塞成本").toString()))));
            totalCost.put("回路绕线总长度", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路绕线总长度").toString()) + Double.parseDouble(objectMap.get("回路绕线长度").toString()))));
            lenght += Double.parseDouble(objectMap.get("回路理论直径").toString()) * Double.parseDouble(objectMap.get("回路理论直径").toString());
            if(Double.parseDouble(objectMap.get("回路打断次数").toString()) > 0){
                circuitBreakNum++;
            }
            if(Double.parseDouble(objectMap.get("回路绕线长度").toString()) > 0){
                coiling++;
            }
            int i = Integer.parseInt(objectMap.get("回路打断次数").toString());
            i += 1;
            count += i;
        }
        totalCost.put("回路打断总次数",circuitBreakNum);
        totalCost.put("回路绕线数量",coiling);
        if(coiling > 0){
            totalCost.put("回路绕线长度均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路绕线总长度").toString()) / coiling)));
        }
        if(pointList.size() > 0){
            double coilingPercent = (double)coiling / pointList.size() * 100;
            totalCost.put("回路绕线数量占比",df.format(coilingPercent) + "%");
        }else {
            totalCost.put("回路绕线数量占比","0.00%");
        }
        totalCost.put("回路数量(打断前)", multiLoopInfosSet.size());
        totalCost.put("回路数量(打断后)", count);
        //打断前回路均值
        double avgLength = 0.00;
        if(multiLoopInfosSet.size() > 0){
            avgLength = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / multiLoopInfosSet.size()));
            double breakNumb = Double.parseDouble(totalCost.get("回路打断总次数").toString()) / multiLoopInfosSet.size() * 100;
            totalCost.put("回路打断数量占比",df.format(breakNumb) + "%");
            totalCost.put("回路打断成本代价均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路打断总成本").toString()) / multiLoopInfosSet.size())));
        }
        totalCost.put("回路长度均值(打断前)", avgLength);
        double vagLength2 = 0.00;
        if(count > 0){
            vagLength2 = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / count));
        }
        totalCost.put("回路长度均值(打断后)", vagLength2);
        totalCost.put("总理论直径", Double.parseDouble(df.format(Math.sqrt(lenght) * 1.3)));
        totalCost.put("分支直径RGB坐标", getlengthColor((Double) totalCost.get("总理论直径")));

        return totalCost;
    }


    /**
     * @Description 在所有的方案中找出最优的一个方案(再次遍历所有方案 ， 根据与最小成本方案的成本和重量差异计算得分 ， 最小成本方案得分为 - 4.0 ( 得分 = ( 当前重量 - 最小方案的重量) / (当前成本-最小方案的成))
     * @input maps:所有路径信息
     * @Return 最优的一条路径
     */
    public Map<String, Object> findBestPossibility(List<Map<String, Object>> allpossibilityLoopInfoList) {
        Map<String, Object> minCostMap = null;
        double minTotalCost = Double.MAX_VALUE;

        for (Map<String, Object> map : allpossibilityLoopInfoList) {
            Map<String, Double> costMap = (Map<String, Double>) map.get("成本");
            double totalCost = costMap.get("总成本");

            if (totalCost < minTotalCost) {
                minTotalCost = totalCost;
                minCostMap = map;
            }
        }
        Map<String, Double> standardMap = (Map<String, Double>) minCostMap.get("成本");
        Double standardCost = standardMap.get("总成本");
        Double standardWeight = standardMap.get("总重量");

        for (Map<String, Object> map : allpossibilityLoopInfoList) {
            Map<String, Double> costMap = (Map<String, Double>) map.get("成本");
            if (costMap.get("总成本") != minTotalCost) {
                Double cost = costMap.get("总成本");
                Double weight = costMap.get("总重量");
                Double score = (weight - standardWeight) / (cost - standardCost + 0.001);
                map.put("得分", score);
            } else {
                map.put("得分", -4.0);
            }

        }
        Map<String, Object> finallyCostMap = null;
        double finallyScore = Double.MAX_VALUE;

        for (Map<String, Object> map : allpossibilityLoopInfoList) {
            double score = (Double) map.get("得分");
            if (score < finallyScore) {
                finallyScore = score;
                finallyCostMap = map;
            }
        }
        return finallyCostMap;
    }


    //    根据给定的回路信息  计算当前list的总成本、总长度、总重量
    public Map<String, Double> calculateLoppListCost(List<Map<String, Object>> loopList) {
        Map<String, Double> resultCost = new HashMap<>();
        resultCost.put("总成本", 0.0);
        resultCost.put("总长度", 0.0);
        resultCost.put("总重量", 0.0);
        for (Map<String, Object> map : loopList) {
            Double cost = (Double) map.get("回路总成本");
            Double length = (Double) map.get("回路长度");
            Double weight = (Double) map.get("回路重量");
            resultCost.put("总成本", resultCost.get("总成本") + cost);
            resultCost.put("总长度", resultCost.get("总长度") + length);
            resultCost.put("总重量", resultCost.get("总重量") + weight);
        }
        return resultCost;

    }


    /**
     * @Description: 单条路径的方法
     * @input: twoMap 分类完成的单个回路信息
     * @input: projectInfo 解析完成的txt的文件信息
     * @input: adjacencyMatrixGraph 构建的邻接矩阵
     * @input: elecFixedLocationLibrary excel表中的线径信息
     * @input: whetherFix 用电器是都固定 true：固定  false：不固定
     * @input: objectMap 不固定用电器每个接口对应的位置
     * @input: electricalSet 不固定用电器的集合
     * @input: elecBusinessPrice 读取导线选型的excel表的sheet2的信息
     * @Return: 回路信息 当前回路中最优的一条信息
     * {起点用电器名称=BMS, 起点用电id=ee8600dc-1ab6-4fa2-a043-1e0a0cb1c6d7, 起点位置名称=前围板外中点, 起点位置id=f753b6fe-39bd-4602-8cb3-b663c79af003, 终点用电器名称=LVManuSrvcDscnctr, 终点用电id=2955c804-a8bd-463f-baff-ce063f0f5695, 终点位置名称=车身线左后inline点, 终点位置id=b9e1aee2-7e19-4bf0-9618-5b16de00aa84, 回路属性=null, 导线选型=FLRY-B 0.5, 回路编号=5349, 方案号=85342e86-dd12-498d-b6a4-a45a3bd96fce, 所属系统=系统Others, 回路起点用电器接口编号=, 回路终点用电器接口编号=, 回路信号名=BMS-KL30.2, 湿区两端连接器成本补偿=0.0, 湿区两端防水塞成本补偿=0.0, 焊点名称=null, 回路id=017874d0-de96-4a88-b253-0d4f42b2849f, 焊点位置名称=null, 焊点位置id=null, 回路途径分支点=[前围板外中点, 前围板外左点, 前舱左纵梁后点, 前舱线左后inline点, 车身线左前inline点, 左门槛中点, 左门槛后点, 左后轮包顶点, 后围板内左点, 车身线左后inline点], 回路总成本=43.365026, 回路湿区成本加成=0.0, inline湿区连接器成本补偿=0.0, inline湿区防水塞成本补偿=0.0, 回路打断成本=0.2, 回路导线成本=42.765026, 回路总重量=3.7390186666666665, 回路总长度=7.01066, 回路打断分支=[d89d0217-8ef0-41f2-835c-49ca8f7e069c], 回路打断次数=1, 回路所有分支=[199eecf0-3320-4b2a-86e6-036442fdc317, a6300929-2419-4a1f-b257-7c68d9a3e3c1, 8a464b8f-d481-465a-a7ce-f320d548d83e, d89d0217-8ef0-41f2-835c-49ca8f7e069c, 1710d6e7-d788-4bdb-adb8-10cfc0160f26, 263afced-fd0d-4ddc-9bfe-355643f80812, 674c6947-31a3-4879-9f73-5db9f7b7b43e, 027dc9a1-bb19-425f-aa16-55a6c26bdb62, 0577f318-7a61-4e13-82fb-4ddd7bbf8167], 回路所有分支数量=9, 回路直径=2.1}
     */
    public Map<String, Object> findTwoPointInfo(Map<String, String> twoMap,
                                                Map<String, Object> projectInfo,
                                                GenerateTopoMatrix adjacencyMatrixGraph,
                                                Map<String, Map<String, String>> elecFixedLocationLibrary,
                                                Boolean whetherFix,
                                                Map<String, Object> objectMap,
                                                List<String> electricalSet,
                                                Map<String, Double> elecBusinessPrice) {
        List<Map<String, String>> appPositions = (List<Map<String, String>>) projectInfo.get("用电器信息");
        List<Map<String, String>> pointList = (List<Map<String, String>>) projectInfo.get("所有端点信息");


        String start = twoMap.get("回路起点用电器");
        String end = twoMap.get("回路终点用电器");
        String materials = twoMap.get("回路导线选型");
        String circuitId = twoMap.get("回路id");

//        根据导线选型选择对应的信息
        Map<String, String> materialsMsg = elecFixedLocationLibrary.get(materials);
        FindAllPath findAllPath = new FindAllPath();
        String startName = "";
        String endName = "";
        //查找用电器位置点
        if (whetherFix) {
            startName = findNode(start, appPositions);
            endName = findNode(end, appPositions);
        } else {
            if (electricalSet.contains(start) && objectMap.containsKey(start)) {
                if (twoMap.get("回路起点用电器接口编号") != null) {
                    String startAppPort = twoMap.get("回路起点用电器接口编号").toString();
                    if (objectMap.containsKey(start)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(start);
                        startName = map.get(startAppPort);
                    } else {
                        startName = findNode(start, appPositions);
                    }
                    if (startName == null) {
                        startName = findNode(start, appPositions);
                    }

                } else {
                    if (objectMap.containsKey(start)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(start);
                        startName = map.get("null");
                    } else {
                        startName = findNode(start, appPositions);
                    }
                }
            } else {
                startName = findNode(start, appPositions);
            }
            if (electricalSet.contains(end) && objectMap.containsKey(end)) {
                if (twoMap.get("回路终点用电器接口编号") != null) {
                    String endAppPort = twoMap.get("回路终点用电器接口编号").toString();
                    if (objectMap.containsKey(end)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(end);
                        endName = map.get(endAppPort);
                    } else {
                        endName = findNode(end, appPositions);
                    }
                    if (endName == null) {
                        endName = findNode(end, appPositions);
                    }
                } else {
                    if (objectMap.containsKey(end)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(end);
                        endName = map.get("null");
                    } else {
                        endName = findNode(end, appPositions);
                    }
                }
            } else {
                endName = findNode(end, appPositions);
            }
        }

        List<Map<String, Object>> pathList = new ArrayList<>();
        FindShortestPath shortestPathSearch = new FindShortestPath();
        //如果回路起点或终点不在导线矩阵中，则返回null
        if (adjacencyMatrixGraph.getAllPoint().indexOf(startName) == -1 || adjacencyMatrixGraph.getAllPoint().indexOf(endName) == -1) {
            return null;
        }

        //边，用电器位置点名称（起点和终点），返回最短路径索引
        //回路绕线也可以直接调用这个方法，传入的值为所有分支打通情况下的值
        List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(startName), adjacencyMatrixGraph.getAllPoint().indexOf(endName));
//            获取回路的所有路径
//       成本计算
        if (shortestPath != null) {
            //寻找从起点到终点的不同路径，通过破坏已知最短路径得到
            List<List<Integer>> allPathBetweenTwoPoint = findAllPath.findAllPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(startName), adjacencyMatrixGraph.getAllPoint().indexOf(endName));

            DecimalFormat df = new DecimalFormat("0.00");
            for (List<Integer> list : allPathBetweenTwoPoint) {
                Map<String, Object> sinaglePath = new LinkedMap<>();
                CalculateCircuitInfo acceptLoopInfo = new CalculateCircuitInfo();
//           将路径中的数字转化为对应的名称
                List<String> listname = convertPathToNumbers(list, adjacencyMatrixGraph.getAllPoint());
//            计算该条路径成本
                //导线选型，路径点，项目信息，导线excel
                Map<String, Object> twoPointMsg = acceptLoopInfo.calculateCircuitInfo(materials, listname, projectInfo, elecFixedLocationLibrary);
//               计算两端连接器干湿
                String startParam = getWaterParam(listname.get(0), pointList);
                String endParam = getWaterParam(listname.get(listname.size() - 1), pointList);
                twoPointMsg.put("端子成本", Double.parseDouble(materialsMsg.get("导线打断成本（元/次）")));//端子成本   实际上指的是导线打断成本
//            回路两端湿区数量
                Integer wetNumber = 0;
                if ("w".toUpperCase().equals(startParam)) {
                    wetNumber++;
                }
                if ("w".toUpperCase().equals(endParam)) {
                    wetNumber++;
                }

                sinaglePath.put("起点用电器名称", start);
                sinaglePath.put("起点用电id", findidByAppName(start, appPositions));
                sinaglePath.put("起点位置名称", startName);
                sinaglePath.put("起点位置id", findIdByName(startName, pointList));
                sinaglePath.put("终点用电器名称", end);
                sinaglePath.put("终点用电id", findidByAppName(end, appPositions));
                sinaglePath.put("终点位置名称", endName);
                sinaglePath.put("终点位置id", findIdByName(endName, pointList));
                sinaglePath.put("回路属性", twoMap.get("回路属性"));
                sinaglePath.put("导线选型", twoMap.get("回路导线选型"));
                sinaglePath.put("回路编号", twoMap.get("回路编号"));
                sinaglePath.put("方案号", twoMap.get("方案号"));
                sinaglePath.put("所属系统", twoMap.get("所属系统"));
                sinaglePath.put("回路起点用电器接口编号", twoMap.get("回路起点用电器接口编号"));
                sinaglePath.put("回路终点用电器接口编号", twoMap.get("回路终点用电器接口编号"));
                sinaglePath.put("回路信号名", twoMap.get("回路信号名"));
                sinaglePath.put("湿区两端连接器成本补偿", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) * wetNumber)));
                sinaglePath.put("湿区两端防水塞成本补偿", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) * wetNumber)));
                sinaglePath.put("焊点名称", null);
                sinaglePath.put("回路id", circuitId);
                sinaglePath.put("焊点位置名称", null);
                sinaglePath.put("焊点位置id", null);
                sinaglePath.put("回路途径分支点", listname);
                //看用电器是否存在在商务清单中，如果存在，则相加
                if (keyExistsIgnoreCase(elecBusinessPrice, start) || keyExistsIgnoreCase(elecBusinessPrice, end)) {
                    if (keyExistsIgnoreCase(elecBusinessPrice, start)) {
                        twoPointMsg.put("回路导线成本", (Double) twoPointMsg.get("回路导线成本") + getValueIgnoreCase(elecBusinessPrice, start));
                    } else {
                        twoPointMsg.put("回路导线成本", (Double) twoPointMsg.get("回路导线成本") + getValueIgnoreCase(elecBusinessPrice, end));
                    }
                }
                sinaglePath.put("回路总成本", Double.parseDouble(df.format(Double.parseDouble(sinaglePath.get("湿区两端连接器成本补偿").toString()) + Double.parseDouble(sinaglePath.get("湿区两端防水塞成本补偿").toString())
                        + Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("回路打断成本").toString())
                        + Double.parseDouble(twoPointMsg.get("端子成本").toString()) + Double.parseDouble(twoPointMsg.get("回路导线成本").toString()))));

                sinaglePath.put("回路湿区成本加成", Double.parseDouble(df.format(Double.parseDouble(sinaglePath.get("湿区两端连接器成本补偿").toString()) + Double.parseDouble(sinaglePath.get("湿区两端防水塞成本补偿").toString())
                        + Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()))));
                sinaglePath.put("inline湿区连接器成本补偿", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()))));
                sinaglePath.put("inline湿区防水塞成本补偿", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()))));
                sinaglePath.put("回路打断成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路打断成本").toString()))));
                sinaglePath.put("回路两端端子成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("端子成本").toString()))));
                sinaglePath.put("回路导线成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路导线成本").toString()))));
                sinaglePath.put("回路重量", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路重量").toString()))));
                sinaglePath.put("回路长度", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路长度").toString()))));
                sinaglePath.put("回路打断分支id", twoPointMsg.get("回路分支打断清单"));
                sinaglePath.put("回路打断分支名称", twoPointMsg.get("回路分支打断清单名称"));
                sinaglePath.put("回路打断次数", ((List<String>) twoPointMsg.get("回路分支打断清单")).size());
                sinaglePath.put("回路所有分支id", twoPointMsg.get("所有分支"));
                sinaglePath.put("回路所有分支名称", twoPointMsg.get("所有分支名称"));
                sinaglePath.put("回路所有分支数量", ((List<String>) twoPointMsg.get("所有分支")).size());
                sinaglePath.put("回路理论直径", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("外径").toString()))));
                pathList.add(sinaglePath);
            }
        } else {
            return null;
        }

        //        对当前的路径取最优的一种情况
        Map<String, Object> bestMap = pathSelectBetweenPoint(pathList);
        //计算最优路径的端子成本、连接器塑壳成本、防水塞成本
        //回路打断分支id
        List<String> breakIdList = (List<String>) bestMap.get("回路打断分支id");
        //获取单次打断的端子成本,一端的成本
        Double oneTerminalPrice = Double.parseDouble(materialsMsg.get("端子成本（元/端）"));
        //打断后新增端子成本加上回路原本就有的两个端子(回路所有端子成本)
        Double wireTerminalPrice = oneTerminalPrice * 2 * (breakIdList.size() + 1);
        //连接器塑壳成本
        Double oneShellPrice = Double.parseDouble(materialsMsg.get("导线两端的连接器塑壳商务价（元/端）"));
        //回路所有塑壳成本
        Double shellPrice = oneShellPrice * 2 * (breakIdList.size() + 1);
        //湿区防水塞成本
        Double waterPrice = Double.parseDouble(bestMap.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(bestMap.get("湿区两端防水塞成本补偿").toString());
        //湿区连接器塑壳成本
        Double connectPrice = Double.parseDouble(bestMap.get("inline湿区连接器成本补偿").toString()) + Double.parseDouble(bestMap.get("湿区两端连接器成本补偿").toString());
        bestMap.put("端子成本",wireTerminalPrice);
        bestMap.put("连接器塑壳成本",shellPrice + connectPrice);
        bestMap.put("防水塞成本",waterPrice);
        return bestMap;
    }

    /**
     * @Description: 族群解析的方法
     * @input: name 族群名称
     * @inputExample: [FVCM-KL30]
     * @input: multiLoopinfoMap 族群信息
     * @input: adjacencyMatrixGraph 邻接矩阵
     * @input: elecFixedLocationLibrary excel表中的线径信息
     * @input: projectInfo 解析完成的txt的文件信息
     * @input: whetherFix 用电器是都固定 true：固定  false：不固定
     * @input: objectMap 不固定用电器每个接口对应的位置
     * @input: electricalSet 不固定用电器的集合
     * @input: elecBusinessPrice 读取导线选型的excel表的sheet2的信息
     * @Return: 当前族群中最优的一条信息
     * {中心点名称=空调箱中点, 到1用电器的信息={最优路径={起点用电器名称=[FVCM-KL30], 起点用电id=e3d0e010-3fbc-4af0-bab6-9ad8c7044a95, 起点位置名称=null, 起点位置id=null, 终点用电器名称=ETCM-FVCM, 终点用电id=b83a49d7-13ea-4633-9664-7bd32de3536a, 终点位置名称=前挡右出点, 终点位置id=b2593b81-b586-4f32-91a8-b01df6ac3ae1, 回路属性=null, 导线选型=FLRY-B 0.35, 回路编号=5353, 方案号=85342e86-dd12-498d-b6a4-a45a3bd96fce, 所属系统=系统Others, 回路起点用电器接口编号=, 回路终点用电器接口编号=, 回路信号名=FVCM-KL30, 湿区两端连接器成本补偿=0.0, 湿区两端防水塞成本补偿=0.0, 焊点名称=[FVCM-KL30], 回路id=2e215948-968f-4680-8cd6-a59521e89736, 焊点位置名称=空调箱中点, 焊点位置id=e3d0e010-3fbc-4af0-bab6-9ad8c7044a95, 回路途径分支点=[空调箱中点, 空调箱左点, 仪表线左中点, 仪表线右中点, 仪表右侧点, 仪表线右侧顶棚inline点, 顶棚右前inline点, 顶棚右前点, 前挡右出点], 回路总成本=8.793069999999998, 回路湿区成本加成=0.0, inline湿区连接器成本补偿=0.0, inline湿区防水塞成本补偿=0.0, 回路打断成本=0.0, 回路导线成本=8.593069999999999, 回路总重量=0.7513066666666667, 回路总长度=1.4087, 回路打断分支=[], 回路打断次数=0, 回路所有分支=[0fc9f2f4-0422-4429-b74f-0becb211b5da, 8e0bcb1b-760b-40d3-ae03-2d305acc7313, 6f3ecce5-c586-4b6f-a5af-51fb9ef7f0f5, f4171731-680e-40f7-8a91-75ac88f86514, 23bd3efd-7651-44d5-a01d-1860ca3a8af2, 9bb0d48f-3b62-44ed-bf10-6c52e9501e48, 8d5fa636-f386-4194-b559-8cfa944d6350, e55233dd-3a8d-4815-adf7-620e39687589], 回路所有分支数量=8, 回路直径=2.1}, 起点=空调箱中点, 终点=前挡右出点}, 到2用电器的信息={最优路径={起点用电器名称=[FVCM-KL30], 起点用电id=e3d0e010-3fbc-4af0-bab6-9ad8c7044a95, 起点位置名称=null, 起点位置id=null, 终点用电器名称=FVCM-A40D, 终点用电id=3b6ee76d-6a32-4714-9c87-0cbc5dde65b5, 终点位置名称=空调箱右点, 终点位置id=d6016448-b2a9-45a1-873b-22c7d6999972, 回路属性=null, 导线选型=FLRY-B 0.35, 回路编号=5354, 方案号=85342e86-dd12-498d-b6a4-a45a3bd96fce, 所属系统=系统Others, 回路起点用电器接口编号=, 回路终点用电器接口编号=, 回路信号名=FVCM-KL30, 湿区两端连接器成本补偿=0.0, 湿区两端防水塞成本补偿=0.0, 焊点名称=[FVCM-KL30], 回路id=661ebebb-4ce2-46a5-bfd6-1564a6f48534, 焊点位置名称=空调箱中点, 焊点位置id=e3d0e010-3fbc-4af0-bab6-9ad8c7044a95, 回路途径分支点=[空调箱中点, 空调箱右点], 回路总成本=0.2, 回路湿区成本加成=0.0, inline湿区连接器成本补偿=0.0, inline湿区防水塞成本补偿=0.0, 回路打断成本=0.0, 回路导线成本=0.0, 回路总重量=0.0, 回路总长度=0.0, 回路打断分支=[], 回路打断次数=0, 回路所有分支=[], 回路所有分支数量=1, 回路直径=2.1}, 起点=空调箱中点, 终点=空调箱右点}, 到3用电器的信息={最优路径={起点用电器名称=[FVCM-KL30], 起点用电id=e3d0e010-3fbc-4af0-bab6-9ad8c7044a95, 起点位置名称=null, 起点位置id=null, 终点用电器名称=UEC, 终点用电id=f82321cc-3ae6-4c0f-9ac9-04bad98e120b, 终点位置名称=前舱左纵梁中点, 终点位置id=a8e70607-a4a6-44cd-8708-7eca48590228, 回路属性=二级配电-KL.30, 导线选型=FLRY-B 0.35, 回路编号=5355, 方案号=85342e86-dd12-498d-b6a4-a45a3bd96fce, 所属系统=系统Others, 回路起点用电器接口编号=, 回路终点用电器接口编号=J2, 回路信号名=FVCM-KL30, 湿区两端连接器成本补偿=0.0, 湿区两端防水塞成本补偿=0.0, 焊点名称=[FVCM-KL30], 回路id=ead061cf-254f-4cc9-9bef-953e5c5b6049, 焊点位置名称=空调箱中点, 焊点位置id=e3d0e010-3fbc-4af0-bab6-9ad8c7044a95, 回路途径分支点=[空调箱中点, 空调箱左点, 仪表线左中点, 仪表左侧点, 仪表线左侧顶棚inline点, 顶棚左前inline点, 车身线左前inline点, 前舱线左后inline点, 前舱左纵梁后点, 前舱左纵梁后中点, 前舱左纵梁中点], 回路总成本=10.71388, 回路湿区成本加成=0.0, inline湿区连接器成本补偿=0.0, inline湿区防水塞成本补偿=0.0, 回路打断成本=0.2, 回路导线成本=10.31388, 回路总重量=0.9017599999999999, 回路总长度=1.6907999999999999, 回路打断分支=[d89d0217-8ef0-41f2-835c-49ca8f7e069c], 回路打断次数=1, 回路所有分支=[0fc9f2f4-0422-4429-b74f-0becb211b5da, 8e0bcb1b-760b-40d3-ae03-2d305acc7313, a57758e1-fd04-416c-b577-7aba48c11e0f, 4c57dd24-da2b-44f4-b63f-e9ad7ac90163, 893aea5f-74a4-4f28-a2c8-e54d688d5b41, 322031f6-af42-4829-9227-4eb50eadc1a5, d89d0217-8ef0-41f2-835c-49ca8f7e069c, 8a464b8f-d481-465a-a7ce-f320d548d83e, 4a1dc59b-e378-4bd9-a535-db19647faae7, 6ef1cf41-e4f4-404b-a93a-c3f015b42608], 回路所有分支数量=10, 回路直径=2.1}, 起点=空调箱中点, 终点=前舱左纵梁中点}}
     */
    public Map<String, Object> findGroupInfo(String name,
                                             List<Map<String, String>> multiLoopinfoMap,
                                             GenerateTopoMatrix adjacencyMatrixGraph,
                                             Map<String, Map<String, String>> elecFixedLocationLibrary,
                                             Map<String, Object> projectInfo,
                                             Boolean whetherFix,
                                             Map<String, Object> objectMap,
                                             List<String> electricalSet,
                                             Map<String, Double> elecBusinessPrice) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, String>> appPositions = (List<Map<String, String>>) projectInfo.get("用电器信息");
        List<Map<String, String>> pointList = (List<Map<String, String>>) projectInfo.get("所有端点信息");

        String materials = multiLoopinfoMap.get(0).get("回路导线选型");
        Map<String, String> materialsMsg = elecFixedLocationLibrary.get(materials);
        FindAllPath findAllPath = new FindAllPath();
//        是族群
//       首先找出中心点
//       找出当中用电电器名称
        List<String> appName = new ArrayList<>();
        for (Map<String, String> stringMap : multiLoopinfoMap) {
            if (!stringMap.get("回路起点用电器").startsWith("[")) {
                appName.add(stringMap.get("回路起点用电器"));
            } else {
                appName.add(stringMap.get("回路终点用电器"));
            }
        }

//        根据用电器名称  查找用电器所在位置
        Set<String> appNode = new HashSet<>();
        if (whetherFix) {
            for (String s : appName) {
                appNode.add(findNode(s, appPositions));
            }
        } else {
            for (Map<String, String> stringMap : multiLoopinfoMap) {
                if (!stringMap.get("回路起点用电器").startsWith("[")) {
                    String elecName = stringMap.get("回路起点用电器");
                    if (electricalSet.contains(elecName) && objectMap.containsKey(elecName)) {
                        if (stringMap.get("回路起点用电器接口编号") != null) {
                            String startAppPort = stringMap.get("回路起点用电器接口编号");
                            Map<String, String> map1 = (Map<String, String>) objectMap.get(elecName);
                            appNode.add(map1.get(startAppPort));
                        } else {
                            Map<String, String> map1 = (Map<String, String>) objectMap.get(elecName);
                            appNode.add(map1.get("null"));
                        }
                    } else {
                        appNode.add(findNode(elecName, appPositions));
                    }
                } else {
                    String elecName = stringMap.get("回路终点用电器");
                    if (electricalSet.contains(elecName) && objectMap.containsKey(elecName)) {
                        if (stringMap.get("回路终点用电器接口编号") != null) {
                            String endAppPort = stringMap.get("回路终点用电器接口编号");
                            Map<String, String> map1 = (Map<String, String>) objectMap.get(elecName);
                            appNode.add(map1.get(endAppPort));
                        } else {
                            Map<String, String> map1 = (Map<String, String>) objectMap.get(elecName);
                            if (map1 != null) {
                                appNode.add(map1.get("null"));
                            } else {
                                appNode.add(findNode(elecName, appPositions));
                            }

                        }
                    } else {
                        appNode.add(findNode(elecName, appPositions));
                    }
                }
            }
        }

        DecimalFormat df = new DecimalFormat("0.00");
        //        获取中心点，这里会考虑所有用电器位置
        //起点与终点对应列表，邻接矩阵所有点的信息，
        List<String> centerPoint = findCenterPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint(), appNode.stream().collect(Collectors.toList()));
        if (centerPoint == null) {
            return null;
        }
        //            单个中心点到到各个点信息
        Integer centerPointNum = 1;
        Map<String, Object> groupMap = new LinkedMap<>();
//        分情况讨论   1 存在中心点的情况  2存在中心点的情况
        //对每个中心点，计算到族群中所有用电器的路径
        if (centerPoint.size() > 0) {
            for (String integer : centerPoint) {
//          每一个中心点的map
                Map<String, Object> sonMap = new LinkedMap<>();
                sonMap.put("中心点名称", integer);
                Integer pathNumber = 1;
                for (Map<String, String> stringMap : multiLoopinfoMap) {
//                 起点   也是中心点
                    String start = integer;
//                  该条路径用电器所在位置
                    String end = "";
                    String port = null;
                    if (!stringMap.get("回路起点用电器").startsWith("[")) {
                        end = stringMap.get("回路起点用电器");
                        start = stringMap.get("回路终点用电器");
                        port = stringMap.get("回路起点用电器接口编号");
                    } else {
                        end = stringMap.get("回路终点用电器");
                        start = stringMap.get("回路起点用电器");
                        port = stringMap.get("回路终点用电器接口编号");
                    }
                    String endName = null;
                    if (whetherFix) {
                        endName = findNode(end, appPositions);
                    } else {
                        if (electricalSet.contains(end)) {
                            Map<String, String> map1 = (Map<String, String>) objectMap.get(end);
                            if (map1 != null) {
                                if (port == null) {
                                    endName = map1.get("null");
                                } else if (!map1.containsKey(port)) {
                                    endName = findNode(end, appPositions);
                                } else {
                                    endName = map1.get(port);
                                }
                            } else {
                                endName = findNode(end, appPositions);
                            }
                        } else {
                            endName = findNode(end, appPositions);
                        }
                    }


                    Map<String, Object> detailMap = new HashMap<>();
                    detailMap.put("起点", integer);
                    detailMap.put("终点", endName);
//                  起点到终点的所有路径
                    List<Map<String, Object>> sonPathList = new ArrayList<>();

                    //找到两点间的所有路径,对每条路径进行详细的成本计算
                    List<List<Integer>> allPathBetweenTwoPoint = findAllPath.findAllPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(integer), adjacencyMatrixGraph.getAllPoint().indexOf(endName));
                    String startElectricalId = findIdByName(integer, pointList);
                    for (List<Integer> list : allPathBetweenTwoPoint) {
                        Map<String, Object> sinaglePath = new LinkedMap<>();
                        CalculateCircuitInfo acceptLoopInfo = new CalculateCircuitInfo();
                        List<String> listname = convertPathToNumbers(list, adjacencyMatrixGraph.getAllPoint());
                        Map<String, Object> twoPointMsg = acceptLoopInfo.calculateCircuitInfo(materials, listname, projectInfo, elecFixedLocationLibrary);
                        twoPointMsg.put("端子成本", Double.parseDouble(materialsMsg.get("导线打断成本（元/次）")) / 2);//端子成本   实际上指的是导线打断成本
                        String startParam = getWaterParam(endName, pointList);
                        Integer wetNumber = 0;
                        if ("w".toUpperCase().equals(startParam)) {
                            wetNumber++;
                        }
                        sinaglePath.put("起点用电器名称", stringMap.get("回路起点用电器"));
                        sinaglePath.put("起点用电器id", findidByAppName(stringMap.get("回路起点用电器"), appPositions));
                        sinaglePath.put("起点位置名称", stringMap.get("回路起点用电器").startsWith("[") ? null : endName);
                        sinaglePath.put("起点位置id", stringMap.get("回路起点用电器").startsWith("[") ? null : findIdByName(endName, pointList));
                        sinaglePath.put("终点用电器名称", stringMap.get("回路终点用电器"));
                        sinaglePath.put("终点用电器id", findidByAppName(stringMap.get("回路终点用电器"), appPositions));
                        sinaglePath.put("终点位置名称", stringMap.get("回路终点用电器").startsWith("[") ? null : endName);
                        sinaglePath.put("终点位置id", stringMap.get("回路起点用电器").startsWith("[") ? null : findIdByName(endName, pointList));
                        sinaglePath.put("回路属性", stringMap.get("回路属性"));
                        sinaglePath.put("导线选型", stringMap.get("回路导线选型"));
                        sinaglePath.put("回路编号", stringMap.get("回路编号"));
                        sinaglePath.put("方案号", stringMap.get("方案号"));
                        sinaglePath.put("所属系统", stringMap.get("所属系统"));
                        sinaglePath.put("回路起点用电器接口编号", stringMap.get("回路起点用电器接口编号"));
                        sinaglePath.put("回路终点用电器接口编号", stringMap.get("回路终点用电器接口编号"));
                        sinaglePath.put("回路信号名", stringMap.get("回路信号名"));
                        sinaglePath.put("湿区两端连接器成本补偿", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) * wetNumber)));
                        sinaglePath.put("湿区两端防水塞成本补偿", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) * wetNumber)));
                        sinaglePath.put("焊点名称", name);
                        sinaglePath.put("回路id", stringMap.get("回路id"));
                        sinaglePath.put("焊点位置名称", integer);
                        sinaglePath.put("焊点位置id", startElectricalId);
                        sinaglePath.put("回路途径分支点", listname);
                        if (keyExistsIgnoreCase(elecBusinessPrice, start) || keyExistsIgnoreCase(elecBusinessPrice, end)) {
                            if (keyExistsIgnoreCase(elecBusinessPrice, start)) {
                                twoPointMsg.put("回路导线成本", (Double) twoPointMsg.get("回路导线成本") + getValueIgnoreCase(elecBusinessPrice, start));
                            } else {
                                twoPointMsg.put("回路导线成本", (Double) twoPointMsg.get("回路导线成本") + getValueIgnoreCase(elecBusinessPrice, end));
                            }
                        }
                        sinaglePath.put("回路总成本", Double.parseDouble(df.format(Double.parseDouble(sinaglePath.get("湿区两端连接器成本补偿").toString()) + Double.parseDouble(sinaglePath.get("湿区两端防水塞成本补偿").toString())
                                + Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("回路打断成本").toString())
                                + Double.parseDouble(twoPointMsg.get("端子成本").toString()) + Double.parseDouble(twoPointMsg.get("回路导线成本").toString()))));
                        sinaglePath.put("回路湿区成本加成", Double.parseDouble(df.format(Double.parseDouble(sinaglePath.get("湿区两端连接器成本补偿").toString()) + Double.parseDouble(sinaglePath.get("湿区两端防水塞成本补偿").toString())
                                + (Double) twoPointMsg.get("inline湿区防水塞成本补偿") + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()))));
                        sinaglePath.put("inline湿区连接器成本补偿", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()))));
                        sinaglePath.put("inline湿区防水塞成本补偿", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()))));
                        sinaglePath.put("回路打断成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路打断成本").toString()))));
                        sinaglePath.put("回路两端端子成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("端子成本").toString()))));
                        sinaglePath.put("回路导线成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路导线成本").toString()))));
                        sinaglePath.put("回路重量", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路重量").toString()))));
                        sinaglePath.put("回路长度", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路长度").toString()))));
                        sinaglePath.put("回路打断分支id", twoPointMsg.get("回路分支打断清单"));
                        sinaglePath.put("回路打断分支名称", twoPointMsg.get("回路分支打断清单名称"));
                        sinaglePath.put("回路打断次数", ((List<String>) twoPointMsg.get("回路分支打断清单")).size());
                        sinaglePath.put("回路所有分支id", twoPointMsg.get("所有分支"));
                        sinaglePath.put("回路所有分支名称", twoPointMsg.get("所有分支名称"));
                        sinaglePath.put("回路所有分支数量", ((List<String>) twoPointMsg.get("所有分支")).size());
                        sinaglePath.put("回路理论直径", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("外径").toString()))));
                        sonPathList.add(sinaglePath);
                    }
//                 所有路径中最优的路径,根据成本，重量，长度计算综合得分，选择得分最优的路径
                    Map<String, Object> bestPath = pathSelectBetweenPoint(sonPathList);
                    detailMap.put("最优路径", bestPath);
                    //回路打断分支id
                    List<String> pathBreakList = (List<String>) bestPath.get("回路打断分支id");
                    //获取单次打断端子成本
                    Double oneTerminalPrice = Double.parseDouble(materialsMsg.get("端子成本（元/端）"));
                    //回路所有端子成本
                    Double wireTerminalPrice = oneTerminalPrice * 2 *pathBreakList.size() + oneTerminalPrice;
                    //连接器塑壳成本
                    Double oneSheLlPrice = Double.parseDouble(materialsMsg.get("导线两端的连接器塑壳商务价（元/端）"));
                    //回路所有塑壳成本(干区)
                    Double shellPrice = oneSheLlPrice * 2 *pathBreakList.size() + oneSheLlPrice;
                    //湿区防水塞成本
                    Double waterPrice = Double.parseDouble(bestPath.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(bestPath.get("湿区两端防水塞成本补偿").toString());
                    //湿区连接器塑壳成本
                    Double connectPrice = Double.parseDouble(bestPath.get("inline湿区连接器成本补偿").toString()) + Double.parseDouble(bestPath.get("湿区两端连接器成本补偿").toString());
                    bestPath.put("端子成本",wireTerminalPrice);
                    bestPath.put("连接器塑壳成本",shellPrice + connectPrice);
                    bestPath.put("防水塞成本",waterPrice);
                    sonMap.put("到" + pathNumber + "用电器的信息", detailMap);
                    pathNumber++;
                }
                groupMap.put("第" + centerPointNum + "中心点信息", sonMap);
                centerPointNum++;
            }
        } else {
            String integer = findNode(appName.get(0), appPositions);
            Map<String, Object> sonMap = new LinkedMap<>();
            groupMap.put("中心点名称", integer);
            Integer pathNumber = 1;
            for (Map<String, String> stringMap : multiLoopinfoMap) {
                String start = integer;
//                end默认就是连接的用电器不是焊点
                String end = "";
                String port = null;
                if (!stringMap.get("回路起点用电器").startsWith("[")) {
                    end = stringMap.get("回路起点用电器");
                    start = stringMap.get("回路终点用电器");
                    port = stringMap.get("回路起点用电器接口编号");
                } else {
                    end = stringMap.get("回路终点用电器");
                    start = stringMap.get("回路起点用电器");
                    port = stringMap.get("回路终点用电器接口编号");
                }

                String endName = null;
                if (whetherFix) {
                    endName = findNode(end, appPositions);
                } else {
                    if (electricalSet.contains(end)) {
                        Map<String, String> map1 = (Map<String, String>) objectMap.get(end);
                        if (port == null) {
                            endName = map1.get("null");
                        } else if (!map1.containsKey(port)) {
                            endName = findNode(end, appPositions);
                        } else {
                            endName = map1.get(port);
                        }
                    } else {
                        endName = findNode(end, appPositions);
                    }
                }


                Map<String, Object> detailMap = new HashMap<>();
                detailMap.put("起点", integer);
                detailMap.put("终点", integer);
                List<Map<String, Object>> sonPathList = new ArrayList<>();
                Map<String, Object> sinaglePath = new LinkedMap<>();
                CalculateCircuitInfo acceptLoopInfo = new CalculateCircuitInfo();
                List<String> list = new ArrayList<>();
                list.add("");
                Map<String, Object> twoPointMsg = acceptLoopInfo.calculateCircuitInfo(materials, list, projectInfo, elecFixedLocationLibrary);
                twoPointMsg.put("端子成本", Double.parseDouble(materialsMsg.get("导线打断成本（元/次）")) / 2);//端子成本   实际上指的是导线打断成本
                String startParam = getWaterParam(endName, pointList);
                Integer wetNumber = 0;
                if ("w".toUpperCase().equals(startParam)) {
                    wetNumber++;
                }
                sinaglePath.put("起点用电器名称", stringMap.get("回路起点用电器"));
                sinaglePath.put("起点用电器id", findidByAppName(stringMap.get("回路起点用电器"), appPositions));
                sinaglePath.put("起点位置名称", stringMap.get("回路起点用电器").startsWith("[") ? null : endName);
                sinaglePath.put("起点位置id", stringMap.get("回路起点用电器").startsWith("[") ? null : findIdByName(endName, pointList));
                sinaglePath.put("终点用电器名称", stringMap.get("回路终点用电器"));
                sinaglePath.put("终点用电器id", findidByAppName(stringMap.get("回路终点用电器"), appPositions));
                sinaglePath.put("终点位置名称", stringMap.get("回路终点用电器").startsWith("[") ? null : endName);
                sinaglePath.put("终点位置id", stringMap.get("回路终点用电器").startsWith("[") ? null : findIdByName(endName, pointList));
                sinaglePath.put("回路属性", stringMap.get("回路属性"));
                sinaglePath.put("导线选型", stringMap.get("回路导线选型"));
                sinaglePath.put("回路编号", stringMap.get("回路编号"));
                sinaglePath.put("方案号", stringMap.get("方案号"));
                sinaglePath.put("所属系统", stringMap.get("所属系统"));
                sinaglePath.put("回路起点用电器接口编号", stringMap.get("回路起点用电器接口编号"));
                sinaglePath.put("回路终点用电器接口编号", stringMap.get("回路终点用电器接口编号"));
                sinaglePath.put("回路信号名", stringMap.get("回路信号名"));
                sinaglePath.put("湿区两端连接器成本补偿", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) * wetNumber)));
                sinaglePath.put("湿区两端防水塞成本补偿", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) * wetNumber)));
                sinaglePath.put("焊点名称", name);
                sinaglePath.put("回路id", stringMap.get("回路id"));
                sinaglePath.put("焊点位置名称", null);
                sinaglePath.put("焊点位置id", null);
                sinaglePath.put("回路途径分支点", null);
                if (keyExistsIgnoreCase(elecBusinessPrice, start) || keyExistsIgnoreCase(elecBusinessPrice, end)) {
                    if (keyExistsIgnoreCase(elecBusinessPrice, start)) {
                        twoPointMsg.put("回路导线成本", (Double) twoPointMsg.get("回路导线成本") + getValueIgnoreCase(elecBusinessPrice, start));
                    } else {
                        twoPointMsg.put("回路导线成本", (Double) twoPointMsg.get("回路导线成本") + getValueIgnoreCase(elecBusinessPrice, end));
                    }
                }
                sinaglePath.put("回路总成本", Double.parseDouble(df.format(Double.parseDouble(sinaglePath.get("湿区两端连接器成本补偿").toString()) + Double.parseDouble(sinaglePath.get("湿区两端防水塞成本补偿").toString())
                        + Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()) + Double.parseDouble(twoPointMsg.get("回路打断成本").toString())
                        + Double.parseDouble(twoPointMsg.get("端子成本").toString()) + Double.parseDouble(twoPointMsg.get("回路导线成本").toString()))));
                sinaglePath.put("回路湿区成本加成", Double.parseDouble(df.format(Double.parseDouble(sinaglePath.get("湿区两端连接器成本补偿").toString()) + Double.parseDouble(sinaglePath.get("湿区两端防水塞成本补偿").toString())
                        + (Double) twoPointMsg.get("inline湿区防水塞成本补偿") + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()))));
                sinaglePath.put("inline湿区连接器成本补偿", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString()))));
                sinaglePath.put("inline湿区防水塞成本补偿", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString()))));
                sinaglePath.put("回路打断成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路打断成本").toString()))));
                sinaglePath.put("回路两端端子成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("端子成本").toString()))));
                sinaglePath.put("回路导线成本", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路导线成本").toString()))));
                sinaglePath.put("回路重量", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路重量").toString()))));
                sinaglePath.put("回路长度", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("回路长度").toString()))));
                sinaglePath.put("回路打断分支id", twoPointMsg.get("回路分支打断清单"));
                sinaglePath.put("回路打断分支名称", twoPointMsg.get("回路分支打断清单名称"));
                sinaglePath.put("回路打断次数", ((List<String>) twoPointMsg.get("回路分支打断清单")).size());
                sinaglePath.put("回路所有分支id", twoPointMsg.get("所有分支"));
                sinaglePath.put("回路所有分支名称", twoPointMsg.get("所有分支名称"));
                sinaglePath.put("回路所有分支数量", ((List<String>) twoPointMsg.get("所有分支")).size());
                sinaglePath.put("回路理论直径", Double.parseDouble(df.format(Double.parseDouble(twoPointMsg.get("外径").toString()))));
                sonPathList.add(sinaglePath);
                Map<String, Object> objectMap1 = pathSelectBetweenPoint(sonPathList);
                //回路打断分支id
                List<String> pathBreakList = (List<String>) objectMap1.get("回路打断分支id");
                //单次打断端子成本
                Double oneTerminalPrice = Double.parseDouble(materialsMsg.get("端子成本（元/端）"));
                //回路所有端子成本
                Double wireTerminalPrice = oneTerminalPrice * 2 * pathBreakList.size() + oneTerminalPrice;
                //连接器塑壳成本
                Double oneSheLLPrice = Double.parseDouble(materialsMsg.get("导线两端的连接器塑壳商务价（元/端）"));
                //回路所有塑壳成本(干区)
                Double oneSheLLPriceDry = oneSheLLPrice * 2 * pathBreakList.size() + oneSheLLPrice;
                //湿区防水塞成本
                Double waterPrice = Double.parseDouble(objectMap1.get("inline湿区防水塞成本补偿").toString()) + Double.parseDouble(objectMap1.get("湿区两端防水塞成本补偿").toString());
                //湿区连接器塑壳成本
                Double connectPrice = Double.parseDouble(objectMap1.get("inline湿区连接器成本补偿").toString()) + Double.parseDouble(objectMap1.get("湿区两端连接器成本补偿").toString());
                objectMap1.put("端子成本",wireTerminalPrice);
                objectMap1.put("连接器塑壳成本",oneSheLLPriceDry + connectPrice);
                objectMap1.put("防水塞成本",waterPrice);
                detailMap.put("最优路径", objectMap1);
                groupMap.put("到" + pathNumber + "用电器的信息", detailMap);
                pathNumber++;
            }
            return groupMap;
        }
//      返回中心点最优的那一条信息
        return splicePositionSelect(groupMap);

    }


    /**
     * @Description 判断所在点的干湿
     * @input name 路径点名称
     * @inputExample 前围板外中点
     * @input maps 所有端点信息
     * @Return 端点的干湿状态 W
     */
    public String getWaterParam(String name, List<Map<String, String>> maps) {
        for (Map<String, String> map : maps) {
            if (name.equalsIgnoreCase(map.get("端点名称"))) {
                return map.get("端点干湿");
            }
        }
        return null;
    }


    /**
     * @Description 获取最优的一条路径(再次遍历所有方案 ， 根据与最小成本方案的成本和重量差异计算得分 ， 最小成本方案得分为 - 4.0 ( 得分 = ( 当前重量 - 最小方案的重量) / (当前成本-最小方案的成))
     * @input maps:所有路径信息
     * @Return 最优的一条路径
     */
    public Map<String, Object> pathSelectBetweenPoint(List<Map<String, Object>> maps) {
        Double minCost = Double.parseDouble(maps.get(0).get("回路总成本").toString());
        Double maxCost = Double.parseDouble(maps.get(0).get("回路总成本").toString());
        Double minWeight = Double.parseDouble(maps.get(0).get("回路重量").toString());
        Double maxWeight = Double.parseDouble(maps.get(0).get("回路重量").toString());
        Double minLength = Double.parseDouble(maps.get(0).get("回路长度").toString());
        Double maxLength = Double.parseDouble(maps.get(0).get("回路长度").toString());
//        首先最大最小值
        for (Map<String, Object> map : maps) {
            if (minCost > Double.parseDouble(map.get("回路总成本").toString())) {
                minCost = Double.parseDouble(map.get("回路总成本").toString());
            }
            if (maxCost < Double.parseDouble(map.get("回路总成本").toString())) {
                maxCost = Double.parseDouble(map.get("回路总成本").toString());
            }
            if (minWeight > Double.parseDouble(map.get("回路重量").toString())) {
                minWeight = Double.parseDouble(map.get("回路重量").toString());
            }
            if (maxWeight < Double.parseDouble(map.get("回路重量").toString())) {
                maxWeight = Double.parseDouble(map.get("回路重量").toString());
            }

            if (minLength > Double.parseDouble(map.get("回路长度").toString())) {
                minLength = Double.parseDouble(map.get("回路长度").toString());
            }
            if (maxLength < Double.parseDouble(map.get("回路长度").toString())) {
                maxLength = Double.parseDouble(map.get("回路长度").toString());
            }
        }
        Map<String, Object> map = new HashMap<>();
        for (Map<String, Object> objectMap : maps) {
            if (map.isEmpty()) {
                map = objectMap;
            } else {
                Double allCost1 = Double.parseDouble(map.get("回路总成本").toString());
                Double weight1 = Double.parseDouble(map.get("回路重量").toString());
                Double length1 = Double.parseDouble(map.get("回路长度").toString());


                Double allCost2 = Double.parseDouble(objectMap.get("回路总成本").toString());
                Double weight2 = Double.parseDouble(objectMap.get("回路重量").toString());
                Double length2 = Double.parseDouble(objectMap.get("回路长度").toString());
                if ((allCost2 - minCost) / ((maxCost - minCost) + 0.0001) * 0.98 +
                        (weight2 - minWeight) / ((maxWeight - minWeight) + 0.0001) * 0.01 +
                        (length2 - minLength) / ((maxLength - minLength) + 0.0001) * 0.01 <
                        (allCost1 - minCost) / ((maxCost - minCost) + 0.0001) * 0.98 +
                                (weight1 - minWeight) / ((maxWeight - minWeight) + 0.0001) * 0.01 +
                                (length1 - minLength) / ((maxLength - minLength) + 0.0001) * 0.01) {
                    map = objectMap;
                }
            }
        }
        return map;
    }

    /**
     * @Description: 根据用电器名称获取对应的位置点名称
     * @input: appName  用电器名称
     * @input: appPositions  用电器位置信息
     * @Return: 返回接收到用电器名称对应的位置
     */
    public String findNode(String appName, List<Map<String, String>> appPositions) {
        for (Map<String, String> appPosition : appPositions) {
            if (appPosition.get("用电器名称").equalsIgnoreCase(appName)) {
                if (appPosition.get("用户更改后用电器位置名称") != null) {
                    return appPosition.get("用户更改后用电器位置名称");
                } else if (appPosition.get("用户更改后用电器位置名称") == null && appPosition.get("用电器固化位置点名称") != null) {
                    return appPosition.get("用电器固化位置点名称");
                } else if (appPosition.get("用户更改后用电器位置名称") == null && appPosition.get("用电器固化位置点名称") == null) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * @Description: 根据用电器名称获取对应的用电器id
     * @input: appName  用电器名称
     * @input: appPositions  用电器位置信息
     * @Return: 返回接收到用电器名称对应的id
     */
    public String findidByAppName(String appName, List<Map<String, String>> appPositions) {
        for (Map<String, String> appPosition : appPositions) {
            if (appPosition.get("用电器名称").equalsIgnoreCase(appName)) {
                return appPosition.get("用电器id");
            }
        }
        return null;
    }


    /**
     * @Description 根据位置点名称查找位置点id
     * @input appName 位置点名称
     * @inputExample 前围板外中点
     * @input pointList  txt解析完成后当中所有端点信息
     * @inputExample 前围板外中点
     * @Return 返回位置点名称的id f753b6fe-39bd-4602-8cb3-b663c79af003
     */
    public String findIdByName(String appName, List<Map<String, String>> pointList) {
        String node = "";
        for (Map<String, String> stringMap : pointList) {
            if (stringMap.get("端点名称").equalsIgnoreCase(appName)) {
                node = stringMap.get("端点id编号");
                return node;
            }
        }
        return node;
    }

    /**
     * @Description 找出最优的一条中心点（首先找出每个中心点中的 回路总成本、回路总量、回路长度的最大值和最小值 然后按照 （总成本-总成本最小值）/（总成本最大值-总成本最大值）+（回路长度-回路长度最小值）/（回路长度最大值-回路长度最大值）+（回路重量-回路重量最小值）/（回路重量最大值-回路重量最大值） 其值最小的一个中心点 ）
     * @input maps:所有中心点中的信息
     * @Return 最优的一条中心点
     */
    public Map<String, Object> splicePositionSelect(Map<String, Object> maps) {
        Map<String, Object> map = new LinkedMap<>();
        Double minCost = 0.0;
        Double maxCost = 0.0;
        Double minWeight = 0.0;
        Double maxWeight = 0.0;
        Double minLength = 0.0;
        Double maxLength = 0.0;
        int size = maps.size();
        Map<String, Object> objectMap = (Map<String, Object>) maps.get("第1中心点信息");
        int electricalSize = objectMap.size();
        for (int i = 1; i < electricalSize; i++) {
            Map<String, Object> electricalMap1 = (Map<String, Object>) objectMap.get("到" + i + "用电器的信息");
            Map<String, Object> pathMap = (Map<String, Object>) electricalMap1.get("最优路径");
            minCost = minCost + Double.parseDouble(pathMap.get("回路总成本").toString());
            maxCost = maxCost + Double.parseDouble(pathMap.get("回路总成本").toString());
            minWeight = minWeight + Double.parseDouble(pathMap.get("回路重量").toString());
            maxWeight = maxWeight + Double.parseDouble(pathMap.get("回路重量").toString());
            minLength = minLength + Double.parseDouble(pathMap.get("回路长度").toString());
            maxLength = maxLength + Double.parseDouble(pathMap.get("回路长度").toString());
        }
//        找出最大值和最小值
        for (int i = 2; i <= size; i++) {
            objectMap = (Map<String, Object>) maps.get("第" + i + "中心点信息");
            electricalSize = objectMap.size();
            double cost = 0.0;
            double weight = 0.0;
            double length = 0.0;
            for (int j = 1; j < electricalSize; j++) {
                Map<String, Object> electricalMap2 = (Map<String, Object>) objectMap.get("到" + j + "用电器的信息");
                Map<String, Object> pathMap = (Map<String, Object>) electricalMap2.get("最优路径");
                cost = cost + Double.parseDouble(pathMap.get("回路总成本").toString());
                weight = weight + Double.parseDouble(pathMap.get("回路重量").toString());
                length = length + Double.parseDouble(pathMap.get("回路长度").toString());
            }
            if (minCost > cost) {
                minCost = cost;
            }
            if (maxCost < cost) {
                maxCost = cost;
            }
            if (minWeight > weight) {
                minWeight = weight;
            }
            if (maxWeight < weight) {
                maxWeight = weight;
            }

            if (minLength > length) {
                minLength = length;
            }
            if (maxLength < length) {
                maxLength = length;
            }
        }


//       循环找出最优的中心点
        for (int i = 1; i <= size; i++) {
            if (map.isEmpty()) {
                map = (Map<String, Object>) maps.get("第" + i + "中心点信息");
            } else {
                int mapsize = map.size();
                double cost1 = 0.0;
                double weight1 = 0.0;
                double length1 = 0.0;

                for (int j = 1; j < mapsize; j++) {
                    Map<String, Object> electricalMap1 = (Map<String, Object>) map.get("到" + j + "用电器的信息");
                    Map<String, Object> pathMap = (Map<String, Object>) electricalMap1.get("最优路径");
                    cost1 = cost1 + Double.parseDouble(pathMap.get("回路总成本").toString());
                    weight1 = weight1 + Double.parseDouble(pathMap.get("回路重量").toString());
                    length1 = length1 + Double.parseDouble(pathMap.get("回路长度").toString());
                }

                Map<String, Object> contrastMap = (Map<String, Object>) maps.get("第" + i + "中心点信息");
                int mapsize2 = map.size();
                double cost2 = 0.0;
                double weight2 = 0.0;
                double length2 = 0.0;
                for (int j = 1; j < mapsize2; j++) {
                    Map<String, Object> electricalMap2 = (Map<String, Object>) contrastMap.get("到" + j + "用电器的信息");
                    Map<String, Object> pathMap = (Map<String, Object>) electricalMap2.get("最优路径");
                    cost2 = cost2 + Double.parseDouble(pathMap.get("回路总成本").toString());
                    weight2 = weight2 + Double.parseDouble(pathMap.get("回路重量").toString());
                    length2 = length2 + Double.parseDouble(pathMap.get("回路长度").toString());
                }
//              map的积分
                Double score1 = (cost1 - minCost) / ((maxCost - minCost) + 0.0001) * 0.98 + (weight1 - minWeight) / ((maxWeight - minWeight) + 0.0001) * 0.01 + (length1 - minLength) / ((maxLength - minLength) + 0.0001) * 0.01;
//               对比的积分
                Double score2 = (cost2 - minCost) / ((maxCost - minCost) + 0.0001) * 0.98 + (weight2 - minWeight) / ((maxWeight - minWeight) + 0.0001) * 0.01 + (length2 - minLength) / ((maxLength - minLength) + 0.0001) * 0.01;
                if (score2 < score1) {
                    map = contrastMap;
                }

            }

        }
        return map;
    }

    /**
     * @Description 寻找中心点的位置
     * @input adj 邻接矩阵
     * @input allPoint  GenerateTopoMatrix构成的allpoint所有的节点
     * @input terminal 用电器节点
     * @inputExample {空调箱右点，前挡右出点}
     * @Return 返回中心点的名称   [仪表线左中点, 前顶横梁左中点, 前舱右纵梁中点, 前舱中部右点, 空调箱中点, 仪表线左侧inline点, 仪表线左侧顶棚inline点, 仪表线右侧inline点, 前舱线左后inline点, 前挡右出点, 前舱中后部左点, 前舱左纵梁后点, 车身线右前inline点, 仪表右侧点, 前舱中后部中点, 前舱中后部右点, 空调箱左点, 前舱左纵梁后中点, 前围板内右点, 前顶横梁右中点, 顶棚右前点, 前围板内中点, 仪表线右中点, 顶棚右前inline点, 仪表线右侧顶棚inline点, 前顶横梁左点, 空调箱右点, 前舱右纵梁后点, 车身线左前inline点, 顶棚左前点, 前舱线右后inline点]
     */
    public List<String> findCenterPoint(List<List<Integer>> adj, List<String> allPoint, List<String> terminal) {
        Set<Integer> set = new HashSet<>();
        FindAllPath findAllPath = new FindAllPath();
        FindShortestPath findShortestPath = new FindShortestPath();
        for (int i = 0; i < terminal.size(); i++) {
            for (int j = i + 1; j < terminal.size(); j++) {
                int start = allPoint.indexOf(terminal.get(i));
                int endpoint = allPoint.indexOf(terminal.get(j));
                if (start == -1 || endpoint == -1) {
                    return null;
                }
                List<Integer> shortestPathBetweenTwoPoint = findShortestPath.findShortestPathBetweenTwoPoint(adj, start, endpoint);
                for (Integer integers : shortestPathBetweenTwoPoint) {
                    set.add(integers);
                }
            }
        }
        return convertPathToNumbers(set.stream().collect(Collectors.toList()), allPoint);
    }

    /**
     * @Description 将路径数字转为点
     * @input numberPath 数字路径
     * @inputExample [23, 17, 135]
     * @input adjacencyMatrixGraph中的allPoint
     * @Return 返回数字对应的点 [仪表线左中点, 前顶横梁左中点, 前舱右纵梁中点]
     */
    public List<String> convertPathToNumbers(List<Integer> numberPath, List<String> allPoint) {
        List<String> points = new ArrayList<>();
        for (Integer point : numberPath) {
            points.add(allPoint.get(point));
        }
        return points;
    }

    /**
     * @Description 根据传入的值返回对应的颜色
     * @input number 湿区补偿成本
     * @Return 返回传入值的对应的颜色
     */
    public static String getCostColor(double number) {
        if (number == 0) {
            return "rgb(248,246,231)";
        } else if (number >= 0 && number <= 5) {
            return "rgb(75,0,130)";
        } else if (number >= 5 && number <= 10) {
            return "rgb(131,100,246)";
        } else if (number >= 10 && number <= 15) {
            return "rgb(0,0,204)";
        } else if (number >= 15 && number <= 20) {
            return "rgb(0,102,255)";
        } else if (number >= 20 && number <= 25) {
            return "rgb(0,153,255)";
        } else if (number >= 25 && number <= 30) {
            return "rgb(0,255,255)";
        } else if (number >= 30 && number <= 35) {
            return "rgb(0,255,102)";
        } else if (number >= 35 && number <= 40) {
            return "rgb(153,255,0)";
        } else if (number >= 40 && number <= 45) {
            return "rgb(204,255,0)";
        } else if (number >= 45 && number <= 50) {
            return "rgb(255,255,0)";
        } else if (number >= 50 && number <= 55) {
            return "rgb(255,204,0)";
        } else if (number >= 55 && number <= 60) {
            return "rgb(255,153,0)";
        } else if (number >= 60 && number <= 65) {
            return "rgb(255,102,0)";
        } else if (number >= 65 && number <= 70) {
            return "rgb(255,105,180)";
        } else if (number >= 70 && number <= 75) {
            return "rgb(244,38,241)";
        } else if (number >= 75 && number <= 80) {
            return "rgb(255,0,0)";
        } else if (number >= 80 && number <= 85) {
            return "rgb(153,0,0)";
        } else if (number >= 85 && number <= 90) {
            return "rgb(192,192,203)";
        } else if (number >= 90 && number <= 95) {
            return "rgb(82,82,82)";
        } else {
            return "rgb(0,0,0)";
        }
    }

    //    根据传入的值找到对应的颜色

    /**
     * @Description 根据传入的值找到对应的颜色
     * @input number 总理论直径
     * @Return 返回传入值的对应的颜色
     */
    public static String getlengthColor(double number) {
        if (number == 0) {
            return "rgb(248,246,231)";
        } else if (number >= 0 && number <= 5) {
            return "rgb(0,0,255)";
        } else if (number >= 5 && number <= 10) {
            return "rgb(0,255,255)";
        } else if (number >= 10 && number <= 15) {
            return "rgb(0,255,0)";
        } else if (number >= 15 && number <= 20) {
            return "rgb(127,255,0)";
        } else if (number >= 20 && number <= 25) {
            return "rgb(255,255,0)";
        } else if (number >= 25 && number <= 30) {
            return "rgb(255,165,0)";
        } else if (number >= 30 && number <= 35) {
            return "rgb(255,69,0)";
        } else if (number >= 35 && number <= 40) {
            return "rgb(255,0,0)";
        } else if (number >= 40 && number <= 45) {
            return "rgb(139,0,0)";
        } else {
            return "rgb(0,0,0)";
        }
    }

    public boolean keyExistsIgnoreCase(Map<String, Double> map, String key) {
        for (String existingKey : map.keySet()) {
            if (existingKey.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }


    public static double getValueIgnoreCase(Map<String, Double> map, String key) {
        for (String existingKey : map.keySet()) {
            if (existingKey.equalsIgnoreCase(key)) {
                return map.get(existingKey);
            }
        }
        return 0.0;
    }
}
