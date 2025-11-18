package HarnessPackOpti.Optimize.elec;


import HarnessPackOpti.Algorithm.FindAllPath;
import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.FindBranchByNode;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.CircuitInfoCalculate.CalculateInlineWet;
import HarnessPackOpti.CircuitInfoCalculate.CalculatePathBreakNumber;
import HarnessPackOpti.CircuitInfoCalculate.CalculatePathLength;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ElecPositionVariantCalculation {
    //    top20
    public static Integer TopNumber = 0;
    //    可能用点数量
    public static Integer PointNumber = 0;
    //    初始样本最少为多少
    public static final Integer LessRandomSamleNumber = 10000;
    //    仓库
    public static List<List<String>> WareHouse = new ArrayList<>();
    //    自动补全的次数
    public static Integer AutoComplete = 100;
    //    每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();
    //    最优样本重复次数
    public static Integer BestRepetitionNumber = 0;
    //    迭代重复的次数限值
    public static Integer IterationRestrictNumber = 5;
    //    初代样本量
    public static Integer InitialSampleNumber = 10000;
    //    优化阈值
    public static Integer OptimizeThresholdValue = 100000;


    private final OptimizeStopStatusStore optimizeStopStatusStore;

    public ElecPositionVariantCalculation() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance(); // 使用Store的单例实例
    }

    //    当前方案的id
    private static String CaseId = null;
    private static String TopoId = null;
    private static String optimizeRecordId = null;

    public String elecPositionVariantCalculation(String jsonContent) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> initmapFile = jsonToMap.TransJsonToMap(jsonContent);
        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(initmapFile);
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) initmapFile.get("appPositions");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) initmapFile.get("edges");
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) initmapFile.get("loopInfos");
        Map<String, Object> caseInfo = (Map<String, Object>) initmapFile.get("caseInfo");
        Map<String, Object> topoInfo = (Map<String, Object>) initmapFile.get("topoInfo");
        List<Map<String, Object>> points = (List<Map<String, Object>>) initmapFile.get("points");
        Map<String, Object> optimizeRecord = (Map<String, Object>) initmapFile.get("optimizeRecord");
        CaseId = caseInfo.get("id").toString();
        TopoId = topoInfo.get("id").toString();
        optimizeRecordId = optimizeRecord.get("id").toString();
        optimizeStopStatusStore.setKey(optimizeRecordId);
//        读取excel中的导线成本
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        Map<String, Double> elecBusinessPrice = readWireInfoLibrary.getElecBusinessPrice();
//       主要是为了获得当前打断状况下  可用的位置点
        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        for (Map<String, Object> k : edges) {
            strPointNameList.add(k.get("startPointName").toString());
            endPointNameList.add(k.get("endPointName").toString());
        }
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (edge.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointNameList, endPointNameList, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        List<String> allPoint = adjacencyMatrixGraph.getAllPoint();
        TopNumber = allPoint.size();
        PointNumber = allPoint.size();
//        生成一个字典

//        首先找出当中位置不固定的用电器以及不固定用电器可变的位置点
        Map<String, List<String>> elecChangeablePosition = new HashMap<>();
        for (Map<String, Object> appPosition : appPositions) {
            if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("1")) {
                String appName = appPosition.get("appName").toString();
                List<String> list = new ArrayList<>();
                if (appPosition.get("specifyPoints") != null && !appPosition.get("specifyPoints").toString().isEmpty()) {
                    String specifyPoints = appPosition.get("specifyPoints").toString();
                    String[] parts = specifyPoints.split(",");
                    List<String> collect = new ArrayList<>();
                    for (String part : parts) {
                        collect.add(part);
                    }
                    for (String s : collect) {
                        list.add(findNameById(s, points));
                    }
                }
                list.retainAll(allPoint);
                elecChangeablePosition.put(appName, list);
            } else if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("2")) {
                String appName = appPosition.get("appName").toString();
                elecChangeablePosition.put(appName, allPoint);
            }
        }
        Set<String> elecChangeablePositionSet = new HashSet<>(elecChangeablePosition.keySet());
        List<String> elecChangeableList = new ArrayList<>(elecChangeablePositionSet);
        System.out.println("计算用电器再每个点的成本");

//        计算每个用电器在所有点的成本计算
        List<Map<String, Object>> elecInAllAddress = new ArrayList<>();
        for (int i = 0; i < elecChangeableList.size(); i++) {
            String s = elecChangeableList.get(i);
            Map<String, Object> elecInAllAddressDetail = new HashMap<>();
            Map<String, Object> mapFile = deepCopy(initmapFile);
            List<Map<String, Object>> best = findOneGroup(mapFile, s, allPoint, "0");
            elecInAllAddressDetail.put("group", s);
            elecInAllAddressDetail.put("detail", best);
            elecInAllAddress.add(elecInAllAddressDetail);
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                String s1 = objectMapper.writeValueAsString(elecInAllAddress);
                return objectMapper.writeValueAsString(elecInAllAddress);
            }
        }

        System.out.println("计算用电器再每个点的成本完成");
        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            String s1 = objectMapper.writeValueAsString(elecInAllAddress);
            return objectMapper.writeValueAsString(elecInAllAddress);
        }


        System.out.println("生成字典");
        Map<String, Object> filtration = filtration(adjacencyMatrixGraph, projectInfo);
        System.out.println("字典生成完成");
//        筛选出可变的回路
        List<Map<String, Object>> unfixedLoopInfoList = new ArrayList<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            if (elecChangeablePositionSet.contains(startApp) || elecChangeablePositionSet.contains(endApp)) {
                unfixedLoopInfoList.add(loopInfo);
            }
        }
        List<String> elecChangeablePositionList = elecChangeablePositionSet.stream().collect(Collectors.toList());
        List<List<String>> correlationElec = new ArrayList<>();

