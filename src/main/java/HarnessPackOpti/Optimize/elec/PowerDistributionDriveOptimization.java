package HarnessPackOpti.Optimize.elec;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.PowerProjectCircuitInfoOutput;

/**
 * 配电驱动优化
 */
public class PowerDistributionDriveOptimization {

    // 当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;
    private static Integer TopNumber = 20;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    // 可变数量阈值，走枚举
    public static Integer caseNumbe = 10000;

    // 生成初始样本数量限制
    public static Integer LessRandomSamleNumber = 20;

    // 遗传最优样本重复次数
    public static Integer BestRepetitionNumber = 0;

    // 每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();

    // 遗传迭代重复的次数限值
    public static Integer IterationRestrictNumber = 30;

    // 遗传每轮迭代最少样本数量
    public static Integer HybridizationLessRandomSamleNumber = 200;

    // 遗传算法数量不够时自动补全得次数
    public static Integer AutoCompleteNumber = 30;

    // 遗传算法每轮变异的次数
    public static Integer VariationNumber = 1;

    // 交叉概率（0.7 表示 70% 的方案参与交叉）
    public static Double CrossoverRate = 0.7;

    // 定义一个仓库，遗传每次生成的方案存储，防止重复
    public static Set<String> WareHouse = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 枚举收集的所有方案
    private List<Map<String, String>> enumeratedSchemes = new ArrayList<>();

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance();
    }

    public static void main(String[] args) throws Exception {
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\配电驱动优化测试数据.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));// 将文件中内容转为字符串
        PowerDistributionDriveOptimization powerDistributionDriveOptimization = new PowerDistributionDriveOptimization();
        powerDistributionDriveOptimization.powerDriverOptimize(jsonContent);
    }

    public List<Map<String, Object>> powerDriverOptimize(String jsonContent) throws Exception {
        // ========== 修复3：重置静态状态，避免多任务干扰 ==========
        WareHouse.clear();
        BestRepetitionNumber = 0;
        BestCost.clear();

        long categoryTime = System.currentTimeMillis();
        ObjectMapper objectMapper = new ObjectMapper();
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
        // 位置点名称-id
        Map<String, String> pointNameId = new HashMap<>();
        // 回路id-可连接的用电器列表,关于终点的
        Map<String, Set<String>> loopElecById = new HashMap<>();
        // 回路id-可连接的用电器列表,关于起始点的
        Map<String, Set<String>> loopElecByIdStart = new HashMap<>();
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

        // 查找用电器自身位置点
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
                        appPosition.get("resourceNumb"), new TypeReference<List<String>>() {
                        });
                resourceNum.put(appName, list);
            }
            if ("1".equals(appPosition.get("changeType"))) {
                List<String> list = new ArrayList<>();
                String sp = appPosition.get("specifyPoints");
                if (sp != null && !sp.isEmpty()) {
                    for (String part : sp.split(",")) {
                        String pointName = findNameById(part, points);
                        list.add(pointName);

                        // 如果该位置点是直连接口，将整个接口组的位置都加入
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
                list.add(eleclection.get(appName)); // 把自身位置加进去
                elecChangeablePosition.put(appName, list);
            } else if ("2".equals(appPosition.get("changeType"))) {
                elecChangeablePosition.put(appName, new ArrayList<>(allPoint));
            }
            elecNameId.put(appPosition.get("id"), appName);
        }

        // 统计约束list集合，方便后面判断回路是否有约束
        List<String> togetherList = new ArrayList<>();
        List<String> mutualList = new ArrayList<>();
        for (Map<String, String> loopInfo : loopInfos) {
            if ("主供电回路".equals(loopInfo.get("loopAttribute"))
                    || "配电回路".equals(loopInfo.get("loopAttribute"))) {
                elecLoopList.add(loopInfo);
            } else if ("驱动回路".equals(loopInfo.get("loopAttribute"))) {
                driveLoopList.add(loopInfo);
            }
            // 回路可连接的终点用电器统计
            String s = loopInfo.get("endSpecifyPoints");
            if (s != null && !s.isEmpty()) {
                for (String part : s.split(",")) {
                    String pointName = findNameById(part, points);
                    loopElecById.computeIfAbsent(loopInfo.get("id"), k -> new HashSet<>()).add(pointName);
                }
            }
            // 回路可连接的终点用电器统计
            String start = loopInfo.get("startSpecifyPoints");
            if (start != null && !start.isEmpty()) {
                for (String part : start.split(",")) {
                    String pointName = findNameById(part, points);
                    loopElecByIdStart.computeIfAbsent(loopInfo.get("id"), k -> new HashSet<>()).add(pointName);
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

        // 同时优化配电回路和驱动器回路
        List<Map<String, String>> combinedList = new ArrayList<>(elecLoopList);
        combinedList.addAll(driveLoopList);
        if (typeList.contains("1") && typeList.contains("2")) {
            combinations = calculateOptimizationCombinations(combinedList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        // 优化驱动回路
        if ("1".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(driveLoopList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        // 优化类型 2：配电器回路
        if ("2".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(elecLoopList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        // 优化类型 3：所有回路
        if ("3".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(loopInfos, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
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
                enumerateAllSchemes(targetLoops, elecChangeablePosition, togetherGroup, mutualGroup, loopInfos,
                        loopElecById, loopElecByIdStart);

                System.out.println("枚举耗时: " + (System.currentTimeMillis() - enumerateTime) + "ms");

                // 每个方案格式: Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
                // 遍历所有方案
                for (int i = 0; i < enumeratedSchemes.size(); i++) {
                    Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);
                    Map<String, String> scheme = enumeratedSchemes.get(i);

                    List<Map<String, String>> loopInfoCopy = new ArrayList<>();
                    for (Map<String, String> loop : loopInfos) {
                        loopInfoCopy.add(new HashMap<>(loop));
                    }
                    List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(appPositions);

                    // 遍历该方案中的所有回路，还原方案
                    for (Map.Entry<String, String> entry : scheme.entrySet()) {
                        String loopId = entry.getKey();
                        String value = entry.getValue();
                        String[] parts = value.split("\\|");
                        // 一根贿赂两个用电器都没有可变回路就采用默认位置
                        if (parts.length == 2) {
                            continue;
                        }
                        String startApp = parts[0];
                        String endApp = parts[1];
                        String startPos = parts[2];
                        String endPos = parts[3];

                        for (Map<String, String> loop : loopInfoCopy) {
                            if (loop.get("id").equals(loopId)) {
                                loop.put("startApp", startApp);
                                loop.put("endApp", endApp);
                            }
                        }

                        for (Map<String, String> stringStringMap : appPositionsCopy) {
                            if (stringStringMap.get("appName").equals(startApp) && !startPos.isEmpty()) {
                                stringStringMap.put("unregularPointName", startPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(startPos));
                            }
                            if (stringStringMap.get("appName").equals(endApp) && !endPos.isEmpty()) {
                                stringStringMap.put("unregularPointName", endPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(endPos));
                            }
                        }
                    }

                    // 生成完整方案的指纹（包含所有回路和所有用电器位置）
                    String fingerprint = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
                    if (WareHouse.contains(fingerprint)) {
                        duplicateCount++;
                        continue;
                    }
                    // 资源数量检查
                    Boolean aBoolean = elecResourceCheck(loopInfoCopy, resourceNum);
                    if (!aBoolean) {
                        continue;
                    }

                    WareHouse.add(fingerprint);
                    validSchemeCount++;
                    jsonMapCopy.put("loopInfo", loopInfoCopy);
                    jsonMapCopy.put("appPositions", appPositionsCopy);
                    // ========== 修复1：计算成本时使用修改后的方案 ==========
                    String modifiedJson = objectMapper.writeValueAsString(jsonMapCopy);
                    String s = powerProjectCircuitInfoOutput.powerOptimize(modifiedJson);
                    if (s == null) {
                        continue;
                    }
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
            List<Map<String, Object>> topBest = findBest.findBest(resultList, "成本", TopNumber);
            return topBest;
        }

        // 开始生成初代样本
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
                    loopElecByIdStart,
                    resourceNum);

            System.out.println("初代样本生成耗时: " + (System.currentTimeMillis() - gaInitTime) + "ms");
            System.out.println("有效初代样本数: " + initialPopulation.size());

            if (!initialPopulation.isEmpty()) {
                topBest = findBest.findBest(initialPopulation, "成本", TopNumber);
                topBest.add(jsonToMap.TransJsonToMap(originalResult));
            }
        }

        // 遗传算法
        int hybridizationNumber = 0;
        List<Map<String, Object>> currentTopBest = topBest;

        while (true) {
            System.out.println((hybridizationNumber + 1) + "代迭代开始");

            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                System.out.println("优化被用户中断");
                break;
            }

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
                    resourceNum);

            System.out.println("交叉生成 " + crossedSchemes.size() + " 个方案");

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
                    loopElecByIdStart,
                    random,
                    resourceNum);
            System.out.println("变异生成 " + mutatedSchemes.size() + " 个方案");

            if (mutatedSchemes.isEmpty()) {
                System.out.println("第" + (hybridizationNumber + 1) + "代未生成有效方案，继续下一轮");
                hybridizationNumber++;
                continue;
            }

            List<Map<String, Object>> resuliList = new ArrayList<>(currentTopBest);
            resuliList.addAll(crossedSchemes);
            resuliList.addAll(mutatedSchemes);
            int numb = 0;
            while (resuliList.size() < HybridizationLessRandomSamleNumber) {
                int need = HybridizationLessRandomSamleNumber - resuliList.size();
                System.out.println("方案数量不足，需要补充 " + need + " 个方案");
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
                        loopElecByIdStart,
                        resourceNum);
                numb++;
                if (numb > AutoCompleteNumber) {
                    break;
                }
                resuliList.addAll(supplementedSchemes);
            }
            currentTopBest = findBest.findBest(resuliList, "成本", TopNumber);
            System.out.println("第" + (hybridizationNumber + 1) + "代完成，最优成本: " +
                    currentTopBest.get(0).get("成本"));

            // 修正：首次迭代（hybridizationNumber == 0）记录最优成本
            if (hybridizationNumber == 0) {
                double costTotal = Double
                        .parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double
                        .parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double
                        .parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总重量").toString());
                BestCost.put("总成本", costTotal);
                BestCost.put("总长度", costLenth);
                BestCost.put("总重量", costWeight);
            } else {
                double costTotal = Double
                        .parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double
                        .parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double
                        .parseDouble(((Map<String, Object>) currentTopBest.get(0).get("成本")).get("总重量").toString());
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
     */
    public Boolean elecResourceCheck(List<Map<String, String>> loopInfos, Map<String, List<String>> resourceNum) {
        Map<String, Map<String, Integer>> currentResource = new HashMap<>();
        Set<String> restrictedApps = resourceNum.keySet();

        for (Map<String, String> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp");
            String endApp = loopInfo.get("endApp");
            String wireType = loopInfo.get("loopWireway");

            if (wireType == null || wireType.isEmpty()) {
                continue;
            }

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
                continue;
            }

            if (restrictedApps.contains(startApp)) {
                currentResource.computeIfAbsent(startApp, k -> new HashMap<>());
                currentResource.get(startApp).merge(currentType, 1, Integer::sum);
            }
            if (restrictedApps.contains(endApp)) {
                currentResource.computeIfAbsent(endApp, k -> new HashMap<>());
                currentResource.get(endApp).merge(currentType, 1, Integer::sum);
            }
        }

        for (String appName : restrictedApps) {
            List<String> limits = resourceNum.get(appName);
            if (limits == null || limits.size() < 3)
                continue;

            Map<String, Integer> actualResource = currentResource.getOrDefault(appName, new HashMap<>());
            int actualLarge = actualResource.getOrDefault("large", 0);
            int actualMedium = actualResource.getOrDefault("medium", 0);
            int actualSmall = actualResource.getOrDefault("small", 0);

            String largeLimit = limits.get(0);
            if (!"不限".equals(largeLimit) && !largeLimit.isEmpty()) {
                try {
                    int maxLarge = Integer.parseInt(largeLimit);
                    if (actualLarge > maxLarge)
                        return false;
                } catch (NumberFormatException e) {
                }
            }
            String mediumLimit = limits.get(1);
            if (!"不限".equals(mediumLimit) && !mediumLimit.isEmpty()) {
                try {
                    int maxMedium = Integer.parseInt(mediumLimit);
                    if (actualMedium > maxMedium)
                        return false;
                } catch (NumberFormatException e) {
                }
            }
            String smallLimit = limits.get(2);
            if (!"不限".equals(smallLimit) && !smallLimit.isEmpty()) {
                try {
                    int maxSmall = Integer.parseInt(smallLimit);
                    if (actualSmall > maxSmall)
                        return false;
                } catch (NumberFormatException e) {
                }
            }
        }
        return true;
    }

    /**
     * 交叉操作
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
            Random random,
            Map<String, List<String>> resourceNum) throws Exception {

        List<Map<String, Object>> crossedSchemes = new ArrayList<>();
        int populationSize = topSchemes.size();
        System.out.println("开始交叉操作，种群大小: " + populationSize);

        List<Map<String, Object>> shuffledSchemes = new ArrayList<>(topSchemes);
        Collections.shuffle(shuffledSchemes, random);

        for (int i = 0; i < shuffledSchemes.size() - 1; i += 2) {
            if (random.nextDouble() > CrossoverRate)
                continue;

            Map<String, Object> parent1 = shuffledSchemes.get(i);
            Map<String, Object> parent2 = shuffledSchemes.get(i + 1);

            Map<String, Object> child1 = uniformCrossover(
                    parent1, parent2, targetLoops, allLoopInfos, allAppPositions,
                    elecChangeablePosition, togetherGroup, mutualGroup,
                    pointNameId, objectMapper, powerProjectCircuitInfoOutput,
                    jsonToMap, topoInfoMap, projectInfo, loopElecById, random, resourceNum);

            Map<String, Object> child2 = uniformCrossover(
                    parent2, parent1, targetLoops, allLoopInfos, allAppPositions,
                    elecChangeablePosition, togetherGroup, mutualGroup,
                    pointNameId, objectMapper, powerProjectCircuitInfoOutput,
                    jsonToMap, topoInfoMap, projectInfo, loopElecById, random, resourceNum);

            if (child1 != null)
                crossedSchemes.add(child1);
            if (child2 != null)
                crossedSchemes.add(child2);
        }
        return crossedSchemes;
    }

    /**
     * 均匀交叉（修复：增加位置一致性同步，并继承父本2的用电器位置）
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
            Random random,
            Map<String, List<String>> resourceNum) throws Exception {

        List<Map<String, String>> parent1Loops = (List<Map<String, String>>) parent1.get("loopInfos");
        List<Map<String, String>> parent2Loops = (List<Map<String, String>>) parent2.get("loopInfos");
        List<Map<String, String>> parent1Apps = (List<Map<String, String>>) parent1.get("appPositions");
        List<Map<String, String>> parent2Apps = (List<Map<String, String>>) parent2.get("appPositions");

        List<Map<String, String>> childLoops = deepCopyLoopInfos(parent1Loops);
        List<Map<String, String>> childApps = deepCopyAppPositions(parent1Apps);

        // 构建父本2的用电器位置映射
        Map<String, String> parent2AppPosMap = new HashMap<>();
        for (Map<String, String> appPos : parent2Apps) {
            parent2AppPosMap.put(appPos.get("appName"), appPos.get("unregularPointName"));
        }

        Map<String, Map<String, String>> childLoopById = new HashMap<>();
        for (Map<String, String> loop : childLoops)
            childLoopById.put(loop.get("id"), loop);

        Map<String, Map<String, String>> parent2LoopById = new HashMap<>();
        for (Map<String, String> loop : parent2Loops)
            parent2LoopById.put(loop.get("id"), loop);

        // 记录哪些用电器需要从父本2继承位置
        Set<String> appsToInheritFromParent2 = new HashSet<>();

        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");
            if (random.nextDouble() > 0.5) {
                Map<String, String> p2Loop = parent2LoopById.get(loopId);
                Map<String, String> childLoop = childLoopById.get(loopId);
                if (p2Loop != null && childLoop != null) {
                    String oldStart = childLoop.get("startApp");
                    String oldEnd = childLoop.get("endApp");
                    childLoop.put("startApp", p2Loop.get("startApp"));
                    childLoop.put("endApp", p2Loop.get("endApp"));
                    if (!p2Loop.get("startApp").equals(oldStart)) {
                        appsToInheritFromParent2.add(p2Loop.get("startApp"));
                    }
                    if (!p2Loop.get("endApp").equals(oldEnd)) {
                        appsToInheritFromParent2.add(p2Loop.get("endApp"));
                    }
                }
            }
        }

        // 从父本2继承位置（如果子代中该用电器尚无位置或位置为空）
        for (String appName : appsToInheritFromParent2) {
            boolean hasPosition = false;
            for (Map<String, String> ap : childApps) {
                if (ap.get("appName").equals(appName) && ap.get("unregularPointName") != null
                        && !ap.get("unregularPointName").isEmpty()) {
                    hasPosition = true;
                    break;
                }
            }
            if (!hasPosition && parent2AppPosMap.containsKey(appName) && parent2AppPosMap.get(appName) != null) {
                String pos = parent2AppPosMap.get(appName);
                for (Map<String, String> ap : childApps) {
                    if (ap.get("appName").equals(appName)) {
                        ap.put("unregularPointName", pos);
                        ap.put("unregularPointId", pointNameId.get(pos));
                        break;
                    }
                }
            }
        }

        enforceTogetherGroupConstraints(childLoops, childApps, togetherGroup, loopElecById, random);

        boolean success = enforceMutualGroupConstraints(childLoops, childApps, mutualGroup,
                loopElecById, elecChangeablePosition, pointNameId, random);
        if (!success)
            return null;

        // 关键修正：确保子代中同一用电器位置唯一且优先保留已有位置
        syncAppPositionsPreservingExisting(childLoops, childApps, elecChangeablePosition, pointNameId, random);

        Boolean b = elecResourceCheck(childLoops, resourceNum);
        if (!b)
            return null;

        String fingerprint = generateSchemeFingerprint(childLoops, childApps);
        if (WareHouse.contains(fingerprint))
            return null;

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
        for (Map<String, String> loop : childLoops)
            loopById.put(loop.get("id"), loop);

        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            List<String> memberLoopIds = entry.getValue();
            String standardEndApp = null;
            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null) {
                    standardEndApp = loop.get("endApp");
                    break;
                }
            }
            if (standardEndApp == null)
                continue;
            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null)
                    loop.put("endApp", standardEndApp);
            }
        }
    }

    /**
     * 强制满足互斥组约束（修正：复用已有位置）
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
        for (Map<String, String> loop : childLoops)
            loopById.put(loop.get("id"), loop);

        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            List<String> memberLoopIds = entry.getValue();
            Set<String> usedEndApps = new HashSet<>();
            List<Map<String, String>> conflictedLoops = new ArrayList<>();

            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null)
                    continue;
                String endApp = loop.get("endApp");
                if (usedEndApps.contains(endApp)) {
                    conflictedLoops.add(loop);
                } else {
                    usedEndApps.add(endApp);
                }
            }

            for (Map<String, String> loop : conflictedLoops) {
                String loopId = loop.get("id");
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty())
                    continue;

                String newEndApp = null;
                for (String endApp : allowedEndApps) {
                    if (!usedEndApps.contains(endApp)) {
                        newEndApp = endApp;
                        break;
                    }
                }
                if (newEndApp == null)
                    return false;

                loop.put("endApp", newEndApp);
                usedEndApps.add(newEndApp);

                // 修正：复用该用电器已有位置，若无则随机
                String existingPosition = null;
                for (Map<String, String> appPos : childApps) {
                    if (appPos.get("appName").equals(newEndApp)) {
                        existingPosition = appPos.get("unregularPointName");
                        break;
                    }
                }
                if (existingPosition != null && !existingPosition.isEmpty()) {
                    // 位置已存在，无需操作
                } else {
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
                }
            }
        }
        return true;
    }

    /**
     * 变异操作（修正：位置随机加入概率保留，并增加约束修复）
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
            Map<String, Set<String>> loopElecByIdStart,
            Random random,
            Map<String, List<String>> resourceNum) throws Exception {

        List<Map<String, Object>> mutatedSchemes = new ArrayList<>();
        System.out.println("开始对 " + topSchemes.size() + " 个方案进行多分支变异...");

        for (int schemeIdx = 0; schemeIdx < topSchemes.size(); schemeIdx++) {
            Map<String, Object> scheme = topSchemes.get(schemeIdx);
            List<Map<String, String>> originalLoops = (List<Map<String, String>>) scheme.get("loopInfos");
            List<Map<String, String>> originalApps = (List<Map<String, String>>) scheme.get("appPositions");

            List<List<Map<String, String>>> constrainedVariants = generateConstrainedVariants(
                    originalLoops, originalApps, targetLoops, elecChangeablePosition,
                    togetherGroup, mutualGroup, pointNameId, random, loopElecById);
            List<List<Map<String, String>>> unconstrainedVariants = generateUnconstrainedVariants(
                    originalLoops, originalApps, targetLoops, elecChangeablePosition,
                    pointNameId, random, loopElecById, loopElecByIdStart, togetherGroup, mutualGroup);
            List<List<Map<String, String>>> mixedVariants = generateMixedVariants(
                    originalLoops, originalApps, targetLoops, elecChangeablePosition,
                    togetherGroup, mutualGroup, pointNameId, random, loopElecById, loopElecByIdStart, togetherGroup,
                    mutualGroup);

            List<List<Map<String, String>>> allVariants = new ArrayList<>();
            allVariants.addAll(constrainedVariants);
            allVariants.addAll(unconstrainedVariants);
            allVariants.addAll(mixedVariants);

            System.out.println("方案 " + (schemeIdx + 1) + " 生成 " + allVariants.size() + " 个变异候选");

            for (List<Map<String, String>> variantLoops : allVariants) {
                List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(originalApps);
                // 变异中位置随机概率保留（0.3 表示 30% 概率重新选位）
                syncAppPositionsWithProbability(variantLoops, appPositionsCopy, elecChangeablePosition, pointNameId,
                        random, 0.3);

                // ========== 修复4：对变异候选进行约束修复，确保联动组和互斥组正确 ==========
                enforceTogetherGroupConstraints(variantLoops, appPositionsCopy, togetherGroup, loopElecById, random);
                boolean ok = enforceMutualGroupConstraints(variantLoops, appPositionsCopy, mutualGroup,
                        loopElecById, elecChangeablePosition, pointNameId, random);
                if (!ok)
                    continue;

                Boolean b = elecResourceCheck(variantLoops, resourceNum);
                if (!b)
                    continue;

                String fingerprint = generateSchemeFingerprint(variantLoops, appPositionsCopy);
                if (WareHouse.contains(fingerprint))
                    continue;

                Map<String, Object> tempJsonMap = new HashMap<>();
                tempJsonMap.put("loopInfos", variantLoops);
                tempJsonMap.put("appPositions", appPositionsCopy);
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
                        map.put("finishStatue", "mutated");
                        map.put("initializationScheme", false);
                        WareHouse.add(fingerprint);
                        mutatedSchemes.add(map);
                    }
                } catch (Exception e) {
                    System.err.println("变异方案计算失败，跳过: " + e.getMessage());
                }
            }
        }
        System.out.println("变异完成，生成 " + mutatedSchemes.size() + " 个有效方案");
        return mutatedSchemes;
    }

    // 以下为辅助方法（保持原有逻辑，但部分签名修改以支持约束检查）

    private List<List<Map<String, String>>> generateConstrainedVariants(
            List<Map<String, String>> originalLoops,
            List<Map<String, String>> originalApps,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById) {
        // 此方法保持原样（已正确实现）
        List<List<Map<String, String>>> variants = new ArrayList<>();
        Set<String> targetLoopIdSet = new HashSet<>();
        for (Map<String, String> targetLoop : targetLoops)
            targetLoopIdSet.add(targetLoop.get("id"));

        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : originalLoops)
            loopById.put(loop.get("id"), loop);

        // 联动组变异
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }
            if (!hasTargetLoop)
                continue;

            Set<String> endAppIntersection = null;
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(loopId);
                    if (lp != null && lp.get("endApp") != null)
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    else
                        allowedEndApps = Collections.emptySet();
                }
                if (endAppIntersection == null)
                    endAppIntersection = new HashSet<>(allowedEndApps);
                else
                    endAppIntersection.retainAll(allowedEndApps);
            }
            if (endAppIntersection == null || endAppIntersection.isEmpty())
                continue;

            for (String endApp : endAppIntersection) {
                List<Map<String, String>> copyVariant = deepCopyLoopInfos(originalLoops);
                for (String loopId : allMemberLoopIds) {
                    for (Map<String, String> loop : copyVariant) {
                        if (loop.get("id").equals(loopId))
                            loop.put("endApp", endApp);
                    }
                }
                variants.add(copyVariant);
            }
        }

        // 互斥组变异（简化版）
        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            String mutualId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }
            if (!hasTargetLoop)
                continue;

            List<Set<String>> optionsList = new ArrayList<>();
            Map<Integer, String> indexToLoopId = new HashMap<>();
            int idx = 0;
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                    optionsList.add(allowedEndApps);
                    indexToLoopId.put(idx, loopId);
                    idx++;
                }
            }
            if (optionsList.isEmpty())
                continue;

            for (int attempt = 0; attempt < Math.min(5, optionsList.get(0).size()); attempt++) {
                List<Map<String, String>> copyVariant = deepCopyLoopInfos(originalLoops);
                Set<String> usedEndApps = new HashSet<>();
                boolean success = true;
                for (int i = 0; i < optionsList.size(); i++) {
                    String loopId = indexToLoopId.get(i);
                    Set<String> options = optionsList.get(i);
                    String selectedEndApp = null;
                    for (String endApp : options) {
                        if (!usedEndApps.contains(endApp)) {
                            selectedEndApp = endApp;
                            break;
                        }
                    }
                    if (selectedEndApp == null) {
                        success = false;
                        break;
                    }
                    usedEndApps.add(selectedEndApp);
                    for (Map<String, String> loop : copyVariant) {
                        if (loop.get("id").equals(loopId))
                            loop.put("endApp", selectedEndApp);
                    }
                }
                if (success)
                    variants.add(copyVariant);
            }
        }
        return variants;
    }

    /**
     * 生成无约束回路的变异方案（修复：只处理真正无约束的回路）
     */
    private List<List<Map<String, String>>> generateUnconstrainedVariants(
            List<Map<String, String>> originalLoops,
            List<Map<String, String>> originalApps,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup) {

        List<List<Map<String, String>>> variants = new ArrayList<>();

        // 构建约束回路ID集合
        Set<String> constrainedLoopIds = new HashSet<>();
        for (List<String> ids : togetherGroup.values())
            constrainedLoopIds.addAll(ids);
        for (List<String> ids : mutualGroup.values())
            constrainedLoopIds.addAll(ids);

        // 筛选出无约束的目标回路
        List<Map<String, String>> unconstrainedLoops = new ArrayList<>();
        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");
            if (!constrainedLoopIds.contains(loopId)) {
                unconstrainedLoops.add(targetLoop);
            }
        }

        if (unconstrainedLoops.isEmpty())
            return variants;

        // 对每个无约束回路，尝试多个不同的终点和起点组合
        for (Map<String, String> unconstrainedLoop : unconstrainedLoops) {
            String loopId = unconstrainedLoop.get("id");

            Set<String> allowedEndApps = loopElecById.get(loopId);
            Set<String> allowedStartApps = loopElecByIdStart.get(loopId);

            if ((allowedEndApps == null || allowedEndApps.isEmpty()) &&
                    (allowedStartApps == null || allowedStartApps.isEmpty())) {
                continue;
            }

            // 最多生成3个变异方案
            int maxAttempts = Math.min(3, Math.max(
                    allowedEndApps != null ? allowedEndApps.size() : 1,
                    allowedStartApps != null ? allowedStartApps.size() : 1));
            List<String> endAppList = allowedEndApps != null ? new ArrayList<>(allowedEndApps)
                    : Collections.singletonList(null);
            List<String> startAppList = allowedStartApps != null ? new ArrayList<>(allowedStartApps)
                    : Collections.singletonList(null);
            Collections.shuffle(endAppList, random);
            Collections.shuffle(startAppList, random);

            for (int i = 0; i < maxAttempts; i++) {
                List<Map<String, String>> copyVariant = deepCopyLoopInfos(originalLoops);
                String selectedEndApp = endAppList.get(i % endAppList.size());
                String selectedStartApp = startAppList.get(i % startAppList.size());

                for (Map<String, String> loop : copyVariant) {
                    if (loop.get("id").equals(loopId)) {
                        if (selectedEndApp != null)
                            loop.put("endApp", selectedEndApp);
                        if (selectedStartApp != null)
                            loop.put("startApp", selectedStartApp);
                    }
                }
                variants.add(copyVariant);
            }
        }
        return variants;
    }

    /**
     * 生成混合变异方案（修复：只对无约束回路进行变异）
     */
    private List<List<Map<String, String>>> generateMixedVariants(
            List<Map<String, String>> originalLoops,
            List<Map<String, String>> originalApps,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Map<String, List<String>> allTogetherGroup,
            Map<String, List<String>> allMutualGroup) {

        List<List<Map<String, String>>> variants = new ArrayList<>();
        int mixedCount = 3;

        // 构建约束回路ID集合
        Set<String> constrainedLoopIds = new HashSet<>();
        for (List<String> ids : allTogetherGroup.values())
            constrainedLoopIds.addAll(ids);
        for (List<String> ids : allMutualGroup.values())
            constrainedLoopIds.addAll(ids);

        // 筛选出无约束的目标回路
        List<Map<String, String>> unconstrainedTargets = new ArrayList<>();
        for (Map<String, String> targetLoop : targetLoops) {
            if (!constrainedLoopIds.contains(targetLoop.get("id"))) {
                unconstrainedTargets.add(targetLoop);
            }
        }
        if (unconstrainedTargets.isEmpty())
            return variants;

        for (int m = 0; m < mixedCount; m++) {
            List<Map<String, String>> copyVariant = deepCopyLoopInfos(originalLoops);
            // 随机选择 20% 的无约束回路进行变异
            int mutationCount = Math.max(1, (int) (unconstrainedTargets.size() * 0.2));
            List<Map<String, String>> shuffledTargets = new ArrayList<>(unconstrainedTargets);
            Collections.shuffle(shuffledTargets, random);
            List<Map<String, String>> selectedTargets = shuffledTargets.subList(0,
                    Math.min(mutationCount, shuffledTargets.size()));

            for (Map<String, String> targetLoop : selectedTargets) {
                String loopId = targetLoop.get("id");
                // 随机变异终点
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                    List<String> endAppList = new ArrayList<>(allowedEndApps);
                    String newEndApp = endAppList.get(random.nextInt(endAppList.size()));
                    for (Map<String, String> loop : copyVariant) {
                        if (loop.get("id").equals(loopId)) {
                            loop.put("endApp", newEndApp);
                        }
                    }
                }
                // 随机变异起点
                Set<String> allowedStartApps = loopElecByIdStart.get(loopId);
                if (allowedStartApps != null && !allowedStartApps.isEmpty()) {
                    List<String> startAppList = new ArrayList<>(allowedStartApps);
                    String newStartApp = startAppList.get(random.nextInt(startAppList.size()));
                    for (Map<String, String> loop : copyVariant) {
                        if (loop.get("id").equals(loopId)) {
                            loop.put("startApp", newStartApp);
                        }
                    }
                }
            }
            variants.add(copyVariant);
        }
        return variants;
    }

    /**
     * 位置同步：仅对尚无位置的用电器随机选位（保留已有位置）
     */
    private void syncAppPositionsPreservingExisting(
            List<Map<String, String>> loops,
            List<Map<String, String>> appPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random) {
        Set<String> appsInLoops = new HashSet<>();
        for (Map<String, String> loop : loops) {
            if (loop.get("startApp") != null)
                appsInLoops.add(loop.get("startApp"));
            if (loop.get("endApp") != null)
                appsInLoops.add(loop.get("endApp"));
        }
        for (String appName : appsInLoops) {
            boolean hasPosition = false;
            for (Map<String, String> ap : appPositions) {
                if (ap.get("appName").equals(appName) && ap.get("unregularPointName") != null
                        && !ap.get("unregularPointName").isEmpty()) {
                    hasPosition = true;
                    break;
                }
            }
            if (!hasPosition) {
                List<String> positions = elecChangeablePosition.get(appName);
                if (positions != null && !positions.isEmpty()) {
                    String chosenPos = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> ap : appPositions) {
                        if (ap.get("appName").equals(appName)) {
                            ap.put("unregularPointName", chosenPos);
                            ap.put("unregularPointId", pointNameId.get(chosenPos));
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 位置同步：以一定概率重新选位
     */
    private void syncAppPositionsWithProbability(
            List<Map<String, String>> loops,
            List<Map<String, String>> appPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random,
            double rechooseProbability) {
        Set<String> appsInLoops = new HashSet<>();
        for (Map<String, String> loop : loops) {
            if (loop.get("startApp") != null)
                appsInLoops.add(loop.get("startApp"));
            if (loop.get("endApp") != null)
                appsInLoops.add(loop.get("endApp"));
        }
        for (String appName : appsInLoops) {
            boolean rechoose = random.nextDouble() < rechooseProbability;
            if (!rechoose) {
                boolean hasPos = false;
                for (Map<String, String> ap : appPositions) {
                    if (ap.get("appName").equals(appName) && ap.get("unregularPointName") != null
                            && !ap.get("unregularPointName").isEmpty()) {
                        hasPos = true;
                        break;
                    }
                }
                if (hasPos)
                    continue;
            }
            List<String> positions = elecChangeablePosition.get(appName);
            if (positions != null && !positions.isEmpty()) {
                String chosenPos = positions.get(random.nextInt(positions.size()));
                for (Map<String, String> ap : appPositions) {
                    if (ap.get("appName").equals(appName)) {
                        ap.put("unregularPointName", chosenPos);
                        ap.put("unregularPointId", pointNameId.get(chosenPos));
                        break;
                    }
                }
            }
        }
    }

    /**
     * 深拷贝回路信息列表
     */
    private List<Map<String, String>> deepCopyLoopInfos(List<Map<String, String>> source) {
        if (source == null)
            return null;
        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> map : source)
            copy.add(new HashMap<>(map));
        return copy;
    }

    /**
     * 深拷贝位置列表
     */
    private List<Map<String, String>> deepCopyAppPositions(List<Map<String, String>> source) {
        if (source == null)
            return null;
        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> map : source)
            copy.add(new HashMap<>(map));
        return copy;
    }

    /**
     * 生成唯一指纹（完整版）
     */
    private String generateSchemeFingerprint(List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions) {
        StringBuilder fingerprint = new StringBuilder();
        List<Map<String, String>> sortedLoops = new ArrayList<>(loopInfos);
        sortedLoops.sort((a, b) -> a.get("id").compareTo(b.get("id")));
        fingerprint.append("LOOPS:");
        for (Map<String, String> loop : sortedLoops) {
            String loopId = loop.get("id");
            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            if (startApp != null && endApp != null) {
                fingerprint.append(loopId).append("=").append(startApp).append("|").append(endApp).append(";");
            }
        }
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
            if (appName != null && position != null && !position.isEmpty()) {
                fingerprint.append(appName).append("=").append(position).append(";");
            }
        }
        return fingerprint.toString();
    }

    /**
     * 生成初代种群
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
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Map<String, List<String>> resourceNum) throws Exception {
        Random random = new Random();
        List<Map<String, Object>> population = new ArrayList<>();
        int maxAttempts = populationSize * 10;
        int attemptCount = 0;
        System.out.println("开始生成 " + populationSize + " 个初代个体...");
        while (population.size() < populationSize && attemptCount < maxAttempts) {
            attemptCount++;
            List<Map<String, String>> loopInfoCopy = new ArrayList<>();
            for (Map<String, String> loop : allLoopInfos)
                loopInfoCopy.add(new HashMap<>(loop));
            List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(allAppPositions);

            boolean success = perturbConstrainedLoops(
                    loopInfoCopy, appPositionsCopy, targetLoops, elecChangeablePosition,
                    togetherGroup, mutualGroup, pointNameId, random, loopElecById);
            if (!success)
                continue;

            perturbUnconstrainedLoops(
                    loopInfoCopy, appPositionsCopy, targetLoops, elecChangeablePosition,
                    pointNameId, random, loopElecById, loopElecByIdStart);

            Boolean b = elecResourceCheck(loopInfoCopy, resourceNum);
            if (!b)
                continue;

            String fingerprint = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
            if (WareHouse.contains(fingerprint))
                continue;
            WareHouse.add(fingerprint);

            Map<String, Object> tempJsonMap = new HashMap<>();
            tempJsonMap.put("loopInfos", loopInfoCopy);
            tempJsonMap.put("appPositions", appPositionsCopy);
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
                    map.put("loopInfos", loopInfoCopy);
                    map.put("appPositions", appPositionsCopy);
                    map.put("schemeIndex", population.size() + 1);
                    population.add(map);
                    if (population.size() % 10 == 0)
                        System.out.println("已生成 " + population.size() + " 个有效个体...");
                }
            } catch (Exception e) {
                System.err.println("方案计算失败，跳过: " + e.getMessage());
            }
        }
        System.out.println("初代种群生成完成，共 " + population.size() + " 个个体，尝试次数: " + attemptCount);
        return population;
    }

    /**
     * 扰动有约束回路（已修正互斥组基于 endApp，并增加了起点随机）
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
        Set<String> targetLoopIdSet = new HashSet<>();
        for (Map<String, String> targetLoop : targetLoops)
            targetLoopIdSet.add(targetLoop.get("id"));
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy)
            loopById.put(loop.get("id"), loop);
        Map<String, Set<String>> mutualGroupUsedEndApps = new HashMap<>();

        // 处理联动组
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            List<String> allMemberLoopIds = entry.getValue();
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }
            if (!hasTargetLoop)
                continue;

            Set<String> endAppIntersection = null;
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(loopId);
                    if (lp != null && lp.get("endApp") != null)
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    else
                        allowedEndApps = Collections.emptySet();
                }
                if (endAppIntersection == null)
                    endAppIntersection = new HashSet<>(allowedEndApps);
                else
                    endAppIntersection.retainAll(allowedEndApps);
            }
            if (endAppIntersection == null || endAppIntersection.isEmpty())
                return false;

            List<String> endAppList = new ArrayList<>(endAppIntersection);
            String selectedEndApp = endAppList.get(random.nextInt(endAppList.size()));
            List<String> positions = elecChangeablePosition.get(selectedEndApp);
            if (positions == null || positions.isEmpty())
                return false;
            String selectedPosition = positions.get(random.nextInt(positions.size()));

            String mutualId = null;
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null && loop.get("mutualExclusion") != null && !loop.get("mutualExclusion").isEmpty()) {
                    mutualId = loop.get("mutualExclusion");
                    break;
                }
            }
            if (mutualId != null) {
                Set<String> usedEndApps = mutualGroupUsedEndApps.computeIfAbsent(mutualId, k -> new HashSet<>());
                if (usedEndApps.contains(selectedEndApp))
                    return false;
                usedEndApps.add(selectedEndApp);
            }

            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null)
                    continue;
                loop.put("endApp", selectedEndApp);
                for (Map<String, String> appPos : appPositionsCopy) {
                    if (appPos.get("appName").equals(selectedEndApp)) {
                        appPos.put("unregularPointName", selectedPosition);
                        appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                        break;
                    }
                }
                String startApp = loop.get("startApp");
                if (startApp != null && !startApp.isEmpty()) {
                    List<String> startPositions = elecChangeablePosition.get(startApp);
                    if (startPositions != null && !startPositions.isEmpty()) {
                        String randomStartPos = startPositions.get(random.nextInt(startPositions.size()));
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName").equals(startApp)) {
                                appPos.put("unregularPointName", randomStartPos);
                                appPos.put("unregularPointId", pointNameId.get(randomStartPos));
                                break;
                            }
                        }
                    }
                }
            }
        }

        // 处理独立互斥回路
        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            String mutualId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }
            if (!hasTargetLoop)
                continue;

            Set<String> usedEndApps = mutualGroupUsedEndApps.computeIfAbsent(mutualId, k -> new HashSet<>());
            for (String loopId : allMemberLoopIds) {
                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }
                if (inTogetherGroup)
                    continue;

                Map<String, String> loop = loopById.get(loopId);
                if (loop == null)
                    continue;
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    allowedEndApps = Collections.singleton(loop.get("endApp"));
                }
                List<String> candidates = new ArrayList<>(allowedEndApps);
                candidates.removeAll(usedEndApps);
                if (candidates.isEmpty())
                    return false;
                String selectedEndApp = candidates.get(random.nextInt(candidates.size()));
                usedEndApps.add(selectedEndApp);
                loop.put("endApp", selectedEndApp);

                List<String> positions = elecChangeablePosition.get(selectedEndApp);
                if (positions != null && !positions.isEmpty()) {
                    String selectedPosition = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(selectedEndApp)) {
                            appPos.put("unregularPointName", selectedPosition);
                            appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                            break;
                        }
                    }
                }
                String startApp = loop.get("startApp");
                if (startApp != null && !startApp.isEmpty()) {
                    List<String> startPositions = elecChangeablePosition.get(startApp);
                    if (startPositions != null && !startPositions.isEmpty()) {
                        String randomStartPos = startPositions.get(random.nextInt(startPositions.size()));
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName").equals(startApp)) {
                                appPos.put("unregularPointName", randomStartPos);
                                appPos.put("unregularPointId", pointNameId.get(randomStartPos));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * 扰动无约束回路（同时处理起点和终点的连接关系及位置）
     */
    private void perturbUnconstrainedLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart) {

        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 收集所有需要随机化位置的用电器（起点和终点）
        Set<String> appsToRandomize = new HashSet<>();

        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");
            Map<String, String> loop = loopById.get(loopId);
            if (loop == null)
                continue;

            String together = loop.get("changeTogether");
            String mutual = loop.get("mutualExclusion");
            if ((together != null && !together.isEmpty()) || (mutual != null && !mutual.isEmpty())) {
                continue; // 有约束回路已在 perturbConstrainedLoops 中处理，这里不扰动
            }

            // 随机改变终点用电器
            Set<String> allowedEndApps = loopElecById.get(loopId);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                List<String> endAppList = new ArrayList<>(allowedEndApps);
                String newEndApp = endAppList.get(random.nextInt(endAppList.size()));
                loop.put("endApp", newEndApp);
            }

            // 随机改变起点用电器
            Set<String> allowedStartApps = loopElecByIdStart.get(loopId);
            if (allowedStartApps != null && !allowedStartApps.isEmpty()) {
                List<String> startAppList = new ArrayList<>(allowedStartApps);
                String newStartApp = startAppList.get(random.nextInt(startAppList.size()));
                loop.put("startApp", newStartApp);
            }

            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            if (startApp != null)
                appsToRandomize.add(startApp);
            if (endApp != null)
                appsToRandomize.add(endApp);
        }

        // 统一为每个用电器随机选择一个位置（保证唯一性）
        for (String appName : appsToRandomize) {
            List<String> positions = elecChangeablePosition.get(appName);
            if (positions != null && !positions.isEmpty()) {
                String chosenPos = positions.get(random.nextInt(positions.size()));
                for (Map<String, String> appPos : appPositionsCopy) {
                    if (appPos.get("appName").equals(appName)) {
                        appPos.put("unregularPointName", chosenPos);
                        appPos.put("unregularPointId", pointNameId.get(chosenPos));
                        break;
                    }
                }
            }
        }
    }

    /**
     * 枚举所有可行方案
     * 
     * @param targetLoops
     * @param elecChangeablePosition
     * @param togetherGroup
     * @param mutualGroup
     * @param allLoopInfos
     * @param loopElecById           回路终点可连接的
     * @param loopElecByIdStart
     */
    private void enumerateAllSchemes(
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            List<Map<String, String>> allLoopInfos,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart) {
        long startTime = System.currentTimeMillis();
        // 回路id-回路信息
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : allLoopInfos)
            loopById.put(lp.get("id"), lp);

        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        // 判断哪些回路已经被分组覆盖，哪些是未处理
        Set<String> coveredLoopIds = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue(); // 该组组团一起变的分支id
            Set<String> endAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(lid);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) { // 找回路可连接的终点用电器，如果为null
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("endApp") != null)
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    else
                        allowedEndApps = Collections.emptySet();
                }
                if (endAppIntersection == null)
                    endAppIntersection = new HashSet<>(allowedEndApps);
                else
                    endAppIntersection.retainAll(allowedEndApps); // 组团一起变的取交集
                coveredLoopIds.add(lid);
            }
            if (endAppIntersection != null && !endAppIntersection.isEmpty()) {
                varDomains.put("E_G_" + groupId, new ArrayList<>(endAppIntersection));
            }
        }
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            // 如果回路已经被处理过则跳过这条回路
            if (coveredLoopIds.contains(lid))
                continue;
            Set<String> allowedEndApps = loopElecById.get(lid);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                // 存储这跟回路终点可选用电器列表
                varDomains.put("E_L_" + lid, new ArrayList<>(allowedEndApps));
            } else {
                // 如果没有预设的可选端子，检查该回路终点用电器位置是否可变
                String endApp = lp.get("endApp");
                if (endApp != null && !endApp.isEmpty()) {
                    List<String> changeablePositions = elecChangeablePosition.get(endApp);
                    if (changeablePositions != null && !changeablePositions.isEmpty()) {
                        // 位置可变，使用当前端子作为唯一选项（位置会在 generateSchemesForAssignment 中枚举）
                        varDomains.put("E_L_" + lid, Collections.singletonList(endApp));
                    }
                }
            }
        }

        // 起点用电器变量域构建
        Set<String> coveredStartLoopIds = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();
            Set<String> startAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedStartApps = loopElecByIdStart != null ? loopElecByIdStart.get(lid) : null;
                if (allowedStartApps == null || allowedStartApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("startApp") != null)
                        allowedStartApps = Collections.singleton(lp.get("startApp"));
                    else
                        allowedStartApps = Collections.emptySet();
                }
                if (startAppIntersection == null)
                    startAppIntersection = new HashSet<>(allowedStartApps);
                else
                    startAppIntersection.retainAll(allowedStartApps);
                coveredStartLoopIds.add(lid);
            }
            if (startAppIntersection != null && !startAppIntersection.isEmpty()) {
                varDomains.put("S_G_" + groupId, new ArrayList<>(startAppIntersection));
            }
        }
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            if (coveredStartLoopIds.contains(lid))
                continue;
            Set<String> allowedStartApps = loopElecByIdStart != null ? loopElecByIdStart.get(lid) : null;
            if (allowedStartApps != null && !allowedStartApps.isEmpty()) {
                varDomains.put("S_L_" + lid, new ArrayList<>(allowedStartApps));
            }
        }

        Map<String, List<String>> varKeyToMutualIds = new LinkedHashMap<>();
        // 遍历所有目标回路，找出哪些变量受到互斥约束
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");
            if (mutual == null || mutual.isEmpty())
                continue;
            String vk = (together != null && !together.isEmpty()) ? "E_G_" + together : "E_L_" + lid;
            varKeyToMutualIds.computeIfAbsent(vk, k -> new ArrayList<>()).add(mutual);
        }
        // 建立互斥组->变量列表的反向映射，组团和互斥约束只对终点连接关系的回路生效
        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : varKeyToMutualIds.entrySet()) {
            String varKey = e.getKey();
            for (String mutualId : e.getValue()) {
                List<String> varList = mutualIdToVarKeys.computeIfAbsent(mutualId, k -> new ArrayList<>());
                if (!varList.contains(varKey))
                    varList.add(varKey);
            }
        }
        // 收集所有受互斥组约束影响的变量，方便后续回溯算法进行约束检查和剪枝
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualIds.keySet()); // 记录哪些变量参与了互斥约束，回溯时检查
        List<String> varKeys = new ArrayList<>(varDomains.keySet()); // 所有待复制的变量列表

        // 统一入口：连接关系变化和用电器位置变化都参与枚举（乘积关系）
        // 当 varKeys 为空时（无连接关系变化），仍为"空赋值"枚举所有位置组合
        System.out.println("开始回溯枚举，变量数: " + varKeys.size()
                + "（连接关系），位置变化: " + (elecChangeablePosition != null ? elecChangeablePosition.size() : 0) + " 个用电器");
        Map<String, String> currentAssignment = new LinkedHashMap<>(); // 当前赋值状态
        Set<String> usedEndApps = new HashSet<>(); // 已被使用的终点用电器集合(用于互斥剪枝)
        enumerateSchemesByBacktrack(
                varDomains, mutualIdToVarKeys, varsInAnyMutualGroup,
                0, varKeys, currentAssignment, usedEndApps,
                targetLoops, loopById, elecChangeablePosition, loopElecByIdStart);
        System.out.println("枚举完成，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private void enumerateSchemesByBacktrack(
            Map<String, List<String>> varDomains,
            Map<String, List<String>> mutualIdToVarKeys,
            Set<String> varsInMutualGroup,
            int varIndex,
            List<String> varKeys,
            Map<String, String> currentAssignment,
            Set<String> usedEndApps,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, Set<String>> loopElecByIdStart) {
        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            System.out.println("优化被用户中断");
            return;
        }
        if (enumeratedSchemes.size() >= caseNumbe) {
            System.out.println("枚举方案数已达到限制(" + caseNumbe + ")，提前退出");
            return;
        }
        if (varIndex == varKeys.size()) {
            // 连接关系赋值完成，枚举所有用电器位置组合
            generateSchemesForAssignment(
                    currentAssignment, targetLoops, loopById, elecChangeablePosition, loopElecByIdStart);
            return;
        }
        String varKey = varKeys.get(varIndex);
        List<String> domain = varDomains.get(varKey);
        if (domain == null || domain.isEmpty())
            return;
        boolean isInMutualGroup = varsInMutualGroup.contains(varKey);
        for (String endApp : domain) {
            if (isInMutualGroup && usedEndApps.contains(endApp))
                continue;
            currentAssignment.put(varKey, endApp);
            usedEndApps.add(endApp);
            enumerateSchemesByBacktrack(
                    varDomains, mutualIdToVarKeys, varsInMutualGroup,
                    varIndex + 1, varKeys, currentAssignment, usedEndApps,
                    targetLoops, loopById, elecChangeablePosition, loopElecByIdStart);
            if (enumeratedSchemes.size() >= caseNumbe)
                break;
            usedEndApps.remove(endApp);
            currentAssignment.remove(varKey);
        }
    }

    /**
     * @Description: 统一入口——对给定的连接关系赋值，枚举所有用电器位置组合
     *               连接关系变化和用电器位置变化是乘积关系：
     *               一条回路 = 起点用电器选项 × 起点位置选项 × 终点用电器选项 × 终点位置选项
     *               全局方案 = 所有回路选项的乘积（受互斥/同组约束）
     * @input: assignment 当前连接关系赋值（varKeys 为空时为空 Map）
     * @Complexity: O(Π(p_i) * L)，p_i 为每个有可变位置的用电器的位置数，L 为回路数
     */
    private void generateSchemesForAssignment(
            Map<String, String> assignment,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, Set<String>> loopElecByIdStart) {
        if (enumeratedSchemes.size() >= caseNumbe) {
            return;
        }
        // Step 1: 解析每个回路的起点/终点用电器（从 assignment 中获取枚举值）
        Map<String, String> loopToStartApp = new LinkedHashMap<>();
        Map<String, String> loopToEndApp = new LinkedHashMap<>();
        Set<String> affectedLoopIds = new LinkedHashSet<>();

        for (Map<String, String> loop : targetLoops) {
            String loopId = loop.get("id");
            affectedLoopIds.add(loopId);
            String together = loop.get("changeTogether");
            if (together != null && !together.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (together.equals(allLoop.get("changeTogether"))) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
            String mutual = loop.get("mutualExclusion");
            if (mutual != null && !mutual.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (mutual.equals(allLoop.get("mutualExclusion"))) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
        }

        for (String loopId : affectedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop == null) {
                continue;
            }
            String originalStartApp = loop.get("startApp");
            String originalEndApp = loop.get("endApp");
            String together = loop.get("changeTogether");

            String selectedEndApp = originalEndApp;
            if (together != null && !together.isEmpty()) {
                String assigned = assignment.get("E_G_" + together);
                if (assigned != null) {
                    selectedEndApp = assigned;
                }
            } else {
                String assigned = assignment.get("E_L_" + loopId);
                if (assigned != null) {
                    selectedEndApp = assigned;
                }
            }

            String selectedStartApp = originalStartApp;
            if (together != null && !together.isEmpty()) {
                String assigned = assignment.get("S_G_" + together);
                if (assigned != null) {
                    selectedStartApp = assigned;
                }
            } else {
                String assigned = assignment.get("S_L_" + loopId);
                if (assigned != null) {
                    selectedStartApp = assigned;
                }
            }

            loopToStartApp.put(loopId, selectedStartApp);
            loopToEndApp.put(loopId, selectedEndApp);
        }

        // Step 2: 收集所有出现过的用电器中，哪些有可变位置
        Map<String, List<String>> appPositionDomains = new LinkedHashMap<>();
        for (String loopId : affectedLoopIds) {
            String startApp = loopToStartApp.get(loopId);
            String endApp = loopToEndApp.get(loopId);
            if (startApp != null && !startApp.isEmpty() && !appPositionDomains.containsKey(startApp)) {
                List<String> positions = elecChangeablePosition.get(startApp);
                if (positions != null && !positions.isEmpty()) {
                    appPositionDomains.put(startApp, positions);
                }
            }
            if (endApp != null && !endApp.isEmpty() && !appPositionDomains.containsKey(endApp)) {
                List<String> positions = elecChangeablePosition.get(endApp);
                if (positions != null && !positions.isEmpty()) {
                    appPositionDomains.put(endApp, positions);
                }
            }
        }

        // Step 3: 枚举所有位置组合，每个组合生成一个方案
        if (appPositionDomains.isEmpty()) {
            // 无位置变化，直接生成一个方案
            Map<String, String> scheme = buildSchemeFromAssignment(
                    targetLoops, loopById, new HashMap<>(),
                    loopToStartApp, loopToEndApp, affectedLoopIds);
            if (!scheme.isEmpty()) {
                enumeratedSchemes.add(scheme);
            }
            return;
        }

        List<String> appNames = new ArrayList<>(appPositionDomains.keySet());
        Map<String, String> currentPositions = new LinkedHashMap<>();
        enumeratePositionsForAssignment(
                appPositionDomains, appNames, 0, currentPositions,
                targetLoops, loopById, loopToStartApp, loopToEndApp, affectedLoopIds);
    }

    /**
     * 回溯枚举所有用电器位置组合，每到达叶子节点生成一个方案
     * 由 generateSchemesForAssignment 调用，对"连接关系赋值"后的方案进行位置组合枚举
     */
    private void enumeratePositionsForAssignment(
            Map<String, List<String>> appPositionDomains,
            List<String> appNames,
            int index,
            Map<String, String> currentPositions,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, String> loopToStartApp,
            Map<String, String> loopToEndApp,
            Set<String> affectedLoopIds) {
        if (enumeratedSchemes.size() >= caseNumbe) {
            return;
        }
        if (index == appNames.size()) {
            Map<String, String> scheme = buildSchemeFromAssignment(
                    targetLoops, loopById, currentPositions,
                    loopToStartApp, loopToEndApp, affectedLoopIds);
            if (!scheme.isEmpty()) {
                enumeratedSchemes.add(scheme);
                if (enumeratedSchemes.size() % 100 == 0) {
                    System.out.println("已枚举 " + enumeratedSchemes.size() + " 个方案...");
                }
            }
            return;
        }
        String appName = appNames.get(index);
        List<String> positions = appPositionDomains.get(appName);
        for (String pos : positions) {
            if (enumeratedSchemes.size() >= caseNumbe) {
                break;
            }
            currentPositions.put(appName, pos);
            enumeratePositionsForAssignment(
                    appPositionDomains, appNames, index + 1, currentPositions,
                    targetLoops, loopById, loopToStartApp, loopToEndApp, affectedLoopIds);
        }
        currentPositions.remove(appName);
    }

    /**
     * 根据（连接关系赋值 + 位置组合）构建一个方案 Map
     * 格式: 回路ID -> "起点用电器|终点用电器|起点位置|终点位置"
     */
    private Map<String, String> buildSchemeFromAssignment(
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, String> currentPositions,
            Map<String, String> loopToStartApp,
            Map<String, String> loopToEndApp,
            Set<String> affectedLoopIds) {
        Map<String, String> scheme = new LinkedHashMap<>();
        for (String loopId : affectedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop == null) {
                continue;
            }
            String originalStartApp = loop.get("startApp");
            String originalEndApp = loop.get("endApp");
            String selectedStartApp = loopToStartApp.get(loopId);
            String selectedEndApp = loopToEndApp.get(loopId);

            String startPos = currentPositions.getOrDefault(selectedStartApp, "");
            String endPos = currentPositions.getOrDefault(selectedEndApp, "");

            boolean startChanged = !Objects.equals(selectedStartApp, originalStartApp);
            boolean endChanged = !Objects.equals(selectedEndApp, originalEndApp);
            boolean positionChanged = !startPos.isEmpty() || !endPos.isEmpty();
            if (!startChanged && !endChanged && !positionChanged) {
                continue;
            }

            String value = (selectedStartApp != null ? selectedStartApp : "") + "|"
                    + (selectedEndApp != null ? selectedEndApp : "") + "|"
                    + startPos + "|" + endPos;
            scheme.put(loopId, value);
        }
        return scheme;
    }

    private long calculateOptimizationCombinations(
            List<Map<String, String>> loopInfos,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart) {
        long calcStart = System.currentTimeMillis();
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : loopInfos)
            loopById.put(lp.get("id"), lp);
        Set<String> extendedLoopIds = new HashSet<>();
        for (Map<String, String> lp : loopInfos)
            extendedLoopIds.add(lp.get("id"));
        for (Map<String, String> lp : loopInfos) {
            String together = lp.get("changeTogether");
            if (together != null && !together.isEmpty()) {
                List<String> groupMembers = togetherGroup.get(together);
                if (groupMembers != null)
                    extendedLoopIds.addAll(groupMembers);
            }
            String mutual = lp.get("mutualExclusion");
            if (mutual != null && !mutual.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (mutual.equals(allLoop.get("mutualExclusion")))
                        extendedLoopIds.add(allLoop.get("id"));
                }
            }
        }
        List<Map<String, String>> extendedLoops = new ArrayList<>();
        for (String loopId : extendedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop != null)
                extendedLoops.add(loop);
        }
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();
            Set<String> endAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(lid);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("endApp") != null)
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    else
                        allowedEndApps = Collections.emptySet();
                }
                if (endAppIntersection == null)
                    endAppIntersection = new HashSet<>(allowedEndApps);
                else
                    endAppIntersection.retainAll(allowedEndApps);
                coveredLoopIds.add(lid);
            }
            varDomains.put("E_G_" + groupId,
                    endAppIntersection != null ? new ArrayList<>(endAppIntersection) : Collections.emptyList());
        }
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid))
                continue;
            Set<String> allowedEndApps = loopElecById.get(lid);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                varDomains.put("E_L_" + lid, new ArrayList<>(allowedEndApps));
            } else {
                String endApp = lp.get("endApp");
                varDomains.put("E_L_" + lid, Collections.singletonList(endApp != null ? endApp : ""));
            }
        }
        // 起点用电器变量域
        Set<String> coveredStartLoopIds = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();
            Set<String> startAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedStartApps = loopElecByIdStart != null ? loopElecByIdStart.get(lid) : null;
                if (allowedStartApps == null || allowedStartApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("startApp") != null)
                        allowedStartApps = Collections.singleton(lp.get("startApp"));
                    else
                        allowedStartApps = Collections.emptySet();
                }
                if (startAppIntersection == null)
                    startAppIntersection = new HashSet<>(allowedStartApps);
                else
                    startAppIntersection.retainAll(allowedStartApps);
                coveredStartLoopIds.add(lid);
            }
            if (startAppIntersection != null && !startAppIntersection.isEmpty()) {
                varDomains.put("S_G_" + groupId, new ArrayList<>(startAppIntersection));
            }
        }
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            if (coveredStartLoopIds.contains(lid))
                continue;
            Set<String> allowedStartApps = loopElecByIdStart != null ? loopElecByIdStart.get(lid) : null;
            if (allowedStartApps != null && !allowedStartApps.isEmpty()) {
                varDomains.put("S_L_" + lid, new ArrayList<>(allowedStartApps));
            }
        }
        Map<String, List<String>> varKeyToMutualIds = new LinkedHashMap<>();
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");
            if (mutual == null || mutual.isEmpty())
                continue;
            String vk = (together != null && !together.isEmpty()) ? "E_G_" + together : "E_L_" + lid;
            varKeyToMutualIds.computeIfAbsent(vk, k -> new ArrayList<>()).add(mutual);
        }
        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : varKeyToMutualIds.entrySet()) {
            String varKey = e.getKey();
            for (String mutualId : e.getValue()) {
                List<String> varList = mutualIdToVarKeys.computeIfAbsent(mutualId, k -> new ArrayList<>());
                if (!varList.contains(varKey))
                    varList.add(varKey);
            }
        }
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualIds.keySet());
        long totalCombinations = 1L;
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
        if (totalCombinations > 0) {
            Set<String> allPossibleApps = new HashSet<>();
            for (Map<String, String> lp : extendedLoops) {
                String startApp = lp.get("startApp");
                if (startApp != null && !startApp.isEmpty())
                    allPossibleApps.add(startApp);
                String endApp = lp.get("endApp");
                if (endApp != null && !endApp.isEmpty())
                    allPossibleApps.add(endApp);
            }
            for (List<String> domain : varDomains.values())
                allPossibleApps.addAll(domain);
            for (String appName : allPossibleApps) {
                List<String> positions = elecChangeablePosition.get(appName);
                if (positions != null && !positions.isEmpty())
                    totalCombinations *= positions.size();
            }
        }
        System.out.println("可行方案总数（含约束）: " + totalCombinations);
        System.out.println("方案数计算耗时: " + (System.currentTimeMillis() - calcStart) + "ms");
        return totalCombinations;
    }

    private long countAllDifferent(List<List<String>> domains) {
        if (domains == null || domains.isEmpty())
            return 1L;
        return backtrackCount(domains, 0, new HashSet<>());
    }

    private long backtrackCount(List<List<String>> domains, int idx, Set<String> usedValues) {
        if (idx == domains.size())
            return 1L;
        List<String> domain = domains.get(idx);
        if (domain == null || domain.isEmpty())
            return 0L;
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

    public String findNameById(String id, List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if (point.get("id").toString().equals(id)) {
                return point.get("pointName").toString();
            }
        }
        return "";
    }

    public Map<String, String> getEleclection(List<Map<String, String>> mapList) {
        Map<String, String> stringMap1 = new HashMap<>();
        for (Map<String, String> stringMap : mapList) {
            String result = "";
            if (stringMap.get("unregularPointName") != null) {
                result = stringMap.get("unregularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") != null) {
                result = stringMap.get("regularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") == null) {
                result = null;
            }
            stringMap1.put(stringMap.get("appName"), result);
        }
        return stringMap1;
    }
}