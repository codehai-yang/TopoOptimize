package HarnessPackOpti.Optimize.elec;

import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.FindElecLocation;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.PowerProjectCircuitInfoOutput;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配电驱动优化
 */
public class PowerDistributionDriveOptimization {

    //    当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;
    private static Integer TopNumber = 20;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    // 可变数量阈值，走枚举
    public static Integer caseNumbe = 10000;

    //生成初始样本数量限制
    public static Integer LessRandomSamleNumber = 20;

    //遗传最优样本重复次数
    public static Integer BestRepetitionNumber = 0;

    //    每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();

    //遗传迭代重复的次数限值
    public static Integer IterationRestrictNumber = 30;

    //遗传每轮迭代最少样本数量
    public static Integer HybridizationLessRandomSamleNumber = 200;

    //遗传算法数量不够时自动补全得次数
    public static Integer AutoCompleteNumber = 30;

    //遗传算法每轮变异的次数
    public static Integer VariationNumber = 1;

    // 交叉概率（0.7 表示 70% 的方案参与交叉）
    public static Double CrossoverRate = 0.7;

    //定义一个仓库，遗传每次生成的方案存储，防止重复
    public static Set<String> WareHouse = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 枚举收集的所有方案
    private List<Map<String, String>> enumeratedSchemes = new ArrayList<>();

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance();
    }

    public List<Map<String, Object>> powerDriverOptimize(String jsonContent) throws Exception {
        long categoryTime = System.currentTimeMillis();
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        PowerProjectCircuitInfoOutput powerProjectCircuitInfoOutput = new PowerProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, Object>> points = (List<Map<String, Object>>) jsonMap.get("points");
        Map<String, String> projectInfo = (Map<String, String>) jsonMap.get("projectInfo");
        CaseId = caseInfo.get("id").toString();
        optimizeRecordId = optimizeRecord.get("id").toString();
        optimizeStopStatusStore.setKey(optimizeRecordId);

        // 整车信息计算(初始方案)
        String originalResult = powerProjectCircuitInfoOutput.powerOptimize(jsonContent);
        // 判断是哪种类型优化
        String optimizeType = projectInfo.get("optimizeType");
        String[] split = optimizeType.split(",");
        List<String> typeList = Arrays.asList(split);
        Random random = new Random();
        // 是否开启直连接口
        boolean whetherToChange = projectInfo.get("whetherToChange") != null
                && projectInfo.get("whetherToChange").equals("true");

        // 主供电回路和配电回路
        List<Map<String, String>> elecLoopList = new ArrayList<>();
        // 驱动回路
        List<Map<String, String>> driveLoopList = new ArrayList<>();
        // 资源数量读取：{"2","5","不限"}分别对应大，中，小电流
        Map<String, List<String>> resourceNum = new HashMap<>();
        // 组团一起变：groupId → [loopId, ...]
        Map<String, List<String>> togetherGroup = new HashMap<>();
        // 互斥组：mutualId → [loopId, ...]
        Map<String, List<String>> mutualGroup = new HashMap<>();
        // 用电器 appId → appName
        Map<String, String> elecNameId = new HashMap<>();
        //位置点名称-id
        Map<String, String> pointNameId = new HashMap<>();
        //回路id-可连接的用电器列表
        Map<String, Set<String>> loopElecById = new HashMap<>();
        FindBest findBest = new FindBest();

        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            strPointName.add(edge.get("startPointName").toString());
            endPointName.add(edge.get("endPointName").toString());
            pointNameId.put(edge.get("startPointName").toString(), edge.get("startPointId").toString());
            pointNameId.put(edge.get("endPointName").toString(), edge.get("endPointId").toString());
            if (edge.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }

        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);
        adjacencyMatrixGraph.adjacencyMatrix();
        adjacencyMatrixGraph.addEdge();
        adjacencyMatrixGraph.getAdj();
        List<String> allPoint = adjacencyMatrixGraph.getAllPoint();

        //查找用电器自身位置点
        Map<String, String> eleclection = getEleclection(appPositions);

        // 先收集直连接口分组（需要在构建 elecChangeablePosition 之前）
        Map<String, List<String>> interfaceCodegroup = new HashMap<>();
        Set<String> pointNameSet = new HashSet<>();
        if (whetherToChange) {
            for (Map<String, Object> point : points) {
                if (point.get("interfaceCode") != null
                        && !point.get("interfaceCode").toString().trim().isEmpty()) {
                    String interfaceCode = point.get("interfaceCode").toString();
                    String pointName = point.get("pointName").toString();
                    interfaceCode = interfaceCode.substring(0, interfaceCode.length() - 1);
                    interfaceCodegroup.computeIfAbsent(interfaceCode, k -> new ArrayList<>()).add(pointName);
                    pointNameSet.add(pointName);
                }
            }
        }

        Map<String, List<String>> elecChangeablePosition = new HashMap<>();
        for (Map<String, String> appPosition : appPositions) {
            String appName = appPosition.get("appName");
            if (resourceNum.get(appName) == null) {
                List<String> list = objectMapper.readValue(
                        appPosition.get("resourceNumb"), new TypeReference<List<String>>() {});
                resourceNum.put(appName, list);
            }
            if ("1".equals(appPosition.get("changeType"))) {
                List<String> list = new ArrayList<>();
                String sp = appPosition.get("specifyPoints");
                if (sp != null && !sp.isEmpty()) {
                    for (String part : sp.split(",")) {
                        String pointName = findNameById(part, points);
                        list.add(pointName);

                        // 【关键修复】如果该位置点是直连接口，将整个接口组的位置都加入
                        if (whetherToChange && pointNameSet.contains(pointName)) {
                            // 查找该位置点属于哪个接口组
                            for (List<String> interfacePoints : interfaceCodegroup.values()) {
                                if (interfacePoints.contains(pointName)) {
                                    // 将整个接口组的位置都加入可变列表
                                    for (String interfacePoint : interfacePoints) {
                                        if (!list.contains(interfacePoint) && allPoint.contains(interfacePoint)) {
                                            list.add(interfacePoint);
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
                list.retainAll(allPoint);
                list.add(eleclection.get(appName));     //把自身位置加进去
                elecChangeablePosition.put(appName, list);
            } else if ("2".equals(appPosition.get("changeType"))) {
                elecChangeablePosition.put(appName, new ArrayList<>(allPoint));
            }
            elecNameId.put(appPosition.get("appId"), appName);
        }

        //统计约束list集合，方便后面判断回路是否有约束
        List<String> togetherList = new ArrayList<>();
        List<String> mutualList = new ArrayList<>();
        for (Map<String, String> loopInfo : loopInfos) {
            if ("主供电回路".equals(loopInfo.get("loopAttribute"))
                    || "配电回路".equals(loopInfo.get("loopAttribute"))) {
                elecLoopList.add(loopInfo);
            } else if ("驱动回路".equals(loopInfo.get("loopAttribute"))) {
                driveLoopList.add(loopInfo);
            }
            //回路可连接的终点用电器统计
            String s = loopInfo.get("specifyPoints");
            if (s != null && !s.isEmpty()) {
                for (String part : s.split(",")) {
                    String pointName = findNameById(part, points);
                    loopElecById.computeIfAbsent(loopInfo.get("id"), k -> new HashSet<>()).add(pointName);
                }
            }
            // 组团归组
            String ct = loopInfo.get("changeTogether");
            if (ct != null && !ct.isEmpty()) {
                togetherGroup.computeIfAbsent(ct, k -> new ArrayList<>()).add(loopInfo.get("id"));
                togetherList.add(loopInfo.get("id"));
            }
            // 互斥归组
            String me = loopInfo.get("mutualExclusion");
            if (me != null && !me.isEmpty()) {
                mutualGroup.computeIfAbsent(me, k -> new ArrayList<>()).add(loopInfo.get("id"));
                mutualList.add(loopInfo.get("id"));
            }
        }
        System.out.println("回路分类耗时:" + (System.currentTimeMillis() - categoryTime));

        // 枚举模式：计算带约束的方案总数
        // 优化类型 1：控制器回路
        long combinationsTime = System.currentTimeMillis();
        long combinations = 0;

        //同时优化配电回路和驱动器回路
        List<Map<String, String>> combinedList = new ArrayList<>(elecLoopList);
        combinedList.addAll(driveLoopList);
        if (typeList.contains("1") && typeList.contains("2")) {
            combinations = calculateOptimizationCombinations(combinedList, elecChangeablePosition, togetherGroup, loopElecById);
        }

        //优化驱动回路
        if ("1".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(driveLoopList, elecChangeablePosition, togetherGroup, loopElecById);
        }

        // 优化类型 2：配电器回路
        if ("2".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(elecLoopList, elecChangeablePosition, togetherGroup, loopElecById);
        }

        // 优化类型 3：所有回路
        if ("3".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(loopInfos, elecChangeablePosition, togetherGroup, loopElecById);
        }

        System.out.println("枚举模式耗时:" + (System.currentTimeMillis() - combinationsTime));
        System.out.println("总方案数: " + combinations);

        // 如果方案数在限制内，进行枚举生成方案列表
        if (combinations <= caseNumbe) {
            long enumerateTime = System.currentTimeMillis();
            enumeratedSchemes.clear();

            // 根据优化类型选择目标回路并枚举
            List<Map<String, String>> targetLoops = null;
            if (typeList.contains("1") && typeList.contains("2")) {
                targetLoops = combinedList;
            } else if ("1".equals(optimizeType)) {
                targetLoops = driveLoopList;
            } else if ("2".equals(optimizeType)) {
                targetLoops = elecLoopList;
            } else if ("3".equals(optimizeType)) {
                targetLoops = loopInfos;
            }
            List<Map<String, Object>> resultList = new ArrayList<>();
            int duplicateCount = 0; // 统计重复方案数
            int validSchemeCount = 0; // 统计有效方案数

            if (targetLoops != null && !targetLoops.isEmpty()) {
                // 执行枚举
                enumerateAllSchemes(targetLoops, elecChangeablePosition, togetherGroup, mutualGroup, loopInfos,loopElecById);

                System.out.println("枚举耗时: " + (System.currentTimeMillis() - enumerateTime) + "ms");

                // 每个方案格式: Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
                // 遍历所有方案
                for (int i = 0; i < enumeratedSchemes.size(); i++) {
                    Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);
                    Map<String, String> scheme = enumeratedSchemes.get(i);

                    //存储指定连接关系和用电器位置的数据以方便后续去重
                    List<Map<String,String>> connectionData = new ArrayList<>();
                    List<Map<String,String>> appPositionsData = new ArrayList<>();

                    List<Map<String, String>> loopInfoCopy = new ArrayList<>();
                    for (Map<String, String> loop : loopInfos) {
                        loopInfoCopy.add(new HashMap<>(loop));
                    }
                    List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(appPositions);

                    // 遍历该方案中的所有回路，还原方案，计算成本
                    for (Map.Entry<String, String> entry : scheme.entrySet()) {
                        String loopId = entry.getKey();           // 回路ID
                        String value = entry.getValue();          // "BCM|CPM|前围板外中点|车身线左前inline点"

                        // 解析value
                        String[] parts = value.split("\\|");
                        String startApp = parts[0];    // 起点用电器: "BCM"
                        String endApp = parts[1];      // 终点用电器: "CPM"
                        String startPos = parts[2];    // 起点位置: "前围板外中点"
                        String endPos = parts[3];      // 终点位置: "车身线左前inline点"

                        //方案还原
                        for (Map<String, String> loop : loopInfoCopy) {
                            if (loop.get("id").equals(loopId)) {
                                loop.put("startApp", startApp);
                                loop.put("endApp", endApp);
                                Map<String, String> loopCopy = new HashMap<>();
                                loopCopy.put("id", loop.get("id"));
                                loopCopy.put("startApp", startApp);
                                loopCopy.put("endApp", endApp);
                                connectionData.add(loopCopy);
                            }
                        }

                        for (Map<String, String> stringStringMap : appPositionsCopy) {
                            if(stringStringMap.get("appName").equals(startApp)){
                                stringStringMap.put("unregularPointName", startPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(startPos));
                                Map<String, String> appPositionCopy = new HashMap<>();
                                appPositionCopy.put("appName", stringStringMap.get("appName"));
                                appPositionCopy.put("unregularPointName", stringStringMap.get("unregularPointName"));
                                appPositionsData.add(appPositionCopy);
                            }
                            if(stringStringMap.get("appName").equals(endApp)){
                                stringStringMap.put("unregularPointName", endPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(endPos));
                                Map<String, String> appPositionCopy = new HashMap<>();
                                appPositionCopy.put("appName", stringStringMap.get("appName"));
                                appPositionCopy.put("unregularPointName", stringStringMap.get("unregularPointName"));
                                appPositionsData.add(appPositionCopy);
                            }
                        }
                    }
                    //字符串拼接(只传入用户需要优化的回路和用电器)
                    String fingerprint = generateSchemeFingerprint(connectionData, appPositionsData);
                    // 方案去重检查
                    if (WareHouse.contains(fingerprint)) {
                        duplicateCount++;
                        continue; // 跳过重复方案
                    }
                    //资源数量检查，和位置无关
                    Boolean aBoolean = elecResourceCheck(loopInfoCopy,resourceNum);
                    //不符合约束的方案跳过
                    if(!aBoolean){
                        continue;
                    }

                    // 添加到去重仓库
                    WareHouse.add(fingerprint);
                    validSchemeCount++;
                    //合成方案
                    jsonMapCopy.put("loopInfo", loopInfoCopy);
                    jsonMapCopy.put("appPositions", appPositionsCopy);
                    //计算成本
                    String s = powerProjectCircuitInfoOutput.powerOptimize(jsonContent);
                    Map<String, Object> map = jsonToMap.TransJsonToMap(s);
                    Map<String, Object> projectCircuitInfo = (Map<String, Object>) map.get("projectCircuitInfo");
                    Map<String, Double> projectCost = new HashMap<>();
                    projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
                    projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
                    projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

                    map.put("成本", projectCost);
                    map.put("topoId", topoInfoMap.get("id").toString());
                    map.put("caseId", projectInfo.get("caseId"));
                    map.put("finishStatue", "normal");
                    map.put("initializationScheme", false);
                    resultList.add(map);
                }
            }
            System.out.println("重复方案数: " + duplicateCount);
            System.out.println("有效方案数: " + validSchemeCount);
            //枚举返回最优top20样本
            List<Map<String, Object>> topBest = findBest.findBest(resultList, "成本",TopNumber);
            return topBest;
        }

        //开始生成初代样本
        // 根据优化类型选择目标回路
        List<Map<String, String>> targetLoops = null;
        if (typeList.contains("1") && typeList.contains("2")) {
            targetLoops = combinedList;
        } else if ("1".equals(optimizeType)) {
            targetLoops = driveLoopList;
        } else if ("2".equals(optimizeType)) {
            targetLoops = elecLoopList;
        } else if ("3".equals(optimizeType)) {
            targetLoops = loopInfos;
        }
        long gaInitTime = System.currentTimeMillis();
        List<Map<String, Object>> topBest = new ArrayList<>();
        if (targetLoops != null && !targetLoops.isEmpty()) {
            List<Map<String, Object>> initialPopulation = generateInitialPopulation(
                    LessRandomSamleNumber,
                    targetLoops,
                    loopInfos,
                    appPositions,
                    elecChangeablePosition,
                    togetherGroup,
                    mutualGroup,
                    pointNameId,
                    objectMapper,
                    powerProjectCircuitInfoOutput,
                    jsonToMap,
                    topoInfoMap,
                    projectInfo,
                    loopElecById,
                    resourceNum
            );

            System.out.println("初代样本生成耗时: " + (System.currentTimeMillis() - gaInitTime) + "ms");
            System.out.println("有效初代样本数: " + initialPopulation.size());

            // 找出top20
            if (!initialPopulation.isEmpty()) {
                 topBest = findBest.findBest(initialPopulation, "成本", TopNumber);
                //加入初始方案
                topBest.add(jsonToMap.TransJsonToMap(originalResult));
            }
        }

        //遗传算法
        int hybridizationNumber = 0;
        List<Map<String, Object>> currentTopBest = topBest; // 初始 Top20

        while (true) {
            System.out.println((hybridizationNumber + 1) + "代迭代开始");

            // 检查是否应该停止优化
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                System.out.println("优化被用户中断");
                break;
            }

            // Step 1: 交叉操作 - 对 Top20 方案进行随机配对交叉
            List<Map<String, Object>> crossedSchemes = crossoverTopSchemes(
                    currentTopBest,
                    targetLoops,
                    loopInfos,
                    appPositions,
                    elecChangeablePosition,
                    togetherGroup,
                    mutualGroup,
                    pointNameId,
                    objectMapper,
                    powerProjectCircuitInfoOutput,
                    jsonToMap,
                    topoInfoMap,
                    projectInfo,
                    loopElecById,
                    random,
                    resourceNum
            );

            System.out.println("交叉生成 " + crossedSchemes.size() + " 个方案");


            // Step 2: 变异操作 - 对当前 Top20 和交叉方案进行变异
            List<Map<String, Object>> allSchemesForMutation = new ArrayList<>(currentTopBest);
            allSchemesForMutation.addAll(crossedSchemes);

            List<Map<String, Object>> mutatedSchemes = mutateTopSchemes(
                    allSchemesForMutation,
                    targetLoops,
                    loopInfos,
                    appPositions,
                    elecChangeablePosition,
                    togetherGroup,
                    mutualGroup,
                    pointNameId,
                    objectMapper,
                    powerProjectCircuitInfoOutput,
                    jsonToMap,
                    topoInfoMap,
                    projectInfo,
                    loopElecById,
                    random,
                    resourceNum
            );
            System.out.println("变异生成 " + mutatedSchemes.size() + " 个方案");

            if (mutatedSchemes.isEmpty()) {
                System.out.println("第" + (hybridizationNumber + 1) + "代未生成有效方案，继续下一轮");
                hybridizationNumber++;
                continue;
            }

            // 合并所有方案：原始Top20 + 交叉方案 + 变异方案
            List<Map<String, Object>> resuliList = new ArrayList<>(currentTopBest);
            resuliList.addAll(crossedSchemes);
            resuliList.addAll(mutatedSchemes);
            // 【关键】检查方案数量是否达到最低要求，如果不够则补充
            int numb = 0;
            while (resuliList.size() < HybridizationLessRandomSamleNumber) {
                int need = HybridizationLessRandomSamleNumber - resuliList.size();
                System.out.println("方案数量不足，需要补充 " + need + " 个方案");

                // 调用初代生成方法补充方案
                List<Map<String, Object>> supplementedSchemes = generateInitialPopulation(
                        need,
                        targetLoops,
                        loopInfos,
                        appPositions,
                        elecChangeablePosition,
                        togetherGroup,
                        mutualGroup,
                        pointNameId,
                        objectMapper,
                        powerProjectCircuitInfoOutput,
                        jsonToMap,
                        topoInfoMap,
                        projectInfo,
                        loopElecById,
                        resourceNum
                );
                numb++;
                if(numb > AutoCompleteNumber){
                    break;
                }
                resuliList.addAll(supplementedSchemes);
            }
            // 按成本排序，选出新的 Top20
            currentTopBest = findBest.findBest(resuliList, "成本", TopNumber);
            System.out.println("第" + (hybridizationNumber + 1) + "代完成，最优成本: " +
                    currentTopBest.get(0).get("成本"));

            if (hybridizationNumber == 1) {
                double costTotal = Double.parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double.parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double.parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总重量").toString());
                BestCost.put("总成本", costTotal);
                BestCost.put("总长度", costLenth);
                BestCost.put("总重量", costWeight);
            } else {
                //获取当前最优解的各项指标
                double costTotal = Double.parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double.parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double.parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总重量").toString());
//            当前最优解中的长度判断当前的成本、长度、重量是都一样
                //判断是否与历史最优解基本相同（允许微小误差)
                if (Math.abs(BestCost.get("总成本") - costTotal) < 0.000001
                        && Math.abs(BestCost.get("总长度") - costLenth) < 0.000001
                        && Math.abs(BestCost.get("总重量") - costWeight) < 0.000001) {
                    BestRepetitionNumber = BestRepetitionNumber + 1;        //相同则计数器加1
                } else {
                    BestRepetitionNumber = 0;           //不同则重置计数器
                    //更新历史最优解
                    BestCost.put("总成本", costTotal);
                    BestCost.put("总长度", costLenth);
                    BestCost.put("总重量", costWeight);
                }
            }
            if (BestRepetitionNumber == IterationRestrictNumber) {
                System.out.println("迭代次数达到限制，后续与上一代结果相同达到30次");
                break;
            }

            hybridizationNumber++;
        }

        System.out.println("遗传算法完成，共迭代 " + hybridizationNumber + " 代");
        return currentTopBest;
    }

    /**
     * 资源连接数量检查
     * @param loopInfos 生成的方案回路列表
     * @param resourceNum 前端传入的每个用电器的电流限制 {"ECU": ["2", "5", "不限"]}
     * @return true-满足约束，false-不满足约束
     */
    public Boolean elecResourceCheck(List<Map<String, String>> loopInfos, Map<String, List<String>> resourceNum){
        // 存储现有方案中每个用电器的大、中、小电流回路数量
        // 结构: {"ECU": {"large": 3, "medium": 2, "small": 1}}
        Map<String, Map<String, Integer>> currentResource = new HashMap<>();

        // 找出哪些用电器有资源限制
        Set<String> restrictedApps = resourceNum.keySet();

        // 遍历方案中的所有回路，统计每个用电器的电流类型数量
        for (Map<String, String> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp");
            String endApp = loopInfo.get("endApp");
            String wireType = loopInfo.get("loopWireway");

            if (wireType == null || wireType.isEmpty()) {
                continue;
            }

            // 获取方铜数量，判断电流类型
            // split[1]是一个数字，>=6表示大电流，>2且<6表示中电流，<=2表示小电流
            String[] split = wireType.split(" ");
            if (split.length < 2) {
                continue;
            }

            String currentType;
            try {
                int copperCount = Integer.parseInt(split[1]);
                if (copperCount >= 6) {
                    currentType = "large";
                } else if (copperCount > 2) {
                    currentType = "medium";
                } else {
                    currentType = "small";
                }
            } catch (NumberFormatException e) {
                // 如果解析失败，跳过该回路
                continue;
            }

            // 统计起点用电器的电流类型数量
            if (restrictedApps.contains(startApp)) {
                currentResource.computeIfAbsent(startApp, k -> new HashMap<>());
                Map<String, Integer> appResource = currentResource.get(startApp);
                appResource.merge(currentType, 1, Integer::sum);
            }

            // 统计终点用电器的电流类型数量
            if (restrictedApps.contains(endApp)) {
                currentResource.computeIfAbsent(endApp, k -> new HashMap<>());
                Map<String, Integer> appResource = currentResource.get(endApp);
                appResource.merge(currentType, 1, Integer::sum);
            }
        }

        // 检查每个有限制的用电器是否满足资源约束
        for (String appName : restrictedApps) {
            List<String> limits = resourceNum.get(appName);
            if (limits == null || limits.size() < 3) {
                continue;
            }

            // 获取该用电器实际的电流回路数量
            Map<String, Integer> actualResource = currentResource.getOrDefault(appName, new HashMap<>());
            int actualLarge = actualResource.getOrDefault("large", 0);
            int actualMedium = actualResource.getOrDefault("medium", 0);
            int actualSmall = actualResource.getOrDefault("small", 0);

            // 检查大电流限制
            String largeLimit = limits.get(0);
            if (!"不限".equals(largeLimit) && !largeLimit.isEmpty()) {
                try {
                    int maxLarge = Integer.parseInt(largeLimit);
                    if (actualLarge > maxLarge) {
                        System.out.println("用电器 " + appName + " 大电流超限: 实际" + actualLarge + " > 限制" + maxLarge);
                        return false;
                    }
                } catch (NumberFormatException e) {
                    // 如果不是数字且不是"不限"，跳过
                }
            }

            // 检查中电流限制
            String mediumLimit = limits.get(1);
            if (!"不限".equals(mediumLimit) && !mediumLimit.isEmpty()) {
                try {
                    int maxMedium = Integer.parseInt(mediumLimit);
                    if (actualMedium > maxMedium) {
                        System.out.println("用电器 " + appName + " 中电流超限: 实际" + actualMedium + " > 限制" + maxMedium);
                        return false;
                    }
                } catch (NumberFormatException e) {
                    // 如果不是数字且不是"不限"，跳过
                }
            }

            // 检查小电流限制
            String smallLimit = limits.get(2);
            if (!"不限".equals(smallLimit) && !smallLimit.isEmpty()) {
                try {
                    int maxSmall = Integer.parseInt(smallLimit);
                    if (actualSmall > maxSmall) {
                        System.out.println("用电器 " + appName + " 小电流超限: 实际" + actualSmall + " > 限制" + maxSmall);
                        return false;
                    }
                } catch (NumberFormatException e) {
                    // 如果不是数字且不是"不限"，跳过
                }
            }
        }

        // 所有用电器都满足约束
        return true;
    }

    /**
     * 交叉操作：对 Top 方案进行随机配对交叉
     */
    private List<Map<String, Object>> crossoverTopSchemes(
            List<Map<String, Object>> topSchemes,
            List<Map<String, String>> targetLoops,
            List<Map<String, String>> allLoopInfos,
            List<Map<String, String>> allAppPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            ObjectMapper objectMapper,
            PowerProjectCircuitInfoOutput powerProjectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> topoInfoMap,
            Map<String, String> projectInfo,
            Map<String, Set<String>> loopElecById,
            Random random,Map<String, List<String>> resourceNum) throws Exception {

        List<Map<String, Object>> crossedSchemes = new ArrayList<>();
        int populationSize = topSchemes.size();

        System.out.println("开始交叉操作，种群大小: " + populationSize);

        // 随机打乱方案顺序
        List<Map<String, Object>> shuffledSchemes = new ArrayList<>(topSchemes);
        Collections.shuffle(shuffledSchemes, random);

        // 两两配对进行交叉
        for (int i = 0; i < shuffledSchemes.size() - 1; i += 2) {
            // 以一定概率进行交叉
            if (random.nextDouble() > CrossoverRate) {
                continue;
            }

            Map<String, Object> parent1 = shuffledSchemes.get(i);
            Map<String, Object> parent2 = shuffledSchemes.get(i + 1);

            // 执行均匀交叉，生成两个子代
            Map<String, Object> child1 = uniformCrossover(
                    parent1, parent2, targetLoops, allLoopInfos, allAppPositions,
                    elecChangeablePosition, togetherGroup, mutualGroup,
                    pointNameId, objectMapper, powerProjectCircuitInfoOutput,
                    jsonToMap, topoInfoMap, projectInfo, loopElecById, random,resourceNum);

            Map<String, Object> child2 = uniformCrossover(
                    parent2, parent1, targetLoops, allLoopInfos, allAppPositions,
                    elecChangeablePosition, togetherGroup, mutualGroup,
                    pointNameId, objectMapper, powerProjectCircuitInfoOutput,
                    jsonToMap, topoInfoMap, projectInfo, loopElecById, random,resourceNum);

            if (child1 != null) {
                crossedSchemes.add(child1);
            }
            if (child2 != null) {
                crossedSchemes.add(child2);
            }
        }

        return crossedSchemes;
    }

    /**
     * 均匀交叉：每个目标回路随机从父本1或父本2继承
     */
    private Map<String, Object> uniformCrossover(
            Map<String, Object> parent1,
            Map<String, Object> parent2,
            List<Map<String, String>> targetLoops,
            List<Map<String, String>> allLoopInfos,
            List<Map<String, String>> allAppPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            ObjectMapper objectMapper,
            PowerProjectCircuitInfoOutput powerProjectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> topoInfoMap,
            Map<String, String> projectInfo,
            Map<String, Set<String>> loopElecById,
            Random random,Map<String, List<String>> resourceNum) throws Exception {

        // 深拷贝父本数据
        List<Map<String, String>> parent1Loops = (List<Map<String, String>>) parent1.get("loopInfos");
        List<Map<String, String>> parent2Loops = (List<Map<String, String>>) parent2.get("loopInfos");
        List<Map<String, String>> parent1Apps = (List<Map<String, String>>) parent1.get("appPositions");
        List<Map<String, String>> parent2Apps = (List<Map<String, String>>) parent2.get("appPositions");

        // 以父本1为基础创建子代
        List<Map<String, String>> childLoops = deepCopyLoopInfos(parent1Loops);
        List<Map<String, String>> childApps = deepCopyAppPositions(parent1Apps);

        // 构建子代回路查找表
        Map<String, Map<String, String>> childLoopById = new HashMap<>();
        for (Map<String, String> loop : childLoops) {
            childLoopById.put(loop.get("id"), loop);
        }

        // 构建父本2回路查找表
        Map<String, Map<String, String>> parent2LoopById = new HashMap<>();
        for (Map<String, String> loop : parent2Loops) {
            parent2LoopById.put(loop.get("id"), loop);
        }

        // 对每个目标回路，50% 概率从父本2继承
        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");

            if (random.nextDouble() > 0.5) {
                // 从父本2继承
                Map<String, String> p2Loop = parent2LoopById.get(loopId);
                Map<String, String> childLoop = childLoopById.get(loopId);

                if (p2Loop != null && childLoop != null) {
                    childLoop.put("startApp", p2Loop.get("startApp"));
                    childLoop.put("endApp", p2Loop.get("endApp"));
                }
            }
        }

        // 处理联动组约束：如果回路属于联动组，确保组内所有回路的 endApp 一致
        enforceTogetherGroupConstraints(childLoops, childApps, togetherGroup, loopElecById, random);

        // 处理互斥组约束：确保互斥组的回路 endApp 不同
        boolean success = enforceMutualGroupConstraints(childLoops, childApps, mutualGroup,
                loopElecById, elecChangeablePosition, pointNameId, random);

        if (!success) {
            return null; // 约束冲突，返回 null
        }
        //资源限制检查
        Boolean b = elecResourceCheck(childLoops, resourceNum);
        if(!b){
            return null;
        }
        // 【关键】生成方案指纹，检查是否与历史方案重复
        String fingerprint = generateSchemeFingerprint(childLoops, childApps);

        // 检查是否在 WareHouse 中（全局去重）
        if (WareHouse.contains(fingerprint)) {
            return null; // 与历史方案重复，跳过
        }
        // 构建子代方案并计算成本
        Map<String, Object> tempJsonMap = new HashMap<>();
        tempJsonMap.put("loopInfos", childLoops);
        tempJsonMap.put("appPositions", childApps);
        tempJsonMap.put("topoInfo", topoInfoMap);
        tempJsonMap.put("projectInfo", projectInfo);

        String schemeJson = objectMapper.writeValueAsString(tempJsonMap);

        try {
            String result = powerProjectCircuitInfoOutput.powerOptimize(schemeJson);
            Map<String, Object> map = jsonToMap.TransJsonToMap(result);
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) map.get("projectCircuitInfo");

            if (projectCircuitInfo != null) {
                Map<String, Double> projectCost = new HashMap<>();
                projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
                projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
                projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

                map.put("成本", projectCost);
                map.put("topoId", topoInfoMap.get("id").toString());
                map.put("caseId", projectInfo.get("caseId"));
                map.put("finishStatue", "crossed");
                map.put("initializationScheme", false);
                // 添加到 WareHouse
                WareHouse.add(fingerprint);

                return map;
            }
        } catch (Exception e) {
            System.err.println("交叉方案计算失败: " + e.getMessage());
        }

        return null;
    }

    /**
     * 强制满足联动组约束
     */
    private void enforceTogetherGroupConstraints(
            List<Map<String, String>> childLoops,
            List<Map<String, String>> childApps,
            Map<String, List<String>> togetherGroup,
            Map<String, Set<String>> loopElecById,
            Random random) {

        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : childLoops) {
            loopById.put(loop.get("id"), loop);
        }

        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();

            // 找到组内第一个回路的 endApp 作为标准
            String standardEndApp = null;
            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null) {
                    standardEndApp = loop.get("endApp");
                    break;
                }
            }

            if (standardEndApp == null) continue;

            // 将组内所有回路的 endApp 设置为一致
            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null) {
                    loop.put("endApp", standardEndApp);
                }
            }
        }
    }

    /**
     * 强制满足互斥组约束
     */
    private boolean enforceMutualGroupConstraints(
            List<Map<String, String>> childLoops,
            List<Map<String, String>> childApps,
            Map<String, List<String>> mutualGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random) {

        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : childLoops) {
            loopById.put(loop.get("id"), loop);
        }

        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            String mutualId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();

            // 检查组内是否有重复的 endApp
            Set<String> usedEndApps = new HashSet<>();
            List<Map<String, String>> conflictedLoops = new ArrayList<>();

            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                String endApp = loop.get("endApp");
                if (usedEndApps.contains(endApp)) {
                    conflictedLoops.add(loop);
                } else {
                    usedEndApps.add(endApp);
                }
            }

            // 解决冲突：为冲突的回路重新选择 endApp
            for (Map<String, String> loop : conflictedLoops) {
                String loopId = loop.get("id");
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    continue;
                }

                // 找到一个未被使用的 endApp
                String newEndApp = null;
                for (String endApp : allowedEndApps) {
                    if (!usedEndApps.contains(endApp)) {
                        newEndApp = endApp;
                        break;
                    }
                }

                if (newEndApp != null) {
                    loop.put("endApp", newEndApp);
                    usedEndApps.add(newEndApp);

                    // 更新用电器位置
                    List<String> positions = elecChangeablePosition.get(newEndApp);
                    if (positions != null && !positions.isEmpty()) {
                        String selectedPosition = positions.get(random.nextInt(positions.size()));
                        for (Map<String, String> appPos : childApps) {
                            if (appPos.get("appName").equals(newEndApp)) {
                                appPos.put("unregularPointName", selectedPosition);
                                appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                                break;
                            }
                        }
                    }
                } else {
                    return false; // 无法解决冲突
                }
            }
        }

        return true;
    }


    /**
     * 对 Top 方案进行变异
     *
     * @param topSchemes 当前 Top20 方案列表
     * @param targetLoops 目标回路列表
     * @param allLoopInfos 所有回路信息
     * @param allAppPositions 所有用电器位置
     * @param elecChangeablePosition 用电器可变位置映射
     * @param togetherGroup 联动组配置
     * @param mutualGroup 互斥组配置
     * @param pointNameId 位置名称-ID映射
     * @param loopElecById 回路可连接的终点用电器列表
     * @param random 随机数生成器
     * @return 变异后的新方案列表
     */
    private List<Map<String, Object>> mutateTopSchemes(
            List<Map<String, Object>> topSchemes,
            List<Map<String, String>> targetLoops,
            List<Map<String, String>> allLoopInfos,
            List<Map<String, String>> allAppPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            ObjectMapper objectMapper,
            PowerProjectCircuitInfoOutput powerProjectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> topoInfoMap,
            Map<String, String> projectInfo,
            Map<String, Set<String>> loopElecById,
            Random random,Map<String, List<String>> resourceNum) throws Exception {

        List<Map<String, Object>> mutatedSchemes = new ArrayList<>();
        int mutationCount = VariationNumber; // 每个方案变异次数

        System.out.println("开始对 " + topSchemes.size() + " 个方案进行变异...");

        for (Map<String, Object> scheme : topSchemes) {
            for (int m = 0; m < mutationCount; m++) {
                // 深拷贝模板数据
                //拿到初代样本的回路和用电器位置信息
                List<Map<String, String>> loopInfos = (List<Map<String, String>>)scheme.get("loopInfos");
                List<Map<String, String>> appPositions = (List<Map<String, String>>)scheme.get("appPositions");
                List<Map<String, String>> loopInfoCopy = deepCopyLoopInfos(loopInfos);
                List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(appPositions);

                // Step 1: 对有约束的回路进行变异（抽取 30%）
                boolean success = mutateConstrainedLoops(
                        loopInfoCopy,
                        appPositionsCopy,
                        targetLoops,
                        elecChangeablePosition,
                        togetherGroup,
                        mutualGroup,
                        pointNameId,
                        random,
                        loopElecById
                );

                if (!success) {
                    continue; // 约束冲突，跳过
                }

                // Step 2: 对无约束的独立回路进行变异（抽取 30%）
                mutateUnconstrainedLoops(
                        loopInfoCopy,
                        appPositionsCopy,
                        targetLoops,
                        elecChangeablePosition,
                        pointNameId,
                        random,
                        loopElecById
                );
                //资源限制检查
                Boolean b = elecResourceCheck(loopInfoCopy, resourceNum);
                if(!b){
                    continue;
                }

                // Step 3: 检查方案是否重复
                String fingerprint = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
                // 检查是否在 WareHouse 中（全局去重）
                if (WareHouse.contains(fingerprint)) {
                    continue; // 与历史方案重复，跳过
                }

                // Step 5: 构建完整的JSON方案并计算成本
                Map<String, Object> tempJsonMap = new HashMap<>();
                tempJsonMap.put("loopInfos", loopInfoCopy);
                tempJsonMap.put("appPositions", appPositionsCopy);
                tempJsonMap.put("topoInfo", topoInfoMap);
                tempJsonMap.put("projectInfo", projectInfo);

                String schemeJson = objectMapper.writeValueAsString(tempJsonMap);

                try {
                    // 计算成本
                    String result = powerProjectCircuitInfoOutput.powerOptimize(schemeJson);
                    Map<String, Object> map = jsonToMap.TransJsonToMap(result);
                    Map<String, Object> projectCircuitInfo = (Map<String, Object>) map.get("projectCircuitInfo");

                    if (projectCircuitInfo != null) {
                        Map<String, Double> projectCost = new HashMap<>();
                        projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
                        projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
                        projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

                        map.put("成本", projectCost);
                        map.put("topoId", topoInfoMap.get("id").toString());
                        map.put("caseId", projectInfo.get("caseId"));
                        map.put("finishStatue", "mutated");
                        map.put("initializationScheme", false);
                        // 【关键】添加到 WareHouse
                        WareHouse.add(fingerprint);
                        mutatedSchemes.add(map);
                    }
                } catch (Exception e) {
                    System.err.println("变异方案计算失败，跳过: " + e.getMessage());
                    continue;
                }
            }
        }

        System.out.println("变异完成，生成 " + mutatedSchemes.size() + " 个有效方案");
        return mutatedSchemes;
    }

    /**
     * 对有约束的回路进行变异（抽取 30%）
     * 包括：联动组、互斥组、组团互斥
     * 外部回路会跟随变异
     */
    private boolean mutateConstrainedLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById) {

        // 构建 targetLoops 的 ID 集合
        Set<String> targetLoopIdSet = new HashSet<>();
        for (Map<String, String> targetLoop : targetLoops) {
            targetLoopIdSet.add(targetLoop.get("id"));
        }

        // 构建 loopId -> loopInfo 的快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 记录每个互斥组已选择的位置
        Map<String, Set<String>> mutualGroupUsedPositions = new HashMap<>();

        // Step 1: 处理联动组（抽取 30% 的组团进行变异）
        List<String> togetherGroupIds = new ArrayList<>(togetherGroup.keySet());
        int togetherMutationCount = Math.max(1, (int) Math.ceil(togetherGroupIds.size() * 0.3));
        Collections.shuffle(togetherGroupIds, random);
        List<String> selectedTogetherGroups = togetherGroupIds.subList(0, togetherMutationCount);

        for (String groupId : selectedTogetherGroups) {
            List<String> allMemberLoopIds = togetherGroup.get(groupId);

            // 检查该组团中是否有目标回路
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }

            // 如果该组团中没有目标回路，跳过
            if (!hasTargetLoop) {
                continue;
            }

            // 收集所有成员的可选 endApp 列表，计算交集
            Set<String> endAppIntersection = null;
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(loopId);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
            }

            if (endAppIntersection == null || endAppIntersection.isEmpty()) {
                return false;
            }

            // 从交集中随机选择一个 endApp（可能与当前不同）
            List<String> endAppList = new ArrayList<>(endAppIntersection);
            String selectedEndApp = endAppList.get(random.nextInt(endAppList.size()));

            // 获取该 endApp 的可变位置列表
            List<String> positions = elecChangeablePosition.get(selectedEndApp);
            if (positions == null || positions.isEmpty()) {
                return false;
            }

            // 从位置列表中随机选择一个位置
            String selectedPosition = positions.get(random.nextInt(positions.size()));

            // 检查互斥约束
            String mutualId = null;
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null) {
                    mutualId = loop.get("mutualExclusion");
                    if (mutualId != null && !mutualId.isEmpty()) {
                        break;
                    }
                }
            }

            if (mutualId != null) {
                Set<String> usedPositions = mutualGroupUsedPositions.computeIfAbsent(mutualId, k -> new HashSet<>());
                if (usedPositions.contains(selectedPosition)) {
                    return false; // 位置已被占用，冲突
                }
                usedPositions.add(selectedPosition);
            }

            // 更新整个组所有回路的 endApp 和位置（包括外部回路）
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                // 更新回路的 endApp（连接关系）
                loop.put("endApp", selectedEndApp);

                // 更新终点用电器位置
                for (Map<String, String> appPos : appPositionsCopy) {
                    if (appPos.get("appName").equals(selectedEndApp)) {
                        appPos.put("unregularPointName", selectedPosition);
                        appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                        break;
                    }
                }
            }
        }

        // Step 2: 处理互斥组（抽取 30% 的回路进行变异）
        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            String mutualId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();

            // 检查该互斥组中是否有目标回路
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }

            if (!hasTargetLoop) {
                continue;
            }

            // 抽取 30% 的回路进行变异
            int mutationCount = Math.max(1, (int) Math.ceil(allMemberLoopIds.size() * 0.3));
            List<String> shuffledLoops = new ArrayList<>(allMemberLoopIds);
            Collections.shuffle(shuffledLoops, random);
            List<String> selectedLoops = shuffledLoops.subList(0, mutationCount);

            Set<String> usedPositions = new HashSet<>();

            // 先收集所有已在联动组中处理的成员已占用的位置
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }

                if (inTogetherGroup) {
                    String endApp = loop.get("endApp");
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(endApp) &&
                                appPos.get("unregularPointName") != null) {
                            usedPositions.add(appPos.get("unregularPointName"));
                            break;
                        }
                    }
                }
            }

            // 对被选中的回路进行变异
            for (String loopId : selectedLoops) {
                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }
                if (inTogetherGroup) continue; // 已在联动组中处理过

                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                // 获取该回路可选的 endApp 列表
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    allowedEndApps = Collections.singleton(loop.get("endApp"));
                }

                // 尝试为每个可选 endApp 找到一个可用位置
                boolean assigned = false;
                List<String> endAppCandidates = new ArrayList<>(allowedEndApps);
                Collections.shuffle(endAppCandidates, random);

                for (String endApp : endAppCandidates) {
                    List<String> positions = elecChangeablePosition.get(endApp);
                    if (positions == null || positions.isEmpty()) continue;

                    // 过滤掉已被占用的位置
                    List<String> availablePositions = new ArrayList<>();
                    for (String pos : positions) {
                        if (!usedPositions.contains(pos)) {
                            availablePositions.add(pos);
                        }
                    }

                    if (!availablePositions.isEmpty()) {
                        // 随机选择一个位置
                        String chosenPosition = availablePositions.get(random.nextInt(availablePositions.size()));

                        // 更新回路的 endApp
                        loop.put("endApp", endApp);

                        // 更新用电器位置
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName").equals(endApp)) {
                                appPos.put("unregularPointName", chosenPosition);
                                appPos.put("unregularPointId", pointNameId.get(chosenPosition));
                                break;
                            }
                        }

                        usedPositions.add(chosenPosition);
                        assigned = true;
                        break;
                    }
                }

                if (!assigned) {
                    return false; // 无法找到合适的位置，冲突
                }
            }
        }

        return true;
    }


    /**
     * 对无约束的独立回路进行变异（抽取 30%）
     */
    private void mutateUnconstrainedLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById) {

        // 构建 loopId -> loopInfo 的快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 收集所有无约束的目标回路
        List<Map<String, String>> unconstrainedLoops = new ArrayList<>();
        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");
            Map<String, String> loop = loopById.get(loopId);

            if (loop == null) continue;

            // 检查该回路是否已经被约束处理过
            String together = loop.get("changeTogether");
            String mutual = loop.get("mutualExclusion");
            if ((together != null && !together.isEmpty()) || (mutual != null && !mutual.isEmpty())) {
                continue; // 已被约束处理，跳过
            }

            unconstrainedLoops.add(targetLoop);
        }

        if (unconstrainedLoops.isEmpty()) {
            return;
        }

        // 抽取 30% 的回路进行变异
        int mutationCount = Math.max(1, (int) Math.ceil(unconstrainedLoops.size() * 0.3));
        Collections.shuffle(unconstrainedLoops, random);
        List<Map<String, String>> selectedLoops = unconstrainedLoops.subList(0, mutationCount);

        // 对选中的回路进行变异
        for (Map<String, String> targetLoop : selectedLoops) {
            String loopId = targetLoop.get("id");
            Map<String, String> loop = loopById.get(loopId);

            if (loop == null) continue;

            String startApp = loop.get("startApp");

            // 扰动终点用电器的连接关系（endApp）
            Set<String> allowedEndApps = loopElecById.get(loopId);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                List<String> endAppCandidates = new ArrayList<>(allowedEndApps);
                String newEndApp = endAppCandidates.get(random.nextInt(endAppCandidates.size()));
                loop.put("endApp", newEndApp);
            }

            String endApp = loop.get("endApp");

            // 1. 扰动起点用电器位置
            if (startApp != null) {
                List<String> startPositions = elecChangeablePosition.get(startApp);
                if (startPositions != null && !startPositions.isEmpty()) {
                    String randomStartPosition = startPositions.get(random.nextInt(startPositions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(startApp)) {
                            appPos.put("unregularPointName", randomStartPosition);
                            appPos.put("unregularPointId", pointNameId.get(randomStartPosition));
                            break;
                        }
                    }
                }
            }

            // 2. 扰动终点用电器位置
            if (endApp != null) {
                List<String> positions = elecChangeablePosition.get(endApp);
                if (positions != null && !positions.isEmpty()) {
                    String randomPosition = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(endApp)) {
                            appPos.put("unregularPointName", randomPosition);
                            appPos.put("unregularPointId", pointNameId.get(randomPosition));
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 深拷贝回路信息列表
     */
    private List<Map<String, String>> deepCopyLoopInfos(List<Map<String, String>> source) {
        if (source == null) {
            return null;
        }

        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> map : source) {
            copy.add(new HashMap<>(map));
        }
        return copy;
    }


    /**
     * 生成遗传算法的初代种群
     *
     * @param populationSize 种群大小
     * @param targetLoops 目标回路列表
     * @param allLoopInfos 所有回路信息（模板）
     * @param allAppPositions 所有用电器位置（模板）
     * @param elecChangeablePosition 用电器可变位置映射
     * @param togetherGroup 联动组配置
     * @param mutualGroup 互斥组配置
     * @param pointNameId 位置名称-ID映射
     * @param loopElecById 回路可连接的终点用电器列表
     * @return 初代种群列表
     */
    private List<Map<String, Object>> generateInitialPopulation(
            int populationSize,
            List<Map<String, String>> targetLoops,
            List<Map<String, String>> allLoopInfos,
            List<Map<String, String>> allAppPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            ObjectMapper objectMapper,
            PowerProjectCircuitInfoOutput powerProjectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> topoInfoMap,
            Map<String, String> projectInfo,
            Map<String, Set<String>> loopElecById,Map<String, List<String>> resourceNum) throws Exception {

        Random random = new Random();
        List<Map<String, Object>> population = new ArrayList<>();
        int maxAttempts = populationSize * 10; // 最大尝试次数，避免无限循环
        int attemptCount = 0;

        System.out.println("开始生成 " + populationSize + " 个初代个体...");

        while (population.size() < populationSize && attemptCount < maxAttempts) {
            attemptCount++;

            // 深拷贝模板数据
            List<Map<String, String>> loopInfoCopy = new ArrayList<>();
            for (Map<String, String> loop : allLoopInfos) {
                loopInfoCopy.add(new HashMap<>(loop));
            }
            List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(allAppPositions);

            // Step 1: 处理有约束的回路（联动组、互斥组）- 同时扰动连接关系和位置
            boolean success = perturbConstrainedLoops(
                    loopInfoCopy,
                    appPositionsCopy,
                    targetLoops,
                    elecChangeablePosition,
                    togetherGroup,
                    mutualGroup,
                    pointNameId,
                    random,
                    loopElecById
            );

            if (!success) {
                continue; // 约束冲突，重新生成
            }

            // Step 2: 处理无约束的回路 - 同时扰动连接关系和位置
            perturbUnconstrainedLoops(
                    loopInfoCopy,
                    appPositionsCopy,
                    targetLoops,
                    elecChangeablePosition,
                    pointNameId,
                    random,
                    loopElecById
            );
            //资源限制检查
            Boolean b = elecResourceCheck(loopInfoCopy, resourceNum);
            if(!b){
                continue;
            }

            // Step 3: 检查方案是否重复
            String fingerprint = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
            // 检查是否在 WareHouse 中（全局去重）
            if (WareHouse.contains(fingerprint)) {
                continue; // 重复方案，跳过
            }

            // Step 4: 添加到去重仓库
            WareHouse.add(fingerprint);

            // Step 5: 构建完整的JSON方案并计算成本
            Map<String, Object> tempJsonMap = new HashMap<>();
            tempJsonMap.put("loopInfos", loopInfoCopy);
            tempJsonMap.put("appPositions", appPositionsCopy);
            tempJsonMap.put("topoInfo", topoInfoMap);
            tempJsonMap.put("projectInfo", projectInfo);

            String schemeJson = objectMapper.writeValueAsString(tempJsonMap);

            try {
                // 计算成本
                String result = powerProjectCircuitInfoOutput.powerOptimize(schemeJson);
                Map<String, Object> map = jsonToMap.TransJsonToMap(result);
                Map<String, Object> projectCircuitInfo = (Map<String, Object>) map.get("projectCircuitInfo");

                if (projectCircuitInfo != null) {
                    Map<String, Double> projectCost = new HashMap<>();
                    projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
                    projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
                    projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

                    map.put("成本", projectCost);
                    map.put("loopInfos", loopInfoCopy);
                    map.put("appPositions", appPositionsCopy);
                    map.put("schemeIndex", population.size() + 1);

                    population.add(map);

                    if (population.size() % 10 == 0) {
                        System.out.println("已生成 " + population.size() + " 个有效个体...");
                    }
                }
            } catch (Exception e) {
                System.err.println("方案计算失败，跳过: " + e.getMessage());
                continue;
            }
        }

        System.out.println("初代种群生成完成，共 " + population.size() + " 个个体，尝试次数: " + attemptCount);
        return population;
    }


    /**
     * 扰动有约束的回路（联动组、互斥组、组团内互斥）
     * 关键规则：
     * 1. 同时扰动连接关系（endApp）和用电器位置
     * 2. 如果联动组中有任意一个目标回路，则该组所有回路都要一起变化（endApp必须相同）
     * 3. 互斥组同理，只要有一个目标回路，组内所有回路的endApp对应的位置必须不同
     *
     * @return true-成功应用约束，false-约束冲突
     */
    private boolean perturbConstrainedLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById) {

        // 构建 targetLoops 的 ID 集合，用于快速判断是否为目标回路
        Set<String> targetLoopIdSet = new HashSet<>();
        for (Map<String, String> targetLoop : targetLoops) {
            targetLoopIdSet.add(targetLoop.get("id"));
        }

        // 构建 loopId -> loopInfo 的快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 记录每个互斥组已选择的位置（用于互斥检查）
        Map<String, Set<String>> mutualGroupUsedPositions = new HashMap<>();

        // Step 1: 处理联动组（组团）
        // 规则：如果组内有任意一个目标回路，则整个组的所有回路endApp必须相同，位置也必须相同
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();

            // 检查该组团中是否有目标回路
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }

            // 如果该组团中没有目标回路，跳过整个组
            if (!hasTargetLoop) {
                continue;
            }

            // 【关键】收集所有成员的可选endApp列表，计算交集
            Set<String> endAppIntersection = null;
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(loopId);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
            }

            if (endAppIntersection == null || endAppIntersection.isEmpty()) {
                return false;
            }

            // 从交集中随机选择一个endApp
            List<String> endAppList = new ArrayList<>(endAppIntersection);
            String selectedEndApp = endAppList.get(random.nextInt(endAppList.size()));

            // 获取该endApp的可变位置列表
            List<String> positions = elecChangeablePosition.get(selectedEndApp);
            if (positions == null || positions.isEmpty()) {
                return false;
            }

            // 从位置列表中随机选择一个位置
            String selectedPosition = positions.get(random.nextInt(positions.size()));

            // 检查互斥约束：如果该组团属于某个互斥组
            String mutualId = null;
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null) {
                    mutualId = loop.get("mutualExclusion");
                    if (mutualId != null && !mutualId.isEmpty()) {
                        break;
                    }
                }
            }

            if (mutualId != null) {
                Set<String> usedPositions = mutualGroupUsedPositions.computeIfAbsent(mutualId, k -> new HashSet<>());
                if (usedPositions.contains(selectedPosition)) {
                    return false; // 位置已被占用，冲突
                }
                usedPositions.add(selectedPosition);
            }

            // 【关键】更新整个组所有回路的endApp和位置
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                // 更新回路的endApp（连接关系）
                loop.put("endApp", selectedEndApp);

                // 更新终点用电器位置
                for (Map<String, String> appPos : appPositionsCopy) {
                    if (appPos.get("appName").equals(selectedEndApp)) {
                        appPos.put("unregularPointName", selectedPosition);
                        appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                        break;
                    }
                }
            }
        }

        // Step 2: 处理独立的互斥回路（不在任何联动组中的互斥回路）
        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            String mutualId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();

            // 检查该互斥组中是否有目标回路
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }

            if (!hasTargetLoop) {
                continue;
            }

            Set<String> usedPositions = new HashSet<>();

            // 先收集所有已在联动组中处理的成员已占用的位置
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }

                if (inTogetherGroup) {
                    String endApp = loop.get("endApp");
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(endApp) &&
                                appPos.get("unregularPointName") != null) {
                            usedPositions.add(appPos.get("unregularPointName"));
                            break;
                        }
                    }
                }
            }

            // 对所有未在处理联动组的成员进行endApp和位置分配
            for (String loopId : allMemberLoopIds) {
                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }
                if (inTogetherGroup) continue; // 已在联动组中处理过

                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                // 获取该回路可选的endApp列表
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    allowedEndApps = Collections.singleton(loop.get("endApp"));
                }

                // 尝试为每个可选endApp找到一个可用位置
                boolean assigned = false;
                List<String> endAppCandidates = new ArrayList<>(allowedEndApps);
                Collections.shuffle(endAppCandidates, random); // 随机打乱顺序

                for (String endApp : endAppCandidates) {
                    List<String> positions = elecChangeablePosition.get(endApp);
                    if (positions == null || positions.isEmpty()) continue;

                    // 过滤掉已被占用的位置
                    List<String> availablePositions = new ArrayList<>();
                    for (String pos : positions) {
                        if (!usedPositions.contains(pos)) {
                            availablePositions.add(pos);
                        }
                    }

                    if (!availablePositions.isEmpty()) {
                        // 随机选择一个位置
                        String chosenPosition = availablePositions.get(random.nextInt(availablePositions.size()));

                        // 更新回路的endApp
                        loop.put("endApp", endApp);

                        // 更新用电器位置
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName").equals(endApp)) {
                                appPos.put("unregularPointName", chosenPosition);
                                appPos.put("unregularPointId", pointNameId.get(chosenPosition));
                                break;
                            }
                        }

                        usedPositions.add(chosenPosition);
                        assigned = true;
                        break;
                    }
                }

                if (!assigned) {
                    return false; // 无法找到合适的位置，冲突
                }
            }
        }

        return true;
    }


    /**
     * 生成方案的唯一指纹（用于去重检查）
     * 指纹包含两部分：
     * 1. 回路连接关系：loopId -> startApp|endApp
     * 2. 用电器位置：appName -> unregularPointName
     *
     * @param loopInfos 回路信息列表
     * @param appPositions 用电器位置列表
     * @return 方案指纹字符串
     */
    private String generateSchemeFingerprint(List<Map<String, String>> loopInfos,
                                             List<Map<String, String>> appPositions) {
        StringBuilder fingerprint = new StringBuilder();

        // 第一部分：回路连接关系（按回路ID排序，确保顺序一致性）
        List<Map<String, String>> sortedLoops = new ArrayList<>(loopInfos);
        sortedLoops.sort((a, b) -> a.get("id").compareTo(b.get("id")));

        fingerprint.append("LOOPS:");
        for (Map<String, String> loop : sortedLoops) {
            String loopId = loop.get("id");
            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            // 只关注发生变化的回路
            if (startApp != null && endApp != null) {
                fingerprint.append(loopId)
                        .append("=")
                        .append(startApp)
                        .append("|")
                        .append(endApp)
                        .append(";");
            }
        }

        // 第二部分：用电器位置信息（按appName排序）
        List<Map<String, String>> sortedApps = new ArrayList<>(appPositions);
        sortedApps.sort((a, b) -> {
            String nameA = a.get("appName") != null ? a.get("appName") : "";
            String nameB = b.get("appName") != null ? b.get("appName") : "";
            return nameA.compareTo(nameB);
        });

        fingerprint.append("|APPS:");
        for (Map<String, String> app : sortedApps) {
            String appName = app.get("appName");
            String position = app.get("unregularPointName");
            // 只记录有位置信息的用电器
            if (appName != null && position != null && !position.isEmpty()) {
                fingerprint.append(appName)
                        .append("=")
                        .append(position)
                        .append(";");
            }
        }

        return fingerprint.toString();
    }

    /**
     * 扰动无约束的回路（同时扰动连接关系和位置）
     */
    private void perturbUnconstrainedLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById) {

        // 构建 loopId -> loopInfo 的快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 遍历所有目标回路，对未设置的回路进行随机扰动
        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");
            Map<String, String> loop = loopById.get(loopId);

            if (loop == null) continue;

            // 检查该回路是否已经被约束处理过（有联动组或互斥组标记）
            String together = loop.get("changeTogether");
            String mutual = loop.get("mutualExclusion");
            if ((together != null && !together.isEmpty()) || (mutual != null && !mutual.isEmpty())) {
                continue; // 已被约束处理，跳过
            }

            String startApp = loop.get("startApp");

            // 【关键】扰动终点用电器的连接关系（endApp）
            Set<String> allowedEndApps = loopElecById.get(loopId);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                List<String> endAppCandidates = new ArrayList<>(allowedEndApps);
                String newEndApp = endAppCandidates.get(random.nextInt(endAppCandidates.size()));
                loop.put("endApp", newEndApp);
            }

            String endApp = loop.get("endApp");

            // 1. 扰动起点用电器位置
            if (startApp != null) {
                List<String> startPositions = elecChangeablePosition.get(startApp);
                if (startPositions != null && !startPositions.isEmpty()) {
                    String randomStartPosition = startPositions.get(random.nextInt(startPositions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(startApp)) {
                            appPos.put("unregularPointName", randomStartPosition);
                            appPos.put("unregularPointId", pointNameId.get(randomStartPosition));
                            break;
                        }
                    }
                }
            }

            // 2. 扰动终点用电器位置
            if (endApp != null) {
                List<String> positions = elecChangeablePosition.get(endApp);
                if (positions != null && !positions.isEmpty()) {
                    String randomPosition = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(endApp)) {
                            appPos.put("unregularPointName", randomPosition);
                            appPos.put("unregularPointId", pointNameId.get(randomPosition));
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 深拷贝 List<Map<String, String>>
     */
    private List<Map<String, String>> deepCopyAppPositions(List<Map<String, String>> source) {
        if (source == null) {
            return null;
        }

        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> map : source) {
            copy.add(new HashMap<>(map));
        }
        return copy;
    }


    /**
     * 查找用电器默认位置
     * @param mapList 用电器位置集合
     * @return
     */
    public Map<String,String> getEleclection(List<Map<String,String >> mapList)  {
        Map<String,String> stringMap1=new HashMap<>();
        for (Map<String, String> stringMap : mapList) {
            String result = "";
            if (stringMap.get("unregularPointName") !=null){
                result= stringMap.get("unregularPointName");
            } else if (stringMap.get("unregularPointName") ==null && stringMap.get("regularPointName") !=null ) {
                result= stringMap.get("regularPointName");
            }else if (stringMap.get("unregularPointName") ==null && stringMap.get("regularPointName") ==null ) {
                result= null;
            }

            stringMap1.put(stringMap.get("appName"),result);
        }
//        System.out.println("从txt中读取到的用电器，经过位置判断后共计"+resultList.size()+"个");
        return stringMap1;
    }


    /**
     * 枚举所有可行方案并收集到 enumeratedSchemes
     *
     * @param targetLoops           需要优化的目标回路列表
     * @param elecChangeablePosition 用电器到可变位置列表的映射
     * @param togetherGroup         联动组配置
     * @param mutualGroup           互斥组配置
     * @param allLoopInfos          所有回路信息（用于获取回路详情）
     * @param loopElecById          回路可连接的终点用电器列表
     */
    private void enumerateAllSchemes(
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            List<Map<String, String>> allLoopInfos,
            Map<String, Set<String>> loopElecById) {

        long startTime = System.currentTimeMillis();

        // loopId → loopInfo 快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : allLoopInfos) {
            loopById.put(lp.get("id"), lp);
        }

        // --------------------------------------------------------
        // Step 1: 构建"变量"列表 - 只包含 endApp 选择变量
        //
        // 【关键】约束只针对 endApp，不包含位置
        // --------------------------------------------------------
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();

        // 处理联动组：组内所有回路共享一个 endApp 选择变量
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();

            // 计算所有成员可选 endApp 的交集
            Set<String> endAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(lid);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
                coveredLoopIds.add(lid);
            }

            // endApp 选择变量（值是 endApp 名称，不是位置）
            if (endAppIntersection != null && !endAppIntersection.isEmpty()) {
                varDomains.put("E_G_" + groupId, new ArrayList<>(endAppIntersection));
            }
        }

        // 处理独立回路的 endApp 选择变量
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid)) continue;

            Set<String> allowedEndApps = loopElecById.get(lid);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                varDomains.put("E_L_" + lid, new ArrayList<>(allowedEndApps));
            } else {
                // 如果没有指定，使用当前 endApp
                varDomains.put("E_L_" + lid, Collections.singletonList(lp.get("endApp")));
            }
        }

        // --------------------------------------------------------
        // Step 2: 变量 → 互斥组映射
        // 互斥的是 endApp 选择变量
        // --------------------------------------------------------
        Map<String, List<String>> varKeyToMutualIds = new LinkedHashMap<>();
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");

            if (mutual == null || mutual.isEmpty()) continue;

            // 互斥的是 endApp 选择变量
            String vk = (together != null && !together.isEmpty())
                    ? "E_G_" + together
                    : "E_L_" + lid;

            varKeyToMutualIds.computeIfAbsent(vk, k -> new ArrayList<>()).add(mutual);
        }

        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : varKeyToMutualIds.entrySet()) {
            String varKey = e.getKey();
            for (String mutualId : e.getValue()) {
                List<String> varList = mutualIdToVarKeys.computeIfAbsent(mutualId, k -> new ArrayList<>());
                if (!varList.contains(varKey)) {
                    varList.add(varKey);
                }
            }
        }
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualIds.keySet());

        // --------------------------------------------------------
        // Step 3: 准备变量列表
        // --------------------------------------------------------
        List<String> varKeys = new ArrayList<>(varDomains.keySet());

        System.out.println("开始回溯枚举，变量数: " + varKeys.size());

        // --------------------------------------------------------
        // Step 4: 执行回溯枚举
        // --------------------------------------------------------
        Map<String, String> currentAssignment = new LinkedHashMap<>();
        Set<String> usedEndApps = new HashSet<>(); // 用于互斥检查：记录已使用的 endApp

        enumerateSchemesByBacktrack(
                varDomains, mutualIdToVarKeys, varsInAnyMutualGroup,
                0, varKeys, currentAssignment, usedEndApps,
                targetLoops, loopById, elecChangeablePosition);

        System.out.println("枚举完成，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
    }



    /**
     * 回溯法枚举方案并收集到 enumeratedSchemes
     */
    private void enumerateSchemesByBacktrack(
            Map<String, List<String>> varDomains,
            Map<String, List<String>> mutualIdToVarKeys,
            Set<String> varsInMutualGroup,
            int varIndex,
            List<String> varKeys,
            Map<String, String> currentAssignment,
            Set<String> usedEndApps, // 改为 usedEndApps
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition) {

        // 检查是否应该停止优化
        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            System.out.println("优化被用户中断");
            return;
        }

        // 检查是否超过限制
        if (enumeratedSchemes.size() >= caseNumbe) {
            System.out.println("枚举方案数已达到限制(" + caseNumbe + ")，提前退出");
            return;
        }

        // 所有变量都已赋值，生成一个方案
        if (varIndex == varKeys.size()) {
            // 将变量赋值转换为回路级别的方案
            Map<String, String> scheme = convertAssignmentToSchemeFormat(
                    currentAssignment, targetLoops, loopById, elecChangeablePosition);

            if (!scheme.isEmpty()) {
                enumeratedSchemes.add(scheme);

                // 每100个方案输出一次进度
                if (enumeratedSchemes.size() % 100 == 0) {
                    System.out.println("已枚举 " + enumeratedSchemes.size() + " 个方案...");
                }
            }
            return;
        }

        String varKey = varKeys.get(varIndex);
        List<String> domain = varDomains.get(varKey);

        if (domain == null || domain.isEmpty()) {
            return;
        }

        // 检查该变量是否在互斥组中
        boolean isInMutualGroup = varsInMutualGroup.contains(varKey);

        for (String endApp : domain) {
            // 互斥约束检查：检查 endApp 是否已被占用
            if (isInMutualGroup && usedEndApps.contains(endApp)) {
                continue;
            }

            // 做选择
            currentAssignment.put(varKey, endApp);
            usedEndApps.add(endApp);

            // 递归处理下一个变量
            enumerateSchemesByBacktrack(
                    varDomains, mutualIdToVarKeys, varsInMutualGroup,
                    varIndex + 1, varKeys, currentAssignment, usedEndApps,
                    targetLoops, loopById, elecChangeablePosition);

            // 如果已经达到限制，提前退出
            if (enumeratedSchemes.size() >= caseNumbe) {
                break;
            }

            // 撤销选择
            usedEndApps.remove(endApp);
            currentAssignment.remove(varKey);
        }
    }

    /**
     * 将变量赋值转换为方案格式
     *
     * @return Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
     *         包含所有发生变化的回路（目标回路 + 受约束影响的外部回路）
     */
    private Map<String, String> convertAssignmentToSchemeFormat(
            Map<String, String> assignment,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition) {

        Map<String, String> scheme = new LinkedHashMap<>();
        Random random = new Random();

        // 【关键修复】收集所有需要加入方案的回路ID
        Set<String> affectedLoopIds = new HashSet<>();

        // 1. 添加所有目标回路
        for (Map<String, String> loop : targetLoops) {
            affectedLoopIds.add(loop.get("id"));
        }

        // 2. 添加与目标回路在同一联动组的所有回路
        for (Map<String, String> loop : targetLoops) {
            String together = loop.get("changeTogether");
            if (together != null && !together.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    String loopTogether = allLoop.get("changeTogether");
                    if (together.equals(loopTogether)) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }

            // 3. 添加与目标回路互斥的所有回路
            String mutual = loop.get("mutualExclusion");
            if (mutual != null && !mutual.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    String loopMutual = allLoop.get("mutualExclusion");
                    if (mutual.equals(loopMutual)) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
        }

        // 遍历所有受影响的回路，生成方案
        for (String loopId : affectedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop == null) continue;

            String startApp = loop.get("startApp");
            String together = loop.get("changeTogether");

            // 【关键】获取该回路的 endApp 选择
            String selectedEndApp = null;
            if (together != null && !together.isEmpty()) {
                // 从联动组的 endApp 变量中获取
                selectedEndApp = assignment.get("E_G_" + together);
            } else {
                // 从独立回路的 endApp 变量中获取
                selectedEndApp = assignment.get("E_L_" + loopId);
            }

            // 如果没有赋值，使用原始值
            if (selectedEndApp == null) {
                selectedEndApp = loop.get("endApp");
            }

            // 【关键】为选定的 endApp 随机选择一个位置
            String assignedEndPosition = null;
            if (selectedEndApp != null) {
                List<String> positions = elecChangeablePosition.get(selectedEndApp);
                if (positions != null && !positions.isEmpty()) {
                    assignedEndPosition = positions.get(random.nextInt(positions.size()));
                }
            }

            // 为起点用电器随机选择一个位置
            String assignedStartPosition = null;
            if (startApp != null && !startApp.isEmpty()) {
                List<String> startPositions = elecChangeablePosition.get(startApp);
                if (startPositions != null && !startPositions.isEmpty()) {
                    assignedStartPosition = startPositions.get(random.nextInt(startPositions.size()));
                }
            }

            // 如果位置有赋值，则加入方案
            if (assignedStartPosition != null || assignedEndPosition != null) {
                String finalStartPos = assignedStartPosition != null ? assignedStartPosition : "";
                String finalEndPos = assignedEndPosition != null ? assignedEndPosition : "";

                // 格式: "起点用电器|终点用电器|起点位置|终点位置"
                String value = startApp + "|" + selectedEndApp + "|" + finalStartPos + "|" + finalEndPos;

                scheme.put(loopId, value);
            }
        }

        return scheme;
    }



    private long calculateOptimizationCombinations(
            List<Map<String, String>> loopInfos,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, Set<String>> loopElecById) {

        long calcStart = System.currentTimeMillis();

        // loopId → loopInfo 快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : loopInfos) {
            loopById.put(lp.get("id"), lp);
        }

        // --------------------------------------------------------
        // 【关键修复】Step 0: 扩展回路列表，包含所有受约束影响的外部回路
        // --------------------------------------------------------
        Set<String> extendedLoopIds = new HashSet<>();

        // 1. 添加所有目标回路
        for (Map<String, String> lp : loopInfos) {
            extendedLoopIds.add(lp.get("id"));
        }

        // 2. 添加与目标回路在同一联动组的所有回路
        for (Map<String, String> lp : loopInfos) {
            String together = lp.get("changeTogether");
            if (together != null && !together.isEmpty()) {
                List<String> groupMembers = togetherGroup.get(together);
                if (groupMembers != null) {
                    extendedLoopIds.addAll(groupMembers);
                }
            }

            // 3. 添加与目标回路互斥的所有回路
            String mutual = lp.get("mutualExclusion");
            if (mutual != null && !mutual.isEmpty()) {
                // 需要从 loopById 中查找所有同互斥组的回路
                for (Map<String, String> allLoop : loopById.values()) {
                    String loopMutual = allLoop.get("mutualExclusion");
                    if (mutual.equals(loopMutual)) {
                        extendedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
        }

        // 构建扩展后的回路列表
        List<Map<String, String>> extendedLoops = new ArrayList<>();
        for (String loopId : extendedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop != null) {
                extendedLoops.add(loop);
            }
        }

        // --------------------------------------------------------
        // Step 1: 构建"变量"列表 - 只包含 endApp 选择变量
        //
        // 规则：
        //   联动组（changeTogether）→ 合并为 1 个变量
        //     该变量的域 = 组内所有回路可选 endApp 的【交集】
        //   独立回路（无 changeTogether）→ 1 个变量
        //     该变量的域 = 该回路可选 endApp 列表
        //
        // 变量 key 格式：
        //   联动组变量  → "E_G_<togetherGroupId>"
        //   独立回路变量 → "E_L_<loopId>"
        // --------------------------------------------------------
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();

        // 处理联动组：取所有成员可选 endApp 的交集
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();

            // Step 1.1: 收集所有成员的可选 endApp 列表，取交集
            Set<String> endAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(lid);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    // 如果没有指定可选 endApp，使用当前 endApp
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
                coveredLoopIds.add(lid);
            }

            varDomains.put("E_G_" + groupId,
                    endAppIntersection != null ? new ArrayList<>(endAppIntersection) : Collections.emptyList());
        }

        // 处理独立回路（不在任何联动组中）
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid)) continue;

            // 获取该回路可选的 endApp 列表
            Set<String> allowedEndApps = loopElecById.get(lid);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                varDomains.put("E_L_" + lid, new ArrayList<>(allowedEndApps));
            } else {
                // 如果没有指定可选 endApp，使用当前 endApp
                varDomains.put("E_L_" + lid, Collections.singletonList(lp.get("endApp")));
            }
        }

        // --------------------------------------------------------
        // Step 2: 变量 → 互斥组映射
        //
        // 一条回路如果有 mutualExclusion 标记：
        //   若它属于某个联动组 → 整个联动组变量参与互斥
        //   否则 → 该回路自己的变量参与互斥
        //
        // 注意：同一个变量可能属于多个互斥组
        // --------------------------------------------------------
        Map<String, List<String>> varKeyToMutualIds = new LinkedHashMap<>();
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");

            if (mutual == null || mutual.isEmpty()) continue;

            // 确定变量key
            String vk = (together != null && !together.isEmpty())
                    ? "E_G_" + together
                    : "E_L_" + lid;

            // 添加到互斥组列表（允许一个变量属于多个互斥组）
            varKeyToMutualIds.computeIfAbsent(vk, k -> new ArrayList<>()).add(mutual);
        }

        // 互斥组 → 变量列表（去重，保持唯一）
        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : varKeyToMutualIds.entrySet()) {
            String varKey = e.getKey();
            for (String mutualId : e.getValue()) {
                // 避免同一个变量在同一个互斥组中重复添加
                List<String> varList = mutualIdToVarKeys.computeIfAbsent(mutualId, k -> new ArrayList<>());
                if (!varList.contains(varKey)) {
                    varList.add(varKey);
                }
            }
        }

        // 收集所有参与互斥的变量
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualIds.keySet());

        // --------------------------------------------------------
        // Step 3: 计算总方案数
        //
        // 3a. 独立变量（不在任何互斥组中）→ 直接乘以域大小
        // 3b. 互斥组（可能含联动组变量）→ 回溯法计算"两两不同 endApp"的赋值数
        // 3c. 用电器位置（每个唯一 endApp 和 startApp 独立计算，相乘）
        // --------------------------------------------------------
        long totalCombinations = 1L;

        // 3a: 独立变量
        for (Map.Entry<String, List<String>> e : varDomains.entrySet()) {
            if (!varsInAnyMutualGroup.contains(e.getKey())) {
                int sz = e.getValue().size();
                if (sz <= 0) {
                    totalCombinations = 0;
                    break;
                }
                totalCombinations *= sz;
            }
        }

        // 3b: 互斥组 —— 回溯计算全不同 endApp 赋值数
        if (totalCombinations > 0) {
            for (Map.Entry<String, List<String>> e : mutualIdToVarKeys.entrySet()) {
                List<List<String>> doms = new ArrayList<>();
                for (String vk : e.getValue()) {
                    List<String> d = varDomains.get(vk);
                    doms.add(d != null ? d : Collections.emptyList());
                }
                long mc = countAllDifferent(doms);
                if (mc <= 0) {
                    totalCombinations = 0;
                    break;
                }
                totalCombinations *= mc;
            }
        }

        // 3c: 用电器位置（同一用电器作为起点或终点时只统计一次）
        if (totalCombinations > 0) {
            // 收集所有可能被选择的用电器（包括 startApp 和 endApp）
            Set<String> allPossibleApps = new HashSet<>();

            // 添加所有 startApp
            for (Map<String, String> lp : extendedLoops) {
                String startApp = lp.get("startApp");
                if (startApp != null && !startApp.isEmpty()) {
                    allPossibleApps.add(startApp);
                }
            }

            // 添加所有可能被选择的 endApp（从变量域中提取）
            for (List<String> domain : varDomains.values()) {
                allPossibleApps.addAll(domain);
            }

            // 计算位置组合数
            for (String appName : allPossibleApps) {
                List<String> positions = elecChangeablePosition.get(appName);
                if (positions != null && !positions.isEmpty()) {
                    totalCombinations *= positions.size();
                }
            }
        }

        System.out.println("可行方案总数（含约束）: " + totalCombinations);
        System.out.println("方案数计算耗时: " + (System.currentTimeMillis() - calcStart) + "ms");

        return totalCombinations;
    }





    // ================================================================
    // 辅助方法 1：回溯法计算"多变量两两互斥"的合法赋值数
    //
    // 思路：
    //   逐个变量赋值，每次只选择"当前尚未被其他变量使用"的位置
    //   递归到最后一个变量时，记为 1 种合法方案
    //
    // 适用条件：变量数量和域大小均较小（互斥组一般 ≤ 10 个变量）
    // ================================================================
    private long countAllDifferent(List<List<String>> domains) {
        if (domains == null || domains.isEmpty()) return 1L;
        return backtrackCount(domains, 0, new HashSet<>());
    }

    /**
     * @param domains    每个变量的可选值列表
     * @param idx        当前处理第几个变量
     * @param usedValues 已被前面变量占用的值（位置）集合
     * @return 从 idx 开始往后的合法赋值数量
     */
    private long backtrackCount(List<List<String>> domains, int idx, Set<String> usedValues) {
        if (idx == domains.size()) return 1L;
        List<String> domain = domains.get(idx);
        if (domain == null || domain.isEmpty()) return 0L;
        long count = 0L;
        for (String val : domain) {
            if (!usedValues.contains(val)) {
                usedValues.add(val);
                count += backtrackCount(domains, idx + 1, usedValues);
                usedValues.remove(val);
            }
        }
        return count;
    }

    // ================================================================
    // 辅助方法 2：根据位置 id 获取对应的位置点名称
    // ================================================================
    public String findNameById(String id, List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if (point.get("id").toString().equals(id)) {
                return point.get("pointName").toString();
            }
        }
        return "";
    }
}