//      可变用电器一一比对   将符合要求的放到一个组里面
        for (int i = 0; i < elecChangeablePositionList.size() - 1; i++) {
            for (int j = i + 1; j < elecChangeablePositionList.size(); j++) {
                String elec1 = elecChangeablePositionList.get(i);
                String elec2 = elecChangeablePositionList.get(j);
                List<Map<String, Object>> group = new ArrayList<>();
                for (Map<String, Object> map : unfixedLoopInfoList) {
                    if ((map.get("startApp").equals(elec1) && map.get("endApp").equals(elec2))
                            || (map.get("startApp").equals(elec2) && map.get("endApp").equals(elec1))) {
                        group.add(map);
                    }
                }
//                计算当前是否达到一个阈值
                double costNumber = 0.0;
                for (Map<String, Object> map : group) {
                    String loopWireway = map.get("loopWireway").toString();
                    Map<String, String> map1 = elecFixedLocationLibrary.get(loopWireway);
                    costNumber += Double.parseDouble(map1.get("导线单位商务价（元/米）"));
                }
                if (costNumber > 2) {
                    List<String> elec = new ArrayList<>();
                    elec.add(elecChangeablePositionList.get(i));
                    elec.add(elecChangeablePositionList.get(j));
                    correlationElec.add(elec);
                }
            }
        }
//        对当前的用电器进行一个分组
        List<List<String>> lists = elecGroup(elecChangeablePositionSet.stream().collect(Collectors.toList()), correlationElec);

        List<Map<String, Object>> bestList = new ArrayList<>();
//        针对不同的阈值进行一个计算
        for (List<String> list : lists) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                break;
            }
//            每一组里面的用电器名称进行一个拼接
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                sb.append(list.get(i));
                if (i < list.size() - 1) {
                    sb.append("&");
                }
            }
            String result = sb.toString();
            Map<String, Object> mapFile = new HashMap<>(initmapFile);
            Map<String, Object> groupResult = new HashMap<>();
//            对分组的进行一个计算   当这个组里面只有一个点的时候 直接计算     当数量达到一个量级进行一个迭代计算
            if (list.size() == 1) {
//                elecInAllAddress 里面已经存在了每个用电器在每个点上的成本  只需要找出当前用电器可变点的成本
                List<Map<String, Object>> best = new ArrayList<>();
                for (Map<String, Object> inAllAddress : elecInAllAddress) {
                    if (inAllAddress.get("group").equals(list.get(0))) {
                        List<Map<String, Object>> detail = (List<Map<String, Object>>) inAllAddress.get("detail");
                        best.add(detail.get(0));
                        for (Map<String, Object> map : detail) {
                            Map<String, Object> elecOptimizeResult = (Map<String, Object>) map.get("elecOptimizeResult");
                            if (elecOptimizeResult.get("number").toString().equals("base")) {
                                continue;
                            }
                            Map<String, String> address = (Map<String, String>) elecOptimizeResult.get("address");
                            List<String> addressList = address.keySet().stream().collect(Collectors.toList());
                            String addressName = address.get(addressList.get(0));
                            if (elecChangeablePosition.get(result).contains(addressName)) {
                                best.add(map);
                            }
                        }
                    }
                }
                groupResult.put("group", result);
                groupResult.put("detail", best);
                bestList.add(groupResult);
            } else {
//               计算一共可能存在多少可能性
                BigInteger currentPossibility = new BigInteger("1");
                for (String s : list) {
                    currentPossibility = currentPossibility.multiply(new BigInteger(String.valueOf(elecChangeablePosition.get(s).size())));
                }
                if (currentPossibility.compareTo(new BigInteger(String.valueOf(OptimizeThresholdValue))) == -1) {
                    TopNumber = 20;
                    List<Map<String, Object>> moreGroup = findMoreGroup(mapFile, elecChangeablePosition, list, elecFixedLocationLibrary, elecBusinessPrice, filtration);
                    groupResult.put("group", result);
                    groupResult.put("detail", moreGroup);
                    bestList.add(groupResult);
                } else {
                    List<Map<String, Object>> optimizeList = optimizeIteration(mapFile, elecChangeablePosition, list, elecFixedLocationLibrary, elecBusinessPrice, filtration);
                    groupResult.put("group", result);
                    groupResult.put("detail", optimizeList);
                    bestList.add(groupResult);
                }
            }
            WareHouse = new ArrayList<>();
            BestCost = new HashMap<>();
            BestRepetitionNumber = 0;
        }

