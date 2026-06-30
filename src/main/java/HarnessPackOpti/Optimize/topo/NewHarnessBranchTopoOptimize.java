package HarnessPackOpti.Optimize.topo;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.utils.GINEInferenceEngine;
import HarnessPackOpti.utils.Normalize;
import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import HarnessPackOpti.utils.ThreadPool;
import org.apache.commons.collections4.map.LinkedMap;

/**
 * 新的topo优化遗传算法
 */
public class NewHarnessBranchTopoOptimize {
    // 初代样本最低生成数量
    public static Integer LessRandomSamleNumber = 1000;
    // 迭代最少样本数量
    public static Integer HybridizationLessRandomSamleNumber = 30;
    // top几的数量规定
    public static final Integer TopNumber = 100;
    // 最后返回前端的方案数量
    public static final Integer LastNumber = 20;
    // 每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();
    // 最优样本重复次数
    public static Integer BestRepetitionNumber = 0;
    // 迭代重复的次数限值
    public static Integer IterationRestrictNumber = 10;
    // 定义一个仓库
    public static List<List<String>> WareHouse = new CopyOnWriteArrayList<>();
    // 仓库的 key 索引：完整状态列表拼接的字符串，用于 O(1) 去重
    public static final Set<String> WAREHOUSE_KEYS = ConcurrentHashMap.newKeySet();
    // 变异的次数
    public static Integer VariationNumber = 1;
    // 每次迭代得到的top20
    public static List<Map<String, Object>> TopDetail = new ArrayList<>();
    // 初始化自动补全得次数
    public static Integer InitializeAutoCompleteNumber = 2000;
    // 找存活率每轮生成的样本数
    public static Integer MaxSamplePerRound = 1000;
    // 决定走枚举还是随机的阈值
    public static Integer MaxEnumerateCombinations = 1000;
    // 自动补全得次数
    public static Integer AutoCompleteNumber = 30;
    // 定义仓库(所有裂变生成的方案，用于AI)
    public static List<List<String>> WareHouseAI = new CopyOnWriteArrayList<>();
    // 暂存的仓库
    public static List<List<String>> WareHouseTemp = new CopyOnWriteArrayList<>();
    // 分支打断代价降序排序时使用的权重衰减系数，越低打断越激进，高打断代价的分支也会又概率打断
    public static Double WeightFactor = 0.7;
    // 决定最高打断概率
    public static Double MaxProbability = 0.9;
    // 存活率阈值：低于此值的 k 视为无效（用于过滤 maxValidK）
    public static Double MinSurvivalRate = 0.30;
    // 线程池
    public static ThreadPool threadPool = new ThreadPool(11, 50);

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
        String topoOptimize = newHarnessBranchTopoOptimize.topoOptimize(jsonContent);
    }

    public String topoOptimize(String jsonContent) throws Exception {
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
        for (Map<String, Object> map : edges) {
            Map<String, String> result = new HashMap<>();
            result.put("edgeId", map.get("id").toString());
            result.put("statue", map.get("topologyStatusCode").toString());
            topoOptimizeResult.add(result);
        }
        initializeCaseResultMap.put("topoOptimizeResult", topoOptimizeResult);
        initializeCaseResultMap.put("initializationScheme", true);

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
        // 固定为 B 的分支集合（B 状态保留，不动）
        Set<String> keepBSet = new HashSet<>(onlyNameB);
        // initialScheme 当前方案下的分支打断情况，只把"允许改 C 且非固定 B"的分支置为 C，其他保持原状
        List<String> initialScheme = new ArrayList<>();
        List<Map<String, Object>> coppyedges = edges.stream()
                .map(map -> new HashMap<>(map)) // 对每个 Map 创建新实例
                .collect(Collectors.toList());
        // 分支允许为 C 的才置为 C
        for (Map<String, Object> coppyedge : coppyedges) {
            String id = (String) coppyedge.get("id");
            if (!keepBSet.contains(id) && canChangeToCSet.contains(id)) {
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

        // 10) 阈值探索：计算不同 k 下的方案存活率（使用 initialScheme 作为基础状态）
        // 返回的 survivalRateList 已按 survivalRate 降序排序，第 0 条就是最佳
        long breakTime = System.currentTimeMillis();
        List<Map<String, Object>> survivalRateList = calcSurvivalRateByBreakCount(
                edges, canBreakToBSet, initialScheme,
                appPositions, eleclection,
                MaxEnumerateCombinations, MaxSamplePerRound, null);
        System.out.println("计算打断的阈值耗时:" + (System.currentTimeMillis() - breakTime));
        // 11) bestBreakCount：存活率 ≥ MinSurvivalRate 的最大 k
        // 例：k=17 存活率30%、k=18 存活率29%（< 30%），则 bestBreakCount = 17
        // 兜底：若所有 k 都不满足，则取存活率最高的 k
        int bestBreakCount = 1;
        if (survivalRateList != null && !survivalRateList.isEmpty()) {
            int maxValidK = 0;
            for (Map<String, Object> entry : survivalRateList) {
                int k = (int) entry.get("breakCount");
                double rate = (double) entry.get("survivalRate");
                if (rate >= MinSurvivalRate) {
                    if (k > maxValidK) {
                        maxValidK = k;
                    }
                }
            }
            if (maxValidK > 0) {
                bestBreakCount = maxValidK;
            } else {
                // 兜底：所有 k 都不满足阈值，取存活率最高的 k（survivalRateList 已降序）
                bestBreakCount = (int) survivalRateList.get(0).get("breakCount");
            }
        }

        // 12) 提取 k → 存活率 Map（用于 generateInitialSchemes 计算 maxValidK）
        Map<Integer, Double> survivalRateByK = new LinkedHashMap<>();
        if (survivalRateList != null) {
            for (Map<String, Object> entry : survivalRateList) {
                int k = (int) entry.get("breakCount");
                double rate = (double) entry.get("survivalRate");
                survivalRateByK.put(k, rate);
            }
        }

        // 13) 生成初代方案：基于 bestBreakCount 和 survivalRateByK，使用 initialScheme 作为基础状态，
        // breakCostMap 用于策略 B 计算概率表
        long initTime = System.currentTimeMillis();
        List<List<String>> initialSchemes = generateInitialSchemes(
                edges, canBreakToBSet, initialScheme,
                appPositions, eleclection,
                bestBreakCount, breakCostMap, survivalRateByK,normList,
                 mutexMap,
                 chooseOneList, togetherBCList);
        System.out.println("初代方案生成" + LessRandomSamleNumber + "个方案耗时：" + (System.currentTimeMillis() - initTime));
        //TODO 模型预测成本
        long predictTime = System.currentTimeMillis();
        List<Map<String, Object>> findBest = predictAndFindBest(initialSchemes, edges, normList, jsonMap,
                edgeChooseBS, elecPosition, branchLength, connection, multiLoopInfos, pointMap, null);
        System.out.println("预测" + initialSchemes.size() + "个样本耗时：" + (System.currentTimeMillis() - predictTime));
        List<Map<String,Object>> resultList = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<String> strings = initialSchemes.get(i);
            List<Map<String, Object>> newEdges = createNewEdges(strings, edges, normList);
            //测试成本
            jsonMap.put("edges", newEdges);
            String s = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> map = jsonToMap.TransJsonToMap(s);
            resultList.add(map);
        }

        // 14) 构造最终输出
        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("topoId", topoInfoMap.get("id").toString());
        finalResult.put("caseId", caseInfo.get("id").toString());
        finalResult.put("initialScheme", initializeCaseResultMap);
        finalResult.put("survivalRateList", survivalRateList);
        finalResult.put("bestBreakCount", bestBreakCount);
        finalResult.put("initialSchemes", initialSchemes);
        return objectMapper.writeValueAsString(finalResult);
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
     * @input:
     *         sortedBreakCostMap 按打断代价从高到低排序的分支id->打断代价 LinkedHashMap
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
     * @input:
     *         probabilityMap 分支id -> 被打断概率（0~1）
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
     * @Description: 阈值探索 —— 计算"同时变 k 个可打断分支(C改B)"时各轮方案的存活率。
     *               1) 从 k=1 开始枚举或随机抽样，遍历到 k=N；
     *               2) 对每轮生成的方案调用 checkFirstOption 判断是否存活（回路导通+用电器周围有分支）；
     *               3) 统计本轮存活率 = 存活数 / 本轮实际测试数；
     *               4) 全部轮次结束后按存活率从高到低排序，方便后续遗传算法选择最佳 breakCount。
     *               注意：本方法不做仓库去重，每轮独立采样/枚举；如需去重请在调用方处理。
     * @input:
     *         originalEdges 原始分支列表（含
     *         id、topologyStatusCode、startPointName、endPointName）
     *         canBreakToBSet 可打断为B的分支id集合（null或空表示全部可打断）
     *         appPositions 用电器位置信息，传给 checkFirstOption
     *         eleclection 用电器->位置点映射，传给 checkFirstOption
     *         maxEnumerateCombinations 单轮组合数阈值，超过此值改用随机抽样，默认10000
     *         maxSamplePerRound 随机抽样时每轮最多测试多少方案，默认1000
     *         random 可选外部Random实例，null则新建
     * @Return: List<Map<String,Object>> 每条 Map:
     *          - breakCount 轮次k（同时变几个分支）
     *          - totalCombinations 理论组合数C(N,k)
     *          - actualTested 实际测试方案数
     *          - survivedCount 存活方案数
     *          - survivalRate 存活率 = survivedCount / actualTested
     *          - method "枚举" / "随机抽样"
     *          列表按 survivalRate 从高到低排序
     * @Complexity: 前几轮枚举为 O(C(N,k) * E)，E=edges数量；后续随机抽样为 O(maxSamplePerRound * E)
     */
    public List<Map<String, Object>> calcSurvivalRateByBreakCount(
            List<Map<String, Object>> originalEdges,
            Set<String> canBreakToBSet,
            List<String> baseStatusList,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            int maxEnumerateCombinations,
            int maxSamplePerRound,
            Random random) {

        List<Map<String, Object>> resultList = new ArrayList<>();

        // 1) 参数校验与默认值
        if (originalEdges == null || originalEdges.isEmpty()) {
            return resultList;
        }
        if (canBreakToBSet == null || canBreakToBSet.isEmpty()) {
            return resultList;
        }
        if (maxEnumerateCombinations <= 0) {
            maxEnumerateCombinations = 1000;
        }
        if (maxSamplePerRound <= 0) {
            maxSamplePerRound = 1000;
        }
        Random rnd = (random == null) ? new Random() : random;

        // 2) 提取可打断分支的有序id列表（保留 originalEdges 的顺序）
        List<String> breakableIds = new ArrayList<>();
        for (Map<String, Object> e : originalEdges) {
            String id = e.get("id").toString();
            if (canBreakToBSet.contains(id)) {
                breakableIds.add(id);
            }
        }
        int N = breakableIds.size();
        if (N == 0) {
            return resultList;
        }

        // 3) 构造基准状态：优先使用 baseStatusList（如 initialScheme，已还原 C）
        // 如果 baseStatusList 为 null 或长度不匹配，则用旧逻辑：可打断分支初始化为C，其他保留原状态
        Map<String, String> baseStatusMap = new LinkedHashMap<>();
        if (baseStatusList != null && baseStatusList.size() == originalEdges.size()) {
            for (int i = 0; i < originalEdges.size(); i++) {
                String id = originalEdges.get(i).get("id").toString();
                baseStatusMap.put(id, baseStatusList.get(i));
            }
        }

        // 4) 主循环：k = 1, 2, ..., N
        for (int k = 1; k <= N; k++) {
            long totalCombinations = combination(N, k);
            boolean useEnumeration = totalCombinations <= maxEnumerateCombinations;

            // 4.1) 生成本轮所有待测的分支id组合
            List<List<String>> pickedCombinations = new ArrayList<>();
            if (useEnumeration) {
                enumerateCombinations(breakableIds, k, 0, new ArrayList<>(), pickedCombinations);
            } else {
                pickedCombinations = randomSampleCombinations(breakableIds, k, maxSamplePerRound, rnd);
            }

            int totalTested = pickedCombinations.size();
            int survived = 0;

            // 4.2) 对每个组合构造方案、检查存活（不去重）
            for (List<String> chosenIds : pickedCombinations) {
                // 构造本方案的状态map（基准C + 选中的改为B）
                Map<String, String> statusMap = new LinkedHashMap<>(baseStatusMap);
                for (String id : chosenIds) {
                    statusMap.put(id, "B");
                }

                // 构造测试用的 edges 深拷贝
                List<Map<String, Object>> testEdges = new ArrayList<>();
                for (Map<String, Object> e : originalEdges) {
                    Map<String, Object> copy = new HashMap<>(e);
                    String id = e.get("id").toString();
                    copy.put("topologyStatusCode", statusMap.get(id));
                    testEdges.add(copy);
                }

                // 方案检查
                if (checkFirstOption(testEdges, appPositions, eleclection)) {
                    survived++;
                }
            }

            // 4.3) 记录本轮结果
            double survivalRate = totalTested > 0 ? (double) survived / totalTested : 0.0;
            Map<String, Object> roundResult = new LinkedHashMap<>();
            roundResult.put("breakCount", k);
            roundResult.put("totalCombinations", totalCombinations);
            roundResult.put("actualTested", totalTested);
            roundResult.put("survivedCount", survived);
            roundResult.put("survivalRate", survivalRate);
            roundResult.put("method", useEnumeration ? "枚举" : "随机抽样");
            resultList.add(roundResult);
        }

        // 5) 按存活率从高到低排序
        resultList.sort((a, b) -> Double.compare(
                (Double) b.get("survivalRate"),
                (Double) a.get("survivalRate")));
        return resultList;
    }

    /**
     * @Description: 生成初代遗传算法方案。多线程并行生成，通过约束检查的方案存入结果集。
     *               每个任务一半采用"同时变 bestBreakCount 个分支"策略，一半采用"按概率独立掷骰子"策略。
     *               线程池使用 class 字段 threadPool，每个任务独立 Random 避免共享冲突。
     *               外层 while 多轮循环：当 result.size() < minCount 时持续生成新批次，
     *               每轮重新计算"还差多少"作为本轮目标，直到达到 minCount 或达到 maxRounds 兜底。
     * @input:
     *         originalEdges 原始分支列表
     *         canBreakToBSet 可打断为B的分支id集合
     *         baseStatusList 基础状态列表（按 edges 顺序，与 topoOptimize 的 initialScheme 一致）。
     *         非空时直接用作 baseStatusMap；空时用旧逻辑：可打断的设 C，其他用原状态
     *         appPositions 用电器位置信息
     *         eleclection 用电器->位置点映射
     *         bestBreakCount 来自 calcSurvivalRateByBreakCount 排第一的 breakCount
     *         breakCostMap 打断代价降序排序的 Map（用于策略 B 计算概率表），可空
     *         survivalRateByK k → 存活率 Map（来自 calcSurvivalRateByBreakCount），可空
     *         minSurvivalRate 存活率阈值（默认 0.30），低于此值的 k 视为无效
     *         minCount 最少生成多少个有效方案（≤0 用 LessRandomSamleNumber 默认值）
     * @Return: List<List<String>> 每个 List<String> 是一个方案的完整状态列表（按 normList 顺序）
     * @Complexity: O(minCount * E * R / 11)，E=edges数量，R=实际生成轮数（≤maxRounds=10），
     *              通过 11 线程并行缩短耗时
     */
    public List<List<String>> generateInitialSchemes(
            List<Map<String, Object>> originalEdges,
            Set<String> canBreakToBSet,
            List<String> baseStatusList,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            int bestBreakCount,
            Map<String, Double> breakCostMap,
            Map<Integer, Double> survivalRateByK, List<String> normList,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList, List<List<String>> togetherBCList) {
        List<List<String>> result = new ArrayList<>();
        final int finalMinCount = LessRandomSamleNumber;
        final double finalMinSurvivalRate = MinSurvivalRate;

        // 2) 准备可打断分支id列表和基准状态
        // 优先使用 baseStatusList（来自 topoOptimize 的 initialScheme，已还原 C）
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

        // 3) 算出 maxValidK：存活率 ≥ minSurvivalRate 的最大 k
        // 若 survivalRateByK 为空，则 maxValidK = N（不过滤）
        int maxValidK = N;
        if (survivalRateByK != null && !survivalRateByK.isEmpty()) {
            maxValidK = 0;
            for (int k = 1; k <= N; k++) {
                Double rate = survivalRateByK.get(k);
                if (rate != null && rate >= finalMinSurvivalRate) {
                    maxValidK = k;
                }
            }
            if (maxValidK == 0) {
                // 所有 k 都不满足存活率阈值，直接返回
                return result;
            }
        }

        // 4) 调整 adjustedBestBreakCount：必须在 [1, maxValidK] 范围内
        final int adjustedBestBreakCount;
        if (bestBreakCount <= 0 || bestBreakCount > maxValidK) {
            adjustedBestBreakCount = Math.max(1, Math.min(3, maxValidK));
        } else {
            adjustedBestBreakCount = bestBreakCount;
        }
        final int finalMaxValidK = maxValidK;

        // 5) 计算概率表（用于策略 B 按概率独立掷骰子）
        // breakCostMap 为空时使用均匀概率
        Map<String, Double> probMap;
        if (breakCostMap != null && !breakCostMap.isEmpty()) {
            probMap = calcBreakProbabilityByCost(breakCostMap, canBreakToBSet, MaxProbability, WeightFactor);
        } else {
            // 没有代价信息时，均匀概率
            probMap = new HashMap<>();
            for (String id : breakableIds) {
                probMap.put(id, MaxProbability);
            }
        }

        // 6) 多轮并行生成：每轮目标 = "还差多少"；外层 while 一直循环到 result.size() ≥ finalMinCount
        // 设置 maxRounds 兜底，防止存活率极低或持续重复时死循环
        final int baseTaskCount = Math.min(11, Math.max(1, finalMinCount / 50));
        final int maxRounds = finalMinCount * 2;
        int round = 0;
        while (result.size() < finalMinCount && round < maxRounds) {
            final int currentRound = round;
            round++;
            int remaining = finalMinCount - result.size();
            // 本轮任务数：剩余数较少时适当缩减
            int taskCount = Math.min(baseTaskCount, Math.max(1, remaining / 50 + 1));
            // 每任务目标：剩余数量摊到每个任务
            int targetPerTask = Math.max(1, (remaining + taskCount - 1) / taskCount);
            // 每任务最大尝试次数：兜底，防止本轮存活率 0% 时死循环
            int maxAttemptsPerTask = Math.max(100, targetPerTask * 10);
            // 跨任务共享本轮生成数（早退优化）
            AtomicInteger roundGenerated = new AtomicInteger(0);

            // 7) 提交本轮并行任务
            List<Future<List<List<String>>>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < taskCount; t++) {
                    final int taskId = t;
                    final int localTarget = targetPerTask;
                    final int localMaxAttempts = maxAttemptsPerTask;
                    final int roundTarget = remaining;
                    futures.add(threadPool.submit(() -> {
                        // 每个任务独立 Random，避免共享冲突
                        Random rnd = new Random(System.nanoTime() + taskId * 31L + currentRound * 131L);
                        // 本地结果集：避免共享 result 的锁竞争，任务结束返回再合并
                        List<List<String>> localResult = new ArrayList<>();
                        int localGenerated = 0;
                        int localAttempts = 0;
                        // 单策略目标 = 总目标 / 2
                        int singleStrategyTarget = (localTarget + 1) / 2;
                        // 单策略最大尝试次数
                        int singleStrategyMax = Math.max(50, localMaxAttempts / 2);

                        // 策略 A：按 bestBreakCount 同时变 k 个
                        // 循环条件：本地生成数 < 单策略目标 且 总尝试 < 单策略最大
                        // 一旦本轮生成数达到 remaining，所有线程也会快速退出
                        while (localGenerated < singleStrategyTarget
                                && localAttempts < singleStrategyMax
                                && roundGenerated.get() < roundTarget) {
                            localAttempts++;
                            List<String> chosen = randomPickSingle(breakableIds, adjustedBestBreakCount, rnd);
                            if (chosen == null || chosen.isEmpty()) {
                                continue;
                            }
                            // 1) 构造完整状态map
                            Map<String, String> statusMap = buildStatusMap(baseStatusMap, chosen);
                            // 2) 验证约束
                            if (!isValidScheme(originalEdges, statusMap, appPositions, eleclection)) {
                                continue;
                            }
                            // 3) 构造完整状态列表（按 baseStatusMap key 顺序 = normList 顺序）
                            List<String> fullStatus = new ArrayList<>();
                            for (String id : baseStatusMap.keySet()) {
                                fullStatus.add(statusMap.get(id));
                            }
                            // 检查生成的方案是否符合约束
                            List<Map<String, Object>> coppysonedges = createNewEdges(fullStatus, originalEdges,
                                    normList);
                            Boolean bool = checkFirstOption(normList, fullStatus, coppysonedges, appPositions,
                                    eleclection,
                                    mutexMap, chooseOneList, togetherBCList);
                            if (!bool) {
                                continue;
                            }
                            synchronized (WareHouse) {
                                // 检查之前是否生成过该方案
                                if (!containsList(fullStatus, WareHouse)) {
                                    WareHouse.add(fullStatus);
                                    localResult.add(fullStatus);
                                    localGenerated++;
                                    roundGenerated.incrementAndGet();
                                }
                            }
                        }

                        // 策略 B：按概率独立掷骰子
                        while (localGenerated < localTarget
                                && localAttempts < localMaxAttempts
                                && roundGenerated.get() < roundTarget) {
                            localAttempts++;
                            List<String> chosen = pickByProbability(breakableIds, probMap, rnd);
                            if (chosen == null || chosen.isEmpty()) {
                                continue;
                            }
                            Map<String, String> statusMap = buildStatusMap(baseStatusMap, chosen);
                            if (!isValidScheme(originalEdges, statusMap, appPositions, eleclection)) {
                                continue;
                            }
                            List<String> fullStatus = new ArrayList<>();
                            for (String id : baseStatusMap.keySet()) {
                                fullStatus.add(statusMap.get(id));
                            }
                             List<Map<String, Object>> coppysonedges = createNewEdges(fullStatus, originalEdges,
                                    normList);
                            Boolean bool = checkFirstOption(normList, fullStatus, coppysonedges, appPositions,
                                    eleclection,
                                    mutexMap, chooseOneList, togetherBCList);
                            if (!bool) {
                                continue;
                            }
                            synchronized (WareHouse) {
                                // 检查之前是否生成过该方案
                                if (!containsList(fullStatus, WareHouse)) {
                                    WareHouse.add(fullStatus);
                                    localResult.add(fullStatus);
                                    localGenerated++;
                                    roundGenerated.incrementAndGet();
                                }
                            }
                        }

                        return localResult;
                    }));
                }

                // 8) 等待本轮所有任务完成，合并本地结果到 result
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

            // 提早达到目标，跳出外层 while
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
     * @Description: 构造方案完整状态map：基线状态 + chosenIds 改B
     *               返回的是新的 Map，修改不影响 baseStatusMap
     */
    private Map<String, String> buildStatusMap(Map<String, String> baseStatusMap, List<String> chosenIds) {
        Map<String, String> statusMap = new LinkedHashMap<>(baseStatusMap);
        for (String id : chosenIds) {
            statusMap.put(id, "B");
        }
        return statusMap;
    }

    /**
     * @Description: 检查一个状态map对应的方案是否通过约束（checkFirstOption）
     */
    private boolean isValidScheme(
            List<Map<String, Object>> originalEdges,
            Map<String, String> statusMap,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection) {
        List<Map<String, Object>> testEdges = new ArrayList<>();
        for (Map<String, Object> e : originalEdges) {
            Map<String, Object> copy = new HashMap<>(e);
            String id = e.get("id").toString();
            copy.put("topologyStatusCode", statusMap.get(id));
            testEdges.add(copy);
        }
        return checkFirstOption(testEdges, appPositions, eleclection);
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
        Integer number = 0;
        for (Map<String, Object> k : edges) {
            if (k.get("topologyStatusCode").toString().equals("B")) {
                number++;
            }
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
            if (edge.get("topologyStatusCode").equals("B")) {
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
