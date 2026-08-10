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

import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.InfoRead.ReadPowerPropertiesInfo;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import HarnessPackOpti.utils.ThreadPool;

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
    public static Integer caseNumber = 100;

    // 生成初始样本数量限制
    public static Integer LessRandomSamleNumber = 1000;

    // 遗传最优样本重复次数
    public static Integer BestRepetitionNumber = 0;

    // 每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();

    // 遗传迭代重复的次数限值
    public static Integer IterationRestrictNumber = 1;

    // 遗传每轮迭代最少样本数量
    public static Integer HybridizationLessRandomSamleNumber = 500;

    // 遗传算法数量不够时自动补全得次数
    public static Integer AutoCompleteNumber = 100;

    // 连续空代上限：超过此轮次无新有效方案则提前终止遗传迭代
    public static Integer MaxConsecutiveEmptyGenerations = 10;

    // 交叉概率（0.7 表示 70% 的方案参与交叉）
    public static Double CrossoverRate = 0.7;

    // 线程池
    private static ThreadPool threadPool = null;

    // 定义一个仓库，遗传每次生成的方案存储，防止重复
    // ConcurrentHashMap 的 putIfAbsent 本身原子，无需外部 synchronized
    public static Set<String> WareHouse = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 枚举收集的所有方案
    private List<Map<String, String>> enumeratedSchemes = new ArrayList<>();

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance();
    }

    public static void main(String[] args) throws Exception {
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\入参.json");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));// 将文件中内容转为字符串
        PowerDistributionDriveOptimization powerDistributionDriveOptimization = new PowerDistributionDriveOptimization();
        String s = powerDistributionDriveOptimization.powerDriverOptimize(jsonContent);
        File outputFile = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\powerOutput.txt");
        Files.write(outputFile.toPath(), s.getBytes());
        System.out.println("JSON已成功输出到: " + outputFile.getAbsolutePath());
    }

    public String powerDriverOptimize(String jsonContent) throws Exception {
        // ========== 修复3：重置静态状态，避免多任务干扰 ==========
        WareHouse.clear();
        BestRepetitionNumber = 0;
        BestCost.clear();
        enumeratedSchemes.clear();

        long categoryTime = System.currentTimeMillis();
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        ReadPowerPropertiesInfo readProjectInfo = new ReadPowerPropertiesInfo();
        Map<String, Object> readProject = readProjectInfo.getProjectInfo(jsonMap);
        threadPool = ThreadPool.shared(HarnessBranchTopoOptimize.Threads, HarnessBranchTopoOptimize.QueueCapacity);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        // 记录原始方案指纹，用于判断遗传结果中是否包含原始方案
        final String originalFingerprint = generateSchemeFingerprint(
                (List<Map<String, String>>) jsonMap.get("loopInfos"), appPositions);
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, Object>> points = (List<Map<String, Object>>) jsonMap.get("points");
        Map<String, String> projectInfo = (Map<String, String>) jsonMap.get("projectInfo");
        CaseId = caseInfo.get("id").toString();
        optimizeRecordId = optimizeRecord.get("id").toString();
        optimizeStopStatusStore.setKey(optimizeRecordId);

        // 检查是否有 changeType=2 但位置未设置的用电器（前端未选择具体位置，跳过 base 成本计算）
        boolean hasUnsetFullRangeApp = false;
        if (appPositions != null) {
            for (Map<String, String> app : appPositions) {
                if ("2".equals(app.get("changeType"))
                        && isBlank(app.get("regularPointId"))
                        && isBlank(app.get("regularPointName"))
                        && isBlank(app.get("unregularPointId"))
                        && isBlank(app.get("unregularPointName"))) {
                    hasUnsetFullRangeApp = true;
                    break;
                }
            }
        }

        // 整车信息计算(初始方案)
        String originalResult;
        if (hasUnsetFullRangeApp) {
            originalResult = null;
            System.out.println("存在 changeType=2 但位置未设置的用电器，跳过 base 方案整车计算");
        } else {
            originalResult = projectCircuitInfoOutput.projectCircuitInfoOutput(jsonContent);
        }

        // 判断是哪种类型优化（新格式：优化类型取自 optimizeRecord.type）
        // 4=驱动回路，3=配电回路，5=配电回路+主供电回路+驱动回路（包括硬线/高速线缆/接地回路）
        String optimizeType = optimizeRecord.get("type") != null ? optimizeRecord.get("type").toString() : "5";
        String[] split = optimizeType.split(",");
        List<String> typeList = Arrays.asList(split);
        Random random = new Random();
        // 是否开启直连接口（新格式：开关取自 caseInfo.connect，"true"/"false"）
        boolean whetherToChange = caseInfo.get("connect") != null
                && caseInfo.get("connect").toString().equals("true");

        // 主供电回路和配电回路
        List<Map<String, String>> elecLoopList = new ArrayList<>();
        // 驱动回路
        List<Map<String, String>> driveLoopList = new ArrayList<>();
        // 资源数量读取（新格式）：用电器 -> 8 类可连接资源数量限制，限制为 null 表示不限
        Map<String, AppResourceLimit> resourceNum = new HashMap<>();
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
        // 新格式：接口码位于 edges 的 startInterfaceCode / endInterfaceCode，分别约束起点/终点位置点
        Map<String, List<String>> interfaceCodegroup = new HashMap<>();
        Set<String> pointNameSet = new HashSet<>();
        if (whetherToChange) {
            for (Map<String, Object> edge : edges) {
                Object startIc = edge.get("startInterfaceCode");
                if (startIc != null && !startIc.toString().trim().isEmpty()) {
                    String startPointName = edge.get("startPointName").toString();
                    interfaceCodegroup.computeIfAbsent(startIc.toString(), k -> new ArrayList<>()).add(startPointName);
                    pointNameSet.add(startPointName);
                }
                Object endIc = edge.get("endInterfaceCode");
                if (endIc != null && !endIc.toString().trim().isEmpty()) {
                    String endPointNameIc = edge.get("endPointName").toString();
                    interfaceCodegroup.computeIfAbsent(endIc.toString(), k -> new ArrayList<>()).add(endPointNameIc);
                    pointNameSet.add(endPointNameIc);
                }
            }
        }

        Map<String, List<String>> elecChangeablePosition = new HashMap<>();
        for (Map<String, String> appPosition : appPositions) {
            String appName = appPosition.get("appName");
            // 读取该用电器的 8 类资源限制（dist/drive 的大/中/小电流 + hardWire + highSpeedWire）
            // 新格式：字段值为数值或 null；任一非 null 才登记限制，否则视为无限制
            if (resourceNum.get(appName) == null) {
                AppResourceLimit limit = parseAppResourceLimit(appPosition);
                if (limit != null) {
                    resourceNum.put(appName, limit);
                }
            }
            if ("1".equals(appPosition.get("changeType"))) {
                List<String> list = new ArrayList<>();
                String sp = appPosition.get("specifyPoints");
                if (sp != null && !sp.isEmpty()) {
                    for (String part : sp.split(",")) {
                        String pointName = findNameById(part, points);
                        list.add(pointName);

                        // 如果该位置点是直连接口，将整个接口组的位置都加入
                        // if (whetherToChange && pointNameSet.contains(pointName)) {
                        // // 查找该位置点属于哪个接口组
                        // for (List<String> interfacePoints : interfaceCodegroup.values()) {
                        // if (interfacePoints.contains(pointName)) {
                        // // 将整个接口组的位置都加入可变列表
                        // for (String interfacePoint : interfacePoints) {
                        // if (!list.contains(interfacePoint) && allPoint.contains(interfacePoint)) {
                        // list.add(interfacePoint);
                        // }
                        // }
                        // break;
                        // }
                        // }
                        // }
                    }
                }
                list.retainAll(allPoint);
                list.add(eleclection.get(appName)); // 把自身位置加进去
                elecChangeablePosition.put(appName, list);
            } else if ("2".equals(appPosition.get("changeType"))) {
                // 全量位置可变：若 allPoint 为空则无法枚举位置，给出明确告警避免静默 0 方案
                if (allPoint == null || allPoint.isEmpty()) {
                    System.out.println("警告: 用电器[" + appName + "] changeType=2 但可用位置 allPoint 为空，"
                            + "该用电器位置将不会被枚举，请检查拓扑/位置点数据。");
                }
                elecChangeablePosition.put(appName, new ArrayList<>(allPoint));
            }
            elecNameId.put(appPosition.get("id"), appName);
        }

        // 统计约束list集合，方便后面判断回路是否有约束
        List<String> togetherList = new ArrayList<>();
        List<String> mutualList = new ArrayList<>();
        for (Map<String, String> loopInfo : loopInfos) {
            // 按 loopAttr 对回路分类：配电回路/主供电回路 -> 配电类；驱动回路 -> 驱动类
            // 硬线信号回路/接地回路/高速线缆回路 不进入配电/驱动优化目标（type3 时由 combinedList 天然排除）
            if ("主供电回路".equals(loopInfo.get("loopAttr"))
                    || "配电回路".equals(loopInfo.get("loopAttr"))) {
                elecLoopList.add(loopInfo);
            } else if ("驱动回路".equals(loopInfo.get("loopAttr"))) {
                driveLoopList.add(loopInfo);
            }
            // 回路可连接的终点用电器（startConnEndApps 传的是用电器名称，逗号分隔）
            String s = loopInfo.get("startConnEndApps");
            if (s != null && !s.isEmpty()) {
                for (String name : s.split(",")) {
                    String trim = name.trim();
                    if (!trim.isEmpty())
                        loopElecById.computeIfAbsent(loopInfo.get("id"), k -> new HashSet<>()).add(trim);
                }
            }
            // 回路可连接的起点用电器（selectedEndApp 传的是用电器名称，逗号分隔，与终点同一约定）
            String start = loopInfo.get("selectedEndApp");
            if (start != null && !start.trim().isEmpty()) {
                for (String name : start.split(",")) {
                    String trim = name.trim();
                    if (!trim.isEmpty())
                        loopElecByIdStart.computeIfAbsent(loopInfo.get("id"), k -> new HashSet<>()).add(trim);
                }
            }
            // 组团一起变归组（新字段 teamConnRel）
            String ct = loopInfo.get("teamConnRel");
            if (ct != null && !ct.isEmpty()) {
                togetherGroup.computeIfAbsent(ct, k -> new ArrayList<>()).add(loopInfo.get("id"));
                togetherList.add(loopInfo.get("id"));
            }
            // 互斥归组（新字段 exclusiveConnRel，按字母前缀分组：A-1/A-2 → 同组 A，组内 endApp 必须不同）
            String me = loopInfo.get("exclusiveConnRel");
            if (me != null && !me.isEmpty()) {
                String groupKey = me.contains("-") ? me.substring(0, me.lastIndexOf('-')) : me;
                mutualGroup.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(loopInfo.get("id"));
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
        if (typeList.contains("3") && typeList.contains("4")) {
            combinations = calculateOptimizationCombinations(combinedList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        // 优化驱动回路
        if ("4".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(driveLoopList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        // 优化类型 2：配电器回路
        if ("3".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(elecLoopList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        // 优化类型 3：配电回路+主供电回路+驱动回路（combinedList 已天然排除硬线/高速线缆/接地回路）
        if ("5".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(combinedList, elecChangeablePosition, togetherGroup,
                    loopElecById, loopElecByIdStart);
        }

        System.out.println("枚举模式耗时:" + (System.currentTimeMillis() - combinationsTime));
        System.out.println("总方案数: " + combinations);

        // 总方案数 ≤ 1，没有优化空间，直接返回原始方案
        if (combinations <= 1) {
            System.out.println("方案数 ≤ 1，无需优化，直接返回原始方案");
            List<Map<String, Object>> result = new ArrayList<>();
            Map<String, Object> origMap = new HashMap<>();
            origMap.put("loopInfos", deepCopyLoopInfos(loopInfos));
            origMap.put("appPositions", deepCopyAppPositions(appPositions));
            origMap.put("成本", parseOriginalCost(originalResult, jsonToMap));
            try {
                result.add(enrichToFullScheme(origMap, jsonMap, objectMapper,
                        projectCircuitInfoOutput, jsonToMap, topoInfoMap, projectInfo, true));
            } catch (Exception e) {
                System.err.println("原始方案还原失败: " + e.getMessage());
                result.add(origMap);
            }
            return objectMapper.writeValueAsString(result);
        }

        // 如果方案数在限制内，进行枚举生成方案列表
        if (combinations <= caseNumber) {
            long enumerateTime = System.currentTimeMillis();
            enumeratedSchemes.clear();

            // 根据优化类型选择目标回路并枚举
            List<Map<String, String>> targetLoops = null;
            if (typeList.contains("3") && typeList.contains("4")) {
                targetLoops = combinedList;
            } else if ("4".equals(optimizeType)) {
                targetLoops = driveLoopList;
            } else if ("3".equals(optimizeType)) {
                targetLoops = elecLoopList;
            } else if ("5".equals(optimizeType)) {
                targetLoops = combinedList;
            }
            List<Map<String, Object>> resultList = new ArrayList<>();
            int duplicateCount = 0; // 统计重复方案数
            int validSchemeCount = 0; // 统计有效方案数

            if (targetLoops != null && !targetLoops.isEmpty()) {
                // 执行枚举
                enumerateAllSchemes(targetLoops, elecChangeablePosition, togetherGroup, mutualGroup, loopInfos,
                        loopElecById, loopElecByIdStart);

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

                    // 遍历该方案中的所有回路，还原方案（抽出为 applyEnumeratedSchemeToLoops）
                    applyEnumeratedSchemeToLoops(loopInfoCopy, appPositionsCopy, scheme, elecChangeablePosition,
                            pointNameId);

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
                    jsonMapCopy.put("loopInfos", loopInfoCopy);
                    jsonMapCopy.put("appPositions", appPositionsCopy);
                    // ========== 修复1：计算成本时使用修改后的方案 ==========
                    String modifiedJson = objectMapper.writeValueAsString(jsonMapCopy);
                    String s = projectCircuitInfoOutput.projectCircuitInfoOutput(modifiedJson);
                    if (s == null) {
                        continue;
                    }
                    Map<String, Object> rawMap = jsonToMap.TransJsonToMap(s);
                    Map<String, Object> projectCircuitInfo = (Map<String, Object>) rawMap.get("projectCircuitInfo");
                    Object totalCost = projectCircuitInfo.get("总成本");
                    Object totalWeight = projectCircuitInfo.get("回路总重量");
                    Object totalLength = projectCircuitInfo.get("回路总长度");
                    if (!(totalCost instanceof Number) || !(totalWeight instanceof Number)
                            || !(totalLength instanceof Number)) {
                        System.err.println("枚举方案计算失败，跳过: 成本字段缺失");
                        continue;
                    }
                    Map<String, Double> projectCost = new HashMap<>();
                    projectCost.put("总成本", ((Number) totalCost).doubleValue());
                    projectCost.put("总重量", ((Number) totalWeight).doubleValue());
                    projectCost.put("总长度", ((Number) totalLength).doubleValue());

                    // 精简方案 Map：仅保留 findBest/交叉/变异 真正需要的三个字段
                    Map<String, Object> map = new HashMap<>();
                    map.put("成本", projectCost);
                    map.put("loopInfos", loopInfoCopy);
                    map.put("appPositions", appPositionsCopy);
                    resultList.add(map);
                }
            }
            System.out.println("重复方案数: " + duplicateCount);
            System.out.println("有效方案数: " + validSchemeCount);
            List<Map<String, Object>> topBest = new ArrayList<>();
            if (resultList.size() > 1) {
                topBest = findBest.findBest(resultList, "成本", TopNumber);
            } else if (resultList.size() == 1) {
                // 只有1个方案，直接用
                topBest.add(resultList.get(0));
            } else {
                // 枚举无有效方案时，仅当 base 计算成功才用原始方案兜底
                if (originalResult != null) {
                    Map<String, Object> origMap = new HashMap<>();
                    origMap.put("loopInfos", deepCopyLoopInfos(loopInfos));
                    origMap.put("appPositions", deepCopyAppPositions(appPositions));
                    Map<String, Object> rawMap = jsonToMap.TransJsonToMap(originalResult);
                    Map<String, Object> pcInfo = (Map<String, Object>) rawMap.get("projectCircuitInfo");
                    if (pcInfo != null) {
                        Map<String, Double> projectCost = new HashMap<>();
                        projectCost.put("总成本", ((Number) pcInfo.get("总成本")).doubleValue());
                        projectCost.put("总重量", ((Number) pcInfo.get("回路总重量")).doubleValue());
                        projectCost.put("总长度", ((Number) pcInfo.get("回路总长度")).doubleValue());
                        origMap.put("成本", projectCost);
                    }
                    if (origMap.containsKey("成本")) {
                        topBest.add(origMap);
                    }
                }
            }
            System.out.println("枚举总耗时: " + (System.currentTimeMillis() - enumerateTime) + "ms");
            // 最终输出前：为 top 方案补齐完整整车计算结果
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> slim : topBest) {
                try {
                    enriched.add(enrichToFullScheme(slim, jsonMap, objectMapper,
                            projectCircuitInfoOutput, jsonToMap, topoInfoMap, projectInfo, true));
                } catch (Exception e) {
                    System.err.println("方案还原失败，使用精简版: " + e.getMessage());
                    enriched.add(slim);
                }
            }
            return objectMapper.writeValueAsString(enriched);
        }

        // 开始生成初代样本
        List<Map<String, String>> targetLoops = null;
        if (typeList.contains("3") && typeList.contains("4")) {
            targetLoops = combinedList;
        } else if ("4".equals(optimizeType)) {
            targetLoops = driveLoopList;
        } else if ("3".equals(optimizeType)) {
            targetLoops = elecLoopList;
        } else if ("5".equals(optimizeType)) {
            targetLoops = combinedList;
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
                    projectCircuitInfoOutput,
                    jsonToMap,
                    jsonMap,
                    loopElecById,
                    loopElecByIdStart,
                    resourceNum);

            System.out.println("初代样本生成耗时: " + (System.currentTimeMillis() - gaInitTime) + "ms");
            System.out.println("有效初代样本数: " + initialPopulation.size());

            if (!initialPopulation.isEmpty()) {
                topBest = findBest.findBest(initialPopulation, "成本", TopNumber);
                // 原始方案（仅当有 base 计算结果时才加入对比）
                if (originalResult != null) {
                    Map<String, Object> origMap = new HashMap<>();
                    origMap.put("loopInfos", deepCopyLoopInfos(loopInfos));
                    origMap.put("appPositions", deepCopyAppPositions(appPositions));
                    Map<String, Object> rawMap = jsonToMap.TransJsonToMap(originalResult);
                    Map<String, Object> pcInfo = (Map<String, Object>) rawMap.get("projectCircuitInfo");
                    if (pcInfo != null) {
                        Map<String, Double> projectCost = new HashMap<>();
                        projectCost.put("总成本", ((Number) pcInfo.get("总成本")).doubleValue());
                        projectCost.put("总重量", ((Number) pcInfo.get("回路总重量")).doubleValue());
                        projectCost.put("总长度", ((Number) pcInfo.get("回路总长度")).doubleValue());
                        origMap.put("成本", projectCost);
                    }
                    if (origMap.containsKey("成本")) {
                        topBest.add(origMap);
                    }
                }
            }
        }

        // 遗传算法
        int hybridizationNumber = 0;
        int emptyGenCount = 0; // 连续无有效变异体的代数
        List<Map<String, Object>> currentTopBest = topBest;

        while (true) {
            long iterStartMs = System.currentTimeMillis();
            System.out.println((hybridizationNumber + 1) + "代迭代开始, 仓库方案数: " + WareHouse.size());

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
                    projectCircuitInfoOutput,
                    jsonToMap,
                    topoInfoMap,
                    projectInfo,
                    loopElecById,
                    random,
                    resourceNum, jsonMap);

            System.out.println("交叉生成 " + crossedSchemes.size() + " 个方案");

            List<Map<String, Object>> allSchemesForMutation = new ArrayList<>(currentTopBest);
            allSchemesForMutation.addAll(crossedSchemes);

            // 变异上限 = 本代目标 - 已保留的方案，避免无限制生成
            int mutationCap = Math.max(1, HybridizationLessRandomSamleNumber - currentTopBest.size());
            List<Map<String, Object>> mutatedSchemes = mutateTopSchemes(
                    allSchemesForMutation,
                    mutationCap,
                    targetLoops,
                    loopInfos,
                    appPositions,
                    elecChangeablePosition,
                    togetherGroup,
                    mutualGroup,
                    pointNameId,
                    objectMapper,
                    projectCircuitInfoOutput,
                    jsonToMap,
                    jsonMap,
                    loopElecById,
                    loopElecByIdStart,
                    random,
                    resourceNum);
            System.out.println("变异生成 " + mutatedSchemes.size() + " 个方案");

            boolean noProgress = mutatedSchemes.isEmpty() && crossedSchemes.isEmpty();
            if (noProgress) {
                emptyGenCount++;
                System.out.println("第" + (hybridizationNumber + 1) + "代未生成有效方案（连续空代: "
                        + emptyGenCount + "/" + MaxConsecutiveEmptyGenerations + "），继续下一轮");
                if (emptyGenCount >= MaxConsecutiveEmptyGenerations) {
                    System.out.println("连续 " + MaxConsecutiveEmptyGenerations
                            + " 代无新有效方案，终止遗传迭代，返回当前最优方案");
                    break;
                }
                hybridizationNumber++;
                continue;
            }
            // 只要交叉或变异任一产生新方案都算有进展，但只在变异成功时清零（交叉只是配对不直接入代）
            if (!mutatedSchemes.isEmpty())
                emptyGenCount = 0;

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
                        projectCircuitInfoOutput,
                        jsonToMap,
                        jsonMap,
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
            long iterElapsed = System.currentTimeMillis() - iterStartMs;
            System.out.println("第" + (hybridizationNumber + 1) + "代完成, 最优成本: " +
                    currentTopBest.get(0).get("成本") + ", 本代耗时: " + iterElapsed + "ms");

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

        // 兜底1：GA 一代有效方案都没出，且 combinations 规模小，直接走枚举
        if (currentTopBest.isEmpty() && combinations > 1 && combinations <= caseNumber * 10
                && (enumeratedSchemes == null || enumeratedSchemes.isEmpty())) {
            System.out.println("[兜底] GA 未产出有效方案，尝试兜底枚举生成 topBest ...");
            try {
                List<Map<String, String>> fallbackTarget = null;
                if (typeList.contains("3") && typeList.contains("4"))
                    fallbackTarget = combinedList;
                else if ("4".equals(optimizeType))
                    fallbackTarget = driveLoopList;
                else if ("3".equals(optimizeType))
                    fallbackTarget = elecLoopList;
                else if ("5".equals(optimizeType))
                    fallbackTarget = combinedList;
                if (fallbackTarget != null && !fallbackTarget.isEmpty()) {
                    enumerateAllSchemes(fallbackTarget, elecChangeablePosition, togetherGroup, mutualGroup,
                            loopInfos, loopElecById, loopElecByIdStart);
                    // enumerateAllSchemes 内部会把方案塞到 enumeratedSchemes，这里再还原成 currentTopBest
                    for (int i = 0; i < enumeratedSchemes.size(); i++) {
                        Map<String, String> scheme = enumeratedSchemes.get(i);
                        // 构造 loopInfoCopy + appPositionsCopy
                        List<Map<String, String>> loopInfoCopy = new ArrayList<>();
                        for (Map<String, String> loop : loopInfos)
                            loopInfoCopy.add(new HashMap<>(loop));
                        List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(appPositions);
                        applyEnumeratedSchemeToLoops(loopInfoCopy, appPositionsCopy, scheme, elecChangeablePosition,
                                pointNameId);
                        Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);
                        jsonMapCopy.put("loopInfos", loopInfoCopy);
                        jsonMapCopy.put("appPositions", appPositionsCopy);
                        String result = projectCircuitInfoOutput.projectCircuitInfoOutput(
                                objectMapper.writeValueAsString(jsonMapCopy));
                        if (result == null || result.isEmpty())
                            continue;
                        Map<String, Object> parsed = jsonToMap.TransJsonToMap(result);
                        Map<String, Object> pcInfo = (Map<String, Object>) parsed.get("projectCircuitInfo");
                        if (pcInfo == null)
                            continue;
                        Object tc = pcInfo.get("总成本");
                        Object tw = pcInfo.get("回路总重量");
                        Object tl = pcInfo.get("回路总长度");
                        if (!(tc instanceof Number && tw instanceof Number && tl instanceof Number))
                            continue;
                        Map<String, Double> projectCost = new HashMap<>();
                        projectCost.put("总成本", ((Number) tc).doubleValue());
                        projectCost.put("总重量", ((Number) tw).doubleValue());
                        projectCost.put("总长度", ((Number) tl).doubleValue());
                        Map<String, Object> map = new HashMap<>();
                        map.put("成本", projectCost);
                        map.put("loopInfos", loopInfoCopy);
                        map.put("appPositions", appPositionsCopy);
                        String fp = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
                        if (WareHouse.add(fp))
                            currentTopBest.add(map);
                        if (currentTopBest.size() >= TopNumber)
                            break;
                    }
                    if (!currentTopBest.isEmpty()) {
                        currentTopBest = findBest.findBest(currentTopBest, "成本", TopNumber);
                        System.out.println("[兜底] 枚举兜底生成 " + currentTopBest.size() + " 个有效方案");
                    }
                }
            } catch (Exception ex) {
                System.out.println("[兜底] 枚举兜底失败: " + ex.getMessage());
            }
        }

        // 兜底2：所有方案都没拿到，返回原始方案
        if (currentTopBest.isEmpty()) {
            System.out.println("[兜底] 全部失败，返回原始方案");
            Map<String, Object> origMap = new HashMap<>();
            origMap.put("loopInfos", deepCopyLoopInfos(loopInfos));
            origMap.put("appPositions", deepCopyAppPositions(appPositions));
            origMap.put("成本", parseOriginalCost(originalResult, jsonToMap));
            currentTopBest.add(origMap);
        }

        List<Map<String, Object>> enrichedTopBest = new ArrayList<>();
        for (Map<String, Object> slim : currentTopBest) {
            try {
                List<Map<String, String>> sloopInfos = (List<Map<String, String>>) slim.get("loopInfos");
                List<Map<String, String>> sAppPositions = (List<Map<String, String>>) slim.get("appPositions");
                boolean isInitial = originalFingerprint != null
                        && originalFingerprint.equals(generateSchemeFingerprint(sloopInfos, sAppPositions));
                enrichedTopBest.add(enrichToFullScheme(slim, jsonMap, objectMapper,
                        projectCircuitInfoOutput, jsonToMap, topoInfoMap, projectInfo, isInitial));
            } catch (Exception e) {
                System.err.println("方案还原失败，使用精简版: " + e.getMessage());
                enrichedTopBest.add(slim);
            }
        }
        return objectMapper.writeValueAsString(enrichedTopBest);
    }

    /**
     * 将单个枚举方案（loopId → "startApp|endApp|startPos|endPos"）还原到 loopInfoCopy /
     * appPositionsCopy。
     * startApp/endApp 为空时跳过该回路。
     */
    private void applyEnumeratedSchemeToLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            Map<String, String> scheme,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId) {
        if (scheme == null)
            return;
        Map<String, Map<String, String>> appIndex = new HashMap<>();
        for (Map<String, String> ap : appPositionsCopy)
            appIndex.put(ap.get("appName"), ap);
        for (Map.Entry<String, String> entry : scheme.entrySet()) {
            String loopId = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty())
                continue;
            String[] parts = value.split("\\|");
            String startApp = parts.length > 0 ? parts[0] : "";
            String endApp = parts.length > 1 ? parts[1] : "";
            String startPos = parts.length > 2 ? parts[2] : "";
            String endPos = parts.length > 3 ? parts[3] : "";
            if (startApp.isEmpty() || endApp.isEmpty())
                continue;

            for (Map<String, String> loop : loopInfoCopy) {
                if (loop.get("id").equals(loopId)) {
                    loop.put("startApp", startApp);
                    loop.put("endApp", endApp);
                }
            }

            Map<String, String> startAp = appIndex.get(startApp);
            if (startAp != null && !startPos.isEmpty()) {
                startAp.put("unregularPointName", startPos);
                startAp.put("unregularPointId", pointNameId.get(startPos));
            }
            Map<String, String> endAp = appIndex.get(endApp);
            if (endAp != null && !endPos.isEmpty()) {
                endAp.put("unregularPointName", endPos);
                endAp.put("unregularPointId", pointNameId.get(endPos));
            }
        }
    }

    /**
     * 资源连接数量检查（新格式）
     * 按 loopAttr 判定资源类别（配电/主供电->配电器；驱动->驱动器；硬线；高速线），
     * 按 loopWireway 第二分段（线径截面积/铜丝数）判定大/中/小电流，
     * 与用电器在 resourceNum 中登记的 8 类限制逐一比较；限制为 null 视为不限。
     */
    public Boolean elecResourceCheck(List<Map<String, String>> loopInfos, Map<String, AppResourceLimit> resourceNum) {
        if (resourceNum == null || resourceNum.isEmpty()) {
            return true; // 无任何资源限制，直接通过
        }
        // 实际消耗统计：appName -> (statKey -> 数量)，statKey ∈
        // {distHigh,distMedium,distLow,driveHigh,driveMedium,driveLow,hardWire,highSpeedWire}
        Map<String, Map<String, Integer>> actualResource = new HashMap<>();

        for (Map<String, String> loopInfo : loopInfos) {
            String resourceCategory = resolveResourceCategory(loopInfo.get("loopAttr"));
            if (resourceCategory == null) {
                continue; // 接地回路等不参与资源限制
            }
            // 硬线/高速线不按线径分行径尺寸
            String statKey;
            if ("hardWire".equals(resourceCategory)) {
                statKey = "hardWire";
            } else if ("highSpeedWire".equals(resourceCategory)) {
                statKey = "highSpeedWire";
            } else {
                String size = resolveCurrentSize(loopInfo.get("loopWireway"));
                if (size == null)
                    continue;
                statKey = resourceCategory + size; // distHigh / distMedium / distLow / driveHigh ...
            }

            String startApp = loopInfo.get("startApp");
            String endApp = loopInfo.get("endApp");
            if (startApp != null && resourceNum.containsKey(startApp)) {
                actualResource.computeIfAbsent(startApp, k -> new HashMap<>()).merge(statKey, 1, Integer::sum);
            }
            if (endApp != null && resourceNum.containsKey(endApp)) {
                actualResource.computeIfAbsent(endApp, k -> new HashMap<>()).merge(statKey, 1, Integer::sum);
            }
        }

        // 与登记的限制逐一比较
        for (Map.Entry<String, AppResourceLimit> entry : resourceNum.entrySet()) {
            String appName = entry.getKey();
            AppResourceLimit limit = entry.getValue();
            Map<String, Integer> actual = actualResource.getOrDefault(appName, new HashMap<>());
            if (!checkLimit(actual.get("distHigh"), limit.distHigh))
                return false;
            if (!checkLimit(actual.get("distMedium"), limit.distMedium))
                return false;
            if (!checkLimit(actual.get("distLow"), limit.distLow))
                return false;
            if (!checkLimit(actual.get("driveHigh"), limit.driveHigh))
                return false;
            if (!checkLimit(actual.get("driveMedium"), limit.driveMedium))
                return false;
            if (!checkLimit(actual.get("driveLow"), limit.driveLow))
                return false;
            if (!checkLimit(actual.get("hardWire"), limit.hardWire))
                return false;
            if (!checkLimit(actual.get("highSpeedWire"), limit.highSpeedWire))
                return false;
        }
        return true;
    }

    /**
     * 根据 loopAttr 返回资源类别：dist(配电器)/drive(驱动器)/hardWire/highSpeedWire；其余返回
     * null（不参与限制）
     */
    private String resolveResourceCategory(String loopAttr) {
        if (loopAttr == null)
            return null;
        switch (loopAttr) {
            case "配电回路":
            case "主供电回路":
                return "dist";
            case "驱动回路":
                return "drive";
            case "硬线信号回路":
                return "hardWire";
            case "高速线缆回路":
                return "highSpeedWire";
            default:
                return null; // 接地回路等
        }
    }

    /**
     * 根据 loopWireway 第二分段（线径截面积或铜丝数）判定大/中/小电流，返回 High/Medium/Low；无法解析返回 null
     */
    private String resolveCurrentSize(String loopWireway) {
        if (loopWireway == null || loopWireway.trim().isEmpty())
            return null;
        String[] split = loopWireway.trim().split("\\s+");
        if (split.length < 2)
            return null;
        double value;
        try {
            value = Double.parseDouble(split[1]); // 兼容 "FLRY-B 0.35"（截面积 mm²）与整数铜丝数
        } catch (NumberFormatException e) {
            return null;
        }
        // 阈值沿用旧逻辑语义：>=6 大电流，>2 中电流，否则小电流
        if (value >= 6.0)
            return "High";
        if (value > 2.0)
            return "Medium";
        return "Low";
    }

    /**
     * 实际数 <= 限制 才通过；限制为 null 表示不限
     */
    private boolean checkLimit(Integer actual, Integer limit) {
        if (limit == null)
            return true;
        int a = actual != null ? actual : 0;
        return a <= limit;
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
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> topoInfoMap,
            Map<String, String> projectInfo,
            Map<String, Set<String>> loopElecById,
            Random random,
            Map<String, AppResourceLimit> resourceNum, Map<String, Object> jsonMap)
            throws Exception {

        long xStartMs = System.currentTimeMillis();
        List<Map<String, Object>> crossedSchemes = Collections.synchronizedList(new ArrayList<>());
        int populationSize = topSchemes.size();
        System.out.println("开始并行交叉操作，种群大小: " + populationSize);

        List<Map<String, Object>> shuffledSchemes = new ArrayList<>(topSchemes);
        Collections.shuffle(shuffledSchemes, random);

        // 按 CrossoverRate 过滤进入配对池，池内再随机配对（避免按序配对时跳过导致个体浪费）
        List<Map<String, Object>> pool = new ArrayList<>();
        for (Map<String, Object> s : shuffledSchemes) {
            if (random.nextDouble() < CrossoverRate)
                pool.add(s);
        }
        Collections.shuffle(pool, random);
        // 收集有效的交叉配对
        List<Map<String, Object>[]> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < pool.size(); i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, Object>[] pair = new Map[] { pool.get(i), pool.get(i + 1) };
            pairs.add(pair);
        }

        for (Map<String, Object>[] pair : pairs) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false)
                break;
            threadPool.execute(() -> {
                Random rnd = new Random();
                try {
                    Map<String, Object> child1 = uniformCrossover(
                            pair[0], pair[1], targetLoops, allLoopInfos, allAppPositions,
                            elecChangeablePosition, togetherGroup, mutualGroup,
                            pointNameId, objectMapper, projectCircuitInfoOutput,
                            jsonToMap, jsonMap, loopElecById, rnd, resourceNum);
                    if (child1 != null)
                        crossedSchemes.add(child1);

                    Map<String, Object> child2 = uniformCrossover(
                            pair[1], pair[0], targetLoops, allLoopInfos, allAppPositions,
                            elecChangeablePosition, togetherGroup, mutualGroup,
                            pointNameId, objectMapper, projectCircuitInfoOutput,
                            jsonToMap, jsonMap, loopElecById, rnd, resourceNum);
                    if (child2 != null)
                        crossedSchemes.add(child2);
                } catch (Exception e) {
                    // 单对交叉失败不影响其他任务
                }
            });
        }

        threadPool.awaitCompletion();
        long xElapsed = System.currentTimeMillis() - xStartMs;
        System.out.println("交叉完成: " + crossedSchemes.size() + " 个方案, 耗时 " + xElapsed + "ms");
        return crossedSchemes;
    }

    /**
     * 均匀交叉（修复：增加位置一致性同步，并继承父本2的用电器位置）
     * 注：所有共享框架数据从 jsonMap 读取，方法签名只接收变化数据。
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
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> jsonMap,
            Map<String, Set<String>> loopElecById,
            Random random,
            Map<String, AppResourceLimit> resourceNum) throws Exception {

        List<Map<String, String>> parent1Loops = (List<Map<String, String>>) parent1.get("loopInfos");
        List<Map<String, String>> parent2Loops = (List<Map<String, String>>) parent2.get("loopInfos");
        List<Map<String, String>> parent1Apps = (List<Map<String, String>>) parent1.get("appPositions");
        List<Map<String, String>> parent2Apps = (List<Map<String, String>>) parent2.get("appPositions");
        // 防御：父本缺少必要字段时回退到 allLoopInfos/allAppPositions
        if (parent1Loops == null)
            parent1Loops = allLoopInfos;
        if (parent2Loops == null)
            parent2Loops = allLoopInfos;
        if (parent1Apps == null)
            parent1Apps = allAppPositions;
        if (parent2Apps == null)
            parent2Apps = allAppPositions;

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

        if (!enforceAllConstraints(childLoops, childApps, togetherGroup, mutualGroup,
                loopElecById, elecChangeablePosition, pointNameId, random))
            return null;

        // 关键修正：确保子代中同一用电器位置唯一且优先保留已有位置
        syncAppPositionsPreservingExisting(childLoops, childApps, elecChangeablePosition, pointNameId, random);

        Boolean b = elecResourceCheck(childLoops, resourceNum);
        if (!b)
            return null;

        String fingerprint = generateSchemeFingerprint(childLoops, childApps);
        if (!WareHouse.add(fingerprint))
            return null;

        // 整车全量成本计算
        Map<String, Double> deltaTotals = computeFullCost(
                childLoops, childApps, jsonMap, objectMapper,
                projectCircuitInfoOutput, jsonToMap);
        if (deltaTotals == null) {
            WareHouse.remove(fingerprint);
            return null;
        }
        // 精简方案 Map：仅保留 findBest/交叉/变异 真正需要的三个字段
        Map<String, Double> projectCost = new HashMap<>();
        projectCost.put("总成本", deltaTotals.get("总成本"));
        projectCost.put("总重量", deltaTotals.get("回路总重量"));
        projectCost.put("总长度", deltaTotals.get("回路总长度"));
        Map<String, Object> map = new HashMap<>();
        map.put("成本", projectCost);
        map.put("loopInfos", childLoops);
        map.put("appPositions", childApps);
        return map;
    }

    /**
     * 强制满足联动组约束：组内所有回路 endApp 必须一致。
     * 取组内第一个回路的 endApp 作为标准，校验其他回路是否合法（在其 allowedEndApps 中）。
     * 如有回路无法接受该终点 → 返回 false，方案应被丢弃。
     */
    private boolean enforceTogetherGroupConstraints(
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

            // 校验所有成员回路是否都能接受这个 standardEndApp
            for (String loopId : memberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null)
                    continue;
                // 如果该回路 endApp 已经是标准值，无需校验
                if (standardEndApp.equals(loop.get("endApp")))
                    continue;
                // 检查标准值是否在合法候选中
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || !allowedEndApps.contains(standardEndApp))
                    return false; // 不合法，方案丢弃
                loop.put("endApp", standardEndApp);
            }
        }
        return true;
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
     * 迭代执行约束强制（组团 + 互斥），直到无变更或达到最大迭代次数。
     * 解决交叉依赖场景（如 A-组团-B, B-互斥-C, C-组团-D）的单次扫描遗漏问题。
     */
    private boolean enforceAllConstraints(
            List<Map<String, String>> loops,
            List<Map<String, String>> apps,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random) {

        int maxIters = 5;
        Map<String, String> endAppSnapshot = new HashMap<>();

        while (maxIters-- > 0) {
            // 保存变更前的 endApp 快照
            for (Map<String, String> loop : loops)
                endAppSnapshot.put(loop.get("id"), loop.get("endApp"));

            if (!enforceTogetherGroupConstraints(loops, apps, togetherGroup, loopElecById, random))
                return false;
            if (!enforceMutualGroupConstraints(loops, apps, mutualGroup,
                    loopElecById, elecChangeablePosition, pointNameId, random))
                return false;

            // 检查是否有变更：任意回路 endApp 不一致则需继续迭代
            boolean stable = true;
            for (Map<String, String> loop : loops) {
                if (!Objects.equals(endAppSnapshot.get(loop.get("id")), loop.get("endApp"))) {
                    stable = false;
                    break;
                }
            }
            if (stable)
                return true;
        }
        // 达到最大迭代次数仍不稳定，视为通过（实际极少发生）
        return true;
    }

    /**
     * 变异操作（修正：位置随机加入概率保留，并增加约束修复）
     *
     * @param maxSchemes 生成上限，达到后不再继续变异
     *                   注：所有共享框架数据从 jsonMap 读取，方法签名只接收变化数据。
     */
    private List<Map<String, Object>> mutateTopSchemes(
            List<Map<String, Object>> topSchemes,
            int maxSchemes,
            List<Map<String, String>> targetLoops,
            List<Map<String, String>> allLoopInfos,
            List<Map<String, String>> allAppPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, String> pointNameId,
            ObjectMapper objectMapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> jsonMap,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Random random,
            Map<String, AppResourceLimit> resourceNum) throws Exception {

        long mStartMs = System.currentTimeMillis();
        List<Map<String, Object>> mutatedSchemes = Collections.synchronizedList(new ArrayList<>());
        // 原子计数器：用 CAS 替代 synchronizedList.size() 检查，避免 race
        java.util.concurrent.atomic.AtomicInteger mutationCount = new java.util.concurrent.atomic.AtomicInteger(0);
        System.out.println("开始并行对 " + topSchemes.size() + " 个方案进行多分支变异...");

        for (int schemeIdx = 0; schemeIdx < topSchemes.size(); schemeIdx++) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false)
                break;
            final int idx = schemeIdx;
            final Map<String, Object> scheme = topSchemes.get(schemeIdx);
            threadPool.execute(() -> {
                // 已达上限，跳过本任务
                if (mutationCount.get() >= maxSchemes)
                    return;
                // 提取父本数据（每个线程读取不变数据，安全）
                List<Map<String, String>> originalLoops = (List<Map<String, String>>) scheme.get("loopInfos");
                List<Map<String, String>> originalApps = (List<Map<String, String>>) scheme.get("appPositions");
                if (originalLoops == null)
                    originalLoops = allLoopInfos;
                if (originalApps == null)
                    originalApps = allAppPositions;

                Random rnd = new Random();
                try {
                    List<List<Map<String, String>>> allVariants = new ArrayList<>();
                    allVariants.addAll(generateConstrainedVariants(
                            originalLoops, originalApps, targetLoops, elecChangeablePosition,
                            togetherGroup, mutualGroup, pointNameId, rnd, loopElecById));
                    allVariants.addAll(generateUnconstrainedVariants(
                            originalLoops, originalApps, targetLoops, elecChangeablePosition,
                            pointNameId, rnd, loopElecById, loopElecByIdStart, togetherGroup, mutualGroup));
                    allVariants.addAll(generateMixedVariants(
                            originalLoops, originalApps, targetLoops, elecChangeablePosition,
                            togetherGroup, mutualGroup, pointNameId, rnd, loopElecById, loopElecByIdStart,
                            togetherGroup, mutualGroup));

                    for (List<Map<String, String>> variantLoops : allVariants) {
                        // 已达上限，停止生成（用原子计数器避免 race）
                        if (mutationCount.get() >= maxSchemes)
                            return;

                        List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(originalApps);
                        // 强制同步：变连接关系必变位置（start/end app 改了，位置必须跟着重选）
                        syncPositionsForLoops(variantLoops, appPositionsCopy, elecChangeablePosition,
                                pointNameId, rnd);
                        if (!enforceAllConstraints(variantLoops, appPositionsCopy, togetherGroup, mutualGroup,
                                loopElecById, elecChangeablePosition, pointNameId, rnd))
                            continue;
                        if (!elecResourceCheck(variantLoops, resourceNum))
                            continue;

                        String fingerprint = generateSchemeFingerprint(variantLoops, appPositionsCopy);
                        if (!WareHouse.add(fingerprint))
                            continue;

                        // 整车全量成本计算
                        Map<String, Double> deltaTotals = computeFullCost(
                                variantLoops, appPositionsCopy, jsonMap, objectMapper,
                                projectCircuitInfoOutput, jsonToMap);
                        if (deltaTotals == null) {
                            WareHouse.remove(fingerprint);
                            continue;
                        }
                        Map<String, Double> projectCost = new HashMap<>();
                        projectCost.put("总成本", deltaTotals.get("总成本"));
                        projectCost.put("总重量", deltaTotals.get("回路总重量"));
                        projectCost.put("总长度", deltaTotals.get("回路总长度"));
                        Map<String, Object> map = new HashMap<>();
                        map.put("成本", projectCost);
                        map.put("loopInfos", variantLoops);
                        map.put("appPositions", appPositionsCopy);

                        // CAS 自增：超过上限则不放行
                        int cur = mutationCount.incrementAndGet();
                        if (cur > maxSchemes) {
                            mutationCount.decrementAndGet();
                            WareHouse.remove(fingerprint);
                            return;
                        }
                        mutatedSchemes.add(map);
                    }
                } catch (Exception e) {
                    // 单个方案变异失败不影响其他
                }
            });
        }

        threadPool.awaitCompletion();

        long mElapsed = System.currentTimeMillis()
                - mStartMs;
        System.out.println("变异完成: " + mutatedSchemes.size() + " 个有效方案, 耗时 " + mElapsed + "ms");
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

        // 互斥组变异：贪心失败时回退到 backtrack，最多尝试 100 次
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

            // 收集有合法 endApp 选项的成员
            List<List<String>> optionsList = new ArrayList<>();
            List<String> optionLoopIds = new ArrayList<>();
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                    optionsList.add(new ArrayList<>(allowedEndApps));
                    optionLoopIds.add(loopId);
                }
            }
            if (optionsList.isEmpty())
                continue;

            int maxAttempts = Math.max(10, Math.min(100,
                    optionsList.size() * optionsList.get(0).size() * 2));
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                List<Map<String, String>> copyVariant = deepCopyLoopInfos(originalLoops);
                Set<String> usedEndApps = new HashSet<>();
                boolean success = assignMutualGroup(copyVariant, optionsList, optionLoopIds, usedEndApps, 0);
                if (success)
                    variants.add(copyVariant);
            }
        }
        return variants;
    }

    /**
     * 互斥组 endApp 分配 backtrack：对 optionsList[fromIdx..] 尝试给每个成员分配不重复的 endApp。
     * 找到一组即返回 true。
     */
    private boolean assignMutualGroup(
            List<Map<String, String>> copyVariant,
            List<List<String>> optionsList,
            List<String> optionLoopIds,
            Set<String> usedEndApps,
            int fromIdx) {
        if (fromIdx == optionsList.size())
            return true;
        List<String> opts = optionsList.get(fromIdx);
        // 随机洗牌增加多样性
        List<String> shuffled = new ArrayList<>(opts);
        Collections.shuffle(shuffled, new Random());
        for (String endApp : shuffled) {
            if (usedEndApps.contains(endApp))
                continue;
            usedEndApps.add(endApp);
            for (Map<String, String> loop : copyVariant) {
                if (loop.get("id").equals(optionLoopIds.get(fromIdx)))
                    loop.put("endApp", endApp);
            }
            if (assignMutualGroup(copyVariant, optionsList, optionLoopIds, usedEndApps, fromIdx + 1))
                return true;
            usedEndApps.remove(endApp);
        }
        return false;
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

            // 收集所有合法的 (endApp, startApp) 组合，去掉 startApp==endApp 与重复
            List<String[]> validPairs = new ArrayList<>();
            Set<String> seenPair = new HashSet<>();
            List<String> endAppPool = new ArrayList<>();
            if (allowedEndApps != null)
                endAppPool.addAll(allowedEndApps);
            else
                endAppPool.add(null);
            List<String> startAppPool = new ArrayList<>();
            if (allowedStartApps != null)
                startAppPool.addAll(allowedStartApps);
            else
                startAppPool.add(null);
            for (String endApp : endAppPool) {
                for (String startApp : startAppPool) {
                    if (endApp != null && startApp != null && endApp.equals(startApp))
                        continue;
                    String key = endApp + "|" + startApp;
                    if (seenPair.contains(key))
                        continue;
                    seenPair.add(key);
                    validPairs.add(new String[] { endApp, startApp });
                }
            }
            if (validPairs.isEmpty())
                continue;
            Collections.shuffle(validPairs, random);
            int maxAttempts = Math.min(3, validPairs.size());
            for (int i = 0; i < maxAttempts; i++) {
                String[] pair = validPairs.get(i);
                String selectedEndApp = pair[0];
                String selectedStartApp = pair[1];
                List<Map<String, String>> copyVariant = deepCopyLoopInfos(originalLoops);
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
     * 统一位置同步：遍历变异后的 loops，对每个 startApp/endApp，
     * 从 elecChangeablePosition 中随机选位并写入 appPositions。
     * 跳过已无合法位置的用电器。
     * 必须配合"起点终点连接关系变化"使用，不能单独调用。
     */
    private void syncPositionsForLoops(
            List<Map<String, String>> loops,
            List<Map<String, String>> appPositions,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random) {
        if (loops == null || appPositions == null)
            return;
        Set<String> appsUsed = new HashSet<>();
        for (Map<String, String> loop : loops) {
            String s = loop.get("startApp");
            String e = loop.get("endApp");
            if (s != null && !s.isEmpty())
                appsUsed.add(s);
            if (e != null && !e.isEmpty())
                appsUsed.add(e);
        }
        // 用电器→位置的索引（避免每轮都线性扫描）
        Map<String, Map<String, String>> appIndex = new HashMap<>();
        for (Map<String, String> ap : appPositions)
            appIndex.put(ap.get("appName"), ap);

        for (String appName : appsUsed) {
            List<String> positions = elecChangeablePosition.get(appName);
            if (positions == null || positions.isEmpty())
                continue;
            String chosenPos = positions.get(random.nextInt(positions.size()));
            Map<String, String> ap = appIndex.get(appName);
            if (ap == null)
                continue;
            ap.put("unregularPointName", chosenPos);
            ap.put("unregularPointId", pointNameId.get(chosenPos));
        }
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
     * 解析原始方案的整车成本（总成本/总重量/总长度），用于初代为空的兜底返回。
     */
    private Map<String, Double> parseOriginalCost(String originalResult, JsonToMap jsonToMap) {
        Map<String, Double> cost = new HashMap<>();
        cost.put("总成本", 0.0);
        cost.put("总重量", 0.0);
        cost.put("总长度", 0.0);
        if (originalResult == null || originalResult.isEmpty())
            return cost;
        try {
            Map<String, Object> parsed = jsonToMap.TransJsonToMap(originalResult);
            Map<String, Object> pcInfo = (Map<String, Object>) parsed.get("projectCircuitInfo");
            if (pcInfo != null) {
                Object tc = pcInfo.get("总成本");
                Object tw = pcInfo.get("回路总重量");
                Object tl = pcInfo.get("回路总长度");
                if (tc instanceof Number)
                    cost.put("总成本", ((Number) tc).doubleValue());
                if (tw instanceof Number)
                    cost.put("总重量", ((Number) tw).doubleValue());
                if (tl instanceof Number)
                    cost.put("总长度", ((Number) tl).doubleValue());
            }
        } catch (Exception e) {
            // 解析失败返回默认 0.0
        }
        return cost;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * 整车全量成本计算：根据回路连接关系和用电器位置，调用 projectCircuitInfoOutput 计算总成本/重量/长度。
     * 返回 null 表示计算失败，并打印具体原因以便定位。
     */
    private Map<String, Double> computeFullCost(
            List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions,
            Map<String, Object> jsonMap,
            ObjectMapper objectMapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap) throws Exception {
        Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);
        jsonMapCopy.put("loopInfos", loopInfos);
        jsonMapCopy.put("appPositions", appPositions);
        String result;
        try {
            result = projectCircuitInfoOutput.projectCircuitInfoOutput(
                    objectMapper.writeValueAsString(jsonMapCopy));
        } catch (Exception ex) {
            // 拓扑/数据异常：典型场景是用电器位置没绑 / 回路 id 找不到 / 位置点不在图上
            System.out.println("[computeFullCost] 调用 projectCircuitInfoOutput 抛异常: " + ex.getClass().getSimpleName()
                    + "  msg=" + ex.getMessage());
            return null;
        }
        if (result == null || result.isEmpty()) {
            System.out.println("[computeFullCost] projectCircuitInfoOutput 返回空（通常是 data 缺失或位置未绑定）");
            return null;
        }
        Map<String, Object> parsed;
        try {
            parsed = jsonToMap.TransJsonToMap(result);
        } catch (Exception ex) {
            System.out.println("[computeFullCost] 解析 projectCircuitInfoOutput 结果失败: " + ex.getMessage());
            return null;
        }
        Map<String, Object> pcInfo = (Map<String, Object>) parsed.get("projectCircuitInfo");
        if (pcInfo == null) {
            System.out.println("[computeFullCost] 结果无 projectCircuitInfo 字段，原始片段: "
                    + (result.length() > 200 ? result.substring(0, 200) + "..." : result));
            return null;
        }
        Object tc = pcInfo.get("总成本");
        Object tw = pcInfo.get("回路总重量");
        Object tl = pcInfo.get("回路总长度");
        if (!(tc instanceof Number && tw instanceof Number && tl instanceof Number)) {
            System.out.println("[computeFullCost] 成本字段类型异常 tc=" + (tc == null ? "null" : tc.getClass().getSimpleName())
                    + " tw=" + (tw == null ? "null" : tw.getClass().getSimpleName())
                    + " tl=" + (tl == null ? "null" : tl.getClass().getSimpleName()));
            return null;
        }
        Map<String, Double> totals = new HashMap<>();
        totals.put("总成本", ((Number) tc).doubleValue());
        totals.put("回路总重量", ((Number) tw).doubleValue());
        totals.put("回路总长度", ((Number) tl).doubleValue());
        return totals;
    }

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
     * 最终输出前：对 GA 内部 3 字段精简 Map 重新计算一次整车信息，
     * 补齐 topoId/caseId/finishStatue/initializationScheme 等输出字段。
     * 只对 top~20 个方案调用，不会 OOM。
     */
    private Map<String, Object> enrichToFullScheme(
            Map<String, Object> slim,
            Map<String, Object> jsonMap,
            ObjectMapper objectMapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> topoInfoMap,
            Map<String, String> projectInfo,
            boolean isInitial) throws Exception {
        List<Map<String, String>> loops = (List<Map<String, String>>) slim.get("loopInfos");
        List<Map<String, String>> apps = (List<Map<String, String>>) slim.get("appPositions");

        Map<String, Object> tempJsonMap = new HashMap<>(jsonMap);
        tempJsonMap.put("loopInfos", loops);
        tempJsonMap.put("appPositions", apps);
        String result = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(tempJsonMap));
        if (result == null || result.isEmpty())
            return slim;

        Map<String, Object> map2 = jsonToMap.TransJsonToMap(result);
        Map<String, Object> projectCircuitInfo = (Map<String, Object>) map2.get("projectCircuitInfo");
        Map<String, Double> projectCost = new HashMap<>();
        projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
        projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
        projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

        map2.put("成本", projectCost);
        map2.put("topoId", topoInfoMap.get("id").toString());
        map2.put("caseId", projectInfo.get("caseId"));
        map2.put("finishStatue", "normal");
        map2.put("initializationScheme", isInitial);
        // 用户设置过变种的用电器信息
        map2.put("variantAppPositions", buildVariantAppList(apps));
        // 移除内部使用的精简字段，不暴露给调用方
        map2.remove("loopInfos");
        map2.remove("appPositions");
        return map2;
    }

    /**
     * 收集方案中用户设置过变种的用电器信息（名称 → 位置点名称，仅 changeType≠0 的用电器）
     */
    private Map<String, String> buildVariantAppList(List<Map<String, String>> apps) {
        Map<String, String> map = new LinkedHashMap<>();
        if (apps == null)
            return map;
        for (Map<String, String> app : apps) {
            String ct = app.get("changeType");
            if (ct == null || "0".equals(ct))
                continue;
            String appName = app.get("appName");
            if (appName == null)
                continue;
            String posName = app.get("unregularPointName");
            if (posName == null || posName.isEmpty()) {
                posName = app.get("regularPointName");
            }
            map.put(appName, posName != null ? posName : "");
        }
        return map;
    }

    /**
     * 生成初代种群
     * 注：所有共享框架数据从 jsonMap 读取，方法签名只接收变化数据。
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
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap,
            Map<String, Object> jsonMap,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Map<String, AppResourceLimit> resourceNum) throws Exception {
        // 线程安全的结果收集器
        List<Map<String, Object>> population = Collections.synchronizedList(new ArrayList<>());
        // 原子计数器：用 CAS 替代 synchronizedList.size() 检查，避免多线程 read-then-check race
        java.util.concurrent.atomic.AtomicInteger currentSize = new java.util.concurrent.atomic.AtomicInteger(0);
        // 每个任务最多重试次数
        int maxRetriesPerTask = Math.max(10, populationSize / 5);
        // 任务数：略多于目标数，补偿失败率
        int totalTasks = Math.min(populationSize * 2, populationSize * 10);

        long genStartMs = System.currentTimeMillis();
        System.out.println("开始并行生成 " + populationSize + " 个初代个体（" + threadPool.getThreadCount() + " 线程）...");

        for (int t = 0; t < totalTasks; t++) {
            // 队列满时 execute 会自动阻塞，形成自然背压
            threadPool.execute(() -> {
                // 用乐观的 CAS 抢占名额：如果失败说明已满，直接退出
                if (!tryAcquireSlot(currentSize, populationSize))
                    return;
                Random random = new Random();
                for (int retry = 0; retry < maxRetriesPerTask; retry++) {
                    if (currentSize.get() >= populationSize
                            || optimizeStopStatusStore.get(optimizeRecordId) == false) {
                        currentSize.decrementAndGet(); // 释放抢占的名额
                        return;
                    }
                    try {
                        // 深拷贝当前基线数据
                        List<Map<String, String>> loopInfoCopy = deepCopyLoopInfos(allLoopInfos);
                        List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(allAppPositions);

                        // 扰动
                        boolean success = perturbConstrainedLoops(
                                loopInfoCopy, appPositionsCopy, targetLoops, elecChangeablePosition,
                                togetherGroup, mutualGroup, pointNameId, random, loopElecById);
                        if (!success)
                            continue;

                        perturbUnconstrainedLoops(
                                loopInfoCopy, appPositionsCopy, targetLoops, elecChangeablePosition,
                                pointNameId, random, loopElecById, loopElecByIdStart);

                        // 迭代约束校验（组团 + 互斥），解决交叉依赖场景的单次扫描遗漏
                        if (!enforceAllConstraints(loopInfoCopy, appPositionsCopy, togetherGroup, mutualGroup,
                                loopElecById, elecChangeablePosition, pointNameId, random))
                            continue;

                        // 资源检查
                        if (!elecResourceCheck(loopInfoCopy, resourceNum))
                            continue;

                        // 指纹去重（ConcurrentHashMap.putIfAbsent 自带原子性）
                        String fingerprint = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
                        if (!WareHouse.add(fingerprint))
                            continue;

                        // 整车全量成本计算
                        Map<String, Double> deltaTotals = computeFullCost(
                                loopInfoCopy, appPositionsCopy, jsonMap, objectMapper,
                                projectCircuitInfoOutput, jsonToMap);
                        if (deltaTotals == null) {
                            WareHouse.remove(fingerprint);
                            continue;
                        }
                        Map<String, Double> projectCost = new HashMap<>();
                        projectCost.put("总成本", deltaTotals.get("总成本"));
                        projectCost.put("总重量", deltaTotals.get("回路总重量"));
                        projectCost.put("总长度", deltaTotals.get("回路总长度"));
                        Map<String, Object> map = new HashMap<>();
                        map.put("成本", projectCost);
                        map.put("loopInfos", loopInfoCopy);
                        map.put("appPositions", appPositionsCopy);

                        population.add(map);
                        int curSize = currentSize.incrementAndGet(); // 真正加入时再自增
                        if (curSize % 200 == 0 || curSize == populationSize) {
                            long elapsed = System.currentTimeMillis() - genStartMs;
                            System.out.println("已生成 " + curSize + "/" + populationSize
                                    + " 个有效个体, 耗时 " + elapsed + "ms");
                        }
                        return; // 本任务成功，退出重试
                    } catch (Exception e) {
                        // 异常情况下不阻塞其他任务
                    }
                }
            });
        }

        // 等待所有任务完成
        threadPool.awaitCompletion();

        int resultSize = Math.min(populationSize, population.size());
        List<Map<String, Object>> result = new ArrayList<>(population.subList(0, resultSize));
        long elapsed = System.currentTimeMillis() - genStartMs;
        System.out.println("初代种群生成完成: " + result.size() + " 个个体, "
                + "总耗时 " + elapsed + "ms, "
                + (elapsed > 0 ? (result.size() * 1000L / elapsed) : "?") + " 个/秒");
        return result;
    }

    /**
     * 乐观 CAS 抢占名额：未达上限则 +1 返回 true；已满返回 false。
     * 失败的任务在退出前需要 decrementAndGet 释放。
     */
    private boolean tryAcquireSlot(java.util.concurrent.atomic.AtomicInteger currentSize, int limit) {
        while (true) {
            int cur = currentSize.get();
            if (cur >= limit)
                return false;
            if (currentSize.compareAndSet(cur, cur + 1))
                return true;
        }
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
            if (endAppIntersection == null || endAppIntersection.isEmpty()) {
                // 交集为空：该组所有成员没有共同的合法 endApp，保持原样不扰动
                // 后续由 enforceAllConstraints 兜底校验
                continue;
            }

            List<String> endAppList = new ArrayList<>(endAppIntersection);
            String selectedEndApp = endAppList.get(random.nextInt(endAppList.size()));
            List<String> positions = elecChangeablePosition.get(selectedEndApp);
            String selectedPosition = null;
            if (positions != null && !positions.isEmpty()) {
                selectedPosition = positions.get(random.nextInt(positions.size()));
            }

            String mutualId = null;
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null && loop.get("exclusiveConnRel") != null && !loop.get("exclusiveConnRel").isEmpty()) {
                    mutualId = loop.get("exclusiveConnRel");
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
                if (selectedPosition != null) {
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
                if (candidates.isEmpty()) {
                    // 无可用 endApp，保持原样不扰动，后续由 enforceAllConstraints 兜底校验
                    continue;
                }
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

            String together = loop.get("teamConnRel");
            String mutual = loop.get("exclusiveConnRel");
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
     * 连接关系变量域与互斥约束的承载结构（枚举与计数共用，避免口径漂移）
     */
    private static final class ConnectionPlan {
        final Map<String, List<String>> varDomains;
        final Map<String, List<String>> mutualIdToVarKeys;
        final Set<String> varsInAnyMutualGroup;
        final List<String> varKeys;

        ConnectionPlan(Map<String, List<String>> varDomains,
                Map<String, List<String>> mutualIdToVarKeys,
                Set<String> varsInAnyMutualGroup,
                List<String> varKeys) {
            this.varDomains = varDomains;
            this.mutualIdToVarKeys = mutualIdToVarKeys;
            this.varsInAnyMutualGroup = varsInAnyMutualGroup;
            this.varKeys = varKeys;
        }
    }

    /**
     * 构建"连接关系变量域 + 互斥约束"（枚举与计数共用，逻辑与 enumerateAllSchemes 原内联代码逐行等价）
     *
     * @param targetLoops            待优化的目标回路（与 enumerateAllSchemes 入参一致）
     * @param togetherGroup          组团一起变归组
     * @param loopElecById           回路终点可连接的用电器
     * @param loopElecByIdStart      回路起点可连接的用电器
     * @param elecChangeablePosition 用电器位置可变映射（含 changeType==2 的全量点）
     * @param loopById               回路id -> 回路信息
     */
    private ConnectionPlan buildConnectionVarDomains(
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> togetherGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, Map<String, String>> loopById) {
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
            String mutual = lp.get("exclusiveConnRel");
            String together = lp.get("teamConnRel");
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
        return new ConnectionPlan(varDomains, mutualIdToVarKeys, varsInAnyMutualGroup, varKeys);
    }

    /**
     * 枚举所有可行方案
     *
     * @param targetLoops
     * @param elecChangeablePosition
     * @param togetherGroup
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

        // 复用与计数一致的共享方法构建连接关系变量域与互斥约束（行为与原内联逻辑等价）
        ConnectionPlan plan = buildConnectionVarDomains(
                targetLoops, togetherGroup,
                loopElecById, loopElecByIdStart, elecChangeablePosition, loopById);
        Map<String, List<String>> varDomains = plan.varDomains;
        Map<String, List<String>> mutualIdToVarKeys = plan.mutualIdToVarKeys;
        Set<String> varsInAnyMutualGroup = plan.varsInAnyMutualGroup;
        List<String> varKeys = plan.varKeys;

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
        if (enumeratedSchemes.size() >= caseNumber) {
            System.out.println("枚举方案数已达到限制(" + caseNumber + ")，提前退出");
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
            if (enumeratedSchemes.size() >= caseNumber)
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
     * @Complexity: O(Π ( p_i) * L)，p_i 为每个有可变位置的用电器的位置数，L 为回路数
     */
    private void generateSchemesForAssignment(
            Map<String, String> assignment,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, Set<String>> loopElecByIdStart) {
        if (enumeratedSchemes.size() >= caseNumber) {
            return;
        }
        // Step 1: 解析每个回路的起点/终点用电器（从 assignment 中获取枚举值）
        Map<String, String> loopToStartApp = new LinkedHashMap<>();
        Map<String, String> loopToEndApp = new LinkedHashMap<>();
        Set<String> affectedLoopIds = new LinkedHashSet<>();

        for (Map<String, String> loop : targetLoops) {
            String loopId = loop.get("id");
            affectedLoopIds.add(loopId);
            String together = loop.get("teamConnRel");
            if (together != null && !together.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (together.equals(allLoop.get("teamConnRel"))) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
            String mutual = loop.get("exclusiveConnRel");
            if (mutual != null && !mutual.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (mutual.equals(allLoop.get("exclusiveConnRel"))) {
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
            String together = loop.get("teamConnRel");

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
        if (enumeratedSchemes.size() >= caseNumber) {
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
            if (enumeratedSchemes.size() >= caseNumber) {
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
        // 回路id-回路信息（与枚举侧一致，使用目标回路集合构建）
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : loopInfos)
            loopById.put(lp.get("id"), lp);

        // 复用与枚举完全一致的"连接关系变量域 + 互斥约束"
        ConnectionPlan plan = buildConnectionVarDomains(
                loopInfos, togetherGroup,
                loopElecById, loopElecByIdStart, elecChangeablePosition, loopById);
        Map<String, List<String>> varDomains = plan.varDomains;
        Map<String, List<String>> mutualIdToVarKeys = plan.mutualIdToVarKeys;
        Set<String> varsInAnyMutualGroup = plan.varsInAnyMutualGroup;
        List<String> varKeys = plan.varKeys;

        // 连接关系回溯 + 互斥剪枝：在每一个完整连接赋值上，
        // 按 generateSchemesForAssignment 的口径累加"真正被选中且位置可变"的用电器位置组合数，
        // 使 总方案数 == 实际枚举出的方案总数（不再把位置数无条件乘进全局乘积）。
        long[] total = { 0L };
        boolean[] overflow = { false };
        Map<String, String> currentAssignment = new LinkedHashMap<>();
        Set<String> usedEndApps = new HashSet<>();
        countSchemesByBacktrack(varDomains, varsInAnyMutualGroup,
                0, varKeys, currentAssignment, usedEndApps,
                total, overflow, loopInfos, loopById, elecChangeablePosition);

        long totalCombinations = overflow[0] ? caseNumber + 1L : total[0];
        System.out.println("可行方案总数（含约束）: " + totalCombinations);
        System.out.println("方案数计算耗时: " + (System.currentTimeMillis() - calcStart) + "ms");
        return totalCombinations;
    }

    /**
     * 连接关系回溯统计方案数（与 enumerateSchemesByBacktrack 同口径：变量域、互斥剪枝一致）。
     * 到达叶子（一个完整连接赋值）时，调用 countSchemesForAssignment 累加该赋值下的位置组合数。
     * 超过 caseNumber 时通过 overflow 标记提前结束（此时总数必然 > caseNumber，枚举分支会被跳过）。
     */
    private void countSchemesByBacktrack(
            Map<String, List<String>> varDomains,
            Set<String> varsInAnyMutualGroup,
            int varIndex,
            List<String> varKeys,
            Map<String, String> currentAssignment,
            Set<String> usedEndApps,
            long[] total,
            boolean[] overflow,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition) {
        if (overflow[0])
            return;
        if (varIndex == varKeys.size()) {
            total[0] += countSchemesForAssignment(currentAssignment, targetLoops, loopById, elecChangeablePosition);
            if (total[0] > caseNumber)
                overflow[0] = true;
            return;
        }
        String varKey = varKeys.get(varIndex);
        List<String> domain = varDomains.get(varKey);
        if (domain == null || domain.isEmpty())
            return;
        boolean isInMutualGroup = varsInAnyMutualGroup.contains(varKey);
        for (String endApp : domain) {
            if (overflow[0])
                return;
            if (isInMutualGroup && usedEndApps.contains(endApp))
                continue;
            currentAssignment.put(varKey, endApp);
            usedEndApps.add(endApp);
            countSchemesByBacktrack(varDomains, varsInAnyMutualGroup,
                    varIndex + 1, varKeys, currentAssignment, usedEndApps,
                    total, overflow, targetLoops, loopById, elecChangeablePosition);
            if (overflow[0]) {
                usedEndApps.remove(endApp);
                currentAssignment.remove(varKey);
                return;
            }
            usedEndApps.remove(endApp);
            currentAssignment.remove(varKey);
        }
    }

    /**
     * 单个完整连接赋值下的位置组合数（与 generateSchemesForAssignment Step1/Step2 口径完全一致）。
     * 收集该赋值下真正被选中（起点/终点）且 elecChangeablePosition 非空的用电器，返回其位置数乘积；
     * 无位置可变用电器时返回 1（对应枚举里 appPositionDomains 为空时生成 1 个方案）。
     */
    private long countSchemesForAssignment(
            Map<String, String> assignment,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition) {
        // Step1: affectedLoopIds（与 generateSchemesForAssignment 一致，展开 together/mutual
        // 成员）
        Set<String> affectedLoopIds = new LinkedHashSet<>();
        for (Map<String, String> loop : targetLoops) {
            String loopId = loop.get("id");
            affectedLoopIds.add(loopId);
            String together = loop.get("teamConnRel");
            if (together != null && !together.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (together.equals(allLoop.get("teamConnRel")))
                        affectedLoopIds.add(allLoop.get("id"));
                }
            }
            String mutual = loop.get("exclusiveConnRel");
            if (mutual != null && !mutual.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    if (mutual.equals(allLoop.get("exclusiveConnRel")))
                        affectedLoopIds.add(allLoop.get("id"));
                }
            }
        }

        // Step2: 收集"真正被选中且位置可变"的用电器（口径同 generateSchemesForAssignment）
        Map<String, List<String>> appPositionDomains = new LinkedHashMap<>();
        for (String loopId : affectedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop == null)
                continue;
            String originalStartApp = loop.get("startApp");
            String originalEndApp = loop.get("endApp");
            String together = loop.get("teamConnRel");

            String selectedEndApp = originalEndApp;
            if (together != null && !together.isEmpty()) {
                String assigned = assignment.get("E_G_" + together);
                if (assigned != null)
                    selectedEndApp = assigned;
            } else {
                String assigned = assignment.get("E_L_" + loopId);
                if (assigned != null)
                    selectedEndApp = assigned;
            }

            String selectedStartApp = originalStartApp;
            if (together != null && !together.isEmpty()) {
                String assigned = assignment.get("S_G_" + together);
                if (assigned != null)
                    selectedStartApp = assigned;
            } else {
                String assigned = assignment.get("S_L_" + loopId);
                if (assigned != null)
                    selectedStartApp = assigned;
            }

            if (selectedStartApp != null && !selectedStartApp.isEmpty()
                    && !appPositionDomains.containsKey(selectedStartApp)) {
                List<String> positions = elecChangeablePosition.get(selectedStartApp);
                if (positions != null && !positions.isEmpty())
                    appPositionDomains.put(selectedStartApp, positions);
            }
            if (selectedEndApp != null && !selectedEndApp.isEmpty()
                    && !appPositionDomains.containsKey(selectedEndApp)) {
                List<String> positions = elecChangeablePosition.get(selectedEndApp);
                if (positions != null && !positions.isEmpty())
                    appPositionDomains.put(selectedEndApp, positions);
            }
        }

        if (appPositionDomains.isEmpty())
            return 1L;
        long product = 1L;
        for (List<String> positions : appPositionDomains.values()) {
            product *= positions.size();
            if (product > caseNumber)
                break; // 仅为防止后续越界，外层 overflow 会据此判定 > caseNumber
        }
        return product;
    }

    // 找用电器位置名称
    public String findNameById(String id, List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if (point.get("id").toString().equals(id)) {
                return point.get("pointName").toString();
            }
        }
        return "";
    }

    // 找用电器名称根据id
    public String findAppNameById(String id, List<Map<String, String>> apppositions) {
        for (Map<String, String> app : apppositions) {
            if (app.get("id").equals(id)) {
                return app.get("appName");
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

    /**
     * 用电器可连接资源数量限制（新格式，8 类）；字段为 null 表示该类别不限。
     * dist/drive 分别对应配电器/驱动器的大、中、小电流回路数；hardWire/highSpeedWire 为硬线/高速线回路数。
     */
    public static class AppResourceLimit {
        Integer distHigh; // 配电器大电流回路数
        Integer distMedium; // 配电器中电流回路数
        Integer distLow; // 配电器小电流回路数
        Integer driveHigh; // 驱动器大电流回路数
        Integer driveMedium; // 驱动器中电流回路数
        Integer driveLow; // 驱动器小电流回路数
        Integer hardWire; // 硬线回路数
        Integer highSpeedWire;// 高速线回路数
    }

    /**
     * 解析用电器的 8 类资源限制；全部为 null 时返回 null（视为无限制）
     */
    private AppResourceLimit parseAppResourceLimit(Map<String, String> appPosition) {
        AppResourceLimit limit = new AppResourceLimit();
        limit.distHigh = parseIntField(appPosition.get("distHighCurrentLoop"));
        limit.distMedium = parseIntField(appPosition.get("distMediumCurrentLoop"));
        limit.distLow = parseIntField(appPosition.get("distLowCurrentLoop"));
        limit.driveHigh = parseIntField(appPosition.get("driveHighCurrentLoop"));
        limit.driveMedium = parseIntField(appPosition.get("driveMediumCurrentLoop"));
        limit.driveLow = parseIntField(appPosition.get("driveLowCurrentLoop"));
        limit.hardWire = parseIntField(appPosition.get("hardWire"));
        limit.highSpeedWire = parseIntField(appPosition.get("highSpeedWire"));
        if (limit.distHigh == null && limit.distMedium == null && limit.distLow == null
                && limit.driveHigh == null && limit.driveMedium == null && limit.driveLow == null
                && limit.hardWire == null && limit.highSpeedWire == null) {
            return null;
        }
        return limit;
    }

    /**
     * 将 Object（可能是 Integer 或 String）安全转为 Integer；无法解析返回 null
     */
    private static Integer parseIntField(Object value) {
        if (value == null)
            return null;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}