//
        Set<String> groupSet = new HashSet<>();
        for (Map<String, Object> map : bestList) {
            String group = map.get("group").toString();
            groupSet.add(group);
        }
        for (Map<String, Object> inAllAddress : elecInAllAddress) {
            String group = inAllAddress.get("group").toString();
            if (!groupSet.contains(group)) {
                List<String> list = elecChangeablePosition.get(group);
                Map<String, Object> groupResult = new HashMap<>();
                String name = inAllAddress.get("group").toString();
                List<Map<String, Object>> best = new ArrayList<>();
                List<Map<String, Object>> detail = (List<Map<String, Object>>) inAllAddress.get("detail");
                best.add(detail.get(0));
                for (Map<String, Object> map : detail) {
                    Map<String, Object> elecOptimizeResult = (Map<String, Object>) map.get("elecOptimizeResult");
                    if (elecOptimizeResult.get("number").toString().equals("base")) {
                        continue;
                    }
                    Map<String, String> address = (Map<String, String>) elecOptimizeResult.get("address");
                    List<String> addressList = address.keySet().stream().collect(Collectors.toList());
                    String addressName = address.get(addressList.get(0));
                    if (list.contains(addressName)) {
                        best.add(map);
                    }
                }
                groupResult.put("group", name);
                groupResult.put("detail", best);
                bestList.add(groupResult);

            }
        }
        String json = objectMapper.writeValueAsString(bestList);
        System.out.println(json);
        return json;
    }


    /**
     * @Description: 编写一个字典
     * @input: adjacencyMatrixGraph 生成的拓扑图
     * @input: map 解析完成的整车信息
     * @Return: 返回两点之间所有的路径   每个路劲中又详细说明了长度、分支打断等情况
     */
    public Map<String, Object> filtration(GenerateTopoMatrix adjacencyMatrixGraph, Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        List<String> allPoint = adjacencyMatrixGraph.getAllPoint();
        FindAllPath findAllPath = new FindAllPath();
        List<Map<String, String>> edges = (List<Map<String, String>>) map.get("所有分支信息");
        for (int i = 0; i < allPoint.size(); i++) {
            String startName = allPoint.get(i);
            for (int j = 0; j < allPoint.size(); j++) {
                String endName = allPoint.get(j);
                List<List<Integer>> allPathBetweenTwoPoint = findAllPath.findAllPathBetweenTwoPoint(adjacencyMatrixGraph.getAdj(), adjacencyMatrixGraph.getAllPoint().indexOf(startName), adjacencyMatrixGraph.getAllPoint().indexOf(endName));
                List<Object> currentPath = new ArrayList<>();
                for (List<Integer> list : allPathBetweenTwoPoint) {
                    //           将路径中的数字转化为对应的名称
                    List<String> listname = convertPathToNumbers(list, adjacencyMatrixGraph.getAllPoint());
//                        开始及逆行一个计算
                    Map<String, Object> information = new HashMap<>();
                    FindBranchByNode findBranchByNode = new FindBranchByNode();
                    Map<String, Object> MapbranchByNode = findBranchByNode.findBranchByNode(listname, edges);
                    List<String> edgeIdList = (List<String>) MapbranchByNode.get("idList");
                    CalculatePathLength calculatePathLength = new CalculatePathLength();
                    Map<String, Object> pathLength = calculatePathLength.calculatePathLength(edgeIdList, map);
                    CalculatePathBreakNumber calculatePathBreakNumber = new CalculatePathBreakNumber();
                    Map<String, Object> objectMap = calculatePathBreakNumber.calculatePathBreakNumber(edgeIdList, map);
//        分支打断名称
                    List<String> topologyStatusCodeNameList = (List<String>) objectMap.get("nameList");

                    Integer breakNumber = topologyStatusCodeNameList.size();
                    CalculateInlineWet calculateInlineWet = new CalculateInlineWet();
                    Map<String, String> inlineWet = calculateInlineWet.calculateInlineWet(topologyStatusCodeNameList, map);
                    int count = 0;
                    for (Object mapValue : inlineWet.values()) {
                        if ("w".toUpperCase().equals(mapValue.toString())) {
                            count++;
                        }
                    }
                    if ("w".toUpperCase().equals(getWaterParam(listname.get(0), (List<Map<String, String>>) map.get("所有端点信息")))) {
                        count++;
                    }
                    if ("w".toUpperCase().equals(getWaterParam(listname.get(listname.size() - 1), (List<Map<String, String>>) map.get("所有端点信息")))) {
                        count++;
                    }
                    information.put("回路长度", (Double) pathLength.get("长度") / 1000);
                    information.put("打断次数", breakNumber);
                    information.put("湿区个数", count);
                    information.put("路径", listname);
                    currentPath.add(information);
                }
                String name = startName + "-" + endName;
                result.put(name, currentPath);
            }
        }
        return result;
    }


    /**
     * @Description: 分组之后   用电位置排列组合数量太大时进行优化
     * @input: initMapFile  最初的接送转为map形式
     * @input: elecChangeablePosition 可变用电器 以及对应的可变位置
     * @input: electricalList 可变用电器
     * @input: elecFixedLocationLibrary 导线选型的excel文件中sheet1中的信息
     * @input: elecBusinessPrice 导线选型的excel文件中sheet2中的信息
     * @input: filtration 生成的字典
     * @Return: 返回最优的top10方案
     */
    public List<Map<String, Object>> optimizeIteration(Map<String, Object> initMapFile,
                                                       Map<String, List<String>> elecChangeablePosition,
                                                       List<String> electricalList,
                                                       Map<String, Map<String, String>> elecFixedLocationLibrary,
                                                       Map<String, Double> elecBusinessPrice,
                                                       Map<String, Object> filtration) throws Exception {
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) initMapFile.get("appPositions");
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> temporarilyList = new ArrayList<>();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
//       首先对里面的每一个用电器的位置进行一个处理 当用电器的可变数量在10以内最多取前五    用电器的位置在10-20之间取前10 用电器的位置在20-30之间取前15  用电器的位置在30以上取前百分之四十
        Map<String, List<String>> newElecChangeablePosition = new HashMap<>();
//        重新划定用电器可变的位置点
        for (String s : electricalList) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                return null;
            }
            Map<String, Object> deepCopyMap = deepCopy(initMapFile);
            System.out.println(s);
            List<String> list = elecChangeablePosition.get(s);
            if (list.size() < 5) {
                newElecChangeablePosition.put(s, list);
            } else if (list.size() >= 5 && list.size() <= 10) {
                TopNumber = 5;
                List<Map<String, Object>> bestOneGroup = findOneGroup(deepCopyMap, s, elecChangeablePosition.get(s), "1");
                List<String> address = new ArrayList<>();
                for (Map<String, Object> bestOnemap : bestOneGroup) {
                    Map<String, Object> map = (Map<String, Object>) bestOnemap.get("elecOptimizeResult");
                    Map<String, String> map1 = (Map<String, String>) map.get("address");
                    address.add(map1.get(s));
                }
                newElecChangeablePosition.put(s, address);
            } else if (list.size() > 10 && list.size() <= 20) {
                TopNumber = 10;
                List<Map<String, Object>> bestOneGroup = findOneGroup(deepCopyMap, s, elecChangeablePosition.get(s), "1");
                List<String> address = new ArrayList<>();
                for (Map<String, Object> bestOnemap : bestOneGroup) {
                    Map<String, Object> map = (Map<String, Object>) bestOnemap.get("elecOptimizeResult");
                    Map<String, String> map1 = (Map<String, String>) map.get("address");
                    address.add(map1.get(s));
                }
                newElecChangeablePosition.put(s, address);
            } else if (list.size() > 20 && list.size() <= 30) {
                TopNumber = 15;
                List<Map<String, Object>> bestOneGroup = findOneGroup(deepCopyMap, s, elecChangeablePosition.get(s), "1");
                List<String> address = new ArrayList<>();
                for (Map<String, Object> bestOnemap : bestOneGroup) {
                    Map<String, Object> map = (Map<String, Object>) bestOnemap.get("elecOptimizeResult");
                    Map<String, String> map1 = (Map<String, String>) map.get("address");
                    address.add(map1.get(s));
                }
                newElecChangeablePosition.put(s, address);
            } else {
                TopNumber = list.size() * 40 / 100;
                List<Map<String, Object>> bestOneGroup = findOneGroup(deepCopyMap, s, elecChangeablePosition.get(s), "1");
                List<String> address = new ArrayList<>();
                for (Map<String, Object> bestOnemap : bestOneGroup) {
                    Map<String, Object> map = (Map<String, Object>) bestOnemap.get("elecOptimizeResult");
                    Map<String, String> map1 = (Map<String, String>) map.get("address");
                    address.add(map1.get(s));
                }
                newElecChangeablePosition.put(s, address);
            }
        }

        TopNumber = 20;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < electricalList.size(); i++) {
            sb.append(electricalList.get(i));
            if (i < electricalList.size() - 1) {
                sb.append("&");
            }
        }
        String resultElectrical = sb.toString();
