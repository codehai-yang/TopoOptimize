package HarnessPackOpti.Optimize.topo;

import static HarnessPackOpti.utils.GINEInferenceEngine.objectMapper;

import java.io.File;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.collections4.map.LinkedMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.FindShortestPath;
import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.Algorithm.GenerateTopoMatrixConnector;
import HarnessPackOpti.CircuitInfoCalculate.CalculateCircuitInfo;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import HarnessPackOpti.utils.GINEInferenceEngine;
import HarnessPackOpti.utils.Normalize;
import HarnessPackOpti.utils.ThreadPool;

/**
 * 新的topo优化遗传算法
 */
public class NewHarnessBranchTopoOptimize {
    // 初代样本最低生成数量（提高多样性，避免遗传起点过于集中）
    public static Integer LessRandomSamleNumber = 20;
    // 迭代最少样本数量（提高每代候选池规模，保证进化方向充分探索）
    public static Integer HybridizationLessRandomSamleNumber = 20;
    // top几的数量规定
    public static final Integer TopNumber = 5;
    // 绕线优化:分支累计绕线成本贡献阈值,超过则 B 改 C
    public static final Double WindingCostThreshold = 10.0;
    // 每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();
    // 最优样本重复次数
    public static Integer BestRepetitionNumber = 0;
    // 迭代重复的次数限值
    public static Integer IterationRestrictNumber = 3;
    // 定义一个仓库
    public static List<List<String>> WareHouse = new CopyOnWriteArrayList<>();
    // 仓库的 key 索引：完整状态列表拼接的字符串，用于 O(1) 去重
    public static final Set<String> WAREHOUSE_KEYS = ConcurrentHashMap.newKeySet();
    // 每次迭代得到的top20
    public static List<Map<String, Object>> TopDetail = new ArrayList<>();
    // 找存活率每轮生成的样本数
    public static Integer MaxSamplePerRound = 1000;
    // 决定走枚举还是随机的阈值
    public static Integer MaxEnumerateCombinations = 1000;
    // 自动补全得次数
    public static Integer AutoCompleteNumber = 2000;
    // 分支打断代价降序排序时使用的权重衰减系数，越低打断越激进，高打断代价的分支也会又概率打断
    public static Double WeightFactor = 0.7;
    // 决定最高打断概率
    public static Double MaxProbability = 0.9;
    // 存活率阈值：低于此值的 k 视为无效（用于过滤 maxValidK）
    public static Double MinSurvivalRate = 0.30;
    // 线程池
    public static ThreadPool threadPool = new ThreadPool(11, 50);
    // 全局种子计数器，用于生成不碰撞的Random种子
    private static final AtomicLong seedCounter = new AtomicLong(System.nanoTime());

    // 定义一个仓库
    public static List<List<String>> WareHouseTop = new ArrayList<>();
    // 每次迭代得到的top10
    public static List<Map<String, Object>> TopCostDetail = new ArrayList<>();

