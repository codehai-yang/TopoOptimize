package HarnessPackOpti.Optimize.elec;

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

/**
 * 配电驱动优化
 */
public class PowerDistributionDriveOptimization {

    //    当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    // 可变数量阈值，走枚举
    public static Integer caseNumbe = 10000;

    // 枚举收集的所有方案
    private List<Map<String, String>> enumeratedSchemes = new ArrayList<>();

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance();
    }

    public String powerDriverOptimize(String jsonContent) throws Exception {
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
        String initializeCaseResult = powerProjectCircuitInfoOutput.powerOptimize(jsonContent);
        // 判断是哪种类型优化
        String optimizeType = projectInfo.get("optimizeType");
        String[] split = optimizeType.split(",");
        List<String> typeList = Arrays.asList(split);
        // 是否开启直连接口
        boolean whetherToChange = projectInfo.get("whetherToChange") != null
                && projectInfo.get("whetherToChange").equals("true");

        // 主供电回路和配电回路
        List<Map<String, String>> elecLoopList = new ArrayList<>();
        // 驱动回路
        List<Map<String, String>> driveLoopList = new ArrayList<>();
        // 资源数量读取
        Map<String, List<String>> resourceNum = new HashMap<>();
        // 组团一起变：groupId → [loopId, ...]
        Map<String, List<String>> togetherGroup = new HashMap<>();
        // 互斥组：mutualId → [loopId, ...]
        Map<String, List<String>> mutualGroup = new HashMap<>();
        // 用电器 appId → appName
        Map<String, String> elecNameId = new HashMap<>();
        //位置点名称-id
        Map<String, String> pointNameId = new HashMap<>();

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

        // 统计用电器可变位置点：appName → [position, ...]
        //查找用电器自身位置点
        Map<String, String> eleclection = getEleclection(appPositions);
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
                        list.add(findNameById(part, points));
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

        for (Map<String, String> loopInfo : loopInfos) {
            if ("主供电回路".equals(loopInfo.get("loopAttribute"))
                    || "配电回路".equals(loopInfo.get("loopAttribute"))) {
                elecLoopList.add(loopInfo);
            } else if ("驱动回路".equals(loopInfo.get("loopAttribute"))) {
                driveLoopList.add(loopInfo);
            }
            // 组团归组
            String ct = loopInfo.get("changeTogether");
            if (ct != null && !ct.isEmpty()) {
                togetherGroup.computeIfAbsent(ct, k -> new ArrayList<>()).add(loopInfo.get("id"));
            }
            // 互斥归组
            String me = loopInfo.get("mutualExclusion");
            if (me != null && !me.isEmpty()) {
                mutualGroup.computeIfAbsent(me, k -> new ArrayList<>()).add(loopInfo.get("id"));
            }
        }

        // 找出所有可能变化的接口点，同组归并
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
        System.out.println("回路分类耗时:" + (System.currentTimeMillis() - categoryTime));

        // 枚举模式：计算带约束的方案总数
        // 优化类型 1：控制器回路
        long combinationsTime = System.currentTimeMillis();
        long combinations = 0;

        //同时优化配电回路和驱动器回路
        List<Map<String, String>> combinedList = new ArrayList<>(elecLoopList);
        combinedList.addAll(driveLoopList);
        if (typeList.contains("1") && typeList.contains("2")) {
            combinations = calculateOptimizationCombinations(combinedList, elecChangeablePosition, togetherGroup);
        }

        //优化驱动回路
        if ("1".equals(optimizeType)) {
             combinations = calculateOptimizationCombinations(driveLoopList, elecChangeablePosition, togetherGroup);
        }

        // 优化类型 2：配电器回路
        if ("2".equals(optimizeType)) {
             combinations = calculateOptimizationCombinations(elecLoopList, elecChangeablePosition, togetherGroup);
        }

        // 优化类型 3：所有回路
        if ("3".equals(optimizeType)) {
             combinations = calculateOptimizationCombinations(loopInfos, elecChangeablePosition, togetherGroup);
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

            if (targetLoops != null && !targetLoops.isEmpty()) {
                // 执行枚举
                Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);

                enumerateAllSchemes(targetLoops, elecChangeablePosition, togetherGroup, mutualGroup, loopInfos);

                System.out.println("枚举耗时: " + (System.currentTimeMillis() - enumerateTime) + "ms");

                // 每个方案格式: Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
                // 遍历所有方案
                for (int i = 0; i < enumeratedSchemes.size(); i++) {
                    Map<String, String> scheme = enumeratedSchemes.get(i);

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
                            }
                        }

                        for (Map<String, String> stringStringMap : appPositionsCopy) {
                            if(stringStringMap.get("appName").equals(startApp)){
                                stringStringMap.put("unregularPointName", startPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(startPos));
                            }
                            if(stringStringMap.get("appName").equals(endApp)){
                                stringStringMap.put("unregularPointName", endPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(endPos));
                            }
                        }
                    }
                    //合成方案
                    jsonMapCopy.put("loopInfo", loopInfoCopy);
                    jsonMapCopy.put("appPositions", appPositionsCopy);
                    //计算成本
                }
                String tempOptimizeResult = powerProjectCircuitInfoOutput.powerOptimize(jsonContent);
            }


            //枚举返回最优top20样本
            return null;
        }
        return null;
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
     */
    private void enumerateAllSchemes(
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            List<Map<String, String>> allLoopInfos) {

        long startTime = System.currentTimeMillis();

        // loopId → loopInfo 快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : allLoopInfos) {
            loopById.put(lp.get("id"), lp);
        }

        // --------------------------------------------------------
        // Step 1: 构建"变量"列表
        // --------------------------------------------------------
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();

        // 处理联动组：取所有成员 endApp 可变位置的交集
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();
            List<String> intersection = null;
            for (String lid : memberLoopIds) {
                Map<String, String> lp = loopById.get(lid);
                if (lp == null) continue;
                List<String> pos = elecChangeablePosition.get(lp.get("endApp"));
                if (pos == null) pos = Collections.emptyList();
                if (intersection == null) {
                    intersection = new ArrayList<>(pos);
                } else {
                    intersection.retainAll(pos);
                }
                coveredLoopIds.add(lid);
            }
            varDomains.put("G_" + groupId,
                    intersection != null ? intersection : Collections.emptyList());
        }

        // 处理独立回路（不在任何联动组中）
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid)) continue;
            List<String> pos = elecChangeablePosition.getOrDefault(
                    lp.get("endApp"), Collections.emptyList());
            varDomains.put("L_" + lid, new ArrayList<>(pos));
        }

        // --------------------------------------------------------
        // Step 2: 变量 → 互斥组映射
        // --------------------------------------------------------
        Map<String, String> varKeyToMutualId = new LinkedHashMap<>();
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");
            if (mutual == null || mutual.isEmpty()) continue;
            String vk = (together != null && !together.isEmpty())
                    ? "G_" + together
                    : "L_" + lid;
            varKeyToMutualId.putIfAbsent(vk, mutual);
        }

        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : varKeyToMutualId.entrySet()) {
            mutualIdToVarKeys.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                    .add(e.getKey());
        }
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualId.keySet());

        // --------------------------------------------------------
        // Step 3: 准备变量列表（包含起点位置变量）
        // --------------------------------------------------------
        List<String> varKeys = new ArrayList<>(varDomains.keySet());
        Set<String> countedStartApps = new HashSet<>();
        for (Map<String, String> lp : targetLoops) {
            String startApp = lp.get("startApp");
            if (startApp != null && !startApp.isEmpty() && !countedStartApps.contains(startApp)) {
                List<String> startPos = elecChangeablePosition.get(startApp);
                if (startPos != null && !startPos.isEmpty()) {
                    String varKey = "S_" + startApp;
                    varKeys.add(varKey);
                    varDomains.put(varKey, new ArrayList<>(startPos));
                    countedStartApps.add(startApp);
                }
            }
        }

        System.out.println("开始回溯枚举，变量数: " + varKeys.size());

        // --------------------------------------------------------
        // Step 4: 执行回溯枚举
        // --------------------------------------------------------
        Map<String, String> currentAssignment = new LinkedHashMap<>();
        Set<String> usedPositions = new HashSet<>();

        enumerateSchemesByBacktrack(
                varDomains, mutualIdToVarKeys, varsInAnyMutualGroup,
                0, varKeys, currentAssignment, usedPositions,
                targetLoops, loopById);

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
            Set<String> usedPositions,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById) {

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
                    currentAssignment, targetLoops, loopById);

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

        for (String position : domain) {
            // 互斥约束检查
            if (isInMutualGroup && usedPositions.contains(position)) {
                continue;
            }

            // 做选择
            currentAssignment.put(varKey, position);
            usedPositions.add(position);

            // 递归处理下一个变量
            enumerateSchemesByBacktrack(
                    varDomains, mutualIdToVarKeys, varsInMutualGroup,
                    varIndex + 1, varKeys, currentAssignment, usedPositions,
                    targetLoops, loopById);

            // 如果已经达到限制，提前退出
            if (enumeratedSchemes.size() >= caseNumbe) {
                break;
            }

            // 撤销选择
            usedPositions.remove(position);
            currentAssignment.remove(varKey);
        }
    }

    /**
     * 将变量赋值转换为方案格式
     *
     * @return Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
     *         只包含需要优化的回路
     */
    private Map<String, String> convertAssignmentToSchemeFormat(
            Map<String, String> assignment,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById) {

        Map<String, String> scheme = new LinkedHashMap<>();

        // 遍历所有目标回路
        for (Map<String, String> loop : targetLoops) {
            String loopId = loop.get("id");
            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            String together = loop.get("changeTogether");

            String assignedStartPosition = null;
            String assignedEndPosition = null;

            // 获取终点位置（endApp的位置）
            if (together != null && !together.isEmpty()) {
                // 联动组变量
                assignedEndPosition = assignment.get("G_" + together);
            } else {
                // 独立回路变量
                assignedEndPosition = assignment.get("L_" + loopId);
            }

            // 获取起点位置（startApp的位置）
            if (startApp != null && !startApp.isEmpty()) {
                assignedStartPosition = assignment.get("S_" + startApp);
            }

            // 如果位置有赋值，则加入方案
            if (assignedStartPosition != null || assignedEndPosition != null) {
                // 使用默认值（如果未赋值）
                String finalStartPos = assignedStartPosition != null ? assignedStartPosition : "";
                String finalEndPos = assignedEndPosition != null ? assignedEndPosition : "";

                // 格式: "起点用电器|终点用电器|起点位置|终点位置"
                String value = startApp + "|" + endApp + "|" + finalStartPos + "|" + finalEndPos;

                scheme.put(loopId, value);
            }
        }

        return scheme;
    }

    // ================================================================
    // 计算优化方案总数（支持不同类型的回路集合）
    //
    // @param loopInfos           需要优化的回路列表
    // @param elecChangeablePosition 用电器到可变位置列表的映射
    // @param togetherGroup       联动组配置（groupId -> [loopId, ...]）
    // @return 可行方案总数
    // ================================================================
    private long calculateOptimizationCombinations(
            List<Map<String, String>> loopInfos,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup) {

        long calcStart = System.currentTimeMillis();

        // loopId → loopInfo 快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : loopInfos) {
            loopById.put(lp.get("id"), lp);
        }

        // --------------------------------------------------------
        // Step 1: 构建"变量"列表
        //
        // 规则：
        //   联动组（changeTogether）→ 合并为 1 个变量
        //     该变量的域 = 组内所有回路 endApp 可变位置的【交集】
        //   独立回路（无 changeTogether）→ 1 个变量
        //     该变量的域 = 该回路 endApp 的可变位置列表
        //
        // 变量 key 格式：
        //   联动组变量  → "G_<togetherGroupId>"
        //   独立回路变量 → "L_<loopId>"
        // --------------------------------------------------------
        // varKey → 可选位置列表（域）
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();

        // 处理联动组：取所有成员 endApp 可变位置的交集
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();
            List<String> intersection = null;
            for (String lid : memberLoopIds) {
                Map<String, String> lp = loopById.get(lid);
                if (lp == null) continue;
                List<String> pos = elecChangeablePosition.get(lp.get("endApp"));
                if (pos == null) pos = Collections.emptyList();
                if (intersection == null) {
                    intersection = new ArrayList<>(pos);
                } else {
                    intersection.retainAll(pos);
                }
                coveredLoopIds.add(lid);
            }
            varDomains.put("G_" + groupId,
                    intersection != null ? intersection : Collections.emptyList());
        }

        // 处理独立回路（不在任何联动组中）
        for (Map<String, String> lp : loopInfos) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid)) continue;
            List<String> pos = elecChangeablePosition.getOrDefault(
                    lp.get("endApp"), Collections.emptyList());
            varDomains.put("L_" + lid, new ArrayList<>(pos));
        }

        // --------------------------------------------------------
        // Step 2: 变量 → 互斥组映射
        //
        // 一条回路如果有 mutualExclusion 标记：
        //   若它属于某个联动组 → 整个联动组变量参与互斥
        //   否则 → 该回路自己的变量参与互斥
        // --------------------------------------------------------
        // varKey → mutualGroupId（同一变量只属于一个互斥组）
        Map<String, String> varKeyToMutualId = new LinkedHashMap<>();
        for (Map<String, String> lp : loopInfos) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");
            if (mutual == null || mutual.isEmpty()) continue;
            String vk = (together != null && !together.isEmpty())
                    ? "G_" + together
                    : "L_" + lid;
            // 同一个变量只记录一次互斥组（以第一个为准）
            varKeyToMutualId.putIfAbsent(vk, mutual);
        }

        // 互斥组 → 变量列表（去重，保持唯一）
        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : varKeyToMutualId.entrySet()) {
            mutualIdToVarKeys.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                    .add(e.getKey());
        }
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualId.keySet());

        // --------------------------------------------------------
        // Step 3: 计算总方案数
        //
        // 3a. 独立变量（不在任何互斥组中）→ 直接乘以域大小
        // 3b. 互斥组（可能含联动组变量）→ 回溯法计算"两两不同"的赋值数
        // 3c. 起点位置（每个唯一 startApp 独立计算，相乘）
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

        // 3b: 互斥组 —— 回溯计算全不同赋值数
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

        // 3c: 起点位置（同一用电器作为起点时只统计一次）
        if (totalCombinations > 0) {
            Set<String> countedStartApps = new HashSet<>();
            for (Map<String, String> lp : loopInfos) {
                String startApp = lp.get("startApp");
                if (startApp == null || startApp.isEmpty()) continue;
                if (countedStartApps.contains(startApp)) continue;
                List<String> startPos = elecChangeablePosition.get(startApp);
                if (startPos != null && !startPos.isEmpty()) {
                    totalCombinations *= startPos.size();
                    countedStartApps.add(startApp);
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