//        计算处理处理后的样本数量
        BigInteger possibilityNumber = new BigInteger("1");
        for (String name : newElecChangeablePosition.keySet()) {
            possibilityNumber = possibilityNumber.multiply(new BigInteger(String.valueOf(newElecChangeablePosition.get(name).size())));
        }
        Map<String, Object> mapFile = deepCopy(initMapFile);
//        当前的可能性与初代样本数量进行一个比较：   初代样本*10   是否大于possibilityNumber   大于直接进行计算      小于则进行一个优化
        if (possibilityNumber.compareTo(new BigInteger(String.valueOf(InitialSampleNumber))) == -1) {
            List<Map<String, Object>> bestList = findMoreGroup(mapFile, newElecChangeablePosition, newElecChangeablePosition.keySet().stream().collect(Collectors.toList()), elecFixedLocationLibrary, elecBusinessPrice, filtration);
            return bestList;
        }

//        按照新的位置点随机生成一批样本
        List<List<String>> possibilityLists = initialOptimize(newElecChangeablePosition, electricalList);
        WareHouse.addAll(possibilityLists);
//        找出这些可变用电器的相关回路
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) mapFile.get("loopInfos");
        List<Map<String, Object>> correlationLoopInfos = new ArrayList<>();
        List<String> correlationId = new ArrayList<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            if (!(startApp.startsWith("[") || endApp.startsWith("[")) && (newElecChangeablePosition.containsKey(startApp) || newElecChangeablePosition.containsKey(endApp))) {
                correlationLoopInfos.add(loopInfo);
                correlationId.add(loopInfo.get("id").toString());
            }
        }

        mapFile.put("loopInfos", correlationLoopInfos);

        String baseJson = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile));
        Map<String, Object> baseCalculateMap = jsonToMap.TransJsonToMap(baseJson);
        Map<String, Object> baseProjectCircuitInfo = (Map<String, Object>) baseCalculateMap.get("projectCircuitInfo");
        baseCalculateMap.put("topoId", TopoId);
        baseCalculateMap.put("caseId", CaseId);
        double baseCost = (Double) baseProjectCircuitInfo.get("总成本");
        double baseWeight = (Double) baseProjectCircuitInfo.get("回路总重量");
        double baseLength = (Double) baseProjectCircuitInfo.get("回路总长度");
        Map<String, String> baseCostMap = new HashMap<>();
        baseCostMap.put("总成本", "base");
        baseCostMap.put("总重量", "base");
        baseCostMap.put("总长度", "base");
        Map<String, String> baaseAddress = new HashMap<>();
        for (String s : electricalList) {
            baaseAddress.put(s, findNode(s, appPositions));
        }
        Map<String, Object> baseMap = new HashMap<>();
        baseMap.put("成本", baseCostMap);
        baseMap.put("address", baaseAddress);
        baseMap.put("number", "base");
        baseCalculateMap.put("elecOptimizeResult", baseMap);
        result.add(baseCalculateMap);
        temporarilyList.add(baseCalculateMap);
//        计算在当前的方案下面筛选出来的回路成本:直接将要的回路放在里面   替换位置点的信息

        List<Map<String, Object>> findBest = compute(possibilityLists, mapFile, electricalList, "0", elecFixedLocationLibrary, elecBusinessPrice, filtration);
        temporarilyList.addAll(findBest);
//        开始进行一个迭代
        int hybridizationNumber = 1;
        while (true) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                List<Map<String, Object>> restore = restore(findBest, initMapFile);
                result.addAll(restore);
                return result;
            }
            System.out.println("第" + hybridizationNumber + "次迭代");
            findBest = hybridization(temporarilyList, mapFile, electricalList, newElecChangeablePosition, hybridizationNumber, elecFixedLocationLibrary, elecBusinessPrice, filtration);
            temporarilyList = findBest;
            if (hybridizationNumber == 1) {
                Map<String, Object> map = (Map<String, Object>) findBest.get(0).get("elecOptimizeResult");
                Map<String, Object> cost = (Map<String, Object>) map.get("成本");
                double costTotal = Double.parseDouble(cost.get("总成本").toString());
                double costLenth = Double.parseDouble(cost.get("总长度").toString());
                double costWeight = Double.parseDouble(cost.get("总重量").toString());
                BestCost.put("总成本", costTotal);
                BestCost.put("总长度", costLenth);
                BestCost.put("总重量", costWeight);
            } else {
                Map<String, Object> map = (Map<String, Object>) findBest.get(0).get("elecOptimizeResult");
                Map<String, Object> cost = (Map<String, Object>) map.get("成本");
                double costTotal = Double.parseDouble(cost.get("总成本").toString());
                double costLenth = Double.parseDouble(cost.get("总长度").toString());
                double costWeight = Double.parseDouble(cost.get("总重量").toString());
//            当前最优解中的长度判断当前的成本、长度、重量是都一样
                if (Math.abs(BestCost.get("总成本") - costTotal) < 0.000001
                        && Math.abs(BestCost.get("总长度") - costLenth) < 0.000001
                        && Math.abs(BestCost.get("总重量") - costWeight) < 0.000001) {
                    BestRepetitionNumber = BestRepetitionNumber + 1;
                } else {
                    BestRepetitionNumber = 0;
                    BestCost.put("总成本", costTotal);
                    BestCost.put("总长度", costLenth);
                    BestCost.put("总重量", costWeight);
                }
            }
            if (BestRepetitionNumber == IterationRestrictNumber) {
                break;
            }
            hybridizationNumber++;
        }
        List<Map<String, Object>> restore = restore(findBest, initMapFile);
        result.addAll(restore);
        return result;
    }

    /**
     * @Description: 迭代的方案
     * @input: compute   最优的top10的方案
     * @input: initMapFile  最初的接送转为map形式
     * @input: electricalList 可变用电器
     * @input: newElecChangeablePosition 可变用电器 以及对应的可变位置
     * @input: hybridizationNumber  迭代次数
     * @input: elecFixedLocationLibrary 导线选型的excel文件中sheet1中的信息
     * @input: elecBusinessPrice 导线选型的excel文件中sheet2中的信息
     * @input: filtration 生成的字典
     * @Return: 返回最优的top10方案
     */
    public List<Map<String, Object>> hybridization(List<Map<String, Object>> compute,
                                                   Map<String, Object> initMapFile,
                                                   List<String> electricalList,
                                                   Map<String, List<String>> newElecChangeablePosition,
                                                   int hybridizationNumber,
                                                   Map<String, Map<String, String>> elecFixedLocationLibrary,
                                                   Map<String, Double> elecBusinessPrice,
                                                   Map<String, Object> filtration) throws Exception {
//        首先提取出中每一个用电器对应的位置
        List<List<String>> electricalAddressList = new ArrayList<>();
        for (Map<String, Object> computeMap : compute) {
            Map<String, Object> map = (Map<String, Object>) computeMap.get("elecOptimizeResult");
            List<String> electricalAddress = new ArrayList<>();
            Map<String, String> address = (Map<String, String>) map.get("address");
            for (String s : electricalList) {
                electricalAddress.add(address.get(s));
            }
            electricalAddressList.add(electricalAddress);
        }

        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            return null;
        }
