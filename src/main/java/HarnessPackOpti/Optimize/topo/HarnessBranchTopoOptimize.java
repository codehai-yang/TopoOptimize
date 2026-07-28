package HarnessPackOpti.Optimize.topo;

import static HarnessPackOpti.utils.GINEInferenceEngine.objectMapper;

import java.io.File;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
public class HarnessBranchTopoOptimize {
    // 初代样本最低生成数量（提高多样性，避免遗传起点过于集中）
    public static Integer LessRandomSamleNumber = 1000;
    // 迭代最少样本数量（提高每代候选池规模，保证进化方向充分探索）
    public static Integer HybridizationLessRandomSamleNumber = 10000;
    // top几的数量规定
    public static  Integer TopNumber = 1000;
    // 遗传最后一轮要精确计算的top
    public static Integer InteratorLastTop = 100;
    // 最终返回前端的参数
    public static  Integer resultNumber = 20;
    // 绕线优化:分支累计绕线成本贡献阈值,超过则 B 改 C
    public static  Double WindingCostThreshold = 10.0;
    // 每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();
    // 最优样本重复次数
    public static Integer BestRepetitionNumber = 0;
    // 成本重量长度的权重
    // 截面系数
    // 迭代重复的次数限值
    public static Integer IterationRestrictNumber = 6;
    // 仓库的 key 索引：完整状态列表拼接的字符串，用于 O(1) 去重（原子操作，无需额外的 List 存储）
    public static final Set<String> WAREHOUSE_KEYS = ConcurrentHashMap.newKeySet();
    // 每次迭代得到的top20
    public static List<Map<String, Object>> TopDetail = new ArrayList<>();
    // 自动补全得次数
    public static Integer AutoCompleteNumber = 2000;
    // 父本邻域抽样概率公式: p = norm^WeightFactor * MaxProbability + MinProbability
    // 0.7 衰减系数让中间分支概率不至于太极端,0.9 概率上限 + 0.05 下限保证每个分支都能被抽到
    public static Double WeightFactor = 0.7;
    public static Double MaxProbability = 0.9;
    public static Double MinProbability = 0.05;
    public static Integer Threads = 10;
    public static Integer QueueCapacity = 20;

    // 父本邻域单桶枚举/抽样上限：避免 C(pC, k2) 极大时炸内存
    // 桶内总组合数 ≤ 该值时枚举；> 该值时随机抽样该值次
    // 例:N=19, pB=5, pC=14, k=10, k1=2 → C(5,2)*C(14,8)=10*3003=30030 → 抽样 1000 次
    public static int ParentBucketEnumerateThreshold = 1000;

    // 成本权重
    public static Double costWeight = 0.98;
    // 重量权重
    public static Double weightWeight = 0.01;
    // 长度权重
    public static Double lengthWeight = 0.01;

    // 线程池
    public static ThreadPool threadPool = ThreadPool.shared(Threads, QueueCapacity);

    // 全局种子计数器，用于生成不碰撞的Random种子
    private static final AtomicLong seedCounter = new AtomicLong(System.nanoTime());

    // 定义一个仓库
    public static List<List<String>> WareHouseTop = new ArrayList<>();

    // 是否启用AI
    public static boolean whetherAI = false;

    // 当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    public HarnessBranchTopoOptimize() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance(); // 使用Store的单例实例
    }

    public static void main(String[] args) throws Exception {
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\BS4EM测试数据.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));// 将文件中内容转为字符串
        HarnessBranchTopoOptimize newHarnessBranchTopoOptimize = new HarnessBranchTopoOptimize();
        long startTime = System.currentTimeMillis();
        String topoOptimize = newHarnessBranchTopoOptimize.topoOptimize(jsonContent);
        System.out.println("算法总耗时：" + (System.currentTimeMillis() - startTime));
        File outputFile = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\测试新遗传算法2.txt");
        Files.write(outputFile.toPath(), topoOptimize.getBytes());
        System.out.println("JSON已成功输出到: " + outputFile.getAbsolutePath());
    }

    /**
     * 拓扑优化主入口。
     * 流程:解析 json → 分类分支 → 算初始成本 → 生成初代方案 → AI 预测 → 遗传迭代 → TOP100 → 绕线优化。
     * 控制台输出各阶段耗时;不写任何 Excel 统计。
     */
    public String topoOptimize(String jsonContent) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
        if(!ReadProjectInfo.whetherGet) {
            Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(jsonMap);
        }
        // 不启用AI则用老的方法
        if (!whetherAI) {
            OldHarnessBranchTopoOptimize harnessBranchTopoOptimize = new OldHarnessBranchTopoOptimize();
            String s = harnessBranchTopoOptimize.topoOptimize(jsonContent);
            return s;
        }
        // 每次优化前清理仓库，避免跨case累积
        WAREHOUSE_KEYS.clear();
        WareHouseTop.clear();
        TopDetail.clear();
        BestCost.clear();
        BestRepetitionNumber = 0;
        final long topoOptimizeStart = System.currentTimeMillis();

        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();

        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, String>> points = (List<Map<String, String>>) jsonMap.get("points");
        CaseId = caseInfo.get("id").toString();
        optimizeRecordId = "1";
