package HarnessPackOpti.Optimize.elec;

import HarnessPackOpti.Algorithm.FindBest;
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
    private static Integer TopNumber = 20;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    // 可变数量阈值，走枚举
    public static Integer caseNumbe = 10000;

    //生成初始样本数量限制
    public static Integer LessRandomSamleNumber = 20;

    // 方案去重仓库：存储已存在方案的指纹
    private Set<String> schemeFingerprintSet = new HashSet<>();


    // 枚举收集的所有方案
    private List<Map<String, String>> enumeratedSchemes = new ArrayList<>();

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance();
    }

    public List<Map<String, Object>> powerDriverOptimize(String jsonContent) throws Exception {
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
        //回路id-可连接的用电器列表
        Map<String, Set<String>> loopElecById = new HashMap<>();
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

        //统计约束list集合，方便后面判断回路是否有约束
        List<String> togetherList = new ArrayList<>();
        List<String> mutualList = new ArrayList<>();
        for (Map<String, String> loopInfo : loopInfos) {
            if ("主供电回路".equals(loopInfo.get("loopAttribute"))
                    || "配电回路".equals(loopInfo.get("loopAttribute"))) {
                elecLoopList.add(loopInfo);
            } else if ("驱动回路".equals(loopInfo.get("loopAttribute"))) {
                driveLoopList.add(loopInfo);
            }
            //回路可连接的终点用电器统计
            String s = loopInfo.get("specifyPoints");
            if (s != null && !s.isEmpty()) {
                for (String part : s.split(",")) {
                    String pointName = findNameById(part, points);
                    loopElecById.computeIfAbsent(loopInfo.get("id"), k -> new HashSet<>()).add(pointName);
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
            combinations = calculateOptimizationCombinations(combinedList, elecChangeablePosition, togetherGroup, loopElecById);
        }

        //优化驱动回路
        if ("1".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(driveLoopList, elecChangeablePosition, togetherGroup, loopElecById);
        }

        // 优化类型 2：配电器回路
        if ("2".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(elecLoopList, elecChangeablePosition, togetherGroup, loopElecById);
        }

        // 优化类型 3：所有回路
        if ("3".equals(optimizeType)) {
            combinations = calculateOptimizationCombinations(loopInfos, elecChangeablePosition, togetherGroup, loopElecById);
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
                Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);

                enumerateAllSchemes(targetLoops, elecChangeablePosition, togetherGroup, mutualGroup, loopInfos,loopElecById);

                System.out.println("枚举耗时: " + (System.currentTimeMillis() - enumerateTime) + "ms");

                // 每个方案格式: Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
                // 遍历所有方案
                for (int i = 0; i < enumeratedSchemes.size(); i++) {
                    Map<String, String> scheme = enumeratedSchemes.get(i);

                    //存储指定连接关系和用电器位置的数据以方便后续去重
                    List<Map<String,String>> connectionData = new ArrayList<>();
                    List<Map<String,String>> appPositionsData = new ArrayList<>();

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
                                Map<String, String> loopCopy = new HashMap<>();
                                loopCopy.put("id", loop.get("id"));
                                loopCopy.put("startApp", startApp);
                                loopCopy.put("endApp", endApp);
                                connectionData.add(loopCopy);
                            }
                        }

                        for (Map<String, String> stringStringMap : appPositionsCopy) {
                            if(stringStringMap.get("appName").equals(startApp)){
                                stringStringMap.put("unregularPointName", startPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(startPos));
                                Map<String, String> appPositionCopy = new HashMap<>();
                                appPositionCopy.put("appName", stringStringMap.get("appName"));
                                appPositionCopy.put("unregularPointName", stringStringMap.get("unregularPointName"));
                                appPositionsData.add(appPositionCopy);
                            }
                            if(stringStringMap.get("appName").equals(endApp)){
                                stringStringMap.put("unregularPointName", endPos);
                                stringStringMap.put("unregularPointId", pointNameId.get(endPos));
                                Map<String, String> appPositionCopy = new HashMap<>();
                                appPositionCopy.put("appName", stringStringMap.get("appName"));
                                appPositionCopy.put("unregularPointName", stringStringMap.get("unregularPointName"));
                                appPositionsData.add(appPositionCopy);
                            }
                        }
                    }
                    //字符串拼接(只传入用户需要优化的回路和用电器)
                    String fingerprint = generateSchemeFingerprint(connectionData, appPositionsData);
                    // 方案去重检查
                    if (schemeFingerprintSet.contains(fingerprint)) {
                        duplicateCount++;
                        continue; // 跳过重复方案
                    }
                    // 添加到去重仓库
                    schemeFingerprintSet.add(fingerprint);
                    validSchemeCount++;
                    //合成方案
                    jsonMapCopy.put("loopInfo", loopInfoCopy);
                    jsonMapCopy.put("appPositions", appPositionsCopy);
                }
                //计算成本
                String s = powerProjectCircuitInfoOutput.powerOptimize(jsonContent);
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
            System.out.println("重复方案数: " + duplicateCount);
            System.out.println("有效方案数: " + validSchemeCount);
            //枚举返回最优top20样本
            List<Map<String, Object>> topBest = findBest.findBest(resultList, "成本",TopNumber);
            return topBest;
        }

        //开始生成初代样本
        // 根据优化类型选择目标回路
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
                    loopElecById
            );

            System.out.println("初代样本生成耗时: " + (System.currentTimeMillis() - gaInitTime) + "ms");
            System.out.println("有效初代样本数: " + initialPopulation.size());

            // 找出top20
            if (!initialPopulation.isEmpty()) {
                List<Map<String, Object>> topBest = findBest.findBest(initialPopulation, "成本", TopNumber);
                System.out.println("Top " + TopNumber + " 最优样本已选出");
                return topBest;
            }
        }
        return null;
    }

    /**
     * 生成遗传算法的初代种群
     *
     * @param populationSize 种群大小
     * @param targetLoops 目标回路列表
     * @param allLoopInfos 所有回路信息（模板）
     * @param allAppPositions 所有用电器位置（模板）
     * @param elecChangeablePosition 用电器可变位置映射
     * @param togetherGroup 联动组配置
     * @param mutualGroup 互斥组配置
     * @param pointNameId 位置名称-ID映射
     * @param loopElecById 回路可连接的终点用电器列表
     * @return 初代种群列表
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
            Map<String, Set<String>> loopElecById) throws Exception {

        Random random = new Random();
        List<Map<String, Object>> population = new ArrayList<>();
        int maxAttempts = populationSize * 10; // 最大尝试次数，避免无限循环
        int attemptCount = 0;

        System.out.println("开始生成 " + populationSize + " 个初代个体...");

        while (population.size() < populationSize && attemptCount < maxAttempts) {
            attemptCount++;

            // 深拷贝模板数据
            List<Map<String, String>> loopInfoCopy = new ArrayList<>();
            for (Map<String, String> loop : allLoopInfos) {
                loopInfoCopy.add(new HashMap<>(loop));
            }
            List<Map<String, String>> appPositionsCopy = deepCopyAppPositions(allAppPositions);

            // Step 1: 处理有约束的回路（联动组、互斥组）- 同时扰动连接关系和位置
            boolean success = perturbConstrainedLoops(
                    loopInfoCopy,
                    appPositionsCopy,
                    targetLoops,
                    elecChangeablePosition,
                    togetherGroup,
                    mutualGroup,
                    pointNameId,
                    random,
                    loopElecById
            );

            if (!success) {
                continue; // 约束冲突，重新生成
            }

            // Step 2: 处理无约束的回路 - 同时扰动连接关系和位置
            perturbUnconstrainedLoops(
                    loopInfoCopy,
                    appPositionsCopy,
                    targetLoops,
                    elecChangeablePosition,
                    pointNameId,
                    random,
                    loopElecById
            );

            // Step 3: 检查方案是否重复
            String s = generateSchemeFingerprint(loopInfoCopy, appPositionsCopy);
            if (schemeFingerprintSet.contains(s)) {
                continue; // 重复方案，跳过
            }

            // Step 4: 添加到去重仓库
            schemeFingerprintSet.add(s);

            // Step 5: 构建完整的JSON方案并计算成本
            Map<String, Object> tempJsonMap = new HashMap<>();
            tempJsonMap.put("loopInfos", loopInfoCopy);
            tempJsonMap.put("appPositions", appPositionsCopy);
            tempJsonMap.put("topoInfo", topoInfoMap);
            tempJsonMap.put("projectInfo", projectInfo);

            String schemeJson = objectMapper.writeValueAsString(tempJsonMap);

            try {
                // 计算成本
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
                    map.put("finishStatue", "abnormal");
                    map.put("initializationScheme", false);
                    map.put("schemeIndex", population.size() + 1);

                    population.add(map);

                    if (population.size() % 10 == 0) {
                        System.out.println("已生成 " + population.size() + " 个有效个体...");
                    }
                }
            } catch (Exception e) {
                System.err.println("方案计算失败，跳过: " + e.getMessage());
                continue;
            }
        }

        System.out.println("初代种群生成完成，共 " + population.size() + " 个个体，尝试次数: " + attemptCount);
        return population;
    }


    /**
     * 扰动有约束的回路（联动组、互斥组、组团内互斥）
     * 关键规则：
     * 1. 同时扰动连接关系（endApp）和用电器位置
     * 2. 如果联动组中有任意一个目标回路，则该组所有回路都要一起变化（endApp必须相同）
     * 3. 互斥组同理，只要有一个目标回路，组内所有回路的endApp对应的位置必须不同
     *
     * @return true-成功应用约束，false-约束冲突
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

        // 构建 targetLoops 的 ID 集合，用于快速判断是否为目标回路
        Set<String> targetLoopIdSet = new HashSet<>();
        for (Map<String, String> targetLoop : targetLoops) {
            targetLoopIdSet.add(targetLoop.get("id"));
        }

        // 构建 loopId -> loopInfo 的快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 记录每个互斥组已选择的位置（用于互斥检查）
        Map<String, Set<String>> mutualGroupUsedPositions = new HashMap<>();

        // Step 1: 处理联动组（组团）
        // 规则：如果组内有任意一个目标回路，则整个组的所有回路endApp必须相同，位置也必须相同
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();

            // 检查该组团中是否有目标回路
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }

            // 如果该组团中没有目标回路，跳过整个组
            if (!hasTargetLoop) {
                continue;
            }

            // 【关键】收集所有成员的可选endApp列表，计算交集
            Set<String> endAppIntersection = null;
            for (String loopId : allMemberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(loopId);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
            }

            if (endAppIntersection == null || endAppIntersection.isEmpty()) {
                return false;
            }

            // 从交集中随机选择一个endApp
            List<String> endAppList = new ArrayList<>(endAppIntersection);
            String selectedEndApp = endAppList.get(random.nextInt(endAppList.size()));

            // 获取该endApp的可变位置列表
            List<String> positions = elecChangeablePosition.get(selectedEndApp);
            if (positions == null || positions.isEmpty()) {
                return false;
            }

            // 从位置列表中随机选择一个位置
            String selectedPosition = positions.get(random.nextInt(positions.size()));

            // 检查互斥约束：如果该组团属于某个互斥组
            String mutualId = null;
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop != null) {
                    mutualId = loop.get("mutualExclusion");
                    if (mutualId != null && !mutualId.isEmpty()) {
                        break;
                    }
                }
            }

            if (mutualId != null) {
                Set<String> usedPositions = mutualGroupUsedPositions.computeIfAbsent(mutualId, k -> new HashSet<>());
                if (usedPositions.contains(selectedPosition)) {
                    return false; // 位置已被占用，冲突
                }
                usedPositions.add(selectedPosition);
            }

            // 【关键】更新整个组所有回路的endApp和位置
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                // 更新回路的endApp（连接关系）
                loop.put("endApp", selectedEndApp);

                // 更新终点用电器位置
                for (Map<String, String> appPos : appPositionsCopy) {
                    if (appPos.get("appName").equals(selectedEndApp)) {
                        appPos.put("unregularPointName", selectedPosition);
                        appPos.put("unregularPointId", pointNameId.get(selectedPosition));
                        break;
                    }
                }
            }
        }

        // Step 2: 处理独立的互斥回路（不在任何联动组中的互斥回路）
        for (Map.Entry<String, List<String>> entry : mutualGroup.entrySet()) {
            String mutualId = entry.getKey();
            List<String> allMemberLoopIds = entry.getValue();

            // 检查该互斥组中是否有目标回路
            boolean hasTargetLoop = false;
            for (String loopId : allMemberLoopIds) {
                if (targetLoopIdSet.contains(loopId)) {
                    hasTargetLoop = true;
                    break;
                }
            }

            if (!hasTargetLoop) {
                continue;
            }

            Set<String> usedPositions = new HashSet<>();

            // 先收集所有已在联动组中处理的成员已占用的位置
            for (String loopId : allMemberLoopIds) {
                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }

                if (inTogetherGroup) {
                    String endApp = loop.get("endApp");
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(endApp) &&
                                appPos.get("unregularPointName") != null) {
                            usedPositions.add(appPos.get("unregularPointName"));
                            break;
                        }
                    }
                }
            }

            // 对所有未在处理联动组的成员进行endApp和位置分配
            for (String loopId : allMemberLoopIds) {
                boolean inTogetherGroup = false;
                for (List<String> groupMembers : togetherGroup.values()) {
                    if (groupMembers.contains(loopId)) {
                        inTogetherGroup = true;
                        break;
                    }
                }
                if (inTogetherGroup) continue; // 已在联动组中处理过

                Map<String, String> loop = loopById.get(loopId);
                if (loop == null) continue;

                // 获取该回路可选的endApp列表
                Set<String> allowedEndApps = loopElecById.get(loopId);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    allowedEndApps = Collections.singleton(loop.get("endApp"));
                }

                // 尝试为每个可选endApp找到一个可用位置
                boolean assigned = false;
                List<String> endAppCandidates = new ArrayList<>(allowedEndApps);
                Collections.shuffle(endAppCandidates, random); // 随机打乱顺序

                for (String endApp : endAppCandidates) {
                    List<String> positions = elecChangeablePosition.get(endApp);
                    if (positions == null || positions.isEmpty()) continue;

                    // 过滤掉已被占用的位置
                    List<String> availablePositions = new ArrayList<>();
                    for (String pos : positions) {
                        if (!usedPositions.contains(pos)) {
                            availablePositions.add(pos);
                        }
                    }

                    if (!availablePositions.isEmpty()) {
                        // 随机选择一个位置
                        String chosenPosition = availablePositions.get(random.nextInt(availablePositions.size()));

                        // 更新回路的endApp
                        loop.put("endApp", endApp);

                        // 更新用电器位置
                        for (Map<String, String> appPos : appPositionsCopy) {
                            if (appPos.get("appName").equals(endApp)) {
                                appPos.put("unregularPointName", chosenPosition);
                                appPos.put("unregularPointId", pointNameId.get(chosenPosition));
                                break;
                            }
                        }

                        usedPositions.add(chosenPosition);
                        assigned = true;
                        break;
                    }
                }

                if (!assigned) {
                    return false; // 无法找到合适的位置，冲突
                }
            }
        }

        return true;
    }


    /**
     * 生成方案的唯一指纹（用于去重检查）
     * 指纹包含两部分：
     * 1. 回路连接关系：loopId -> startApp|endApp
     * 2. 用电器位置：appName -> unregularPointName
     *
     * @param loopInfos 回路信息列表
     * @param appPositions 用电器位置列表
     * @return 方案指纹字符串
     */
    private String generateSchemeFingerprint(List<Map<String, String>> loopInfos,
                                             List<Map<String, String>> appPositions) {
        StringBuilder fingerprint = new StringBuilder();

        // 第一部分：回路连接关系（按回路ID排序，确保顺序一致性）
        List<Map<String, String>> sortedLoops = new ArrayList<>(loopInfos);
        sortedLoops.sort((a, b) -> a.get("id").compareTo(b.get("id")));

        fingerprint.append("LOOPS:");
        for (Map<String, String> loop : sortedLoops) {
            String loopId = loop.get("id");
            String startApp = loop.get("startApp");
            String endApp = loop.get("endApp");
            // 只关注发生变化的回路
            if (startApp != null && endApp != null) {
                fingerprint.append(loopId)
                        .append("=")
                        .append(startApp)
                        .append("|")
                        .append(endApp)
                        .append(";");
            }
        }

        // 第二部分：用电器位置信息（按appName排序）
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
            // 只记录有位置信息的用电器
            if (appName != null && position != null && !position.isEmpty()) {
                fingerprint.append(appName)
                        .append("=")
                        .append(position)
                        .append(";");
            }
        }

        return fingerprint.toString();
    }

    /**
     * 扰动无约束的回路（同时扰动连接关系和位置）
     */
    private void perturbUnconstrainedLoops(
            List<Map<String, String>> loopInfoCopy,
            List<Map<String, String>> appPositionsCopy,
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, String> pointNameId,
            Random random,
            Map<String, Set<String>> loopElecById) {

        // 构建 loopId -> loopInfo 的快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> loop : loopInfoCopy) {
            loopById.put(loop.get("id"), loop);
        }

        // 遍历所有目标回路，对未设置的回路进行随机扰动
        for (Map<String, String> targetLoop : targetLoops) {
            String loopId = targetLoop.get("id");
            Map<String, String> loop = loopById.get(loopId);

            if (loop == null) continue;

            // 检查该回路是否已经被约束处理过（有联动组或互斥组标记）
            String together = loop.get("changeTogether");
            String mutual = loop.get("mutualExclusion");
            if ((together != null && !together.isEmpty()) || (mutual != null && !mutual.isEmpty())) {
                continue; // 已被约束处理，跳过
            }

            String startApp = loop.get("startApp");

            // 【关键】扰动终点用电器的连接关系（endApp）
            Set<String> allowedEndApps = loopElecById.get(loopId);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                List<String> endAppCandidates = new ArrayList<>(allowedEndApps);
                String newEndApp = endAppCandidates.get(random.nextInt(endAppCandidates.size()));
                loop.put("endApp", newEndApp);
            }

            String endApp = loop.get("endApp");

            // 1. 扰动起点用电器位置
            if (startApp != null) {
                List<String> startPositions = elecChangeablePosition.get(startApp);
                if (startPositions != null && !startPositions.isEmpty()) {
                    String randomStartPosition = startPositions.get(random.nextInt(startPositions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(startApp)) {
                            appPos.put("unregularPointName", randomStartPosition);
                            appPos.put("unregularPointId", pointNameId.get(randomStartPosition));
                            break;
                        }
                    }
                }
            }

            // 2. 扰动终点用电器位置
            if (endApp != null) {
                List<String> positions = elecChangeablePosition.get(endApp);
                if (positions != null && !positions.isEmpty()) {
                    String randomPosition = positions.get(random.nextInt(positions.size()));
                    for (Map<String, String> appPos : appPositionsCopy) {
                        if (appPos.get("appName").equals(endApp)) {
                            appPos.put("unregularPointName", randomPosition);
                            appPos.put("unregularPointId", pointNameId.get(randomPosition));
                            break;
                        }
                    }
                }
            }
        }
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
     * @param loopElecById          回路可连接的终点用电器列表
     */
    private void enumerateAllSchemes(
            List<Map<String, String>> targetLoops,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, List<String>> mutualGroup,
            List<Map<String, String>> allLoopInfos,
            Map<String, Set<String>> loopElecById) {

        long startTime = System.currentTimeMillis();

        // loopId → loopInfo 快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : allLoopInfos) {
            loopById.put(lp.get("id"), lp);
        }

        // --------------------------------------------------------
        // Step 1: 构建"变量"列表 - 只包含 endApp 选择变量
        //
        // 【关键】约束只针对 endApp，不包含位置
        // --------------------------------------------------------
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();

        // 处理联动组：组内所有回路共享一个 endApp 选择变量
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();

            // 计算所有成员可选 endApp 的交集
            Set<String> endAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(lid);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
                coveredLoopIds.add(lid);
            }

            // endApp 选择变量（值是 endApp 名称，不是位置）
            if (endAppIntersection != null && !endAppIntersection.isEmpty()) {
                varDomains.put("E_G_" + groupId, new ArrayList<>(endAppIntersection));
            }
        }

        // 处理独立回路的 endApp 选择变量
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid)) continue;

            Set<String> allowedEndApps = loopElecById.get(lid);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                varDomains.put("E_L_" + lid, new ArrayList<>(allowedEndApps));
            } else {
                // 如果没有指定，使用当前 endApp
                varDomains.put("E_L_" + lid, Collections.singletonList(lp.get("endApp")));
            }
        }

        // --------------------------------------------------------
        // Step 2: 变量 → 互斥组映射
        // 互斥的是 endApp 选择变量
        // --------------------------------------------------------
        Map<String, List<String>> varKeyToMutualIds = new LinkedHashMap<>();
        for (Map<String, String> lp : targetLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");

            if (mutual == null || mutual.isEmpty()) continue;

            // 互斥的是 endApp 选择变量
            String vk = (together != null && !together.isEmpty())
                    ? "E_G_" + together
                    : "E_L_" + lid;

            varKeyToMutualIds.computeIfAbsent(vk, k -> new ArrayList<>()).add(mutual);
        }

        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : varKeyToMutualIds.entrySet()) {
            String varKey = e.getKey();
            for (String mutualId : e.getValue()) {
                List<String> varList = mutualIdToVarKeys.computeIfAbsent(mutualId, k -> new ArrayList<>());
                if (!varList.contains(varKey)) {
                    varList.add(varKey);
                }
            }
        }
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualIds.keySet());

        // --------------------------------------------------------
        // Step 3: 准备变量列表
        // --------------------------------------------------------
        List<String> varKeys = new ArrayList<>(varDomains.keySet());

        System.out.println("开始回溯枚举，变量数: " + varKeys.size());

        // --------------------------------------------------------
        // Step 4: 执行回溯枚举
        // --------------------------------------------------------
        Map<String, String> currentAssignment = new LinkedHashMap<>();
        Set<String> usedEndApps = new HashSet<>(); // 用于互斥检查：记录已使用的 endApp

        enumerateSchemesByBacktrack(
                varDomains, mutualIdToVarKeys, varsInAnyMutualGroup,
                0, varKeys, currentAssignment, usedEndApps,
                targetLoops, loopById, elecChangeablePosition);

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
            Set<String> usedEndApps, // 改为 usedEndApps
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition) {

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
                    currentAssignment, targetLoops, loopById, elecChangeablePosition);

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

        for (String endApp : domain) {
            // 互斥约束检查：检查 endApp 是否已被占用
            if (isInMutualGroup && usedEndApps.contains(endApp)) {
                continue;
            }

            // 做选择
            currentAssignment.put(varKey, endApp);
            usedEndApps.add(endApp);

            // 递归处理下一个变量
            enumerateSchemesByBacktrack(
                    varDomains, mutualIdToVarKeys, varsInMutualGroup,
                    varIndex + 1, varKeys, currentAssignment, usedEndApps,
                    targetLoops, loopById, elecChangeablePosition);

            // 如果已经达到限制，提前退出
            if (enumeratedSchemes.size() >= caseNumbe) {
                break;
            }

            // 撤销选择
            usedEndApps.remove(endApp);
            currentAssignment.remove(varKey);
        }
    }

    /**
     * 将变量赋值转换为方案格式
     *
     * @return Map<回路ID, "起点用电器|终点用电器|起点位置|终点位置">
     *         包含所有发生变化的回路（目标回路 + 受约束影响的外部回路）
     */
    private Map<String, String> convertAssignmentToSchemeFormat(
            Map<String, String> assignment,
            List<Map<String, String>> targetLoops,
            Map<String, Map<String, String>> loopById,
            Map<String, List<String>> elecChangeablePosition) {

        Map<String, String> scheme = new LinkedHashMap<>();
        Random random = new Random();

        // 【关键修复】收集所有需要加入方案的回路ID
        Set<String> affectedLoopIds = new HashSet<>();

        // 1. 添加所有目标回路
        for (Map<String, String> loop : targetLoops) {
            affectedLoopIds.add(loop.get("id"));
        }

        // 2. 添加与目标回路在同一联动组的所有回路
        for (Map<String, String> loop : targetLoops) {
            String together = loop.get("changeTogether");
            if (together != null && !together.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    String loopTogether = allLoop.get("changeTogether");
                    if (together.equals(loopTogether)) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }

            // 3. 添加与目标回路互斥的所有回路
            String mutual = loop.get("mutualExclusion");
            if (mutual != null && !mutual.isEmpty()) {
                for (Map<String, String> allLoop : loopById.values()) {
                    String loopMutual = allLoop.get("mutualExclusion");
                    if (mutual.equals(loopMutual)) {
                        affectedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
        }

        // 遍历所有受影响的回路，生成方案
        for (String loopId : affectedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop == null) continue;

            String startApp = loop.get("startApp");
            String together = loop.get("changeTogether");

            // 【关键】获取该回路的 endApp 选择
            String selectedEndApp = null;
            if (together != null && !together.isEmpty()) {
                // 从联动组的 endApp 变量中获取
                selectedEndApp = assignment.get("E_G_" + together);
            } else {
                // 从独立回路的 endApp 变量中获取
                selectedEndApp = assignment.get("E_L_" + loopId);
            }

            // 如果没有赋值，使用原始值
            if (selectedEndApp == null) {
                selectedEndApp = loop.get("endApp");
            }

            // 【关键】为选定的 endApp 随机选择一个位置
            String assignedEndPosition = null;
            if (selectedEndApp != null) {
                List<String> positions = elecChangeablePosition.get(selectedEndApp);
                if (positions != null && !positions.isEmpty()) {
                    assignedEndPosition = positions.get(random.nextInt(positions.size()));
                }
            }

            // 为起点用电器随机选择一个位置
            String assignedStartPosition = null;
            if (startApp != null && !startApp.isEmpty()) {
                List<String> startPositions = elecChangeablePosition.get(startApp);
                if (startPositions != null && !startPositions.isEmpty()) {
                    assignedStartPosition = startPositions.get(random.nextInt(startPositions.size()));
                }
            }

            // 如果位置有赋值，则加入方案
            if (assignedStartPosition != null || assignedEndPosition != null) {
                String finalStartPos = assignedStartPosition != null ? assignedStartPosition : "";
                String finalEndPos = assignedEndPosition != null ? assignedEndPosition : "";

                // 格式: "起点用电器|终点用电器|起点位置|终点位置"
                String value = startApp + "|" + selectedEndApp + "|" + finalStartPos + "|" + finalEndPos;

                scheme.put(loopId, value);
            }
        }

        return scheme;
    }



    private long calculateOptimizationCombinations(
            List<Map<String, String>> loopInfos,
            Map<String, List<String>> elecChangeablePosition,
            Map<String, List<String>> togetherGroup,
            Map<String, Set<String>> loopElecById) {

        long calcStart = System.currentTimeMillis();

        // loopId → loopInfo 快速查找
        Map<String, Map<String, String>> loopById = new HashMap<>();
        for (Map<String, String> lp : loopInfos) {
            loopById.put(lp.get("id"), lp);
        }

        // --------------------------------------------------------
        // 【关键修复】Step 0: 扩展回路列表，包含所有受约束影响的外部回路
        // --------------------------------------------------------
        Set<String> extendedLoopIds = new HashSet<>();

        // 1. 添加所有目标回路
        for (Map<String, String> lp : loopInfos) {
            extendedLoopIds.add(lp.get("id"));
        }

        // 2. 添加与目标回路在同一联动组的所有回路
        for (Map<String, String> lp : loopInfos) {
            String together = lp.get("changeTogether");
            if (together != null && !together.isEmpty()) {
                List<String> groupMembers = togetherGroup.get(together);
                if (groupMembers != null) {
                    extendedLoopIds.addAll(groupMembers);
                }
            }

            // 3. 添加与目标回路互斥的所有回路
            String mutual = lp.get("mutualExclusion");
            if (mutual != null && !mutual.isEmpty()) {
                // 需要从 loopById 中查找所有同互斥组的回路
                for (Map<String, String> allLoop : loopById.values()) {
                    String loopMutual = allLoop.get("mutualExclusion");
                    if (mutual.equals(loopMutual)) {
                        extendedLoopIds.add(allLoop.get("id"));
                    }
                }
            }
        }

        // 构建扩展后的回路列表
        List<Map<String, String>> extendedLoops = new ArrayList<>();
        for (String loopId : extendedLoopIds) {
            Map<String, String> loop = loopById.get(loopId);
            if (loop != null) {
                extendedLoops.add(loop);
            }
        }

        // --------------------------------------------------------
        // Step 1: 构建"变量"列表 - 只包含 endApp 选择变量
        //
        // 规则：
        //   联动组（changeTogether）→ 合并为 1 个变量
        //     该变量的域 = 组内所有回路可选 endApp 的【交集】
        //   独立回路（无 changeTogether）→ 1 个变量
        //     该变量的域 = 该回路可选 endApp 列表
        //
        // 变量 key 格式：
        //   联动组变量  → "E_G_<togetherGroupId>"
        //   独立回路变量 → "E_L_<loopId>"
        // --------------------------------------------------------
        Map<String, List<String>> varDomains = new LinkedHashMap<>();
        Set<String> coveredLoopIds = new HashSet<>();

        // 处理联动组：取所有成员可选 endApp 的交集
        for (Map.Entry<String, List<String>> entry : togetherGroup.entrySet()) {
            String groupId = entry.getKey();
            List<String> memberLoopIds = entry.getValue();

            // Step 1.1: 收集所有成员的可选 endApp 列表，取交集
            Set<String> endAppIntersection = null;
            for (String lid : memberLoopIds) {
                Set<String> allowedEndApps = loopElecById.get(lid);
                if (allowedEndApps == null || allowedEndApps.isEmpty()) {
                    // 如果没有指定可选 endApp，使用当前 endApp
                    Map<String, String> lp = loopById.get(lid);
                    if (lp != null && lp.get("endApp") != null) {
                        allowedEndApps = Collections.singleton(lp.get("endApp"));
                    } else {
                        allowedEndApps = Collections.emptySet();
                    }
                }

                if (endAppIntersection == null) {
                    endAppIntersection = new HashSet<>(allowedEndApps);
                } else {
                    endAppIntersection.retainAll(allowedEndApps);
                }
                coveredLoopIds.add(lid);
            }

            varDomains.put("E_G_" + groupId,
                    endAppIntersection != null ? new ArrayList<>(endAppIntersection) : Collections.emptyList());
        }

        // 处理独立回路（不在任何联动组中）
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            if (coveredLoopIds.contains(lid)) continue;

            // 获取该回路可选的 endApp 列表
            Set<String> allowedEndApps = loopElecById.get(lid);
            if (allowedEndApps != null && !allowedEndApps.isEmpty()) {
                varDomains.put("E_L_" + lid, new ArrayList<>(allowedEndApps));
            } else {
                // 如果没有指定可选 endApp，使用当前 endApp
                varDomains.put("E_L_" + lid, Collections.singletonList(lp.get("endApp")));
            }
        }

        // --------------------------------------------------------
        // Step 2: 变量 → 互斥组映射
        //
        // 一条回路如果有 mutualExclusion 标记：
        //   若它属于某个联动组 → 整个联动组变量参与互斥
        //   否则 → 该回路自己的变量参与互斥
        //
        // 注意：同一个变量可能属于多个互斥组
        // --------------------------------------------------------
        Map<String, List<String>> varKeyToMutualIds = new LinkedHashMap<>();
        for (Map<String, String> lp : extendedLoops) {
            String lid = lp.get("id");
            String mutual = lp.get("mutualExclusion");
            String together = lp.get("changeTogether");

            if (mutual == null || mutual.isEmpty()) continue;

            // 确定变量key
            String vk = (together != null && !together.isEmpty())
                    ? "E_G_" + together
                    : "E_L_" + lid;

            // 添加到互斥组列表（允许一个变量属于多个互斥组）
            varKeyToMutualIds.computeIfAbsent(vk, k -> new ArrayList<>()).add(mutual);
        }

        // 互斥组 → 变量列表（去重，保持唯一）
        Map<String, List<String>> mutualIdToVarKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : varKeyToMutualIds.entrySet()) {
            String varKey = e.getKey();
            for (String mutualId : e.getValue()) {
                // 避免同一个变量在同一个互斥组中重复添加
                List<String> varList = mutualIdToVarKeys.computeIfAbsent(mutualId, k -> new ArrayList<>());
                if (!varList.contains(varKey)) {
                    varList.add(varKey);
                }
            }
        }

        // 收集所有参与互斥的变量
        Set<String> varsInAnyMutualGroup = new HashSet<>(varKeyToMutualIds.keySet());

        // --------------------------------------------------------
        // Step 3: 计算总方案数
        //
        // 3a. 独立变量（不在任何互斥组中）→ 直接乘以域大小
        // 3b. 互斥组（可能含联动组变量）→ 回溯法计算"两两不同 endApp"的赋值数
        // 3c. 用电器位置（每个唯一 endApp 和 startApp 独立计算，相乘）
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

        // 3b: 互斥组 —— 回溯计算全不同 endApp 赋值数
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

        // 3c: 用电器位置（同一用电器作为起点或终点时只统计一次）
        if (totalCombinations > 0) {
            // 收集所有可能被选择的用电器（包括 startApp 和 endApp）
            Set<String> allPossibleApps = new HashSet<>();

            // 添加所有 startApp
            for (Map<String, String> lp : extendedLoops) {
                String startApp = lp.get("startApp");
                if (startApp != null && !startApp.isEmpty()) {
                    allPossibleApps.add(startApp);
                }
            }

            // 添加所有可能被选择的 endApp（从变量域中提取）
            for (List<String> domain : varDomains.values()) {
                allPossibleApps.addAll(domain);
            }

            // 计算位置组合数
            for (String appName : allPossibleApps) {
                List<String> positions = elecChangeablePosition.get(appName);
                if (positions != null && !positions.isEmpty()) {
                    totalCombinations *= positions.size();
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