//        首先进行交叉   计算出所有的可能性，在交叉的基础上面进行一个变异
        List<List<String>> intersect = intersect(electricalAddressList);
        WareHouse.addAll(intersect);
        intersect.addAll(electricalAddressList);
        intersect = intersect.stream().distinct().collect(Collectors.toList());

        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            return null;
        }

        List<List<String>> allPossibilityLists = new ArrayList<>();
        allPossibilityLists.addAll(intersect);
        int IterationNumber = 1;
        while (allPossibilityLists.size() < InitialSampleNumber && IterationNumber < AutoComplete) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                return null;
            }
            List<List<String>> variation = variation(intersect, electricalList, newElecChangeablePosition);
            allPossibilityLists.addAll(variation);
            allPossibilityLists = allPossibilityLists.stream().distinct().collect(Collectors.toList());
            IterationNumber++;
        }
        System.out.println("最终方案数量：" + allPossibilityLists.size());
        Map<String, Object> mapFile = new HashMap<>(initMapFile);
        List<Map<String, Object>> hybridizationcompute = compute(allPossibilityLists, mapFile, electricalList, String.valueOf(hybridizationNumber), elecFixedLocationLibrary, elecBusinessPrice, filtration);
        return hybridizationcompute;
    }

    /**
     * @Description: 迭代的方案
     * @input: lists 所有的位置变种可能性
     * @input: initMapFile  最初的接送转为map形式
     * @input: electricalList 可变用电器
     * @input: hybridizationNumber  迭代次数
     * @input: elecFixedLocationLibrary 导线选型的excel文件中sheet1中的信息
     * @input: elecBusinessPrice 导线选型的excel文件中sheet2中的信息
     * @input: filtration 生成的字典
     * @Return: 返回最优的top10方案
     */
    public List<Map<String, Object>> compute(List<List<String>> lists,
                                             Map<String, Object> initMapFile,
                                             List<String> electricalList,
                                             String number,
                                             Map<String, Map<String, String>> elecFixedLocationLibrary,
                                             Map<String, Double> elecBusinessPrice,
                                             Map<String, Object> filtration) throws Exception {
        FindBest findBest = new FindBest();
        List<Map<String, Object>> result = new ArrayList<>();
        ElecProjectCircuitInfoOutput projectCircuitInfoOutput = new ElecProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        String baseJson = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(initMapFile), filtration, elecFixedLocationLibrary, elecBusinessPrice);
        Map<String, Object> baseCalculateMap = jsonToMap.TransJsonToMap(baseJson);
        Map<String, Object> baseProjectCircuitInfo = (Map<String, Object>) baseCalculateMap.get("projectCircuitInfo");
        baseCalculateMap.put("topoId", TopoId);
        baseCalculateMap.put("caseId", CaseId);
        double baseCost = (Double) baseProjectCircuitInfo.get("总成本");
        double baseWeight = (Double) baseProjectCircuitInfo.get("回路总重量");
        double baseLength = (Double) baseProjectCircuitInfo.get("回路总长度");

        System.out.println("lists:" + lists.size() + "个方案");
        int numbber = 1;
        DecimalFormat df = new DecimalFormat("0.00");
        for (List<String> list : lists) {
            Map<String, Object> mapFile = deepCopy(initMapFile);
            numbber++;
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                break;
            }
            Map<String, Object> map = new HashMap<>();
            List<Map<String, Object>> copyAppPositions = (List<Map<String, Object>>) mapFile.get("appPositions");
            for (String s : electricalList) {
                for (Map<String, Object> copyAppPosition : copyAppPositions) {
                    if (copyAppPosition.get("appName").toString().equals(s)) {
                        copyAppPosition.put("unregularPointName", list.get(electricalList.indexOf(s)));
                        continue;
                    }
                }
            }
            mapFile.put("appPositions", copyAppPositions);
            long l = System.currentTimeMillis();
            String json = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile), filtration, elecFixedLocationLibrary, elecBusinessPrice);
            System.out.println("第" + numbber + "个方案" + "用时" + (System.currentTimeMillis() - l));
            Map<String, Object> calculatemap = jsonToMap.TransJsonToMap(json);
            Map<String, Double> cost = new HashMap<>();
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) calculatemap.get("projectCircuitInfo");
            calculatemap.put("topoId", TopoId);
            calculatemap.put("caseId", CaseId);
            cost.put("总成本", Double.parseDouble(df.format((Double) projectCircuitInfo.get("总成本") - baseCost)));
            cost.put("总重量", Double.parseDouble(df.format((Double) projectCircuitInfo.get("回路总重量") - baseWeight)));
            cost.put("总长度", Double.parseDouble(df.format((Double) projectCircuitInfo.get("回路总长度") - baseLength)));
            map.put("成本", cost);
            Map<String, String> address = new HashMap<>();
            for (String s : electricalList) {
                address.put(s, list.get(electricalList.indexOf(s)));
            }
            map.put("address", address);
            map.put("number", number);
            calculatemap.put("成本", cost);
            calculatemap.put("elecOptimizeResult", map);
            result.add(calculatemap);
            mapFile = null;

        }
        List<Map<String, Object>> best = findBest.findBest(result, "成本", TopNumber);
        result = null;
        System.gc();
        return best;
    }

    /**
     * @Description: 迭代的方案
     * @input: newElecChangeablePosition 可变用电器 以及对应的可变位置
     * @input: electricalList 可变用电器
     * @Return: 返回生成的随机方案
     */
    public List<List<String>> initialOptimize(Map<String, List<String>> newElecChangeablePosition, List<String> electricalList) {
        Random random = new Random();
        List<List<String>> result = new ArrayList<>();
        while (result.size() < LessRandomSamleNumber) {
            List<String> caseList = new ArrayList<>();
            for (String s : electricalList) {
                List<String> list = newElecChangeablePosition.get(s);
                caseList.add(list.get(random.nextInt(list.size())));
            }
            if (!containsList(caseList, result) && !containsList(caseList, WareHouse)) {
                result.add(caseList);
            }
        }
        return result;
    }

    /**
     * @Description: 当一个用电器的时候  找到他像关联的回路然后进行一个计算并且返回最优的一个结果
     * @input: mapFile 整车信息
     * @input: name    用电器名称
     * @input: list     用电器可能存在的位置点
     * @input: number   迭代
     * @Return: 返回当前用电器在所有位置上的成本、重量、长度等一系列信息
     */
    public List<Map<String, Object>> findOneGroup(Map<String, Object> mapFile, String name, List<String> list, String number) throws Exception {

        FindBest findBest = new FindBest();
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) mapFile.get("loopInfos");
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) mapFile.get("appPositions");
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();