//         optimizeRecordId = optimizeRecord.get("id").toString();
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
            // 条件:((B&&S) || (C&&B) || (B&&S&&C) || (B&&topoC&&statusC空) ||
            // (C&&topoB&&statusB空))
            // —— 用于遗传算法 canBreakToBSet,推导 bestBreakCount
            // 注意:不能放宽到 statusB=="B",否则会纳入"纯B分支"
            // (statusB="B" 但 statusC/statusS 为空,topologyStatusCode=B),
            // 导致 bestBreakCount 从 ~10 涨到 20+,父本邻域变异候选组合爆炸 → OOM
            // ★ 第 4 段:前端只设 statusB="B" 不设 statusC,但 topologyStatusCode=C,
            // 实际也能 C↔B 转换(当前 C 可改 B)
            // ★ 第 5 段:镜像——前端只设 statusC="C" 不设 statusB,但 topologyStatusCode=B,
            // 实际也能 B↔C 转换(当前 B 可改 C)
            String sT = edge.get("topologyStatusCode") != null ? edge.get("topologyStatusCode").toString() : "";
            if ((edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")) ||
                    (edge.get("statusC").toString().equals("C") && edge.get("statusB").toString().equals("B")) ||
                    (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")
                            && edge.get("statusC").toString().equals("C"))
                    ||
                    (edge.get("statusB").toString().equals("B") && edge.get("statusC").toString().isEmpty()
                            && sT.equals("C"))
                    ||
                    (edge.get("statusC").toString().equals("C") && edge.get("statusB").toString().isEmpty()
                            && sT.equals("B"))) {
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
        // 统计所有分支的打断代价,分支打断代价指经过这个分支的所有回路打断后的成本相加，这个打断代价会根据图的通断状态决定，因为回路走向不一样了
        for (String s : bundeleRelatedCircuitInfo.keySet()) {
            Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
            // 分支详细信息
            Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
            breakCostMap.put(s,
                    Double.parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
        }

        // 9) 把 conformList 转为 Set（作为 canBreakToBSet：可打断为 B 的分支集合）
        Set<String> canBreakToBSet = new HashSet<>(conformList);

        // 10) bestBreakCount:阶段一父本邻域变异 k 范围的上限
        // 之前:N/3(如 19→6),候选空间太小,容易收敛;k=7~18 的方向完全没采样
        // 改为:N-1(预留至少 1 个未打断分支),给 1..N-1 全范围
        // 防 OOM:每个 (k,k1) 桶超过 ParentBucketEnumerateThreshold 时改用随机抽样而非全枚举
        int bestBreakCount = Math.max(3, canBreakToBSet.size() - 1);
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
                edgeChooseBS, elecPosition, branchLength, connection, multiLoopInfos, pointMap, null, objectMapper);
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
                    mutexMap, chooseOneList, togetherBCList, mutexGroupList,
                    jsonMap, edgeChooseBS, elecPosition, branchLength,
                    connection, multiLoopInfos, pointMap, hybridizationNumber,
                    togetherBCIndex, chooseOneIndex, mutexConflictIndex,
                    canChangeSSet);
            if (findBest == null || findBest.size() == 0) {
                break;
            }
            TopDetail = findBest;
            long genDuration = System.currentTimeMillis() - startTime;
            System.out.println("第" + hybridizationNumber + "代迭代结束，耗时：" + genDuration);
            // 提示 GC 回收本代生成的大量临时对象（10000+ 方案的中转数据）
            System.gc();
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
        long hybridizationDuration = System.currentTimeMillis() - hybridizationTime;
        System.out.println("遗传算法结束，耗时：" + hybridizationDuration);
        FindBest findBestUtil = new FindBest();
        List<Map<String, Object>> topBeat = findBestUtil.findBest(findBest, "成本", InteratorLastTop);
        // 对遗传生成的方案进行闭环检测，打断代价低的分支改S
        List<List<String>> lists = new ArrayList<>();
        for (Map<String, Object> stringObjectMap : topBeat) {
            List<String> serviceableStatue = (List<String>) stringObjectMap.get("serviceableStatue");
            lists.add(serviceableStatue);
        }

        // 拿到的top最优方案，没有闭环
        long time = System.currentTimeMillis();
        List<Map<String, Object>> mapList = changeAndFindBest(lists, edges, normList, wearId, canChangeS,
                jsonMap, findBest, conformList, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
        long topBestDuration = System.currentTimeMillis() - time;
        System.out.println("找Top最优耗时：" + topBestDuration);
        // 回路绕线优化
        time = System.currentTimeMillis();
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
                jsonToMap, mutexMap, chooseOneList, togetherBCList, singleBSCList, eleclection, conformList);
        threadPool.shutdown();
        long windingDuration = System.currentTimeMillis() - time;
        System.out.println("绕线优化耗时：" + windingDuration);

        // ★追踪:写出方案状态变更追踪 Excel
        // 记录每个最终方案(绕线后)在三个阶段的状态:精确前 / 精确后 / 绕线后,
        // 并标注入口索引(500 中的哪一个、100 中的哪一个)。
//        try {
//            SchemeChangeTraceExcel.writeTrace(
//                    "src/main/resources/", CaseId, normList, maps);
//        } catch (Exception traceEx) {
//            System.out.println("[topoOptimize] 写出方案状态变更追踪 Excel 失败: " + traceEx.getMessage());
//            traceEx.printStackTrace();
//        }

        long totalDuration = System.currentTimeMillis() - topoOptimizeStart;
        System.out.println("topoOptimize 总耗时：" + totalDuration + " ms");
        return objectMapper.writeValueAsString(maps);
    }

    /**
     * 绕线优化(在遗传算法结束后最后跑一次)。
     * 思路:遍历方案 → 统计每分支绕线成本贡献 → 贡献 > WindingCostThreshold 的 B 改 C → 闭环消除 → 重算成本。
     * 对每个方案独立统计 + 多线程并行,最后取 TopNumber 返回。
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
            List<String> singleBSCList, Map<String, String> eleclection, List<String> conformList) throws Exception {

        FindBest findBest = new FindBest();
        if (mapList == null || mapList.isEmpty()) {
            return null;
        }

        // ② 对每个方案做 独立统计 + B→C + 闭环消除 + 成本重算 + 约束检查（多线程提速）
        // ★追踪:循环改为带索引,把 100 输入方案的索引传给 processSingleSchemeForWinding,
        // 用于在最终输出方案中记录 _windingInputIndex(精确后→绕线后的来源)。
        // 同步在 mapList 副本上预填 _windingInputIndex / _windingInputServiceableStatue,
        // 保证"全被淘汰时"mapList 透传也能在 Excel 中正常显示精确后状态。
        List<Map<String, Double>> costDeail = Collections.synchronizedList(new ArrayList<>());
        List<java.util.concurrent.Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (int windingInputIdx = 0; windingInputIdx < mapList.size(); windingInputIdx++) {
            final int windingInputIndex = windingInputIdx;
            final Map<String, Object> map = mapList.get(windingInputIdx);
            // 预填追踪字段(在原 map 上轻量添加,后续 processSingleSchemeForWinding 会再覆盖)
//            map.put("_windingInputIndex", windingInputIndex);
//            Object preStat = map.get("serviceableStatue");
//            if (preStat instanceof List) {
//                map.put("_windingInputServiceableStatue", new ArrayList<>((List<String>) preStat));
//            }
            tasks.add(() -> {
                return processSingleSchemeForWinding(
                        map, windingInputIndex, adjacencyMatrixGraphConnector, edges, normList, canChangeS, wearId,
                        jsonMap, mapper, projectCircuitInfoOutput, jsonToMap, mutexMap, chooseOneList, togetherBCList,
                        singleBSCList, eleclection, costDeail, conformList);
            });
        }

        List<java.util.concurrent.Future<Map<String, Object>>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<Map<String, Object>> task : tasks) {
            futures.add(threadPool.submit(task));
        }

        List<Map<String, Object>> optimized = new ArrayList<>();
        int scrapCount = 0;
        for (java.util.concurrent.Future<Map<String, Object>> future : futures) {
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
            return findBest.findBest(mapList, "成本", resultNumber);
        }
        return findBest.findBest(optimized, "成本", resultNumber);
    }

    /**
     * 阶段一(单方案版):对单个方案统计其所有回路的绕线成本贡献。
     * 对每个绕线回路,找全打通状态下的最短路径,与原回路差异分支均摊绕线成本作为该分支的贡献。
     * 与全局聚合版不同:本版本按本方案独立统计,B→C 完全基于本方案自己的贡献,精度更高。
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
     * 判断所在点的干湿。
     * 在点表中查找匹配名称的 waterParam。
     * 区分大小写查找,未找到返回 null。
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
     * 将路径数字转为对应的点名称。
     * 按索引在 allPoint 中查表,得到点名称列表。
     * 输入 numberPath 中的每个整数必须是 allPoint 的合法下标。
     */
    public List<String> convertPathToNumbers(List<Integer> numberPath, List<String> allPoint) {
        List<String> points = new ArrayList<>();
        for (Integer point : numberPath) {
            points.add(allPoint.get(point));
        }
        return points;
    }

    /**
     * 对单个方案执行 独立统计 + B→C + 闭环消除 + 成本重算 + 约束检查。
     * 流程严格遵循硬约束:闭环必须消完才返回,约束不过则丢弃。
     * 关键:branchCostContribution 按本方案独立计算,B→C 也只针对本方案。
     */
    private Map<String, Object> processSingleSchemeForWinding(
            Map<String, Object> map,
            int windingInputIndex,
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
            List<String> singleBSCList, Map<String, String> eleclection, List<Map<String, Double>> costDeail,
            List<String> conformList)
            throws Exception {
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> projectInfo = (Map<String, Object>) jsonMap.get("projectInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Boolean whetherOnLoop = caseInfo.get("loopcreate").toString().equals("true") ? true : false;
        // ★追踪:记录 100→100 的入口索引和绕线前状态(精确后状态),
        // 同时从输入 map 继承 _inputIndex / _inputServiceableStatue(500→100 入口)。
        final List<String> windingInputStatueSnapshot = (map.get("serviceableStatue") instanceof List)
                ? new ArrayList<>((List<String>) map.get("serviceableStatue"))
                : new ArrayList<String>();
        final Object inheritedInputIndex = map.get("_inputIndex");
        @SuppressWarnings("unchecked")
        final List<String> inheritedInputStatue = (map.get("_inputServiceableStatue") instanceof List)
                ? new ArrayList<>((List<String>) map.get("_inputServiceableStatue"))
                : null;
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

        // 2) B → C:本方案贡献超阈值的分支,且可还原为C（edge.statusC == "C"）
        Set<String> canRevertToCSet = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            Object statusCObj = edge.get("statusC");
            Object idObj = edge.get("id");
            if (statusCObj != null && "C".equals(statusCObj.toString()) && idObj != null) {
                canRevertToCSet.add(idObj.toString());
            }
        }
        if (highCostBranches.size() != 0) {
            for (int i = 0; i < statue.size(); i++) {
                if ("B".equals(statue.get(i)) && highCostBranches.contains(normList.get(i))
                        && canRevertToCSet.contains(normList.get(i))) {
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
            List<String> recognizeLoopIdList = collectWearIdLoopBranches(recognizeLoopList, wearId);

            if (!recognizeLoopIdList.isEmpty()) {
                // 含 wearId 的闭环:按打断代价升序选,优先用低代价分支打S
                List<String> sortedCandidates = new ArrayList<>(new HashSet<>(recognizeLoopIdList));
                sortedCandidates.sort((a, b) -> {
                    double costA = breakCostMap.getOrDefault(a, Double.MAX_VALUE);
                    double costB = breakCostMap.getOrDefault(b, Double.MAX_VALUE);
                    return Double.compare(costA, costB);
                });
                String minCostKey = null;
                for (String s : sortedCandidates) {
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
                            String minCostKey = selectBestBreakByEfficiency(
                                    recognizeLoopListSon, breakCostMap, canChangeS);
                            if (minCostKey == null) {
                                scrapOrNot = true;
                                break;
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
                // 仍有未消除的闭环，尝试最后一轮强制打断（成本最低优先，每次打断后 refresh）
                System.out.println("原始方案开始消除闭环");
                boolean broken = forceBreakLoops(origStatueCopy, edges, normList, wearId, canChangeS, whetherOnLoop,
                        appPositions, eleclection, mutexMap, chooseOneList, togetherBCList, conformList,
                        breakCostMap, costResultData, threadLocalJsonMap,
                        projectCircuitInfoOutput, mapper, jsonToMap);
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
            map2.put("成本", tempCost);
            map2.put("topoId", topoInfoMap.get("id").toString());
            map2.put("caseId", projectInfo.get("caseId").toString());
            map2.put("topoOptimizeResult", topoOptimizeResult);
            map2.put("finishStatue", "normal");
            map2.put("initializationScheme", false);
            map2.put("serviceableStatue", origStatueCopy);
            map2.put("serviceableEdges", origEdges);
            // ★追踪:写入绕线→绕线后的入口索引和状态;继承 500→100 入口字段。
            map2.put("_windingInputIndex", windingInputIndex);
            map2.put("_windingInputServiceableStatue", windingInputStatueSnapshot);
            if (inheritedInputIndex != null) {
                map2.put("_inputIndex", inheritedInputIndex);
            }
            if (inheritedInputStatue != null) {
                map2.put("_inputServiceableStatue", inheritedInputStatue);
            }
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
            // 仍有未消除的闭环,尝试最后一轮强制打断（成本最低优先,每次打断后 refresh）
            System.out.println("新方案还有闭环，开始消除闭环");
            boolean broken = forceBreakLoops(statue, edges, normList, wearId, canChangeS, whetherOnLoop,
                    appPositions, eleclection, mutexMap, chooseOneList, togetherBCList, conformList,
                    breakCostMap, costResultData, threadLocalJsonMap,
                    projectCircuitInfoOutput, mapper, jsonToMap);
            if (broken) {
                // 重新计算最终边
                finalEdgeresult = createNewEdges(statue, edges, normList);
            } else {
                return null;
            }
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

        Map<String, Double> dedupCost = new HashMap<>();
        dedupCost.put("总成本", Double.parseDouble(costResultData.get("总成本").toString()));
        dedupCost.put("总重量", Double.parseDouble(costResultData.get("总重量").toString()));
        dedupCost.put("总长度", Double.parseDouble(costResultData.get("总长度").toString()));
        if (costDeail.contains(dedupCost)) {
            System.out.println("成本重复");
            return null;
        }
        costDeail.add(dedupCost);
        map2.put("成本", dedupCost);
        map2.put("topoId", topoInfoMap.get("id").toString());
        map2.put("caseId", projectInfo.get("caseId").toString());
        map2.put("topoOptimizeResult", topoOptimizeResult2);
        map2.put("finishStatue", "normal");
        map2.put("initializationScheme", false);
        map2.put("serviceableStatue", statue);
        map2.put("serviceableEdges", finalEdgeresult);
        // ★追踪:写入绕线→绕线后的入口索引和状态;继承 500→100 入口字段。
        map2.put("_windingInputIndex", windingInputIndex);
        map2.put("_windingInputServiceableStatue", windingInputStatueSnapshot);
        if (inheritedInputIndex != null) {
            map2.put("_inputIndex", inheritedInputIndex);
        }
        if (inheritedInputStatue != null) {
            map2.put("_inputServiceableStatue", inheritedInputStatue);
        }
        return map2;
    }

    /**
     * 强制打断未消除的闭环。
     * 对未消除的闭环（含 wearId 或 whetherOnLoop 开启），迭代打断，直到闭环全部消除或无法继续。
     * true 表示至少打断了一个分支。
     */
    private boolean forceBreakLoops(
            List<String> statueList,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> wearId,
            List<String> canChangeToS,
            boolean whetherOnLoop,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            List<String> conformList,
            Map<String, Double> breakCostMap,
            Map<String, Object> costResultData,
            Map<String, Object> threadLocalJsonMap,
            ProjectCircuitInfoOutput projectCircuitInfoOutput,
            ObjectMapper mapper,
            JsonToMap jsonToMap) {
        boolean anyBroken = false;
        int maxLoopIter = 300;

        // ===== 阶段一：打 S（成本最低优先，每次打断后重新计算 breakCostMap）=====
        while (maxLoopIter-- > 0) {
            List<Map<String, Object>> currentEdges = createNewEdges(statueList, edges, normList);
            List<List<String>> lists = recognizeLoopNew(currentEdges);
            if (lists.isEmpty()) {
                break;
            }
            // 找出需处理的闭环：含 wearId 的,或 whetherOnLoop=true 时的所有闭环
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
            // 收集目标闭环中可打断的分支(canChangeS 中)
            Set<String> breakableIds = new LinkedHashSet<>();
            for (List<String> loop : targetLoops) {
                for (String id : loop) {
                    if (canChangeToS.contains(id) && !id.isEmpty()) {
                        breakableIds.add(id);
                    }
                }
            }
            if (breakableIds.isEmpty()) {
                System.out.println("forceBreakLoops[S]: 无可打断分支,停止");
                break;
            }
            // 统计每个候选在 targetLoops 中的出现次数
            Map<String, Integer> loopCountMap = new HashMap<>();
            for (String id : breakableIds) {
                int c = 0;
                for (List<String> loop : targetLoops) {
                    if (loop.contains(id)) {
                        c++;
                    }
                }
                loopCountMap.put(id, c);
            }
            // 排序：覆盖闭环数倒序 > 代价升序 tie-breaker
            final Map<String, Integer> finalLoopCountMap = loopCountMap;
            List<String> sortedBreakables = new ArrayList<>(breakableIds);
            sortedBreakables.sort((a, b) -> {
                int cntCmp = Integer.compare(finalLoopCountMap.getOrDefault(b, 0),
                        finalLoopCountMap.getOrDefault(a, 0));
                if (cntCmp != 0) {
                    return cntCmp;
                }
                double costA = breakCostMap.getOrDefault(a, 0.0);
                double costB = breakCostMap.getOrDefault(b, 0.0);
                if (costA < 0.001) {
                    costA = 0.001;
                }
                if (costB < 0.001) {
                    costB = 0.001;
                }
                return Double.compare(costA, costB);
            });
            // 尝试本轮所有可打断分支,直到找到一个合法打断位置
            boolean brokenThisRound = false;
            for (String pickId : sortedBreakables) {
                int pickIdx = normList.indexOf(pickId);
                String originalStatus = statueList.get(pickIdx);
                statueList.set(pickIdx, "S");
                List<Map<String, Object>> afterEdges = createNewEdges(statueList, edges, normList);
                Boolean ok = checkFirstOption(normList, statueList, afterEdges, appPositions, eleclection, mutexMap,
                        chooseOneList, togetherBCList, null);
                if (ok) {
                    // 打断后立即刷新 breakCostMap（方案已变，代价分布已变）
                    if (!refreshCircuitInfo(statueList, edges, normList, threadLocalJsonMap,
                            projectCircuitInfoOutput, mapper, jsonToMap, costResultData, breakCostMap)) {
                        statueList.set(pickIdx, originalStatus);
                        System.out.println("forceBreakLoops[S]: refreshCircuitInfo 失败,停止");
                        return anyBroken;
                    }
                    anyBroken = true;
                    brokenThisRound = true;
                    break;
                } else {
                    statueList.set(pickIdx, originalStatus);
                }
            }
            if (!brokenThisRound) {
                System.out.println("forceBreakLoops[S]: 本轮所有候选尝试后仍无法合法打断,停止");
                break;
            }
        }

        // ===== 阶段二：打 B（S 阶段打完后若还有闭环,继续打 B 兜底）=====
        Set<String> allBStatusSet = new HashSet<>(conformList);
        int conformMaxIter = 300;
        while (conformMaxIter-- > 0) {
            List<Map<String, Object>> currentEdgesB = createNewEdges(statueList, edges, normList);
            List<List<String>> remainLoops = recognizeLoopNew(currentEdgesB);
            if (remainLoops.isEmpty()) {
                break;
            }
            List<List<String>> targetLoops = new ArrayList<>();
            for (List<String> loop : remainLoops) {
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
                targetLoops = remainLoops;
            }
            if (targetLoops.isEmpty()) {
                break;
            }
            // 收集 targetLoops 中、属于 allBStatusSet、且当前不是 B 的分支
            Set<String> conformCandidates = new LinkedHashSet<>();
            for (List<String> loop : targetLoops) {
                for (String id : loop) {
                    if (!allBStatusSet.contains(id)) {
                        continue;
                    }
                    int idx = normList.indexOf(id);
                    if (idx < 0) {
                        continue;
                    }
                    if (!"B".equals(statueList.get(idx))) {
                        conformCandidates.add(id);
                    }
                }
            }
            if (conformCandidates.isEmpty()) {
                System.out.println("forceBreakLoops[B]: allBStatusSet中无可用打B分支,停止");
                break;
            }
            // 排序：覆盖闭环数倒序 > 代价升序 tie-breaker（与 S 阶段一致）
            Map<String, Integer> conformLoopCountMap = new HashMap<>();
            for (String id : conformCandidates) {
                int c = 0;
                for (List<String> loop : targetLoops) {
                    if (loop.contains(id)) {
                        c++;
                    }
                }
                conformLoopCountMap.put(id, c);
            }
            final Map<String, Integer> finalConformCountMap = conformLoopCountMap;
            List<String> sortedConformCandidates = new ArrayList<>(conformCandidates);
            sortedConformCandidates.sort((a, b) -> {
                int cntCmp = Integer.compare(finalConformCountMap.getOrDefault(b, 0),
                        finalConformCountMap.getOrDefault(a, 0));
                if (cntCmp != 0) {
                    return cntCmp;
                }
                double costA = breakCostMap.getOrDefault(a, 0.0);
                double costB = breakCostMap.getOrDefault(b, 0.0);
                if (costA < 0.001) {
                    costA = 0.001;
                }
                if (costB < 0.001) {
                    costB = 0.001;
                }
                return Double.compare(costA, costB);
            });
            // 选最前(最高效)的一个,设置成 B（打 B 不影响 breakCostMap,但仍调一次 refresh 同步成本）
            String pickId = sortedConformCandidates.get(0);
            int pickIdx = normList.indexOf(pickId);
            String originalStatus = statueList.get(pickIdx);
            statueList.set(pickIdx, "B");
            List<Map<String, Object>> trialEdges = createNewEdges(statueList, edges, normList);
            List<List<String>> trialLoops = recognizeLoopNew(trialEdges);
            if (trialLoops.size() < remainLoops.size()) {
                if (!refreshCircuitInfo(statueList, edges, normList, threadLocalJsonMap,
                        projectCircuitInfoOutput, mapper, jsonToMap, costResultData, breakCostMap)) {
                    statueList.set(pickIdx, originalStatus);
                    System.out.println("forceBreakLoops[B]: refreshCircuitInfo 失败,停止");
                    return anyBroken;
                }
                anyBroken = true;
            } else {
                // 没消,回滚
                statueList.set(pickIdx, originalStatus);
                // 出现"打了 B 闭环数不减"的情况说明 allBStatusSet 不准,兜底退出避免死循环
                System.out.println("forceBreakLoops[B]: 候选分支打 B 后闭环未减少,停止");
                break;
            }
        }

        return anyBroken;
    }

    /**
     * 安全 double 解析。
     * null 或非数字字符串都返回 0.0,避免调用方做空值判断。
     * 内部用 try-catch 包裹,异常也返 0.0。
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
     * 安全取字符串。
     * null 返 null,空字符串也返 null,避免上游拿到 "" 触发空指针。
     * 非空则返回 toString() 结果。
     */
    private String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }

    /**
     * 根据给定的分支打断状况集合,做闭环检查和 S 修复,返回 topN 最优方案。
     * 闭环含 wearId 强制消除,whetherOnLoop=true 时所有闭环都消。
     * 多线程并行 + 仓库去重 + 上一代 top3 注入。
     */
    public List<Map<String, Object>> changeAndFindBest(List<List<String>> simpleList,
            List<Map<String, Object>> edges,
            List<String> normList,
            List<String> wearId,
            List<String> canChangeS,
            Map<String, Object> jsonMap,
            List<Map<String, Object>> findBestPre,
            List<String> conformList,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList)
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
        // ★追踪:每个 task 注入 _inputIndex / _inputServiceableStatue,
        // 供后续 Excel 追踪"500 入口→100 出口"的映射。
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (int inputIdx = 0; inputIdx < simpleList.size(); inputIdx++) {
            final int inputIndex = inputIdx;
            final List<String> strings = simpleList.get(inputIndex);
            tasks.add(() -> {
                long startTime = System.currentTimeMillis();
                Map<String, Object> map = new HashMap<>();
                // ★追踪:写入 500 输入索引和原始状态(精确计算前)
                // map.put("_inputIndex", inputIndex);
                // map.put("_inputServiceableStatue", inputSnapshot);
                List<String> serviceableStatue = strings.stream().collect(Collectors.toList());
                List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                // 深拷贝
                Map<String, Object> threadLocalJsonMap = new HashMap<>(jsonMap);
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
                // 打断代价为0的分支尝试打B,过约束才保留,否则还原
                for (Map.Entry<String, Double> entry : breakCostMap.entrySet()) {
                    if (entry.getValue() != 0.0) {
                        continue;
                    }
                    int idx = normList.indexOf(entry.getKey());
                    if (idx < 0) {
                        continue;
                    }
                    String originalStatus = serviceableStatue.get(idx);
                    serviceableStatue.set(idx, "B");
                    List<Map<String, Object>> testEdges = createNewEdges(serviceableStatue, edges, normList);
                    Boolean ok = checkFirstOption(normList, serviceableStatue, testEdges, appPositions, eleclection,
                            mutexMap, chooseOneList, togetherBCList, null);
                    if (!ok) {
                        serviceableStatue.set(idx, originalStatus);
                    }
                }
                // 阶段三:对打断代价前30%高代价的非C分支尝试还原为C,过约束才保留
                // 可还原为C的分支集合:edge.statusC == "C" 的分支
                Set<String> canRevertToCSet = new HashSet<>();
                for (Map<String, Object> edge : edges) {
                    Object statusCObj = edge.get("statusC");
                    Object idObj = edge.get("id");
                    if (statusCObj != null && "C".equals(statusCObj.toString()) && idObj != null) {
                        canRevertToCSet.add(idObj.toString());
                    }
                }
                List<Map.Entry<String, Double>> sortedByCostDesc = new ArrayList<>(breakCostMap.entrySet());
                sortedByCostDesc.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                int revertCount = Math.max(1, sortedByCostDesc.size() * 30 / 100);
                for (int i = 0; i < revertCount && i < sortedByCostDesc.size(); i++) {
                    Map.Entry<String, Double> entry = sortedByCostDesc.get(i);
                    String bid = entry.getKey();
                    // 不可还原为C的分支直接跳过
                    if (!canRevertToCSet.contains(bid)) {
                        continue;
                    }
                    int idx = normList.indexOf(bid);
                    if (idx < 0) {
                        continue;
                    }
                    String currentStatus = serviceableStatue.get(idx);
                    if ("C".equals(currentStatus)) {
                        continue;
                    }
                    // 试探还原为 C（产生的闭环由后续 while 循环统一打S处理）
                    serviceableStatue.set(idx, "C");
                    List<Map<String, Object>> testEdges = createNewEdges(serviceableStatue, edges, normList);
                    // 约束检查
                    Boolean ok = checkFirstOption(normList, serviceableStatue, testEdges, appPositions, eleclection,
                            mutexMap, chooseOneList, togetherBCList, null);
                    if (!ok) {
                        serviceableStatue.set(idx, currentStatus);
                    }
                }

                int maxLoopIterations = 100; // 防止死循环，最多打断100次
                while (scrapOrNot == false && maxLoopIterations-- > 0) {
                    serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                    List<List<String>> recognizeLoopList = recognizeLoopNew(serviceableEdge);
                    // 收集穿腔分支相关的闭环
                    List<String> recognizeLoopIdList = collectWearIdLoopBranches(recognizeLoopList, wearId);

                    // 检查当前方案中是否存在需要处理的闭环
                    if (!recognizeLoopIdList.isEmpty()) {
                        // wearId 闭环:按打断代价升序选,优先用低代价分支打S
                        List<String> sortedCandidates = new ArrayList<>(new HashSet<>(recognizeLoopIdList));
                        sortedCandidates.sort((a, b) -> {
                            double costA = breakCostMap.getOrDefault(a, Double.MAX_VALUE);
                            double costB = breakCostMap.getOrDefault(b, Double.MAX_VALUE);
                            return Double.compare(costA, costB);
                        });
                        String minCostKey = null;
                        for (String s : sortedCandidates) {
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
                                    String minCostKey = selectBestBreakByEfficiency(
                                            recognizeLoopListSon, breakCostMap, canChangeS);
                                    if (minCostKey == null) {
                                        // 无可选分支，放弃
                                        scrapOrNot = true;
                                        break;
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
                Set<String> allBStatusSet = new HashSet<>(conformList);
                int conformMaxIter = 100;
                while (conformMaxIter-- > 0) {
                    serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                    List<List<String>> remainLoops = recognizeLoopNew(serviceableEdge);
                    if (remainLoops.isEmpty()) {
                        break;
                    }
                    System.out.println("找top加S后还有闭环");
                    // 找出需处理的闭环：含 wearId 的，或 whetherOnLoop=true 时的所有闭环
                    // 与上面打S循环保持一致:非 wearId 闭环 + whetherOnLoop=false 时不打断,直接退出
                    List<List<String>> targetLoops = new ArrayList<>();
                    for (List<String> loop : remainLoops) {
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
                        targetLoops = remainLoops;
                    }
                    if (targetLoops.isEmpty()) {
                        // 没有需要强制打断的闭环(无 wearId 且 whetherOnLoop=false),停止
                        System.out.println(
                                "changeAndFindBest[conform]: 无 wearId 闭环且 whetherOnLoop=false,无需强制打B,停止");
                        break;
                    }
                    // 收集 targetLoops 中、属于 allBStatusSet、且当前不是 B 的分支
                    Set<String> conformCandidates = new LinkedHashSet<>();
                    for (List<String> loop : targetLoops) {
                        for (String id : loop) {
                            if (!allBStatusSet.contains(id)) {
                                continue;
                            }
                            int idx = normList.indexOf(id);
                            if (idx < 0) {
                                continue;
                            }
                            if (!"B".equals(serviceableStatue.get(idx))) {
                                conformCandidates.add(id);
                            }
                        }
                    }
                    if (conformCandidates.isEmpty()) {
                        System.out.println(
                                "changeAndFindBest[conform]: allBStatusSet中无可用打B分支,停止");
                        break;
                    }
                    // 统计每个候选在 targetLoops 中的出现次数
                    Map<String, Integer> conformLoopCountMap = new HashMap<>();
                    for (String id : conformCandidates) {
                        int c = 0;
                        for (List<String> loop : targetLoops) {
                            if (loop.contains(id)) {
                                c++;
                            }
                        }
                        conformLoopCountMap.put(id, c);
                    }
                    // 排序:覆盖闭环数倒序 > 代价升序 tie-breaker
                    final Map<String, Integer> finalConformCountMap = conformLoopCountMap;
                    List<String> sortedConformCandidates = new ArrayList<>(conformCandidates);
                    sortedConformCandidates.sort((a, b) -> {
                        int cntCmp = Integer.compare(
                                finalConformCountMap.getOrDefault(b, 0),
                                finalConformCountMap.getOrDefault(a, 0));
                        if (cntCmp != 0) {
                            return cntCmp;
                        }
                        double costA = breakCostMap.getOrDefault(a, 0.0);
                        double costB = breakCostMap.getOrDefault(b, 0.0);
                        if (costA < 0.001) {
                            costA = 0.001;
                        }
                        if (costB < 0.001) {
                            costB = 0.001;
                        }
                        return Double.compare(costA, costB);
                    });
                    // 选最前(最高效)的一个,设置成 B
                    String pickId = sortedConformCandidates.get(0);
                    int pickIdx = normList.indexOf(pickId);
                    String originalStatus = serviceableStatue.get(pickIdx);
                    serviceableStatue.set(pickIdx, "B");
                    // 打断后重新计算全图成本和 breakCostMap
                    if (!refreshCircuitInfo(serviceableStatue, edges, normList, threadLocalJsonMap,
                            projectCircuitInfoOutput, mapper, jsonToMap, costResultData, breakCostMap)) {
                        // 刷新失败,回滚原状态,退出本轮
                        serviceableStatue.set(pickIdx, originalStatus);
                        System.out.println(
                                "changeAndFindBest[conform]: refreshCircuitInfo失败,停止强制打B");
                        break;
                    }
                    serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                    // 同步到 map
                    map.put("成本", costResultData);
                    map.put("serviceableEdges", serviceableEdge);
                    map.put("serviceableStatue", serviceableStatue);
                }

                // ★兜底:conform 阶段退出后可能仍有未消除的闭环（while 循环因候选耗尽/迭代上限而退出）,
                // 进入绕线优化前必须确保方案无闭环,这里再调一次 forceBreakLoops 兜底。
                List<Map<String, Object>> tailCheckEdges = createNewEdges(serviceableStatue, edges, normList);
                List<List<String>> tailCheckLoops = recognizeLoopNew(tailCheckEdges);
                if (!tailCheckLoops.isEmpty()) {
                    System.out.println("changeAndFindBest: conform 后仍存在 " + tailCheckLoops.size()
                            + " 个闭环,兜底 forceBreakLoops");
                    boolean tailBroken = forceBreakLoops(serviceableStatue, edges, normList, wearId, canChangeS,
                            whetherOnLoop, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList,
                            conformList, breakCostMap, costResultData, threadLocalJsonMap,
                            projectCircuitInfoOutput, mapper, jsonToMap);
                    if (tailBroken) {
                        serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                        map.put("serviceableEdges", serviceableEdge);
                        map.put("serviceableStatue", serviceableStatue);
                        map.put("成本", costResultData);
                    }
                    // 兜底后最终校验:含 wearId 的闭环必须消除,否则丢弃
                    List<List<String>> finalTailLoops = recognizeLoopNew(serviceableEdge);
                    for (List<String> loop : finalTailLoops) {
                        boolean containsWearId = false;
                        for (String s1 : wearId) {
                            if (loop.contains(s1)) {
                                containsWearId = true;
                                break;
                            }
                        }
                        if (containsWearId) {
                            System.out.println("changeAndFindBest: 兜底 forceBreakLoops 后仍存在 wearId 闭环,丢弃");
                            return null;
                        }
                    }
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
                Map<String, Object> result = future.get(6000, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    resultList.add(result);
                }
            } catch (Exception e) {
                // e.printStackTrace();
            }

        }
        // 加入上一代最优top3
        if (findBestPre != null) {
            for (int i = 0; i < 3; i++) {
                Map<String, Object> preMap = findBestPre.get(i);
                    resultList.add(preMap);
            }
        }
        List<Map<String, Object>> topBeat = findBest.findBest(resultList, "成本", resultNumber);

        for (Map<String, Object> map : topBeat) {
            List<String> list = (List<String>) map.get("serviceableStatue");
            if (!containsList(list, WareHouseTop)) {
                WareHouseTop.add(list);
            }
        }
        return topBeat;
    }

    /**
     * 识别图中的所有闭环(基于 C 状态分支构建邻接表 + DFS 回溯)。
     * S 状态分支与 B 状态分支均视为断开,不参与邻接表构建。
     * 返回每个闭环包含的边 id 列表;无环时返回空列表。
     */
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
     * 收集所有包含至少一个穿腔(wearId)分支的闭环中的所有分支ID。
     * 用于闭环消除的第一步:优先处理含穿腔分支的闭环。
     */
    private List<String> collectWearIdLoopBranches(List<List<String>> recognizeLoopList, List<String> wearId) {
        List<String> result = new ArrayList<>();
        for (List<String> loop : recognizeLoopList) {
            for (String s : loop) {
                if (wearId.contains(s)) {
                    result.addAll(loop);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 闭环消除效率评分:统计每个分支覆盖的闭环数,按"覆盖闭环数/打断代价"效率排序,
     * 优先选 canChangeS 中的分支,无可选时 fallback 到排序第一位。找不到任何候选返回 null。
     * 用于 whetherOnLoop 模式下的全局闭环消除。
     */
    private String selectBestBreakByEfficiency(List<List<String>> recognizeLoopList,
            Map<String, Double> breakCostMap, List<String> canChangeS) {
        Map<String, Integer> loopCountMap = new HashMap<>();
        for (List<String> loop : recognizeLoopList) {
            for (String bid : loop) {
                loopCountMap.merge(bid, 1, Integer::sum);
            }
        }
        List<String> sortedCandidates = new ArrayList<>(loopCountMap.keySet());
        sortedCandidates.sort((a, b) -> {
            double costA = breakCostMap.getOrDefault(a, 0.001);
            double costB = breakCostMap.getOrDefault(b, 0.001);
            if (costA < 0.001)
                costA = 0.001;
            if (costB < 0.001)
                costB = 0.001;
            double effA = loopCountMap.getOrDefault(a, 1) / costA;
            double effB = loopCountMap.getOrDefault(b, 1) / costB;
            return Double.compare(effB, effA); // 效率高的优先
        });
        for (String s : sortedCandidates) {
            if (canChangeS.contains(s)) {
                return s;
            }
        }
        return sortedCandidates.isEmpty() ? null : sortedCandidates.get(0);
    }

    /**
     * 打断一个分支后,重新计算全图成本和各分支的打断代价。
     * 必须重新调用,不能简单累加原 breakCostMap 增量:打断后回路走线、导线选型、连接器配置都变化。
     * costResultData 和 breakCostMap 都会被覆盖;true=计算成功,false=计算异常。
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
     * 遗传算法主体(单代)。
     * 阶段一:对 TopDetail 每个父本调 generateInitialSchemes 做变异;邻域枯竭时反权重补充。
     * 阶段二:以 TopDetail 为父本两两配对交叉变异,做约束校验后入仓。
     * 合并后注入上一代 top 30% 精英保留,再 AI 预测取 TopNumber。
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
            List<List<String>> mutexGroupList,
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
        // 0) 兜底:没有上一代父本,直接退出
        if (TopDetail == null || TopDetail.isEmpty()) {
            return null;
        }

        // 1) 提取父本状态列表(从上一代 TopDetail 中取出 serviceableStatue)
        // ★ S → C 还原（与旧遗传算法 HarnessBranchTopoOptimize L1690-1703 保持一致）：
        // 每代迭代开始前，对父本状态做 S → C 还原（仅 canChangeSSet 中的分支）。
        // 原因：S 是闭环消除的临时态（windingOptimize 阶段加的），遗传变异应基于
        // C 状态做 B/C 翻转，否则 S 状态会污染父本邻域的 baseStatusMap。
        List<List<String>> parentStatues = new ArrayList<>();
        for (Map<String, Object> detail : TopDetail) {
            List<String> statue = (List<String>) detail.get("serviceableStatue");
            if (statue != null && initialScheme != null && statue.size() == initialScheme.size()) {
                List<String> statueClean = new ArrayList<>(statue);
                for (int i = 0; i < statueClean.size(); i++) {
                    if ("S".equals(statueClean.get(i)) && canChangeSSet.contains(normList.get(i))) {
                        statueClean.set(i, "C");
                    }
                }
                parentStatues.add(statueClean);
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
        // 阶段一对 TopDetail 每个父本调一次 generateInitialSchemes 做变异
        // topUpRounds 累计补充调用次数,达到 AutoCompleteNumber 上限则停止
        int topUpRounds = 0;
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
        while (phase1.size() < HybridizationLessRandomSamleNumber) {
            // 继续调用初代生成的方案直到满足方案数量
            // 限制:补充次数 ≤ AutoCompleteNumber 防止无限循环
            if (topUpRounds >= AutoCompleteNumber) {
                System.out.println("[hybridization] 达到补充次数上限 " + AutoCompleteNumber + ",停止补充");
                break;
            }
            topUpRounds++;
            // 用 initialScheme 作基础状态调 generateInitialSchemes,补充初代方案
            List<List<String>> moreVariants = generateInitialSchemes(
                    edges, canBreakToBSet, initialScheme, appPositions, eleclection,
                    bestBreakCount, breakCostMap, normList,
                    mutexMap, chooseOneList, togetherBCList,
                    togetherBCIndex, chooseOneIndex, mutexConflictIndex,
                    canChangeSSet);
            int beforeSize = phase1.size();
            phase1.addAll(moreVariants);
            int added = phase1.size() - beforeSize;
            System.out.println("[hybridization] 第 " + topUpRounds + " 次初代补充:本次新增 " + added
                    + " 个,累计 " + phase1.size());
            // 本次未新增任何方案,说明仓库已饱和,退出防止死循环
            if (added == 0) {
                System.out.println("[hybridization] 仓库已饱和,停止补充");
                break;
            }
        }
        System.out.println("[hybridization] 阶段一累计 " + phase1.size() + " 个有效方案,耗时 "
                + (System.currentTimeMillis() - phase1Time) + " ms");
        // 对上面生成的样本进行ai预测成本，拿top
        if (phase1.isEmpty()) {
            System.out.println("[hybridization] 阶段一无有效方案,跳过 AI 预测");
            return null;
        }
        long phase1PredictTime = System.currentTimeMillis();
        // findBestPre 传 null 避免注入 10% 精英(由本方法统一控制)
        List<Map<String, Object>> phase1Top = predictAndFindBest(
                phase1, edges, normList, jsonMap,
                edgeChooseBS, elecPosition, branchLength, connection,
                multiLoopInfos, pointMap, null, objectMapper);
        System.out.println("[hybridization] 阶段一 AI 预测+取 top 耗时 "
                + (System.currentTimeMillis() - phase1PredictTime) + " ms,top 数 "
                + (phase1Top == null ? 0 : phase1Top.size()));

        // 3) 阶段二:交叉变异(以阶段一 AI 预测的 top 为父本,两两配对)
        // ★ 不是上一代 TopDetail,而是刚刚阶段一 AI 预测排序拿到的 top
        // 同样做 S → C 还原(避免 S 状态污染父本邻域的 baseStatusMap)
        List<List<String>> phase2Parents = new ArrayList<>();
        for (Map<String, Object> detail : phase1Top) {
            List<String> statue = (List<String>) detail.get("serviceableStatue");
            if (statue != null && initialScheme != null && statue.size() == initialScheme.size()) {
                List<String> statueClean = new ArrayList<>(statue);
                for (int i = 0; i < statueClean.size(); i++) {
                    if ("S".equals(statueClean.get(i)) && canChangeSSet.contains(normList.get(i))) {
                        statueClean.set(i, "C");
                    }
                }
                phase2Parents.add(statueClean);
            }
        }
        if (phase2Parents.isEmpty()) {
            System.out.println("[hybridization] 阶段一 AI top 无有效父本,跳过交叉变异");
            return null;
        }
        long phase2Time = System.currentTimeMillis();
        int crossTarget = Math.max(perGenTarget, phase2Parents.size() * 2);
        List<Double> parentCosts = new ArrayList<>();
        for (Map<String, Object> detail : phase1Top) {
            Map<String, Object> costMap = (Map<String, Object>) detail.get("成本");
            if (costMap != null && costMap.get("总成本") != null) {
                parentCosts.add(Double.parseDouble(costMap.get("总成本").toString()));
            } else {
                parentCosts.add(Double.MAX_VALUE);
            }
        }
        List<List<String>> phase2Raw = crossoverMutation(
                phase2Parents, initialScheme, normList, crossTarget, parentCosts);
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
                    mutexMap, chooseOneList, togetherBCList, mutexGroupList)) {
                phase2Valid.add(adjusted);
            }
        }
        System.out.println("[hybridization] 阶段二通过约束 " + phase2Valid.size() + " 个,耗时 "
                + (System.currentTimeMillis() - phase2CheckTime) + " ms");
        // 交叉变异原始产物已用完（仅 valid 子集保留），释放引用
        phase2Raw = null;

        // 5) 合并两阶段方案(阶段一已入仓,阶段二已入仓,这里只做候选池聚合)
        List<List<String>> allSchemes = new ArrayList<>();
        allSchemes.addAll(phase1);
        allSchemes.addAll(phase2Valid);
        List<List<String>> topThirty = new ArrayList<>();
        // 6) 注入上一代 top 30%(精英保留,确保新一代最优 ≤ 上一代最优)
        int eliteCount = Math.max(1, (int) Math.ceil(TopDetail.size() * 0.3));
        for (int i = 0; i < eliteCount && i < TopDetail.size(); i++) {
            List<String> eliteStatue = (List<String>) TopDetail.get(i).get("serviceableStatue");
            if (eliteStatue != null && eliteStatue.size() == initialScheme.size()) {
                allSchemes.add(eliteStatue);
                topThirty.add(eliteStatue);
            }
        }

        // 阶段一方案列表已用完（已通过 predictAndFindBest 预测完毕），释放引用
        // phase1 约 10000 条 List<String>，每条约 N 个分支状态，占用大量内存
        phase1 = null;

        if (allSchemes.isEmpty()) {
            return null;
        }
        // 7) AI 预测 + 排序取 TopNumber
        // findBestPre 传 null 避免 predictAndFindBest 内部再注入 10%(精英由本方法统一控制)
        long predTime = System.currentTimeMillis();
        phase2Valid.addAll(topThirty);
        List<Map<String, Object>> mapList = predictAndFindBest(phase2Valid, edges, normList, jsonMap,
                edgeChooseBS, elecPosition, branchLength, connection,
                multiLoopInfos, pointMap, null, objectMapper);
        // 阶段二方案列表已用完（已通过 predictAndFindBest 预测完毕），释放引用
        int allSchemesSize = allSchemes.size();
        allSchemes = null;
        phase2Valid = null;
        long findBestTimeMs = System.currentTimeMillis() - predTime;
        // 对阶段一何阶段二生成的样本再次进行找top
        List<Map<String, Object>> finaleResult = new ArrayList<>();
        finaleResult.addAll(mapList);
        finaleResult.addAll(phase1Top);
        FindBest findBest = new FindBest();
        List<Map<String, Object>> topBeat = findBest.findBest(finaleResult, "成本", TopNumber);
        System.out.println("预测" + allSchemesSize + "个样本成本耗时：" + findBestTimeMs);
        return topBeat;
    }

    /**
     * 交叉变异。两两配对父本(成本低的优先),各取 1-2 个 mutation 位置叠加到子代。
     * 生成 2-3 mutation 子代方案集合,保留 S 状态不变。
     * 父本均来自上一代 TopDetail,突变位置=相对 baseScheme 被改 B 的位置。
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
     * 从列表中随机选择 n 个不重复元素（Fisher-Yates 部分洗牌）。
     * 仅前 n 个元素真正洗牌,后面 n 个不动,原地返回 list 的拷贝。
     * n >= list.size() 时返回整个 list; n <= 0 时返回空列表。
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
     * 构建轮盘赌权重：成本越低，权重越高（用倒数转换）。
     * 权重 = (minCost + 1) / (cost + 1),加 1 防止 cost=0 时权重无限大。
     * 所有父本成本相同时退化为均匀分布;空列表返空数组。
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
     * 加权随机选择（轮盘赌），返回选中的索引。
     * 根据 weights 数组中的相对权重抽样,所有权重均 0 时降级均匀随机。
     * 返回范围 [0, weights.length-1]。
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
     * 公共约束检查 + 入仓。4 关 + checkFirstOption + WareHouse 去重,全过才入仓并返回 true。
     * 4 关:连通性、互斥、多选一、组团一致。
     * 阶段二(交叉变异)产生的原始子代通过本方法统一校验入仓。
     */
    private boolean validateAndAddToWarehouse(
            List<String> fullStatus,
            List<Map<String, Object>> originalEdges,
            List<String> normList,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            List<List<String>> mutexGroupList) {
        if (fullStatus == null || originalEdges == null || normList == null
                || fullStatus.size() != normList.size()) {
            return false;
        }

        // 1) 完整约束检查(回路/互斥/组团/chooseOne/用电器覆盖)
        List<Map<String, Object>> copyEdges = createNewEdges(fullStatus, originalEdges, normList);
        Boolean bool = checkFirstOption(normList, fullStatus, copyEdges, appPositions, eleclection,
                mutexMap, chooseOneList, togetherBCList, mutexGroupList);
        if (!bool) {
            return false;
        }

        // 2) WareHouse 去重(原子)
        // WAREHOUSE_KEYS 是 ConcurrentHashMap.newKeySet()，add() 自身原子，
        // 返回 true 表示本次是新加入，可省掉 synchronized 块
        String warehouseKey = String.join(",", fullStatus);
        if (!WAREHOUSE_KEYS.add(warehouseKey)) {
            return false;
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
     * 按打断代价从高到低排序。
     * 排序键为 Double 值,降序排列。
     * 稳定排序,返回 LinkedHashMap 保持插入顺序。
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
     * 约束感知的打断展开:给定一组要打断的分支,先展开 togetherBC(同组必须一起变),
     * 再快速校验互斥约束(每对互斥组至少一方有B),全部通过则返回展开后的完整打断集合。
     * 若约束冲突则返回 null。注意:chooseOne 约束(每组最多一个C)在只添加B的情况下自动满足,无需额外检查。
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
     * 多选一传播:确保每个 chooseOne 组恰好保留一个 C。
     * 规则:1个C放过;0个C从可设为C的分支中按打断代价加权随机选一个;>1个C保留一个,其余原本C的分支转B或S。
     * 只修改原本状态为C的分支,原本是B/S的保留原状。
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
     * 让方案满足"多选一"约束(基于 statue 列表,直接修改传入的列表)。
     * 规则:每组中"恰好一个 C"。1个C满足;0个C从允许状态含C的分支中选一个改成C;>1个C保留一个,其余转非C。
     * 与 applyChooseOnePropagation 的区别:本方法直接基于当前 statue 操作,任何 C 都参与判断。
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
     * 将分支设为B（优先），若不可B则设为S。
     * 优先 B,其次 S;两者都不可则保留原状。
     * 用于 chooseOne 传播时把"非选中"的 C 分支降级。
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
     * 加权随机选一个：打断代价越低，越容易被选中。
     * 权重 = minCost / cost(倒数),即 cost 越低权重越高。
     * 候选为 1 个时直接返回;权重总和为 0 时降级为均匀随机。
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
     * 按打断代价加权不放回抽 k 个:reverse=false 时低 cost 优先,reverse=true 时高 cost 优先。
     * 概率公式: p[i] = norm^0.7 * 0.9 + 0.05,反向时 norm 取 1-norm。
     * 0.7 衰减系数让中间分支概率不至于太极端,0.9 概率上限+0.05 概率下限保证每个分支都能被抽到(不会 0% 或 100%)。
     * 所有 cost 相同时降级为均匀随机,pool 不足 k 个时直接返回全 pool。
     */
    private List<String> weightedSampleByCost(
            List<String> pool, int k, boolean reverse,
            Map<String, Double> breakCostMap, Random rnd) {
        int n = pool.size();
        List<String> result = new ArrayList<>(k);
        if (k <= 0 || n == 0) {
            return result;
        }
        if (k >= n) {
            result.addAll(pool);
            return result;
        }

        // 归一化 cost
        double minCost = Double.MAX_VALUE;
        double maxCost = -Double.MAX_VALUE;
        double[] costs = new double[n];
        for (int i = 0; i < n; i++) {
            double c = breakCostMap != null ? breakCostMap.getOrDefault(pool.get(i), 0.0) : 0.0;
            costs[i] = c;
            if (c < minCost)
                minCost = c;
            if (c > maxCost)
                maxCost = c;
        }
        double range = maxCost - minCost;
        if (range < 1e-9) {
            // 所有 cost 一样,降级为均匀随机
            List<String> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled, rnd);
            return new ArrayList<>(shuffled.subList(0, k));
        }

        // 计算每个分支的权重
        double[] weights = new double[n];
        double total = 0.0;
        for (int i = 0; i < n; i++) {
            double norm = (costs[i] - minCost) / range;
            if (reverse)
                norm = 1.0 - norm;
            double p = Math.pow(norm, WeightFactor) * MaxProbability + MinProbability;
            weights[i] = p;
            total += p;
        }

        // 不放回抽 k 个
        boolean[] used = new boolean[n];
        for (int s = 0; s < k; s++) {
            double dart = rnd.nextDouble() * total;
            double cum = 0.0;
            int pick = n - 1;
            for (int i = 0; i < n; i++) {
                if (used[i])
                    continue;
                cum += weights[i];
                if (dart <= cum) {
                    pick = i;
                    break;
                }
            }
            result.add(pool.get(pick));
            used[pick] = true;
            total -= weights[pick];
        }
        return result;
    }

    /**
     * 快速约束检查(不含拓扑):给定完整状态,检查互斥/多选一/组团约束。
     * 用于约束感知变异后的最终校验,比完整的 checkFirstOption 轻量。
     * true 表示通过所有约束。
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
     * 生成初代遗传算法方案。约束感知:枚举/抽样时先按约束传播(togetherBC展开),
     * 再快速校验互斥/多选一/组团,最后才做拓扑检查,大幅提高存活率。
     * k=1,2 走枚举,k>2 走加权随机抽样,多线程并行。
     * 不再依赖 survivalRateByK,直接使用 bestBreakCount。
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
        // 初代目标生成数：达到后所有 (k,k1) 桶立刻停手，避免父本邻域命中率高时 1-2 万个方案
        // 改成 finalLessRandomSamleNumber 是为了方便在循环里访问（final 才能被 lambda 引用）
        final int finalLessRandomSamleNumber = LessRandomSamleNumber;
        // 所有桶共享的入仓计数器：超过 target 的桶立即停止枚举
        final AtomicInteger globalResultSize = new AtomicInteger(0);

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
        // 2) adjustedBestBreakCount
        final int adjustedBestBreakCount = Math.max(1, Math.min(bestBreakCount, N));
        // 4) 【核心改造】父本邻域识别
        // 原实现：全空间 C(N,k) 随机搜索（大海捞针），命中率高时 80s+ / 1000 方案
        // 改造后：在父本邻域内变异（k1 减打断 + k2 加打断）
        // parentBs = 父本已打断的 B 分支 → 可"减打断"k1 个回退为 C
        // parentBreakableCs = 父本可打断但未打断的 C 分支 → 可"加打断"k2 个设为 B
        // 邻域大小 = sum_{k1=0..k, k1<=pB, k-k1<=pC} C(pB,k1)*C(pC,k-k1)
        // 远小于全空间 C(N,k)；且父本有效 → 邻域命中率高 50-500 倍
        List<String> parentBs = new ArrayList<>();
        List<String> parentBreakableCs = new ArrayList<>();
        for (String id : breakableIds) {
            String status = baseStatusMap.get(id);
            if ("B".equals(status)) {
                parentBs.add(id);
            } else if ("C".equals(status)) {
                parentBreakableCs.add(id);
            }
        }
        final int pB = parentBs.size();
        final int pC = parentBreakableCs.size();

        // 6) per-call 指纹预过滤 + 邻域变异（或 fallback 随机抽样）
        final Set<Long> localFingerprints = ConcurrentHashMap.newKeySet();
        long time = System.currentTimeMillis();

        // 每个 (k, k1) 桶一个 task，并行处理
        // 桶数 = sum_{k=1..maxK} min(k,pB)+1，远大于 BaseTaskCount，负载均衡
        // 单桶内部根据 totalComb 决定枚举(<=ParentBucketEnumerateThreshold)或抽样(>该值时)
        //
        // ★ k 上限优化:maxK = min(adjustedBestBreakCount, pB+pC)
        // 原因:k > pB+pC 时桶内 k1+k2=k 无法满足 (k1<=pB && k2<=pC),
        // 提前 cap 避免空转
        final int maxK = Math.min(adjustedBestBreakCount, pB + pC);
        List<Future<List<List<String>>>> futures = new ArrayList<>();
        try {
            for (int k = 1; k <= maxK; k++) {
                for (int k1 = 0; k1 <= Math.min(k, pB); k1++) {
                    int k2 = k - k1;
                    if (k2 > pC)
                        continue;
                    long totalCand = combination(pB, k1) * combination(pC, k2);
                    if (totalCand == 0)
                        continue;
                    final int k1F = k1, k2F = k2;
                    final long totalCandF = totalCand;
                    // 提前剪枝：分配桶前先检查目标，节省线程任务开销
                    if (globalResultSize.get() >= finalLessRandomSamleNumber) {
                        break;
                    }
                    if (totalCandF <= ParentBucketEnumerateThreshold) {
                        // 小桶:全枚举
                        futures.add(threadPool.submit((Callable<List<List<String>>>) () -> {
                            List<List<String>> bucketResult = new ArrayList<>();
                            processParentGuidedBucket(parentBs, k1F, parentBreakableCs, k2F, baseStatusMap,
                                    breakCostMap, canBreakToBSet, canChangeSSet, normList, originalEdges,
                                    appPositions, eleclection, mutexMap, chooseOneList, togetherBCList,
                                    togetherBCIndex, mutexConflictIndex, localFingerprints, bucketResult,
                                    new Random(seedCounter.incrementAndGet()), globalResultSize,
                                    finalLessRandomSamleNumber);
                            return bucketResult;
                        }));
                    } else {
                        // 大桶:随机抽样(每桶 ParentBucketEnumerateThreshold 次)
                        // 避免 C(pC, k2) 极大时枚举炸内存/炸时间
                        futures.add(threadPool.submit((Callable<List<List<String>>>) () -> {
                            List<List<String>> bucketResult = new ArrayList<>();
                            processParentGuidedBucketSampled(parentBs, k1F, parentBreakableCs, k2F,
                                    totalCandF, baseStatusMap, breakCostMap, canBreakToBSet, canChangeSSet,
                                    normList, originalEdges, appPositions, eleclection, mutexMap,
                                    chooseOneList, togetherBCList, togetherBCIndex, mutexConflictIndex,
                                    localFingerprints, bucketResult,
                                    new Random(seedCounter.incrementAndGet()), globalResultSize,
                                    finalLessRandomSamleNumber);
                            return bucketResult;
                        }));
                    }
                }
            }
            for (Future<List<List<String>>> f : futures) {
                try {
                    List<List<String>> part = f.get(10, TimeUnit.MINUTES);
                    if (part != null)
                        result.addAll(part);
                } catch (TimeoutException te) {
                    System.err.println("[generateInitialSchemes] 桶任务超时 30s,跳过");
                    f.cancel(true); // 尝试中断(但 worker 死了就不行)
                } catch (Exception e) {
                    System.err.println("[generateInitialSchemes] 桶任务异常: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("generateInitialSchemes 父本邻域变异异常: " + e.getMessage());
        }

        System.out.println("阶段一耗时（父本邻域变异）：" + (System.currentTimeMillis() - time) + " ms, 生成 "
                + result.size() + " 个方案");
        return result;
    }

    /**
     * 计算组合数 C(n, k),使用 long 避免溢出。
     * k<0 或 k>n 返回 0;k=0 或 k=n 返回 1;k>n/2 时对称化以减少计算量。
     * 内部用 long 累乘,超出 long 范围时可能溢出(本项目 n≤50,安全)。
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
     * 用 FNV-1a 64-bit 算法对 fullStatus 列表算指纹。
     * O(N) 遍历无分配,比 String.join 省一次 ~N 字节的字符串对象。
     * 64-bit 指纹碰撞概率 ~ 1/2^64,仅做"快速预过滤",精确去重仍依赖 WAREHOUSE_KEYS。
     */
    private static long fingerprintFullStatus(List<String> fullStatus) {
        final long FNV_OFFSET = 0xcbf29ce484222325L;
        final long FNV_PRIME = 0x100000001b3L;
        long hash = FNV_OFFSET;
        for (int i = 0, n = fullStatus.size(); i < n; i++) {
            String s = fullStatus.get(i);
            for (int j = 0, m = s.length(); j < m; j++) {
                hash ^= s.charAt(j);
                hash *= FNV_PRIME;
            }
            // 混入逗号分隔符，区分 ["BC", "S"] 与 ["B", "CS"]
            hash ^= ',';
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * 单候选 B 集的完整处理流水线：约束展开 → 多选一传播 → 约束检查 → 拓扑检查 → 入仓。
     * 父本邻域变异和全空间 fallback 随机抽样共用此函数,避免重复代码。
     */
    private boolean tryChildBs(
            Set<String> childBs,
            Map<String, String> baseStatusMap,
            Map<String, Double> breakCostMap,
            Set<String> canBreakToBSet,
            Set<String> canChangeSSet,
            List<String> normList,
            List<Map<String, Object>> originalEdges,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            Map<String, Set<String>> togetherBCIndex,
            Map<String, Set<String>> mutexConflictIndex,
            Set<Long> localFingerprints,
            List<List<String>> localResult,
            Random rnd,
            AtomicInteger globalResultSize,
            int target) {
        // 0) 全局早退：所有桶共享一个计数器，达到目标后所有桶立即停止
        if (target > 0 && globalResultSize.get() >= target) {
            return false;
        }

        // 1) 约束感知展开（处理 togetherBC + mutex）
        Set<String> expanded = expandAndValidateBreaks(
                childBs, baseStatusMap, togetherBCIndex, mutexConflictIndex);
        if (expanded == null) {
            return false;
        }

        // 2) 构造 statusMap
        Map<String, String> statusMap = new LinkedHashMap<>(baseStatusMap);
        for (String id : expanded) {
            statusMap.put(id, "B");
        }

        // 3) 多选一传播：in-place 修改 statusMap，返回的就是 statusMap 本身
        Map<String, String> propagated = applyChooseOnePropagation(
                statusMap, baseStatusMap, chooseOneList, breakCostMap,
                canBreakToBSet, canChangeSSet, rnd);
        if (propagated == null) {
            return false;
        }

        // 4) 构造 fullStatus（按 baseStatusMap.keySet() 顺序）
        List<String> fullStatus = new ArrayList<>();
        for (String id : baseStatusMap.keySet()) {
            fullStatus.add(propagated.get(id));
        }

        // 5) 快速约束校验
        if (!checkConstraintsFast(fullStatus, normList, mutexMap, chooseOneList, togetherBCList)) {
            return false;
        }

        // 6) 拓扑检查
        List<Map<String, Object>> testEdges = createNewEdges(fullStatus, originalEdges, normList);
        if (!checkFirstOption(testEdges, appPositions, eleclection)) {
            return false;
        }

        // 7) 两级去重：long 指纹（无分配）→ 精确 String key
        long fingerprint = fingerprintFullStatus(fullStatus);
        if (!localFingerprints.add(fingerprint)) {
            return false;
        }
        String warehouseKey = String.join(",", fullStatus);
        if (WAREHOUSE_KEYS.add(warehouseKey)) {
            // 入仓前再校验一次目标（防竞争：其他桶已抢到 target）
            if (target > 0 && globalResultSize.incrementAndGet() > target) {
                // 超额：回滚计数 + 仓库 key 释放（让其他相同 key 的方案可被统计）
                globalResultSize.decrementAndGet();
                WAREHOUSE_KEYS.remove(warehouseKey);
                return false;
            }
            localResult.add(fullStatus);
            return true;
        }
        return false;
    }

    /**
     * 处理一个 (k, k1) 桶：枚举所有 (unBreak, newBreak) 组合 → 调 tryChildBs。
     * k1 = 减打断的父本 B 数；k2 = 加打断的父本可打断 C 数。
     * 子代 B 集 = (parentBs - unBreak) ∪ newBreak。
     */
    private void processParentGuidedBucket(
            List<String> parentBs, int k1,
            List<String> parentBreakableCs, int k2,
            Map<String, String> baseStatusMap,
            Map<String, Double> breakCostMap,
            Set<String> canBreakToBSet,
            Set<String> canChangeSSet,
            List<String> normList,
            List<Map<String, Object>> originalEdges,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            Map<String, Set<String>> togetherBCIndex,
            Map<String, Set<String>> mutexConflictIndex,
            Set<Long> localFingerprints,
            List<List<String>> bucketResult,
            Random rnd,
            AtomicInteger globalResultSize,
            int target) {

        // 枚举所有 unBreak（k1 个减打断）和 newBreak（k2 个加打断）
        List<List<String>> allUnBreak = new ArrayList<>();
        if (k1 > 0) {
            enumerateCombinations(parentBs, k1, 0, new ArrayList<>(), allUnBreak);
        } else {
            allUnBreak.add(new ArrayList<>());
        }
        List<List<String>> allNewBreak = new ArrayList<>();
        if (k2 > 0) {
            enumerateCombinations(parentBreakableCs, k2, 0, new ArrayList<>(), allNewBreak);
        } else {
            allNewBreak.add(new ArrayList<>());
        }

        outer: for (List<String> unBreak : allUnBreak) {
            // 每轮进入时检查全局计数（多桶共享，单桶内不必每候选都查）
            if (target > 0 && globalResultSize.get() >= target) {
                break outer;
            }
            Set<String> unBreakSet = unBreak.isEmpty() ? Collections.emptySet() : new LinkedHashSet<>(unBreak);
            for (List<String> newBreak : allNewBreak) {
                // 每尝试一个候选前再查一次（更及时的早退）
                if (target > 0 && globalResultSize.get() >= target) {
                    break outer;
                }
                Set<String> candidateBs = new LinkedHashSet<>(parentBs);
                candidateBs.removeAll(unBreakSet);
                candidateBs.addAll(newBreak);
                tryChildBs(candidateBs, baseStatusMap, breakCostMap, canBreakToBSet, canChangeSSet,
                        normList, originalEdges, appPositions, eleclection, mutexMap, chooseOneList,
                        togetherBCList, togetherBCIndex, mutexConflictIndex, localFingerprints, bucketResult,
                        rnd, globalResultSize, target);
            }
        }
    }

    /**
     * 父本邻域单桶 — 抽样版(配合枚举版 processParentGuidedBucket)。
     * 当 totalComb = C(pB,k1) * C(pC,k2) 超过 ParentBucketEnumerateThreshold 时调用。
     * 替代全枚举,改为随机抽样 min(ParentBucketEnumerateThreshold, totalComb) 个候选。
     */
    private void processParentGuidedBucketSampled(
            List<String> parentBs, int k1,
            List<String> parentBreakableCs, int k2,
            long totalComb,
            Map<String, String> baseStatusMap,
            Map<String, Double> breakCostMap,
            Set<String> canBreakToBSet,
            Set<String> canChangeSSet,
            List<String> normList,
            List<Map<String, Object>> originalEdges,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            Map<String, Set<String>> togetherBCIndex,
            Map<String, Set<String>> mutexConflictIndex,
            Set<Long> localFingerprints,
            List<List<String>> bucketResult,
            Random rnd,
            AtomicInteger globalResultSize,
            int target) {

        int sampleSize = (int) Math.min(ParentBucketEnumerateThreshold, totalComb);
        for (int s = 0; s < sampleSize; s++) {
            // 桶内早退:目标已达成
            if (target > 0 && globalResultSize.get() >= target) {
                break;
            }
            // 1) 加权抽 k1 个减打断:高 cost 优先(把"代价高但被打断"的分支撤掉,降低方案成本)
            Set<String> unBreakSet = new LinkedHashSet<>();
            if (k1 > 0 && !parentBs.isEmpty()) {
                unBreakSet.addAll(weightedSampleByCost(parentBs, k1, true, breakCostMap, rnd));
            }
            // 2) 加权抽 k2 个加打断:低 cost 优先(挑"代价低"的分支打断,提升方案经济性)
            Set<String> newBreakSet = new LinkedHashSet<>();
            if (k2 > 0 && !parentBreakableCs.isEmpty()) {
                newBreakSet.addAll(weightedSampleByCost(parentBreakableCs, k2, false, breakCostMap, rnd));
            }
            // 3) 合成 candidateBs = (parentBs - unBreak) ∪ newBreak
            Set<String> candidateBs = new LinkedHashSet<>(parentBs);
            candidateBs.removeAll(unBreakSet);
            candidateBs.addAll(newBreakSet);
            // 4) 走 tryChildBs 完整流水线(约束+拓扑+指纹去重+入仓)
            tryChildBs(candidateBs, baseStatusMap, breakCostMap, canBreakToBSet, canChangeSSet,
                    normList, originalEdges, appPositions, eleclection, mutexMap, chooseOneList,
                    togetherBCList, togetherBCIndex, mutexConflictIndex, localFingerprints, bucketResult,
                    rnd, globalResultSize, target);
        }
    }

    /**
     * 递归枚举 list 中选 k 个的所有组合。
     * 通过 start 游标避免重复,current 暂存当前组合,result 收集所有组合。
     * 剪枝:剩余元素不够凑齐 k 个时直接返回。
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
     * 对生成的方案做检查:1、回路是否导通 2、用电器周围是否至少存在一个分支。
     * 仅做拓扑连通性检查,不含约束;约束检查用 checkFirstOption 的另一重载。
     * 不通过则说明该方案被打断成多个不连通子图,优化算法拒绝。
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
     * 判断用电器对应位置点两端是否存在非 B 状态分支。
     * 任意一段连通即返回 true,保证用电器不被孤立。
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
     * 根据传入的分支打断状况,生成新的分支详情列表。
     * 每个分支按 normList 索引从 edgeStatue 取状态,深拷贝到新 map。
     * 内部用 normIndexMap 做 O(1) 索引查找,避免反复 indexOf。
     */
    public List<Map<String, Object>> createNewEdges(List<String> edgeStatue, List<Map<String, Object>> edgeDetails,
            List<String> normList) {
        List<Map<String, Object>> newEdges = new ArrayList<>(edgeDetails.size());
        Map<String, Integer> normIndexMap = new HashMap<>(normList.size() * 2);
        for (int i = 0; i < normList.size(); i++) {
            normIndexMap.put(normList.get(i), i);
        }
        for (Map<String, Object> src : edgeDetails) {
            Map<String, Object> newEdge = new HashMap<>(src); // ← 深拷贝
            String id = (String) newEdge.get("id");
            Integer number = normIndexMap.get(id);
            if (number != null) {
                newEdge.put("topologyStatusCode", edgeStatue.get(number));
            }
            newEdges.add(newEdge);
        }
        return newEdges;
    }

    /**
     * 判断 targetList 是否在 listOfLists 中，用 list.equals 逐个比较。
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
     * 完整约束检查重载:1、是否存在互斥 2、回路是否导通 3、用电器周围至少一个分支 4、chooseOne 数量约束。
     * 包含组团一致、互斥、changeTogether 组、chooseOne、拓扑连通性、用电器覆盖检查,全过才返回 true。
     * 与轻量版 checkConstraintsFast 区别:本版本包含拓扑连通性检查,适合做最终入仓校验。
     * mutexGroupList 是 changeTogether 组(已与 togetherBC 同 key 合并),需要"同组同态"检查。
     */
    public Boolean checkFirstOption(List<String> normList, List<String> changeList, List<Map<String, Object>> edges,
            List<Map<String, String>> appPositions, Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            List<List<String>> mutexGroupList) {
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
        // 规则：
        // 1) 同 mutexFullName 内所有分支必须同状态（全 B 或全 C/S）
        // 2) 不同 mutexFullName 之间状态必须相反
        // - 第一组 B → 其他组必须 C 或 S（不允许 B）
        // - 第一组 C/S → 其他组必须 B（被打断）
        // 3) 同一 changeTogether 组（mutexGroupList）内所有分支必须同状态
        // （处理"互斥组团"语义：A 在 changeTogether 组里，A 变 B 时整个组团都变 B）
        Set<String> mutexName = mutexMap.keySet();
        for (String s : mutexName) {
            Map<String, List<String>> listMap = mutexMap.get(s);
            Set<String> sonset = listMap.keySet();
            int cycleNumber = 1;
            String statue = null;
            for (String edgeId : sonset) {
                List<String> list = listMap.get(edgeId);
                if (cycleNumber == 1) {
                    // 第一组:同 mutexFullName 内必须同状态
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
                    // 其他组:必须和第一组状态相反
                    if (statue.equals("B")) {
                        // 第一组 B → 其他组必须 C 或 S(不允许 B,避免"同 mutex 相反"语义失效)
                        for (String topologyStatusCode : list) {
                            if (!(changeList.get(normList.indexOf(topologyStatusCode)).equals("C")
                                    || changeList.get(normList.indexOf(topologyStatusCode)).equals("S"))) {
                                return false;
                            }
                        }
                    } else {
                        // 第一组 C/S → 其他组必须 B
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

        // changeTogether 组同态检查(处理"互斥组团"语义)
        // 组内任意一条边为 B,其余边也必须为 B(组团一起打断)
        // 前提:mutexGroupMap 已与 togetherBCMap 合并(同 key 组团成员全在 mutexGroupList 里)
        if (mutexGroupList != null) {
            for (List<String> group : mutexGroupList) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                String groupStatue = changeList.get(normList.indexOf(group.get(0)));
                for (String id : group) {
                    if (!groupStatue.equals(changeList.get(normList.indexOf(id)))) {
                        return false;
                    }
                }
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
     * 使用 AI 预测模型对每个样本预测成本,返回成本最优的 topN 样本。
     * 替代整车计算方法,直接通过 GINE 模型预测成本。
     * 多线程并行 + 上一代 top 10% 注入 + WareHouseTop 去重。
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
            Map<String, String> pointMap, List<Map<String, Object>> findBestPre, ObjectMapper mapper) throws Exception {
        GINEInferenceEngine gine = new GINEInferenceEngine();
        List<Float> length = (List<Float>) branchLength.get("branchLength");
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) jsonMap.get("loopInfos");
        List<Map<String, String>> pointsList = (List<Map<String, String>>) jsonMap.get("points");
        List<Map<String, Object>> resultList = new ArrayList<>();
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (List<String> strings : simpleList) {
            tasks.add(() -> {
                List<String> serviceableStatue = strings.stream().collect(Collectors.toList());

                List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                // 深拷贝
                List<Map<String, String>> originalList = (List<Map<String, String>>) jsonMap.get("appPositions");
                List<Map<String, String>> deepCopyAppPositions = new ArrayList<>(originalList.size());
                for (Map<String, String> item : originalList) {
                    deepCopyAppPositions.add(new HashMap<>(item)); // 逐个复制
                }
                // 深拷贝
                Map<String, Object> threadLocalJsonMap = new HashMap<>(jsonMap);
                threadLocalJsonMap.put("edges", serviceableEdge);
                threadLocalJsonMap.put("appPositions", deepCopyAppPositions);

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
                float predict = gine.predict(x, edgeIndex, edgeAttr);
                // 构建返回结果：仅保留 serviceableStatue 和成本，不携带 serviceableEdges
                // serviceableEdges（所有边的深拷贝）约 100KB/方案，10000 方案 ≈ 1GB，下游不需要
                Map<String, Object> costResultData = new HashMap<>();
                costResultData.put("总成本", (double) predict);
                // AI模型仅预测成本，重量和长度置为占位值
                costResultData.put("总重量", 0.0);
                costResultData.put("总长度", 0.0);

                Map<String, Object> map = new HashMap<>();
                map.put("成本", costResultData);
                map.put("serviceableStatue", serviceableStatue);
                return map;
            });
        }
        // 线程池提交任务
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Callable<Map<String, Object>> task : tasks) {
            futures.add(threadPool.submit(task));
        }
        // 提交完毕立即释放 tasks 引用（每个 task 内部持有的闭包变量可提前被 GC）
        tasks = null;

        // 获取线程池结果（边取边释放 Future，避免全部结果同时驻留内存）
        for (int i = 0; i < futures.size(); i++) {
            Future<Map<String, Object>> future = futures.get(i);
            try {
                Map<String, Object> result = future.get(6000, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    resultList.add(result);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            futures.set(i, null); // 释放 Future 引用，允许 GC
        }
        futures.clear();
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
        // 释放全量预测结果列表（10000+ 条），仅保留 topBeat
        resultList = null;
        // WareHouseTop 去重入仓
        for (Map<String, Object> map : topBeat) {
            List<String> list = (List<String>) map.get("serviceableStatue");
            if (!containsList(list, WareHouseTop)) {
                WareHouseTop.add(list);
            }
        }
        return topBeat;
    }

    /**
     * 计算各分支长度。
     * 优先用用户确认的长度,否则用参考长度,都没有的按状态 C/S 给 200、B 给 0。
     * 按 normList 顺序输出 branchLength 列表,用于 AI 模型分支长度特征。
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

    /**
     * 构建分支起终点的索引连接关系。
     * 按 normList 顺序遍历 edges,将每条分支的 startPointName/endPointName 映射为 allNameList
     * 中的下标。
     * 返回 [startIndex 列表, endIndex 列表] — 用于 AI 模型 edgeIndex 构造。
     */
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
     * 根据用电器名称获取对应的位置点名称。
     * 优先取 unregularPointName,缺则取 regularPointName,都没有返 null。
     * appName 比较忽略大小写;appPositions 为空时返 null。
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