    // 当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    public NewHarnessBranchTopoOptimize() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance(); // 使用Store的单例实例
    }

    public static void main(String[] args) throws Exception {
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\BS4EM项目json优化设置.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));// 将文件中内容转为字符串
        NewHarnessBranchTopoOptimize newHarnessBranchTopoOptimize = new NewHarnessBranchTopoOptimize();
        long startTime = System.currentTimeMillis();
        String topoOptimize = newHarnessBranchTopoOptimize.topoOptimize(jsonContent);
        System.out.println("算法总耗时：" + (System.currentTimeMillis() - startTime));
        File outputFile = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\测试新遗传算法2.txt");
        Files.write(outputFile.toPath(), topoOptimize.getBytes());
        System.out.println("JSON已成功输出到: " + outputFile.getAbsolutePath());
    }

    public String topoOptimize(String jsonContent) throws Exception {
        // 每次优化前清理仓库，避免跨case累积
        WareHouse.clear();
        WAREHOUSE_KEYS.clear();
        WareHouseTop.clear();
        TopCostDetail.clear();
        TopDetail.clear();
        BestCost.clear();
        BestRepetitionNumber = 0;

        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, String>> points = (List<Map<String, String>>) jsonMap.get("points");
        CaseId = caseInfo.get("id").toString();
        // optimizeRecordId = optimizeRecord.get("id").toString();
        optimizeRecordId = java.util.UUID.randomUUID().toString();
        optimizeStopStatusStore.setKey(optimizeRecordId);

        // 整车信息计算
        String initializeCaseResult = projectCircuitInfoOutput.projectCircuitInfoOutput(jsonContent);
        Map<String, Object> initializeCaseResultMap = jsonToMap.TransJsonToMap(initializeCaseResult);
        initializeCaseResultMap.put("topoId", topoInfoMap.get("id").toString());
        initializeCaseResultMap.put("caseId", caseInfo.get("id").toString());
        List<Map<String, String>> topoOptimizeResult = new ArrayList<>();
        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        for (Map<String, Object> map : edges) {
            Map<String, String> result = new HashMap<>();
            result.put("edgeId", map.get("id").toString());
            result.put("statue", map.get("topologyStatusCode").toString());
            topoOptimizeResult.add(result);
            strPointName.add(map.get("startPointName").toString());
            endPointName.add(map.get("endPointName").toString());
        }
        initializeCaseResultMap.put("topoOptimizeResult", topoOptimizeResult);
        initializeCaseResultMap.put("initializationScheme", true);
        // 分支全部打通情况下的邻接矩阵和邻接列表

        // 图全通的情况下的邻接列表
        GenerateTopoMatrixConnector adjacencyMatrixGraphConnector = new GenerateTopoMatrixConnector(strPointName,
                endPointName);
        adjacencyMatrixGraphConnector.adjacencyMatrix();
        adjacencyMatrixGraphConnector.addEdge();
        adjacencyMatrixGraphConnector.getAdj();

        // 用电器对应位置点
        Map<String, String> eleclection = getEleclection(appPositions);
        // 首先对所有的分支进行一个分类 固定的，非固定的
        // 固定状态分支(仅B/C/S之一)
        Map<String, List<String>> completefixedMap = new HashMap<>();
        // 组团一起变化的分支
        Map<String, List<String>> togetherBCMap = new HashMap<>();
        // 可选BC的单独分支
        List<String> singleBCList = new ArrayList<>();
        // 可选SC的单独分支
        List<String> singleSCList = new ArrayList<>();
        // 可选BS的单独分支
        List<String> singleBSList = new ArrayList<>();
        // 可选BSC的单独分支
        List<String> singleBSCList = new ArrayList<>();
        // 存储图的所有分支id，以这个顺序为主
        List<String> normList = new ArrayList<>();
        // 初始方案得分支打断状况
        List<String> primeList = new ArrayList<>();
        // 穿腔的id(涉及闭环的关键分支)
        List<String> wearId = new ArrayList<>();
        // 互斥的情况 互斥分支(一组为B则另一组必须为C)
        Map<String, Map<String, List<String>>> mutexMap = new HashMap<>();
        // 互斥团的情况
        Map<String, List<String>> mutexGroupMap = new HashMap<>();
        // 多选一的情况(N个分支中至多一个为C)
        Map<String, Map<String, List<String>>> chooseOneMap = new HashMap<>();
        // 可以变为S的id集合
        List<String> canChangeS = new ArrayList<>();
        // 分支可供选择的是BS的这种集合
        List<String> edgeChooseBS = new ArrayList<>();
        // 找出那些符合变B的情况：用在随机取B 算闭环平均数
        List<String> conformList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            normList.add(edge.get("id").toString());
            primeList.add(edge.get("topologyStatusCode").toString());
            if (edge.get("statusB") == null) {
                edge.put("statusB", "");
            }
            if (edge.get("statusC") == null) {
                edge.put("statusC", "");
            }
            if (edge.get("statusS") == null) {
                edge.put("statusS", "");
            }

            // 只要分支可以为b，则添加到符合变b条件的分支
            if ((edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")) ||
                    (edge.get("statusC").toString().equals("C") && edge.get("statusB").toString().equals("B")) ||
                    (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")
                            && edge.get("statusC").toString().equals("C"))) {
                conformList.add(edge.get("id").toString());
            }

            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")
                    && edge.get("statusC").toString().isEmpty()) {
                edgeChooseBS.add(edge.get("id").toString());
            }
            // 找出那些可变S的情况，可以变为s状态的分支
            if (edge.get("oneC") == null || "".equals(edge.get("oneC"))) {
                if ((edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S"))
                        || (edge.get("statusC").toString().equals("C") && edge.get("statusS").toString().equals("S"))
                        || (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")
                                && edge.get("statusC").toString().equals("C"))) {
                    canChangeS.add(edge.get("id").toString());
                }
            }

            // 将穿腔的id添加到wearId中去
            if (edge.get("closedLoop") != null && !edge.get("closedLoop").toString().isEmpty()) {
                wearId.add(edge.get("id").toString());
            }
            // 存在互斥的情况 将id添加到mutexMap中去
            if (edge.get("mutualExclusion") != null && !edge.get("mutualExclusion").toString().isEmpty()) {
                String mutexName = edge.get("mutualExclusion").toString();
                String[] split = mutexName.split("-");
                if (mutexMap.containsKey(split[0])) {
                    Map<String, List<String>> map1 = mutexMap.get(split[0]);
                    if (map1.containsKey(mutexName)) {
                        map1.get(mutexName).add(edge.get("id").toString());
                    } else {
                        List<String> idList = new ArrayList<>();
                        idList.add(edge.get("id").toString());
                        map1.put(mutexName, idList);
                    }
                } else {
                    Map<String, List<String>> sonMap = new HashMap<>();
                    List<String> idList = new ArrayList<>();
                    idList.add(edge.get("id").toString());
                    // 互斥状态-分支id
                    sonMap.put(mutexName, idList);
                    mutexMap.put(split[0], sonMap);
                }
                // 考虑互斥是否存在组团的情况如果存在 记录下来
                if (edge.get("changeTogether") != null && !edge.get("changeTogether").toString().isEmpty()) {
                    if (mutexGroupMap.containsKey(edge.get("changeTogether").toString())) {
                        mutexGroupMap.get(edge.get("changeTogether").toString()).add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        mutexGroupMap.put(edge.get("changeTogether").toString(), list);
                    }
                }
                continue;
            }

            // 对多选的一个情况进行一个记录，具有相同onec值的分支属于同一组
            if (edge.get("oneC") != null && !"".equals(edge.get("oneC"))) {
                String chooseName = edge.get("oneC").toString();
                List<String> chooselist = new ArrayList<>();
                if (edge.get("statusB").toString().equals("B")) {
                    chooselist.add("B");
                }
                if (edge.get("statusS").toString().equals("S")) {
                    chooselist.add("S");
                }
                if (edge.get("statusC").toString().equals("C")) {
                    chooselist.add("C");
                }

                if (chooseOneMap.containsKey(chooseName)) {
                    chooseOneMap.get(chooseName).put(edge.get("id").toString(), chooselist);
                } else {
                    Map<String, List<String>> listMap = new HashMap<>();
                    listMap.put(edge.get("id").toString(), chooselist);
                    chooseOneMap.put(chooseName, listMap);
                }
                continue;
            }

            // 当只有一个勾选的的情况 将该分支加入对应的框里面
            int trueCount = 0;
            if (edge.get("statusB").toString().equals("B")) {
                trueCount++;
            }
            if (edge.get("statusS").toString().equals("S")) {
                trueCount++;
            }
            if (edge.get("statusC").toString().equals("C")) {
                trueCount++;
            }
            if (trueCount == 1) {
                if (edge.get("statusB").toString().equals("B")) {
                    if (completefixedMap.containsKey("B")) {
                        completefixedMap.get("B").add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        completefixedMap.put("B", list);
                    }
                }

                if (edge.get("statusC").toString().equals("C")) {
                    if (completefixedMap.containsKey("C")) {
                        completefixedMap.get("C").add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        completefixedMap.put("C", list);
                    }
                }
                if (edge.get("statusS").toString().equals("S")) {
                    if (completefixedMap.containsKey("S")) {
                        completefixedMap.get("S").add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        completefixedMap.put("S", list);
                    }
                }
                continue;
            }

            // 是否为cs这种情况 分别放入到对应的集合中去
            if (edge.get("statusB").toString().isEmpty() && edge.get("statusS").toString().equals("S")
                    && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleSCList.add(edge.get("id").toString());
                    continue;
                }
            }

            // 当前为bs的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")
                    && edge.get("statusC").toString().isEmpty()) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBSList.add(edge.get("id").toString());
                    continue;
                }
            }
            // 当前为bsc的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")
                    && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBSCList.add(edge.get("id").toString());
                    continue;
                }
            }
            // 当前为bc的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().isEmpty()
                    && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBCList.add(edge.get("id").toString());
                    continue;
                }

            }
            // 剩下的都是都是在BC中进行一个挑选
            if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                singleBCList.add(edge.get("id").toString());
            } else {
                if (togetherBCMap.containsKey(edge.get("changeTogether").toString())) {
                    togetherBCMap.get(edge.get("changeTogether").toString()).add(edge.get("id").toString());
                } else {
                    List<String> list = new ArrayList<>();
                    list.add(edge.get("id").toString());
                    togetherBCMap.put(edge.get("changeTogether").toString(), list);
                }
            }
        }
        Map<String, Map<String, String>> elecPosition = new HashMap<>();
        for (Map<String, String> appPosition : appPositions) {
            // 用电器名称
            String appName = appPosition.get("appName");
            Map<String, String> appPositionMap = new HashMap<>();
            // 用电器位置是否固化
            String positionRegular = appPosition.get("positionRegular");
            if ("N".equals(positionRegular)) {
                String appId = appPosition.get("unregularPointId");
                String positionName = appPosition.get("unregularPointName");
                if (appId != null && !"null".equals(appId)) {
                    appPositionMap.put(appId, positionName);
                }
            } else {
                String appId = appPosition.get("regularPointId");
                String positionName = appPosition.get("regularPointName");
                if (appId != null && !"null".equals(appId)) {
                    appPositionMap.put(appId, positionName);
                }
            }
            if (elecPosition != null) {
                elecPosition.put(appName, appPositionMap);
            }
        }
        // 对两个组团进行一个处理
        Set<String> mutexGroupKey = mutexGroupMap.keySet();
        for (String s : mutexGroupKey) {
            // 如果组团一起变的中包含互斥组团，那么将该组团加入到互斥组团里，并且从togetherBCMap中删除该组
            if (togetherBCMap.containsKey(s)) {
                mutexGroupMap.get(s).addAll(togetherBCMap.get(s));
                togetherBCMap.remove(s);
            }
        }
        // 分支长度统计
        Map<String, Object> branchLength = getBranchLength(normList, edges);
        // 连接关系索引构建
        List<List<Integer>> connection = connection(edges, normList);
        // 焊点-对应回路位置点名称
        Map<String, List<String>> multiLoopInfos = new LinkedMap<>();

        // 位置点-位置点id
        Map<String, String> pointMap = new HashMap<>();
        for (Map<String, String> point : points) {
            pointMap.put(point.get("pointName").toString(), point.get("id").toString());
        }
        for (Map<String, String> loopInfoTemp : loopInfos) {
            if (loopInfoTemp.get("startApp").startsWith("[") || loopInfoTemp.get("endApp").startsWith("[")) {
                if (loopInfoTemp.get("startApp").startsWith("[")) {
                    // 获取终点用电器名称(非焊点we)
                    String endApp = loopInfoTemp.get("endApp");
                    // 查找终点位置名称
                    String node = findNode(endApp, appPositions);
                    if (multiLoopInfos.containsKey(loopInfoTemp.get("startApp"))) {
                        multiLoopInfos.get(loopInfoTemp.get("startApp")).add(node);
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(node);
                        multiLoopInfos.put(loopInfoTemp.get("startApp"), list);
                    }
                }
                if (loopInfoTemp.get("endApp").startsWith("[")) {
                    // 获取终点用电器名称
                    String startApp = loopInfoTemp.get("startApp");
                    // 查找终点位置名称
                    String node = findNode(startApp, appPositions);
                    if (multiLoopInfos.containsKey(loopInfoTemp.get("endApp"))) {
                        multiLoopInfos.get(loopInfoTemp.get("endApp")).add(node);
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(node);
                        multiLoopInfos.put(loopInfoTemp.get("endApp"), list);
                    }
                }
            }
        }
        // 将统一的map格式改为list
        List<List<String>> togetherBCList = new ArrayList<>();
        for (String key : togetherBCMap.keySet()) {
            togetherBCList.add(togetherBCMap.get(key));
        }
        List<List<String>> mutexGroupList = new ArrayList<>();
        for (String key : mutexGroupMap.keySet()) {
            mutexGroupList.add(mutexGroupMap.get(key));
        }
        List<Map<String, List<String>>> chooseOneList = new ArrayList<>();
        for (String key : chooseOneMap.keySet()) {
            chooseOneList.add(chooseOneMap.get(key));
        }

        // ---- 约束预计算：构建快速查找映射，供约束感知变异使用 ----
        // togetherBC: branchId -> 同组所有branchId集合（含自身）
        Map<String, Set<String>> togetherBCIndex = new HashMap<>();
        for (List<String> group : togetherBCList) {
            Set<String> set = new HashSet<>(group);
            for (String id : group) {
                togetherBCIndex.put(id, set);
            }
        }
        // chooseOne: branchId -> 同chooseOne组所有branchId集合
        Map<String, Set<String>> chooseOneIndex = new HashMap<>();
        for (Map<String, List<String>> group : chooseOneList) {
            // 收集该chooseOne组中所有分支id
            Set<String> allIds = new HashSet<>();
            for (List<String> ids : group.values()) {
                allIds.addAll(ids);
            }
            for (String id : allIds) {
                chooseOneIndex.put(id, allIds);
            }
        }
        // mutex: branchId -> 冲突的branchId集合（互斥组中另一方的所有分支）
        Map<String, Set<String>> mutexConflictIndex = new HashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : mutexMap.entrySet()) {
            Map<String, List<String>> groupMap = entry.getValue();
            List<Set<String>> subgroups = new ArrayList<>();
            for (List<String> ids : groupMap.values()) {
                subgroups.add(new HashSet<>(ids));
            }
            if (subgroups.size() >= 2) {
                // 子组0 ↔ 子组1 互斥
                for (String id : subgroups.get(0)) {
                    mutexConflictIndex.computeIfAbsent(id, k -> new HashSet<>()).addAll(subgroups.get(1));
                }
                for (String id : subgroups.get(1)) {
                    mutexConflictIndex.computeIfAbsent(id, k -> new HashSet<>()).addAll(subgroups.get(0));
                }
            }
        }
        // ---- 约束预计算结束 ----

        // 分支可以由S转为B的集合
        List<String> initialCanchangeSToBList = new ArrayList<>();
        initialCanchangeSToBList.addAll(singleBSList);
        initialCanchangeSToBList.addAll(singleBSCList);
        // 固定为B和S的统计
        List<String> onlyNameB = new ArrayList<>();
        if (completefixedMap.containsKey("B")) {
            onlyNameB.addAll(completefixedMap.get("B"));
        }
        List<String> onlyNameS = new ArrayList<>();
        if (completefixedMap.containsKey("S")) {
            List<String> list = completefixedMap.get("S");
            onlyNameS.addAll(list);
        }
        // 允许为 C 的分支集合：BC 可选、SC 可选、BSC 可选、归一化分支
        Set<String> canChangeToCSet = new HashSet<>();
        canChangeToCSet.addAll(singleBCList);
        canChangeToCSet.addAll(singleSCList);
        canChangeToCSet.addAll(singleBSCList);
        canChangeToCSet.addAll(normList);
        // 固定状态分支集合（B/S 状态保留，不动）
        Set<String> keepFixedSet = new HashSet<>(onlyNameB);
        keepFixedSet.addAll(onlyNameS);
        // initialScheme 当前方案下的分支打断情况，只把"允许改 C 且非固定"的分支置为 C，其他保持原状
        List<String> initialScheme = new ArrayList<>();
        List<Map<String, Object>> coppyedges = edges.stream()
                .map(map -> new HashMap<>(map)) // 对每个 Map 创建新实例
                .collect(Collectors.toList());
        // 分支允许为 C 的才置为 C，但固定为 B/S 的分支保持原状态不动
        for (Map<String, Object> coppyedge : coppyedges) {
            String id = (String) coppyedge.get("id");
            if (!keepFixedSet.contains(id) && canChangeToCSet.contains(id)) {
                coppyedge.put("topologyStatusCode", "C");
                initialScheme.add("C");
            } else {
                initialScheme.add(coppyedge.get("topologyStatusCode") == null ? "C"
                        : coppyedge.get("topologyStatusCode").toString());
            }
        }
        jsonMap.put("edges", coppyedges);
        // 分支id-分支打断代价 获取每条分支的打断代价
        Map<String, Double> breakCostMap = new HashMap<>();
        String detail = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
        Map<String, Object> objectMap = jsonToMap.TransJsonToMap(detail);
        // 提取经过各个分支的所有信息
        Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) objectMap
                .get("bundeleRelatedCircuitInfo");
        // 所有回路详细信息（最优方案）
        List<Map<String, Object>> circuitInfoList = (List<Map<String, Object>>) objectMap.get("circuitInfo");
        // 统计所有分支的打断代价,分支打断代价指经过这个分支的所有回路打断后的成本相加，这个打断代价会根据图的通断状态决定，因为回路走向不一样了
        for (String s : bundeleRelatedCircuitInfo.keySet()) {
            Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
            // 分支详细信息
            Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
            breakCostMap.put(s,
                    Double.parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
        }
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        // 按照导线单位商务价降序排序
        Map<String, Map<String, String>> sortedMapExcel = sortMapByInnerCostValue(elecFixedLocationLibrary);
        // 打断代价从高到低排序
        Map<String, Double> sortedMap = sortMapByDoubleValue(breakCostMap);

        // 9) 把 conformList 转为 Set（作为 canBreakToBSet：可打断为 B 的分支集合）
        Set<String> canBreakToBSet = new HashSet<>(conformList);

        // 10) bestBreakCount：直接根据可打断分支数量设定，不再计算存活率
        // 约束感知变异能保证存活率接近100%，存活率计算已无必要
        int bestBreakCount = Math.min(canBreakToBSet.size(), Math.max(3, canBreakToBSet.size() / 3));
        System.out.println("bestBreakCount = " + bestBreakCount + " (可打断分支数=" + canBreakToBSet.size() + ")");

        // 11) 生成初代方案：约束感知变异，枚举k=1,2，抽样k>2，使用 initialScheme 作为基础状态
        long initTime = System.currentTimeMillis();
        Set<String> canChangeSSet = new HashSet<>(canChangeS);
        List<List<String>> initialSchemes = generateInitialSchemes(
                edges, canBreakToBSet, initialScheme,
                appPositions, eleclection,
                bestBreakCount, breakCostMap, normList,
                mutexMap, chooseOneList, togetherBCList,
                togetherBCIndex, chooseOneIndex, mutexConflictIndex,
                canChangeSSet);
        System.out.println("初代方案生成" + initialSchemes.size() + "个方案耗时：" + (System.currentTimeMillis() - initTime));
        if (initialSchemes.isEmpty()) {
            System.err.println("初代方案生成失败：0个方案通过约束，无法继续优化");
            return null;
        }
        // 模型预测成本
        long predictTime = System.currentTimeMillis();
        List<Map<String, Object>> findBest = predictAndFindBest(initialSchemes, edges, normList, jsonMap,
                edgeChooseBS, elecPosition, branchLength, connection, multiLoopInfos, pointMap, null);
        System.out.println("预测" + initialSchemes.size() + "个样本耗时：" + (System.currentTimeMillis() - predictTime));

        // 将初始化方案也放入到迭代中去
        Map<String, Object> addtoMap = new HashMap<>();
        addtoMap.put("serviceableStatue", primeList);
        findBest.add(addtoMap);
        // 遗传算法第一代的父本=初代预测 Top 100 + 初始方案
        // 阶段一以 TopDetail[0](初代最优)为基准变异,不再使用 initialScheme
        TopDetail = findBest;
        int hybridizationNumber = 1;
        long hybridizationTime = System.currentTimeMillis();
        // 遗传算法
        while (true) {
            System.out.println("第" + hybridizationNumber + "代迭代开始");
            long startTime = System.currentTimeMillis();
            // 只有当迭代的结果top10都是同一个值的时候 才结束迭代
            // 两阶段变异:① 同时+概率变异(复用 generateInitialSchemes) ② 两两交叉变异
            // 精英保留:把上一代 top 30% 注入候选池,保证新一代最优只可能持平或更低
            findBest = hybridization(
                    edges, canBreakToBSet, initialScheme, appPositions, eleclection,
                    bestBreakCount, breakCostMap, normList,
                    mutexMap, chooseOneList, togetherBCList,
                    jsonMap, edgeChooseBS, elecPosition, branchLength,
                    connection, multiLoopInfos, pointMap, hybridizationNumber,
                    togetherBCIndex, chooseOneIndex, mutexConflictIndex,
                    canChangeSSet);
            if (findBest == null || findBest.size() == 0) {
                break;
            }
            TopDetail = findBest;
            System.out.println("第" + hybridizationNumber + "代迭代结束，耗时：" + (System.currentTimeMillis() - startTime));
            if (hybridizationNumber == 1) {
                double costTotal = Double
                        .parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double
                        .parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double
                        .parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总重量").toString());
                BestCost.put("总成本", costTotal);
                BestCost.put("总长度", costLenth);
                BestCost.put("总重量", costWeight);
            } else {
                // 获取当前最优解的各项指标
                double costTotal = Double
                        .parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double
                        .parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double
                        .parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总重量").toString());
                // 当前最优解中的长度判断当前的成本、长度、重量是都一样
                // 判断是否与历史最优解基本相同（允许微小误差)
                if (Math.abs(BestCost.get("总成本") - costTotal) < 0.000001
                        && Math.abs(BestCost.get("总长度") - costLenth) < 0.000001
                        && Math.abs(BestCost.get("总重量") - costWeight) < 0.000001) {
                    BestRepetitionNumber = BestRepetitionNumber + 1; // 相同则计数器加1
                    System.out.println("重复次数： " + BestRepetitionNumber);
                } else if (costTotal < BestCost.get("总成本")) {
                    // 找到更优解，更新并重置计数器
                    BestRepetitionNumber = 0;
                    BestCost.put("总成本", costTotal);
                    BestCost.put("总长度", costLenth);
                    BestCost.put("总重量", costWeight);
                } else {
                    // 当前最优更差，不更新历史最优，但计数器+1（因为和最优解不同）
                    BestRepetitionNumber++;
                }
            }
            if (BestRepetitionNumber == IterationRestrictNumber) {
                System.out.println("迭代次数达到限制，后续与上一代结果相同达到30次");
                break;
            }
            hybridizationNumber++;
        }
        System.out.println("遗传算法结束，耗时：" + (System.currentTimeMillis() - hybridizationTime));

        // 对遗传生成的方案进行闭环检测，打断代价低的分支改S
        List<List<String>> lists = new ArrayList<>();
        for (Map<String, Object> stringObjectMap : findBest) {
            List<String> serviceableStatue = (List<String>) stringObjectMap.get("serviceableStatue");
            lists.add(serviceableStatue);
        }
        // 拿到的top最优方案，没有闭环
        List<Map<String, Object>> mapList = changeAndFindBest(lists, edges, normList, wearId, canChangeS,
                jsonMap,
                edgeChooseBS, elecPosition, branchLength, connection, multiLoopInfos, pointMap, findBest, conformList);
        // 回路绕线优化
        List<Map<String, Object>> maps = windingOptimize(
                mapList,
                adjacencyMatrixGraphConnector,
                edges,
                normList,
                canChangeS,
                wearId,
                jsonMap,
                objectMapper,
                projectCircuitInfoOutput,
                jsonToMap, mutexMap, chooseOneList, togetherBCList, singleBCList, singleSCList,
                singleBSList, singleBSCList, eleclection);
        threadPool.shutdown();
        return objectMapper.writeValueAsString(maps);
    }

    /**
     * @Description: 绕线优化(在遗传算法结束后最后跑一次)。
     *               思路:
     *               ① 遍历所有方案,对每个绕线回路(windingLength > 0)算出"全打通"状态下的最短路径,
     *               与原回路比对,差异分支(全打通有而原回路没有的分支)就是"打通后能消除绕线"的分支;
     *               把"绕线长度"作为成本贡献,均摊到这些差异分支上,累加得 branchCostContribution[branchId]。
     *               ② 累计成本贡献 > WindingCostThreshold 的分支,直接 B → C(用不绕线的回路)。
     *               ③ 改完后用 recognizeLoopNew 检测闭环,用 canChangeS 中打断成本最小的分支打 S 消除,
     *               循环直到无闭环。
     *               ④ 重算成本 + 约束检查,过则保留,挂则丢弃。
     *               ⑤ 取 TopNumber 返回。
     * @input: mapList 已完成遗传迭代的方案集
     * @input: adjacencyMatrixGraphConnector 分支全部打通情况下的邻接矩阵
     * @input: edges 原始 edges
     * @input: normList 分支 id 顺序列表
     * @input: canChangeS 可打 S 的分支
     * @input: wearId 穿腔分支
     * @input: jsonMap 项目配置
     * @input: mapper ObjectMapper
     * @input: projectCircuitInfoOutput 成本重算器
     * @input: jsonToMap 结果反序列化器
     * @Return: 绕线优化后的 TopNumber 个方案
     * @Complexity: O(N * ( E log V + 成本重算)) N=方案数,E=edges,V=节点数
     */
    public List<Map<String, Object>> windingOptimize(
            List<Map<String, Object>> mapList,
            GenerateTopoMatrixConnector adjacencyMatrixGraphConnector,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> canChangeS,
            List<String> wearId,
            Map<String, Object> jsonMap,
            ObjectMapper mapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap, Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList, List<List<String>> togetherBCList,
            List<String> singleBCList,
            List<String> singleSCList,
            List<String> singleBSList,
            List<String> singleBSCList, Map<String, String> eleclection) throws Exception {

        FindBest findBest = new FindBest();
        if (mapList == null || mapList.isEmpty()) {
            return null;
        }

        // ① 收集高成本贡献分支(改:放到 processSingleSchemeForWinding 里按方案独立统计)
        // 原因:不同方案通断状态不一致,全局统计会把各方案的差异平均掉,丢失"本方案该改哪个分支"的精确度
        // 改为:对每个方案独立计算 branchCostContribution,得到该方案自己的 highCostBranches,再 B→C

        // ② 对每个方案做 独立统计 + B→C + 闭环消除 + 成本重算 + 约束检查（多线程提速）
        List<Map<String, Double>> costDeail = Collections.synchronizedList(new ArrayList<>());
        List<java.util.concurrent.Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (Map<String, Object> map : mapList) {
            tasks.add(() -> {
                return processSingleSchemeForWinding(
                        map, adjacencyMatrixGraphConnector, edges, normList, canChangeS, wearId,
                        jsonMap, mapper, projectCircuitInfoOutput, jsonToMap, mutexMap, chooseOneList, togetherBCList,
                        singleBCList, singleSCList,
                        singleBSList, singleBSCList, eleclection, costDeail);
            });
        }

        List<java.util.concurrent.Future<Map<String, Object>>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<Map<String, Object>> task : tasks) {
            futures.add(threadPool.submit(task));
        }

        List<Map<String, Object>> optimized = new ArrayList<>();
        int scrapCount = 0;
        final int earlyStopTarget = TopNumber * 2;
        for (java.util.concurrent.Future<Map<String, Object>> future : futures) {
            if (optimized.size() >= earlyStopTarget) {
                System.out.println("[windingOptimize] 早停:已收集 " + optimized.size() + " 个优化方案");
                break;
            }
            try {
                Map<String, Object> result = future.get(3000, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    optimized.add(result);
                } else {
                    scrapCount++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println("[windingOptimize] 处理完成:通过 " + optimized.size()
                + " / " + mapList.size() + " (淘汰 " + scrapCount + ")");

        if (optimized.isEmpty()) {
            return findBest.findBest(mapList, "成本", TopNumber);
        }
        return findBest.findBest(optimized, "成本", TopNumber);
    }

    /**
     * @Description: 阶段一(单方案版):对单个方案统计其所有回路的绕线成本贡献。
     *               对每个绕线回路,找全打通状态下的最短路径,与原回路差异分支均摊绕线成本作为该分支的贡献。
     *               与原 mapList 版的区别:原版本是全局聚合(多方案求和),误差大;
     *               本版本是 per-scheme 统计,B→C 完全基于本方案自己的贡献,精度高。
     *
     * @return 该方案的 branchCostContribution(branchId -> 成本贡献)
     */
    private Map<String, Double> collectBranchCostContribution(
            Map<String, Object> map,
            GenerateTopoMatrixConnector adjacencyMatrixGraphConnector,
            List<Map<String, Object>> edges,
            List<String> normList,
            Map<String, Object> jsonMap) {
        Map<String, Double> branchCostContribution = new HashMap<>();
        if (adjacencyMatrixGraphConnector == null) {
            return branchCostContribution;
        }
        CalculateCircuitInfo acceptLoopInfo = new CalculateCircuitInfo();
        FindShortestPath findShortestPath = new FindShortestPath();
        List<String> allPoints = adjacencyMatrixGraphConnector.getAllPoint();
        List<List<Integer>> adj = adjacencyMatrixGraphConnector.getAdj();
        List<Map<String, String>> pointList = (List<Map<String, String>>) jsonMap.get("points");
        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
        DecimalFormat df = new DecimalFormat("0.00");

        // 预构建:点对 → 边id(双向)
        Map<String, String> pairToEdgeId = new HashMap<>();
        // edges的edgeName就是右前门线inline点-车身线右前inline点这种的名称，下面还需要拼接吗手动的
        for (Map<String, Object> e : edges) {
            Object idObj = e.get("id");
            Object sObj = e.get("startPointName");
            Object tObj = e.get("endPointName");
            if (idObj == null || sObj == null || tObj == null) {
                continue;
            }
            String s = sObj.toString();
            String t = tObj.toString();
            String id = idObj.toString();
            pairToEdgeId.put(s + "-" + t, id);
            pairToEdgeId.put(t + "-" + s, id);
        }

        Object costObj = map.get("成本");
        if (!(costObj instanceof Map)) {
            return branchCostContribution; // 单方案:无成本就直接返回空 map
        }
        Object ciObj = ((Map<?, ?>) costObj).get("circuitInfo");
        // 拿当前 top 方案的 edges(原状 B/C/S 混合),严格只用 top 自己的状态
        // 不兜底用原始 edges:原始 edges 没有 top 的状态,会污染成本计算
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topEdges = (List<Map<String, Object>>) map.get("serviceableEdges");
        if (topEdges == null) {
            return branchCostContribution;
        }
        // 构造"B→C"版 edges(假恢复,仅供 calculateCircuitInfo 算 newCost):
        // B → C ← 原本打断的分支假性打通,模拟"消绕线"后的走线
        // C → C ← 原本通的保持通
        // S → S ← 穿腔刻意打断,保持打断,绝不能假性打通
        // 真正的 B→C 在 processSingleSchemeForWinding 里,基于 branchCostContribution > 阈值 才执行
        List<Map<String, Object>> bToCEdges = new ArrayList<>();
        for (Map<String, Object> e : topEdges) {
            Map<String, Object> edgeCopy = new HashMap<>(e);
            String code = e.get("topologyStatusCode") != null
                    ? e.get("topologyStatusCode").toString()
                    : "";
            if ("B".equalsIgnoreCase(code)) {
                edgeCopy.put("topologyStatusCode", "C");
            }
            bToCEdges.add(edgeCopy);
        }
        Map<String, Object> copyJsonMap = new HashMap<>(jsonMap);
        copyJsonMap.put("edges", bToCEdges);
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(copyJsonMap);
        // 拿当前 top 方案的分支状态(用于过滤"原本是 B 的分支")
        @SuppressWarnings("unchecked")
        List<String> statue = (List<String>) map.get("serviceableStatue");
        if (statue == null || statue.size() != normList.size()) {
            return branchCostContribution;
        }
        if (!(ciObj instanceof List)) {
            return branchCostContribution;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> circuitInfo = (List<Map<String, Object>>) ciObj;

        for (Map<String, Object> circuitMap : circuitInfo) {
            // 1.1) 跳过非绕线回路
            double windingLength = parseDoubleSafe(circuitMap.get("回路绕线长度"));
            if (windingLength <= 0) {
                continue;
            }

            // 获取当前回路的总成本
            double oldTotalCost = parseDoubleSafe(circuitMap.get("回路总成本"));

            // 1.2) 拿回路起终点
            String startPointName = str(circuitMap.get("起点位置名称"));
            String endPointName = str(circuitMap.get("终点位置名称"));
            if (startPointName == null || endPointName == null) {
                continue;
            }
            int startIdx = allPoints.indexOf(startPointName);
            int endIdx = allPoints.indexOf(endPointName);
            if (startIdx < 0 || endIdx < 0) {
                continue;
            }

            // 1.3) 全打通状态下最短路径
            List<Integer> altPath = findShortestPath.findShortestPathBetweenTwoPoint(adj, startIdx, endIdx);
            if (altPath == null || altPath.size() < 2) {
                continue;
            }
            // 计算打通状态下的回路成本
            // 将路径中的数字转化为对应的名称
            List<String> listname = convertPathToNumbers(altPath, adjacencyMatrixGraphConnector.getAllPoint());
            // 计算该条路径成本
            // 导线选型，路径点，项目信息，导线excel
            String materials = circuitMap.get("导线选型").toString();
            // 根据导线选型选择对应的信息
            Map<String, String> materialsMsg = ProjectCircuitInfoOutput.elecFixedLocationLibrary.get(materials);
            Map<String, Object> twoPointMsg = acceptLoopInfo.calculateCircuitInfo(materials, listname, projectInfo,
                    ProjectCircuitInfoOutput.elecFixedLocationLibrary);
            // 计算两端连接器干湿
            String startParam = getWaterParam(listname.get(0), pointList);
            String endParam = getWaterParam(listname.get(listname.size() - 1), pointList);
            twoPointMsg.put("端子成本", Double.parseDouble(materialsMsg.get("导线打断成本（元/次）")));// 端子成本 实际上指的是导线打断成本
            // 回路两端湿区数量
            Integer wetNumber = 0;
            if ("w".toUpperCase().equals(startParam)) {
                wetNumber++;
            }
            if ("w".toUpperCase().equals(endParam)) {
                wetNumber++;
            }
            // 计算图打通后不绕线的回路的总成本
            double connectorCost = Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) * wetNumber;
            double waterproofCost = Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) * wetNumber;
            double inlineWaterproofCost = Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString());
            double inlineConnectorCost = Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString());
            double breakCost = Double.parseDouble(twoPointMsg.get("回路打断成本").toString());
            double terminalCost = Double.parseDouble(twoPointMsg.get("端子成本").toString());
            double wireCost = Double.parseDouble(twoPointMsg.get("回路导线成本").toString());
            double newCost = connectorCost + waterproofCost + inlineWaterproofCost
                    + inlineConnectorCost + breakCost + terminalCost + wireCost;
            // 绕线成本减去不饶先成本
            Double disCost = oldTotalCost - newCost;
            // 1.4) 路径点 → 分支id
            List<String> altPathBranchIds = new ArrayList<>();
            for (int i = 0; i < altPath.size() - 1; i++) {
                String a = allPoints.get(altPath.get(i));
                String b = allPoints.get(altPath.get(i + 1));
                String eid = pairToEdgeId.get(a + "-" + b);
                if (eid != null && !altPathBranchIds.contains(eid)) {
                    altPathBranchIds.add(eid);
                }
            }
            if (altPathBranchIds.isEmpty()) {
                continue;
            }

            // 1.5) 原始回路分支id(回路打断分支id 即该回路实际走的分支)
            @SuppressWarnings("unchecked")
            List<String> originalBranchIds = (List<String>) circuitMap.get("回路打断分支id");
            Set<String> originalSet = new HashSet<>();
            if (originalBranchIds != null) {
                originalSet.addAll(originalBranchIds);
            }

            // 1.6) 差异分支(全打通有,原回路没有) + 当前 top 方案里是 B = 真正"原本打断、改为 C 即可消绕线"的分支
            // 原本是 C 的:已在全打通态,无变化
            // 原本是 S 的:刻意打断,不动
            List<String> newOpened = new ArrayList<>();
            for (String bid : altPathBranchIds) {
                if (originalSet.contains(bid)) {
                    continue;
                }
                int idx = normList.indexOf(bid);
                if (idx >= 0 && "B".equals(statue.get(idx))) {
                    newOpened.add(bid);
                }
            }
            if (newOpened.isEmpty()) {
                continue;
            }

            // 1.7) 均摊绕线前后成本差到这些差异分支(以"打通后能消绕线"的 B→C 分支为载体)
            if (disCost <= 0) {
                continue;
            }
            double perBranch = disCost / newOpened.size();
            for (String bid : newOpened) {
                branchCostContribution.merge(bid, perBranch, Double::sum);
            }
        }
        return branchCostContribution;
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
            if (name.equalsIgnoreCase(map.get("pointName"))) {
                return map.get("waterParam");
            }
        }
        return null;
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
     * @Description: 对单个方案执行 独立统计 + B→C + 闭环消除 + 成本重算 + 约束检查。
     *               流程严格遵循硬约束:闭环必须消完才返回,约束不过则丢弃。
     *               关键:branchCostContribution 按本方案独立计算,B→C 也只针对本方案
     */
    private Map<String, Object> processSingleSchemeForWinding(
            Map<String, Object> map,
            GenerateTopoMatrixConnector adjacencyMatrixGraphConnector,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> canChangeS,
            List<String> wearId,
            Map<String, Object> jsonMap,
            ObjectMapper mapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap, Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList, List<List<String>> togetherBCList,
            List<String> singleBCList,
            List<String> singleSCList,
            List<String> singleBSList,
            List<String> singleBSCList, Map<String, String> eleclection, List<Map<String, Double>> costDeail)
            throws Exception {
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> projectInfo = (Map<String, Object>) jsonMap.get("projectInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Boolean whetherOnLoop = caseInfo.get("loopcreate").toString().equals("true") ? true : false;
        // 可打B的分支
        List<String> canChangeToB = new ArrayList<>();
        canChangeToB.addAll(singleBCList);
        canChangeToB.addAll(singleBSList);
        canChangeToB.addAll(singleBSCList);
        List<String> originalStatue = (List<String>) map.get("serviceableStatue");
        if (originalStatue == null || originalStatue.size() != normList.size()) {
            return null;
        }
        List<String> statue = new ArrayList<>(originalStatue);

        // 1) 阶段一(改):per-scheme 独立统计 branchCostContribution,得到本方案自己的 highCostBranches
        // 原因:不同方案通断状态不一致,全局统计会把各方案的差异平均掉,丢失"本方案该改哪个分支"的精确度
        Map<String, Double> branchCostContribution = collectBranchCostContribution(
                map, adjacencyMatrixGraphConnector, edges, normList, jsonMap);
        Set<String> highCostBranches = new HashSet<>();
        for (Map.Entry<String, Double> e : branchCostContribution.entrySet()) {
            if (e.getValue() > WindingCostThreshold) {
                highCostBranches.add(e.getKey());
            }
        }

        // 2) B → C:本方案贡献超阈值的分支
        if (highCostBranches.size() != 0) {
            for (int i = 0; i < statue.size(); i++) {
                if ("B".equals(statue.get(i)) && highCostBranches.contains(normList.get(i))
                        && singleBSCList.contains(normList.get(i))) {
                    statue.set(i, "C");
                }
            }
        }
        // 让方案满足多选一约束
        // 规则:每组中"恰好一个 C"
        // - 当前 1 个 C:满足,放过
        // - 当前 0 个 C:从允许状态含 C 的分支中选一个改成 C
        // - 当前 >1 个 C:随机保留一个 C,其余 C → 其允许状态中随机选一个(非 C)
        applyChooseOneConstraint(statue, chooseOneList, normList);
        // 记录原始方案的成本（用于后续比较）
        Map<String, Object> origCostObj = (Map<String, Object>) map.get("成本");
        double originalCost = origCostObj != null && origCostObj.get("总成本") != null
                ? Double.parseDouble(origCostObj.get("总成本").toString())
                : Double.MAX_VALUE;

        // 2) 计算 B→C 后的初始成本和打断代价（对齐 changeAndFindBest）
        List<Map<String, Object>> serviceableEdge = createNewEdges(statue, edges, normList);
        Map<String, Object> threadLocalJsonMap = mapper.readValue(
                mapper.writeValueAsString(jsonMap), Map.class);
        threadLocalJsonMap.put("edges", serviceableEdge);

        Map<String, Double> breakCostMap = new HashMap<>();
        Map<String, Object> costResultData = new HashMap<>();
        try {
            String circuitResult = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(mapper.writeValueAsString(threadLocalJsonMap));
            Map<String, Object> obj = jsonToMap.TransJsonToMap(circuitResult);
            Map<String, Object> circuitInfoMap = (Map<String, Object>) obj.get("projectCircuitInfo");
            costResultData.put("总成本", circuitInfoMap.get("总成本"));
            costResultData.put("总长度", circuitInfoMap.get("回路总长度"));
            costResultData.put("总重量", circuitInfoMap.get("回路总重量"));

            Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) obj
                    .get("bundeleRelatedCircuitInfo");
            for (String s : bundeleRelatedCircuitInfo.keySet()) {
                Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
                Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                breakCostMap.put(s, Double.parseDouble(
                        edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
            }
        } catch (Exception e) {
            System.out.println("processSingleSchemeForWinding: 计算初始成本异常: " + e.getMessage());
            return null;
        }

        // 3) 闭环消除：对齐 changeAndFindBest 逻辑（wearId → whetherOnLoop）
        boolean scrapOrNot = false;
        int maxLoopIterations = 300;
        while (!scrapOrNot && maxLoopIterations-- > 0) {
            serviceableEdge = createNewEdges(statue, edges, normList);
            List<List<String>> recognizeLoopList = recognizeLoopNew(serviceableEdge);

            // 3a) 检查含 wearId 的闭环
            List<String> recognizeLoopIdList = new ArrayList<>();
            for (List<String> loop : recognizeLoopList) {
                for (String s : loop) {
                    if (wearId.contains(s)) {
                        recognizeLoopIdList.addAll(loop);
                        break;
                    }
                }
            }

            if (recognizeLoopIdList.size() != 0) {
                // 含 wearId 的闭环：选打断代价最低且可设为S的分支
                List<String> keyList = findMinCostKey(recognizeLoopIdList, breakCostMap);
                String minCostKey = null;
                for (String s : keyList) {
                    if (canChangeS.contains(s)) {
                        minCostKey = s;
                        break;
                    }
                }
                if (minCostKey == null) {
                    scrapOrNot = true;
                    break;
                }
                statue.set(normList.indexOf(minCostKey), "S");
                if (!refreshCircuitInfo(statue, edges, normList, threadLocalJsonMap,
                        projectCircuitInfoOutput, mapper, jsonToMap, costResultData, breakCostMap)) {
                    scrapOrNot = true;
                    break;
                }
            } else {
                // 3b) 检查 whetherOnLoop 全局闭环消除
                if (whetherOnLoop) {
                    int innerMaxIter = 300;
                    while (innerMaxIter-- > 0) {
                        serviceableEdge = createNewEdges(statue, edges, normList);
                        List<List<String>> recognizeLoopListSon = recognizeLoopNew(serviceableEdge);
                        if (recognizeLoopListSon.size() == 0) {
                            break;
                        } else {
                            Set<String> son = new HashSet<>();
                            for (List<String> loop : recognizeLoopListSon) {
                                son.addAll(loop);
                            }
                            List<String> keyList = findMinCostKey(new ArrayList<>(son), breakCostMap);
                            String minCostKey = null;
                            for (String s : keyList) {
                                if (canChangeS.contains(s)) {
                                    minCostKey = s;
                                    break;
                                }
                            }
                            if (minCostKey == null) {
                                if (keyList.isEmpty()) {
                                    scrapOrNot = true;
                                    break;
                                }
                                minCostKey = keyList.get(0);
                            }
                            statue.set(normList.indexOf(minCostKey), "S");
                            if (!refreshCircuitInfo(statue, edges, normList, threadLocalJsonMap,
                                    projectCircuitInfoOutput, mapper, jsonToMap, costResultData,
                                    breakCostMap)) {
                                scrapOrNot = true;
                                break;
                            }
                        }
                    }
                    if (scrapOrNot) {
                        break;
                    }
                }
                // 所有闭环已消除，跳出外层循环
                break;
            }
        }
        if (scrapOrNot || maxLoopIterations <= 0) {
            return null;
        }

        // 4) 成本比较：如果优化后成本没下降，改用原方案
        double newCost = Double.parseDouble(costResultData.get("总成本").toString());
        if (newCost >= originalCost) {
            // 成本上升，返回原方案
            List<String> origStatueCopy = new ArrayList<>(originalStatue);
            // 关键:对原始 statue 也进行多选一约束修正,否则后续 forceBreakLoops 里的 checkFirstOption 会失败
            // (原始 statue 可能本身就不满足多选一,例如来自遗传算法迭代的中间态)
            applyChooseOneConstraint(origStatueCopy, chooseOneList, normList);
            // 对最终的方案进行一个计算 并且按照格式进行一个返回
            List<Map<String, Object>> finalEdgeresult = createNewEdges(origStatueCopy, edges, normList);
            // 输出前进行最终的闭环校验：含 wearId 的闭环不能存在，开启消除闭环时也不能存在
            List<List<String>> finalLoopList = recognizeLoopNew(finalEdgeresult);
            boolean hasUnresolvedLoop = false;
            for (List<String> loop : finalLoopList) {
                boolean containsWearId = false;
                for (String s1 : wearId) {
                    if (loop.contains(s1)) {
                        containsWearId = true;
                        break;
                    }
                }
                if (containsWearId) {
                    hasUnresolvedLoop = true;
                    System.out.println("警告：最终方案仍存在含 wearId 的闭环！" + loop);
                    break;
                }
                if (whetherOnLoop) {
                    hasUnresolvedLoop = true;
                    System.out.println("警告：最终方案仍存在闭环（whetherOnLoop=true）！" + loop);
                    break;
                }
            }
            if (hasUnresolvedLoop) {
                // 仍有未消除的闭环，尝试最后一轮强制打断（从闭环中选 cost 最小的 B 打断）
                boolean broken = forceBreakLoops(origStatueCopy, edges, normList, wearId, canChangeToB, whetherOnLoop,
                        appPositions, eleclection, mutexMap, chooseOneList, togetherBCList,
                        projectCircuitInfoOutput, objectMapper, jsonMap, jsonToMap);
                if (broken) {
                    // 重新计算最终边
                    finalEdgeresult = createNewEdges(origStatueCopy, edges, normList);
                } else {
                    return null;
                }
            }
            List<Map<String, Object>> origEdges = finalEdgeresult;
            List<List<String>> list = recognizeLoopNew(finalEdgeresult);
            boolean whe = false;
            for (List<String> strings : list) {
                for (String string : wearId) {
                    if (strings.contains(string)) {
                        whe = true;
                    }
                }
            }
            if (whe) {
                System.out.println("原方案中穿腔分支还存在闭环");
                return null;
            }
            List<Map<String, String>> topoOptimizeResult = new ArrayList<>();
            HashMap<String, Object> newJsonMap = new HashMap<>(jsonMap);
            newJsonMap.put("edges", origEdges);
            String s = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(objectMapper.writeValueAsString(newJsonMap));
            Map<String, Object> map2 = jsonToMap.TransJsonToMap(s);
            Map<String, Double> tempCost = new HashMap<>();
            Map<String, Object> circuitInfoMap = (Map<String, Object>) map2.get("projectCircuitInfo");
            tempCost.put("总成本", Double.parseDouble(circuitInfoMap.get("总成本").toString()));
            tempCost.put("总重量", Double.parseDouble(circuitInfoMap.get("回路总重量").toString()));
            tempCost.put("总长度", Double.parseDouble(circuitInfoMap.get("回路总长度").toString()));
            if (costDeail.contains(tempCost)) {
                System.out.println("成本重复");
                return null;
            }
            costDeail.add(tempCost);
            for (Map<String, Object> edge : origEdges) {
                Map<String, String> result = new HashMap<>();
                result.put("edgeId", edge.get("id").toString());
                result.put("statue", edge.get("topologyStatusCode").toString());
                topoOptimizeResult.add(result);
            }

            map2.put("成本", costResultData);
            map2.put("topoId", topoInfoMap.get("id").toString());
            map2.put("caseId", projectInfo.get("caseId").toString());
            map2.put("topoOptimizeResult", topoOptimizeResult);
            map2.put("finishStatue", "normal");
            map2.put("initializationScheme", false);
            map2.put("serviceableStatue", origStatueCopy);
            map2.put("serviceableEdges", origEdges);
            return map2;
        }

        // 5) 构建优化方案返回结果
        // 成本去重检查

        // 对最终的方案进行一个计算 并且按照格式进行一个返回
        List<Map<String, Object>> finalEdgeresult = createNewEdges(statue, edges, normList);
        // 输出前进行最终的闭环校验：含 wearId 的闭环不能存在，开启消除闭环时也不能存在
        List<List<String>> finalLoopList = recognizeLoopNew(finalEdgeresult);
        boolean hasUnresolvedLoop = false;
        for (List<String> loop : finalLoopList) {
            boolean containsWearId = false;
            for (String s1 : wearId) {
                if (loop.contains(s1)) {
                    containsWearId = true;
                    break;
                }
            }
            if (containsWearId) {
                hasUnresolvedLoop = true;
                System.out.println("警告：最终方案仍存在含 wearId 的闭环！" + loop);
                break;
            }
            if (whetherOnLoop) {
                hasUnresolvedLoop = true;
                System.out.println("警告：最终方案仍存在闭环（whetherOnLoop=true）！" + loop);
                break;
            }
        }
        if (hasUnresolvedLoop) {
            // 仍有未消除的闭环，尝试最后一轮强制打断（从闭环中选 cost 最小的 B 打断）
            boolean broken = forceBreakLoops(statue, edges, normList, wearId, canChangeToB, whetherOnLoop,
                    appPositions, eleclection, mutexMap, chooseOneList, togetherBCList,
                    projectCircuitInfoOutput, objectMapper, jsonMap, jsonToMap);
            if (broken) {
                // 重新计算最终边
                finalEdgeresult = createNewEdges(statue, edges, normList);
            } else {
                return null;
            }
        }
        HashMap<String, Object> newJsonMap = new HashMap<>(jsonMap);
        newJsonMap.put("edges", finalEdgeresult);
        String s = projectCircuitInfoOutput
                .projectCircuitInfoOutput(objectMapper.writeValueAsString(newJsonMap));
        Map<String, Object> map2 = jsonToMap.TransJsonToMap(s);
        Map<String, Object> circuitInfoMap = (Map<String, Object>) map2.get("projectCircuitInfo");
        costResultData.put("总成本", circuitInfoMap.get("总成本"));
        costResultData.put("总长度", circuitInfoMap.get("回路总长度"));
        costResultData.put("总重量", circuitInfoMap.get("回路总重量"));
        List<Map<String, String>> topoOptimizeResult2 = new ArrayList<>();
        for (Map<String, Object> edge : finalEdgeresult) {
            Map<String, String> result = new HashMap<>();
            result.put("edgeId", edge.get("id").toString());
            result.put("statue", edge.get("topologyStatusCode").toString());
            topoOptimizeResult2.add(result);
        }
        List<List<String>> recognizeLoopListSon = recognizeLoopNew(finalEdgeresult);
        boolean whe = false;
        for (List<String> strings : recognizeLoopListSon) {
            for (String string : wearId) {
                if (strings.contains(string)) {
                    whe = true;
                }
            }
        }
        if (whe) {
            System.out.println("新方案中穿腔分支存在闭环");
            return null;
        }
        Map<String, Double> dedupCost = new HashMap<>();
        dedupCost.put("总成本", Double.parseDouble(costResultData.get("总成本").toString()));
        dedupCost.put("总重量", Double.parseDouble(costResultData.get("总重量").toString()));
        dedupCost.put("总长度", Double.parseDouble(costResultData.get("总长度").toString()));
        if (costDeail.contains(dedupCost)) {
            System.out.println("成本重复");
            return null;
        }
        costDeail.add(dedupCost);
        map2.put("成本", circuitInfoMap);
        map2.put("topoId", topoInfoMap.get("id").toString());
        map2.put("caseId", projectInfo.get("caseId").toString());
        map2.put("topoOptimizeResult", topoOptimizeResult2);
        map2.put("finishStatue", "normal");
        map2.put("initializationScheme", false);
        map2.put("serviceableStatue", statue);
        map2.put("serviceableEdges", finalEdgeresult);
        return map2;
    }

    /**
     * 强制打断未消除的闭环。
     * 对未消除的闭环（含 wearId 或 whetherOnLoop 开启），迭代打断，直到闭环全部消除或无法继续。
     *
     * @return true 表示至少打断了一个分支
     */
    private boolean forceBreakLoops(
            List<String> statueList,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> wearId,
            List<String> canChangeToB,
            boolean whetherOnLoop,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            ObjectMapper objectMapper,
            Map<String, Object> jsonMap,
            JsonToMap jsonToMap) {
        boolean anyBroken = false;
        int maxIterations = 300;
        while (true) {
            List<Map<String, Object>> currentEdges = createNewEdges(statueList, edges, normList);
            List<List<String>> lists = recognizeLoopNew(currentEdges);
            if (lists.isEmpty()) {
                break;
            }
            // 找出需处理的闭环：含 wearId 的，或 whetherOnLoop=true 时的所有闭环
            List<List<String>> targetLoops = new ArrayList<>();
            for (List<String> loop : lists) {
                boolean containsWearId = false;
                for (String w : wearId) {
                    if (loop.contains(w)) {
                        containsWearId = true;
                        break;
                    }
                }
                if (containsWearId) {
                    targetLoops.add(loop);
                } else if (whetherOnLoop) {
                    targetLoops.add(loop);
                }
            }
            if (whetherOnLoop) {
                targetLoops = lists;
            }
            if (targetLoops.isEmpty()) {
                break;
            }
            // 收集所有目标闭环中可打断的分支
            Set<String> breakableIds = new LinkedHashSet<>();
            for (List<String> loop : targetLoops) {
                for (String id : loop) {
                    if (canChangeToB.contains(id) && !id.isEmpty()) {
                        breakableIds.add(id);
                    }
                }
            }
            if (breakableIds.isEmpty()) {
                System.out.println("forceBreakLoops: 无可打断分支，停止");
                break;
            }
            // 尝试本轮所有可打断分支,直到找到一个合法的打断位置
            // 原因:之前一遇 checkFirstOption 不通过就 break 太果断,可能漏掉其他合法分支
            boolean brokenThisRound = false;
            List<String> triedInRound = new ArrayList<>();
            while (true) {
                // 选一个未尝试的分支(优先 wearId 闭环中的)
                String pickId = null;
                for (List<String> loop : targetLoops) {
                    for (String id : loop) {
                        if (breakableIds.contains(id) && !triedInRound.contains(id)) {
                            pickId = id;
                            break;
                        }
                    }
                    if (pickId != null)
                        break;
                }
                if (pickId == null) {
                    // 本轮所有可打断分支都尝试过,都不行
                    break;
                }
                triedInRound.add(pickId);
                int pickIdx = normList.indexOf(pickId);
                String originalStatus = statueList.get(pickIdx); // 记录原状态用于回滚
                // 验证打断后方案合法
                statueList.set(pickIdx, "S");
                List<Map<String, Object>> afterEdges = createNewEdges(statueList, edges, normList);
                Boolean ok = checkFirstOption(normList, statueList, afterEdges, appPositions, eleclection, mutexMap,
                        chooseOneList, togetherBCList);
                if (ok) {
                    System.out.println("forceBreakLoops: 已强制打断 " + pickId + "(原状态 " + originalStatus + " → S)");
                    anyBroken = true;
                    brokenThisRound = true;
                    break; // 跳出内层 while,进入下一轮外层
                } else {
                    System.out.println("forceBreakLoops: 打断 " + pickId + " 后方案不合法,回滚到 " + originalStatus + " 继续尝试下一个");
                    statueList.set(pickIdx, originalStatus); // 回滚到原状态,试下一个
                }
            }
            if (!brokenThisRound) {
                // 本轮所有可打断分支都试过且都失败,真正退出 forceBreakLoops
                System.out.println("forceBreakLoops: 本轮所有可打断分支尝试完毕且都不合法,退出");
                break;
            }
            // brokenThisRound=true 时继续外层 while 的下一轮
        }
        return anyBroken;
    }

    /**
     * 安全 double 解析,null/异常返 0
     */
    private double parseDoubleSafe(Object o) {
        if (o == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 安全取字符串,null/空返 null
     */
    private String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }

    /**
     * @Description: 根据给定的分支打断状况集合（符合要求的） 对他们进行一个分支的闭环检查 修改S 将最终的分支打断情况进行一个计算
     *               返回最优的是个方案
     * @input: simpleList 分支打断情况的集合
     * @input: edges txt中没解析的分支部分
     * @input: normList 分支id的集合
     * @input: wearId 穿孔id
     * @input: canChangeS 可以变s的分支id
     * @input: jsonMap txt内容单纯的转为map
     * @input: edgeChooseBS 分支打断可以选BS的集合
     * @Return: 返回最优的top20方案
     */
    public List<Map<String, Object>> changeAndFindBest(List<List<String>> simpleList,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> wearId,
            List<String> canChangeS,
            Map<String, Object> jsonMap,
            List<String> edgeChooseBS,
            Map<String, Map<String, String>> elecPosition,
            Map<String, Object> branchLength,
            List<List<Integer>> connection,
            Map<String, List<String>> multiLoopInfos,
            Map<String, String> pointMap, List<Map<String, Object>> findBestPre, List<String> conformList)
            throws Exception {
        FindBest findBest = new FindBest();
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        Boolean whetherOnLoop = caseInfo.get("loopcreate").toString().equals("true") ? true : false;
        System.out.println("一共需要计算方案：" + simpleList.size());
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper mapper = new ObjectMapper();
        // 检查生成的方案是否存在穿腔如果存在 将对应的闭环中 将打断成本最小的分支情况进行一个替换
        System.out.println("每个方案开始加s");
        List<Map<String, Object>> resultList = new ArrayList<>();
        // 创建Callable任务列表
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (List<String> strings : simpleList) {
            tasks.add(() -> {
                long startTime = System.currentTimeMillis();
                Map<String, Object> map = new HashMap<>();
                // if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                // break;
                // }
                List<String> serviceableStatue = strings.stream().collect(Collectors.toList());
                // for (int i = 0; i < serviceableStatue.size(); i++) {
                // if (serviceableStatue.get(i).equals("C") &&
                // canChangeS.contains(normList.get(i))) {
                // serviceableStatue.set(i, "S");
                // }
                // }

                List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                // 深拷贝
                Map<String, Object> threadLocalJsonMap = mapper.readValue(
                        mapper.writeValueAsString(jsonMap),
                        Map.class);
                threadLocalJsonMap.put("edges", serviceableEdge);

                Map<String, Double> breakCostMap = new HashMap<>();
                String projectCircuitInfoOutputRsult = projectCircuitInfoOutput
                        .projectCircuitInfoOutput(mapper.writeValueAsString(threadLocalJsonMap));
                Map<String, Object> objectMap = jsonToMap.TransJsonToMap(projectCircuitInfoOutputRsult);
                Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMap.get("projectCircuitInfo");

                Map<String, Object> costResultData = new HashMap<>();
                // 存入map
                costResultData.put("总成本", projectCircuitInfo.get("总成本"));
                costResultData.put("总长度", projectCircuitInfo.get("回路总长度"));
                costResultData.put("总重量", projectCircuitInfo.get("回路总重量"));
                List<Map<String, Object>> circuitInfo = (List<Map<String, Object>>) objectMap.get("circuitInfo");
                costResultData.put("circuitInfo", circuitInfo);
                map.put("成本", costResultData);
                map.put("serviceableEdges", serviceableEdge);
                map.put("serviceableStatue", serviceableStatue);

                Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) objectMap
                        .get("bundeleRelatedCircuitInfo");
                for (String s : bundeleRelatedCircuitInfo.keySet()) {
                    Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
                    Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                    breakCostMap.put(s, Double
                            .parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
                }
                // 对当前的情况进行一个检查 当存在闭环的状况 将当中最打断成本最小的进行打B 直到没有闭环的时候跳出循环
                boolean scrapOrNot = false;
                int maxLoopIterations = 100; // 防止死循环，最多打断100次
                while (scrapOrNot == false && maxLoopIterations-- > 0) {
                    serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                    List<List<String>> recognizeLoopList = recognizeLoopNew(serviceableEdge);
                    // 每一个闭环中存在一个穿腔的分支的 整个组成整个闭环的分支进行记录
                    List<String> recognizeLoopIdList = new ArrayList<>();
                    for (List<String> loop : recognizeLoopList) {
                        for (String s : loop) {
                            if (wearId.contains(s)) {
                                recognizeLoopIdList.addAll(loop);
                                break;
                            }
                        }
                    }

                    // 检查当前方案中是否存在需要处理的闭环
                    if (recognizeLoopIdList.size() != 0) {
                        // 将recognizeLoopIdList 里面分支打断成本最小的打断
                        String minCostKey = null;
                        List<String> keyList = findMinCostKey(recognizeLoopIdList, breakCostMap);
                        for (String s : keyList) {
                            // 改改B的改B
                            if (canChangeS.contains(s)) {
                                minCostKey = s;
                                break;
                            }
                        }
                        if (minCostKey == null) {
                            // 无法找到可打断分支，方案作废
                            scrapOrNot = true;
                            break;
                        }
                        serviceableStatue.set(normList.indexOf(minCostKey), "S");
                        if (!refreshCircuitInfo(serviceableStatue, edges, normList, threadLocalJsonMap,
                                projectCircuitInfoOutput, mapper, jsonToMap, costResultData, breakCostMap)) {
                            // 刷新失败，方案作废
                            scrapOrNot = true;
                            break;
                        }
                        map.put("成本", costResultData);
                        map.put("serviceableEdges", serviceableEdge);
                        map.put("serviceableStatue", serviceableStatue);
                    } else {
                        // 检查是否开启全局闭环消除
                        if (whetherOnLoop) {
                            int innerMaxIter = 100;
                            while (innerMaxIter-- > 0) {
                                serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                                List<List<String>> recognizeLoopListSon = recognizeLoopNew(serviceableEdge);
                                if (recognizeLoopListSon.size() == 0) {
                                    break;
                                } else {
                                    Set<String> son = new HashSet<>();
                                    for (List<String> loop : recognizeLoopListSon) {
                                        son.addAll(loop);
                                    }
                                    List<String> keyList = findMinCostKey(new ArrayList<>(son), breakCostMap);
                                    String minCostKey = null;
                                    for (String s : keyList) {
                                        if (canChangeS.contains(s)) {
                                            minCostKey = s;
                                            break;
                                        }
                                    }
                                    // 如果当前的方案中没有canChangeS，就选打断代价最小的任意分支
                                    if (minCostKey == null) {
                                        if (keyList.isEmpty()) {
                                            // 无可选分支，放弃
                                            scrapOrNot = true;
                                            break;
                                        }
                                        minCostKey = keyList.get(0);
                                    }
                                    serviceableStatue.set(normList.indexOf(minCostKey), "S");
                                    // 关键：打断后重新计算全图成本和 breakCostMap
                                    if (!refreshCircuitInfo(serviceableStatue, edges, normList, threadLocalJsonMap,
                                            projectCircuitInfoOutput, mapper, jsonToMap, costResultData,
                                            breakCostMap)) {
                                        scrapOrNot = true;
                                        break;
                                    }
                                }
                            }
                            if (scrapOrNot) {
                                break;
                            }
                        }
                        // 所有闭环已消除，跳出外层循环
                        map.put("成本", costResultData);
                        map.put("serviceableEdges", serviceableEdge);
                        map.put("serviceableStatue", serviceableStatue);
                        break;
                    }
                }
                if (scrapOrNot) {
                    // 方案无法消除闭环，作废
                    return null;
                }
                System.out.println("changeAndFindBest: 闭环检测结束耗时 " + (System.currentTimeMillis() - startTime) + " ms");
                return map;
            });
        }
        // 线程池提交任务
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Callable<Map<String, Object>> task : tasks) {
            Future<Map<String, Object>> submit = threadPool.submit(task);
            futures.add(submit);
        }
        // 获取线程池结果
        for (Future<Map<String, Object>> future : futures) {
            try {
                Map<String, Object> result = future.get(600, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    resultList.add(result);
                }
            } catch (Exception e) {
                // e.printStackTrace();
            }

        }
        // 每个方案进行计算
        // 加入上一代最优top3
        if (findBestPre != null) {
            for (int i = 0; i < 3; i++) {
                resultList.add(findBestPre.get(i));
            }
        }
        List<Map<String, Object>> topBeat = findBest.findBest(resultList, "成本", TopNumber);

        for (Map<String, Object> map : topBeat) {
            List<String> list = (List<String>) map.get("serviceableStatue");
            if (!containsList(list, WareHouseTop)) {
                WareHouseTop.add(list);
                TopCostDetail.add(map);
            }
        }
        return topBeat;
    }

    /**
     * @Description: List<String> id 在Map < String, Double>
     *               breakCostMap中每一个id作为一个key对应的 double最小的一个key
     * @input: ids id集合
     * @input: breakCostMap 所有分支的打断成本状况
     * @Return: 按照分支打断代价 对id进行一个排序
     */
    public List<String> findMinCostKey(List<String> ids, Map<String, Double> breakCostMap) {
        List<String> validIds = new ArrayList<>();
        for (String id : ids) {
            if (breakCostMap.containsKey(id)) {
                validIds.add(id);
            }
        }
        Collections.sort(validIds, Comparator.comparing(breakCostMap::get));
        return validIds;
    }

    public List<List<String>> recognizeLoopNew(List<Map<String, Object>> edges) {
        // 1. 收集C状态分支，建立"点-点" -> 边id双向映射
        Map<String, String> pairToEdgeId = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String code = edge.get("topologyStatusCode") != null
                    ? edge.get("topologyStatusCode").toString()
                    : "";
            if ("B".equalsIgnoreCase(code) || "S".equalsIgnoreCase(code)) {
                continue;
            }
            String start = (String) edge.get("startPointName");
            String end = (String) edge.get("endPointName");
            String edgeId = edge.get("id") != null ? edge.get("id").toString() : (start + "-" + end);
            pairToEdgeId.put(start + "-" + end, edgeId);
            pairToEdgeId.put(end + "-" + start, edgeId);
        }
        if (pairToEdgeId.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 节点去重并建立索引映射
        Set<String> pointSet = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            String code = edge.get("topologyStatusCode") != null
                    ? edge.get("topologyStatusCode").toString()
                    : "";
            if ("B".equalsIgnoreCase(code) || "S".equalsIgnoreCase(code)) {
                continue;
            }
            pointSet.add((String) edge.get("startPointName"));
            pointSet.add((String) edge.get("endPointName"));
        }
        List<String> pointList = new ArrayList<>(pointSet);
        Map<String, Integer> pointToIndex = new HashMap<>();
        for (int i = 0; i < pointList.size(); i++) {
            pointToIndex.put(pointList.get(i), i);
        }
        int n = pointList.size();

        // 3. 构建邻接表（并行数组：邻接点索引 + 对应边id）
        List<List<Integer>> adjNodes = new ArrayList<>(n);
        List<List<String>> adjEdgeIds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjNodes.add(new ArrayList<>());
            adjEdgeIds.add(new ArrayList<>());
        }
        for (Map<String, Object> edge : edges) {
            String code = edge.get("topologyStatusCode") != null
                    ? edge.get("topologyStatusCode").toString()
                    : "";
            if ("B".equalsIgnoreCase(code) || "S".equalsIgnoreCase(code)) {
                continue;
            }
            int u = pointToIndex.get((String) edge.get("startPointName"));
            int v = pointToIndex.get((String) edge.get("endPointName"));
            String eid = pairToEdgeId.get(pointList.get(u) + "-" + pointList.get(v));
            adjNodes.get(u).add(v);
            adjEdgeIds.get(u).add(eid);
            adjNodes.get(v).add(u);
            adjEdgeIds.get(v).add(eid);
        }

        // 4. DFS显式栈构建生成树，收集非树边
        // parent[i]: -1=未访问, -2=根节点, >=0=父节点索引
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        List<Integer> nonTreeU = new ArrayList<>();
        List<Integer> nonTreeV = new ArrayList<>();
        List<String> nonTreeEid = new ArrayList<>();
        int[][] stack = new int[n * 2][2];

        for (int start = 0; start < n; start++) {
            if (parent[start] != -1) {
                continue;
            }
            parent[start] = -2;
            int stackSize = 0;
            stack[stackSize][0] = start;
            stack[stackSize][1] = 0;
            stackSize++;

            while (stackSize > 0) {
                int[] top = stack[stackSize - 1];
                int u = top[0];
                int ni = top[1];
                List<Integer> neighbors = adjNodes.get(u);

                if (ni < neighbors.size()) {
                    top[1]++;
                    int v = neighbors.get(ni);
                    int p = parent[u];

                    if (parent[v] == -1) {
                        parent[v] = u;
                        stack[stackSize][0] = v;
                        stack[stackSize][1] = 0;
                        stackSize++;
                    } else if (v != p && p != -2) {
                        if (v < u) {
                            nonTreeU.add(v);
                            nonTreeV.add(u);
                            nonTreeEid.add(adjEdgeIds.get(u).get(ni));
                        }
                    }
                } else {
                    stackSize--;
                }
            }
        }

        // 5. 对每条非树边，通过LCA找到基础环并转换为边id列表
        List<List<String>> result = new ArrayList<>();
        Set<String> cycleFingerprint = new HashSet<>();

        for (int k = 0; k < nonTreeU.size(); k++) {
            int u = nonTreeU.get(k);
            int v = nonTreeV.get(k);

            // 收集u到根的所有祖先
            Set<Integer> uAncestors = new HashSet<>();
            int cur = u;
            while (cur >= 0) {
                uAncestors.add(cur);
                cur = (parent[cur] >= 0) ? parent[cur] : -1;
            }

            // 从v向上找LCA
            cur = v;
            int lca = -1;
            while (cur >= 0) {
                if (uAncestors.contains(cur)) {
                    lca = cur;
                    break;
                }
                cur = (parent[cur] >= 0) ? parent[cur] : -1;
            }
            if (lca == -1) {
                continue;
            }

            // 构建环节点序列: u -> ... -> lca -> ... -> v
            List<Integer> cycleNodes = new ArrayList<>();
            cur = u;
            while (cur != lca) {
                cycleNodes.add(cur);
                cur = parent[cur];
            }
            cycleNodes.add(lca);

            List<Integer> vToLca = new ArrayList<>();
            cur = v;
            while (cur != lca) {
                vToLca.add(cur);
                cur = parent[cur];
            }
            for (int i = vToLca.size() - 1; i >= 0; i--) {
                cycleNodes.add(vToLca.get(i));
            }

            // 节点序列转换为边id列表
            List<String> cycleEdgeIds = new ArrayList<>();
            int m = cycleNodes.size();
            for (int i = 0; i < m; i++) {
                int from = cycleNodes.get(i);
                int to = cycleNodes.get((i + 1) % m);
                String key = pointList.get(from) + "-" + pointList.get(to);
                String eid = pairToEdgeId.get(key);
                if (eid != null && !cycleEdgeIds.contains(eid)) {
                    cycleEdgeIds.add(eid);
                }
            }

            // 指纹去重
            if (cycleEdgeIds.size() >= 2) {
                List<String> sorted = new ArrayList<>(cycleEdgeIds);
                Collections.sort(sorted);
                String fp = String.join(",", sorted);
                if (cycleFingerprint.add(fp)) {
                    result.add(cycleEdgeIds);
                }
            }
        }
        return result;
    }

    /**
     * @param costResultData 输出的成本信息（会被覆盖）
     * @param breakCostMap   输出的分支打断代价（会被覆盖）
     * @Description: 打断一个分支后，重新计算全图成本和各分支的打断代价
     *               （必须重新调用，不能简单累加原始 breakCostMap 的增量，
     *               因为打断后回路走线、导线选型、连接器配置都变化，
     *               其他分支的打断代价是相对"新图状态"的）
     * @input: serviceableStatue 当前分支状态集合（已应用本次打断）
     * @input: edges 分支模板
     * @input: normList 分支id集合
     * @input: threadLocalJsonMap 线程本地 jsonMap（避免每次重新反序列化大对象）
     * @input: projectCircuitInfoOutput 整车成本计算输出器
     * @input: mapper JSON序列化器
     * @input: jsonToMap JSON反序列化器
     * @Return: 是否计算成功
     * @Complexity: O(V + E)，主要由 projectCircuitInfoOutput 内部计算决定
     */
    private boolean refreshCircuitInfo(List<String> serviceableStatue,
            List<Map<String, Object>> edges,
            List<String> normList,
            Map<String, Object> threadLocalJsonMap,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            ObjectMapper mapper,
            JsonToMap jsonToMap,
            Map<String, Object> costResultData,
            Map<String, Double> breakCostMap) {
        try {
            List<Map<String, Object>> newServiceableEdge = createNewEdges(serviceableStatue, edges, normList);
            threadLocalJsonMap.put("edges", newServiceableEdge);
            String result = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(mapper.writeValueAsString(threadLocalJsonMap));
            Map<String, Object> obj = jsonToMap.TransJsonToMap(result);
            Map<String, Object> info = (Map<String, Object>) obj.get("projectCircuitInfo");
            List<Map<String, Object>> circuitInfo = (List<Map<String, Object>>) obj.get("circuitInfo");
            costResultData.put("总成本", info.get("总成本"));
            costResultData.put("总长度", info.get("回路总长度"));
            costResultData.put("总重量", info.get("回路总重量"));
            costResultData.put("circuitInfo", circuitInfo);
            Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) obj
                    .get("bundeleRelatedCircuitInfo");
            breakCostMap.clear();
            for (String s : bundeleRelatedCircuitInfo.keySet()) {
                Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
                Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                breakCostMap.put(s, Double.parseDouble(
                        edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
            }
            return true;
        } catch (Exception e) {
            System.out.println("refreshCircuitInfo 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * @Description: 遗传算法主体(单代)。
     *               分两阶段变异:
     *               ① 复用 generateInitialSchemes(同时变异 + 按概率变异)以 initialScheme
     *               为基准生成阶段一方案;
     *               ② 以 TopDetail(上一代最优)为父本,两两配对交叉变异,每对各取 1 个 mutation 叠加,生成阶段二方案;
     *               两阶段合并后做 4 关 + checkFirstOption 约束 + WareHouse 去重,再注入上一代 top
     *               30%(精英保留),
     *               最后 AI 预测成本并取 TopNumber 方案返回。
     *               整体保证:新一代最优成本 ≤ 上一代最优成本(单调不增)。
     * @input: 13+ 参数,分别为 generateInitialSchemes 和 predictAndFindBest 的全部入参
     * @Return: 新一代 TopNumber 个最优方案(含 serviceableStatue / 成本 / serviceableEdges)
     * @Complexity: O(P1 + P2 + E) 阶段一/二生成 + 精英注入;P1 受 generateInitialSchemes
     *              内部线程池限制。
     */
    public List<Map<String, Object>> hybridization(
            List<Map<String, Object>> edges,
            Set<String> canBreakToBSet,
            List<String> initialScheme,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            int bestBreakCount,
            Map<String, Double> breakCostMap,
            List<String> normList,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            Map<String, Object> jsonMap,
            List<String> edgeChooseBS,
            Map<String, Map<String, String>> elecPosition,
            Map<String, Object> branchLength,
            List<List<Integer>> connection,
            Map<String, List<String>> multiLoopInfos,
            Map<String, String> pointMap, int hybridizationNumber,
            Map<String, Set<String>> togetherBCIndex,
            Map<String, Set<String>> chooseOneIndex,
            Map<String, Set<String>> mutexConflictIndex,
            Set<String> canChangeSSet) throws Exception {
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();

        // 0) 兜底:没有上一代父本,直接退出
        if (TopDetail == null || TopDetail.isEmpty()) {
            return null;
        }

        // 1) 提取父本状态列表(从上一代 TopDetail 中取出 serviceableStatue)
        List<List<String>> parentStatues = new ArrayList<>();
        for (Map<String, Object> detail : TopDetail) {
            List<String> statue = (List<String>) detail.get("serviceableStatue");
            if (statue != null && initialScheme != null && statue.size() == initialScheme.size()) {
                parentStatues.add(statue);
            }
        }
        if (parentStatues.isEmpty()) {
            return null;
        }

        // 2) 阶段一:对 TopDetail 中每个父本都调用 generateInitialSchemes 做变异
        // 早停优化:累计够 perGenTarget 个就跳出 for,不再遍历剩余父本
        // perGenTarget 至少等于 LessRandomSamleNumber，避免 generateInitialSchemes 内部生成过多浪费
        final int perGenTarget = Math.max(HybridizationLessRandomSamleNumber, LessRandomSamleNumber);
        long phase1Time = System.currentTimeMillis();
        List<List<String>> phase1 = new ArrayList<>();
        for (List<String> parent : parentStatues) {
            if (phase1.size() >= perGenTarget) {
                System.out.println("[hybridization] 阶段一早停:累计 " + phase1.size());
                break;
            }
            List<List<String>> variants = generateInitialSchemes(
                    edges, canBreakToBSet, parent, appPositions, eleclection,
                    bestBreakCount, breakCostMap, normList,
                    mutexMap, chooseOneList, togetherBCList,
                    togetherBCIndex, chooseOneIndex, mutexConflictIndex,
                    canChangeSSet);
            phase1.addAll(variants);
        }
        System.out.println("[hybridization] 阶段一累计 " + phase1.size() + " 个有效方案,耗时 "
                + (System.currentTimeMillis() - phase1Time) + " ms");

        // 3) 阶段二:交叉变异(以 TopDetail 父本为基准,两两配对)
        // 传递父本成本用于加权轮盘赌选择
        long phase2Time = System.currentTimeMillis();
        int crossTarget = Math.max(perGenTarget, parentStatues.size() * 2);
        List<Double> parentCosts = new ArrayList<>();
        for (Map<String, Object> detail : TopDetail) {
            Map<String, Object> costMap = (Map<String, Object>) detail.get("成本");
            if (costMap != null && costMap.get("总成本") != null) {
                parentCosts.add(Double.parseDouble(costMap.get("总成本").toString()));
            } else {
                parentCosts.add(Double.MAX_VALUE);
            }
        }
        List<List<String>> phase2Raw = crossoverMutation(
                parentStatues, initialScheme, normList, crossTarget, parentCosts);
        System.out.println("[hybridization] 阶段二原始生成 " + phase2Raw.size() + " 个,耗时 "
                + (System.currentTimeMillis() - phase2Time) + " ms");

        // 4) 阶段二约束检查 + 入仓。交叉变异产生的子代未经过约束感知处理，
        // 这里先做 togetherBC展开 + mutex校验 + chooseOne传播，再入仓。
        long phase2CheckTime = System.currentTimeMillis();
        List<List<String>> phase2Valid = new ArrayList<>();
        // 构建 baseStatusMap（用于 chooseOne 传播判断原本状态）
        Map<String, String> baseStatusMapForPhase2 = new LinkedHashMap<>();
        for (int i = 0; i < normList.size(); i++) {
            baseStatusMapForPhase2.put(normList.get(i), initialScheme.get(i));
        }
        Random phase2Rnd = new Random(seedCounter.incrementAndGet());
        for (List<String> child : phase2Raw) {
            // 1) 转为 statusMap
            Map<String, String> statusMap = new LinkedHashMap<>();
            for (int i = 0; i < normList.size(); i++) {
                statusMap.put(normList.get(i), child.get(i));
            }
            // 2) 提取相对 baseScheme 的 B 变更，做 togetherBC 展开 + mutex 校验
            Set<String> breakSet = new LinkedHashSet<>();
            for (int i = 0; i < child.size(); i++) {
                if ("B".equals(child.get(i)) && !"B".equals(initialScheme.get(i))) {
                    breakSet.add(normList.get(i));
                }
            }
            Set<String> expanded = expandAndValidateBreaks(
                    breakSet, baseStatusMapForPhase2, togetherBCIndex, mutexConflictIndex);
            if (expanded == null)
                continue;
            // 重建 statusMap（应用 togetherBC 展开）
            statusMap = new LinkedHashMap<>(baseStatusMapForPhase2);
            for (String id : expanded) {
                statusMap.put(id, "B");
            }
            // 3) chooseOne 传播
            Map<String, String> propagated = applyChooseOnePropagation(
                    statusMap, baseStatusMapForPhase2, chooseOneList, breakCostMap,
                    canBreakToBSet, canChangeSSet, phase2Rnd);
            if (propagated == null)
                continue;
            // 4) 重建 fullStatus
            List<String> adjusted = new ArrayList<>();
            for (String id : normList) {
                adjusted.add(propagated.getOrDefault(id, "C"));
            }
            // 5) 快速约束校验（togetherBC/mutex/chooseOne）
            if (!checkConstraintsFast(adjusted, normList, mutexMap, chooseOneList, togetherBCList)) {
                continue;
            }
            // 6) 入仓（含拓扑检查）
            if (validateAndAddToWarehouse(adjusted, edges, normList, appPositions, eleclection,
                    mutexMap, chooseOneList, togetherBCList)) {
                phase2Valid.add(adjusted);
            }
        }
        System.out.println("[hybridization] 阶段二通过约束 " + phase2Valid.size() + " 个,耗时 "
                + (System.currentTimeMillis() - phase2CheckTime) + " ms");

        // 5) 合并两阶段方案(阶段一已入仓,阶段二已入仓,这里只做候选池聚合)
        List<List<String>> allSchemes = new ArrayList<>(phase1.size() + phase2Valid.size());
        allSchemes.addAll(phase1);
        allSchemes.addAll(phase2Valid);

        // 6) 注入上一代 top 30%(精英保留,确保新一代最优 ≤ 上一代最优)
        int eliteCount = Math.max(1, (int) Math.ceil(TopDetail.size() * 0.3));
        int eliteAdded = 0;
        for (int i = 0; i < eliteCount && i < TopDetail.size(); i++) {
            List<String> eliteStatue = (List<String>) TopDetail.get(i).get("serviceableStatue");
            if (eliteStatue != null && eliteStatue.size() == initialScheme.size()) {
                allSchemes.add(eliteStatue);
                eliteAdded++;
            }
        }

        // 6.5) 补偿:如果两阶段+精英仍不够每代目标,对 TopDetail 每个父本再调 generateInitialSchemes 补充
        // 防止极端情况下方案数不足以让 AI 选出 TopNumber 个好样本
        // perGenTarget 沿用阶段一里的 final 声明
        final int maxCompensationRounds = AutoCompleteNumber;
        int compensationRound = 0;
        while (allSchemes.size() < perGenTarget && compensationRound < maxCompensationRounds) {
            compensationRound++;
            int beforeCount = allSchemes.size();
            for (List<String> parent : parentStatues) {
                if (allSchemes.size() >= perGenTarget) {
                    break;
                }
                List<List<String>> more = generateInitialSchemes(
                        edges, canBreakToBSet, parent, appPositions, eleclection,
                        bestBreakCount, breakCostMap, normList,
                        mutexMap, chooseOneList, togetherBCList,
                        togetherBCIndex, chooseOneIndex, mutexConflictIndex,
                        canChangeSSet);
                allSchemes.addAll(more);
            }
            int added = allSchemes.size() - beforeCount;
            if (added == 0) {
                // 本轮无新增方案，再尝试也无效，提前退出
                break;
            }
            System.out.println("[hybridization] 补偿第 " + compensationRound + " 轮:新增 " + added
                    + " 个");
        }

        if (allSchemes.isEmpty()) {
            return null;
        }

        // 7) AI 预测 + 排序取 TopNumber
        // findBestPre 传 null 避免 predictAndFindBest 内部再注入 10%(精英由本方法统一控制)
        long predTime = System.currentTimeMillis();
        List<Map<String, Object>> mapList = predictAndFindBest(allSchemes, edges, normList, jsonMap,
                edgeChooseBS, elecPosition, branchLength, connection,
                multiLoopInfos, pointMap, null);
        long findBestTimeMs = System.currentTimeMillis() - predTime;
        System.out.println("预测" + allSchemes.size() + "个样本成本耗时：" + findBestTimeMs);
//        // 记录迭代统计到Excel
//        int generatedCount = allSchemes.size();
//        int aiFilteredCount = 0;
//        long filterTimeMs = 0;
//        ObjectMapper mapper = new ObjectMapper();
//        JsonToMap jsonToMap = new JsonToMap();
//        if (mapList != null && !mapList.isEmpty()) {
//            Map<String, Object> bestResult = mapList.get(0);
//            Map<String, Object> costMap = (Map<String, Object>) bestResult.get("成本");
//            // 计算每轮迭代的最优成本，加到excel预测成本的后一列
//            List<String> serviceableStatue = (List<String>) bestResult.get("serviceableStatue");
//            List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
//            Map<String, Object> threadLocalJsonMap = mapper.readValue(
//                    mapper.writeValueAsString(jsonMap),
//                    Map.class);
//            threadLocalJsonMap.put("edges", serviceableEdge);
//            String betweenoptimizeInterfacesresult = null;
//            try {
//                betweenoptimizeInterfacesresult = projectCircuitInfoOutput
//                        .projectCircuitInfoOutput(mapper.writeValueAsString(jsonMap));
//            } catch (Exception e) {
//                return TopDetail;
//            }
//            Map<String, Object> betweenobjectMapresult = jsonToMap.TransJsonToMap(betweenoptimizeInterfacesresult);
//            Map<String, Object> betweenprojectCircuitInfo = (Map<String, Object>) betweenobjectMapresult
//                    .get("projectCircuitInfo");
//            Double betweencurrentalCost = (Double) betweenprojectCircuitInfo.get("总成本");
//            if (costMap != null) {
//                double bestCost = Double.parseDouble(costMap.get("总成本").toString());
//                double bestWeight = Double.parseDouble(costMap.get("总重量").toString());
//                double bestLength = Double.parseDouble(costMap.get("总长度").toString());
//                String excelPath = "F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\iteration_stats_"
//                        + "testAItrue"
//                        + ".xlsx";
//                recordIterationStatsToExcel(
//                        hybridizationNumber, generatedCount, aiFilteredCount, filterTimeMs,
//                        bestCost, bestWeight, bestLength, findBestTimeMs, excelPath, betweencurrentalCost);
//            }
//        }
        return mapList;
    }

    /**
     * 记录迭代统计数据到Excel表格
     * 每轮迭代一行，表头：迭代轮次、生成样本数、AI过滤后样本数、过滤耗时、最优成本、最优重量、最优长度、找最优耗时
     * 每轮之间空两行
     *
     * @param iterationRound  迭代轮次
     * @param generatedCount  生成样本数
     * @param aiFilteredCount AI过滤后样本数
     * @param filterTimeMs    过滤耗时(毫秒)
     * @param bestCost        最优总成本
     * @param bestWeight      最优总重量
     * @param bestLength      最优总长度
     * @param findBestTimeMs  找最优耗时(毫秒)
     * @param excelFilePath   Excel文件路径
     */
    public void recordIterationStatsToExcel(
            int iterationRound,
            int generatedCount,
            int aiFilteredCount,
            long filterTimeMs,
            double bestCost,
            double bestWeight,
            double bestLength,
            long findBestTimeMs,
            String excelFilePath, Double betweencurrentalCost) {
        try {
            org.apache.poi.ss.usermodel.Workbook workbook;
            org.apache.poi.ss.usermodel.Sheet sheet;
            java.io.File file = new java.io.File(excelFilePath);

            // 如果文件已存在，读取它；否则新建
            if (file.exists()) {
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(fis);
                fis.close();
                if (workbook.getNumberOfSheets() > 0) {
                    sheet = workbook.getSheetAt(0);
                } else {
                    sheet = workbook.createSheet("迭代统计");
                }
            } else {
                workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                sheet = workbook.createSheet("迭代统计");
                // 写入表头
                org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                String[] headers = { "迭代轮次", "每代生成样本数", "AI过滤后样本数", "过滤耗时(ms)",
                        "预测成本", "真实成本", "最优重量", "最优长度", "找最优耗时(ms)" };
                for (int i = 0; i < headers.length; i++) {
                    org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                }
            }

            // 找到最后一行，空两行后写入新数据
            int lastRowNum = sheet.getLastRowNum();
            int nextRowNum = lastRowNum + 3; // 空两行
            org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(nextRowNum);

            // 写入数据
            int col = 0;
            dataRow.createCell(col++).setCellValue(iterationRound);
            dataRow.createCell(col++).setCellValue(generatedCount);
            dataRow.createCell(col++).setCellValue(aiFilteredCount);
            dataRow.createCell(col++).setCellValue(filterTimeMs);
            dataRow.createCell(col++).setCellValue(bestCost);
            dataRow.createCell(col++).setCellValue(betweencurrentalCost);
            dataRow.createCell(col++).setCellValue(bestWeight);
            dataRow.createCell(col++).setCellValue(bestLength);
            dataRow.createCell(col++).setCellValue(findBestTimeMs);

            // 自动调整列宽
            for (int i = 0; i < 9; i++) {
                sheet.autoSizeColumn(i);
            }

            // 写入文件
            java.io.FileOutputStream fos = new java.io.FileOutputStream(excelFilePath);
            workbook.write(fos);
            fos.close();
            workbook.close();

            System.out.println("迭代统计已写入Excel: " + excelFilePath);
        } catch (Exception e) {
            System.err.println("写入Excel失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * @Description: 交叉变异。两两配对父本（成本低的优先），各取 1-2 个 mutation 位置叠加到子代。
     *               生成 2-3 mutation 子代方案集合，保留 S 状态不变。
     *               父本均来自上一代 TopDetail，其"突变位置"=相对 baseScheme 被改 B 的位置。
     * @input: parentStatues 父本状态列表(长度 = normList.size())
     * @input: baseScheme 基准状态(initialScheme)
     * @input: normList 分支 id 按顺序排列
     * @input: targetCount 目标生成数(实际可能因去重略少)
     * @input: parentCosts 父本对应的成本列表(用于加权轮盘赌，成本越低越容易被选中)
     * @Return: 子代状态列表(已去重 ， 未做约束检查 ， 由调用方统一校验)
     */
    private List<List<String>> crossoverMutation(
            List<List<String>> parentStatues,
            List<String> baseScheme,
            List<String> normList,
            int targetCount,
            List<Double> parentCosts) {
        List<List<String>> result = new ArrayList<>();
        if (parentStatues == null || parentStatues.size() < 2) {
            return result;
        }
        if (baseScheme == null || normList == null || baseScheme.size() != normList.size()) {
            return result;
        }
        if (targetCount <= 0) {
            return result;
        }

        Random rnd = new Random(seedCounter.incrementAndGet());
        Set<String> seen = new HashSet<>();
        int maxAttempts = targetCount * 10 + 100;
        int attempts = 0;
        final int n = baseScheme.size();
        final int parentCount = parentStatues.size();

        // 构建轮盘赌权重：成本越低，权重越高
        double[] weights = buildRouletteWeights(parentCosts);

        while (result.size() < targetCount && attempts < maxAttempts) {
            attempts++;
            // 轮盘赌选择两个不同的父本
            int idx1 = weightedRandomSelect(weights, rnd);
            int idx2 = weightedRandomSelect(weights, rnd);
            if (idx1 == idx2) {
                idx2 = (idx1 + 1 + rnd.nextInt(parentCount - 1)) % parentCount;
            }
            List<String> p1 = parentStatues.get(idx1);
            List<String> p2 = parentStatues.get(idx2);
            if (p1.size() != n || p2.size() != n) {
                continue;
            }

            // 提取每个父本相对 base 的 mutation 位置（被改 B 的位置，排除 S 状态）
            List<Integer> muts1 = new ArrayList<>();
            List<Integer> muts2 = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                // S 状态的分支保持不动，不参与交叉
                if ("S".equals(baseScheme.get(i)) || "S".equals(p1.get(i)) || "S".equals(p2.get(i))) {
                    continue;
                }
                if (!"B".equals(baseScheme.get(i)) && "B".equals(p1.get(i))) {
                    muts1.add(i);
                }
                if (!"B".equals(baseScheme.get(i)) && "B".equals(p2.get(i))) {
                    muts2.add(i);
                }
            }
            if (muts1.isEmpty() || muts2.isEmpty()) {
                continue;
            }

            // 各取 1-2 个 mutation（50% 概率取 2 个，增加多样性）
            int take1 = Math.min(rnd.nextDouble() < 0.5 ? 2 : 1, muts1.size());
            int take2 = Math.min(rnd.nextDouble() < 0.5 ? 2 : 1, muts2.size());

            // Fisher-Yates 部分洗牌取前 N 个
            List<Integer> picked1 = pickRandomN(muts1, take1, rnd);
            List<Integer> picked2 = pickRandomN(muts2, take2, rnd);

            // 合并去重
            Set<Integer> allMuts = new LinkedHashSet<>(picked1);
            allMuts.addAll(picked2);

            // 以 p1 为基底，叠加 p2 的 mutation
            List<String> child = new ArrayList<>(p1);
            for (int pos : allMuts) {
                child.set(pos, "B");
            }

            // 去重签名
            String sig = String.join(",", child);
            if (seen.add(sig)) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * 从列表中随机选择 n 个不重复元素（Fisher-Yates 部分洗牌）
     */
    private List<Integer> pickRandomN(List<Integer> list, int n, Random rnd) {
        List<Integer> pool = new ArrayList<>(list);
        int size = pool.size();
        n = Math.min(n, size);
        for (int i = 0; i < n; i++) {
            int j = i + rnd.nextInt(size - i);
            int tmp = pool.get(i);
            pool.set(i, pool.get(j));
            pool.set(j, tmp);
        }
        return pool.subList(0, n);
    }

    /**
     * 构建轮盘赌权重：成本越低，权重越高（用倒数转换）
     * 所有父本成本相同时退化为均匀分布
     */
    private double[] buildRouletteWeights(List<Double> costs) {
        int n = costs.size();
        double[] weights = new double[n];
        if (costs == null || costs.isEmpty()) {
            Arrays.fill(weights, 1.0);
            return weights;
        }
        double minCost = Double.MAX_VALUE;
        for (double c : costs) {
            if (c < minCost)
                minCost = c;
        }
        double total = 0;
        for (int i = 0; i < n; i++) {
            double c = costs.get(i);
            if (c <= 0)
                c = minCost; // 防御
            weights[i] = minCost / c; // 成本越低权重越高
            total += weights[i];
        }
        if (total <= 0) {
            Arrays.fill(weights, 1.0);
            return weights;
        }
        // 归一化
        for (int i = 0; i < n; i++) {
            weights[i] /= total;
        }
        return weights;
    }

    /**
     * 加权随机选择（轮盘赌），返回选中的索引
     */
    private int weightedRandomSelect(double[] weights, Random rnd) {
        double dart = rnd.nextDouble();
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (dart <= cumulative) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /**
     * @Description: 统计方案相对 base 改 B 的位置数(即该方案相对 base 的 mutation 数量)。
     *               用于日志展示阶段一基准的"进化深度"。
     */
    private int countMutation(List<String> scheme, List<String> base) {
        if (scheme == null || base == null || scheme.size() != base.size()) {
            return 0;
        }
        int cnt = 0;
        for (int i = 0; i < scheme.size(); i++) {
            if (!"B".equals(base.get(i)) && "B".equals(scheme.get(i))) {
                cnt++;
            }
        }
        return cnt;
    }

    /**
     * @Description: 公共约束检查 + 入仓。4 关 + checkFirstOption + WareHouse 去重,全过才入仓并返回
     *               true。
     *               true。
     *               阶段二(交叉变异)产生的原始子代通过本方法统一校验入仓。
     * @input: fullStatus 完整状态列表(长度 = normList.size(),顺序与 normList 一致)
     * @Return: 是否通过校验并成功入仓
     * @Complexity: O(E) E = edges.size()
     */
    private boolean validateAndAddToWarehouse(
            List<String> fullStatus,
            List<Map<String, Object>> originalEdges,
            List<String> normList,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList) {
        if (fullStatus == null || originalEdges == null || normList == null
                || fullStatus.size() != normList.size()) {
            return false;
        }

        // 1) 拓扑连通性+用电器检查（快速预过滤）
        List<Map<String, Object>> copyEdges = createNewEdges(fullStatus, originalEdges, normList);
        if (!checkFirstOption(copyEdges, appPositions, eleclection)) {
            return false;
        }

        // 2) 完整约束检查(回路/互斥/组团)
        Boolean bool = checkFirstOption(normList, fullStatus, copyEdges, appPositions, eleclection,
                mutexMap, chooseOneList, togetherBCList);
        if (!bool) {
            return false;
        }

        // 4) WareHouse 去重(线程安全)
        synchronized (WareHouse) {
            if (containsList(fullStatus, WareHouse)) {
                return false;
            }
            WareHouse.add(fullStatus);
            WAREHOUSE_KEYS.add(String.join(",", fullStatus));
        }
        return true;
    }

    // 找到所有同电器对应的位置点
    public Map<String, String> getEleclection(List<Map<String, String>> mapList) {
        Map<String, String> resultMap = new HashMap<>();
        for (Map<String, String> stringMap : mapList) {
            String result = "";
            if (stringMap.get("unregularPointName") != null) {
                result = stringMap.get("unregularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") != null) {
                result = stringMap.get("regularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") == null) {
                result = null;
            }

            resultMap.put(stringMap.get("appName"), result);

        }
        // System.out.println("从txt中读取到的用电器，经过位置判断后共计"+resultList.size()+"个");
        return resultMap;
    }

    /**
     * @Description: 按照导线商务单位价降序排序
     * @input: originalMap 从excel读取到的导线选型对应的数据
     * @Return: 按照从高到低排序后的map
     */
    public Map<String, Map<String, String>> sortMapByInnerCostValue(Map<String, Map<String, String>> originalMap) {
        List<Map.Entry<String, Map<String, String>>> entryList = new ArrayList<>(originalMap.entrySet());

        entryList.sort((entry1, entry2) -> {
            String costValue1 = entry1.getValue().get("导线单位商务价（元/米）");
            String costValue2 = entry2.getValue().get("导线单位商务价（元/米）");
            return Double.compare(Double.parseDouble(costValue2), Double.parseDouble(costValue1));
        });

        return entryList.stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new));
    }

    /**
     * @Description: 在理想条件下 按照打断代价从高到低排序
     * @input: originalMap 理想条件下 按照打断代价
     * @Return: 按照从高到低排序后的map
     */
    public Map<String, Double> sortMapByDoubleValue(Map<String, Double> originalMap) {
        // 将Map的键值对转换为List
        List<Map.Entry<String, Double>> entryList = new ArrayList<>(originalMap.entrySet());

        // 对List进行排序，按照Double值从小到大排序
        entryList.sort((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()));

        // 将排序后的List转换回Map，并保持插入顺序（使用LinkedHashMap）
        return entryList.stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new));
    }

    /**
     * @Description: 根据打断代价从高到低排序后的Map，对可打断为B的分支分配被打断概率。
     *               打断代价越低 → 概率越高（成本增加少，容易改B）；
     *               打断代价越高 → 概率越低（成本增加多，不易改B）。
     * @input: sortedBreakCostMap 按打断代价从高到低排序的分支id->打断代价 LinkedHashMap
     *         canBreakToBSet 允许从C打断为B的分支id集合
     *         maxProbability 单条分支被打断的最大概率上限，默认0.9（保留一定随机性，避免100%必中）
     *         weightFactor 整体概率衰减因子，范围(0,1]，越小越平均，默认0.7
     * @Return: 允许打断的分支id -> 被打断概率（0~maxProbability之间），按概率从高到低排序
     * @Complexity: O(n)，n为可打断分支数
     */
    public Map<String, Double> calcBreakProbabilityByCost(
            Map<String, Double> sortedBreakCostMap,
            Set<String> canBreakToBSet,
            double maxProbability,
            double weightFactor) {

        // 参数校验
        if (sortedBreakCostMap == null || sortedBreakCostMap.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (canBreakToBSet == null || canBreakToBSet.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (maxProbability <= 0 || maxProbability > 1) {
            maxProbability = 0.9;
        }
        if (weightFactor <= 0 || weightFactor > 1) {
            weightFactor = 0.7;
        }

        // 1. 提取可打断为B的分支及其代价
        Map<String, Double> candidateMap = new LinkedHashMap<>();
        double minCost = Double.MAX_VALUE;
        double maxCost = -Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : sortedBreakCostMap.entrySet()) {
            String edgeId = entry.getKey();
            if (canBreakToBSet.contains(edgeId)) {
                double cost = entry.getValue() == null ? 0.0 : entry.getValue();
                candidateMap.put(edgeId, cost);
                if (cost < minCost) {
                    minCost = cost;
                }
                if (cost > maxCost) {
                    maxCost = cost;
                }
            }
        }
        if (candidateMap.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // 2. 线性归一化反向计算概率
        // 归一化值 norm = (cost - minCost) / (maxCost - minCost + 0.0001)，范围[0,1]
        // 概率 = (1 - norm) * weightFactor，再裁剪到 [minProb, maxProbability]
        // 注：sortedBreakCostMap是按代价从高到低排的，所以 LinkedHashMap 的遍历顺序就是代价从高到低
        double costRange = (maxCost - minCost) + 0.0001; // 加小数防除零
        double minProb = (1.0 - weightFactor) * maxProbability; // 最低概率
        // 使用按概率从高到低排序的 LinkedHashMap 返回
        Map<String, Double> probabilityMapDesc = new LinkedHashMap<>();
        List<Map.Entry<String, Double>> sortedByProbDesc = new ArrayList<>();
        for (Map.Entry<String, Double> entry : candidateMap.entrySet()) {
            double norm = (entry.getValue() - minCost) / costRange; // 0=最低代价，1=最高代价
            double prob = (1.0 - norm) * weightFactor * maxProbability + minProb;
            // 防御性裁剪
            if (prob < 0) {
                prob = 0;
            }
            if (prob > 1) {
                prob = 1;
            }
            sortedByProbDesc.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), prob));
        }
        // 按概率从高到低排序
        sortedByProbDesc.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> entry : sortedByProbDesc) {
            probabilityMapDesc.put(entry.getKey(), entry.getValue());
        }
        return probabilityMapDesc;
    }

    /**
     * @Description: 根据分支打断概率表，按概率随机抽样一条分支
     * @input: probabilityMap 分支id -> 被打断概率（0~1）
     *         random 可选外部传入的Random实例；为null则新建一个
     * @Return: 抽中的分支id；若概率表为空返回null
     * @Complexity: O(n)
     */
    public String sampleByBreakProbability(Map<String, Double> probabilityMap, Random random) {
        if (probabilityMap == null || probabilityMap.isEmpty()) {
            return null;
        }
        Random rnd = (random == null) ? new Random() : random;
        // 抽样策略：对每条分支独立掷一次骰子，命中概率为prob；返回第一个命中的
        // 这样能保证长尾里的小概率分支也有机会被抽到
        List<String> keyList = new ArrayList<>(probabilityMap.keySet());
        for (String edgeId : keyList) {
            double prob = probabilityMap.get(edgeId);
            if (prob > 0 && rnd.nextDouble() < prob) {
                return edgeId;
            }
        }
        // 全部未命中则降级到均匀随机抽一条
        return keyList.get(rnd.nextInt(keyList.size()));
    }

    /**
     * @Description: sampleByBreakProbability 的便利重载，内部新建Random
     */
    public String sampleByBreakProbability(Map<String, Double> probabilityMap) {
        return sampleByBreakProbability(probabilityMap, null);
    }

    /**
     * 约束感知的打断展开：给定一组要打断的分支，先展开 togetherBC（同组必须一起变），
     * 再快速校验互斥约束（每对互斥组至少一方有B），全部通过则返回展开后的完整打断集合。
     * 若约束冲突则返回 null。
     * <p>
     * 注意：chooseOne 约束（每组最多一个C）在只添加B的情况下自动满足，无需额外检查。
     */
    private Set<String> expandAndValidateBreaks(
            Set<String> breakSet,
            Map<String, String> baseStatusMap,
            Map<String, Set<String>> togetherBCIndex,
            Map<String, Set<String>> mutexConflictIndex) {
        // 1) togetherBC 展开：同组分支必须一起变
        Set<String> expanded = new LinkedHashSet<>(breakSet);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> toAdd = new LinkedHashSet<>();
            for (String id : expanded) {
                Set<String> group = togetherBCIndex.get(id);
                if (group != null) {
                    for (String gid : group) {
                        if (!expanded.contains(gid)) {
                            toAdd.add(gid);
                            changed = true;
                        }
                    }
                }
            }
            expanded.addAll(toAdd);
        }

        // 2) 构建临时状态：baseStatusMap + expanded打断
        Map<String, String> tempStatus = new LinkedHashMap<>(baseStatusMap);
        for (String id : expanded) {
            tempStatus.put(id, "B");
        }

        // 3) 快速检查 mutex：每对互斥组至少一方有B
        Set<Integer> checkedMutex = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : mutexConflictIndex.entrySet()) {
            String myId = entry.getKey();
            Set<String> conflictIds = entry.getValue();
            // 用冲突方集合的 identityHashCode 去重（每对互斥组只检查一次）
            int pairKey = System.identityHashCode(conflictIds);
            if (!checkedMutex.add(pairKey)) {
                continue;
            }
            // 检查双方：myId所在组 和 conflictIds所在组，至少一方有B
            boolean hasB = "B".equals(tempStatus.get(myId));
            if (!hasB) {
                for (String cid : conflictIds) {
                    if ("B".equals(tempStatus.get(cid))) {
                        hasB = true;
                        break;
                    }
                }
            }
            if (!hasB) {
                return null; // 双方都非B，违规
            }
        }

        return expanded;
    }

    /**
     * 多选一传播：确保每个 chooseOne 组恰好保留一个 C。
     * 规则：
     * 1) 已恰好一个C → 不动
     * 2) 0个C → 从可设为C的分支中随机选一个（加权打断代价），其余原本为C的分支 → 优先B，不可B则S
     * 3) 多个C → 保留一个，其余原本为C的分支 → 优先B，不可B则S
     * 只修改原本状态为C的分支，原本是B/S的保留原状。
     *
     * @param statusMap      当前状态（会被修改）
     * @param baseStatusMap  基准状态（用于判断原本是什么状态）
     * @param chooseOneList  多选一约束列表
     * @param breakCostMap   打断代价表（加权随机选C时使用）
     * @param canBreakToBSet 可打断为B的分支集合
     * @param canChangeSSet  可设为S的分支集合
     * @param rnd            随机数生成器
     * @return 成功则返回修改后的 statusMap，若某组无法选出C则返回 null
     */
    private Map<String, String> applyChooseOnePropagation(
            Map<String, String> statusMap,
            Map<String, String> baseStatusMap,
            List<Map<String, List<String>>> chooseOneList,
            Map<String, Double> breakCostMap,
            Set<String> canBreakToBSet,
            Set<String> canChangeSSet,
            Random rnd) {
        if (chooseOneList == null || chooseOneList.isEmpty()) {
            return statusMap;
        }
        for (Map<String, List<String>> group : chooseOneList) {
            Set<String> groupIds = group.keySet();
            // 只关注原本为C的分支
            List<String> originallyC = new ArrayList<>();
            for (String id : groupIds) {
                if ("C".equals(baseStatusMap.get(id))) {
                    originallyC.add(id);
                }
            }
            if (originallyC.isEmpty()) {
                continue; // 组内无原本C的分支，无需处理
            }

            // 当前状态中哪些是C
            List<String> currentC = new ArrayList<>();
            for (String id : originallyC) {
                if ("C".equals(statusMap.get(id))) {
                    currentC.add(id);
                }
            }

            if (currentC.size() == 1) {
                // 恰好一个C → 正确，不动
                continue;
            }

            if (currentC.isEmpty()) {
                // 0个C → 从可设为C的分支中选一个设为C
                List<String> canBeC = new ArrayList<>();
                for (String id : originallyC) {
                    if (canBreakToBSet != null && canBreakToBSet.contains(id)) {
                        canBeC.add(id);
                    }
                }
                if (canBeC.isEmpty()) {
                    // 原本C的分支都不可改 → 看有没有固定C的
                    boolean hasFixedC = false;
                    for (String id : originallyC) {
                        if ("C".equals(statusMap.get(id))) {
                            hasFixedC = true;
                            break;
                        }
                    }
                    if (!hasFixedC) {
                        return null; // 无法选出C
                    }
                    continue;
                }
                // 加权随机选一个为C（打断代价越低越容易被选中）
                String bestC = weightedRandomPick(canBeC, breakCostMap, rnd);
                // 设bestC为C，其余原本C的分支 → B或S
                for (String id : originallyC) {
                    if (id.equals(bestC)) {
                        statusMap.put(id, "C");
                    } else {
                        applyBOrS(statusMap, id, canBreakToBSet, canChangeSSet);
                    }
                }
            } else {
                // 多个C → 保留一个，其余原本C的分支 → B或S
                // 保留打断代价最低的为C
                String bestC = currentC.get(0);
                double bestCost = breakCostMap != null ? breakCostMap.getOrDefault(bestC, Double.MAX_VALUE) : 0;
                for (String id : currentC) {
                    double cost = breakCostMap != null ? breakCostMap.getOrDefault(id, Double.MAX_VALUE) : 0;
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestC = id;
                    }
                }
                for (String id : originallyC) {
                    if (!id.equals(bestC)) {
                        applyBOrS(statusMap, id, canBreakToBSet, canChangeSSet);
                    }
                }
            }
        }
        return statusMap;
    }

    /**
     * 让方案满足"多选一"约束(基于 statue 列表,直接修改传入的列表)
     * 规则:每个 chooseOne 组中"恰好一个 C"
     * - 当前 1 个 C:满足,放过
     * - 当前 0 个 C:从允许状态含 C 的分支中选一个改成 C
     * - 当前 >1 个 C:随机保留一个 C,其余 C → 其允许状态中随机选一个(非 C)
     *
     * 与 applyChooseOnePropagation 的区别:
     * - applyChooseOnePropagation 基于 baseStatusMap 判断"原本是否为 C",只动原本 C 的
     * - applyChooseOneConstraint 直接基于当前 statue 操作,任何 C 都参与判断
     *
     * @param statue        当前状态列表(会被原地修改)
     * @param chooseOneList 多选一约束列表
     * @param normList      分支 id → 索引的映射列表
     */
    private void applyChooseOneConstraint(List<String> statue,
            List<Map<String, List<String>>> chooseOneList, List<String> normList) {
        if (chooseOneList == null || chooseOneList.isEmpty()) {
            return;
        }
        Random chooseOneRnd = new Random();
        for (Map<String, List<String>> group : chooseOneList) {
            // 当前组里有哪些分支是 C
            List<Integer> currentCIndices = new ArrayList<>();
            for (String bid : group.keySet()) {
                int idx = normList.indexOf(bid);
                if (idx >= 0 && "C".equals(statue.get(idx))) {
                    currentCIndices.add(idx);
                }
            }
            if (currentCIndices.size() == 1) {
                continue; // 满足,放过
            }
            if (currentCIndices.isEmpty()) {
                // 0 个 C:从允许状态含 C 的分支中选一个改成 C
                Integer pickIdx = null;
                for (String bid : group.keySet()) {
                    int idx = normList.indexOf(bid);
                    if (idx < 0)
                        continue;
                    List<String> allowed = group.get(bid);
                    if (allowed != null && allowed.contains("C")
                            && !"C".equals(statue.get(idx))) {
                        pickIdx = idx;
                        break;
                    }
                }
                if (pickIdx != null) {
                    statue.set(pickIdx, "C");
                }
            } else {
                // >1 个 C:随机保留一个,其余 C → 其允许状态中随机选一个(非 C)
                int keepIdx = currentCIndices.get(chooseOneRnd.nextInt(currentCIndices.size()));
                for (Integer cIdx : currentCIndices) {
                    if (cIdx == keepIdx)
                        continue;
                    String bid = normList.get(cIdx);
                    List<String> allowed = group.get(bid);
                    List<String> candidates = new ArrayList<>();
                    if (allowed != null) {
                        for (String s : allowed) {
                            if (!"C".equals(s)) {
                                candidates.add(s);
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        // 兜底:该分支只允许 C,改为 B
                        candidates.add("B");
                    }
                    String newStatus;
                    if (candidates.size() == 1) {
                        newStatus = candidates.get(0); // 只有一个候选,直接指定
                    } else {
                        newStatus = candidates.get(chooseOneRnd.nextInt(candidates.size()));
                    }
                    statue.set(cIdx, newStatus);
                }
            }
        }
    }

    /**
     * 将分支设为B（优先），若不可B则设为S
     */
    private void applyBOrS(Map<String, String> statusMap, String id,
            Set<String> canBreakToBSet, Set<String> canChangeSSet) {
        if (canBreakToBSet != null && canBreakToBSet.contains(id)) {
            statusMap.put(id, "B");
        } else if (canChangeSSet != null && canChangeSSet.contains(id)) {
            statusMap.put(id, "S");
        }
        // 不可B也不可S → 保留原状
    }

    /**
     * 加权随机选一个：打断代价越低，越容易被选中
     */
    private String weightedRandomPick(List<String> candidates,
            Map<String, Double> breakCostMap, Random rnd) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        // 计算权重：代价越低权重越高，使用 minCost / cost
        double minCost = Double.MAX_VALUE;
        double[] weights = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            double cost = breakCostMap != null ? breakCostMap.getOrDefault(candidates.get(i), Double.MAX_VALUE) : 1.0;
            if (cost <= 0)
                cost = 1e-9;
            if (cost < minCost)
                minCost = cost;
            weights[i] = cost;
        }
        // 转换为权重（倒数）
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = minCost / weights[i];
            total += weights[i];
        }
        if (total <= 0) {
            return candidates.get(rnd.nextInt(candidates.size()));
        }
        double dart = rnd.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (dart <= cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * 快速约束检查（不含拓扑）：给定完整状态，检查互斥/多选一/组团约束。
     * 用于约束感知变异后的最终校验，比完整的 checkFirstOption 轻量。
     *
     * @return true 表示通过所有约束
     */
    private boolean checkConstraintsFast(
            List<String> fullStatus,
            List<String> normList,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList) {
        // 1) togetherBC：同组状态必须一致
        for (List<String> group : togetherBCList) {
            String firstStatus = null;
            for (String id : group) {
                int idx = normList.indexOf(id);
                if (idx < 0)
                    continue;
                String st = fullStatus.get(idx);
                if (firstStatus == null) {
                    firstStatus = st;
                } else if (!firstStatus.equals(st)) {
                    return false;
                }
            }
        }
        // 2) mutex：每对互斥组至少一方是B
        for (Map.Entry<String, Map<String, List<String>>> entry : mutexMap.entrySet()) {
            Map<String, List<String>> groupMap = entry.getValue();
            List<String> firstGroup = new ArrayList<>();
            List<String> secondGroup = new ArrayList<>();
            int cycle = 0;
            for (List<String> ids : groupMap.values()) {
                if (cycle == 0)
                    firstGroup.addAll(ids);
                else
                    secondGroup.addAll(ids);
                cycle++;
            }
            boolean firstHasB = false, secondHasB = false;
            for (String id : firstGroup) {
                int idx = normList.indexOf(id);
                if (idx >= 0 && "B".equals(fullStatus.get(idx))) {
                    firstHasB = true;
                    break;
                }
            }
            for (String id : secondGroup) {
                int idx = normList.indexOf(id);
                if (idx >= 0 && "B".equals(fullStatus.get(idx))) {
                    secondHasB = true;
                    break;
                }
            }
            if (!firstHasB && !secondHasB) {
                return false;
            }
        }
        // 3) chooseOne：每组最多一个C
        for (Map<String, List<String>> group : chooseOneList) {
            int cCount = 0;
            for (List<String> ids : group.values()) {
                for (String id : ids) {
                    int idx = normList.indexOf(id);
                    if (idx >= 0 && "C".equals(fullStatus.get(idx))) {
                        cCount++;
                        if (cCount > 1)
                            return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * @Description: 生成初代遗传算法方案。约束感知：枚举/抽样时先按约束传播（togetherBC展开），
     *               再快速校验互斥/多选一/组团，最后才做拓扑检查，大幅提高存活率。
     *               k=1,2 走枚举，k>2 走加权随机抽样，多线程并行。
     *               不再依赖 survivalRateByK，直接使用 bestBreakCount。
     */
    public List<List<String>> generateInitialSchemes(
            List<Map<String, Object>> originalEdges,
            Set<String> canBreakToBSet,
            List<String> baseStatusList,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            int bestBreakCount,
            Map<String, Double> breakCostMap,
            List<String> normList,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            Map<String, Set<String>> togetherBCIndex,
            Map<String, Set<String>> chooseOneIndex,
            Map<String, Set<String>> mutexConflictIndex,
            Set<String> canChangeSSet) {
        List<List<String>> result = new ArrayList<>();
        final int finalMinCount = LessRandomSamleNumber;

        // 1) 准备可打断分支id列表和基准状态
        List<String> breakableIds = new ArrayList<>();
        Map<String, String> baseStatusMap = new LinkedHashMap<>();
        if (baseStatusList != null && baseStatusList.size() == originalEdges.size()) {
            for (int i = 0; i < originalEdges.size(); i++) {
                Map<String, Object> e = originalEdges.get(i);
                String id = e.get("id").toString();
                baseStatusMap.put(id, baseStatusList.get(i));
                if (canBreakToBSet != null && canBreakToBSet.contains(id)) {
                    breakableIds.add(id);
                }
            }
        }
        int N = breakableIds.size();
        if (N == 0) {
            return result;
        }

        // 2) bestBreakCount 直接使用，不再通过存活率过滤
        final int adjustedBestBreakCount = Math.max(1, Math.min(bestBreakCount, N));

        // 3) 计算概率表（用于加权抽样）
        final Map<String, Double> probMap;
        if (breakCostMap != null && !breakCostMap.isEmpty()) {
            probMap = calcBreakProbabilityByCost(breakCostMap, canBreakToBSet, MaxProbability, WeightFactor);
        } else {
            Map<String, Double> uniform = new HashMap<>();
            for (String id : breakableIds) {
                uniform.put(id, MaxProbability);
            }
            probMap = uniform;
        }

        // 4) 预枚举 k=1,2（约束感知），k>2 走抽样
        final int maxEnumerateCombinations = 1000;
        List<Integer> sampleKList = new ArrayList<>();
        for (int k = 1; k <= adjustedBestBreakCount; k++) {
            long totalComb = combination(N, k);
            boolean shouldEnumerate = (k <= 2) && (totalComb <= maxEnumerateCombinations);
            if (shouldEnumerate) {
                // 枚举支：约束感知 → 先展开+校验约束，再拓扑检查
                List<List<String>> allComb = new ArrayList<>();
                enumerateCombinations(breakableIds, k, 0, new ArrayList<>(), allComb);
                for (List<String> chosen : allComb) {
                    // 约束感知展开
                    Set<String> expanded = expandAndValidateBreaks(
                            new LinkedHashSet<>(chosen), baseStatusMap,
                            togetherBCIndex, mutexConflictIndex);
                    if (expanded == null)
                        continue;

                    // 构造完整状态
                    Map<String, String> statusMap = new LinkedHashMap<>(baseStatusMap);
                    for (String id : expanded) {
                        statusMap.put(id, "B");
                    }
                    List<String> fullStatus = new ArrayList<>();
                    for (String id : baseStatusMap.keySet()) {
                        fullStatus.add(statusMap.get(id));
                    }

                    // 多选一传播：确保每组恰好一个C，其余原本C的分支 → B或S
                    Map<String, String> propagated = applyChooseOnePropagation(
                            statusMap, baseStatusMap, chooseOneList, breakCostMap,
                            canBreakToBSet, canChangeSSet, new Random(seedCounter.incrementAndGet()));
                    if (propagated == null)
                        continue;
                    // 重建 fullStatus（propagated 可能修改了状态）
                    fullStatus = new ArrayList<>();
                    for (String id : baseStatusMap.keySet()) {
                        fullStatus.add(propagated.get(id));
                    }

                    // 快速约束校验（togetherBC/mutex/chooseOne）
                    if (!checkConstraintsFast(fullStatus, normList, mutexMap, chooseOneList, togetherBCList)) {
                        continue;
                    }

                    // 拓扑检查（仅连通性+用电器，约束已由checkConstraintsFast校验）
                    List<Map<String, Object>> testEdges = createNewEdges(fullStatus, originalEdges, normList);
                    if (!checkFirstOption(testEdges, appPositions, eleclection)) {
                        continue;
                    }

                    synchronized (WareHouse) {
                        if (!containsList(fullStatus, WareHouse)) {
                            WareHouse.add(fullStatus);
                            WAREHOUSE_KEYS.add(String.join(",", fullStatus));
                            result.add(fullStatus);
                        }
                    }
                }
            } else {
                sampleKList.add(k);
            }
        }
        // 预枚举够数 → 早退
        if (result.size() >= finalMinCount) {
            return result;
        }
        if (sampleKList.isEmpty()) {
            sampleKList.add(adjustedBestBreakCount);
        }
        final int totalKSum = sampleKList.stream().mapToInt(Integer::intValue).sum();

        // 5) 多轮并行抽样
        final int baseTaskCount = Math.min(11, Math.max(2, finalMinCount / 30));
        final int maxRounds = finalMinCount * 2;
        int round = 0;
        while (result.size() < finalMinCount && round < maxRounds) {
            final int currentRound = round;
            round++;
            int remaining = finalMinCount - result.size();
            int taskCount = Math.min(baseTaskCount, Math.max(2, remaining / 30 + 1));
            int targetPerTask = Math.max(1, (remaining + taskCount - 1) / taskCount);
            AtomicInteger roundGenerated = new AtomicInteger(0);

            List<Future<List<List<String>>>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < taskCount; t++) {
                    final int taskId = t;
                    final int localTarget = targetPerTask;
                    final int roundTarget = remaining;
                    futures.add(threadPool.submit(() -> {
                        Random rnd = new Random(seedCounter.incrementAndGet() + taskId * 131L);
                        List<List<String>> localResult = new ArrayList<>();
                        int localGenerated = 0;

                        for (int k : sampleKList) {
                            if (localGenerated >= localTarget)
                                break;
                            if (roundGenerated.get() >= roundTarget)
                                break;

                            int perKTarget = Math.max(1, (localTarget * k + totalKSum - 1) / totalKSum);
                            List<List<String>> pickedCombinations = weightedSampleCombinations(
                                    breakableIds, k, perKTarget, probMap, rnd);

                            for (List<String> chosen : pickedCombinations) {
                                if (localGenerated >= localTarget)
                                    break;
                                if (roundGenerated.get() >= roundTarget)
                                    break;

                                // 约束感知展开
                                Set<String> expanded = expandAndValidateBreaks(
                                        new LinkedHashSet<>(chosen), baseStatusMap,
                                        togetherBCIndex, mutexConflictIndex);
                                if (expanded == null)
                                    continue;

                                Map<String, String> statusMap = new LinkedHashMap<>(baseStatusMap);
                                for (String id : expanded) {
                                    statusMap.put(id, "B");
                                }
                                List<String> fullStatus = new ArrayList<>();
                                for (String id : baseStatusMap.keySet()) {
                                    fullStatus.add(statusMap.get(id));
                                }

                                // 多选一传播：确保每组恰好一个C，其余原本C的分支 → B或S
                                Map<String, String> propagated = applyChooseOnePropagation(
                                        statusMap, baseStatusMap, chooseOneList, breakCostMap,
                                        canBreakToBSet, canChangeSSet, rnd);
                                if (propagated == null)
                                    continue;
                                fullStatus = new ArrayList<>();
                                for (String id : baseStatusMap.keySet()) {
                                    fullStatus.add(propagated.get(id));
                                }

                                // 快速约束校验
                                if (!checkConstraintsFast(fullStatus, normList, mutexMap, chooseOneList,
                                        togetherBCList)) {
                                    continue;
                                }

                                // 拓扑检查
                                List<Map<String, Object>> testEdges = createNewEdges(fullStatus, originalEdges,
                                        normList);
                                if (!checkFirstOption(testEdges, appPositions, eleclection)) {
                                    continue;
                                }

                                synchronized (WareHouse) {
                                    if (!containsList(fullStatus, WareHouse)) {
                                        WareHouse.add(fullStatus);
                                        WAREHOUSE_KEYS.add(String.join(",", fullStatus));
                                        localResult.add(fullStatus);
                                        localGenerated++;
                                        roundGenerated.incrementAndGet();
                                    }
                                }
                            }
                        }
                        return localResult;
                    }));
                }

                for (Future<List<List<String>>> f : futures) {
                    List<List<String>> part = f.get();
                    if (part != null && !part.isEmpty()) {
                        result.addAll(part);
                    }
                }
            } catch (Exception e) {
                System.err.println("generateInitialSchemes 第 " + currentRound + " 轮异常: " + e.getMessage());
                e.printStackTrace();
            }

            if (result.size() >= finalMinCount) {
                break;
            }
        }
        return result;
    }

    /**
     * @Description: 从 list 中随机抽取 k 个元素（不重复），返回新列表
     */
    private List<String> randomPickSingle(List<String> list, int k, Random random) {
        if (k <= 0 || k > list.size()) {
            return null;
        }
        List<String> pool = new ArrayList<>(list);
        int n = list.size();
        for (int i = 0; i < k; i++) {
            int j = i + random.nextInt(n - i);
            String tmp = pool.get(i);
            pool.set(i, pool.get(j));
            pool.set(j, tmp);
        }
        return new ArrayList<>(pool.subList(0, k));
    }

    /**
     * @Description: 按概率表对 list 中每个元素独立掷骰子，命中的元素加入结果
     */
    private List<String> pickByProbability(List<String> list, Map<String, Double> probMap, Random random) {
        List<String> picked = new ArrayList<>();
        for (String id : list) {
            Double p = probMap.get(id);
            if (p == null) {
                p = 0.0;
            }
            if (random.nextDouble() < p) {
                picked.add(id);
            }
        }
        return picked;
    }

    /**
     * @Description: 计算组合数 C(n, k)，使用 long 避免溢出
     */
    private long combination(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        if (k == 0 || k == n) {
            return 1;
        }
        if (k > n - k) {
            k = n - k;
        }
        long result = 1;
        for (int i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return result;
    }

    /**
     * @Description: 递归枚举 list 中选 k 个的所有组合
     */
    private void enumerateCombinations(List<String> list, int k, int start,
            List<String> current, List<List<String>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        // 剪枝：剩余元素不够凑齐 k 个时直接返回
        if (list.size() - start < k - current.size()) {
            return;
        }
        for (int i = start; i < list.size(); i++) {
            current.add(list.get(i));
            enumerateCombinations(list, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    /**
     * @Description: 随机抽样 count 个不重复的组合，每个组合选 k 个元素
     *               用 Set 去重，超出尝试上限则提前结束
     */
    private List<List<String>> randomSampleCombinations(List<String> list, int k,
            int count, Random random) {
        List<List<String>> result = new ArrayList<>();
        int n = list.size();
        if (k <= 0 || k > n) {
            return result;
        }
        long totalComb = combination(n, k);
        int target = (int) Math.min((long) count, totalComb);
        Set<String> seen = new HashSet<>();
        // 尝试上限：目标的10倍，避免极端情况下死循环
        int maxAttempts = target * 10 + 100;
        int attempts = 0;
        while (result.size() < target && attempts < maxAttempts) {
            attempts++;
            // 复制 list 后部分洗牌，取前 k 个
            List<String> pool = new ArrayList<>(list);
            // Fisher-Yates 部分洗牌：只洗前 k 个位置
            for (int i = 0; i < k; i++) {
                int j = i + random.nextInt(n - i);
                String tmp = pool.get(i);
                pool.set(i, pool.get(j));
                pool.set(j, tmp);
            }
            List<String> picked = new ArrayList<>(pool.subList(0, k));
            // 排序后做签名，保证同一组合不同顺序视为相同
            Collections.sort(picked);
            String key = String.join(",", picked);
            if (seen.add(key)) {
                result.add(picked);
            }
        }
        return result;
    }

    /**
     * @Description: 按 probMap 加权随机抽 count 个 k-组合（不重复）
     *               算法：每个分支按权重生成"加权随机键" key = random^(1/weight)，
     *               用最小堆选前 k 个最大 key（O(n log k)），避免全排序 O(n log n)
     *               权重高的分支更易排前，权重全相等时退化为均匀随机
     *               多次重复+去重，得到 count 个不同组合
     *               时间复杂度：O(count * n * log k)，n = breakableIds.size()
     */
    private List<List<String>> weightedSampleCombinations(List<String> list, int k,
            int count, Map<String, Double> probMap, Random random) {
        List<List<String>> result = new ArrayList<>();
        int n = list.size();
        if (k <= 0 || k > n) {
            return result;
        }
        long totalComb = combination(n, k);
        int target = (int) Math.min((long) count, totalComb);
        Set<String> seen = new HashSet<>();
        // 尝试上限：目标的10倍 + 100，避免极端情况死循环
        int maxAttempts = target * 10 + 100;
        int attempts = 0;
        while (result.size() < target && attempts < maxAttempts) {
            attempts++;
            // k 较小时用线性扫描找 top-k，比堆更快
            int[] topIndices;
            if (k <= 2) {
                // 小 k：直接线性扫描取 top-k，O(n*k)
                topIndices = selectTopKLinear(list, k, probMap, random);
            } else {
                // 大 k：用最小堆，O(n log k)
                topIndices = selectTopKHeap(list, k, probMap, random);
            }
            List<String> picked = new ArrayList<>();
            for (int idx : topIndices) {
                picked.add(list.get(idx));
            }
            // 排序后做签名，保证同一组合不同顺序视为相同
            Collections.sort(picked);
            String sig = String.join(",", picked);
            if (seen.add(sig)) {
                result.add(picked);
            }
        }
        return result;
    }

    /**
     * 线性扫描取 top-k 个最大加权随机键对应索引（k≤2 时比堆快）
     */
    private int[] selectTopKLinear(List<String> list, int k, Map<String, Double> probMap, Random random) {
        int n = list.size();
        double[] keys = new double[n];
        for (int i = 0; i < n; i++) {
            double w = probMap.getOrDefault(list.get(i), 0.0);
            if (w <= 0) {
                w = 1e-9;
            }
            keys[i] = Math.pow(random.nextDouble(), 1.0 / w);
        }
        int[] result = new int[k];
        if (k == 1) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (keys[i] > keys[best]) {
                    best = i;
                }
            }
            result[0] = best;
        } else {
            // k == 2
            int best1 = 0, best2 = 1;
            if (keys[best1] < keys[best2]) {
                int tmp = best1;
                best1 = best2;
                best2 = tmp;
            }
            for (int i = 2; i < n; i++) {
                if (keys[i] > keys[best1]) {
                    best2 = best1;
                    best1 = i;
                } else if (keys[i] > keys[best2]) {
                    best2 = i;
                }
            }
            result[0] = best1;
            result[1] = best2;
        }
        return result;
    }

    /**
     * 最小堆选择 top-k 个最大加权随机键对应索引
     * 堆中存 double[] {index, key}，按 key 升序排列
     */
    private int[] selectTopKHeap(List<String> list, int k, Map<String, Double> probMap, Random random) {
        int n = list.size();
        java.util.PriorityQueue<double[]> minHeap = new java.util.PriorityQueue<>(
                (a, b) -> Double.compare(a[1], b[1])); // 最小堆：堆顶是第 k 大的门槛，key 最小的在堆顶
        for (int i = 0; i < n; i++) {
            double w = probMap.getOrDefault(list.get(i), 0.0);
            if (w <= 0) {
                w = 1e-9;
            }
            double key = Math.pow(random.nextDouble(), 1.0 / w);
            if (minHeap.size() < k) {
                minHeap.offer(new double[] { i, key });
            } else {
                double[] peek = minHeap.peek();
                if (key > peek[1]) {
                    minHeap.poll();
                    minHeap.offer(new double[] { i, key });
                }
            }
        }
        int[] result = new int[k];
        int idx = 0;
        for (double[] entry : minHeap) {
            result[idx++] = (int) entry[0];
        }
        return result;
    }

    /**
     * @Description: 对生成的方案进行一个检查： 1、回路是否导通 2、用电器周围是否至少存在一个分支
     * @input: edges 生成需要检查的分支
     * @input: appPositions 没有解析txt中的用电器像信息
     * @input: eleclection 用电器对应的位置
     * @input: mutexMap 互斥的情况集合
     * @Return: 根据给定的方案检查 返回是否符合的状态
     */
    public Boolean checkFirstOption(List<Map<String, Object>> edges, List<Map<String, String>> appPositions,
            Map<String, String> eleclection) {

        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> k : edges) {
            strPointNameList.add(k.get("startPointName").toString());
            endPointNameList.add(k.get("endPointName").toString());
            if (k.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(k.get("startPointName").toString());
                interruptedEdgelist.add(k.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointNameList, endPointNameList,
                branchBreakList);// 获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();// 构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();// 为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();

        FindTopoBreak breakRecognize = new FindTopoBreak();
        List<List<String>> breakRec = breakRecognize.recognizeBreak(adjacencyMatrixGraph.getAdj(),
                adjacencyMatrixGraph.getAllPoint());
        // 2、 每个用电器周围至少存在一个分支 3、生成的方案必须使得每个回路导通
        // 如果size大于1，说明打断导致拓扑图分成了多个族群，说明存在断点，优化算法会拒绝这个方案，只能保留保持拓扑联通的
        if (breakRec.size() > 1) {
            return false;
        }

        Boolean edgesFlag = true;

        for (Map<String, String> appPosition : appPositions) {
            if (!appPosition.get("appName").startsWith("[")) {
                String pointName = eleclection.get(appPosition.get("appName"));
                if (!checkElecEdge(pointName, edges)) {
                    return false;
                }
            }
        }

        if (breakRec.size() == 1 && edgesFlag) {
            return true;
        }
        return false;
    }

    /**
     * @Description 判断用电器对应位置点两端是否存在分支为C
     * @input 用电器对应的位置点
     * @input 所有的分支信息
     */
    public boolean checkElecEdge(String pointName, List<Map<String, Object>> edges) {
        for (Map<String, Object> edge : edges) {
            if ((edge.get("startPointName").toString().equals(pointName)
                    || edge.get("endPointName").toString().equals(pointName))
                    && !edge.get("topologyStatusCode").toString().equalsIgnoreCase("B")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 根据传入的分支打断状况 返回一条新的分支详情
     * @input: edgeStatue 分支打断状况
     * @input: edgeDetails 分支模板
     * @input: normList 分支的id编号
     * @Return: 根据传入的分支打断情况 创建一个分支详情
     */
    public List<Map<String, Object>> createNewEdges(List<String> edgeStatue, List<Map<String, Object>> edgeDetails,
            List<String> normList) {
        List<Map<String, Object>> newEdges = edgeDetails.stream().collect(Collectors.toList());
        for (Map<String, Object> newEdge : newEdges) {
            String id = (String) newEdge.get("id");
            int number = normList.indexOf(id);
            newEdge.put("topologyStatusCode", edgeStatue.get(number));
        }
        return newEdges;
    }

    /**
     * @Description: 判断targetList 是否在 listOfLists中
     * @input: targetList
     * @input: listOfLists
     * @Return:
     */
    public boolean containsList(List<String> targetList, List<List<String>> listOfLists) {
        // WareHouse 使用 WAREHOUSE_KEYS 做 O(1) 去重
        if (listOfLists == WareHouse) {
            return WAREHOUSE_KEYS.contains(String.join(",", targetList));
        }
        for (List<String> list : listOfLists) {
            if (list.equals(targetList)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 对生成的方案进行一个检查： 1、是否存在互斥的情况 2、回路是否导通 3、用电器周围是否至少存在一个分支
     * @input: normList 当前分支id的排序情况
     * @input: changeList 分支的打断状况
     * @input: edges 生成需要检查的分支
     * @input: appPositions 没有解析txt中的用电器像信息
     * @input: eleclection 用电器对应的位置
     * @input: mutexMap 互斥的情况集合
     * @Return: 根据给定的方案检查 返回是否符合的状态
     */
    public Boolean checkFirstOption(List<String> normList, List<String> changeList, List<Map<String, Object>> edges,
            List<Map<String, String>> appPositions, Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList) {
        // 组团的检查
        for (List<String> list : togetherBCList) {
            String statue = changeList.get(normList.indexOf(list.get(0)));
            for (String s : list) {
                if (!statue.equals(changeList.get(normList.indexOf(s)))) {
                    return false;
                }
            }
        }

        // 对多选一的情况进行一个检查 首先检查分支状态是否符合要求 其次再检查C的数量
        for (Map<String, List<String>> map : chooseOneList) {
            int numberC = 0;
            Set<String> set = map.keySet();
            for (String s : set) {
                int i = normList.indexOf(s);
                String s1 = changeList.get(i);
                List<String> list = map.get(s);
                if (!list.contains(s1)) {
                    return false;
                }
                if (s1.equals("C")) {
                    numberC++;
                }

            }
            if (numberC > 1) {
                return false;
            }
        }

        // 对互斥的情况进行一个检查
        Set<String> mutexName = mutexMap.keySet();
        for (String s : mutexName) {
            Map<String, List<String>> listMap = mutexMap.get(s);
            Set<String> sonset = listMap.keySet();
            int cycleNumber = 1;
            String statue = null;
            for (String edgeId : sonset) {
                List<String> list = listMap.get(edgeId);
                if (cycleNumber == 1) {
                    statue = changeList.get(normList.indexOf(list.get(0)));
                    if (statue.equals("B")) {
                        for (String topologyStatusCode : list) {
                            if (!changeList.get(normList.indexOf(topologyStatusCode)).equals("B")) {
                                return false;
                            }
                        }
                    } else {
                        for (String topologyStatusCode : list) {
                            if (!(changeList.get(normList.indexOf(topologyStatusCode)).equals("C")
                                    || changeList.get(normList.indexOf(topologyStatusCode)).equals("S"))) {
                                return false;
                            }
                        }
                    }
                } else {
                    if (statue.equals("B")) {
                        for (String topologyStatusCode : list) {
                            if (!(changeList.get(normList.indexOf(topologyStatusCode)).equals("C")
                                    || changeList.get(normList.indexOf(topologyStatusCode)).equals("S")
                                    || changeList.get(normList.indexOf(topologyStatusCode)).equals("B"))) {
                                return false;
                            }
                        }
                    } else {
                        for (String topologyStatusCode : list) {
                            if (!changeList.get(normList.indexOf(topologyStatusCode)).equals("B")) {
                                return false;
                            }
                        }
                    }
                }
                cycleNumber++;
            }
        }

        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        for (Map<String, Object> k : edges) {
            strPointNameList.add(k.get("startPointName").toString());
            endPointNameList.add(k.get("endPointName").toString());
        }
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String code = edge.get("topologyStatusCode") != null
                    ? edge.get("topologyStatusCode").toString()
                    : "";
            if ("B".equals(code)) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointNameList, endPointNameList,
                branchBreakList);// 获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();// 构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();// 为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();

        FindTopoBreak breakRecognize = new FindTopoBreak();
        List<List<String>> breakRec = breakRecognize.recognizeBreak(adjacencyMatrixGraph.getAdj(),
                adjacencyMatrixGraph.getAllPoint());
        // 2、 每个用电器周围至少存在一个分支 3、生成的方案必须使得每个回路导通
        if (breakRec.size() != 1) {
            return false;
        }

        Boolean edgesFlag = true;

        for (Map<String, String> appPosition : appPositions) {
            if (!appPosition.get("appName").startsWith("[")) {
                String pointName = eleclection.get(appPosition.get("appName"));
                if (!checkElecEdge(pointName, edges)) {
                    return false;
                }
            }
        }

        if (breakRec.size() == 1 && edgesFlag) {
            return true;
        }
        return false;
    }

    /**
     * @Description: 使用AI预测模型对每个样本预测成本，返回成本最优的topN样本
     *               替代整车计算方法，直接通过GINE模型预测成本
     * @input: simpleList 分支打断情况的集合
     * @input: edges 分支模板
     * @input: normList 分支id的集合
     * @input: jsonMap txt内容转为map
     * @input: edgeChooseBS 分支打断可以选BS的集合
     * @input: elecPosition 用电器对应的位置
     * @input: branchLength 分支长度信息
     * @input: connection 图连接关系
     * @input: multiLoopInfos 多回路信息
     * @input: pointMap 点位映射
     * @Return: 返回AI预测成本最优的topN方案
     */
    public List<Map<String, Object>> predictAndFindBest(List<List<String>> simpleList,
            List<Map<String, Object>> edges,
            List<String> normList,
            Map<String, Object> jsonMap,
            List<String> edgeChooseBS,
            Map<String, Map<String, String>> elecPosition,
            Map<String, Object> branchLength,
            List<List<Integer>> connection,
            Map<String, List<String>> multiLoopInfos,
            Map<String, String> pointMap, List<Map<String, Object>> findBestPre) throws Exception {
        GINEInferenceEngine gine = new GINEInferenceEngine();
        ObjectMapper mapper = new ObjectMapper();
        List<Float> length = (List<Float>) branchLength.get("branchLength");
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) jsonMap.get("loopInfos");
        List<Map<String, String>> pointsList = (List<Map<String, String>>) jsonMap.get("points");
        List<Map<String, Object>> resultList = new ArrayList<>();
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        int sampleId = 0;
        for (List<String> strings : simpleList) {
            tasks.add(() -> {
                List<String> serviceableStatue = strings.stream().collect(Collectors.toList());
                for (int i = 0; i < serviceableStatue.size(); i++) {
                    if (serviceableStatue.get(i).equals("C") && edgeChooseBS.contains(normList.get(i))) {
                        serviceableStatue.set(i, "S");
                    }
                }
                List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                // 深拷贝
                Map<String, Object> threadLocalJsonMap = mapper.readValue(
                        mapper.writeValueAsString(jsonMap),
                        Map.class);
                threadLocalJsonMap.put("edges", serviceableEdge);

                // 分支特征参数列表 B：[0,0,0],C[0,1,0],S[0,0,1]
                List<List<Float>> branchFeatureList = new ArrayList<>();
                for (String s : serviceableStatue) {
                    List<Float> statue = new ArrayList<>();
                    switch (s) {
                        case "B":
                            statue = new ArrayList<>(Arrays.asList(0.0f, 0.0f, 0.0f));
                            break;
                        case "C":
                            statue = new ArrayList<>(Arrays.asList(0.0f, 1.0f, 0.0f));
                            break;
                        case "S":
                            statue = new ArrayList<>(Arrays.asList(0.0f, 0.0f, 1.0f));
                            break;
                        default:
                            break;
                    }
                    branchFeatureList.add(statue);
                }
                for (int i = 0; i < length.size(); i++) {
                    List<Float> integers = branchFeatureList.get(i);
                    integers.add(length.get(i));
                }
                // 标准化特征矩阵
                float[][] x = Normalize.normalizeData(serviceableEdge, loopInfos, elecPosition, threadLocalJsonMap,
                        pointsList, normList, multiLoopInfos, pointMap);
                long[][] edgeIndex = new long[2][connection.get(0).size()];
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < connection.get(i).size(); j++) {
                        edgeIndex[i][j] = connection.get(i).get(j);
                    }
                }
                float[][] edgeAttr = new float[branchFeatureList.size()][branchFeatureList.get(0).size()];
                for (int i = 0; i < branchFeatureList.size(); i++) {
                    for (int j = 0; j < branchFeatureList.get(i).size(); j++) {
                        edgeAttr[i][j] = branchFeatureList.get(i).get(j);
                    }
                }
                // AI模型预测成本
                float predict = gine.predict(x, edgeIndex, edgeAttr);

                // 构建返回结果，与changeAndFindBest格式保持一致
                Map<String, Object> costResultData = new HashMap<>();
                costResultData.put("总成本", (double) predict);
                // AI模型仅预测成本，重量和长度置为占位值
                costResultData.put("总重量", 0.0);
                costResultData.put("总长度", 0.0);

                Map<String, Object> map = new HashMap<>();
                map.put("成本", costResultData);
                map.put("serviceableEdges", serviceableEdge);
                map.put("serviceableStatue", serviceableStatue);
                return map;
            });
        }
        // 线程池提交任务
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Callable<Map<String, Object>> task : tasks) {
            Future<Map<String, Object>> submit = threadPool.submit(task);
            futures.add(submit);
        }
        // 获取线程池结果
        for (Future<Map<String, Object>> future : futures) {
            try {
                Map<String, Object> result = future.get(600, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    resultList.add(result);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 按AI预测成本排序，取topN
        FindBest findBest = new FindBest();
        if (findBestPre != null) {
            int preCount = Math.max(1, (int) (findBestPre.size() * 0.1));
            if (findBestPre != null) {
                for (int i = 0; i < preCount; i++) {
                    resultList.add(findBestPre.get(i));
                }
            }
        }
        List<Map<String, Object>> topBeat = findBest.findBest(resultList, "成本", TopNumber);

        for (Map<String, Object> map : topBeat) {
            List<String> list = (List<String>) map.get("serviceableStatue");
            if (!containsList(list, WareHouseTop)) {
                WareHouseTop.add(list);
                TopCostDetail.add(map);
            }
        }
        return topBeat;
    }

    /**
     * 分支长度
     *
     * @param normList 分支排列顺序id
     * @param edges
     * @return
     */
    public Map<String, Object> getBranchLength(List<String> normList, List<Map<String, Object>> edges) {
        List<Float> branchLengthList = new ArrayList<>();
        Map<String, Object> result = new HashMap<>();
        for (String branchId : normList) {
            Float length = 0.0f;
            for (Map<String, Object> edge : edges) {
                if (branchId.equals(edge.get("id"))) {
                    // 参考长度
                    String referenceLength = null;
                    // 用户确认的分支长度
                    String verifyLength = null;
                    // 参考长度
                    if (edge.get("referenceLength") != null) {
                        referenceLength = String.valueOf(edge.get("referenceLength"));
                    }
                    // 用户确认的分支长度
                    if (edge.get("length") != null) {
                        verifyLength = String.valueOf(edge.get("length"));
                    }
                    if (verifyLength != null && !verifyLength.isEmpty()) {
                        length += Float.parseFloat(verifyLength);
                    } else {
                        if (!referenceLength.isEmpty()) {
                            length += Float.parseFloat(referenceLength);
                        } else if ("C".equals(edge.get("topologyStatusCode"))
                                || "S".equals(edge.get("topologyStatusCode"))) {
                            length += 200;
                        } else {
                            // 打断状态直接设0
                            length = 0f;
                        }
                    }
                    break;
                }
            }
            branchLengthList.add(length);
        }
        result.put("branchLength", branchLengthList);
        return result;
    }

    public List<List<Integer>> connection(List<Map<String, Object>> edges, List<String> normList) {
        List<List<Integer>> result = new ArrayList<>();
        List<String> startName = new ArrayList<>();
        List<String> endName = new ArrayList<>();
        Set<String> branchPointNameList = new LinkedHashSet<>();
        List<Integer> startIndex = new ArrayList<>();
        List<Integer> endIndex = new ArrayList<>();
        for (int i = 0; i < normList.size(); i++) {
            for (Map<String, Object> k : edges) {
                if (k.get("id").equals(normList.get(i))) {
                    String startPointName = k.get("startPointName").toString();
                    String endPointName = k.get("endPointName").toString();
                    startName.add(startPointName);
                    endName.add(endPointName);
                    // 名称添加
                    branchPointNameList.add(startPointName);
                    branchPointNameList.add(endPointName);
                    break;
                }
            }
        }
        List<String> allNameList = new ArrayList<>(branchPointNameList);
        for (String s : startName) {
            startIndex.add(allNameList.indexOf(s));
        }
        for (String s : endName) {
            endIndex.add(allNameList.indexOf(s));
        }
        result.add(startIndex);
        result.add(endIndex);
        return result;
    }

    /**
     * @Description: 根据用电器名称获取对应的位置点名称
     * @input: appName 用电器名称
     * @input: appPositions 用电器位置信息
     * @Return: 返回接收到用电器名称对应的位置
     */
    public String findNode(String appName, List<Map<String, String>> appPositions) {
        for (Map<String, String> appPosition : appPositions) {
            if (appPosition.get("appName").equalsIgnoreCase(appName)) {
                if (appPosition.get("unregularPointName") != null) {
                    return appPosition.get("unregularPointName");
                } else if (appPosition.get("unregularPointName") == null
                        && appPosition.get("regularPointName") != null) {
                    return appPosition.get("regularPointName");
                } else if (appPosition.get("unregularPointName") == null
                        && appPosition.get("regularPointName") == null) {
                    return null;
                }
            }
        }
        return null;
    }
}
