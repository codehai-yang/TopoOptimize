package HarnessPackOpti.Algorithm;

import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IntergateCircuitInfo {

    // 能量流信号名关键字列表（大小写不敏感）
    private static final Set<String> ENERGY_FLOW_KEYWORDS = new HashSet<>(Arrays.asList(
            "KL30", "EFS", "ESW", "HSD", "DRV"));

    // 用电器类型常量
    private static final String TYPE_APPLIANCE = "用电器";
    private static final String TYPE_PDU = "配电单元";
    private static final String TYPE_GROUND = "接地点";
    private static final String TYPE_ECU = "控制器";
    private static final String TYPE_BATTERY = "储电单元";
    private static final String TYPE_GENERATOR = "发电单元";

    /**
     * 能量流路径计算结果
     */
    public static class EnergyFlowResult {
        public String circuitId;             // 起始回路id
        public List<String> appliancePath;    // 能量流用电器路径（按顺序）
        public List<String> circuitPath;      // 能量流回路id路径（按顺序）
        public double energyFlowLength;       // 能量流路径总长度
        public double noDetourLength;         // 不绕路最短路径长度
        public double detourLength;           // 绕路长度
        public List<String> energyFlowBranchPoints;   // 能量流途径分支id
        public List<String> noDetourBranchPoints;     // 不绕路途径分支id
        public boolean skipped;               // 是否跳过计算
        public String skipReason;             // 跳过原因
        public boolean hasEnergyFlow;         // 是否有有效能量流路径
        public int priority;                  // 该结果对应朝向的优先级（用于多朝向择优）

        public EnergyFlowResult() {
            this.circuitPath = new ArrayList<>();
            this.appliancePath = new ArrayList<>();
            this.energyFlowBranchPoints = new ArrayList<>();
            this.noDetourBranchPoints = new ArrayList<>();
            this.energyFlowLength = 0.0;
            this.noDetourLength = 0.0;
            this.detourLength = 0.0;
            this.skipped = false;
            this.hasEnergyFlow = false;
            this.priority = 0;
        }
    }

    /**
     * 用电器图边信息：表示一条回路连接了两个用电器
     */
    public static class AppEdge {
        String fromApp;
        String toApp;
        String fromType;
        String toType;
        String circuitId;
        String signalName;
        String startPosName;   // 起点位置名称
        String endPosName;     // 终点位置名称

        AppEdge(String fromApp, String toApp, String fromType, String toType,
                String circuitId, String signalName, String startPosName, String endPosName) {
            this.fromApp = fromApp;
            this.toApp = toApp;
            this.fromType = fromType;
            this.toType = toType;
            this.circuitId = circuitId;
            this.signalName = signalName;
            this.startPosName = startPosName;
            this.endPosName = endPosName;
        }
    }

    /**
     * @Description 整合回路信息汇总计算
     * @input pathId 回路id
     * @inputExample [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input pointList 整车回路整合后信息
     * @Return 整合后的回路信息
     */
    public Map<String, Object> intergateCircuitInfo(List<String> pathId, Map<String, Object> pointList,GenerateTopoMatrix adjacencyMatrixGraph,List<Map<String, String>> edges) {
        Map<String, Object> resultMap = new HashMap<>();
//        总成本
        Map<String, Object> totalCost = new HashMap<>();
        totalCost.put("总成本", 0.0);
        totalCost.put("回路湿区成本总加成", 0.0);
        totalCost.put("回路打断成本总值(元)", 0.0);
        totalCost.put("回路两端端子总成本", 0.0);
        totalCost.put("回路导线总成本", 0.0);
        totalCost.put("回路总重量", 0.0);
        totalCost.put("回路总长度", 0.0);
        totalCost.put("端子总成本", 0.0);
        totalCost.put("连接器塑壳总成本", 0.0);
        totalCost.put("防水塞总成本", 0.0);
        totalCost.put("回路绕线长度总值(米)", 0.0);
        totalCost.put("回路绕线长度均值(米/根)", 0.0);
        totalCost.put("回路打断成本均值(元/根)", 0.0);
        double lenght = 0.0;
        int coiling = 0;
        int circuitBreakNum = 0;
        DecimalFormat df = new DecimalFormat("0.00");
        int count = 0;
        for (String s : pathId) {
            Map<String, Object> objectMap = (Map<String, Object>) pointList.get(s);
            //排除分支信息为空的
            if(objectMap == null){
                continue;
            }
            totalCost.put("总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("总成本").toString()) + Double.parseDouble(objectMap.get("回路总成本").toString()))));
            totalCost.put("回路湿区成本总加成",Double.parseDouble( df.format(Double.parseDouble( totalCost.get("回路湿区成本总加成").toString()) + Double.parseDouble(objectMap.get("回路湿区成本加成").toString()))));
            totalCost.put("回路打断成本总值(元)",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路打断成本总值(元)").toString()) + Double.parseDouble(objectMap.get("回路打断成本总值(元)").toString()))));
            totalCost.put("回路两端端子总成本",Double.parseDouble(df.format(Double.parseDouble( totalCost.get("回路两端端子总成本").toString()) + Double.parseDouble(objectMap.get("回路两端端子成本").toString()))));
            totalCost.put("回路导线总成本",Double.parseDouble( df.format(Double.parseDouble( totalCost.get("回路导线总成本").toString()) + Double.parseDouble(objectMap.get("回路导线成本").toString()))));
            totalCost.put("回路总重量",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) + Double.parseDouble(objectMap.get("回路重量").toString()))));
            totalCost.put("回路总长度",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) + Double.parseDouble(objectMap.get("回路长度").toString()))));
            totalCost.put("端子总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("端子总成本").toString()) + Double.parseDouble(objectMap.get("端子成本").toString()))));
            totalCost.put("连接器塑壳总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("连接器塑壳总成本").toString()) + Double.parseDouble(objectMap.get("连接器塑壳成本").toString()))));
            totalCost.put("防水塞总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("防水塞总成本").toString()) + Double.parseDouble(objectMap.get("防水塞成本").toString()))));
            totalCost.put("回路绕线长度总值(米)",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路绕线长度总值(米)").toString()) + Double.parseDouble(objectMap.get("回路绕线长度总值(米)").toString()))));
            lenght += Double.parseDouble( objectMap.get("回路理论直径").toString()) * Double.parseDouble( objectMap.get("回路理论直径").toString());
            if( Double.parseDouble(objectMap.get("回路绕线长度总值(米)").toString()) > 0 ){
                coiling++;
            }
            circuitBreakNum += Double.parseDouble(objectMap.get("回路打断总次数(根)").toString());
            //回路打断后计算
            int i = Integer.parseInt(objectMap.get("回路打断总次数(根)").toString());
            i += 1;
            count += i;
        }
        totalCost.put("回路打断总次数(根)", circuitBreakNum);
        if(coiling > 0){
            totalCost.put("回路绕线长度均值(米/根)",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路绕线长度总值(米)").toString()) / coiling)));
        }
        //回路打断前与打断后统计
        totalCost.put("回器绕线总数量(根)",coiling);
        if(pathId.size() > 0){
            double coilingPercent = (double)coiling / pathId.size() * 100;
            double breakNumb = Double.parseDouble(totalCost.get("回路打断总次数(根)").toString()) / pathId.size() * 100;
            totalCost.put("回路打断成本均值(元/根)",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路打断成本总值(元)").toString()) / pathId.size())));
            totalCost.put("回路绕线数量占比(百分比)",df.format(coilingPercent) + "%");
            totalCost.put("回路打断数量占比(百分比)",df.format(breakNumb) + "%");
        }else {
            totalCost.put("回路绕线数量占比(百分比)","0.00%");
            totalCost.put("回路打断数量占比(百分比)","0.00%");
        }
        totalCost.put("回路重量均值(克/根)",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) / pathId.size())));
        totalCost.put("回路数量-B类(根)", pathId.size());
        totalCost.put("回路数量-A类(根)", count);
        totalCost.put("回路成本均值(元/根)",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("总成本").toString()) / pathId.size())));
        //回路均值打断前
        double avgLength = 0.00;
        if(pathId.size() > 0){
            avgLength = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / pathId.size()));
        }
        totalCost.put("回路长度均值(米/根)",avgLength);
        //回路均值打断后
        double avgLength2 = 0.00;
        if(count > 0){
            avgLength2 = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / count));
        }
        // 能量流绕线字段（默认null，调用方需主动调用calculateEnergyFlowDetour填充）
        IntergateCircuitInfo ici = new IntergateCircuitInfo();
        Map<String, Object> efResult = ici.calculateEnergyFlowDetour(
                pathId, pointList,
                adjacencyMatrixGraph.getAllPoint(),
                adjacencyMatrixGraph.getAdj(),
                edges);
        totalCost.put("能量流绕路总数量(根)", efResult.get("能量流绕路总数量(根)"));
        totalCost.put("能量流绕路数量占比(百分比)", efResult.get("能量流绕路数量占比(百分比)"));
        totalCost.put("能量流绕路长度总值(米)", efResult.get("能量流绕路长度总值(米)"));
        totalCost.put("能量流绕路长度均值(米/根)", efResult.get("能量流绕路长度均值(米/根)"));
        totalCost.put("回路长度均值(打断后)",avgLength2);
        totalCost.put("总理论直径",Double.parseDouble( df.format(Math.sqrt(lenght)*1.3)));
        totalCost.put("分支直径RGB坐标",getlengthColor((Double) totalCost.get("总理论直径")));
        resultMap.put("circuitInfoIntergation", totalCost);
        resultMap.put("circuitList", pathId);
        return resultMap;
    }

    /**
     * 计算能量流绕路信息（聚合输出6个字段 + 每条回路的明细结果）
     *
     * @param circuitIds  需要统计的回路id列表
     * @param loopdetails 所有回路详情（key=回路id，value=回路信息Map）
     *                    value中需包含：起点用电器名称、终点用电器名称、
     *                    起点用电器类型、终点用电器类型、起点位置名称、终点位置名称、
     *                    回路信号名、回路途径分支点
     * @param allPoint    全连通图所有分支点名称列表
     * @param adj         全连通邻接表
     * @param edges       所有分支信息（含分支起点名称、分支终点名称、用户确认的分支长度、参考长度、分支id编号）
     * @return Map包含聚合的6个能量流字段 + "perCircuitResults"（List&lt;EnergyFlowResult&gt;）
     */
    public Map<String, Object> calculateEnergyFlowDetour(
            List<String> circuitIds,
            Map<String, Object> loopdetails,
            List<String> allPoint,
            List<List<Integer>> adj,
            List<Map<String, String>> edges) {

        Map<String, Object> result = new HashMap<>();
        List<EnergyFlowResult> perCircuitResults = new ArrayList<>();

        if (circuitIds == null || circuitIds.isEmpty()) {
            result.put("能量流绕路总数量(根)", 0);
            result.put("能量流绕路数量占比(百分比)", "0.00%");
            result.put("能量流绕路长度总值(米)", 0.0);
            result.put("能量流绕路长度均值(米/根)", 0.0);
            result.put("能量流途径分支id", null);
            result.put("能量流不绕路途径分支id", null);
            result.put("perCircuitResults", perCircuitResults);
            return result;
        }

        // 1. 构建用电器图（全局，从所有loopdetails中提取）
        Map<String, List<AppEdge>> appGraph = new HashMap<>();
        Map<String, String> appTypeMap = new HashMap<>();
        Map<String, String> appPosMap = new HashMap<>();
        buildApplianceGraph(loopdetails, appGraph, appTypeMap, appPosMap);

        // 2. 对每条回路进行能量流计算
        int detourCount = 0;
        double detourTotal = 0.0;
        int analyzedCount = 0;
        List<String> allEnergyFlowBranchPoints = new ArrayList<>();
        List<String> allNoDetourBranchPoints = new ArrayList<>();

        DecimalFormat df = new DecimalFormat("0.00");

        for (String circuitId : circuitIds) {
            Map<String, Object> loopInfo = (Map<String, Object>) loopdetails.get(circuitId);
            if (loopInfo == null) {
                continue;
            }
            EnergyFlowResult efResult = computeSingleCircuitEnergyFlow(
                    circuitId, loopInfo, appGraph, appTypeMap, appPosMap, allPoint, adj, edges);
            perCircuitResults.add(efResult);

            if (efResult.skipped) {
                continue;
            }
            analyzedCount++;

            if (efResult.hasEnergyFlow && efResult.detourLength > 0) {
                detourCount++;
                detourTotal += efResult.detourLength;
                if (!efResult.energyFlowBranchPoints.isEmpty()) {
                    allEnergyFlowBranchPoints.add(String.join(",", efResult.energyFlowBranchPoints));
                }
                if (!efResult.noDetourBranchPoints.isEmpty()) {
                    allNoDetourBranchPoints.add(String.join(",", efResult.noDetourBranchPoints));
                }
            }
        }

        // 3. 汇总6个字段
        result.put("能量流绕路总数量(根)", detourCount);
        if (analyzedCount > 0) {
            double percent = (double) detourCount / analyzedCount * 100;
            result.put("能量流绕路数量占比(百分比)", df.format(percent) + "%");
        } else {
            result.put("能量流绕路数量占比(百分比)", "0.00%");
        }
        double detourTotalMeters = detourTotal / 1000.0;
        result.put("能量流绕路长度总值(米)", Double.parseDouble(df.format(detourTotalMeters)));
        if (detourCount > 0) {
            result.put("能量流绕路长度均值(米/根)", Double.parseDouble(df.format(detourTotalMeters / detourCount)));
        } else {
            result.put("能量流绕路长度均值(米/根)", 0.0);
        }
        // result.put("能量流途径分支id",
        //         allEnergyFlowBranchPoints.isEmpty() ? null : String.join("; ", allEnergyFlowBranchPoints));
        // result.put("能量流不绕路途径分支id",
        //         allNoDetourBranchPoints.isEmpty() ? null : String.join("; ", allNoDetourBranchPoints));
        result.put("perCircuitResults", perCircuitResults);

        return result;
    }

    /**
     * 计算单条回路的能量流信息。
     *
     * 关键改进：能量流路径必须穿过“本回路自身的两端”（起点用电器 ↔ 终点用电器）。
     * 因此以两端中的“叶子端”（合点 > 用电器 > 控制器）作为终端，强制
     * 终端 → 另一端 这一段一定出现在路径里，再从另一端向上回溯到发电/储电单元。
     * 这样无论最短路径如何，合点/焊点位置（如 “左后轮包顶点”）必定出现在
     * 能量流途径分支id中。
     */
    public EnergyFlowResult computeSingleCircuitEnergyFlow(
            String circuitId,
            Map<String, Object> loopInfo,
            Map<String, List<AppEdge>> appGraph,
            Map<String, String> appTypeMap,
            Map<String, String> appPosMap,
            List<String> allPoint,
            List<List<Integer>> adj,
            List<Map<String, String>> edges) {

        EnergyFlowResult efResult = new EnergyFlowResult();
        efResult.circuitId = circuitId;

        String startApp = safeGetString(loopInfo, "起点用电器名称");
        String endApp = safeGetString(loopInfo, "终点用电器名称");
        String startType = safeGetString(loopInfo, "起点用电器类型");
        String endType = safeGetString(loopInfo, "终点用电器类型");
        String startPos = safeGetString(loopInfo, "起点位置名称");
        String endPos = safeGetString(loopInfo, "终点位置名称");
        String signalName = safeGetString(loopInfo, "回路信号名");

        if (isEmpty(startApp) && isEmpty(endApp)) {
            efResult.skipped = true;
            efResult.skipReason = "回路缺少用电器名称";
            return efResult;
        }

        if (isEmpty(startType) && startApp != null) startType = appTypeMap.get(startApp);
        if (isEmpty(endType) && endApp != null) endType = appTypeMap.get(endApp);
        if (isEmpty(startPos) && startApp != null) startPos = appPosMap.get(startApp);
        if (isEmpty(endPos) && endApp != null) endPos = appPosMap.get(endApp);

        if (shouldSkipCircuit(startType, endType)) {
            efResult.skipped = true;
            efResult.skipReason = "起点或终点为(发电单元/储电单元)↔配电单元，无需计算能量流";
            return efResult;
        }

        // 仅计算规则 2~7 提到的回路组合，其余组合直接跳过（不计算能量流）
        if (!isRuleCircuit(startType, startApp, endType, endApp)) {
            efResult.skipped = true;
            efResult.skipReason = "非规则回路（仅计算 配电↔控制器/用电器、控制器↔用电器/控制器、用电器/控制器↔合点），跳过";
            return efResult;
        }

        // 两个端点
        String[][] ends = new String[][]{
                {startApp, startType, startPos},
                {endApp, endType, endPos}
        };

        // 生成朝向：以“叶子端(消费端)”为终端，另一端为上游。
        // 叶子端只能是 用电器/控制器（合点作为中间节点，不参与 leaf 选取）。
        List<int[]> orientations = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int pri = terminalPriority(ends[i][1]);
            if (pri >= 0) {
                orientations.add(new int[]{i, 1 - i, pri});
            }
        }
        if (orientations.isEmpty()) {
            // 两端都不是叶子类型（如 配电↔配电），退化为两端都试
            orientations.add(new int[]{0, 1, 0});
            orientations.add(new int[]{1, 0, 0});
        }

        // 读取本回路自身经过的分支点(位置序列)，用于把能量流路径展开为真实走线
        List<String> ownBranchPoints = new ArrayList<>();
        Object bpObj = loopInfo.get("回路途径分支点");
        if (bpObj instanceof List) {
            for (Object o : (List<?>) bpObj) {
                if (o != null) ownBranchPoints.add(String.valueOf(o));
            }
        }

        // 每个朝向都强制经过本回路两端，收集有效结果
        List<EnergyFlowResult> candidates = new ArrayList<>();
        for (int[] o : orientations) {
            String[] term = ends[o[0]];
            String[] up = ends[o[1]];
            EnergyFlowResult r = computeEnergyFlowWithEdge(
                    circuitId,
                    term[0], term[1], term[2],
                    up[0], up[1], up[2],
                    signalName, ownBranchPoints,
                    appGraph, appTypeMap, appPosMap, allPoint, adj, edges);
            if (r != null && r.hasEnergyFlow) {
                r.priority = o[2];
                candidates.add(r);
            }
        }

        if (candidates.isEmpty()) {
            efResult.hasEnergyFlow = false;
            return efResult;
        }

        // 选优先级最高；同优先级取能量流路径最短
        EnergyFlowResult best = null;
        int bestPri = -1;
        double bestLen = Double.MAX_VALUE;
        for (EnergyFlowResult r : candidates) {
            if (r.priority > bestPri || (r.priority == bestPri && r.energyFlowLength < bestLen)) {
                best = r;
                bestPri = r.priority;
                bestLen = r.energyFlowLength;
            }
        }
        best.circuitId = circuitId;
        return best;
    }

    /**
     * 终端（叶子端）优先级：合点(2) > 用电器(1) > 控制器(0) > 其他(-1)
     * 通过名称判断合点（名称含 []），通过类型判断用电器/控制器。
     */
    /**
     * 消费端（叶子端/回溯起点）优先级：用电器(1) > 控制器(0) > 其他(-1)。
     * 注意：合点(焊点)永远不作为叶子端（即能量流回溯的起点），它始终作为
     * 中间节点被经过，例如：用电器->合点->配电->源 / 控制器->合点->配电->源。
     * 因此合点不参与 leaf 选取，仅在 DFS 展开时按高/低优先级规则被穿过。
     */
    private int terminalPriority(String type) {
        if (TYPE_APPLIANCE.equals(type)) return 1;
        if (TYPE_ECU.equals(type)) return 0;
        return -1;
    }

    /**
     * 以“本回路两端”为锚点计算能量流：
     * 强制能量流路径先经过 叶子端(用电器/控制器) → 上游端(合点/配电/控制器) 这一段（即本回路自身的线），
     * 再从上游端继续向上回溯到发电/储电单元。这样无论最短路径如何，
     * 本回路的两个端点（含合点/焊点位置）必定出现在能量流途径分支点中。
     * 注意：合点永远作为“中间节点”被穿过，而不是叶子端（回溯起点）。
     *
     * @param terminalApp 叶子端用电器名称（能量流最终送达的消费端，用电器/控制器）
     * @param terminalPos 叶子端位置
     * @param upstreamApp 上游端用电器名称（与本回路相连的另一端，可能是合点/配电/控制器）
     * @param upstreamPos 上游端位置
     */
    private EnergyFlowResult computeEnergyFlowWithEdge(
            String circuitId,
            String terminalApp, String terminalType, String terminalPos,
            String upstreamApp, String upstreamType, String upstreamPos,
            String anchorSignalName,
            List<String> ownBranchPoints,
            Map<String, List<AppEdge>> appGraph,
            Map<String, String> appTypeMap,
            Map<String, String> appPosMap,
            List<String> allPoint,
            List<List<Integer>> adj,
            List<Map<String, String>> edges) {

        EnergyFlowResult efResult = new EnergyFlowResult();
        efResult.circuitId = circuitId;

        if (terminalApp == null || terminalPos == null
                || upstreamApp == null || upstreamPos == null) {
            efResult.hasEnergyFlow = false;
            return efResult;
        }

        // 信号名合法性：在回溯一开始就判断，本回路(锚点回路)自身信号名必须合法
        // （信号名按 _ . - 分割后，最后两部分包含 KL30/efs/esw/HSD/DRV 才视为可走通的能量流回路）
        if (!isSignalNameValid(anchorSignalName)) {
            efResult.hasEnergyFlow = false;
            return efResult;
        }

        List<List<String>> allAppPaths = new ArrayList<>();
        List<List<String>> allCircuitPaths = new ArrayList<>();

        // 高优先级优先；失败再低优先级（合点可走控制器）
        for (boolean highPriority : new boolean[]{true, false}) {
            List<String> currentAppPath = new ArrayList<>();
            List<String> currentCircuitPath = new ArrayList<>();
            Set<String> visited = new HashSet<>();

            // 强制本回路边：terminal -> upstream
            currentAppPath.add(terminalApp);
            currentCircuitPath.add(circuitId);
            visited.add(terminalApp);
            visited.add(upstreamApp);
            currentAppPath.add(upstreamApp);

            if (isPowerSource(upstreamType)) {
                // 上游端本身就是源
                allAppPaths.add(new ArrayList<>(currentAppPath));
                allCircuitPaths.add(new ArrayList<>(currentCircuitPath));
            } else {
                findEnergyFlowPathsDFSFromPrefix(
                        upstreamApp, upstreamType, visited,
                        currentAppPath, currentCircuitPath,
                        allAppPaths, allCircuitPaths,
                        appGraph, appTypeMap,
                        highPriority, terminalType);
            }
            if (!allAppPaths.isEmpty()) break;
        }

        if (allAppPaths.isEmpty()) {
            efResult.hasEnergyFlow = false;
            return efResult;
        }

        // 选最短（按位置级路径长度）
        int bestIdx = 0;
        double bestLength = Double.MAX_VALUE;
        for (int i = 0; i < allAppPaths.size(); i++) {
            List<String> posPath = buildPositionPath(allAppPaths.get(i), appPosMap);
            double pathLen = computePositionPathLength(posPath, allPoint, adj, edges);
            if (pathLen < bestLength) {
                bestLength = pathLen;
                bestIdx = i;
            }
        }

        List<String> bestAppPath = allAppPaths.get(bestIdx);
        List<String> bestCircuitPath = allCircuitPaths.get(bestIdx);

        // 能量流终点（发电单元/储电单元）的位置
        String sourceApp = bestAppPath.get(bestAppPath.size() - 1);
        String sourcePos = appPosMap.get(sourceApp);

        // 构造能量流“位置级”路径：逐段展开为真实经过的分支点。
        //  · 锚点段(终端→上游，即本回路自身)：使用本回路的 回路途径分支点(按终端→上游定向)，
        //    保证电流确实从消费端沿着本回路自身的线走到另一端，而不是只经过两个端点。
        //  · 其余段(上游→…→源，经过其它回路)：用位置图最短路展开为真实位置序列。
        List<String> efPositionPath = new ArrayList<>();
        for (int i = 0; i < bestAppPath.size() - 1; i++) {
            String aApp = bestAppPath.get(i);
            String bApp = bestAppPath.get(i + 1);
            String aPos = appPosMap.get(aApp);
            String bPos = appPosMap.get(bApp);
            List<String> seg;
            boolean isAnchor = aPos != null && bPos != null
                    && aPos.equals(terminalPos) && bPos.equals(upstreamPos);
            if (isAnchor && ownBranchPoints != null && !ownBranchPoints.isEmpty()) {
                List<String> oriented = orientOwnBranchPoints(ownBranchPoints, terminalPos, upstreamPos);
                boolean ok = oriented.size() >= 2
                        && (oriented.get(0).equals(terminalPos)
                            || oriented.get(oriented.size() - 1).equals(terminalPos));
                seg = ok ? oriented : shortestPositionNames(aPos, bPos, allPoint, adj);
            } else {
                seg = shortestPositionNames(aPos, bPos, allPoint, adj);
            }
            if (seg == null || seg.isEmpty()) continue;
            if (efPositionPath.isEmpty()) {
                efPositionPath.addAll(seg);
            } else {
                String last = efPositionPath.get(efPositionPath.size() - 1);
                int start = seg.get(0).equals(last) ? 1 : 0;
                for (int k = start; k < seg.size(); k++) efPositionPath.add(seg.get(k));
            }
        }
        // 统一为 源 → ... → 消费端 的展示顺序（与历史输出一致）
        java.util.Collections.reverse(efPositionPath);

        // 不绕路最短路径：源位置 → 终端位置
        List<Integer> noDetourIndexPath = null;
        if (sourcePos != null && terminalPos != null) {
            int sourceIdx = allPoint.indexOf(sourcePos);
            int consumerIdx = allPoint.indexOf(terminalPos);
            if (sourceIdx >= 0 && consumerIdx >= 0) {
                FindShortestPath sp = new FindShortestPath();
                noDetourIndexPath = sp.findShortestPathBetweenTwoPoint(adj, sourceIdx, consumerIdx);
            }
        }
        List<String> noDetourPosPath = new ArrayList<>();
        double noDetourLen = 0.0;
        if (noDetourIndexPath != null && !noDetourIndexPath.isEmpty()) {
            noDetourPosPath = convertIndexPathToNames(noDetourIndexPath, allPoint);
            noDetourLen = computePositionPathLength(noDetourPosPath, allPoint, adj, edges);
        }

        // 绕路长度 = 能量流路径长度 - 不绕路最短路径长度（不绕路应更短，做下限保护）
        double efLen = computePositionPathLength(efPositionPath, allPoint, adj, edges);
        double detourLen = Math.max(0.0, efLen - noDetourLen);

        efResult.hasEnergyFlow = true;
        efResult.appliancePath = bestAppPath;
        efResult.circuitPath = bestCircuitPath;
        efResult.energyFlowLength = efLen;
        efResult.noDetourLength = noDetourLen;
        efResult.detourLength = detourLen;
        efResult.energyFlowBranchPoints = convertPositionPathToEdgeIds(efPositionPath, edges);
        efResult.noDetourBranchPoints = convertPositionPathToEdgeIds(noDetourPosPath, edges);
        return efResult;
    }

    /**
     * 从已确定的前缀（已包含 terminal→upstream）继续做 DFS，寻找到达发电/储电单元的路径。
     * 与 findEnergyFlowPathsDFS 不同，本方法不会重复把当前节点加入路径（假定已在路径中）。
     */
    private void findEnergyFlowPathsDFSFromPrefix(
            String currentApp, String currentType,
            Set<String> visited,
            List<String> currentAppPath, List<String> currentCircuitPath,
            List<List<String>> allAppPaths, List<List<String>> allCircuitPaths,
            Map<String, List<AppEdge>> appGraph, Map<String, String> appTypeMap,
            boolean highPriorityOnly, String originalConsumerType) {

        List<AppEdge> neighbors = appGraph.get(currentApp);
        if (neighbors == null) return;

        for (AppEdge edge : neighbors) {
            String neighborApp = edge.toApp.equals(currentApp) ? edge.fromApp : edge.toApp;
            String neighborType = edge.toApp.equals(currentApp) ? edge.fromType : edge.toType;

            if (visited.contains(neighborApp)) continue;
            if (!isSignalNameValid(edge.signalName)) continue;
            if (!isValidTransition(currentApp, currentType, neighborApp, neighborType,
                    originalConsumerType, highPriorityOnly)) continue;

            visited.add(neighborApp);
            currentAppPath.add(neighborApp);
            currentCircuitPath.add(edge.circuitId);

            if (isPowerSource(neighborType) && currentAppPath.size() > 1) {
                allAppPaths.add(new ArrayList<>(currentAppPath));
                allCircuitPaths.add(new ArrayList<>(currentCircuitPath));
            } else {
                findEnergyFlowPathsDFSFromPrefix(
                        neighborApp, neighborType, visited,
                        currentAppPath, currentCircuitPath,
                        allAppPaths, allCircuitPaths,
                        appGraph, appTypeMap,
                        highPriorityOnly, originalConsumerType);
            }

            visited.remove(neighborApp);
            currentAppPath.remove(currentAppPath.size() - 1);
            currentCircuitPath.remove(currentCircuitPath.size() - 1);
        }
    }

    /**
     * DFS搜索所有能量流路径
     *
     * @param currentApp      当前用电器名称
     * @param currentType     当前用电器类型
     * @param currentPos      当前用电器位置
     * @param visited         已访问用电器集合
     * @param currentAppPath  当前用电器路径
     * @param currentCircuitPath 当前回路路径
     * @param allAppPaths     收集所有用电器路径
     * @param allCircuitPaths 收集所有回路路径
     * @param appGraph        用电器邻接图
     * @param appTypeMap      用电器类型映射
     * @param highPriorityOnly 是否仅尝试高优先级（合点→配电单元）
     * @param originalConsumerType 原始消费端类型
     */
    private void findEnergyFlowPathsDFS(
            String currentApp, String currentType, String currentPos,
            Set<String> visited,
            List<String> currentAppPath, List<String> currentCircuitPath,
            List<List<String>> allAppPaths, List<List<String>> allCircuitPaths,
            Map<String, List<AppEdge>> appGraph,
            Map<String, String> appTypeMap,
            boolean highPriorityOnly,
            String originalConsumerType) {

        visited.add(currentApp);
        currentAppPath.add(currentApp);

        // 到达发电单元/储电单元 → 找到一条完整路径
        if (isPowerSource(currentType) && currentAppPath.size() > 1) {
            allAppPaths.add(new ArrayList<>(currentAppPath));
            allCircuitPaths.add(new ArrayList<>(currentCircuitPath));
            visited.remove(currentApp);
            currentAppPath.remove(currentAppPath.size() - 1);
            return;
        }

        List<AppEdge> neighbors = appGraph.get(currentApp);
        if (neighbors != null) {
            for (AppEdge edge : neighbors) {
                String neighborApp = edge.toApp.equals(currentApp) ? edge.fromApp : edge.toApp;
                String neighborType = edge.toApp.equals(currentApp) ? edge.fromType : edge.toType;

                if (visited.contains(neighborApp)) {
                    continue;
                }

                // 检查信号名是否合法
                if (!isSignalNameValid(edge.signalName)) {
                    continue;
                }

                // 检查类型转移是否合法（传入用电器名称以正确检测焊点）
                if (!isValidTransition(currentApp, currentType, neighborApp, neighborType,
                        originalConsumerType, highPriorityOnly)) {
                    continue;
                }

                currentCircuitPath.add(edge.circuitId);
                findEnergyFlowPathsDFS(
                        neighborApp, neighborType,
                        appTypeMap.getOrDefault(neighborApp, neighborType),
                        visited, currentAppPath, currentCircuitPath,
                        allAppPaths, allCircuitPaths,
                        appGraph, appTypeMap,
                        highPriorityOnly,
                        originalConsumerType);
                currentCircuitPath.remove(currentCircuitPath.size() - 1);
            }
        }

        visited.remove(currentApp);
        currentAppPath.remove(currentAppPath.size() - 1);
    }

    /**
     * 检查类型转移是否合法（基于用电器名称判断合点/焊点）
     *
     * @param fromApp             当前用电器名称
     * @param fromType            当前用电器类型
     * @param toApp               目标用电器名称
     * @param toType              目标用电器类型
     * @param originalConsumerType 原始消费端类型
     * @param highPriorityOnly    是否仅尝试高优先级
     */
    private boolean isValidTransition(String fromApp, String fromType, String toApp, String toType,
            String originalConsumerType, boolean highPriorityOnly) {

        // 通过名称判断焊点（名称含[]括号），不依赖类型字段
        boolean fromSolder = isSolderPoint(fromApp);
        boolean toSolder = isSolderPoint(toApp);

        // 到达发电单元/储电单元 → 始终合法
        if (isPowerSource(toType)) {
            return true;
        }

        // 接地点不允许作为能量流路径节点
        if (TYPE_GROUND.equals(toType)) {
            return false;
        }

        // === 合点(焊点) 作为起始点的转移规则 ===
        if (fromSolder) {
            // 合点→合点（不允许回路间跳转）
            if (toSolder) return false;
            // 合点→配电单元（高优先级，始终允许）
            if (TYPE_PDU.equals(toType)) return true;
            // 合点→控制器（低优先级，仅在非高优先级模式下允许）
            if (TYPE_ECU.equals(toType)) return !highPriorityOnly;
            // 合点不能去其他地方
            return false;
        }

        // === 从其他类型到达合点 ===
        if (toSolder) {
            // 只有用电器或控制器能到达合点
            return TYPE_APPLIANCE.equals(fromType) || TYPE_ECU.equals(fromType);
        }

        // === 常规类型转移（fromType和toType都不为null） ===
        if (fromType == null || toType == null) {
            return false;
        }

        boolean fromConsumer = TYPE_APPLIANCE.equals(fromType);
        boolean fromController = TYPE_ECU.equals(fromType);
        boolean fromDistUnit = TYPE_PDU.equals(fromType);

        boolean toDistUnit = TYPE_PDU.equals(toType);
        boolean toController = TYPE_ECU.equals(toType);

        // 配电单元 → 配电单元（级联查找上游发电/储电单元）
        if (fromDistUnit && toDistUnit) {
            return true;
        }
        // 配电单元 → 其他类型（不允许，只能向上找发电/储电单元）
        if (fromDistUnit) {
            return false;
        }
        // 用电器 → 配电单元 / 控制器
        if (fromConsumer) {
            return toDistUnit || toController;
        }
        // 控制器 → 配电单元 / 控制器
        if (fromController) {
            return toDistUnit || toController;
        }

        return false;
    }

    /**
     * 判断是否跳过该回路的能量流计算
     * 规则1：起点或终点为发电单元/储电单元 ↔ 配电单元 的回路不计算
     */
    private boolean shouldSkipCircuit(String startType, String endType) {
        if (startType == null || endType == null) {
            return false;
        }
        boolean startIsSource = isPowerSource(startType);
        boolean endIsSource = isPowerSource(endType);
        boolean startIsDist = TYPE_PDU.equals(startType);
        boolean endIsDist = TYPE_PDU.equals(endType);

        // 发电单元/储电单元 ↔ 配电单元
        return (startIsSource && endIsDist) || (startIsDist && endIsSource);
    }

    /**
     * 判断该回路是否属于需要计算能量流的“规则回路”。
     * 允许的端点类型组合（无序）对应规则 2~7：
     *   配电↔控制器（规则2）、配电↔用电器（规则3）、控制器↔用电器（规则4）、
     *   控制器↔控制器（规则5）、用电器↔合点（规则6）、控制器↔合点（规则7）。
     * 其余组合（含 发电/储电↔配电 已在 shouldSkipCircuit 跳过、以及
     * 用电器↔用电器、配电↔配电、合点↔合点 等）一律不参与计算。
     */
    private boolean isRuleCircuit(String typeA, String nameA, String typeB, String nameB) {
        String tA = normalizeType(typeA, nameA);
        String tB = normalizeType(typeB, nameB);
        return matches(tA, tB, TYPE_PDU, TYPE_ECU)            // 规则2：配电↔控制器
                || matches(tA, tB, TYPE_PDU, TYPE_APPLIANCE)   // 规则3：配电↔用电器
                || matches(tA, tB, TYPE_ECU, TYPE_APPLIANCE)   // 规则4：控制器↔用电器
                || matches(tA, tB, TYPE_ECU, TYPE_ECU)         // 规则5：控制器↔控制器
                || matches(tA, tB, TYPE_APPLIANCE, "合点")      // 规则6：用电器↔合点
                || matches(tA, tB, TYPE_ECU, "合点");          // 规则7：控制器↔合点
    }

    /**
     * 归一化类型：合点(焊点)统一用名称 "合点" 表示，便于组合判定。
     */
    private String normalizeType(String type, String name) {
        if (TYPE_PDU.equals(type) || TYPE_GENERATOR.equals(type)
                || TYPE_BATTERY.equals(type) || TYPE_ECU.equals(type)
                || TYPE_APPLIANCE.equals(type)) {
            return type;
        }
        if (isSolderPoint(name)) {
            return "合点";
        }
        return type;
    }

    /**
     * 无序二元匹配：判断 (a,b) 是否等于 (x,y) 或 (y,x)。
     */
    private boolean matches(String a, String b, String x, String y) {
        if (a == null || b == null) return false;
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    /**
     * 判断是否为发电单元或储电单元
     */
    private boolean isPowerSource(String type) {
        return TYPE_GENERATOR.equals(type) || TYPE_BATTERY.equals(type);
    }

    /**
     * 判断是否为消费端类型（用电器/控制器/合点）
     * @param type 用电器类型
     * @param name 用电器名称（用于合点/焊点判断，焊点名称含[]括号）
     */
    private boolean isConsumerType(String type, String name) {
        if (type == null && name == null) return false;
        return TYPE_APPLIANCE.equals(type) || TYPE_ECU.equals(type) || isSolderPoint(name);
    }

    /**
     * 判断用电器名称/类型是否为焊点/合点（名称带[]括号）
     */
    private boolean isSolderPoint(String name) {
        if (name == null) return false;
        return name.contains("[") && name.contains("]");
    }

    /**
     * 检查回路信号名是否为合法的能量流信号
     * 规则：信号名按 下划线(_)、点(.)、连字符(-) 分割后，
     * 仅判断“最后两部分”是否包含关键字列表中的任意一个（大小写不敏感）。
     * 例如 "abc_KL30"、"xxx.efs_def"、"mod-HSD-yyy" 均可命中。
     */
    private boolean isSignalNameValid(String signalName) {
        if (signalName == null || signalName.isEmpty()) {
            return false;
        }
        String upperName = signalName.toUpperCase();
        // 按下划线 / 点 / 连字符分割
        String[] parts = upperName.split("[_.-]");
        if (parts.length == 0) {
            return false;
        }
        // 仅检查最后两部分（后两部分）是否包含关键字
        int start = Math.max(0, parts.length - 2);
        for (int i = start; i < parts.length; i++) {
            for (String keyword : ENERGY_FLOW_KEYWORDS) {
                if (parts[i].contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从所有loopdetails构建用电器邻接图（两遍扫描）
     */
    public void buildApplianceGraph(
            Map<String, Object> loopdetails,
            Map<String, List<AppEdge>> appGraph,
            Map<String, String> appTypeMap,
            Map<String, String> appPosMap) {

        // 第一遍：收集所有焊点位置映射（焊点名称→焊点位置名称）
        Map<String, String> solderPosLookup = new HashMap<>();
        for (Map.Entry<String, Object> entry : loopdetails.entrySet()) {
            Map<String, Object> loopInfo = (Map<String, Object>) entry.getValue();
            if (loopInfo == null) continue;
            String solderName = safeGetString(loopInfo, "焊点名称");
            if (solderName != null && isSolderPoint(solderName)) {
                String solderPos = safeGetString(loopInfo, "焊点位置名称");
                if (solderPos != null) {
                    solderPosLookup.put(solderName, solderPos);
                }
            }
        }

        // 第二遍：构建用电器图
        for (Map.Entry<String, Object> entry : loopdetails.entrySet()) {
            Map<String, Object> loopInfo = (Map<String, Object>) entry.getValue();
            if (loopInfo == null) continue;

            String circuitId = entry.getKey();
            String startApp = safeGetString(loopInfo, "起点用电器名称");
            String endApp = safeGetString(loopInfo, "终点用电器名称");
            String startType = safeGetString(loopInfo, "起点用电器类型");
            String endType = safeGetString(loopInfo, "终点用电器类型");
            String startPos = safeGetString(loopInfo, "起点位置名称");
            String endPos = safeGetString(loopInfo, "终点位置名称");
            String signalName = safeGetString(loopInfo, "回路信号名");

            if (startApp == null || endApp == null) continue;

            // 记录用电器类型
            if (startType != null) appTypeMap.put(startApp, startType);
            if (endType != null) appTypeMap.put(endApp, endType);

            // 记录用电器位置：端点自带位置优先，合点且为null则从跨回路的焊点表补
            if (startPos != null) {
                appPosMap.put(startApp, startPos);
            } else if (isSolderPoint(startApp)) {
                String pos = solderPosLookup.get(startApp);
                if (pos != null) appPosMap.put(startApp, pos);
            }
            if (endPos != null) {
                appPosMap.put(endApp, endPos);
            } else if (isSolderPoint(endApp)) {
                String pos = solderPosLookup.get(endApp);
                if (pos != null) appPosMap.put(endApp, pos);
            }

            // 添加双向边
            AppEdge edge = new AppEdge(startApp, endApp, startType, endType, circuitId, signalName, startPos, endPos);
            appGraph.computeIfAbsent(startApp, k -> new ArrayList<>()).add(edge);
            appGraph.computeIfAbsent(endApp, k -> new ArrayList<>()).add(edge);
        }
    }

    /**
     * 根据用电器路径构建位置路径
     * 对于每对相邻用电器，计算它们之间的位置级最短路径，然后拼接
     */
    private List<String> buildPositionPath(List<String> appPath, Map<String, String> appPosMap) {
        List<String> posPath = new ArrayList<>();
        for (String app : appPath) {
            String pos = appPosMap.get(app);
            if (pos != null) {
                posPath.add(pos);
            }
        }
        return posPath;
    }

    /**
     * 计算位置路径的总长度（遍历每对相邻位置，计算实际分支长度之和）
     */
    private double computePositionPathLength(
            List<String> posPath,
            List<String> allPoint,
            List<List<Integer>> adj,
            List<Map<String, String>> edges) {

        if (posPath == null || posPath.size() < 2) {
            return 0.0;
        }

        double totalLength = 0.0;
        FindShortestPath sp = new FindShortestPath();

        for (int i = 0; i < posPath.size() - 1; i++) {
            String fromPos = posPath.get(i);
            String toPos = posPath.get(i + 1);
            int fromIdx = allPoint.indexOf(fromPos);
            int toIdx = allPoint.indexOf(toPos);

            if (fromIdx < 0 || toIdx < 0) {
                continue;
            }

            List<Integer> indexPath = sp.findShortestPathBetweenTwoPoint(adj, fromIdx, toIdx);
            if (indexPath != null) {
                List<String> subPosPath = convertIndexPathToNames(indexPath, allPoint);
                totalLength += computeEdgePathLength(subPosPath, edges);
            }
        }
        // 与 CalculateCircuitInfo 一致：总长后统一补上分支末端默认长度
        return totalLength + ProjectCircuitInfoOutput.BranchEndFallback;
    }

    /**
     * 把本回路自身的「回路途径分支点」(位置序列) 定向为 终端(消费端)→上游端 的顺序。
     * bestAppPath 中该锚点段为 terminal→upstream，故返回序列首部应为 terminalPos。
     */
    private List<String> orientOwnBranchPoints(List<String> own, String terminalPos, String upstreamPos) {
        if (own == null || own.isEmpty()) return new ArrayList<>();
        List<String> lst = new ArrayList<>(own);
        if (lst.size() >= 1 && !lst.get(0).equals(terminalPos)) {
            if (lst.get(lst.size() - 1).equals(terminalPos)) {
                java.util.Collections.reverse(lst); // 末部是终端 → 反转
            }
        }
        // 若首尾都不等于终端(无法确定方向)，保持原样，由调用方回退到最短路展开
        return lst;
    }

    /**
     * 求两个位置在位置图上的最短路位置序列，用于把能量流路径的“其它段”展开为真实走线。
     */
    private List<String> shortestPositionNames(String aPos, String bPos,
            List<String> allPoint, List<List<Integer>> adj) {
        if (aPos == null || bPos == null) return new ArrayList<>();
        int ai = allPoint.indexOf(aPos);
        int bi = allPoint.indexOf(bPos);
        if (ai < 0 || bi < 0) {
            List<String> fb = new ArrayList<>();
            if (aPos != null) fb.add(aPos);
            return fb;
        }
        FindShortestPath sp = new FindShortestPath();
        List<Integer> idx = sp.findShortestPathBetweenTwoPoint(adj, ai, bi);
        if (idx == null || idx.isEmpty()) {
            List<String> fb = new ArrayList<>();
            fb.add(aPos);
            return fb;
        }
        return convertIndexPathToNames(idx, allPoint);
    }

    /**
     * 计算相邻位置路径的实际长度（通过分支信息中存储的长度值）
     */
    private double computeEdgePathLength(List<String> posPath, List<Map<String, String>> edges) {
        if (posPath == null || posPath.size() < 2) {
            return 0.0;
        }
        double length = 0.0;
        for (int i = 0; i < posPath.size() - 1; i++) {
            String p1 = posPath.get(i);
            String p2 = posPath.get(i + 1);
            // 在edges中查找匹配的分支
            for (Map<String, String> edge : edges) {
                String startName = edge.get("分支起点名称");
                String endName = edge.get("分支终点名称");
                if ((p1.equals(startName) && p2.equals(endName)) || (p1.equals(endName) && p2.equals(startName))) {
                    Object verifyLenObj = edge.get("用户确认的分支长度");
                    Integer verifyLen = verifyLenObj == null ? null : Integer.parseInt(verifyLenObj.toString());
                    Object refLenObj = edge.get("参考长度");
                    Integer refLen = refLenObj == null ? null : Integer.parseInt(refLenObj.toString());
                    if (verifyLen != null ) {
                        length += Double.parseDouble(verifyLen.toString());
                    } else if (refLen != null) {
                        length += Double.parseDouble(refLen.toString());
                    } else {
                        length += ProjectCircuitInfoOutput.BranchEndFallback; // 默认200mm
                    }
                    break;
                }
            }
        }
        return length;
    }

    /**
     * 将索引路径转为名称路径
     */
    private List<String> convertIndexPathToNames(List<Integer> indexPath, List<String> allPoint) {
        List<String> names = new ArrayList<>();
        for (Integer idx : indexPath) {
            names.add(allPoint.get(idx));
        }
        return names;
    }

    /**
     * 安全获取Map中的字符串值
     */
    private String safeGetString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || "null".equals(String.valueOf(val))) {
            return null;
        }
        return val.toString();
    }

    /**
     * 判断字符串是否为空
     */
    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 计算单条回路的能量流字段并写入回路信息
     * 供 calculateCircuit 等场景调用，直接修改 loopInfo 中的对应字段
     *
     * @param loopInfo   单条回路信息Map（会被原地修改，写入6个能量流字段）
     * @param loopdetails 全部回路详情
     * @param allPoint   全连通图所有分支点名称列表
     * @param adj        全连通邻接表
     * @param edges      所有分支信息
     */
    public void fillSingleCircuitEnergyFlow(
            Map<String, Object> loopInfo,
            Map<String, Object> loopdetails,
            List<String> allPoint,
            List<List<Integer>> adj,
            List<Map<String, String>> edges) {

        if (loopInfo == null) return;

        String circuitId = safeGetString(loopInfo, "回路id");
        if (circuitId == null) return;

        List<String> singleList = new ArrayList<>();
        singleList.add(circuitId);
        Map<String, Object> result = calculateEnergyFlowDetour(singleList, loopdetails, allPoint, adj, edges);
        @SuppressWarnings("unchecked")
        List<EnergyFlowResult> perCircuitResults = (List<EnergyFlowResult>) result.get("perCircuitResults");

        if (perCircuitResults != null && !perCircuitResults.isEmpty()) {
            EnergyFlowResult efResult = perCircuitResults.get(0);
            DecimalFormat df = new DecimalFormat("0.00");
            if (efResult.skipped || !efResult.hasEnergyFlow) {
                loopInfo.put("能量流绕路总数量(根)", null);
                loopInfo.put("能量流绕路数量占比(百分比)", null);
                loopInfo.put("能量流绕路长度总值(米)", null);
                loopInfo.put("能量流绕路长度均值(米/根)", null);
                loopInfo.put("能量流途径分支id", null);
                loopInfo.put("能量流不绕路途径分支id", null);
            } else {
                double detourMeters = Math.max(efResult.detourLength, 0) / 1000.0;
                loopInfo.put("能量流绕路总数量(根)", detourMeters > 0 ? 1 : 0);
                loopInfo.put("能量流绕路数量占比(百分比)", detourMeters > 0 ? "100.00%" : "0.00%");
                loopInfo.put("能量流绕路长度总值(米)", Double.parseDouble(df.format(detourMeters)));
                loopInfo.put("能量流绕路长度均值(米/根)", Double.parseDouble(df.format(detourMeters)));
                loopInfo.put("能量流途径分支id",
                        efResult.energyFlowBranchPoints.isEmpty() ? null : efResult.energyFlowBranchPoints);
                loopInfo.put("能量流不绕路途径分支id",
                        efResult.noDetourBranchPoints.isEmpty() ? null : efResult.noDetourBranchPoints);
            }
        }
    }

    /**
     * 将位置名称路径转为分支 id 路径（前端需要分支id而非名称）
     */
    private List<String> convertPositionPathToEdgeIds(List<String> posPath, List<Map<String, String>> edges) {
        if (posPath == null || posPath.size() < 2) return new ArrayList<>();
        Map<String, String> edgeMap = new HashMap<>();
        for (Map<String, String> edge : edges) {
            String s = edge.get("分支起点名称");
            String e = edge.get("分支终点名称");
            String id = edge.get("分支id编号");
            if (s != null && e != null && id != null) {
                edgeMap.put(s + "|" + e, id);
                edgeMap.put(e + "|" + s, id);
            }
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < posPath.size() - 1; i++) {
            String key = posPath.get(i) + "|" + posPath.get(i + 1);
            String edgeId = edgeMap.get(key);
            if (edgeId != null) ids.add(edgeId);
        }
        return ids;
    }

    //    根据传入的值找到对应的颜色
    public static String getlengthColor(double number) {
        if (number == 0) {
            return "rgb(248,246,231)";
        } else if (number >= 0 && number <= 5) {
            return "rgb(0,0,255)";
        } else if (number >= 5 && number <= 10) {
            return "rgb(0,255,255)";
        } else if (number >= 10 && number <= 15) {
            return "rgb(0,255,0)";
        } else if (number >= 15 && number <= 20) {
            return "rgb(127,255,0)";
        } else if (number >= 20 && number <= 25) {
            return "rgb(255,255,0)";
        } else if (number >= 25 && number <= 30) {
            return "rgb(255,165,0)";
        } else if (number >= 30 && number <= 35) {
            return "rgb(255,69,0)";
        } else if (number >= 35 && number <= 40) {
            return "rgb(255,0,0)";
        } else if (number >= 40 && number <= 45) {
            return "rgb(139,0,0)";
        } else {
            return "rgb(0,0,0)";
        }
    }

}
