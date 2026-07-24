//package HarnessPackOpti.ProjectInfoOutPut;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.Set;
//import java.util.concurrent.ConcurrentHashMap;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//import HarnessPackOpti.JsonToMap;
//import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
//import HarnessPackOpti.Algorithm.GenerateTopoMatrixConnector;
//import HarnessPackOpti.Algorithm.IntergateCircuitInfo;
//import HarnessPackOpti.InfoRead.ReadProjectInfo;
//import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
//
///**
// * 配电驱动优化整车信息计算
// * <p>
// * 性能优化点：
// * 1) 跳过输入/输出 JSON 往返（powerOptimizeFromMap 直接返回 Map）
// * 2) ProjectContext 缓存：拓扑、邻接矩阵、Excel 库等全工程不变字段仅构建一次
// * 3) evaluateDelta 增量评估：仅重算 changedLoopIds 对应的回路，其余直接复用 baseline
// */
//public class PowerProjectCircuitInfoOutput {
//
//    public static Map<String, Map<String, String>> elecFixedLocationLibrary = null;
//    public static Map<String, Double> elecBusinessPrice = null;
//
//    // 构造函数中读取Excel
//    public PowerProjectCircuitInfoOutput() {
//        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
//        this.elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
//        this.elecBusinessPrice = readWireInfoLibrary.getElecBusinessPrice();
//    }
//
//    /**
//     * 工程静态上下文（跨候选复用）
//     * - projectInfo 静态部分：拓扑信息、方案信息、项目基本信息、所有端点信息、所有分支信息
//     * - 邻接矩阵、连接器矩阵、Excel 库、商务价
//     * - 不含 loopInfos / appPositions（每候选会重建）
//     * - 缓存：findTwoPointInfo / circuitCoilingLength 结果（按 loopId+起止点缓存）
//     * - 缓存：baseline 每回路 cost/weight/length（用于增量 totals 计算）
//     */
//    public static class ProjectContext {
//        public final Map<String, Object> staticProjectInfo;
//        public final List<Map<String, String>> edges;
//        public final GenerateTopoMatrix adjacencyMatrixGraph;
//        public final GenerateTopoMatrixConnector adjacencyMatrixGraphConnector;
//        public final Map<String, Map<String, String>> elecFixedLocationLibrary;
//        public final Map<String, Double> elecBusinessPrice;
//        public final ProjectCircuitInfoOutput projectCircuitInfoOutput;
//
//        // 缓存：findTwoPointInfo 结果（key: loopId|startApp|endApp|startPos|endPos）
//        public final ConcurrentHashMap<String, Map<String, Object>> twoPointInfoCache = new ConcurrentHashMap<>();
//        // 缓存：circuitCoilingLength 结果（key: loopId|startPos|endPos）
//        public final ConcurrentHashMap<String, Double> coilingLengthCache = new ConcurrentHashMap<>();
//        // 缓存：归一化的 baseline loopInfos（按 loopId 索引）
//        public final Map<String, Map<String, String>> normalizedBaselineLoopById;
//        // 缓存：归一化的 baseline appPositions（按 appName 索引）
//        public final Map<String, Map<String, String>> normalizedBaselineAppByName;
//        // 缓存：baseline 每回路的 总成本/重量/长度（用于增量 totals）
//        public final Map<String, Double> baselineLoopTotalCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopWeight = new HashMap<>();
//        public final Map<String, Double> baselineLoopLength = new HashMap<>();
//        public final Map<String, Double> baselineLoopCoilingLength = new HashMap<>();
//        public final Map<String, Double> baselineLoopCoilingCount = new HashMap<>();
//        public final Map<String, Double> baselineLoopBreakCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopWetCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopTerminalCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopWireCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopTerminalTotalCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopShellCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopWaterproofCost = new HashMap<>();
//        public final Map<String, Double> baselineLoopDiameter = new HashMap<>();
//        public final Map<String, Double> baselineLoopBreakCount = new HashMap<>();
//        // 缓存：baseline 汇总（用 Double 类型以便后续增量计算）
//        public final Map<String, Double> baselineSumTotals = new HashMap<>();
//
//        public ProjectContext(Map<String, Object> staticProjectInfo,
//                List<Map<String, String>> edges,
//                GenerateTopoMatrix adjacencyMatrixGraph,
//                GenerateTopoMatrixConnector adjacencyMatrixGraphConnector,
//                Map<String, Map<String, String>> elecFixedLocationLibrary,
//                Map<String, Double> elecBusinessPrice,
//                ProjectCircuitInfoOutput projectCircuitInfoOutput) {
//            this.staticProjectInfo = staticProjectInfo;
//            this.edges = edges;
//            this.adjacencyMatrixGraph = adjacencyMatrixGraph;
//            this.adjacencyMatrixGraphConnector = adjacencyMatrixGraphConnector;
//            this.elecFixedLocationLibrary = elecFixedLocationLibrary;
//            this.elecBusinessPrice = elecBusinessPrice;
//            this.projectCircuitInfoOutput = projectCircuitInfoOutput;
//            // 初始化归一化字段为后续填充
//            this.normalizedBaselineLoopById = new HashMap<>();
//            this.normalizedBaselineAppByName = new HashMap<>();
//        }
//    }
//
//    /**
//     * 准备静态上下文：构建邻接矩阵、加载 Excel 库；
//     * 提取 projectInfo 的静态部分（剥离用电器/回路用电器，回路绕线长度计算需要 pointList 静态）。
//     * 同时：归一化 baseline loopInfos/appPositions，缓存到 ctx 中（避免每个候选重建 HashMap）。
//     */
//    public ProjectContext prepareContext(Map<String, Object> mapFile) throws Exception {
//        // 解析工程信息
//        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
//        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
//        List<Map<String, String>> edges = (List<Map<String, String>>) projectInfo.get("所有分支信息");
//
//        // 邻接矩阵（拓扑相关，工程不变）
//        List<String> strPointName = new ArrayList<>();
//        List<String> endPointName = new ArrayList<>();
//        List<List<String>> branchBreakList = new ArrayList<>();
//        for (Map<String, String> edge : edges) {
//            strPointName.add(edge.get("分支起点名称"));
//            endPointName.add(edge.get("分支终点名称"));
//            if ("B".equals(edge.get("分支打断"))) {
//                List<String> interruptedEdgelist = new ArrayList<>();
//                interruptedEdgelist.add(edge.get("分支起点名称"));
//                interruptedEdgelist.add(edge.get("分支终点名称"));
//                branchBreakList.add(interruptedEdgelist);
//            }
//        }
//        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);
//        adjacencyMatrixGraph.adjacencyMatrix();
//        adjacencyMatrixGraph.addEdge();
//        adjacencyMatrixGraph.getAdj();
//
//        // 全连通连接器矩阵（用于回路绕线长度计算）
//        GenerateTopoMatrixConnector adjacencyMatrixGraphConnector = new GenerateTopoMatrixConnector(strPointName,
//                endPointName);
//        adjacencyMatrixGraphConnector.adjacencyMatrix();
//        adjacencyMatrixGraphConnector.addEdge();
//        adjacencyMatrixGraphConnector.getAdj();
//
//        // 加载 Excel 库（保证与原逻辑一致）
//        if (elecFixedLocationLibrary == null) {
//            elecFixedLocationLibrary = new ReadWireInfoLibrary().getElecFixedLocationLibrary();
//        }
//        if (elecBusinessPrice == null) {
//            elecBusinessPrice = new ReadWireInfoLibrary().getElecBusinessPrice();
//        }
//
//        // 剥离用电器/回路用电器，构造静态 projectInfo（用于 findTwoPointInfo 跨候选复用）
//        Map<String, Object> staticProjectInfo = new HashMap<>(projectInfo);
//        staticProjectInfo.remove("用电器信息");
//        staticProjectInfo.remove("回路用电器信息");
//
//        ProjectContext ctx = new ProjectContext(staticProjectInfo, edges,
//                adjacencyMatrixGraph, adjacencyMatrixGraphConnector,
//                elecFixedLocationLibrary, elecBusinessPrice, new ProjectCircuitInfoOutput());
//
//        // 预归一化 baseline loopInfos（按 loopId 索引），避免每个候选重建 HashMap
//        List<Map<String, String>> rawBaselineLoops = (List<Map<String, String>>) projectInfo.get("回路用电器信息");
//        if (rawBaselineLoops != null) {
//            List<Map<String, String>> normalizedBaselineLoops = normalizeLoopInfos(rawBaselineLoops);
//            for (Map<String, String> li : normalizedBaselineLoops) {
//                String id = String.valueOf(li.get("回路id"));
//                ctx.normalizedBaselineLoopById.put(id, li);
//            }
//        }
//        // 预归一化 baseline appPositions（按 appName 索引）
//        List<Map<String, String>> rawBaselineApps = (List<Map<String, String>>) projectInfo.get("用电器信息");
//        if (rawBaselineApps != null) {
//            List<Map<String, String>> normalizedBaselineApps = normalizeAppPositions(rawBaselineApps);
//            for (Map<String, String> ap : normalizedBaselineApps) {
//                String name = ap.get("用电器名称");
//                if (name != null) {
//                    ctx.normalizedBaselineAppByName.put(name, ap);
//                }
//            }
//        }
//        return ctx;
//    }
//
//    /**
//     * 构造候选 projectInfo：静态部分 + 当前候选的用电器/回路
//     * 关键：appPositions/loopInfos 必须以"深拷贝"传入，避免污染 ctx
//     */
//    @SuppressWarnings({ "unchecked", "rawtypes" })
//    private Map<String, Object> buildCandidateProjectInfo(ProjectContext ctx,
//            List<? extends Map<String, ?>> appPositions,
//            List<? extends Map<String, ?>> loopInfos) {
//        Map<String, Object> candidate = new HashMap<>(ctx.staticProjectInfo);
//        candidate.put("用电器信息", (List) appPositions);
//        candidate.put("回路用电器信息", (List) loopInfos);
//        return candidate;
//    }
//
//    /**
//     * 将 GA 候选的 loopInfo（英文 id/startApp/endApp 字段）转为 findTwoPointInfo 期望的中文回路id 键
//     */
//    private List<Map<String, String>> normalizeLoopInfos(List<? extends Map<String, ?>> raw) {
//        List<Map<String, String>> out = new ArrayList<>(raw.size());
//        for (Map<String, ?> src : raw) {
//            Map<String, String> dst = new HashMap<>();
//            for (Map.Entry<String, ?> e : src.entrySet()) {
//                Object v = e.getValue();
//                dst.put(e.getKey(), v == null ? null : v.toString());
//            }
//            if (!dst.containsKey("回路id") && dst.containsKey("id")) {
//                dst.put("回路id", dst.get("id"));
//            }
//            if (!dst.containsKey("回路起点用电器") && dst.containsKey("startApp")) {
//                dst.put("回路起点用电器", dst.get("startApp"));
//            }
//            if (!dst.containsKey("回路终点用电器") && dst.containsKey("endApp")) {
//                dst.put("回路终点用电器", dst.get("endApp"));
//            }
//            if (!dst.containsKey("回路起点用电器接口编号") && dst.containsKey("startAppPort")) {
//                dst.put("回路起点用电器接口编号", dst.get("startAppPort"));
//            }
//            if (!dst.containsKey("回路终点用电器接口编号") && dst.containsKey("endAppPort")) {
//                dst.put("回路终点用电器接口编号", dst.get("endAppPort"));
//            }
//            if (!dst.containsKey("回路导线选型") && dst.containsKey("loopWireway")) {
//                dst.put("回路导线选型", dst.get("loopWireway"));
//            }
//            if (!dst.containsKey("回路属性") && dst.containsKey("loopAttr")) {
//                dst.put("回路属性", dst.get("loopAttr"));
//            }
//            if (!dst.containsKey("所属系统") && dst.containsKey("loopSys")) {
//                dst.put("所属系统", dst.get("loopSys"));
//            }
//            if (!dst.containsKey("回路编号") && dst.containsKey("loopNo")) {
//                dst.put("回路编号", dst.get("loopNo"));
//            }
//            out.add(dst);
//        }
//        return out;
//    }
//
//    /**
//     * 将 GA 候选的 appPosition（英文 appName/unregularPointName 字段）转为 findNode 期望的中文键
//     */
//    private List<Map<String, String>> normalizeAppPositions(List<? extends Map<String, ?>> raw) {
//        List<Map<String, String>> out = new ArrayList<>(raw.size());
//        for (Map<String, ?> src : raw) {
//            Map<String, String> dst = new HashMap<>();
//            for (Map.Entry<String, ?> e : src.entrySet()) {
//                Object v = e.getValue();
//                dst.put(e.getKey(), v == null ? null : v.toString());
//            }
//            if (!dst.containsKey("用电器名称") && dst.containsKey("appName")) {
//                dst.put("用电器名称", dst.get("appName"));
//            }
//            if (!dst.containsKey("用电器id") && dst.containsKey("id")) {
//                dst.put("用电器id", dst.get("id"));
//            }
//            if (!dst.containsKey("用户更改后用电器位置名称") && dst.containsKey("unregularPointName")) {
//                dst.put("用户更改后用电器位置名称", dst.get("unregularPointName"));
//            }
//            if (!dst.containsKey("用户更改后用电器位置id") && dst.containsKey("unregularPointId")) {
//                dst.put("用户更改后用电器位置id", dst.get("unregularPointId"));
//            }
//            if (!dst.containsKey("用电器固化位置点名称") && dst.containsKey("regularPointName")) {
//                dst.put("用电器固化位置点名称", dst.get("regularPointName"));
//            }
//            if (!dst.containsKey("用电器固化位置点id") && dst.containsKey("regularPointId")) {
//                dst.put("用电器固化位置点id", dst.get("regularPointId"));
//            }
//            if (!dst.containsKey("用电器位置是否固化") && dst.containsKey("positionRegular")) {
//                dst.put("用电器位置是否固化", dst.get("positionRegular"));
//            }
//            out.add(dst);
//        }
//        return out;
//    }
//
//    /**
//     * 整车信息计算（核心实现，不做 JSON 序列化）
//     * 入参：候选的 appPositions + loopInfos
//     * 返回：含 projectCircuitInfo / circuitInfo / bundeleRelatedCircuitInfo 等的 Map
//     */
//    public Map<String, Object> evaluateFullFromContext(ProjectContext ctx,
//            List<? extends Map<String, ?>> appPositions,
//            List<? extends Map<String, ?>> loopInfos) {
//        Map<String, Object> projectInfo = buildCandidateProjectInfo(ctx, appPositions, loopInfos);
//        return evaluateCore(projectInfo, ctx);
//    }
//
//    /**
//     * 由 loopdetails Map 重新汇总 projectCircuitInfo（与原 circuitProjectInfo 行为一致）
//     * 共享 ProjectCircuitInfoOutput 实例，避免每候选都 new 一个对象。
//     */
//    private Map<String, Object> projectCircuitInfoFromLoopdetails(Map<String, Object> loopdetails) {
//        return SHARED_PROJECT_CIRCUIT_INFO_OUTPUT.circuitProjectInfo(loopdetails);
//    }
//
//    /** 线程安全的共享实例（circuitProjectInfo 内部无状态） */
//    private static final ProjectCircuitInfoOutput SHARED_PROJECT_CIRCUIT_INFO_OUTPUT = new ProjectCircuitInfoOutput() {
//        // 复用：避免每候选 new ProjectCircuitInfoOutput() + ReadWireInfoLibrary()
//    };
//
//    /**
//     * 增量评估：仅重算 changedLoopIds，其余回路的成本直接复用 baseline
//     * 适用于 GA 内层：候选只变了 1~N 条回路的起止点 / 用电器位置
//     * 返回：projectCircuitInfo（含总成本/总重量/总长度）；changedLoop 任一失败则返回 null
//     * 入参 loopInfo 支持两种字段命名（"id/startApp/endApp" 或 "回路id/回路起点用电器/回路终点用电器"）
//     */
//    public Map<String, Object> evaluateDeltaFromContext(ProjectContext ctx,
//            List<? extends Map<String, ?>> candidateAppPositions,
//            List<? extends Map<String, ?>> candidateLoopInfos,
//            Set<String> changedLoopIds,
//            Map<String, Object> baselineLoopdetails) {
//        if (changedLoopIds == null || changedLoopIds.isEmpty()) {
//            // 没有任何回路变化，直接复用 baseline
//            return projectCircuitInfoFromLoopdetails(baselineLoopdetails);
//        }
//        // 候选 loopInfos 标准化为 findTwoPointInfo 期望的"回路id/回路起点用电器/回路终点用电器/回路导线选型"等中文键
//        List<Map<String, String>> normalizedLoopInfos = normalizeLoopInfos(candidateLoopInfos);
//        List<Map<String, String>> normalizedAppPositions = normalizeAppPositions(candidateAppPositions);
//        Map<String, Object> candidateProjectInfo = buildCandidateProjectInfo(ctx, normalizedAppPositions,
//                normalizedLoopInfos);
//
//        // 浅拷贝：复用 baseline 回路的 costMap 引用，避免对未变回路重复计算
//        Map<String, Object> newLoopdetails = new HashMap<>(baselineLoopdetails);
//
//        // 收集 changed loop 对应 loopInfo（按 id 索引一次）
//        Map<String, Map<String, String>> loopInfoById = new HashMap<>();
//        for (Map<String, String> li : normalizedLoopInfos) {
//            loopInfoById.put(String.valueOf(li.get("回路id")), li);
//        }
//
//        // 仅对 changed loop 调 findTwoPointInfo
//        for (String loopId : changedLoopIds) {
//            Map<String, String> li = loopInfoById.get(loopId);
//            if (li == null) {
//                return null;
//            }
//            Map<String, Object> twoPointInfo = ctx.projectCircuitInfoOutput.findTwoPointInfo(
//                    li, candidateProjectInfo, ctx.adjacencyMatrixGraph,
//                    ctx.elecFixedLocationLibrary, true, null, null, ctx.elecBusinessPrice);
//            if (twoPointInfo == null) {
//                return null;
//            }
//            // 回路id 字段保持原值
//            twoPointInfo.put("回路id", loopId);
//            newLoopdetails.put(loopId, twoPointInfo);
//        }
//
//        // 回路绕线长度：仅对 changed loop 重新计算（未变 loop 保持 baseline 已有值）
//        // 构造只含 changed loop 的子图，circuitCoilingLength 内部按 keySet 迭代，会原地修改 Map 中的回路对象
//        Map<String, Object> changedLoopsForCoiling = new HashMap<>();
//        for (String loopId : changedLoopIds) {
//            changedLoopsForCoiling.put(loopId, newLoopdetails.get(loopId));
//        }
//        ctx.projectCircuitInfoOutput.circuitCoilingLength(
//                changedLoopsForCoiling, ctx.edges, ctx.adjacencyMatrixGraphConnector, candidateProjectInfo);
//
//        // 重新汇总 totals
//        return projectCircuitInfoFromLoopdetails(newLoopdetails);
//    }
//
//    /**
//     * 用 baseline 的完整 loopdetails 填充 ProjectContext 的增量缓存（每回路
//     * cost/weight/length/coiling）。
//     * GA 内层 evaluateDeltaFast 用此缓存做增量 totals 计算，避免每候选重算全量。
//     */
//    public void primeBaselineCache(ProjectContext ctx, Map<String, Object> baselineLoopdetails) {
//        if (ctx == null || baselineLoopdetails == null) {
//            return;
//        }
//        ctx.baselineLoopTotalCost.clear();
//        ctx.baselineLoopWeight.clear();
//        ctx.baselineLoopLength.clear();
//        ctx.baselineLoopCoilingLength.clear();
//        ctx.baselineLoopCoilingCount.clear();
//        ctx.baselineLoopBreakCost.clear();
//        ctx.baselineLoopWetCost.clear();
//        ctx.baselineLoopTerminalCost.clear();
//        ctx.baselineLoopWireCost.clear();
//        ctx.baselineLoopTerminalTotalCost.clear();
//        ctx.baselineLoopShellCost.clear();
//        ctx.baselineLoopWaterproofCost.clear();
//        ctx.baselineLoopDiameter.clear();
//        ctx.baselineLoopBreakCount.clear();
//        double sumTotal = 0, sumWeight = 0, sumLength = 0, sumCoil = 0, sumCoilCount = 0;
//        double sumBreak = 0, sumWet = 0, sumTerm = 0, sumWire = 0, sumTermTotal = 0;
//        double sumShell = 0, sumWater = 0;
//        for (Map.Entry<String, Object> e : baselineLoopdetails.entrySet()) {
//            String id = e.getKey();
//            @SuppressWarnings("unchecked")
//            Map<String, Object> lm = (Map<String, Object>) e.getValue();
//            double cost = num(lm, "回路总成本");
//            double weight = num(lm, "回路重量");
//            double length = num(lm, "回路长度");
//            double coil = num(lm, "回路绕线长度");
//            double coilCount = coil > 0 ? 1 : 0;
//            double breakC = num(lm, "回路打断成本");
//            double wetC = num(lm, "回路湿区成本加成");
//            double termC = num(lm, "回路两端端子成本");
//            double wireC = num(lm, "回路导线成本");
//            double termTotal = num(lm, "端子成本");
//            double shellC = num(lm, "连接器塑壳成本");
//            double waterC = num(lm, "防水塞成本");
//            double diameter = num(lm, "回路理论直径");
//            double breakCount = num(lm, "回路打断次数");
//            ctx.baselineLoopTotalCost.put(id, cost);
//            ctx.baselineLoopWeight.put(id, weight);
//            ctx.baselineLoopLength.put(id, length);
//            ctx.baselineLoopCoilingLength.put(id, coil);
//            ctx.baselineLoopCoilingCount.put(id, coilCount);
//            ctx.baselineLoopBreakCost.put(id, breakC);
//            ctx.baselineLoopWetCost.put(id, wetC);
//            ctx.baselineLoopTerminalCost.put(id, termC);
//            ctx.baselineLoopWireCost.put(id, wireC);
//            ctx.baselineLoopTerminalTotalCost.put(id, termTotal);
//            ctx.baselineLoopShellCost.put(id, shellC);
//            ctx.baselineLoopWaterproofCost.put(id, waterC);
//            ctx.baselineLoopDiameter.put(id, diameter);
//            ctx.baselineLoopBreakCount.put(id, breakCount);
//            sumTotal += cost;
//            sumWeight += weight;
//            sumLength += length;
//            sumCoil += coil;
//            sumCoilCount += coilCount;
//            sumBreak += breakC;
//            sumWet += wetC;
//            sumTerm += termC;
//            sumWire += wireC;
//            sumTermTotal += termTotal;
//            sumShell += shellC;
//            sumWater += waterC;
//        }
//        ctx.baselineSumTotals.put("总成本", sumTotal);
//        ctx.baselineSumTotals.put("回路总重量", sumWeight);
//        ctx.baselineSumTotals.put("回路总长度", sumLength);
//        ctx.baselineSumTotals.put("回路绕线总长度", sumCoil);
//        ctx.baselineSumTotals.put("回路绕线数量", sumCoilCount);
//        ctx.baselineSumTotals.put("回路打断总成本", sumBreak);
//        ctx.baselineSumTotals.put("回路湿区成本总加成", sumWet);
//        ctx.baselineSumTotals.put("回路两端端子总成本", sumTerm);
//        ctx.baselineSumTotals.put("回路导线总成本", sumWire);
//        ctx.baselineSumTotals.put("端子总成本", sumTermTotal);
//        ctx.baselineSumTotals.put("连接器塑壳总成本", sumShell);
//        ctx.baselineSumTotals.put("防水塞总成本", sumWater);
//    }
//
//    /** 从 Map 中安全取数值字段（缺失返回 0） */
//    private static double num(Map<String, Object> m, String key) {
//        Object v = m.get(key);
//        if (v == null)
//            return 0.0;
//        if (v instanceof Number)
//            return ((Number) v).doubleValue();
//        try {
//            return Double.parseDouble(v.toString());
//        } catch (NumberFormatException e) {
//            return 0.0;
//        }
//    }
//
//    /**
//     * 高速增量评估（GA 内层专用）：
//     * 1) findTwoPointInfo 按 (loopId,startApp,endApp,startPos,endPos) 缓存
//     * 2) circuitCoilingLength 按 (loopId,startPos,endPos) 缓存
//     * 3) 跳过 normalize 阶段：直接基于 ctx 缓存 + 候选 loopInfo 计算位置
//     * 4) totals 增量计算：baseline - 老回路 + 新回路（不重算整张表）
//     * 返回：精简的 totals Map（仅含 GA 真正用到的 总成本/总重量/总长度 等键）；
//     * 候选无效返回 null。
//     */
//    public Map<String, Double> evaluateDeltaFast(ProjectContext ctx,
//            List<? extends Map<String, ?>> candidateAppPositions,
//            List<? extends Map<String, ?>> candidateLoopInfos,
//            Set<String> changedLoopIds,
//            Map<String, Object> baselineLoopdetails) {
//        if (ctx == null) {
//            return null;
//        }
//        if (changedLoopIds == null || changedLoopIds.isEmpty()) {
//            Map<String, Double> out = new HashMap<>();
//            out.put("总成本", ctx.baselineSumTotals.getOrDefault("总成本", 0.0));
//            out.put("回路总重量", ctx.baselineSumTotals.getOrDefault("回路总重量", 0.0));
//            out.put("回路总长度", ctx.baselineSumTotals.getOrDefault("回路总长度", 0.0));
//            return out;
//        }
//
//        // 1) 构建候选 appName → 当前位置 索引（仅遍历一次）
//        // 位置解析遵循 findNode 语义：优先 unregularPointName，缺失则回退到 regularPointName
//        Map<String, String> candidateAppPos = new HashMap<>();
//        for (Map<String, ?> ap : candidateAppPositions) {
//            Object name = ap.get("appName");
//            if (name == null) {
//                continue;
//            }
//            Object pos = ap.get("unregularPointName");
//            if (pos == null || (pos instanceof String && ((String) pos).isEmpty())) {
//                pos = ap.get("regularPointName");
//            }
//            candidateAppPos.put(name.toString(), pos == null ? null : pos.toString());
//        }
//
//        // 2) 收集 changed loop 对应 loopInfo；构建候选 loopId → loopInfo 索引
//        Map<String, Map<String, ?>> candidateLoopById = new HashMap<>();
//        for (Map<String, ?> li : candidateLoopInfos) {
//            Object id = li.get("id");
//            if (id != null) {
//                candidateLoopById.put(id.toString(), li);
//            }
//        }
//
//        // 3) 拷贝 totals 起始 = baseline 汇总
//        Map<String, Double> deltaTotals = new HashMap<>(ctx.baselineSumTotals);
//
//        // 4) 逐 changed loop 增量重算
//        Map<String, String> candidateNormalizedLoop = new HashMap<>();
//        for (String loopId : changedLoopIds) {
//            Map<String, ?> rawLi = candidateLoopById.get(loopId);
//            if (rawLi == null) {
//                return null;
//            }
//
//            Object startAppObj = rawLi.get("startApp");
//            Object endAppObj = rawLi.get("endApp");
//            Object materialsObj = rawLi.get("loopWireway");
//            String startApp = startAppObj == null ? null : startAppObj.toString();
//            String endApp = endAppObj == null ? null : endAppObj.toString();
//            String materials = materialsObj == null ? null : materialsObj.toString();
//            String startPos = startApp == null ? null : candidateAppPos.get(startApp);
//            String endPos = endApp == null ? null : candidateAppPos.get(endApp);
//
//            // 4.1) 构造 findTwoPointInfo 期望的"中文键"loopInfo（轻量：不重建整个 HashMap）
//            Map<String, String> liCn = new HashMap<>(8);
//            liCn.put("回路id", loopId);
//            liCn.put("回路起点用电器", startApp);
//            liCn.put("回路终点用电器", endApp);
//            liCn.put("回路导线选型", materials);
//            // 透传必要字段
//            Object o = rawLi.get("startAppPort");
//            if (o != null) {
//                liCn.put("回路起点用电器接口编号", o.toString());
//            }
//            o = rawLi.get("endAppPort");
//            if (o != null) {
//                liCn.put("回路终点用电器接口编号", o.toString());
//            }
//            o = rawLi.get("loopAttr");
//            if (o != null) {
//                liCn.put("回路属性", o.toString());
//            }
//            o = rawLi.get("loopSys");
//            if (o != null) {
//                liCn.put("所属系统", o.toString());
//            }
//            o = rawLi.get("loopNo");
//            if (o != null) {
//                liCn.put("回路编号", o.toString());
//            }
//            o = rawLi.get("infoName");
//            if (o != null) {
//                liCn.put("回路信号名", o.toString());
//            }
//            o = rawLi.get("caseId");
//            if (o != null) {
//                liCn.put("方案号", o.toString());
//            }
//
//            // 4.2) findTwoPointInfo 缓存（key 必须含 materials，否则不同线径会错误命中）
//            String twoPointKey = loopId + "|" + startApp + "|" + endApp + "|" + startPos + "|" + endPos + "|"
//                    + materials;
//            Map<String, Object> twoPointInfo = ctx.twoPointInfoCache.get(twoPointKey);
//            if (twoPointInfo == null) {
//                // 构造完整 candidateProjectInfo（一次性）
//                Map<String, Object> candidateProjectInfo = buildFastCandidateProjectInfo(ctx, candidateAppPositions);
//                twoPointInfo = ctx.projectCircuitInfoOutput.findTwoPointInfo(
//                        liCn, candidateProjectInfo, ctx.adjacencyMatrixGraph,
//                        ctx.elecFixedLocationLibrary, true, null, null, ctx.elecBusinessPrice);
//                if (twoPointInfo == null) {
//                    return null;
//                }
//                twoPointInfo.put("回路id", loopId);
//                // 缓存：存只读快照（防止后续 coiling 突变影响）
//                Map<String, Object> snapshot = new HashMap<>(twoPointInfo);
//                Map<String, Object> prev = ctx.twoPointInfoCache.putIfAbsent(twoPointKey, snapshot);
//                // 使用缓存或新建工作副本（避免下游修改污染缓存）
//                twoPointInfo = new HashMap<>(prev != null ? prev : snapshot);
//                twoPointInfo.put("回路id", loopId);
//            } else {
//                // 工作副本：避免下游 coiling/circuitProjectInfo 修改污染缓存
//                twoPointInfo = new HashMap<>(twoPointInfo);
//            }
//
//            // 4.3) 回路绕线长度（缓存）
//            String coilingKey = loopId + "|" + startPos + "|" + endPos;
//            Double cachedCoil = ctx.coilingLengthCache.get(coilingKey);
//            double coilLen;
//            if (cachedCoil != null) {
//                coilLen = cachedCoil;
//                twoPointInfo.put("回路绕线长度", coilLen);
//            } else {
//                // 临时构建 changed loop 子图计算 coiling（只针对本回路）
//                Map<String, Object> oneLoopMap = new HashMap<>();
//                oneLoopMap.put(loopId, twoPointInfo);
//                Map<String, Object> candidateProjectInfo = buildFastCandidateProjectInfo(ctx, candidateAppPositions);
//                ctx.projectCircuitInfoOutput.circuitCoilingLength(
//                        oneLoopMap, ctx.edges, ctx.adjacencyMatrixGraphConnector, candidateProjectInfo);
//                Object coilObj = twoPointInfo.get("回路绕线长度");
//                coilLen = coilObj == null ? 0.0 : numFromObj(coilObj);
//                ctx.coilingLengthCache.putIfAbsent(coilingKey, coilLen);
//            }
//
//            // 4.4) 增量 totals：先减去 baseline 的旧回路值，再加上新回路值
//            double oldCost = ctx.baselineLoopTotalCost.getOrDefault(loopId, 0.0);
//            double oldWeight = ctx.baselineLoopWeight.getOrDefault(loopId, 0.0);
//            double oldLength = ctx.baselineLoopLength.getOrDefault(loopId, 0.0);
//            double oldCoil = ctx.baselineLoopCoilingLength.getOrDefault(loopId, 0.0);
//            double oldCoilCount = ctx.baselineLoopCoilingCount.getOrDefault(loopId, 0.0);
//            double oldBreakC = ctx.baselineLoopBreakCost.getOrDefault(loopId, 0.0);
//            double oldWetC = ctx.baselineLoopWetCost.getOrDefault(loopId, 0.0);
//            double oldTermC = ctx.baselineLoopTerminalCost.getOrDefault(loopId, 0.0);
//            double oldWireC = ctx.baselineLoopWireCost.getOrDefault(loopId, 0.0);
//            double oldTermTotal = ctx.baselineLoopTerminalTotalCost.getOrDefault(loopId, 0.0);
//            double oldShellC = ctx.baselineLoopShellCost.getOrDefault(loopId, 0.0);
//            double oldWaterC = ctx.baselineLoopWaterproofCost.getOrDefault(loopId, 0.0);
//
//            double newCost = numFromObj(twoPointInfo.get("回路总成本"));
//            double newWeight = numFromObj(twoPointInfo.get("回路重量"));
//            double newLength = numFromObj(twoPointInfo.get("回路长度"));
//            double newCoil = numFromObj(twoPointInfo.get("回路绕线长度"));
//            double newCoilCount = newCoil > 0 ? 1 : 0;
//            double newBreakC = numFromObj(twoPointInfo.get("回路打断成本"));
//            double newWetC = numFromObj(twoPointInfo.get("回路湿区成本加成"));
//            double newTermC = numFromObj(twoPointInfo.get("回路两端端子成本"));
//            double newWireC = numFromObj(twoPointInfo.get("回路导线成本"));
//            double newTermTotal = numFromObj(twoPointInfo.get("端子成本"));
//            double newShellC = numFromObj(twoPointInfo.get("连接器塑壳成本"));
//            double newWaterC = numFromObj(twoPointInfo.get("防水塞成本"));
//
//            deltaTotals.put("总成本", deltaTotals.getOrDefault("总成本", 0.0) - oldCost + newCost);
//            deltaTotals.put("回路总重量", deltaTotals.getOrDefault("回路总重量", 0.0) - oldWeight + newWeight);
//            deltaTotals.put("回路总长度", deltaTotals.getOrDefault("回路总长度", 0.0) - oldLength + newLength);
//            deltaTotals.put("回路绕线总长度",
//                    deltaTotals.getOrDefault("回路绕线总长度", 0.0) - oldCoil + newCoil);
//            deltaTotals.put("回路绕线数量",
//                    deltaTotals.getOrDefault("回路绕线数量", 0.0) - oldCoilCount + newCoilCount);
//            deltaTotals.put("回路打断总成本",
//                    deltaTotals.getOrDefault("回路打断总成本", 0.0) - oldBreakC + newBreakC);
//            deltaTotals.put("回路湿区成本总加成",
//                    deltaTotals.getOrDefault("回路湿区成本总加成", 0.0) - oldWetC + newWetC);
//            deltaTotals.put("回路两端端子总成本",
//                    deltaTotals.getOrDefault("回路两端端子总成本", 0.0) - oldTermC + newTermC);
//            deltaTotals.put("回路导线总成本",
//                    deltaTotals.getOrDefault("回路导线总成本", 0.0) - oldWireC + newWireC);
//            deltaTotals.put("端子总成本",
//                    deltaTotals.getOrDefault("端子总成本", 0.0) - oldTermTotal + newTermTotal);
//            deltaTotals.put("连接器塑壳总成本",
//                    deltaTotals.getOrDefault("连接器塑壳总成本", 0.0) - oldShellC + newShellC);
//            deltaTotals.put("防水塞总成本",
//                    deltaTotals.getOrDefault("防水塞总成本", 0.0) - oldWaterC + newWaterC);
//        }
//        return deltaTotals;
//    }
//
//    private static double numFromObj(Object v) {
//        if (v == null)
//            return 0.0;
//        if (v instanceof Number)
//            return ((Number) v).doubleValue();
//        try {
//            return Double.parseDouble(v.toString());
//        } catch (NumberFormatException e) {
//            return 0.0;
//        }
//    }
//
//    /**
//     * 构造 candidateProjectInfo（只读视图，不修改 ctx）
//     * 用 ctx.staticProjectInfo + 当前候选的 appPositions/loopInfos。
//     */
//    @SuppressWarnings({ "unchecked", "rawtypes" })
//    private Map<String, Object> buildFastCandidateProjectInfo(ProjectContext ctx,
//            List<? extends Map<String, ?>> candidateAppPositions) {
//        Map<String, Object> pi = new HashMap<>(ctx.staticProjectInfo);
//        // 归一化 appPositions：直接复制候选并按需补键（仅对实际需要的字段）
//        List<Map<String, String>> apps = new ArrayList<>(candidateAppPositions.size());
//        for (Map<String, ?> src : candidateAppPositions) {
//            Map<String, String> dst = new HashMap<>();
//            Object n = src.get("appName");
//            if (n != null) {
//                dst.put("用电器名称", n.toString());
//            }
//            Object id = src.get("id");
//            if (id != null) {
//                dst.put("用电器id", id.toString());
//            }
//            Object p = src.get("unregularPointName");
//            if (p != null) {
//                dst.put("用户更改后用电器位置名称", p.toString());
//            }
//            Object pid = src.get("unregularPointId");
//            if (pid != null) {
//                dst.put("用户更改后用电器位置id", pid.toString());
//            }
//            Object r = src.get("regularPointName");
//            if (r != null) {
//                dst.put("用电器固化位置点名称", r.toString());
//            }
//            Object rid = src.get("regularPointId");
//            if (rid != null) {
//                dst.put("用电器固化位置点id", rid.toString());
//            }
//            Object reg = src.get("positionRegular");
//            if (reg != null) {
//                dst.put("用电器位置是否固化", reg.toString());
//            }
//            apps.add(dst);
//        }
//        pi.put("用电器信息", (List) apps);
//        return pi;
//    }
//
//    /**
//     * 内部核心：执行原有 powerOptimize 主体（不包含外层 JSON 解析/序列化）
//     */
//    private Map<String, Object> evaluateCore(Map<String, Object> projectInfo, ProjectContext ctx) {
//        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
//        List<Map<String, String>> edges = (List<Map<String, String>>) projectInfo.get("所有分支信息");
//
//        // 对所有回路进行成本计算,添加到 loopdetails
//        Map<String, Object> loopdetails = new HashMap<>();
//        for (Map<String, Object> loopInfo : loopInfos) {
//            Map<String, String> loopInfoStrMap = new HashMap<>();
//            for (Map.Entry<String, Object> e : loopInfo.entrySet()) {
//                loopInfoStrMap.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
//            }
//            Map<String, Object> twoPointInfo = ctx.projectCircuitInfoOutput.findTwoPointInfo(
//                    loopInfoStrMap, projectInfo, ctx.adjacencyMatrixGraph,
//                    ctx.elecFixedLocationLibrary, true, null, null, ctx.elecBusinessPrice);
//            if (twoPointInfo == null) {
//                return null;
//            }
//            loopdetails.put(twoPointInfo.get("回路id").toString(), twoPointInfo);
//        }
//
//        // 分类索引
//        Map<String, List<String>> systemMap = new HashMap<>();
//        Map<String, List<String>> elecMap = new HashMap<>();
//        Map<String, Map<String, Object>> appMap = new HashMap<>();
//        for (Map<String, Object> loopInfo : loopInfos) {
//            if (elecMap.containsKey(loopInfo.get("回路起点用电器").toString())) {
//                elecMap.get(loopInfo.get("回路起点用电器").toString()).add(loopInfo.get("回路id").toString());
//            } else {
//                List<String> list = new ArrayList<>();
//                list.add(loopInfo.get("回路id").toString());
//                elecMap.put(loopInfo.get("回路起点用电器").toString(), list);
//            }
//            if (elecMap.containsKey(loopInfo.get("回路终点用电器").toString())) {
//                elecMap.get(loopInfo.get("回路终点用电器").toString()).add(loopInfo.get("回路id").toString());
//            } else {
//                List<String> list = new ArrayList<>();
//                list.add(loopInfo.get("回路id").toString());
//                elecMap.put(loopInfo.get("回路终点用电器").toString(), list);
//            }
//            if (loopInfo.get("所属系统") != null && loopInfo.get("所属系统").toString().length() > 0) {
//                if (systemMap.containsKey(loopInfo.get("所属系统"))) {
//                    systemMap.get(loopInfo.get("所属系统")).add(loopInfo.get("回路id").toString());
//                } else {
//                    List<String> list = new ArrayList<>();
//                    list.add(loopInfo.get("回路id").toString());
//                    systemMap.put(loopInfo.get("所属系统").toString(), list);
//                }
//            }
//            // appMap
//            String startApp = !loopInfo.get("回路起点用电器").toString().startsWith("[")
//                    ? loopInfo.get("回路起点用电器").toString()
//                    : "";
//            String endApp = !loopInfo.get("回路终点用电器").toString().startsWith("[")
//                    ? loopInfo.get("回路终点用电器").toString()
//                    : "";
//            for (String app : Arrays.asList(startApp, endApp)) {
//                if (app.isEmpty())
//                    continue;
//                if (!appMap.containsKey(app)) {
//                    Map<String, Object> electricalMap = new HashMap<>();
//                    Set<String> electricalId = new HashSet<>();
//                    Map<String, Set<String>> interfaceMap = new HashMap<>();
//                    electricalId.add(loopInfo.get("回路id").toString());
//                    String portKey = app.equals(startApp)
//                            ? Optional.ofNullable(loopInfo.get("回路起点用电器接口编号")).map(Object::toString).orElse("")
//                            : Optional.ofNullable(loopInfo.get("回路终点用电器接口编号")).map(Object::toString).orElse("");
//                    if (!portKey.isEmpty()) {
//                        Set<String> s = new HashSet<>();
//                        s.add(loopInfo.get("回路id").toString());
//                        interfaceMap.put(portKey, s);
//                    }
//                    electricalMap.put("electricalList", electricalId);
//                    electricalMap.put("interfaceMap", interfaceMap);
//                    appMap.put(app, electricalMap);
//                } else {
//                    Map<String, Object> electricalMap = appMap.get(app);
//                    Set<String> electricalId = (Set<String>) electricalMap.get("electricalList");
//                    electricalId.add(loopInfo.get("回路id").toString());
//                    Map<String, Set<String>> interfaceMap = (Map<String, Set<String>>) electricalMap
//                            .get("interfaceMap");
//                    String portKey = app.equals(startApp)
//                            ? Optional.ofNullable(loopInfo.get("回路起点用电器接口编号")).map(Object::toString).orElse("")
//                            : Optional.ofNullable(loopInfo.get("回路终点用电器接口编号")).map(Object::toString).orElse("");
//                    if (!portKey.isEmpty()) {
//                        if (interfaceMap.containsKey(portKey)) {
//                            interfaceMap.get(portKey).add(loopInfo.get("回路id").toString());
//                        } else {
//                            Set<String> s = new HashSet<>();
//                            s.add(loopInfo.get("回路id").toString());
//                            interfaceMap.put(portKey, s);
//                        }
//                    }
//                }
//            }
//        }
//
//        // wirePriceMap
//        Map<String, Double> wirePriceMap = new HashMap<>();
//        for (Map.Entry<String, Map<String, String>> entry : ctx.elecFixedLocationLibrary.entrySet()) {
//            String unitPrice = entry.getValue().get("导线单位商务价（元/米）");
//            if (unitPrice != null) {
//                wirePriceMap.put(entry.getKey(), Double.parseDouble(unitPrice));
//            }
//        }
//
//        // circuitInfo（List<Map>，与原 powerOptimize 输出一致，供下游 HarnessBranchTopoOptimize
//        // 等消费者使用）
//        List<Map<String, Object>> circuitInfo = new ArrayList<>();
//        for (Map<String, Object> loopInfo : loopInfos) {
//            Map<String, Object> objectMap = (Map<String, Object>) loopdetails.get(loopInfo.get("回路id").toString());
//            Double price = null;
//            Object wire = objectMap.get("导线选型");
//            if (wire != null) {
//                price = wirePriceMap.get(wire.toString());
//            }
//            objectMap.put("导线单价", price);
//            circuitInfo.add(objectMap);
//        }
//        // 回路绕线长度
//        ctx.projectCircuitInfoOutput.circuitCoilingLength(loopdetails, edges, ctx.adjacencyMatrixGraphConnector,
//                projectInfo);
//        // projectCircuitInfo
//        Map<String, Object> projectCircuitInfo = ctx.projectCircuitInfoOutput.circuitProjectInfo(loopdetails);
//
//        // 分类汇总
//        Map<String, Object> systemCircuitInfo = new HashMap<>();
//        IntergateCircuitInfo intergate = new IntergateCircuitInfo();
//        for (Map.Entry<String, List<String>> e : systemMap.entrySet()) {
//            Map<String, Object> objectMap = intergate.intergateCircuitInfo(e.getValue(), loopdetails);
//            Map<String, Object> cloneMap = (Map<String, Object>) objectMap.get("circuitInfoIntergation");
//            cloneMap.remove("总理论直径");
//            cloneMap.remove("分支直径RGB坐标");
//            objectMap.put("circuitInfoIntergation", cloneMap);
//            systemCircuitInfo.put(e.getKey(), objectMap);
//        }
//        Map<String, Object> elecRelatedCircuitInfo = new HashMap<>();
//        for (String name : elecMap.keySet()) {
//            elecRelatedCircuitInfo.put(name, intergate.intergateCircuitInfo(elecMap.get(name), loopdetails));
//        }
//        Map<String, Object> elecInterfaceRelatedCircuitInfo = new HashMap<>();
//        for (Map.Entry<String, Map<String, Object>> e : appMap.entrySet()) {
//            Map<String, Set<String>> interfaceDetailList = (Map<String, Set<String>>) e.getValue().get("interfaceMap");
//            if (interfaceDetailList != null && !interfaceDetailList.isEmpty()) {
//                Map<String, Object> objectMap2 = new HashMap<>();
//                for (Map.Entry<String, Set<String>> ie : interfaceDetailList.entrySet()) {
//                    objectMap2.put(ie.getKey(),
//                            intergate.intergateCircuitInfo(new ArrayList<>(ie.getValue()), loopdetails));
//                }
//                elecInterfaceRelatedCircuitInfo.put(e.getKey(), objectMap2);
//            }
//        }
//
//        // 分支
//        Map<String, Object> bundeleRelatedCircuitInfo = new HashMap<>();
//        for (Map<String, String> edge : edges) {
//            String id = edge.get("分支id编号");
//            bundeleRelatedCircuitInfo.put(id,
//                    ctx.projectCircuitInfoOutput.circuitInfoByEdge(id, loopdetails, edge.get("分支名称")));
//        }
//        for (Map.Entry<String, Object> entry : bundeleRelatedCircuitInfo.entrySet()) {
//            String bid = entry.getKey();
//            Map<String, Object> objectMap = (Map<String, Object>) entry.getValue();
//            List<String> list = (List<String>) objectMap.get("circuitList");
//            String wet = "";
//            for (Map<String, String> edge : edges) {
//                if (bid.equals(edge.get("分支id编号"))) {
//                    String startPointName = edge.get("分支起点名称");
//                    String endPointName = edge.get("分支终点名称");
//                    String startPoint = ctx.projectCircuitInfoOutput.getWaterParam(startPointName,
//                            (List<Map<String, String>>) projectInfo.get("所有端点信息"));
//                    String endPoint = ctx.projectCircuitInfoOutput.getWaterParam(endPointName,
//                            (List<Map<String, String>>) projectInfo.get("所有端点信息"));
//                    if ("D".equals(startPoint))
//                        wet = "D";
//                    else if ("D".equals(endPoint))
//                        wet = "D";
//                    else
//                        wet = "W";
//                }
//            }
//            Map<String, String> colorMap = ctx.projectCircuitInfoOutput.getColorByEdges(list, circuitInfo,
//                    wet.equals("W"), ctx.elecFixedLocationLibrary);
//            Map<String, Object> objectMap1 = (Map<String, Object>) objectMap.get("circuitInfoIntergation");
//            objectMap1.put("分支打断代价RGB坐标", colorMap.get("color"));
//            objectMap1.put("分支打断代价", colorMap.get("cost"));
//        }
//
//        Map<String, Object> resultMap = new HashMap<>();
//        resultMap.put("systemCircuitInfo", systemCircuitInfo);
//        resultMap.put("elecRelatedCircuitInfo", elecRelatedCircuitInfo);
//        resultMap.put("elecInterfaceRelatedCircuitInfo", elecInterfaceRelatedCircuitInfo);
//        resultMap.put("bundeleRelatedCircuitInfo", bundeleRelatedCircuitInfo);
//        resultMap.put("circuitInfo", circuitInfo);
//        resultMap.put("projectCircuitInfo", projectCircuitInfo);
//        // 内部缓存：每回路的成本明细（供 GA 增量评估复用，不对外暴露 JSON）
//        resultMap.put("__loopdetails__", loopdetails);
//        return resultMap;
//    }
//
//    // ==================== 旧 API 兼容（保留 String 入参） ====================
//
//    public String powerOptimize(String fileStringFormat) throws Exception {
//        JsonToMap jsonToMap = new JsonToMap();
//        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
//        Map<String, Object> resultMap = powerOptimizeFromMap(mapFile);
//        // 内部缓存字段不暴露给最终输出
//        resultMap.remove("__loopdetails__");
//        ObjectMapper objectMapper = new ObjectMapper();
//        return objectMapper.writeValueAsString(resultMap);
//    }
//
//    /**
//     * Map 入参的整车信息计算（跳过输入 JSON 解析）
//     * 返回完整结果 Map，调用方按需使用 projectCircuitInfo / circuitInfo 等
//     */
//    public Map<String, Object> powerOptimizeFromMap(Map<String, Object> mapFile) throws Exception {
//        ProjectContext ctx = prepareContext(mapFile);
//        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
//        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
//        List<Map<String, String>> appPositions = (List<Map<String, String>>) projectInfo.get("用电器信息");
//        List<Map<String, String>> loopInfos = (List<Map<String, String>>) projectInfo.get("回路用电器信息");
//        return evaluateFullFromContext(ctx, appPositions, loopInfos);
//    }
//}
