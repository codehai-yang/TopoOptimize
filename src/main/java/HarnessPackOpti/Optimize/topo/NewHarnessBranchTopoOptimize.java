package HarnessPackOpti.Optimize.topo;

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
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import HarnessPackOpti.utils.GINEInferenceEngine;
import HarnessPackOpti.utils.Normalize;
import HarnessPackOpti.utils.ThreadPool;

import static HarnessPackOpti.utils.GINEInferenceEngine.objectMapper;

/**
 * 新的topo优化遗传算法
 */
public class NewHarnessBranchTopoOptimize {
    // 初代样本最低生成数量
    public static Integer LessRandomSamleNumber = 20;
    // 迭代最少样本数量
    public static Integer HybridizationLessRandomSamleNumber = 30;
    // top几的数量规定
    public static final Integer TopNumber = 20;
    // 绕线优化:分支累计绕线成本贡献阈值,超过则 B 改 C
    public static final Double WindingCostThreshold = 10.0;
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
        File outputFile = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\测试新遗传算法.txt");
        Files.write(outputFile.toPath(), topoOptimize.getBytes());
        System.out.println("JSON已成功输出到: " + outputFile.getAbsolutePath());
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
                bestBreakCount, breakCostMap, survivalRateByK, normList,
                mutexMap,
                chooseOneList, togetherBCList);
        System.out.println("初代方案生成" + initialSchemes.size() + "个方案耗时：" + (System.currentTimeMillis() - initTime));
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
                    bestBreakCount, breakCostMap, survivalRateByK, normList,
                    mutexMap, chooseOneList, togetherBCList,
                    jsonMap, edgeChooseBS, elecPosition, branchLength,
                    connection, multiLoopInfos, pointMap, hybridizationNumber);
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
                edgeChooseBS, elecPosition, branchLength, connection, multiLoopInfos, pointMap, findBest);
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
                jsonToMap,mutexMap, chooseOneList, togetherBCList,singleBCList, singleSCList,
                singleBSList, singleBSCList,eleclection);

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
     * @Complexity: O(N * (E log V + 成本重算)) N=方案数,E=edges,V=节点数
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
            JsonToMap jsonToMap,Map<String, Map<String, List<String>>> mutexMap,List<Map<String, List<String>>> chooseOneList,List<List<String>> togetherBCList,
            List<String> singleBCList,
            List<String> singleSCList,
            List<String> singleBSList,
            List<String> singleBSCList,Map<String, String> eleclection) throws Exception {

        FindBest findBest = new FindBest();
        if (mapList == null || mapList.isEmpty()) {
            return null;
        }

        // ① 统计每个分支的绕线成本贡献
        Map<String, Double> branchCostContribution = new HashMap<>();
        collectBranchCostContribution(mapList, adjacencyMatrixGraphConnector, edges, normList, branchCostContribution,
                jsonMap);
        System.out.println("[windingOptimize] 累计统计: " + branchCostContribution.size()
                + " 个分支有绕线贡献");

        // ② 收集高成本贡献的分支
        Set<String> highCostBranches = new HashSet<>();
        for (Map.Entry<String, Double> e : branchCostContribution.entrySet()) {
            if (e.getValue() > WindingCostThreshold) {
                highCostBranches.add(e.getKey());
            }
        }
        System.out.println("[windingOptimize] " + highCostBranches.size() + " 个分支超阈值(>"
                + WindingCostThreshold + "),待 B→C");
        if (highCostBranches.isEmpty()) {
            return findBest.findBest(mapList, "成本", TopNumber);
        }

        // ③④ 对每个方案做 B→C + 闭环消除 + 成本重算 + 约束检查
        List<Map<String, Object>> optimized = new ArrayList<>();
        int scrapCount = 0;
        List<Map<String, Double>> costDeail = new ArrayList<>();
        for (Map<String, Object> map : mapList) {
            Map<String, Object> result = processSingleSchemeForWinding(
                    map, highCostBranches, edges, normList, canChangeS, wearId,
                    jsonMap, mapper, projectCircuitInfoOutput, jsonToMap,mutexMap, chooseOneList, togetherBCList,singleBCList, singleSCList,
                    singleBSList, singleBSCList,eleclection,costDeail);
            if (result != null) {
                optimized.add(result);
            } else {
                scrapCount++;
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
     * @Description: 阶段一:遍历 mapList 统计每个分支的绕线成本贡献。
     *               对每个绕线回路,找全打通状态下的最短路径,与原回路差异分支均摊绕线成本作为该分支的贡献。
     */
    private void collectBranchCostContribution(
            List<Map<String, Object>> mapList,
            GenerateTopoMatrixConnector adjacencyMatrixGraphConnector,
            List<Map<String, Object>> edges,
            List<String> normList,
            Map<String, Double> branchCostContribution,
            Map<String, Object> jsonMap) {
        if (adjacencyMatrixGraphConnector == null) {
            return;
        }
        CalculateCircuitInfo acceptLoopInfo = new CalculateCircuitInfo();
        FindShortestPath findShortestPath = new FindShortestPath();
        List<String> allPoints = adjacencyMatrixGraphConnector.getAllPoint();
        List<List<Integer>> adj = adjacencyMatrixGraphConnector.getAdj();
        List<Map<String, String>> pointList = (List<Map<String, String>>) jsonMap.get("points");
        DecimalFormat df = new DecimalFormat("0.00");

        // 预构建:点对 → 边id(双向)
        Map<String, String> pairToEdgeId = new HashMap<>();
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

        for (Map<String, Object> map : mapList) {
            Object costObj = map.get("成本");
            if (!(costObj instanceof Map)) {
                continue;
            }
            Object ciObj = ((Map<?, ?>) costObj).get("circuitInfo");
            // 拿当前 top 方案的 edges(原状 B/C/S 混合),严格只用 top 自己的状态
            // 不兜底用原始 edges:原始 edges 没有 top 的状态,会污染成本计算
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topEdges = (List<Map<String, Object>>) map.get("serviceableEdges");
            if (topEdges == null) {
                continue;
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
            // 拿当前 top 方案的分支状态(用于过滤"原本是 B 的分支")
            @SuppressWarnings("unchecked")
            List<String> statue = (List<String>) map.get("serviceableStatue");
            if (statue == null || statue.size() != normList.size()) {
                continue;
            }
            if (!(ciObj instanceof List)) {
                continue;
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
                Map<String, Object> twoPointMsg = acceptLoopInfo.calculateCircuitInfo(materials, listname, copyJsonMap,
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
                Double newCost = Double.parseDouble(df.format(Double.parseDouble(
                        df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) * wetNumber)))
                        + Double
                                .parseDouble(
                                        df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) * wetNumber))
                        + Double.parseDouble(twoPointMsg.get("inline湿区防水塞成本补偿").toString())
                        + Double.parseDouble(twoPointMsg.get("inline湿区连接器成本补偿").toString())
                        + Double.parseDouble(twoPointMsg.get("回路打断成本").toString())
                        + Double.parseDouble(twoPointMsg.get("端子成本").toString())
                        + Double.parseDouble(twoPointMsg.get("回路导线成本").toString()));
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
        }
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
     * @Description: 对单个方案执行 B→C + 闭环消除 + 成本重算 + 约束检查。
     *               流程严格遵循硬约束:闭环必须消完才返回,约束不过则丢弃。
     */
    private Map<String, Object> processSingleSchemeForWinding(
            Map<String, Object> map,
            Set<String> highCostBranches,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> canChangeS,
            List<String> wearId,
            Map<String, Object> jsonMap,
            ObjectMapper mapper,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            JsonToMap jsonToMap,Map<String, Map<String, List<String>>> mutexMap,List<Map<String, List<String>>> chooseOneList,List<List<String>> togetherBCList,
            List<String> singleBCList,
            List<String> singleSCList,
            List<String> singleBSList,
            List<String> singleBSCList,Map<String, String> eleclection,List<Map<String, Double>> costDeail) throws Exception {
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> projectInfo = (Map<String, Object>) jsonMap.get("projectInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Boolean whetherOnLoop = caseInfo.get("loopcreate").toString().equals("true") ? true : false;

        List<String> originalStatue = (List<String>) map.get("serviceableStatue");
        if (originalStatue == null || originalStatue.size() != normList.size()) {
            return null;
        }
        List<String> statue = new ArrayList<>(originalStatue);

        // 1) B → C:贡献超阈值的分支
        int changedCount = 0;
        for (int i = 0; i < statue.size(); i++) {
            if ("B".equals(statue.get(i)) && highCostBranches.contains(normList.get(i))) {
                statue.set(i, "C");
                changedCount++;
            }
        }
        if (changedCount == 0) {
            return null;
        }

        // 2) 重新生成 edges 并重算成本(深拷贝 jsonMap 避免污染)
        Map<String, Object> threadLocalJsonMap = mapper.readValue(
                mapper.writeValueAsString(jsonMap), Map.class);
        List<Map<String, Object>> serviceableEdge = createNewEdges(statue, edges, normList);
        threadLocalJsonMap.put("edges", serviceableEdge);

        List<Map<String, Object>> handleList = bestOptionVariation(serviceableEdge, singleBCList, singleSCList,
                singleBSList, singleBSCList, normList, jsonMap, eleclection, wearId, mutexMap,
                chooseOneList, togetherBCList, whetherOnLoop);
        if (handleList == null || handleList.size() == 0) {
            Map<String,Object> objectMap = handleList.get(0);
            Map<String, Double> cost = (Map<String, Double>) objectMap.get("成本");
            if (costDeail.contains(cost)) {
                System.out.println("成本重复");
                return null;
            }
            List<Map<String, Object>> mapList = (List<Map<String, Object>>) objectMap.get("serviceableEdges");
            List<List<String>> outputLoopList = recognizeLoopNew(mapList);
            for (List<String> loop : outputLoopList) {
                boolean containsWearId = false;
                for (String s1 : wearId) {
                    if (loop.contains(s1)) {
                        containsWearId = true;
                        break;
                    }
                }
                if (containsWearId || whetherOnLoop) {
                    System.out.println("handleAndShowTop: 输出前发现未消除闭环！" + loop
                            + (containsWearId ? "[含 wearId]" : "[whetherOnLoop=true]"));
                }
            }
            List<Map<String, String>> topoOptimizeResult = new ArrayList<>();
            for (Map<String, Object> mapTemp : mapList) {
                Map<String, String> result = new HashMap<>();
                result.put("edgeId", mapTemp.get("id").toString());
                result.put("statue", mapTemp.get("topologyStatusCode").toString());
                topoOptimizeResult.add(result);
            }
            // 保持线程安全 浅拷贝一份
            HashMap<String, Object> newJsonMap = new HashMap<>(jsonMap);
            newJsonMap.put("edges", mapList);
            String s = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(objectMapper.writeValueAsString(newJsonMap));
            Map<String, Object> map2 = jsonToMap.TransJsonToMap(s);
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) map2.get("projectCircuitInfo");
            Map<String, Double> projectCost = new HashMap<>();
            projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
            projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
            projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

            map2.put("成本", projectCost);
            map2.put("topoId", topoInfoMap.get("id").toString());
            map2.put("caseId", projectInfo.get("caseId").toString());
            map2.put("topoOptimizeResult", topoOptimizeResult);
            map2.put("finishStatue", "normal");
            map2.put("initializationScheme", false);
            return map2;
        }else {
            return null;
        }
    }

    /**
     * @Description: 对top的方案进行再次变异 结果中分支打断状况为S的 可以变化的进行变化 在总成本小于3的情况下
     *               选择当前的方案(主要为了降低S的数量)
     * @input: findBest 当前最优的top10 方案
     * @input: singleBCList 分支打断可选BC的集合
     * @input: singleSCList 分支打断可选SC的集合
     * @input: singleBSLis 分支打断可选BS的集合
     * @input: singleBSCList 分支打断可选BSC的集合
     * @input: normList 按照顺序的id排放顺序
     * @input: jsonMap 最初获取的json字符串，转为map格式
     * @input: eleclection 用电器对应的位置点
     * @input: wearId 穿腔的分支
     * @input: mutexMap 互斥的情况的分支
     * @input: chooseOneList 多选一的分支
     * @input:togetherBCList 组团一起变的分支
     * @Return: 返回修改后的top的方案
     */
    public List<Map<String, Object>> bestOptionVariation(List<Map<String, Object>> findBest,
                                                         List<String> singleBCList,
                                                         List<String> singleSCList,
                                                         List<String> singleBSList,
                                                         List<String> singleBSCList,
                                                         List<String> normList,
                                                         Map<String, Object> jsonMapOrigin,
                                                         Map<String, String> eleclection,
                                                         List<String> wearId,
                                                         Map<String, Map<String, List<String>>> mutexMap,
                                                         List<Map<String, List<String>>> chooseOneList,
                                                         List<List<String>> togetherBCList, Boolean whetherOnLoop) throws Exception {
        // 状态检查
        if (threadPool.shouldStop()) {
            return null;
        }
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> jsonMap = new HashMap<>(jsonMapOrigin);
        JsonToMap jsonToMap = new JsonToMap();
        List<Map<String, Object>> bestOption = new ArrayList<>();
        // 深拷贝 edges，避免多线程并发时修改共享边对象导致状态不一致
        List<Map<String, Object>> edgesOrigin = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> edge : edgesOrigin) {
            edges.add(new HashMap<>(edge));
        }
        jsonMap.put("edges", edges);
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        // 可打B的分支
        List<String> canChangeToB = new ArrayList<>();
        canChangeToB.addAll(singleBCList);
        canChangeToB.addAll(singleBSList);
        canChangeToB.addAll(singleBSCList);
        // S可还原为C的集合
        List<String> restore = new ArrayList<>();
        restore.addAll(singleSCList);
        restore.addAll(singleBSCList);

        for (Map<String, Object> map : findBest) {
            List<String> statueList = (List<String>) map.get("serviceableStatue");
            // 首先进行一个分支打断代价计算，将当中S的打断代价为0并且在符合分支拓扑约束的条件下 将S改为B
            List<Map<String, Object>> firstEdgesDetail = createNewEdges(statueList, edges, normList);
            jsonMap.put("edges", firstEdgesDetail);
            String firstoptimizeInterface = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> firstObjectMap = jsonToMap.TransJsonToMap(firstoptimizeInterface);
            // 计算每一个分支的打断代价
            Map<String, Object> firstbundeleRelatedCircuitInfo = (Map<String, Object>) firstObjectMap
                    .get("bundeleRelatedCircuitInfo");
            Map<String, Object> circuitInfo = (Map<String, Object>) firstObjectMap
                    .get("projectCircuitInfo");
            double costTotal = Double.parseDouble(circuitInfo.get("总成本").toString());
            Map<String, Double> firstbreakCostMap = new HashMap<>();
            for (String s : firstbundeleRelatedCircuitInfo.keySet()) {
                Map<String, Object> edgeMap = (Map<String, Object>) firstbundeleRelatedCircuitInfo.get(s);
                Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                firstbreakCostMap.put(s, Double
                        .parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
            }
            // 找出分支为S的id 打断代价s为0的改为b
            for (int i = 0; i < statueList.size(); i++) {
                if (statueList.get(i).equals("S")) {
                    String id = normList.get(i);
                    if (firstbreakCostMap.get(id) == 0) {
                        List<String> newEdges = statueList.stream().collect(Collectors.toList());
                        newEdges.set(normList.indexOf(id), "B");
                        List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
                        Boolean flag = checkFirstOption(normList, newEdges, edgesDetail, appPositions, eleclection,
                                mutexMap, chooseOneList, togetherBCList);
                        if (flag) {
                            statueList.set(normList.indexOf(id), "B");
                        }
                    }
                }
            }
            // 找出当中可还原S的集合
            List<String> canRestoreid = new ArrayList<>();
            for (int i = 0; i < statueList.size(); i++) {
                if (statueList.get(i).equals("S") && restore.contains(normList.get(i))) {
                    canRestoreid.add(normList.get(i));
                }
            }
            // 对每一个S 还原成C 检查是否存在闭环 如果存在闭环 在闭环里面能够变为B的分支 检查当前的方案是否合理 合理的情况下计算成本 如果成本与原方案相比小于3
            // 则用该方案
            for (String s : canRestoreid) {
                List<String> newEdges = statueList.stream().collect(Collectors.toList());
                newEdges.set(normList.indexOf(s), "C");
                List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
                // 对当前的方案进行一个检查
                // 这里只消除了存在穿腔的闭环回路
                int loopIterationLimit = 300; // 防止死循环：最多打断 100 次
                while (loopIterationLimit-- > 0) {
                    // 闭环检测
                    List<List<String>> lists = recognizeLoopNew(edgesDetail);
                    Set<String> loopList = new HashSet<>();
                    for (String s1 : wearId) {
                        for (List<String> list : lists) {
                            if (list.contains(s1)) {
                                loopList.addAll(list);
                            }
                        }
                    }
                    // 如果开启了消除闭环，那么将存在闭环的分支也加入进去进行打断，计算成本
                    if (whetherOnLoop) {
                        for (List<String> list : lists) {
                            for (String string : list) {
                                if (!loopList.contains(string)) {
                                    loopList.add(string);
                                }
                            }
                        }
                    }
                    loopList.retainAll(canChangeToB);
                    Map<String, Double> costMap = new HashMap<>();
                    if (loopList.size() == 0) {
                        // 区分两种情况：
                        // (a) lists 为空：图中没有任何闭环，可以安全还原
                        // (b) lists 不为空但 loopList 为空：存在闭环，但所有闭环分支都不可改为 B
                        // 这种情况必须保持 S 状态（特别是含 wearId 的闭环）
                        if (lists.isEmpty()) {
                            statueList.set(normList.indexOf(s), "C");
                        } else {
                            System.out.println("警告：闭环存在但无可打断分支，保持 S 状态。canRestoreid=" + s
                                    + ", lists.size=" + lists.size());
                            statueList.set(normList.indexOf(s), "S");
                        }
                        break;
                    }

                    if (lists.size() > 0) {
                        // 优化：一次性计算 breakCostMap（基于当前 newEdges 状态）
                        // 避免对 loopList 中每个分支逐个调用 projectCircuitInfoOutput（单次 14s）
                        Map<String, Double> breakCostMap = new HashMap<>();
                        try {
                            // 先校验当前 newEdges 状态合法
                            Boolean baseValid = checkFirstOption(normList, newEdges, edgesDetail, appPositions,
                                    eleclection, mutexMap, chooseOneList, togetherBCList);
                            if (baseValid) {
                                jsonMap.put("edges", edgesDetail);
                                String circuitResult = projectCircuitInfoOutput
                                        .projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
                                Map<String, Object> circuitObj = jsonToMap.TransJsonToMap(circuitResult);
                                Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) circuitObj
                                        .get("bundeleRelatedCircuitInfo");
                                for (String s1 : loopList) {
                                    Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo
                                            .get(s1);
                                    if (edgeMap == null) {
                                        continue;
                                    }
                                    Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap
                                            .get("circuitInfoIntergation");
                                    if (edgeDetail == null) {
                                        continue;
                                    }
                                    Object costObj = edgeDetail.get("分支打断代价");
                                    if (costObj == null) {
                                        continue;
                                    }
                                    breakCostMap.put(s1, Double.parseDouble(costObj.toString()));
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("bestOptionVariation: 计算 breakCostMap 异常: " + e.getMessage());
                        }

                        // 在闭环分支中找满足约束、增量代价最低的
                        for (String s1 : loopList) {
                            List<String> calculateLoop = newEdges.stream().collect(Collectors.toList());
                            calculateLoop.set(normList.indexOf(s1), "B");
                            List<Map<String, Object>> calculateEdgesDetail = createNewEdges(calculateLoop, edges,
                                    normList);
                            Boolean sonSate = checkFirstOption(normList, calculateLoop, calculateEdgesDetail,
                                    appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                            if (!sonSate) {
                                continue;
                            }
                            if (breakCostMap.containsKey(s1)) {
                                // costMap 存增量代价（breakCostMap[s1] 已经是"分支打断代价"= 增量）
                                costMap.put(s1, breakCostMap.get(s1));
                            }
                        }
                    }
                    if (costMap.size() > 0) {
                        String minKey = null;
                        double minValue = Double.MAX_VALUE;
                        // 找出增量代价最小的方案（costMap[s1] 现在存的是 breakCostMap 增量）
                        for (Map.Entry<String, Double> entry : costMap.entrySet()) {
                            if (entry.getValue() < minValue) {
                                minValue = entry.getValue();
                                minKey = entry.getKey();
                            }
                        }
                        if (minValue < 3) {
                            // 增量代价满足约束，打断该分支
                            newEdges.set(normList.indexOf(minKey), "B");
                            edgesDetail = createNewEdges(newEdges, edges, normList);
                            statueList.set(normList.indexOf(s), "C");
                            statueList.set(normList.indexOf(minKey), "B");
                            costTotal = costTotal + minValue;
                        } else {
                            // 增量代价大于3继续保持原 S 状态
                            statueList.set(normList.indexOf(s), "S");
                            break;
                        }
                    } else {
                        // 闭环存在但没有可打断分支，保持原 S 状态
                        statueList.set(normList.indexOf(s), "S");
                        break;
                    }
                }
                if (loopIterationLimit <= 0) {
                    System.out.println("闭环打断达到最大次数限制，强制退出 while 循环");
                }
            }
            List<Map<String, Object>> EdgesDetail = createNewEdges(statueList, edges, normList);
            List<String> newEdges1 = statueList.stream().collect(Collectors.toList());
            Boolean sonSate = checkFirstOption(normList, newEdges1, EdgesDetail, appPositions, eleclection, mutexMap,
                    chooseOneList, togetherBCList);
            if (!sonSate) {
                continue;
            }
            jsonMap.put("edges", EdgesDetail);
            String optimizeInterface = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> objectMap = jsonToMap.TransJsonToMap(optimizeInterface);
            // 计算每一个分支的打断代价
            Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) objectMap
                    .get("bundeleRelatedCircuitInfo");
            Map<String, Double> breakCostMap = new HashMap<>();
            for (String s : bundeleRelatedCircuitInfo.keySet()) {
                Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
                Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                breakCostMap.put(s, Double
                        .parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
            }
            breakCostMap = sortMapByDoubleValue(breakCostMap);
            // 满足条件的分支打断代价等于0的分支id
            List<String> list0 = new ArrayList<>();
            // 找出当中分支打断代价等于0的分支
            breakCostMap.entrySet().stream().filter(entry -> entry.getValue() == 0).forEach(entry -> {
                String key = entry.getKey();
                list0.add(key);
            });
            for (String s : list0) {
                List<String> newEdges = statueList.stream().collect(Collectors.toList());
                newEdges.set(normList.indexOf(s), "B");
                List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
                // 对当前的方案进行一个检查
                Boolean flag = checkFirstOption(normList, newEdges, edgesDetail, appPositions, eleclection, mutexMap,
                        chooseOneList, togetherBCList);

                if (flag) {
                    statueList.set(normList.indexOf(s), "B");
                } else {
                    continue;
                }
            }

            // 将分支打断代价小于三块的改为B 计算总成本，如果新的总成本不超过之前的三块 则就用这个方案
            List<Map<String, Object>> betweenEdgeresult = createNewEdges(statueList, edges, normList);
            jsonMap.put("edges", betweenEdgeresult);
            String betweenoptimizeInterfacesresult = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> betweenobjectMapresult = jsonToMap.TransJsonToMap(betweenoptimizeInterfacesresult);
            Map<String, Object> betweenprojectCircuitInfo = (Map<String, Object>) betweenobjectMapresult
                    .get("projectCircuitInfo");
            Double betweencurrentalCost = (Double) betweenprojectCircuitInfo.get("总成本");
            // 找出分支打断代价小于3的id
            List<String> list3 = new ArrayList<>();
            breakCostMap.entrySet().stream().filter(entry -> 0 < entry.getValue() && entry.getValue() < 3)
                    .forEach(entry -> {
                        String key = entry.getKey();
                        if (canChangeToB.contains(key)) {
                            list3.add(key);
                        }

                    });
            // list3逐一进行检擦
            for (String s : list3) {
                List<String> newEdges = statueList.stream().collect(Collectors.toList());
                newEdges.set(normList.indexOf(s), "B");
                List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
                Boolean flag = checkFirstOption(normList, newEdges, edgesDetail, appPositions, eleclection, mutexMap,
                        chooseOneList, togetherBCList);
                if (flag) {
                    jsonMap.put("edges", edgesDetail);
                    String betweenoptimizeInterfacesresultSon = projectCircuitInfoOutput
                            .projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
                    Map<String, Object> betweenobjectMapresultSon = jsonToMap
                            .TransJsonToMap(betweenoptimizeInterfacesresultSon);
                    Map<String, Object> betweenprojectCircuitInfoSon = (Map<String, Object>) betweenobjectMapresultSon
                            .get("projectCircuitInfo");
                    Double betweencurrentalCostSon = (Double) betweenprojectCircuitInfoSon.get("总成本");

                    if (betweencurrentalCostSon < betweencurrentalCost
                            || betweencurrentalCostSon - betweencurrentalCost < 2) {
                        statueList.set(normList.indexOf(s), "B");
                        betweencurrentalCost = betweencurrentalCostSon;
                    }
                }
            }

            // 对最终的方案进行一个计算 并且按照格式进行一个返回
            List<Map<String, Object>> finalEdgeresult = createNewEdges(statueList, edges, normList);
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
                boolean broken = forceBreakLoops(statueList, edges, normList, wearId, canChangeToB, whetherOnLoop,
                        appPositions, eleclection, mutexMap, chooseOneList, togetherBCList,
                        projectCircuitInfoOutput, objectMapper, jsonMap, jsonToMap);
                if (broken) {
                    // 重新计算最终边
                    finalEdgeresult = createNewEdges(statueList, edges, normList);
                }
            }
            jsonMap.put("edges", finalEdgeresult);
            String optimizeInterfacesresult = projectCircuitInfoOutput
                    .projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> objectMapresult = jsonToMap.TransJsonToMap(optimizeInterfacesresult);
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMapresult.get("projectCircuitInfo");
            Map<String, Double> finalCostDetail = new HashMap<>();
            finalCostDetail.put("总成本", (Double) projectCircuitInfo.get("总成本"));
            finalCostDetail.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));
            finalCostDetail.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("成本", finalCostDetail);
            finalResult.put("serviceableStatue", statueList);
            System.out.println(statueList);
            finalResult.put("serviceableEdges", finalEdgeresult);
            bestOption.add(finalResult);
        }
        return bestOption;
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
        int maxIterations = 100;
        while (maxIterations-- > 0) {
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
            // 选一个打断（优先选 wearId 闭环中的分支）
            String pickId = null;
            for (List<String> loop : targetLoops) {
                for (String id : loop) {
                    if (breakableIds.contains(id)) {
                        pickId = id;
                        break;
                    }
                }
                if (pickId != null)
                    break;
            }
            if (pickId == null) {
                break;
            }
            // 验证打断后方案合法
            statueList.set(normList.indexOf(pickId), "B");
            List<Map<String, Object>> afterEdges = createNewEdges(statueList, edges, normList);
            Boolean ok = checkFirstOption(normList, statueList, afterEdges, appPositions, eleclection, mutexMap,
                    chooseOneList, togetherBCList);
            if (!ok) {
                System.out.println("forceBreakLoops: 打断 " + pickId + " 后方案不合法，回滚");
                statueList.set(normList.indexOf(pickId), "S"); // 回滚到 S（保留原状态的最佳猜测）
                break;
            }
            System.out.println("forceBreakLoops: 已强制打断 " + pickId);
            anyBroken = true;
        }
        return anyBroken;
    }


    /** 安全 double 解析,null/异常返 0 */
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

    /** 安全取字符串,null/空返 null */
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
            Map<String, String> pointMap, List<Map<String, Object>> findBestPre) throws Exception {
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
                for (int i = 0; i < serviceableStatue.size(); i++) {
                    if (serviceableStatue.get(i).equals("C") && edgeChooseBS.contains(normList.get(i))) {
                        serviceableStatue.set(i, "B");
                    }
                }

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
                // 对当前的情况进行一个检查 当存在闭环的状况 将当中最打断成本最小的进行打S 直到没有闭环的时候跳出循环
                boolean scrapOrNot = false;
                while (true) {
                    serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                    List<List<String>> recognizeLoopList = recognizeLoopNew(serviceableEdge);
                    // 每一个闭环中存在一个穿腔的分支的 整组成整个闭环的分支进行记录
                    List<String> recognizeLoopIdList = new ArrayList<>();
                    for (List<String> loop : recognizeLoopList) {
                        for (String s : loop) {
                            if (wearId.contains(s)) {
                                recognizeLoopIdList.addAll(loop);
                                break;
                            }
                        }
                    }

                    // 检查当前方案中是否存在寻妖处理的闭环
                    if (recognizeLoopIdList.size() != 0) {
                        // 将recognizeLoopIdList 里面分支打断成本最小的打断状况修改为S
                        String minCostKey = null;
                        List<String> keyList = findMinCostKey(recognizeLoopIdList, breakCostMap);
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
                        serviceableStatue.set(normList.indexOf(minCostKey), "B");
                        // 关键：打断后重新计算全图成本和 breakCostMap（不能简单累加增量）
                        // 打断一个分支后，回路走线、导线选型、连接器配置都变化，
                        // 后续分支的打断代价是相对"新图状态"的，不是原始图的累加。
                        if (!refreshCircuitInfo(serviceableStatue, edges, normList, threadLocalJsonMap,
                                projectCircuitInfoOutput, mapper, jsonToMap, costResultData, breakCostMap)) {
                            scrapOrNot = true;
                            break;
                        }
                        map.put("成本", costResultData);
                        map.put("serviceableEdges", serviceableEdge);
                        map.put("serviceableStatue", serviceableStatue);
                    } else {
                        // 看是否开启闭环消除
                        if (whetherOnLoop) {
                            while (true) {
                                serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                                List<List<String>> recognizeLoopListSon = recognizeLoopNew(serviceableEdge);
                                if (recognizeLoopListSon.size() == 0) {
                                    break;
                                } else {
                                    Set<String> son = new HashSet<>();
                                    for (List<String> loop : recognizeLoopListSon) {
                                        son.addAll(loop);
                                    }
                                    List<String> keyList = findMinCostKey(son.stream().collect(Collectors.toList()),
                                            breakCostMap);
                                    String minCostKey = null;
                                    for (String s : keyList) {
                                        if (canChangeS.contains(s)) {
                                            minCostKey = s;
                                            break;
                                        }
                                    }
                                    // 如果当前的方案中没有可以打断的分支，则勾选一个打断代价最小的进行打断
                                    if (minCostKey == null) {
                                        minCostKey = keyList.get(0);
                                    }
                                    serviceableStatue.set(normList.indexOf(minCostKey), "B");
                                    // 关键：打断后重新计算全图成本和 breakCostMap
                                    if (!refreshCircuitInfo(serviceableStatue, edges, normList, threadLocalJsonMap,
                                            projectCircuitInfoOutput, mapper, jsonToMap, costResultData,
                                            breakCostMap)) {
                                        break;
                                    }
                                }
                            }
                        }
                        // serviceableList.add(serviceableStatue);
                        map.put("成本", costResultData);
                        map.put("serviceableEdges", serviceableEdge);
                        map.put("serviceableStatue", serviceableStatue);
                        break;
                    }
                }
                // 这里先按null返回，因为如果跳出大的循环，则其余方案无法检测到
//                if (scrapOrNot) {
//                    return null;
//                }
                System.out.println("遗传算法返回左右top时，每个方案闭环检测结束耗时" + (System.currentTimeMillis() - startTime));
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
     * @param costResultData 输出的成本信息（会被覆盖）
     * @param breakCostMap   输出的分支打断代价（会被覆盖）
     * @Return: 是否计算成功
     * @Complexity: O(V+E)，主要由 projectCircuitInfoOutput 内部计算决定
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
     *
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
            Map<Integer, Double> survivalRateByK,
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
            Map<String, String> pointMap, int hybridizationNumber) throws Exception {
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
        // (你实测 1000 个方案 ≈ 800ms,100 个父本全遍历会到 10w+,没必要)
        final int perGenTarget = HybridizationLessRandomSamleNumber;
        long phase1Time = System.currentTimeMillis();
        List<List<String>> phase1 = new ArrayList<>();
        for (List<String> parent : parentStatues) {
            if (phase1.size() >= perGenTarget) {
                System.out.println("[hybridization] 阶段一早停:累计 " + phase1.size());
                break;
            }
            List<List<String>> variants = generateInitialSchemes(
                    edges, canBreakToBSet, parent, appPositions, eleclection,
                    bestBreakCount, breakCostMap, survivalRateByK, normList,
                    mutexMap, chooseOneList, togetherBCList);
            phase1.addAll(variants);
        }
        System.out.println("[hybridization] 阶段一累计 " + phase1.size() + " 个有效方案,耗时 "
                + (System.currentTimeMillis() - phase1Time) + " ms");

        // 3) 阶段二:交叉变异(以 TopDetail 父本为基准,两两配对各取 1 mutation)
        long phase2Time = System.currentTimeMillis();
        int crossTarget = Math.max(HybridizationLessRandomSamleNumber, parentStatues.size() * 2);
        List<List<String>> phase2Raw = crossoverMutation(
                parentStatues, initialScheme, normList, crossTarget);
        System.out.println("[hybridization] 阶段二原始生成 " + phase2Raw.size() + " 个,耗时 "
                + (System.currentTimeMillis() - phase2Time) + " ms");

        // 4) 阶段二约束检查 + 入仓(generateInitialSchemes 已对阶段一做过去重,这里只补阶段二)
        long phase2CheckTime = System.currentTimeMillis();
        List<List<String>> phase2Valid = new ArrayList<>();
        for (List<String> child : phase2Raw) {
            if (validateAndAddToWarehouse(child, edges, normList, appPositions, eleclection,
                    mutexMap, chooseOneList, togetherBCList)) {
                phase2Valid.add(child);
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
            @SuppressWarnings("unchecked")
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
                        bestBreakCount, breakCostMap, survivalRateByK, normList,
                        mutexMap, chooseOneList, togetherBCList);
                allSchemes.addAll(more);
            }
            int added = allSchemes.size() - beforeCount;
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
        // 记录迭代统计到Excel
        int generatedCount = allSchemes.size();
        int aiFilteredCount = 0;
        long filterTimeMs = 0;
        ObjectMapper mapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        if (mapList != null && !mapList.isEmpty()) {
            Map<String, Object> bestResult = mapList.get(0);
            Map<String, Object> costMap = (Map<String, Object>) bestResult.get("成本");
            // 计算每轮迭代的最优成本，加到excel预测成本的后一列
            List<String> serviceableStatue = (List<String>) bestResult.get("serviceableStatue");
            List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
            Map<String, Object> threadLocalJsonMap = mapper.readValue(
                    mapper.writeValueAsString(jsonMap),
                    Map.class);
            threadLocalJsonMap.put("edges", serviceableEdge);
            String betweenoptimizeInterfacesresult = null;
            try {
                betweenoptimizeInterfacesresult = projectCircuitInfoOutput
                        .projectCircuitInfoOutput(mapper.writeValueAsString(jsonMap));
            } catch (Exception e) {
                return TopDetail;
            }
            Map<String, Object> betweenobjectMapresult = jsonToMap.TransJsonToMap(betweenoptimizeInterfacesresult);
            Map<String, Object> betweenprojectCircuitInfo = (Map<String, Object>) betweenobjectMapresult
                    .get("projectCircuitInfo");
            Double betweencurrentalCost = (Double) betweenprojectCircuitInfo.get("总成本");
            if (costMap != null) {
                double bestCost = Double.parseDouble(costMap.get("总成本").toString());
                double bestWeight = Double.parseDouble(costMap.get("总重量").toString());
                double bestLength = Double.parseDouble(costMap.get("总长度").toString());
                String excelPath = "F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\iteration_stats_"
                        + "testAItrue"
                        + ".xlsx";
                recordIterationStatsToExcel(
                        hybridizationNumber, generatedCount, aiFilteredCount, filterTimeMs,
                        bestCost, bestWeight, bestLength, findBestTimeMs, excelPath, betweencurrentalCost);
            }
        }
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
     * @Description: 交叉变异。两两随机配对父本,各取 1 个 mutation 位置叠加到子代,
     *               生成 2-mutation 子代方案集合。
     *               父本均来自上一代 TopDetail,其"突变位置"=相对 initialScheme 被改 B 的位置。
     * @input: parentStatues 父本状态列表(长度 = normList.size())
     * @input: baseScheme 基准状态(initialScheme)
     * @input: normList 分支 id 按顺序排列
     * @input: targetCount 目标生成数(实际可能因去重略少)
     * @Return: 子代状态列表(已去重,未做约束检查,由调用方统一校验)
     * @Complexity: O(targetCount * parents)
     */
    private List<List<String>> crossoverMutation(
            List<List<String>> parentStatues,
            List<String> baseScheme,
            List<String> normList,
            int targetCount) {
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

        Random rnd = new Random();
        Set<String> seen = new HashSet<>();
        int maxAttempts = targetCount * 10 + 100;
        int attempts = 0;
        final int n = baseScheme.size();

        while (result.size() < targetCount && attempts < maxAttempts) {
            attempts++;
            int idx1 = rnd.nextInt(parentStatues.size());
            int idx2 = rnd.nextInt(parentStatues.size());
            if (idx1 == idx2) {
                continue;
            }
            List<String> p1 = parentStatues.get(idx1);
            List<String> p2 = parentStatues.get(idx2);
            if (p1.size() != n || p2.size() != n) {
                continue;
            }

            // 提取每个父本相对 base 的 mutation 位置(被改 B 的位置)
            List<Integer> muts1 = new ArrayList<>();
            List<Integer> muts2 = new ArrayList<>();
            for (int i = 0; i < n; i++) {
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

            // 各取 1 个(随机选)
            int pos1 = muts1.get(rnd.nextInt(muts1.size()));
            int pos2 = muts2.get(rnd.nextInt(muts2.size()));
            if (pos1 == pos2) {
                // 同位置不构成有效交叉
                continue;
            }

            // 以 p1 为基底,叠加 p2 的 mutation → 子代必有 2 个 mutation
            List<String> child = new ArrayList<>(p1);
            child.set(pos2, "B");

            // 去重签名(按 normList 顺序,不排序)
            String sig = String.join(",", child);
            if (seen.add(sig)) {
                result.add(child);
            }
        }
        return result;
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

        // 1) 构造 statusMap(id -> status)
        Map<String, String> statusMap = new LinkedHashMap<>();
        for (int i = 0; i < normList.size(); i++) {
            statusMap.put(normList.get(i), fullStatus.get(i));
        }

        // 2) 4 关 isValidScheme
        if (!isValidScheme(originalEdges, statusMap, appPositions, eleclection)) {
            return false;
        }

        // 3) checkFirstOption(回路/互斥/组团)
        List<Map<String, Object>> copyEdges = createNewEdges(fullStatus, originalEdges, normList);
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
        // 最终目标方案数
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

        // 5) 计算概率表（用于抽样支按权重加权采样）
        // breakCostMap 为空时使用均匀概率
        final Map<String, Double> probMap;
        if (breakCostMap != null && !breakCostMap.isEmpty()) {
            probMap = calcBreakProbabilityByCost(breakCostMap, canBreakToBSet, MaxProbability, WeightFactor);
        } else {
            // 没有代价信息时，均匀概率
            Map<String, Double> uniform = new HashMap<>();
            for (String id : breakableIds) {
                uniform.put(id, MaxProbability);
            }
            probMap = uniform;
        }

        // 5.5) 预枚举：枚举支固定 k=1, k=2 两轮（防栈深/执行时间问题）
        // 其余 k 一律走抽样支 → 线程池并行抽
        // 安全兜底：C(N,k) ≤ 1000 才走枚举（防 N 特别大时 k=2 枚举爆炸）
        // 枚举阈值参考 calcSurvivalRateByBreakCount 保持一致
        final int maxEnumerateCombinations = 1000;
        List<Integer> sampleKList = new ArrayList<>();
        for (int k = 1; k <= adjustedBestBreakCount; k++) {
            long totalComb = combination(breakableIds.size(), k);
            // k ≤ 2 且组合数 ≤ 1000 → 枚举；否则抽样
            boolean shouldEnumerate = (k <= 2) && (totalComb <= maxEnumerateCombinations);
            if (shouldEnumerate) {
                // 枚举支：单线程预枚举 → 4 道关 → 入仓库 + 入 result
                List<List<String>> allComb = new ArrayList<>();
                enumerateCombinations(breakableIds, k, 0, new ArrayList<>(), allComb);
                for (List<String> chosen : allComb) {
                    Map<String, String> statusMap = buildStatusMap(baseStatusMap, chosen);
                    if (!isValidScheme(originalEdges, statusMap, appPositions, eleclection)) {
                        continue;
                    }
                    List<String> fullStatus = new ArrayList<>();
                    for (String id : baseStatusMap.keySet()) {
                        fullStatus.add(statusMap.get(id));
                    }
                    List<Map<String, Object>> coppysonedges = createNewEdges(fullStatus, originalEdges, normList);
                    Boolean bool = checkFirstOption(normList, fullStatus, coppysonedges, appPositions,
                            eleclection, mutexMap, chooseOneList, togetherBCList);
                    if (!bool) {
                        continue;
                    }
                    synchronized (WareHouse) {
                        if (!containsList(fullStatus, WareHouse)) {
                            WareHouse.add(fullStatus);
                            result.add(fullStatus);
                        }
                    }
                }
            } else {
                // 抽样支：放进 sampleKList，留给线程池并行抽
                sampleKList.add(k);
            }
        }
        // 预枚举完已够数 → 早退
        if (result.size() >= finalMinCount) {
            return result;
        }
        // 没有抽样支 → 枚举完没够数，直接返回（兜底防死循环）
        if (sampleKList.isEmpty()) {
            return result;
        }
        // 抽样支的 k 总和（用于 perKTarget 加权）
        final int totalKSum = sampleKList.stream().mapToInt(Integer::intValue).sum();

        // 6) 多轮并行生成：每轮目标 = "还差多少"；外层 while 一直循环到 result.size() ≥ finalMinCount
        // 任务数上限，线程池里提交的任务
        final int baseTaskCount = Math.min(11, Math.max(1, finalMinCount / 50));
        final int maxRounds = finalMinCount * 2;
        int round = 0;
        while (result.size() < finalMinCount && round < maxRounds) {
            final int currentRound = round;
            round++;
            int remaining = finalMinCount - result.size();
            // 本轮任务数：剩余数较少时适当缩减
            int taskCount = Math.min(baseTaskCount, Math.max(1, remaining / 50 + 1));
            // 每任务目标：剩余数量摊到每个任务，每个任务本轮目标产出
            int targetPerTask = Math.max(1, (remaining + taskCount - 1) / taskCount);
            // 跨任务共享本轮生成数（早退优化）
            AtomicInteger roundGenerated = new AtomicInteger(0);

            // 7) 提交本轮并行任务
            List<Future<List<List<String>>>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < taskCount; t++) {
                    final int taskId = t;
                    // 这个任务本轮要产出的有效方案总数
                    final int localTarget = targetPerTask;
                    // 本轮全员总目标
                    final int roundTarget = remaining;
                    futures.add(threadPool.submit(() -> {
                        // 每个任务独立 Random，避免共享冲突
                        Random rnd = new Random(System.nanoTime() + taskId * 31L + currentRound * 131L);
                        // 本地结果集：避免共享 result 的锁竞争，任务结束返回再合并
                        List<List<String>> localResult = new ArrayList<>();
                        int localGenerated = 0;

                        // 按 sampleKList 循环（k 越大抽越多）
                        // 抽样数 = localTarget * k / totalKSum，所有 k 抽样数总和 ≈ localTarget
                        // 命中 localTarget 或 roundTarget 后快速退出
                        for (int k : sampleKList) {
                            if (localGenerated >= localTarget)
                                break;
                            if (roundGenerated.get() >= roundTarget)
                                break;

                            // 单 k 抽样目标
                            int perKTarget = Math.max(1, (localTarget * k + totalKSum - 1) / totalKSum);

                            // 抽样支：按 probMap 加权随机抽（低代价分支更易被选中）
                            List<List<String>> pickedCombinations = weightedSampleCombinations(breakableIds, k,
                                    perKTarget, probMap, rnd);

                            for (List<String> chosen : pickedCombinations) {
                                if (localGenerated >= localTarget)
                                    break;
                                if (roundGenerated.get() >= roundTarget)
                                    break;

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
     * @Description: 按 probMap 加权随机抽 count 个 k-组合（不重复）
     *               算法：每个分支按权重生成"加权随机键" key = random^(1/weight)，
     *               按 key 降序取前 k 个分支，组成一个组合
     *               权重高的分支更易排前，权重全相等时退化为均匀随机
     *               多次重复+去重，得到 count 个不同组合
     *               时间复杂度：O(count * n * log n)，n = breakableIds.size()
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
            // 算每个分支的"加权随机键"
            double[] keys = new double[n];
            for (int i = 0; i < n; i++) {
                double w = probMap.getOrDefault(list.get(i), 0.0);
                if (w <= 0) {
                    // 权重为 0 几乎不可能被选中，给个极小兜底避免 Math.pow 异常
                    w = 1e-9;
                }
                double u = random.nextDouble();
                // key 越大排序越靠前；w 大 → 1/w 小 → u^(1/w) 接近 1（排前）
                keys[i] = Math.pow(u, 1.0 / w);
            }
            // 按 key 降序排前 k 个
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(keys[b], keys[a]));
            List<String> picked = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                picked.add(list.get(order[i]));
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