//        找到他相关的回路
        List<Map<String, Object>> correlationLoopInfos = new ArrayList<>();
        List<String> correlationId = new ArrayList<>();
        DecimalFormat df = new DecimalFormat("0.00");
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            if (!(startApp.startsWith("[") && endApp.startsWith("[")) && (startApp.equals(name) || endApp.equals(name))) {
                correlationLoopInfos.add(loopInfo);
                correlationId.add(loopInfo.get("id").toString());
            }
        }
        List<Map<String, Object>> recordsList = new ArrayList<>();
//        首先计算出当前方案的成本重量长度
        mapFile.put("loopInfos", correlationLoopInfos);
        String baseJson = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile));
        Map<String, Object> baseCalculateMap = jsonToMap.TransJsonToMap(baseJson);
        Map<String, Object> baseProjectCircuitInfo = (Map<String, Object>) baseCalculateMap.get("projectCircuitInfo");
        baseCalculateMap.put("topoId", TopoId);
        baseCalculateMap.put("caseId", CaseId);
        double baseCost = (Double) baseProjectCircuitInfo.get("总成本");
        double baseWeight = (Double) baseProjectCircuitInfo.get("回路总重量");
        double baseLength = (Double) baseProjectCircuitInfo.get("回路总长度");
        Map<String, String> baseCostMap = new HashMap<>();
        baseCostMap.put("总成本", "base");
        baseCostMap.put("总重量", "base");
        baseCostMap.put("总长度", "base");
        Map<String, String> baaseAddress = new HashMap<>();
        baaseAddress.put(name, findNode(name, appPositions));
        Map<String, Object> baseMap = new HashMap<>();
        baseMap.put("成本", baseCostMap);
        baseMap.put("address", baaseAddress);
        baseMap.put("number", "base");
        baseCalculateMap.put("elecOptimizeResult", baseMap);
        recordsList.add(baseCalculateMap);
        List<Map<String, Object>> allArrangementList = new ArrayList<>();
        System.out.println("开始计算=================");
        for (String s : list) {
            System.out.println(s);
            Map<String, Object> map = new HashMap<>();
            for (Map<String, Object> appPosition : appPositions) {
                if (appPosition.get("appName").toString().equals(name)) {
                    appPosition.put("unregularPointName", s);
                }
            }
            String json = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile));
            Map<String, Object> calculatemap = jsonToMap.TransJsonToMap(json);
            Map<String, String> cost = new HashMap<>();
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) calculatemap.get("projectCircuitInfo");
            calculatemap.put("topoId", TopoId);
            calculatemap.put("caseId", CaseId);
            cost.put("总成本", df.format((Double) projectCircuitInfo.get("总成本") - baseCost));
            cost.put("总重量", df.format((Double) projectCircuitInfo.get("回路总重量") - baseWeight));
            cost.put("总长度", df.format((Double) projectCircuitInfo.get("回路总长度") - baseLength));

            Map<String, String> address = new HashMap<>();
            address.put(name, s);
            map.put("成本", cost);
            map.put("address", address);
            map.put("number", number);
            calculatemap.put("elecOptimizeResult", map);
            calculatemap.put("成本", cost);
            allArrangementList.add(calculatemap);
        }
        List<Map<String, Object>> cost = findBest.findBest(allArrangementList, "成本", TopNumber);
        recordsList.addAll(cost);
        allArrangementList = null;
        return recordsList;
    }


    /**
     * @Description: 当组里面不止一个同电器，但是可变
     * @input: initMapFile  最初的接送转为map形式
     * @input: elecChangeablePosition  可变用电器  可选的位置点
     * @input: electricalList 可变用电器
     * @input: elecFixedLocationLibrary 导线选型的excel文件中sheet1中的信息
     * @input: elecBusinessPrice 导线选型的excel文件中sheet2中的信息
     * @input: filtration 生成的字典
     * @Return: 返回最优的top10方案
     */
    public List<Map<String, Object>> findMoreGroup(Map<String, Object> initmapFile,
                                                   Map<String, List<String>> elecChangeablePosition,
                                                   List<String> electricalList,
                                                   Map<String, Map<String, String>> elecFixedLocationLibrary,
                                                   Map<String, Double> elecBusinessPrice,
                                                   Map<String, Object> filtration) throws Exception {
        FindBest findBest = new FindBest();
        ElecProjectCircuitInfoOutput projectCircuitInfoOutput = new ElecProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        List<Map<String, Object>> result = new ArrayList<>();
        // 找出所有用电器存在的位置并进行排列
        List<List<String>> allArrangements = new ArrayList<>();
        // 用于存储当前排列
        List<String> currentArrangement = new ArrayList<>();
        // 递归生成所有排列
        arrange(electricalList, elecChangeablePosition, 0, currentArrangement, allArrangements);
//        计算每个方案的结果
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) initmapFile.get("loopInfos");
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) initmapFile.get("appPositions");

        //        找出这些可变用电器的相关回路
        List<Map<String, Object>> correlationLoopInfos = new ArrayList<>();
        List<String> correlationId = new ArrayList<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            if (!(startApp.startsWith("[") || endApp.startsWith("[")) && (elecChangeablePosition.containsKey(startApp) || elecChangeablePosition.containsKey(endApp))) {
                correlationLoopInfos.add(loopInfo);
                correlationId.add(loopInfo.get("id").toString());
            }
        }
        Map<String, Object> mapFile = deepCopy(initmapFile);
        mapFile.put("loopInfos", correlationLoopInfos);

        String baseJson = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile), filtration, elecFixedLocationLibrary, elecBusinessPrice);
        Map<String, Object> baseCalculateMap = jsonToMap.TransJsonToMap(baseJson);
        Map<String, Object> baseProjectCircuitInfo = (Map<String, Object>) baseCalculateMap.get("projectCircuitInfo");
        baseCalculateMap.put("topoId", TopoId);
        baseCalculateMap.put("caseId", CaseId);
        double baseCost = (Double) baseProjectCircuitInfo.get("总成本");
        double baseWeight = (Double) baseProjectCircuitInfo.get("回路总重量");
        double baseLength = (Double) baseProjectCircuitInfo.get("回路总长度");

        Map<String, String> baseCostMap = new HashMap<>();
        baseCostMap.put("总成本", "base");
        baseCostMap.put("总重量", "base");
        baseCostMap.put("总长度", "base");
        Map<String, String> baaseAddress = new HashMap<>();
        for (String s : electricalList) {
            baaseAddress.put(s, findNode(s, appPositions));
        }
        Map<String, Object> baseMap = new HashMap<>();
        baseMap.put("成本", baseCostMap);
        baseMap.put("address", baaseAddress);
        baseMap.put("number", "base");
        baseCalculateMap.put("elecOptimizeResult", baseMap);
        result.add(baseCalculateMap);


        System.out.println(allArrangements.size());
        int i = 0;

        DecimalFormat df = new DecimalFormat("0.00");
        List<Map<String, Object>> allArrangementList = new ArrayList<>();
        for (List<String> allArrangement : allArrangements) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                break;
            }

            System.out.println(i++);
            Map<String, Object> map = new HashMap<>();
            List<Map<String, Object>> copyAppPositions = (List<Map<String, Object>>) mapFile.get("appPositions");
            for (String s : electricalList) {
                for (Map<String, Object> copyAppPosition : copyAppPositions) {
                    if (copyAppPosition.get("appName").toString().equals(s)) {
                        copyAppPosition.put("unregularPointName", allArrangement.get(electricalList.indexOf(s)));
                        continue;
                    }
                }
            }
            mapFile.put("appPositions", copyAppPositions);
            String json = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile), filtration, elecFixedLocationLibrary, elecBusinessPrice);
            Map<String, Object> calculatemap = jsonToMap.TransJsonToMap(json);
            Map<String, String> cost = new HashMap<>();
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) calculatemap.get("projectCircuitInfo");
            calculatemap.put("topoId", TopoId);
            calculatemap.put("caseId", CaseId);
            cost.put("总成本", df.format((Double) projectCircuitInfo.get("总成本") - baseCost));
            cost.put("总重量", df.format((Double) projectCircuitInfo.get("回路总重量") - baseWeight));
            cost.put("总长度", df.format((Double) projectCircuitInfo.get("回路总长度") - baseLength));
            Map<String, String> address = new HashMap<>();
            for (String s : electricalList) {
                address.put(s, allArrangement.get(electricalList.indexOf(s)));
            }
            map.put("address", address);
            map.put("number", "0");
            map.put("成本", cost);
            calculatemap.put("elecOptimizeResult", map);
            calculatemap.put("成本", cost);
            allArrangementList.add(calculatemap);
        }
        List<Map<String, Object>> cost = findBest.findBest(allArrangementList, "成本", TopNumber);
        List<Map<String, Object>> restore = restore(cost, initmapFile);
        result.addAll(restore);
        allArrangementList = null;
        System.gc();
        return result;
    }

    /**
     * @Description: 对最优的方案进行一个还原(通过字典计算出来的最优方案 ， 缺少信息)
     * @input: originalMap 通过字典取得的最优方案
     * @input: initmapFile 初始化文件
     * @Return: 按照一定格式处理完成的最优方案
     */
    public List<Map<String, Object>> restore(List<Map<String, Object>> originalMap, Map<String, Object> initmapFile) throws Exception {
        List<Map<String, Object>> resultList = new ArrayList<>();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        int i = 1;
        for (Map<String, Object> map : originalMap) {
            System.out.println("i=" + i);
            i++;
            Map<String, Object> mapFile = deepCopy(initmapFile);
            List<Map<String, Object>> copyAppPositions = (List<Map<String, Object>>) mapFile.get("appPositions");
            Map<String, Object> elecOptimizeResult = (Map<String, Object>) map.get("elecOptimizeResult");
            Map<String, String> address = (Map<String, String>) elecOptimizeResult.get("address");
            for (Map<String, Object> copyAppPosition : copyAppPositions) {
                if (address.containsKey(copyAppPosition.get("appName").toString())) {
                    copyAppPosition.put("unregularPointName", address.get(copyAppPosition.get("appName").toString()));
                }
            }
            mapFile.put("appPositions", copyAppPositions);
            String s = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(mapFile));
            Map<String, Object> result = jsonToMap.TransJsonToMap(s);
            result.put("topoId", TopoId);
            result.put("caseId", CaseId);
            result.put("elecOptimizeResult", elecOptimizeResult);
            resultList.add(result);
        }
        return resultList;
    }

    //    对用电器进行一个分组
    public List<List<String>> elecGroup(List<String> elecName, List<List<String>> correlationElec) {
        Map<String, List<String>> graph = new HashMap<>();
        for (List<String> relation : correlationElec) {
            String node1 = relation.get(0);
            String node2 = relation.get(1);
            graph.computeIfAbsent(node1, k -> new ArrayList<>()).add(node2);
            graph.computeIfAbsent(node2, k -> new ArrayList<>()).add(node1);
        }

        // 记录节点是否已被访问过，避免重复访问
        Map<String, Boolean> visited = new HashMap<>();
        for (String elec : elecName) {
            visited.put(elec, false);
        }

        List<List<String>> groups = new ArrayList<>();
        for (String elec : elecName) {
            if (!visited.get(elec)) {
                List<String> group = new ArrayList<>();
                dfs(elec, graph, visited, group);
                groups.add(group);
            }
        }

        return groups;
    }

    //这段代码实现了深度优先搜索（DFS）算法，用于遍历图结构
    private static void dfs(String node, Map<String, List<String>> graph, Map<String, Boolean> visited, List<String> group) {
        visited.put(node, true);
        group.add(node);
        if (graph.containsKey(node)) {
            List<String> neighbors = graph.get(node);
            for (String neighbor : neighbors) {
                if (!visited.get(neighbor)) {
                    dfs(neighbor, graph, visited, group);
                }
            }
        }
    }

    /**
     * @Description: 判断targetList 是否在 listOfLists中
     * @input: targetList
     * @input: listOfLists
     * @Return: 返回true  或者 false
     */
    public boolean containsList(List<String> targetList, List<List<String>> listOfLists) {
        for (List<String> list : listOfLists) {
            if (list.equals(targetList)) {
                return true;
            }
        }
        return false;
    }

    // 递归方法进行排列
    private static void arrange(List<String> electricalList, Map<String, List<String>> elecChangeablePosition, int index, List<String> currentArrangement, List<List<String>> allArrangements) {
        if (index == electricalList.size()) {
            allArrangements.add(new ArrayList<>(currentArrangement));
            return;
        }
        // 获取当前电器可能的位置列表
        List<String> positions = elecChangeablePosition.get(electricalList.get(index));
        for (String position : positions) {
            currentArrangement.add(position);
            arrange(electricalList, elecChangeablePosition, index + 1, currentArrangement, allArrangements);
            currentArrangement.remove(currentArrangement.size() - 1);
        }
    }


    /**
     * @Description: 对传入的内容进行一个深拷贝
     * @Return: 返回一个传入的内容
     */
    public static Map<String, Object> deepCopy(Map<String, Object> originalMap) throws Exception {
        Map<String, Object> copiedMap = new HashMap<>(originalMap);
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) originalMap.get("appPositions");

        List<Map<String, Object>> newList = new ArrayList<>();
        for (Map<String, Object> original : appPositions) {
            Map<String, Object> newMap = new HashMap<>();
            for (String key : original.keySet()) {
                newMap.put(key, original.get(key));
            }
            newList.add(newMap);
        }

        copiedMap.put("appPositions", newList);
        return copiedMap;

    }

    /**
     * @Description: 根据用电器名称获取对应的位置点名称
     * @input: appName  用电器名称
     * @input: appPositions  用电器位置信息
     * @Return: 返回接收到用电器名称对应的位置
     */
    public String findNode(String appName, List<Map<String, Object>> appPositions) {

        for (Map<String, Object> appPosition : appPositions) {
            if (appPosition.get("appName").toString().equals(appName)) {
                if (appPosition.get("unregularPointName") != null) {
                    return appPosition.get("unregularPointName").toString();
                } else if (appPosition.get("unregularPointName") == null && appPosition.get("regularPointName") != null) {
                    return appPosition.get("regularPointName").toString();
                } else if (appPosition.get("unregularPointName") == null && appPosition.get("regularPointName") == null) {
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * @Description: 根据位置id获取对应的位置点名称
     * @input: id  位置id
     * @input: appPositions  用电器位置信息
     * @Return: 返回接收到用电器id对应的位置名称
     */
    public String findNameById(String id, List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if (point.get("id").toString().equals(id)) {
                return point.get("pointName").toString();
            }
        }
        return "";
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
     * @Description 判断所在点的干湿
     * @input name 路径点名称
     * @inputExample 前围板外中点
     * @input maps 所有端点信息
     * @Return 端点的干湿状态 W
     */
    public String getWaterParam(String name, List<Map<String, String>> maps) {
        for (Map<String, String> map : maps) {
            if (name.equals(map.get("端点名称"))) {
                return map.get("端点干湿");
            }
        }
        return null;
    }

    /**
     * @Description: 对随机生成的方案进行变异
     * @input: electricalAddressList 随机生成的方案
     * @input: electricalList 可变用电器
     * @input: newElecChangeablePosition 可变用电器 以及对应的可变位置
     * @Return: 返回变异后的方案
     */
    public List<List<String>> variation(List<List<String>> electricalAddressList, List<String> electricalList, Map<String, List<String>> newElecChangeablePosition) {
        Random random = new Random();
        List<List<String>> result = new ArrayList<>();
        for (List<String> list : electricalAddressList) {
            for (String s : electricalList) {
                List<String> address = newElecChangeablePosition.get(s);
                List<String> collect = list.stream().collect(Collectors.toList());
                collect.set(electricalList.indexOf(s), address.get(random.nextInt(address.size())));
                if (!containsList(collect, result) && !containsList(collect, WareHouse)) {
                    result.add(collect);
                }
            }
        }
        return result;
    }

    //    交叉方法
    public List<List<String>> intersect(List<List<String>> list) {
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            List<String> firstList = list.get(i);
            for (int j = 0; j < list.size(); j++) {
                if (i != j) {
                    List<String> comparisonList = list.get(j);
                    for (int g = 0; g < firstList.size(); g++) {
                        List<String> firstListCopy = new ArrayList<>(firstList);
                        if (!firstList.get(g).equals(comparisonList.get(g))) {
                            firstListCopy.set(g, comparisonList.get(g));
                            if (!containsList(firstListCopy, result) && !containsList(firstListCopy, WareHouse)) {
                                result.add(firstListCopy);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}
