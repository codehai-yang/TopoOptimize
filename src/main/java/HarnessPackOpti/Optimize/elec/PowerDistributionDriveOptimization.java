package HarnessPackOpti.Optimize.elec;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.FindShortestPath;
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
    // 当前优化类型，供导线选型更新使用
    private String currentOptimizeType = "5";

    // 两点回路成本字典：外层 key = 导线选型，内层 key = 位置A|位置B(按字典序排序，a->b 与 b->a 相同)，value = 两位置点间该导线选型的回路成本(元)
    private Map<String, Map<String, Double>> loopCostDictionary = new HashMap<>();

    // 遗传过程仓库：指纹 -> 精简方案(含字典成本)，每轮裂变的 top 存入，遗传结束后从中选 top 做精确计算
    private final Map<String, Map<String, Object>> gaCostWareHouse = new HashMap<>();

    // 懒计算兜底依赖：字典未覆盖的导线选型(如导线选型更新产生的新选型)即时计算时使用
    private transient List<List<Integer>> dictAdj;
    private transient List<String> dictAllPoint;
    private transient Map<String, List<Map<String, Object>>> dictEdgeByPair;
    private transient Map<String, String> dictPointWet;
    private transient FindShortestPath dictShortestPathSearch;

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
    public static Integer IterationRestrictNumber = 10;

    // 遗传每轮迭代裂变目标样本数量(连接变种×位置变种的交叉方案数)
    public static Integer HybridizationLessRandomSamleNumber = 10000;

    // 遗传算法数量不够时自动补全得次数
    public static Integer AutoCompleteNumber = 100;

    // 连续空代上限：超过此轮次无新有效方案则提前终止遗传迭代
    public static Integer MaxConsecutiveEmptyGenerations = 10;

    // 交叉概率（0.7 表示 70% 的方案参与交叉）
    public static Double CrossoverRate = 0.7;

    // 导线选型电流系数（从线上 eeParamConfigList 反射注入）
    public static Double range0to45A = 0.7;
    public static Double range45to90A = 0.6;
    public static Double rangeAbove90A = 0.5;

    // 每次调用 new 一个本地线程池,生命周期=本次调用,避免不同调用相互干扰
    private ThreadPool threadPool = null;

    // 定义一个仓库，遗传每次生成的方案存储，防止重复
    // ConcurrentHashMap 的 putIfAbsent 本身原子，无需外部 synchronized
    public static Set<String> WareHouse = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 仓库指纹上限：防止遗传超长迭代导致全局仓库无限增长 OOM（指纹已压缩为 32 位 hex，1M 条约 64MB）
    private static final int MAX_WAREHOUSE_SIZE = 1_000_000;

    // 仓库大小保护：超过上限后清空，仅影响跨代去重，不影响正确性（去重主要发生在同代候选内）
    private static void capWareHouse() {
        if (WareHouse.size() > MAX_WAREHOUSE_SIZE) {
            WareHouse.clear();
        }
    }

    // 枚举收集的所有方案
    private List<Map<String, String>> enumeratedSchemes = new ArrayList<>();

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance();
    }

    public static void main(String[] args) throws Exception {
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\驱动分配优化日志.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));// 将文件中内容转为字符串
        PowerDistributionDriveOptimization powerDistributionDriveOptimization = new PowerDistributionDriveOptimization();
        String s = powerDistributionDriveOptimization.powerDriverOptimize(jsonContent);
        File outputFile = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\powerOutput.txt");
        Files.write(outputFile.toPath(), s.getBytes());
        System.out.println("JSON yi cheng gong shu chu dao: " + outputFile.getAbsolutePath());
    }

    public String powerDriverOptimize(String jsonContent) throws Exception {
        try {
            // ========== 修复3：重置静态状态，避免多任务干扰 ==========
            WareHouse.clear();
            BestRepetitionNumber = 0;
            BestCost.clear();
            enumeratedSchemes.clear();
            gaCostWareHouse.clear();

            long categoryTime = System.currentTimeMillis();
            ObjectMapper objectMapper = new ObjectMapper();
            ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
            JsonToMap jsonToMap = new JsonToMap();
            Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
            ReadPowerPropertiesInfo readProjectInfo = new ReadPowerPropertiesInfo();
            Map<String, Object> readProject = readProjectInfo.getProjectInfo(jsonMap);
            threadPool = new ThreadPool(HarnessBranchTopoOptimize.Threads, HarnessBranchTopoOptimize.QueueCapacity);
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
            currentOptimizeType = optimizeType;
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

            GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName,
                    branchBreakList);
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
                        interfaceCodegroup.computeIfAbsent(startIc.toString(), k -> new ArrayList<>())
                                .add(startPointName);
                        pointNameSet.add(startPointName);
                    }
                    Object endIc = edge.get("endInterfaceCode");
                    if (endIc != null && !endIc.toString().trim().isEmpty()) {
                        String endPointNameIc = edge.get("endPointName").toString();
                        interfaceCodegroup.computeIfAbsent(endIc.toString(), k -> new ArrayList<>())
                                .add(endPointNameIc);
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
                        }
                    }
                    list.retainAll(allPoint);
                    // 把自身位置加进去（自身位置可能为空，空值会污染位置列表导致随机选位拿到 null 位置，
                    // 从而让 findNode 解析不到位置点 -> twoPointInfo null，这里过滤掉）
                    String selfPos = eleclection.get(appName);
                    if (selfPos != null && !selfPos.isEmpty()) {
                        list.add(selfPos);
                    }
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

            System.out.println("enum take time:" + (System.currentTimeMillis() - combinationsTime));
            System.out.println("total cases : " + combinations);

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
                        // 修复 +/- 同名用电器同控制器约束
                        fixPlusMinusControllerConstraint(loopInfoCopy, appPositions);

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
                        // 导线选型更新（成本计算前）
                        updateWireSelectionForScheme(loopInfoCopy, appPositionsCopy, currentOptimizeType);
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
                    topBest.add(resultList.get(0));
                }
                // base 方案必须加入最后返回的 top,去重后保证 base 在结果中
                Map<String, Object> baseScheme = buildBaseSchemeMap(originalResult, jsonContent,
                        loopInfos, appPositions, jsonToMap, objectMapper, projectCircuitInfoOutput);
                if (baseScheme != null) {
                    topBest.add(baseScheme);
                }
                topBest = ensureBaseAndDedup(topBest, baseScheme);
                System.out.println("枚举总耗时: " + (System.currentTimeMillis() - enumerateTime) + "ms");
                // 最终输出前：为 top 方案补齐完整整车计算结果
                List<Map<String, Object>> enriched = new ArrayList<>();
                for (Map<String, Object> slim : topBest) {
                    try {
                        List<Map<String, String>> sloopInfos = (List<Map<String, String>>) slim.get("loopInfos");
                        List<Map<String, String>> sAppPositions = (List<Map<String, String>>) slim.get("appPositions");
                        boolean isInitial = originalFingerprint != null
                                && originalFingerprint.equals(generateSchemeFingerprint(sloopInfos, sAppPositions));
                        enriched.add(enrichToFullScheme(slim, jsonMap, objectMapper,
                                projectCircuitInfoOutput, jsonToMap, topoInfoMap, projectInfo, isInitial));
                    } catch (Exception e) {
                        System.err.println("方案还原失败，使用精简版: " + e.getMessage());
                        enriched.add(slim);
                    }
                }
                // 只返回 base 方案及比 base 更优秀的方案(总成本不高于 base)，并按整车精确成本升序
                return objectMapper.writeValueAsString(
                        filterBetterThanBase(enriched, baseScheme == null ? null : dictCostOf(baseScheme)));
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
                    // base 方案加入初代 top 对比
                    Map<String, Object> baseScheme = buildBaseSchemeMap(originalResult, jsonContent,
                            loopInfos, appPositions, jsonToMap, objectMapper, projectCircuitInfoOutput);
                    if (baseScheme != null) {
                        topBest.add(baseScheme);
                    }
                }
            }
            // 遗传算法前置准备：构建两两位置点回路成本字典(两两配对，可重复，a->b 与 b->a 成本相同，
            // 两个位置点相同则默认回路长度 200mm)，每个导线选型维护一层字典，供后续遗传裂变快速计算每根回路成本
            try {
                long dictStart = System.currentTimeMillis();
                loopCostDictionary = buildLoopCostDictionary(adjacencyMatrixGraph, edges, points, allPoint, loopInfos);
                int wireCount = loopCostDictionary.size();
                int pairCount = loopCostDictionary.values().stream().findFirst().map(Map::size).orElse(0);
                System.out.println("两点回路成本字典构建完成，导线选型数: " + wireCount + "，位置点对条目数: " + pairCount
                        + "，耗时: " + (System.currentTimeMillis() - dictStart) + "ms");
            } catch (Exception e) {
                System.err.println("两点回路成本字典构建失败，将继续使用原成本计算: " + e.getMessage());
                loopCostDictionary = new HashMap<>();
            }

            // 遗传算法
            int hybridizationNumber = 0;
            int consecutiveEmptyGenerations = 0;
            List<Map<String, Object>> currentTopBest = topBest;
            // 每代裂变目标：连接关系变种 M × 位置变种 N = 100 × 100 = 10000（设计确认）
            // 注意：HybridizationLessRandomSamleNumber 会被前端以旧默认值(≈200)反射注入覆盖，
            // 若用它算 sqrt 只能得到 14×14=196，故这里不依赖它，直接固定每代裂变 10000 个候选。
            int fissionTargetPerGen = 10000;
            int connVariantCount = (int) Math.round(Math.sqrt(fissionTargetPerGen)); // 100
            int posVariantCount = connVariantCount; // 100
            System.out.println("[诊断] 前端注入 HybridizationLessRandomSamleNumber=" + HybridizationLessRandomSamleNumber
                    + "，每代裂变固定 连接" + connVariantCount + "×位置" + posVariantCount + "=" + fissionTargetPerGen);
            // 把初代 top 也存入仓库(方案+字典成本)，遗传结束后从中选 top 做精确计算
            gaCostWareHouse.clear();
            if (!currentTopBest.isEmpty()) {
                for (Map<String, Object> s : currentTopBest) {
                    Map<String, Object> withCost = schemeWithDictCost(s);
                    String fp = generateSchemeFingerprint(
                            (List<Map<String, String>>) withCost.get("loopInfos"),
                            (List<Map<String, String>>) withCost.get("appPositions"));
                    gaCostWareHouse.put(fp, withCost);
                }
            }
            while (true) {
                System.out.println((hybridizationNumber + 1) + "代迭代开始，仓库方案数: " + WareHouse.size());
                if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                    System.out.println("优化被手动中断，退出遗传");
                    break;
                }
                if (currentTopBest.isEmpty()) {
                    System.out.println("当前代 top 为空，退出遗传");
                    break;
                }
                // 收敛判断：连续多代最优(字典成本口径)无改善则提前终止（重复计数在每代结束时更新）
                if (BestRepetitionNumber >= IterationRestrictNumber) {
                    System.out.println("迭代次数达到限制，连续与上一代结果相同达到 " + BestRepetitionNumber + " 次");
                    break;
                }

                // ===== 每代遗传裂变：连接关系变种 × 位置变种 交叉 =====
                long fissionStart = System.currentTimeMillis();
                List<Map<String, Object>> parents = currentTopBest;
                // 1. 生成连接关系变种(以父代为基底)
                List<ConnectionVariant> connVariants = generateConnectionVariants(
                        parents, connVariantCount, targetLoops, elecChangeablePosition, pointNameId,
                        loopElecById, loopElecByIdStart, togetherGroup, mutualGroup, random);
                // 2. 生成位置变种
                List<PositionVariant> posVariants = generatePositionVariants(
                        parents, posVariantCount, targetLoops, elecChangeablePosition, random);
                // 3. 交叉裂变：连接变种i(基底+连接编辑) × 位置变种j(位置编辑)
                int[] crossoverValidCount = new int[1];
                List<Map<String, Object>> fissionSchemes = crossoverFission(
                        connVariants, posVariants, fissionTargetPerGen,
                        togetherGroup, mutualGroup, loopElecById, elecChangeablePosition, pointNameId,
                        resourceNum, random, crossoverValidCount);
                // 3.1 批A 纯连接变异：连接变种本身就是完整方案，直接走同一套后处理
                List<Map<String, Object>> pureConnSchemes = new ArrayList<>();
                int pureConnDup = 0, pureConnConstraint = 0, pureConnResource = 0, pureConnZero = 0;
                for (ConnectionVariant cv : connVariants) {
                    BuildResult r = validateAndBuildScheme(
                            deepCopyLoopInfos(cv.loopInfos), deepCopyAppPositions(cv.appPositions),
                            togetherGroup, mutualGroup, loopElecById, elecChangeablePosition,
                            pointNameId, resourceNum, random);
                    if (r.scheme != null) {
                        pureConnSchemes.add(r.scheme);
                    } else if (r.reason == BuildFailReason.DUPLICATE) {
                        pureConnDup++;
                    } else if (r.reason == BuildFailReason.RESOURCE) {
                        pureConnResource++;
                    } else if (r.reason == BuildFailReason.ZERO_COST) {
                        pureConnZero++;
                    } else {
                        pureConnConstraint++;
                    }
                }
                // 3.2 批B 纯位置变异：位置编辑集叠加到最优父代(连接不变)，再走后处理
                List<Map<String, Object>> purePosSchemes = new ArrayList<>();
                int purePosDup = 0, purePosConstraint = 0, purePosResource = 0, purePosZero = 0;
                if (!parents.isEmpty()) {
                    for (PositionVariant pv : posVariants) {
                        List<Map<String, String>> posLoopInfos = deepCopyLoopInfos(
                                (List<Map<String, String>>) parents.get(0).get("loopInfos"));
                        List<Map<String, String>> posAppPositions = deepCopyAppPositions(
                                (List<Map<String, String>>) parents.get(0).get("appPositions"));
                        for (Map.Entry<String, String> e : pv.positionEdits.entrySet()) {
                            String appName = e.getKey();
                            String position = e.getValue();
                            for (Map<String, String> ap : posAppPositions) {
                                if (ap.get("appName") != null && ap.get("appName").equalsIgnoreCase(appName)) {
                                    ap.put("unregularPointName", position);
                                    ap.put("unregularPointId", pointNameId.get(position));
                                    break;
                                }
                            }
                        }
                        BuildResult r = validateAndBuildScheme(
                                posLoopInfos, posAppPositions, togetherGroup, mutualGroup, loopElecById,
                                elecChangeablePosition, pointNameId, resourceNum, random);
                        if (r.scheme != null) {
                            purePosSchemes.add(r.scheme);
                        } else if (r.reason == BuildFailReason.DUPLICATE) {
                            purePosDup++;
                        } else if (r.reason == BuildFailReason.RESOURCE) {
                            purePosResource++;
                        } else if (r.reason == BuildFailReason.ZERO_COST) {
                            purePosZero++;
                        } else {
                            purePosConstraint++;
                        }
                    }
                }
                System.out.println((hybridizationNumber + 1) + "代裂变: 期望 连接" + connVariantCount + "/位置" + posVariantCount
                        + "，父代 " + parents.size() + "，实际 连接 " + connVariants.size() + " × 位置 " + posVariants.size()
                        + "，交叉有效 " + crossoverValidCount[0] + "(保留 top " + fissionSchemes.size() + ")，纯连接 " + pureConnSchemes.size()
                        + "(约束 " + pureConnConstraint + "/资源 " + pureConnResource + "/重复 " + pureConnDup + "/成本 " + pureConnZero + ")"
                        + "，纯位置 " + purePosSchemes.size()
                        + "(约束 " + purePosConstraint + "/资源 " + purePosResource + "/重复 " + purePosDup + "/成本 " + purePosZero + ")"
                        + "，耗时 " + (System.currentTimeMillis() - fissionStart) + "ms");

                // 4. 合并候选池：交叉 + 纯连接 + 纯位置 + 上一代 top 30% 精英
                List<Map<String, Object>> candidates = new ArrayList<>(fissionSchemes);
                Set<String> candidateFps = new HashSet<>();
                for (Map<String, Object> fs : fissionSchemes) {
                    candidateFps.add(generateSchemeFingerprint(
                            (List<Map<String, String>>) fs.get("loopInfos"),
                            (List<Map<String, String>>) fs.get("appPositions")));
                }
                for (Map<String, Object> s : pureConnSchemes) {
                    String fp = generateSchemeFingerprint(
                            (List<Map<String, String>>) s.get("loopInfos"),
                            (List<Map<String, String>>) s.get("appPositions"));
                    if (candidateFps.add(fp)) {
                        candidates.add(s);
                    }
                }
                for (Map<String, Object> s : purePosSchemes) {
                    String fp = generateSchemeFingerprint(
                            (List<Map<String, String>>) s.get("loopInfos"),
                            (List<Map<String, String>>) s.get("appPositions"));
                    if (candidateFps.add(fp)) {
                        candidates.add(s);
                    }
                }
                int eliteCount = (int) Math.ceil(parents.size() * 0.3);
                for (int e = 0; e < Math.min(eliteCount, parents.size()); e++) {
                    Map<String, Object> elite = schemeWithDictCost(parents.get(e));
                    String fp = generateSchemeFingerprint(
                            (List<Map<String, String>>) elite.get("loopInfos"),
                            (List<Map<String, String>>) elite.get("appPositions"));
                    if (candidateFps.add(fp)) {
                        candidates.add(elite);
                    }
                }

                if (candidates.isEmpty()) {
                    consecutiveEmptyGenerations++;
                    if (consecutiveEmptyGenerations >= MaxConsecutiveEmptyGenerations) {
                        System.out.println("连续 " + consecutiveEmptyGenerations + " 代无新方案，提前终止");
                        break;
                    }
                    hybridizationNumber++;
                    continue;
                }
                consecutiveEmptyGenerations = 0;

                // 5. 按字典成本取 top 作为下一代父代
                candidates.sort(Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf));
                int nextSize = Math.min(TopNumber, candidates.size());
                currentTopBest = new ArrayList<>(candidates.subList(0, nextSize));

                // 6. 本轮 top 存入仓库(方案+成本)
                for (Map<String, Object> s : currentTopBest) {
                    Map<String, Object> withCost = schemeWithDictCost(s);
                    String fp = generateSchemeFingerprint(
                            (List<Map<String, String>>) withCost.get("loopInfos"),
                            (List<Map<String, String>>) withCost.get("appPositions"));
                    gaCostWareHouse.put(fp, withCost);
                }

                // 7. 内存保护：WareHouse 超上限清空；gaCostWareHouse 只保留最优 TopNumber 名，
                //    防止超长迭代导致全局仓库无限增长 OOM（最终只取 top TopNumber，裁剪不影响结果）
                capWareHouse();
                if (gaCostWareHouse.size() > TopNumber) {
                    List<Map<String, Object>> trimmed = new ArrayList<>(gaCostWareHouse.values());
                    trimmed.sort(Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf));
                    gaCostWareHouse.clear();
                    for (int i = 0; i < Math.min(TopNumber, trimmed.size()); i++) {
                        Map<String, Object> t = trimmed.get(i);
                        String f = generateSchemeFingerprint(
                                (List<Map<String, String>>) t.get("loopInfos"),
                                (List<Map<String, String>>) t.get("appPositions"));
                        gaCostWareHouse.put(f, t);
                    }
                }

                // 8. 每代结束打印当代最优方案成本(字典口径)及与上一代的重复次数，便于观测运行进度
                if (!currentTopBest.isEmpty()) {
                    double currentBest = dictCostOf(currentTopBest.get(0));
                    if (hybridizationNumber == 0) {
                        BestCost.put("总成本", currentBest);
                        BestRepetitionNumber = 0;
                    } else {
                        double prevBest = BestCost.getOrDefault("总成本", Double.MAX_VALUE);
                        if (Math.abs(prevBest - currentBest) < 1e-6) {
                            BestRepetitionNumber++;
                        } else {
                            BestCost.put("总成本", currentBest);
                            BestRepetitionNumber = 0;
                        }
                    }
                    System.out.println((hybridizationNumber + 1) + "代结束，当代最优成本: " + currentBest
                            + "，连续与上一代重复: " + BestRepetitionNumber + " 次");
                }

                hybridizationNumber++;
            }
            System.out.println("遗传算法完成，共迭代 " + hybridizationNumber + " 代");
            // 遗传结束后从仓库中选 top(按字典成本)，后续再做精确计算
            // 注意：遗传过程中候选方案只用字典成本(calcSchemeDictCost)校验，它不调用整车精确计算，
            // 因此个别方案的起点/终点用电器位置可能解析不到真实拓扑点，直接精确还原会报
            // "twoPointInfo is null"。这里先用整车精确计算(computeFullCost)过滤出可还原的方案，
            // 再取 top 交给 enrichToFullScheme，保证最终返回的都是可精确计算的方案。
            if (!gaCostWareHouse.isEmpty()) {
                List<Map<String, Object>> allGenTop = new ArrayList<>(gaCostWareHouse.values());
                allGenTop.sort(Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf));
                List<Map<String, Object>> restorableTop = new ArrayList<>();
                int skippedUnrestorable = 0;
                for (Map<String, Object> s : allGenTop) {
                    if (restorableTop.size() >= TopNumber) {
                        break;
                    }
                    try {
                        // 深拷贝校验，避免 computeFullCost 内部的 fixPlusMinus/导线选型更新污染仓库中的原方案
                        Map<String, Double> precise = computeFullCost(
                                deepCopyLoopInfos((List<Map<String, String>>) s.get("loopInfos")),
                                deepCopyAppPositions((List<Map<String, String>>) s.get("appPositions")),
                                jsonMap, objectMapper, projectCircuitInfoOutput, jsonToMap);
                        if (precise != null) {
                            restorableTop.add(s);
                        } else {
                            skippedUnrestorable++;
                            System.err.println("[最终筛选] 方案无法整车精确还原，跳过: dictCost=" + dictCostOf(s));
                        }
                    } catch (Exception e) {
                        skippedUnrestorable++;
                        System.err.println("[最终筛选] 方案精确还原异常，跳过: " + e.getMessage());
                    }
                }
                currentTopBest = restorableTop;
                System.out.println("遗传结束，仓库共 " + gaCostWareHouse.size()
                        + " 个方案，选取 top " + currentTopBest.size() + " 个等待精确计算"
                        + (skippedUnrestorable > 0 ? "，跳过不可精确还原 " + skippedUnrestorable + " 个" : ""));
            }

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
                            // 修复 +/- 同名用电器同控制器约束
                            fixPlusMinusControllerConstraint(loopInfoCopy, appPositions);
                            Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);
                            jsonMapCopy.put("loopInfos", loopInfoCopy);
                            jsonMapCopy.put("appPositions", appPositionsCopy);
                            // 导线选型更新（成本计算前）
                            updateWireSelectionForScheme(loopInfoCopy, appPositionsCopy, currentOptimizeType);
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
            // base 方案必须加入最后返回的 top,去重后保证 base 在结果中
            Map<String, Object> baseSchemeFinal = buildBaseSchemeMap(originalResult, jsonContent,
                    loopInfos, appPositions, jsonToMap, objectMapper, projectCircuitInfoOutput);
            if (baseSchemeFinal != null) {
                currentTopBest.add(baseSchemeFinal);
            }
            currentTopBest = ensureBaseAndDedup(currentTopBest, baseSchemeFinal);

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
            // 此前按字典近似成本排序，enrichToFullScheme 后已重算为整车精确成本；
            // 最终只返回 base 方案及比 base 更优的方案(总成本不高于 base)，并按精确成本升序排列
            return objectMapper.writeValueAsString(
                    filterBetterThanBase(enrichedTopBest, baseSchemeFinal == null ? null : dictCostOf(baseSchemeFinal)));
        } finally {
            // 不管正常返回还是异常,都关闭本次调用的本地线程池
            if (threadPool != null) {
                try {
                    threadPool.shutdown();
                } catch (Exception e) {
                    System.err.println("[PowerDistributionDriveOptimization] 关闭线程池异常: " + e.getMessage());
                }
            }
        }
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
            appIndex.put(ap.get("appName") == null ? null : ap.get("appName").toUpperCase(), ap);
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

            Map<String, String> startAp = appIndex.get(startApp.toUpperCase());
            if (startAp != null && !startPos.isEmpty()) {
                startAp.put("unregularPointName", startPos);
                startAp.put("unregularPointId", pointNameId.get(startPos));
            }
            Map<String, String> endAp = appIndex.get(endApp.toUpperCase());
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
            // 回路里的用电器名称视为正确大小写；resourceNum 键来自 appPositions(用户可能写错大小写)，忽略大小写匹配，
            // 并以 resourceNum 中实际的键聚合，保证下方 entrySet 遍历按原键取数一致。
            String startKey = findKeyIgnoreCase(resourceNum, startApp);
            if (startKey != null) {
                actualResource.computeIfAbsent(startKey, k -> new HashMap<>()).merge(statKey, 1, Integer::sum);
            }
            String endKey = findKeyIgnoreCase(resourceNum, endApp);
            if (endKey != null) {
                actualResource.computeIfAbsent(endKey, k -> new HashMap<>()).merge(statKey, 1, Integer::sum);
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
                    if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(newEndApp)) {
                        existingPosition = appPos.get("unregularPointName");
                        break;
                    }
                }
                if (existingPosition != null && !existingPosition.isEmpty()) {
                    // 位置已存在，无需操作
                } else {
                    List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, newEndApp);
                    if (positions != null && !positions.isEmpty()) {
                        String selectedPosition = positions.get(random.nextInt(positions.size()));
                        for (Map<String, String> appPos : childApps) {
                            if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(newEndApp)) {
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
     * 校验方案中所有回路起点/终点用电器都能够在 appPositions 中解析出非空位置点。
     * 口径与整车计算 ProjectCircuitInfoOutput.findNode 一致（忽略大小写，unregularPointName 优先、regularPointName 兜底）。
     * 任一回路解析不到位置，说明整车精确计算时 findTwoPointInfo 会返回 null，直接拒绝该方案。
     */
    private boolean allLoopPositionsResolvable(List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions) {
        if (loopInfos == null || loopInfos.isEmpty()
                || appPositions == null || appPositions.isEmpty()) {
            return false;
        }
        for (Map<String, String> loop : loopInfos) {
            if (!singleAppPositionResolvable(loop.get("startApp"), appPositions)
                    || !singleAppPositionResolvable(loop.get("endApp"), appPositions)) {
                return false;
            }
        }
        return true;
    }

    /** 单个用电器是否能在 appPositions 中解析出非空位置（空连接放行，与整车计算不参与两点计算的回路一致）。 */
    private boolean singleAppPositionResolvable(String appName, List<Map<String, String>> appPositions) {
        if (appName == null || appName.trim().isEmpty()) {
            return true;
        }
        for (Map<String, String> ap : appPositions) {
            if (ap.get("appName") != null && ap.get("appName").equalsIgnoreCase(appName)) {
                String up = ap.get("unregularPointName");
                if (up != null && !up.isEmpty()) {
                    return true;
                }
                String rp = ap.get("regularPointName");
                if (rp != null && !rp.isEmpty()) {
                    return true;
                }
                return false;
            }
        }
        return false;
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
                if (ap.get("appName") != null && ap.get("appName").equalsIgnoreCase(appName)
                        && ap.get("unregularPointName") != null
                        && !ap.get("unregularPointName").isEmpty()) {
                    hasPosition = true;
                    break;
                }
            }
            if (!hasPosition) {
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, appName);
                if (positions != null && !positions.isEmpty()) {
                    String chosenPos = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> ap : appPositions) {
                        if (ap.get("appName") != null && ap.get("appName").equalsIgnoreCase(appName)) {
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
     * 不区分大小写地从 Map 中查找与 target 匹配的键，返回 Map 里实际存在的键(保留其原始大小写)。
     * 用途：回路里的用电器名称视为正确大小写，而 appPositions/资源限制等映射的键可能来自用户填写、大小写不规范，
     * 用该键回查原 Map 可保证后续 entrySet/getOrDefault 等以原键为准的操作一致。
     */
    private static String findKeyIgnoreCase(Map<String, ?> map, String target) {
        if (map == null || map.isEmpty() || target == null)
            return null;
        for (String key : map.keySet())
            if (key != null && key.equalsIgnoreCase(target))
                return key;
        return null;
    }

    private static boolean containsKeyIgnoreCase(Map<String, ?> map, String target) {
        return findKeyIgnoreCase(map, target) != null;
    }

    /** 从以用电器名称为键的位置映射中，忽略大小写地取该用电器可用的位置列表。 */
    private static List<String> positionsOfIgnoreCase(Map<String, List<String>> map, String target) {
        String key = findKeyIgnoreCase(map, target);
        return key == null ? null : map.get(key);
    }

    /**
     * 构造 base 方案 Map（原始方案的精简表示）
     * 优先从 originalResult 解析成本；若 originalResult 为 null 或解析失败，
     * 则直接用入参 jsonContent 强制做一次整车计算，确保 base 方案总是能加上。
     */
    private Map<String, Object> buildBaseSchemeMap(String originalResult,
            String jsonContent,
            List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions,
            JsonToMap jsonToMap,
            ObjectMapper objectMapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput) {
        // 1. 优先用已计算好的 originalResult
        String result = originalResult;
        // 2. 若 originalResult 为空,强制用 jsonContent 重新做一次整车计算
        if (result == null || result.isEmpty()) {
            try {
                System.out.println("[base] originalResult 为空，用入参 jsonContent 重新做整车计算");
                result = projectCircuitInfoOutput.projectCircuitInfoOutput(jsonContent);
            } catch (Exception e) {
                System.err.println("[base] 用 jsonContent 重算整车失败: " + e.getMessage());
                return null;
            }
        }
        if (result == null || result.isEmpty()) {
            System.err.println("[base] 整车计算返回空，无法构造 base 方案");
            return null;
        }
        try {
            Map<String, Object> origMap = new HashMap<>();
            origMap.put("loopInfos", deepCopyLoopInfos(loopInfos));
            origMap.put("appPositions", deepCopyAppPositions(appPositions));
            Map<String, Object> rawMap = jsonToMap.TransJsonToMap(result);
            Map<String, Object> pcInfo = (Map<String, Object>) rawMap.get("projectCircuitInfo");
            if (pcInfo != null) {
                Object tc = pcInfo.get("总成本");
                Object tw = pcInfo.get("回路总重量");
                Object tl = pcInfo.get("回路总长度");
                if (tc instanceof Number && tw instanceof Number && tl instanceof Number) {
                    Map<String, Double> projectCost = new HashMap<>();
                    projectCost.put("总成本", ((Number) tc).doubleValue());
                    projectCost.put("总重量", ((Number) tw).doubleValue());
                    projectCost.put("总长度", ((Number) tl).doubleValue());
                    origMap.put("成本", projectCost);
                }
            }
            if (origMap.containsKey("成本")) {
                return origMap;
            }
            System.err.println("[base] 整车计算结果中缺少 projectCircuitInfo 或成本字段");
        } catch (Exception e) {
            System.err.println("[base] 构造 base 方案失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 按 成本+用电器可变位置 去重
     * 成本相同（总成本+总重量+总长度）且可变位置相同的方案只保留一个
     */
    private List<Map<String, Object>> dedupByCostAndPositions(List<Map<String, Object>> schemes) {
        if (schemes == null || schemes.size() <= 1) {
            return schemes;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> scheme : schemes) {
            String key = buildCostAndPositionKey(scheme);
            if (seen.add(key)) {
                result.add(scheme);
            }
        }
        return result;
    }

    /**
     * 去重后保证 base 方案始终包含在最终返回结果中。
     * 从其余方案取最优 TopNumber 个，再加回 base，共 TopNumber+1 个，最后按总成本排序。
     *
     * @param schemes    去重前的方案列表(已包含 base 方案)
     * @param baseScheme base 方案(可能为 null)
     * @return 最终方案列表
     */
    private List<Map<String, Object>> ensureBaseAndDedup(List<Map<String, Object>> schemes,
            Map<String, Object> baseScheme) {
        if (baseScheme == null) {
            return dedupByCostAndPositions(schemes);
        }
        // 先按 成本+位置 去重
        List<Map<String, Object>> deduped = dedupByCostAndPositions(schemes);
        String baseKey = buildCostAndPositionKey(baseScheme);
        // 检查 base 是否在去重结果中
        boolean baseInResult = false;
        for (Map<String, Object> scheme : deduped) {
            if (buildCostAndPositionKey(scheme).equals(baseKey)) {
                baseInResult = true;
                break;
            }
        }
        // 确保 base 在结果中(去重可能去掉了与 base 等价的方案)
        if (!baseInResult) {
            deduped.add(baseScheme);
        }
        // 取 top TopNumber: 先移除 base,取最优 TopNumber 个,再加回 base → 共 TopNumber+1 个
        if (deduped.size() > TopNumber + 1) {
            deduped.remove(baseScheme);
            List<Map<String, Object>> topBest = new FindBest().findBest(deduped, "成本", Math.max(1, TopNumber));
            topBest.add(baseScheme);
            deduped = topBest;
        }
        // 最终按总成本排序，保证 base 也参与排序
        deduped.sort(Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf));
        return deduped;
    }

    /**
     * 最终输出过滤规则：只返回 base 方案以及比 base 成本更优(总成本不高于 base)的方案。
     * 若没有任何方案优于 base，则结果只剩 base 一个；baseCost 为 null 表示无法构造 base，不过滤。
     * 注意：必须在 enrichToFullScheme 补齐整车精确成本后调用，保证与 base 的成本口径一致。
     * 返回前按整车精确总成本升序排列。
     *
     * @param enriched 已补齐整车精确成本的完整方案列表(应包含 base 方案)
     * @param baseCost base 方案的总成本阈值；为 null 表示不过滤
     */
    private List<Map<String, Object>> filterBetterThanBase(List<Map<String, Object>> enriched, Double baseCost) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (enriched == null) {
            return result;
        }
        if (baseCost == null) {
            result.addAll(enriched);
        } else {
            // 容忍浮点误差：base 本身(成本相等)也算保留
            double threshold = baseCost + 1e-9;
            for (Map<String, Object> scheme : enriched) {
                if (scheme != null && dictCostOf(scheme) <= threshold) {
                    result.add(scheme);
                }
            }
        }
        result.sort(Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf));
        return result;
    }

    /**
     * 构造成本+可变位置的指纹 key
     */
    private String buildCostAndPositionKey(Map<String, Object> scheme) {
        StringBuilder sb = new StringBuilder();
        Object costObj = scheme.get("成本");
        if (costObj instanceof Map) {
            Map<String, Object> cost = (Map<String, Object>) costObj;
            sb.append("C:").append(cost.getOrDefault("总成本", "")).append(",")
                    .append(cost.getOrDefault("总重量", "")).append(",")
                    .append(cost.getOrDefault("总长度", ""));
        }
        sb.append("|P:");
        Object appsObj = scheme.get("appPositions");
        if (appsObj instanceof List) {
            List<Map<String, String>> apps = (List<Map<String, String>>) appsObj;
            List<String> parts = new ArrayList<>();
            for (Map<String, String> app : apps) {
                String ct = app.get("changeType");
                if (ct == null || "0".equals(ct)) {
                    continue;
                }
                String appName = app.get("appName");
                String posName = app.get("unregularPointName");
                if (posName == null || posName.isEmpty()) {
                    posName = app.get("regularPointName");
                }
                parts.add(appName + "=" + (posName != null ? posName : ""));
            }
            Collections.sort(parts);
            sb.append(String.join(";", parts));
        }
        return sb.toString();
    }

    /**
     * 导线选型更新：根据下游用电器回路线径相加决定上游回路导线选型
     * 优化类型3: 仅更新 配电单元-配电单元 回路
     * 优化类型5: 先更新 配电单元-控制器 回路，再更新 配电单元-配电单元 回路
     */
    /**
     * 导线选型更新入口，在每次成本计算前调用
     * 根据优化类型构建树并更新对应回路的导线选型
     */
    private void updateWireSelectionForScheme(List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions, String optimizeType) {
        if (loopInfos == null || loopInfos.isEmpty() || appPositions == null) {
            return;
        }
        // 仅对类型3和5做导线选型更新
        if (!"3".equals(optimizeType) && !"5".equals(optimizeType)) {
            return;
        }
        // 构建用电器类型映射：键统一为大写，兼容 appPositions 中大小写不规范而回路里名称正确的情况
        Map<String, String> appTypeMap = new HashMap<>();
        for (Map<String, String> app : appPositions) {
            String name = app.get("appName");
            String type = app.get("appType");
            if (name != null && type != null) {
                appTypeMap.put(name.toUpperCase(), type);
            }
        }
        // 构建邻接表: appName -> [(neighborApp, loopInfo)]
        Map<String, List<Object[]>> adjacency = new HashMap<>();
        for (Map<String, String> loop : loopInfos) {
            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            if (startApp == null || endApp == null) {
                continue;
            }
            adjacency.computeIfAbsent(startApp, k -> new ArrayList<>()).add(new Object[] { endApp, loop });
            adjacency.computeIfAbsent(endApp, k -> new ArrayList<>()).add(new Object[] { startApp, loop });
        }
        // 识别隔离模块: 把图按隔离模块分割成多个子图(逻辑断开,非物理断开)
        Set<String> isolatorSet = new HashSet<>();
        for (Map.Entry<String, String> entry : appTypeMap.entrySet()) {
            if ("隔离模块".equals(entry.getValue())) {
                isolatorSet.add(entry.getKey());
            }
        }
        // 构建过滤后的邻接表: 去掉涉及隔离模块的回路
        Map<String, List<Object[]>> filteredAdjacency = new HashMap<>();
        for (Map.Entry<String, List<Object[]>> entry : adjacency.entrySet()) {
            String node = entry.getKey();
            if (isolatorSet.contains(node.toUpperCase())) {
                continue;
            }
            for (Object[] edge : entry.getValue()) {
                String neighbor = (String) edge[0];
                if (isolatorSet.contains(neighbor.toUpperCase())) {
                    continue;
                }
                filteredAdjacency.computeIfAbsent(node, k -> new ArrayList<>()).add(edge);
            }
        }
        // 连通分量分析: 把过滤后的图分成多个子图
        Set<String> allNodes = new HashSet<>(filteredAdjacency.keySet());
        for (List<Object[]> edges : filteredAdjacency.values()) {
            for (Object[] edge : edges) {
                allNodes.add((String) edge[0]);
            }
        }
        Set<String> globalVisited = new HashSet<>();
        for (String node : allNodes) {
            if (globalVisited.contains(node)) {
                continue;
            }
            // BFS 找连通分量
            Set<String> component = new LinkedHashSet<>();
            LinkedList<String> queue = new LinkedList<>();
            queue.add(node);
            component.add(node);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                List<Object[]> neighbors = filteredAdjacency.get(cur);
                if (neighbors == null) {
                    continue;
                }
                for (Object[] edge : neighbors) {
                    String neighbor = (String) edge[0];
                    if (!component.contains(neighbor)) {
                        component.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            globalVisited.addAll(component);
            // 检查该子图是否含发电/储电单元
            String root = null;
            for (String n : component) {
                String t = appTypeMap.get(n.toUpperCase());
                if ("发电单元".equals(t) || "储电单元".equals(t)) {
                    root = n;
                    break;
                }
            }
            if (root == null) {
                continue;
            }
            // 对该含发/储的子图更新导线选型
            updateWireSelectionForSubgraph(component, filteredAdjacency, appTypeMap, optimizeType);
        }
    }

    /**
     * 对单个子图更新导线选型
     * 在子图内找根节点(发电/储电单元), BFS建树, 反向BFS更新回路导线选型
     * 只处理含发电/储电单元的子图,无发/储的子图不调用此方法
     */
    private void updateWireSelectionForSubgraph(Set<String> subgraphNodes,
            Map<String, List<Object[]>> adjacency, Map<String, String> appTypeMap, String optimizeType) {
        // 在子图内查找根节点: 优先发电单元, 无则储电单元
        String root = null;
        for (String node : subgraphNodes) {
            if ("发电单元".equals(appTypeMap.get(node.toUpperCase()))) {
                root = node;
                break;
            }
        }
        if (root == null) {
            for (String node : subgraphNodes) {
                if ("储电单元".equals(appTypeMap.get(node.toUpperCase()))) {
                    root = node;
                    break;
                }
            }
        }
        if (root == null) {
            return;
        }
        // BFS构建有向树: parent -> [(child, loop)]
        Map<String, String> parentMap = new HashMap<>();
        Map<String, Map<String, String>> parentLoopMap = new HashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        LinkedList<String> queue = new LinkedList<>();
        queue.add(root);
        visited.add(root);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            List<Object[]> neighbors = adjacency.get(node);
            if (neighbors == null) {
                continue;
            }
            for (Object[] edge : neighbors) {
                String neighbor = (String) edge[0];
                Map<String, String> loop = (Map<String, String>) edge[1];
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    parentMap.put(neighbor, node);
                    parentLoopMap.put(neighbor, loop);
                    queue.add(neighbor);
                }
            }
        }
        // 反向BFS序(自底向上)处理
        List<String> bfsOrder = new ArrayList<>(visited);
        Collections.reverse(bfsOrder);
        // 记录已更新的回路线径: loopId -> newGauge
        Map<String, Double> updatedGauges = new HashMap<>();
        boolean updateController = "5".equals(optimizeType);
        for (String node : bfsOrder) {
            String nodeType = appTypeMap.get(node.toUpperCase());
            // 按回路获取子回路: 从邻接表排除到parent的回路
            List<Object[]> allNeighbors = adjacency.get(node);
            if (allNeighbors == null || allNeighbors.isEmpty()) {
                continue;
            }
            Map<String, String> parentLoopForNode = parentLoopMap.get(node);
            String parentLoopId = (parentLoopForNode != null) ? parentLoopForNode.get("id") : null;
            List<Object[]> children = new ArrayList<>();
            for (Object[] edge : allNeighbors) {
                Map<String, String> loop = (Map<String, String>) edge[1];
                String loopId = loop.get("id");
                if (parentLoopId != null && parentLoopId.equals(loopId)) {
                    continue;
                }
                if (!isValidLoopAttr(loop)) {
                    continue;
                }
                children.add(edge);
            }
            if (children.isEmpty()) {
                continue;
            }
            // 自底向上更新父回路导线选型: node 到 parent 的回路
            // 规则: 子回路按 loopAttr 累加(配电回路/驱动回路才参与),不看用电器类型
            // 一层层往上更新;连接发/储单元的顶层回路不更新,其他回路都更新
            // 类型5: 控制器节点,更新其到父节点的父回路
            if (updateController && "控制器".equals(nodeType)) {
                String parent = parentMap.get(node);
                if (parent == null) {
                    continue;
                }
                String parentType = appTypeMap.get(parent.toUpperCase());
                // 父为发电/储电单元 = 顶层回路,跳过不更新;其他都更新
                if ("发电单元".equals(parentType) || "储电单元".equals(parentType)) {
                    continue;
                }
                Map<String, String> parentLoop = parentLoopMap.get(node);
                if (parentLoop == null || !isValidLoopAttr(parentLoop)) {
                    continue;
                }
                double sum = 0;
                for (Object[] child : children) {
                    Map<String, String> loop = (Map<String, String>) child[1];
                    String loopId = loop.get("id");
                    if (loopId != null && updatedGauges.containsKey(loopId)) {
                        sum += updatedGauges.get(loopId);
                    } else {
                        sum += extractWireGauge(loop.get("loopWireway"));
                    }
                }
                double calculated = applyWireCoefficient(sum);
                String newWireType = findClosestWireType(calculated);
                if (newWireType != null) {
                    parentLoop.put("loopWireway", newWireType);
                    updatedGauges.put(parentLoop.get("id"), calculated);
                }
            }
            // 类型3和5: 配电单元节点,更新其到父节点的父回路
            // 注意: 连接发/储单元的顶层回路不更新
            if ("配电单元".equals(nodeType)) {
                String parent = parentMap.get(node);
                if (parent == null) {
                    continue;
                }
                String parentType = appTypeMap.get(parent.toUpperCase());
                // 父为发电/储电单元 = 顶层回路,跳过不更新;其他都更新
                if ("发电单元".equals(parentType) || "储电单元".equals(parentType)) {
                    continue;
                }
                Map<String, String> parentLoop = parentLoopMap.get(node);
                if (parentLoop == null || !isValidLoopAttr(parentLoop)) {
                    continue;
                }
                double sum = 0;
                for (Object[] child : children) {
                    Map<String, String> loop = (Map<String, String>) child[1];
                    String loopId = loop.get("id");
                    if (loopId != null && updatedGauges.containsKey(loopId)) {
                        sum += updatedGauges.get(loopId);
                    } else {
                        sum += extractWireGauge(loop.get("loopWireway"));
                    }
                }
                double calculated = applyWireCoefficient(sum);
                String newWireType = findClosestWireType(calculated);
                if (newWireType != null) {
                    parentLoop.put("loopWireway", newWireType);
                    updatedGauges.put(parentLoop.get("id"), calculated);
                }
            }
        }
    }

    /**
     * 判断回路属性是否为配电回路或驱动回路(只有这两种属性参与累加和更新)
     */
    private boolean isValidLoopAttr(Map<String, String> loop) {
        if (loop == null) {
            return false;
        }
        String attr = loop.get("loopAttr");
        return "配电回路".equals(attr) || "驱动回路".equals(attr);
    }

    /**
     * 从导线选型字符串中提取线径(最后一段数字)
     * 如 "FLRY-B 0.35" -> 0.35, "Dacar 462" -> 462.0
     */
    private double extractWireGauge(String loopWireway) {
        if (loopWireway == null || loopWireway.trim().isEmpty()) {
            return 0;
        }
        String[] split = loopWireway.trim().split("\\s+");
        if (split.length < 2) {
            return 0;
        }
        try {
            return Double.parseDouble(split[split.length - 1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 根据线径总和区间应用系数
     * 0~45A -> range0to45A(默认0.7), 45~90A -> range45to90A(默认0.6), >90A ->
     * rangeAbove90A(默认0.5)
     */
    private double applyWireCoefficient(double sum) {
        double coeff;
        if (sum <= 45) {
            coeff = range0to45A != null ? range0to45A : 0.7;
        } else if (sum <= 90) {
            coeff = range45to90A != null ? range45to90A : 0.6;
        } else {
            coeff = rangeAbove90A != null ? rangeAbove90A : 0.5;
        }
        return sum * coeff;
    }

    /**
     * 从导线选型库中找线径最贴近计算值的导线选型
     * 只在 FLRY-B 开头的导线选型中查找
     */
    private String findClosestWireType(double calculatedValue) {
        Map<String, Map<String, String>> library = ProjectCircuitInfoOutput.elecFixedLocationLibrary;
        if (library == null || library.isEmpty()) {
            return null;
        }
        String bestType = null;
        double bestDiff = Double.MAX_VALUE;
        for (String wireType : library.keySet()) {
            // 只考虑 FLRY-B 开头的导线选型
            if (wireType == null || !wireType.startsWith("FLRY-B")) {
                continue;
            }
            double gauge = extractWireGauge(wireType);
            if (gauge <= 0) {
                continue;
            }
            double diff = Math.abs(gauge - calculatedValue);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestType = wireType;
            }
        }
        return bestType;
    }

    /**
     * 修复 +/- 同名用电器同控制器约束
     * 找到 startApp 名称以 + 或 - 结尾的回路，同基名的必须连接同一个控制器
     * 取这些回路可连接用电器(startConnEndApps)的交集，选一个控制器类型的用电器
     */
    private void fixPlusMinusControllerConstraint(List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions) {
        if (loopInfos == null || loopInfos.isEmpty() || appPositions == null) {
            return;
        }
        // 构建用电器类型映射：键统一为大写，兼容 appPositions 中大小写不规范而回路里名称正确的情况
        Map<String, String> appTypeMap = new HashMap<>();
        for (Map<String, String> app : appPositions) {
            String name = app.get("appName");
            String type = app.get("appType");
            if (name != null && type != null) {
                appTypeMap.put(name.toUpperCase(), type);
            }
        }
        // 按基名分组: baseName -> [(loop, suffix)]
        Map<String, List<Object[]>> plusMinusGroups = new HashMap<>();
        for (Map<String, String> loop : loopInfos) {
            String startApp = loop.get("startApp");
            if (startApp == null || startApp.length() < 2) {
                continue;
            }
            char lastChar = startApp.charAt(startApp.length() - 1);
            if (lastChar == '+' || lastChar == '-') {
                String baseName = startApp.substring(0, startApp.length() - 1);
                plusMinusGroups.computeIfAbsent(baseName, k -> new ArrayList<>())
                        .add(new Object[] { loop, String.valueOf(lastChar) });
            }
        }
        // 对每个 +/- 分组进行处理
        for (Map.Entry<String, List<Object[]>> entry : plusMinusGroups.entrySet()) {
            List<Object[]> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            // 取所有回路 startConnEndApps 的交集
            Set<String> intersection = null;
            for (Object[] item : group) {
                Map<String, String> loop = (Map<String, String>) item[0];
                String connEndApps = loop.get("startConnEndApps");
                if (connEndApps == null || connEndApps.trim().isEmpty()) {
                    intersection = null;
                    break;
                }
                Set<String> connSet = new HashSet<>();
                for (String name : connEndApps.split(",")) {
                    String trimmed = name.trim();
                    if (!trimmed.isEmpty()) {
                        connSet.add(trimmed);
                    }
                }
                if (intersection == null) {
                    intersection = new HashSet<>(connSet);
                } else {
                    intersection.retainAll(connSet);
                }
            }
            if (intersection == null || intersection.isEmpty()) {
                continue;
            }
            // 从交集中筛选控制器类型
            String selectedController = null;
            for (String candidate : intersection) {
                if ("控制器".equals(appTypeMap.get(candidate.toUpperCase()))) {
                    selectedController = candidate;
                    break;
                }
            }
            if (selectedController == null) {
                continue;
            }
            // 将所有同组回路的 endApp 设为同一个控制器
            for (Object[] item : group) {
                Map<String, String> loop = (Map<String, String>) item[0];
                loop.put("endApp", selectedController);
            }
        }
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
        // 修复 +/- 同名用电器同控制器约束
        fixPlusMinusControllerConstraint(loopInfos, appPositions);
        // 导线选型更新（成本计算前）
        updateWireSelectionForScheme(loopInfos, appPositions, currentOptimizeType);
        String result;
        try {
            result = projectCircuitInfoOutput.projectCircuitInfoOutput(
                    objectMapper.writeValueAsString(jsonMapCopy));
        } catch (Exception ex) {
            // 拓扑/数据异常：典型场景是用电器位置没绑 / 回路 id 找不到 / 位置点不在图上
            // System.out.println("[computeFullCost] 调用 projectCircuitInfoOutput 抛异常: " +
            // ex.getClass().getSimpleName()
            // + " msg=" + ex.getMessage());
            return null;
        }
        if (result == null || result.isEmpty()) {
            System.out.println("[computeFullCost] projectCircuitInfoOutput 返回空 (通常是数据缺失或位置未绑定)");
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
        // 返回定长 128 位 MD5 hex（32 字符），替代直接返回完整拼接串：
        // 历史版本每条指纹可达 6~12KB，全局仓库 WareHouse 存几十万条会直接 Java heap OOM。
        // 压缩后同量级条目内存约降 200 倍；MD5 128 位在去重场景碰撞概率可忽略。
        return md5Hex(fingerprint.toString());
    }

    /**
     * 对指纹原文取 128 位 MD5 摘要，返回 32 位小写 hex 字符串（所有 JVM 均内置 MD5）。
     */
    private static String md5Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                int hi = (b >> 4) & 0xF;
                int lo = b & 0xF;
                sb.append((char) (hi < 10 ? '0' + hi : 'a' + hi - 10));
                sb.append((char) (lo < 10 ? '0' + lo : 'a' + lo - 10));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // 理论上不可能发生；兜底返回原文（不影响正确性，仅内存变大）
            return input;
        }
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
        // 修复 +/- 同名用电器同控制器约束
        fixPlusMinusControllerConstraint(loops, apps);
        // 导线选型更新（成本计算前）
        updateWireSelectionForScheme(loops, apps, currentOptimizeType);
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
        // 保留 loopInfos(含已更新的导线选型)用于结果可视化对比
        // map2.put("loopInfos", loops);
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
        System.out.println("开始并行生成 " + populationSize + " 个初代个体 (" + threadPool.getThreadCount() + " 线程) ...");

        for (int t = 0; t < totalTasks; t++) {
            // 队列满时 execute 会自动阻塞，形成自然背压
            threadPool.execute(() -> {
                // 用乐观的 CAS 抢占名额：如果失败说明已满，直接退出
                if (!tryAcquireSlot(currentSize, populationSize))
                    return;
                Random random = new Random();
                for (int retry = 0; retry < maxRetriesPerTask; retry++) {
                    if (currentSize.get() >= populationSize) {
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
                                    + " 个有效个体，耗时 " + elapsed + "ms");
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
//        System.out.println("chu dai zhong qun sheng cheng wan cheng : " + result.size() + " ge ge ti , "
//                + "zong hao shi " + elapsed + "ms , "
//                + (elapsed > 0 ? (result.size() * 1000L / elapsed) : "?") + " ge / miao");
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
            List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, selectedEndApp);
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
                        if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(selectedEndApp)) {
                            appPos.put("unregularPointName", selectedPosition);
                            appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                            break;
                        }
                    }
                }
                String startApp = loop.get("startApp");
                if (startApp != null && !startApp.isEmpty()) {
                    List<String> startPositions = positionsOfIgnoreCase(elecChangeablePosition, startApp);
                    if (startPositions != null && !startPositions.isEmpty()) {
                        String randomStartPos = startPositions.get(random.nextInt(startPositions.size()));
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(startApp)) {
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

                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, selectedEndApp);
                if (positions != null && !positions.isEmpty()) {
                    String selectedPosition = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(selectedEndApp)) {
                            appPos.put("unregularPointName", selectedPosition);
                            appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                            break;
                        }
                    }
                }
                String startApp = loop.get("startApp");
                if (startApp != null && !startApp.isEmpty()) {
                    List<String> startPositions = positionsOfIgnoreCase(elecChangeablePosition, startApp);
                    if (startPositions != null && !startPositions.isEmpty()) {
                        String randomStartPos = startPositions.get(random.nextInt(startPositions.size()));
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(startApp)) {
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
            List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, appName);
            if (positions != null && !positions.isEmpty()) {
                String chosenPos = positions.get(random.nextInt(positions.size()));
                for (Map<String, String> appPos : appPositionsCopy) {
                    if (appPos.get("appName") != null && appPos.get("appName").equalsIgnoreCase(appName)) {
                        appPos.put("unregularPointName", chosenPos);
                        appPos.put("unregularPointId", pointNameId.get(chosenPos));
                        break;
                    }
                }
            }
        }
    }

    /**
     * 连接关系变种：以某父代为基底，修改若干回路起点/终点连接关系后的完整方案(作为交叉基底)。
     */
    private static final class ConnectionVariant {
        final List<Map<String, String>> loopInfos;
        final List<Map<String, String>> appPositions;

        ConnectionVariant(List<Map<String, String>> loopInfos, List<Map<String, String>> appPositions) {
            this.loopInfos = loopInfos;
            this.appPositions = appPositions;
        }
    }

    /**
     * 位置变种：若干用电器 -> 新位置 的编辑集合(与基底无关，可叠加到任意连接变种上)。
     */
    private static final class PositionVariant {
        final Map<String, String> positionEdits;

        PositionVariant(Map<String, String> positionEdits) {
            this.positionEdits = positionEdits;
        }
    }

    /**
     * 按成本优先加权选父代：parents 已按字典成本升序(索引越靠前成本越低)，
     * 权重按指数衰减(Math.pow(0.6, i))，保证最优父代被选中概率最高，同时保留少量多样性。
     */
    private Map<String, Object> pickWeightedParent(List<Map<String, Object>> parents, Random random) {
        int size = parents.size();
        if (size <= 1) {
            return parents.get(0);
        }
        double[] cumulative = new double[size];
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += Math.pow(0.6, i);
            cumulative[i] = sum;
        }
        double t = random.nextDouble() * sum;
        for (int i = 0; i < size; i++) {
            if (t <= cumulative[i]) {
                return parents.get(i);
            }
        }
        return parents.get(0);
    }

    /**
     * 生成连接关系变种(双亲交叉，显式双向)：
     * 1) 可变回路 = 目标回路中起点或终点存在可连接用电器列表(且可选项 > 1)的回路；
     * 2) 每轮抽一对父代：父代A(基底)成本加权优先选，父代B(接线来源)从 top 随机挑且与 A 不同；
     * 3) 每代变种等级 k 从 1..可变回路数 均匀随机取，选中 k 根回路；
     * 4) 显式双向交叉：同一对 (A,B) 同时生成两个变种——
     *    - 方向1：以 A 为基底，把 B 里这 k 根回路的连接(start/end)整组复制到 A 上；
     *    - 方向2：以 B 为基底，把 A 里这 k 根回路的连接(start/end)整组复制到 B 上；
     * 5) 有约束(组团/互斥)回路优先被选中，且选中后整组一起复制，保证组内约束与来源父代一致；
     * 6) 每个变种保存修改后的完整 loopInfos/appPositions，作为交叉基底；
     * 7) 新接入的用电器补位(位置统一：同一用电器只保留一个位置)。
     */
    private List<ConnectionVariant> generateConnectionVariants(
            List<Map<String, Object>> parents,
            int count,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Map<String, Set<String>> loopElecById,
            Map<String, Set<String>> loopElecByIdStart,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Random random) {
        List<ConnectionVariant> variants = new ArrayList<>();
        if (parents == null || parents.isEmpty() || targetLoops == null || targetLoops.isEmpty()) {
            return variants;
        }
        // 有约束回路 id 集合
        Set<String> constrainedLoopIds = new HashSet<>();
        for (List<String> members : togetherGroup.values())
            constrainedLoopIds.addAll(members);
        for (List<String> members : mutualGroup.values())
            constrainedLoopIds.addAll(members);
        // 收集可变回路，并按是否有约束拆分（有约束优先变）
        List<Map<String, String>> constrainedVar = new ArrayList<>();
        List<Map<String, String>> unconstrainedVar = new ArrayList<>();
        for (Map<String, String> loop : targetLoops) {
            String id = loop.get("id");
            Set<String> ends = loopElecById.get(id);
            Set<String> starts = loopElecByIdStart.get(id);
            boolean varEnd = ends != null && ends.size() > 1;
            boolean varStart = starts != null && starts.size() > 1;
            if (varEnd || varStart) {
                if (constrainedLoopIds.contains(id))
                    constrainedVar.add(loop);
                else
                    unconstrainedVar.add(loop);
            }
        }
        int variableCount = constrainedVar.size() + unconstrainedVar.size();
        if (variableCount <= 0) {
            return variants;
        }
        System.out.println("[诊断] 连接变种: count=" + count + ", 可变回路数=" + variableCount);
        // 回路 -> 所属组全体成员：选中任一组成员时整组一起从父代B复制，保证组团/互斥约束与B一致
        Map<String, List<String>> loopGroupMap = new HashMap<>();
        for (Map.Entry<String, List<String>> e : togetherGroup.entrySet()) {
            for (String m : e.getValue())
                loopGroupMap.putIfAbsent(m, e.getValue());
        }
        for (Map.Entry<String, List<String>> e : mutualGroup.entrySet()) {
            for (String m : e.getValue())
                loopGroupMap.putIfAbsent(m, e.getValue());
        }
        // 变种去重：相同的完整方案(loopInfos+appPositions)只保留一个，保证 M 个基底互不相同，交叉产物不重复
        Set<String> seenVariantKeys = new HashSet<>();
        // ===== k=1 强制覆盖阶段：基于最优父代对每个可变回路做单回路翻转(只改 1 根) =====
        // 随机抽 k 时 k=1 这类最有价值的"改1根"小邻域可能整代抽不到，这里预留一部分预算保证其被探索。
        // 预算取连接变种总数的一半(至少 5 个)：可变回路少时全部覆盖，可变回路多时也能保证 k=1 每次出现。
        int k1Budget = Math.min(variableCount, Math.max(5, count / 2));
        if (k1Budget > 0 && parents.size() > 1) {
            // 最优父代(字典成本最低)作为 k=1 变异的基底
            Map<String, Object> bestParent = parents.get(0);
            for (Map<String, Object> p : parents) {
                if (dictCostOf(p) < dictCostOf(bestParent)) {
                    bestParent = p;
                }
            }
            List<Map<String, String>> baseLoops = (List<Map<String, String>>) bestParent.get("loopInfos");
            List<Map<String, String>> baseApps = (List<Map<String, String>>) bestParent.get("appPositions");
            Map<String, Map<String, String>> baseLoopById = new HashMap<>();
            for (Map<String, String> loop : baseLoops) {
                baseLoopById.put(loop.get("id"), loop);
            }
            // 逐根可变回路做 k=1 翻转（有约束回路优先，与随机段一致）
            List<Map<String, String>> allVarLoops = new ArrayList<>(constrainedVar);
            allVarLoops.addAll(unconstrainedVar);
            for (Map<String, String> varLoop : allVarLoops) {
                if (variants.size() >= k1Budget) {
                    break;
                }
                String id = varLoop.get("id");
                Map<String, String> baseLoop = baseLoopById.get(id);
                if (baseLoop == null) {
                    continue;
                }
                // 找一个与该回路连接不同(真正翻转)的父代作 donor
                Map<String, String> donorLoop = null;
                for (int t = 0; t < 20 && donorLoop == null; t++) {
                    Map<String, Object> donor = parents.get(random.nextInt(parents.size()));
                    if (donor == bestParent) {
                        continue;
                    }
                    Map<String, Map<String, String>> tmp = new HashMap<>();
                    for (Map<String, String> loop : (List<Map<String, String>>) donor.get("loopInfos")) {
                        tmp.put(loop.get("id"), loop);
                    }
                    Map<String, String> dl = tmp.get(id);
                    if (dl == null) {
                        continue;
                    }
                    boolean sameStart = dl.get("startApp") == null
                            ? baseLoop.get("startApp") == null
                            : dl.get("startApp").equals(baseLoop.get("startApp"));
                    boolean sameEnd = dl.get("endApp") == null
                            ? baseLoop.get("endApp") == null
                            : dl.get("endApp").equals(baseLoop.get("endApp"));
                    if (sameStart && sameEnd) {
                        continue; // 连接相同则不是真正的翻转
                    }
                    donorLoop = dl;
                }
                if (donorLoop == null) {
                    continue;
                }
                // 以最优父代为基底，翻转这一根回路（方向1），其余回路保持最优父代不变
                List<Map<String, String>> loopsK1 = deepCopyLoopInfos(baseLoops);
                List<Map<String, String>> appsK1 = deepCopyAppPositions(baseApps);
                Map<String, Map<String, String>> copyK1ById = new HashMap<>();
                for (Map<String, String> loop : loopsK1) {
                    copyK1ById.put(loop.get("id"), loop);
                }
                Map<String, String> loopK1 = copyK1ById.get(id);
                String ds = donorLoop.get("startApp");
                String de = donorLoop.get("endApp");
                if (ds != null && !ds.isEmpty()) {
                    loopK1.put("startApp", ds);
                }
                if (de != null && !de.isEmpty()) {
                    loopK1.put("endApp", de);
                }
                syncAppPositionsPreservingExisting(loopsK1, appsK1, elecChangeablePosition, pointNameId, random);
                String variantKey = generateSchemeFingerprint(loopsK1, appsK1);
                if (seenVariantKeys.add(variantKey)) {
                    variants.add(new ConnectionVariant(loopsK1, appsK1));
                }
            }
        }
        int attempts = 0;
        int maxAttempts = Math.max(count * 10, 2000);
        while (variants.size() < count && attempts < maxAttempts) {
            attempts++;
            // 父代A(基底)：成本加权优先，成本越低被选中概率越高
            Map<String, Object> parentA = pickWeightedParent(parents, random);
            // 父代B(接线来源/反向基底)：从 top 随机挑一个与 A 不同的方案
            Map<String, Object> parentB = parents.get(random.nextInt(parents.size()));
            if (parentB == parentA) {
                // A、B 相同则双向交叉无意义，跳过本轮重新抽
                continue;
            }
            List<Map<String, String>> aLoops = (List<Map<String, String>>) parentA.get("loopInfos");
            List<Map<String, String>> bLoops = (List<Map<String, String>>) parentB.get("loopInfos");
            // 只读的接线来源索引：donor 的连接只被读取，不会修改原始父代
            Map<String, Map<String, String>> aLoopById = new HashMap<>();
            for (Map<String, String> loop : aLoops)
                aLoopById.put(loop.get("id"), loop);
            Map<String, Map<String, String>> bLoopById = new HashMap<>();
            for (Map<String, String> loop : bLoops)
                bLoopById.put(loop.get("id"), loop);
            // 变种等级 k：1..可变回路数 均匀随机
            int k = 1 + random.nextInt(variableCount);
            // 有约束回路优先选，选中后整组(所有成员)一起加入，再从无约束回路补齐
            Set<String> chosenIds = new LinkedHashSet<>();
            List<Map<String, String>> cs = new ArrayList<>(constrainedVar);
            Collections.shuffle(cs, random);
            int takeConstrained = Math.min(k, cs.size());
            for (int i = 0; i < takeConstrained; i++) {
                String id = cs.get(i).get("id");
                List<String> group = loopGroupMap.get(id);
                if (group != null)
                    chosenIds.addAll(group);
                else
                    chosenIds.add(id);
            }
            if (chosenIds.size() < k && !unconstrainedVar.isEmpty()) {
                List<Map<String, String>> us = new ArrayList<>(unconstrainedVar);
                Collections.shuffle(us, random);
                for (Map<String, String> loop : us) {
                    if (chosenIds.size() >= k)
                        break;
                    chosenIds.add(loop.get("id"));
                }
            }

            // 方向1：以 A 为基底，把 B 里这 k 根回路的连接(start/end)整组复制到 A 上
            // （B 的连接本就是合法方案，约束天然成立）
            List<Map<String, String>> loopsA = deepCopyLoopInfos(aLoops);
            List<Map<String, String>> appsA = deepCopyAppPositions(
                    (List<Map<String, String>>) parentA.get("appPositions"));
            // 可写索引建立在副本上，donor 索引(bLoopById)只读，避免污染原始父代
            Map<String, Map<String, String>> copyALoopById = new HashMap<>();
            for (Map<String, String> loop : loopsA)
                copyALoopById.put(loop.get("id"), loop);
            for (String id : chosenIds) {
                Map<String, String> donorLoop = bLoopById.get(id);
                if (donorLoop == null)
                    continue;
                String ds = donorLoop.get("startApp");
                String de = donorLoop.get("endApp");
                Map<String, String> loop = copyALoopById.get(id);
                if (ds != null && !ds.isEmpty())
                    loop.put("startApp", ds);
                if (de != null && !de.isEmpty())
                    loop.put("endApp", de);
            }
            // 新接入的用电器补位（位置统一）
            syncAppPositionsPreservingExisting(loopsA, appsA, elecChangeablePosition, pointNameId, random);
            String variantKeyA = generateSchemeFingerprint(loopsA, appsA);
            if (seenVariantKeys.add(variantKeyA)) {
                variants.add(new ConnectionVariant(loopsA, appsA));
            }

            // 方向2：以 B 为基底，把 A 里这 k 根回路的连接(start/end)整组复制到 B 上
            List<Map<String, String>> loopsB = deepCopyLoopInfos(bLoops);
            List<Map<String, String>> appsB = deepCopyAppPositions(
                    (List<Map<String, String>>) parentB.get("appPositions"));
            Map<String, Map<String, String>> copyBLoopById = new HashMap<>();
            for (Map<String, String> loop : loopsB)
                copyBLoopById.put(loop.get("id"), loop);
            for (String id : chosenIds) {
                Map<String, String> donorLoop = aLoopById.get(id);
                if (donorLoop == null)
                    continue;
                String ds = donorLoop.get("startApp");
                String de = donorLoop.get("endApp");
                Map<String, String> loop = copyBLoopById.get(id);
                if (ds != null && !ds.isEmpty())
                    loop.put("startApp", ds);
                if (de != null && !de.isEmpty())
                    loop.put("endApp", de);
            }
            syncAppPositionsPreservingExisting(loopsB, appsB, elecChangeablePosition, pointNameId, random);
            String variantKeyB = generateSchemeFingerprint(loopsB, appsB);
            if (seenVariantKeys.add(variantKeyB)) {
                variants.add(new ConnectionVariant(loopsB, appsB));
            }
        }
        return variants;
    }

    /**
     * 生成位置变种：
     * 1) 可变用电器 = 目标回路起点/终点中出现、且位置可变的用电器(elecChangeablePosition 含该用电器)；
     * 2) 每代变种等级 k 从 1..可变用电器数 均匀随机取，变 k 个用电器位置；
     * 3) 位置编辑以 appName -> positionName 存储，与基底无关，可叠加到任意连接变种上。
     */
    private List<PositionVariant> generatePositionVariants(
            List<Map<String, Object>> parents,
            int count,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Random random) {
        List<PositionVariant> variants = new ArrayList<>();
        if (targetLoops == null || targetLoops.isEmpty() || elecChangeablePosition == null) {
            return variants;
        }
        List<String> variableApps = new ArrayList<>();
        Set<String> seenApps = new HashSet<>();
        for (Map<String, String> loop : targetLoops) {
            String start = loop.get("startApp");
            if (start != null && !start.isEmpty() && containsKeyIgnoreCase(elecChangeablePosition, start)
                    && seenApps.add(start)) {
                variableApps.add(start);
            }
            String end = loop.get("endApp");
            if (end != null && !end.isEmpty() && containsKeyIgnoreCase(elecChangeablePosition, end)
                    && seenApps.add(end)) {
                variableApps.add(end);
            }
        }
        if (variableApps.isEmpty()) {
            return variants;
        }
        System.out.println("[诊断] 位置变种: count=" + count + ", 可变用电器数=" + variableApps.size());
        // 位置编辑去重：同一组 用电器->新位置 只保留一个，避免交叉产物大量重复
        Set<String> seenVariantKeys = new HashSet<>();
        // ===== k=1 强制覆盖阶段：基于最优父代对每个可变用电器做单位置翻转(只改 1 个用电器位置) =====
        // 与连接变种对应：随机抽 k 时 k=1 这类"只改1个位置"的小邻域可能整代抽不到，
        // 预留一半预算保证其被探索。新位置基于最优父代当前位置选一个不同值，保证是真正翻转。
        int k1Budget = Math.min(variableApps.size(), Math.max(5, count / 2));
        if (k1Budget > 0 && parents != null && !parents.isEmpty()) {
            Map<String, Object> bestParent = parents.get(0);
            for (Map<String, Object> p : parents) {
                if (dictCostOf(p) < dictCostOf(bestParent)) {
                    bestParent = p;
                }
            }
            // 最优父代中各用电器当前位置，作为"翻转"的参照
            Map<String, String> currentPosByName = new HashMap<>();
            for (Map<String, String> ap : (List<Map<String, String>>) bestParent.get("appPositions")) {
                if (ap.get("appName") == null)
                    continue;
                String pos = ap.get("unregularPointName");
                if (pos == null || pos.isEmpty())
                    pos = ap.get("regularPointName");
                currentPosByName.put(ap.get("appName").toUpperCase(), pos);
            }
            for (String app : variableApps) {
                if (variants.size() >= k1Budget)
                    break;
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, app);
                if (positions == null || positions.isEmpty())
                    continue;
                String currentPos = currentPosByName.get(app.toUpperCase());
                // 找一个与当前位置不同的新位置(真正翻转)
                String newPos = null;
                for (int t = 0; t < 20 && newPos == null; t++) {
                    String cand = positions.get(random.nextInt(positions.size()));
                    if (cand.equals(currentPos))
                        continue;
                    newPos = cand;
                }
                if (newPos == null)
                    continue;
                Map<String, String> edits = new LinkedHashMap<>();
                edits.put(app, newPos);
                List<String> sortedApps = new ArrayList<>(edits.keySet());
                Collections.sort(sortedApps);
                StringBuilder keyBuilder = new StringBuilder();
                for (String a : sortedApps) {
                    keyBuilder.append(a).append('=').append(edits.get(a)).append(';');
                }
                if (seenVariantKeys.add(keyBuilder.toString())) {
                    variants.add(new PositionVariant(edits));
                }
            }
        }
        int attempts = 0;
        int maxAttempts = Math.max(count * 10, 1000);
        while (variants.size() < count && attempts < maxAttempts) {
            attempts++;
            int k = 1 + random.nextInt(variableApps.size());
            List<String> pool = new ArrayList<>(variableApps);
            Collections.shuffle(pool, random);
            Map<String, String> edits = new LinkedHashMap<>();
            for (int idx = 0; idx < Math.min(k, pool.size()); idx++) {
                String app = pool.get(idx);
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, app);
                if (positions == null || positions.isEmpty())
                    continue;
                edits.put(app, positions.get(random.nextInt(positions.size())));
            }
            if (edits.isEmpty()) {
                continue;
            }
            List<String> sortedApps = new ArrayList<>(edits.keySet());
            Collections.sort(sortedApps);
            StringBuilder keyBuilder = new StringBuilder();
            for (String app : sortedApps) {
                keyBuilder.append(app).append('=').append(edits.get(app)).append(';');
            }
            if (!seenVariantKeys.add(keyBuilder.toString())) {
                continue;
            }
            variants.add(new PositionVariant(edits));
        }
        return variants;
    }

    /**
     * 单个产物后处理失败环节（诊断用）
     */
    private enum BuildFailReason { OK, CONSTRAINT, RESOURCE, DUPLICATE, ZERO_COST }

    /**
     * 后处理结果：scheme 非空表示通过，为空时 reason 标记失败环节
     */
    private static final class BuildResult {
        final Map<String, Object> scheme;
        final BuildFailReason reason;

        BuildResult(Map<String, Object> scheme, BuildFailReason reason) {
            this.scheme = scheme;
            this.reason = reason;
        }
    }

    /**
     * 单个产物完整后处理（批A纯连接/批B纯位置/批C交叉三批共用）：
     * 约束校验 -> 位置统一 -> 资源检查 -> 仓库去重 -> 导线选型更新 -> 字典成本。
     * 任一环节不通过返回 BuildResult(null, 失败环节)，通过则返回完整方案。
     */
    private BuildResult validateAndBuildScheme(
            List<Map<String, String>> loopInfos,
            List<Map<String, String>> appPositions,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Map<String, AppResourceLimit> resourceNum,
            Random random) {
        // 约束校验（组团 + 互斥）
        if (!enforceAllConstraints(loopInfos, appPositions, togetherGroup, mutualGroup,
                loopElecById, elecChangeablePosition, pointNameId, random)) {
            return new BuildResult(null, BuildFailReason.CONSTRAINT);
        }
        // 位置统一：确保每个用电器一个位置（缺失位置随机补）
        syncAppPositionsPreservingExisting(loopInfos, appPositions, elecChangeablePosition, pointNameId, random);
        // 位置可解析性校验：所有回路起点/终点用电器都必须在 appPositions 中解析出非空位置点。
        // 交叉复制连接或位置变种可能让某个用电器没有任何位置(或位置为空)，此时字典成本会静默跳过该回路，
        // 方案看着很便宜，但整车精确计算时 findNode 返回 null -> findTwoPointInfo 返回 null
        // -> "twoPointInfo is null" NPE。这里直接拒绝，保证仓库中只保留可精确还原的方案。
        if (!allLoopPositionsResolvable(loopInfos, appPositions)) {
            return new BuildResult(null, BuildFailReason.CONSTRAINT);
        }
        // 资源检查
        if (!elecResourceCheck(loopInfos, resourceNum)) {
            return new BuildResult(null, BuildFailReason.RESOURCE);
        }
        // 去重
        String fingerprint = generateSchemeFingerprint(loopInfos, appPositions);
        if (!WareHouse.add(fingerprint)) {
            return new BuildResult(null, BuildFailReason.DUPLICATE);
        }
        // 导线选型更新（成本计算前）
        updateWireSelectionForScheme(loopInfos, appPositions, currentOptimizeType);
        // 字典成本
        double cost = calcSchemeDictCost(loopInfos, appPositions);
        if (cost <= 0) {
            WareHouse.remove(fingerprint);
            return new BuildResult(null, BuildFailReason.ZERO_COST);
        }
        Map<String, Double> projectCost = new HashMap<>();
        projectCost.put("总成本", cost);
        projectCost.put("总重量", 0.0);
        projectCost.put("总长度", 0.0);
        Map<String, Object> map = new HashMap<>();
        map.put("成本", projectCost);
        map.put("loopInfos", loopInfos);
        map.put("appPositions", appPositions);
        return new BuildResult(map, BuildFailReason.OK);
    }

    /**
     * 交叉裂变单个产物：以连接变种 i 为基底(含连接编辑)，叠加位置变种 j 的位置编辑，
     * 再做完整后处理，任一环节不通过返回 null。
     */
    private Map<String, Object> buildCrossoverScheme(
            ConnectionVariant cv,
            PositionVariant pv,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Map<String, AppResourceLimit> resourceNum,
            Random random) {
        List<Map<String, String>> loopInfos = deepCopyLoopInfos(cv.loopInfos);
        List<Map<String, String>> appPositions = deepCopyAppPositions(cv.appPositions);
        // 叠加位置变种编辑
        for (Map.Entry<String, String> e : pv.positionEdits.entrySet()) {
            String appName = e.getKey();
            String position = e.getValue();
            for (Map<String, String> ap : appPositions) {
                if (ap.get("appName") != null && ap.get("appName").equalsIgnoreCase(appName)) {
                    ap.put("unregularPointName", position);
                    ap.put("unregularPointId", pointNameId.get(position));
                    break;
                }
            }
        }
        return validateAndBuildScheme(loopInfos, appPositions, togetherGroup, mutualGroup,
                loopElecById, elecChangeablePosition, pointNameId, resourceNum, random).scheme;
    }

    /**
     * 交叉裂变：连接关系变种 × 位置变种 相乘做交叉。
     * 第一遍系统遍历全部 M×N 组合；若有效方案不足 targetCount，再随机补几轮(设上限避免死循环)。
     */
    private List<Map<String, Object>> crossoverFission(
            List<ConnectionVariant> connVariants,
            List<PositionVariant> posVariants,
            int targetCount,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            Map<String, Set<String>> loopElecById,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Map<String, AppResourceLimit> resourceNum,
            Random random,
            int[] validCounter) {
        if (connVariants == null || connVariants.isEmpty()
                || posVariants == null || posVariants.isEmpty()) {
            return new ArrayList<>();
        }
        int connN = connVariants.size();
        int posN = posVariants.size();
        int totalPairs = connN * posN;
        // 只保留成本最优的 maxKeep 个交叉产物(即 TopNumber=20)，不再把全部 10000 个完整方案
        // (每个含全部回路)同时驻留堆内存——这是之前 1 代就 OOM 的根因。
        // 调用方只取 top TopNumber 作父代，保留更多没有意义。
        int maxKeep = Math.min(targetCount, TopNumber);
        // 小顶堆按字典成本降序排列：堆顶永远是当前保留里成本最差(最高)的方案
        PriorityQueue<Map<String, Object>> topSchemes = new PriorityQueue<>(
                Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf).reversed());
        // 第一遍：系统遍历全部 M×N 组合，只更新 top maxKeep
        for (int i = 0; i < connN; i++) {
            ConnectionVariant cv = connVariants.get(i);
            for (int j = 0; j < posN; j++) {
                Map<String, Object> scheme = buildCrossoverScheme(
                        cv, posVariants.get(j), togetherGroup, mutualGroup, loopElecById,
                        elecChangeablePosition, pointNameId, resourceNum, random);
                if (scheme == null) {
                    continue;
                }
                // 统计全部有效交叉产物数(用于日志/反映真实探索量)，不随内存保留上限而减少
                validCounter[0]++;
                if (topSchemes.size() < maxKeep) {
                    topSchemes.add(scheme);
                } else if (dictCostOf(scheme) < dictCostOf(topSchemes.peek())) {
                    topSchemes.poll();
                    topSchemes.add(scheme);
                }
            }
        }
        // 仍不足 maxKeep：随机补几轮
        int extraCap = Math.max(totalPairs, targetCount * 2);
        for (int extra = 0; topSchemes.size() < maxKeep && extra < extraCap; extra++) {
            Map<String, Object> scheme = buildCrossoverScheme(
                    connVariants.get(random.nextInt(connN)), posVariants.get(random.nextInt(posN)),
                    togetherGroup, mutualGroup, loopElecById, elecChangeablePosition, pointNameId,
                    resourceNum, random);
            if (scheme != null) {
                validCounter[0]++;
                topSchemes.add(scheme);
            }
        }
        // 返回按成本升序排列(成本低在前)，方便调用方直接取 top 作父代
        List<Map<String, Object>> result = new ArrayList<>(topSchemes);
        result.sort(Comparator.comparingDouble(PowerDistributionDriveOptimization::dictCostOf));
        return result;
    }

    /**
     * 用字典快速计算整个方案的字典成本 = 所有回路 calcLoopCost(起点位置, 终点位置, 导线选型) 之和。
     */
    private double calcSchemeDictCost(List<Map<String, String>> loopInfos, List<Map<String, String>> appPositions) {
        if (loopInfos == null || loopInfos.isEmpty() || appPositions == null) {
            return 0;
        }
        Map<String, String> posMap = new HashMap<>();
        for (Map<String, String> ap : appPositions) {
            String pos = ap.get("unregularPointName");
            if (pos == null || pos.isEmpty()) {
                pos = ap.get("regularPointName");
            }
            if (pos != null && !pos.isEmpty() && ap.get("appName") != null) {
                posMap.put(ap.get("appName").toUpperCase(), pos);
            }
        }
        double total = 0;
        for (Map<String, String> loop : loopInfos) {
            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            String wireType = loop.get("loopWireway");
            if (startApp == null || endApp == null || wireType == null || wireType.trim().isEmpty()) {
                continue;
            }
            String posA = posMap.get(startApp.toUpperCase());
            String posB = posMap.get(endApp.toUpperCase());
            if (posA == null || posB == null) {
                continue;
            }
            total += calcLoopCost(posA, posB, wireType);
        }
        return total;
    }

    /**
     * 复制方案并按字典成本重算 成本 字段（总重量/总长度 暂置 0，遗传内用字典成本排序）。
     */
    private Map<String, Object> schemeWithDictCost(Map<String, Object> scheme) {
        Map<String, Object> copy = new HashMap<>(scheme);
        List<Map<String, String>> loops = (List<Map<String, String>>) scheme.get("loopInfos");
        List<Map<String, String>> apps = (List<Map<String, String>>) scheme.get("appPositions");
        Map<String, Double> projectCost = new HashMap<>();
        projectCost.put("总成本", calcSchemeDictCost(loops, apps));
        projectCost.put("总重量", 0.0);
        projectCost.put("总长度", 0.0);
        copy.put("成本", projectCost);
        return copy;
    }

    /**
     * 取方案字典成本用于排序；缺失成本返回 Double.MAX_VALUE 保证排最后。
     */
    private static double dictCostOf(Map<String, Object> scheme) {
        Object costObj = scheme == null ? null : scheme.get("成本");
        if (costObj instanceof Map) {
            Object tc = ((Map<String, Object>) costObj).get("总成本");
            if (tc instanceof Number) {
                return ((Number) tc).doubleValue();
            }
        }
        return Double.MAX_VALUE;
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
                    List<String> changeablePositions = positionsOfIgnoreCase(elecChangeablePosition, endApp);
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
                + " (连接关系)，位置变化: " + (elecChangeablePosition != null ? elecChangeablePosition.size() : 0) + " 个用电器");
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
        if (enumeratedSchemes.size() >= caseNumber) {
            System.out.println("枚举方案数已达到限制 (" + caseNumber + ")，提前退出");
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
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, startApp);
                if (positions != null && !positions.isEmpty()) {
                    appPositionDomains.put(startApp, positions);
                }
            }
            if (endApp != null && !endApp.isEmpty() && !appPositionDomains.containsKey(endApp)) {
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, endApp);
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
                    System.out.println("已枚举 " + enumeratedSchemes.size() + " 个方案 ...");
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

        long totalCombinations = total[0];
        System.out.println("可行方案总数 (含约束): " + totalCombinations
                + (overflow[0] ? " (已超过 caseNumber=" + caseNumber : ""));
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
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, selectedStartApp);
                if (positions != null && !positions.isEmpty())
                    appPositionDomains.put(selectedStartApp, positions);
            }
            if (selectedEndApp != null && !selectedEndApp.isEmpty()
                    && !appPositionDomains.containsKey(selectedEndApp)) {
                List<String> positions = positionsOfIgnoreCase(elecChangeablePosition, selectedEndApp);
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

    /**
     * 两个位置点之间一条回路的路径数据（长度/打断/干湿，与导线选型无关）。
     * 仅作为字典构建过程与懒计算兜底的内部数据载体，字典中不存储该对象，只存储最终成本。
     */
    public static class TwoPointLoopCost {
        /** 回路长度(mm)：最短路径经过分支长度之和 + BranchEndFallback(200mm)，两点相同则默认 200mm */
        public double lengthMm;
        /** 路径上打断分支数(topologyStatusCode == S) */
        public int breakCount;
        /** 路径上湿区分支数(分支两端都为湿区 W) */
        public int inlineWetCount;
        /** 回路两端位置点中湿区(W)的数量(0/1/2) */
        public int endpointWetCount;
        /** 打断分支id */
        public List<String> breakBranchIds = new ArrayList<>();
        /** 途径全部分支id */
        public List<String> branchIds = new ArrayList<>();
        /** 途径点名称 */
        public List<String> pathPoints = new ArrayList<>();
    }

    /**
     * 根据导线选型计算两点回路的整车口径总成本：
     * 总成本 = 长度(m)*导线单位商务价 + (打断次数+1)*导线打断成本(含回路本身一对端子)
     *        + (湿区分支数*2 + 两端湿区数)*(连接器塑壳补偿+防水塞补偿)
     * 与 ProjectCircuitInfoOutput.findTwoPointInfo 的回路总成本口径一致。
     */
    public double calcTwoPointLoopCost(TwoPointLoopCost info, String wireType) {
        if (info == null || wireType == null || ProjectCircuitInfoOutput.elecFixedLocationLibrary == null) {
            return 0;
        }
        Map<String, String> mat = ProjectCircuitInfoOutput.elecFixedLocationLibrary.get(wireType);
        if (mat == null) {
            return 0;
        }
        double lengthM = info.lengthMm / 1000.0;
        double unitPrice = Double.parseDouble(mat.get("导线单位商务价（元/米）"));
        double breakPrice = Double.parseDouble(mat.get("导线打断成本（元/次）"));
        double connectComp = Double.parseDouble(mat.get("湿区成本补偿——连接器塑壳（元/端）"));
        double defenseComp = Double.parseDouble(mat.get("湿区成本补偿——防水赛（元/个）"));
        double wireCost = lengthM * unitPrice;
        double breakCost = (info.breakCount + 1) * breakPrice;
        double wetUnit = info.inlineWetCount * 2 + info.endpointWetCount;
        double wetCost = wetUnit * (connectComp + defenseComp);
        return wireCost + breakCost + wetCost;
    }

    /**
     * 位置点对的字典 key：按字典序排序后拼接，保证 a->b 与 b->a 相同。
     */
    private static String pairKey(String a, String b) {
        int cmp = a.compareTo(b);
        return cmp <= 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * 从字典中取两个位置点之间、指定导线选型的回路成本(元)(a->b 与 b->a 相同)。
     * 命中预计算字典则 O(1) 返回；未命中(该导线选型不在 base 相关选型中，如导线选型更新产生的新选型)
     * 则用缓存的路径数据即时计算一次。
     */
    public double calcLoopCost(String posA, String posB, String wireType) {
        if (posA == null || posB == null || wireType == null || wireType.trim().isEmpty()) {
            return 0;
        }
        String key = pairKey(posA, posB);
        Map<String, Double> wireMap = loopCostDictionary.get(wireType);
        if (wireMap != null) {
            Double cost = wireMap.get(key);
            if (cost != null) {
                return cost;
            }
        }
        // 未预计算的导线选型：基于缓存的路径数据即时计算
        if (dictAllPoint == null || dictAdj == null) {
            return 0;
        }
        TwoPointLoopCost info = computeTwoPointLoopCost(
                posA, posB, dictAdj, dictAllPoint, dictEdgeByPair, dictPointWet, dictShortestPathSearch);
        return calcTwoPointLoopCost(info, wireType);
    }

    /**
     * 构建两两位置点回路成本字典：
     * 1) 收集 base 方案涉及的所有导线选型(loopInfos.loopWireway 去重)，这些是遗传裂变中回路的常见选型；
     * 2) 对所有位置点两两配对(含自身，自身默认回路长度 200mm)，基于 base 构建的邻接矩阵
     *    (已排除 B 打断分支) 做一次最短路径搜索，得到路径数据(与导线选型无关，只需一次)；
     * 3) 对每个导线选型，从价格库取价格，把路径数据换算成该导线选型的回路成本，存入对应导线选型的字典层。
     * 返回结构：导线选型 -> (位置A|位置B(排序) -> 成本)。
     */
    private Map<String, Map<String, Double>> buildLoopCostDictionary(
            GenerateTopoMatrix adjacencyMatrixGraph,
            List<Map<String, Object>> edges,
            List<Map<String, Object>> points,
            List<String> allPoint,
            List<Map<String, String>> loopInfos) {
        Map<String, Map<String, Double>> dict = new HashMap<>();
        if (adjacencyMatrixGraph == null || allPoint == null || allPoint.isEmpty()
                || ProjectCircuitInfoOutput.elecFixedLocationLibrary == null) {
            return dict;
        }
        // 1) 收集 base 方案涉及的所有导线选型
        LinkedHashSet<String> wireTypes = new LinkedHashSet<>();
        if (loopInfos != null) {
            for (Map<String, String> loop : loopInfos) {
                String wire = loop == null ? null : loop.get("loopWireway");
                if (wire != null && !wire.trim().isEmpty()) {
                    wireTypes.add(wire.trim());
                }
            }
        }
        if (wireTypes.isEmpty()) {
            return dict;
        }
        // 位置名 -> 端点干湿(W/D)
        Map<String, String> pointWet = new HashMap<>();
        if (points != null) {
            for (Map<String, Object> p : points) {
                Object name = p.get("pointName");
                Object wet = p.get("waterParam");
                if (name != null) {
                    pointWet.put(name.toString(), wet == null ? "D" : wet.toString());
                }
            }
        }
        // 分支索引：起点|终点(无向) -> 分支列表，用于按路径还原途经分支
        Map<String, List<Map<String, Object>>> edgeByPair = new HashMap<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                Object s = e.get("startPointName");
                Object t = e.get("endPointName");
                if (s != null && t != null) {
                    edgeByPair.computeIfAbsent(pairKey(s.toString(), t.toString()), k -> new ArrayList<>()).add(e);
                }
            }
        }
        List<List<Integer>> adj = adjacencyMatrixGraph.getAdj();
        FindShortestPath shortestPathSearch = new FindShortestPath();
        // 缓存懒计算兜底依赖(字典未覆盖的导线选型即时计算用)
        this.dictAdj = adj;
        this.dictAllPoint = allPoint;
        this.dictEdgeByPair = edgeByPair;
        this.dictPointWet = pointWet;
        this.dictShortestPathSearch = shortestPathSearch;
        // 2) 先对所有位置点对做一次最短路径，得到路径数据(与导线选型无关)
        Map<String, TwoPointLoopCost> pairPathInfo = new HashMap<>();
        for (int i = 0; i < allPoint.size(); i++) {
            for (int j = i; j < allPoint.size(); j++) {
                String a = allPoint.get(i);
                String b = allPoint.get(j);
                TwoPointLoopCost info = computeTwoPointLoopCost(
                        a, b, adj, allPoint, edgeByPair, pointWet, shortestPathSearch);
                if (info != null) {
                    pairPathInfo.put(pairKey(a, b), info);
                }
            }
        }
        // 3) 对每个 base 导线选型换算成本，存入对应字典层
        for (String wireType : wireTypes) {
            if (!ProjectCircuitInfoOutput.elecFixedLocationLibrary.containsKey(wireType)) {
                continue;
            }
            Map<String, Double> costMap = new HashMap<>();
            for (Map.Entry<String, TwoPointLoopCost> entry : pairPathInfo.entrySet()) {
                costMap.put(entry.getKey(), calcTwoPointLoopCost(entry.getValue(), wireType));
            }
            dict.put(wireType, costMap);
        }
        return dict;
    }

    /**
     * 计算一对位置点的回路成本信息(不含导线选型)。两个位置点相同则默认回路长度 200mm。
     */
    private TwoPointLoopCost computeTwoPointLoopCost(
            String a, String b,
            List<List<Integer>> adj,
            List<String> allPoint,
            Map<String, List<Map<String, Object>>> edgeByPair,
            Map<String, String> pointWet,
            FindShortestPath shortestPathSearch) {
        TwoPointLoopCost info = new TwoPointLoopCost();
        info.pathPoints.add(a);
        if (a.equals(b)) {
            // 两个位置点相同：默认回路长度 200mm，无途经分支，无打断
            info.lengthMm = ProjectCircuitInfoOutput.BranchEndFallback;
            info.breakCount = 0;
            info.inlineWetCount = 0;
            info.endpointWetCount = isWet(a, pointWet) ? 2 : 0;
            return info;
        }
        int startIdx = allPoint.indexOf(a);
        int endIdx = allPoint.indexOf(b);
        if (startIdx == -1 || endIdx == -1) {
            return null;
        }
        List<Integer> shortestPath = shortestPathSearch.findShortestPathBetweenTwoPoint(adj, startIdx, endIdx);
        if (shortestPath == null || shortestPath.size() < 2) {
            return null;
        }
        // 路径点名称
        List<String> pathNames = new ArrayList<>();
        for (Integer idx : shortestPath) {
            pathNames.add(allPoint.get(idx));
        }
        info.pathPoints = pathNames;
        // 还原途经分支并累计长度/打断/湿区
        Set<String> visitedEdgeId = new HashSet<>();
        double totalLen = 0.0;
        for (int k = 0; k < pathNames.size() - 1; k++) {
            List<Map<String, Object>> pairEdges = edgeByPair.get(pairKey(pathNames.get(k), pathNames.get(k + 1)));
            if (pairEdges == null) {
                continue;
            }
            for (Map<String, Object> edge : pairEdges) {
                Object idObj = edge.get("id");
                String id = idObj == null ? null : idObj.toString();
                if (id != null && !visitedEdgeId.add(id)) {
                    continue;
                }
                info.branchIds.add(id);
                totalLen += branchLength(edge);
                // 打断分支(S)
                if ("S".equals(String.valueOf(edge.get("topologyStatusCode")))) {
                    info.breakCount++;
                    info.breakBranchIds.add(id);
                }
                // 湿区分支：分支两端都为湿区 W
                if (isWet(String.valueOf(edge.get("startPointName")), pointWet)
                        && isWet(String.valueOf(edge.get("endPointName")), pointWet)) {
                    info.inlineWetCount++;
                }
            }
        }
        info.lengthMm = totalLen + ProjectCircuitInfoOutput.BranchEndFallback;
        // 两端湿区数
        info.endpointWetCount = (isWet(a, pointWet) ? 1 : 0) + (isWet(b, pointWet) ? 1 : 0);
        return info;
    }

    /**
     * 分支长度(mm)：用户确认 length -> 参考 referenceLength -> 默认 BranchEndFallback
     */
    private double branchLength(Map<String, Object> edge) {
        Object lenObj = edge.get("length");
        if (lenObj != null && !lenObj.toString().trim().isEmpty()) {
            try {
                return Double.parseDouble(lenObj.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        Object refObj = edge.get("referenceLength");
        if (refObj != null && !refObj.toString().trim().isEmpty()) {
            try {
                return Double.parseDouble(refObj.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return ProjectCircuitInfoOutput.BranchEndFallback;
    }

    /**
     * 判断位置点是否为湿区(W)
     */
    private boolean isWet(String pointName, Map<String, String> pointWet) {
        if (pointName == null) {
            return false;
        }
        return "W".equalsIgnoreCase(pointWet.getOrDefault(pointName, "D"));
    }
}