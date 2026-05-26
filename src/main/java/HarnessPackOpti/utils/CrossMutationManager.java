package HarnessPackOpti.utils;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.DecimalFormat;
import java.util.*;

/**
 * 交叉变异管理器
 * 对不同扰动类型的方案进行两两交叉组合，增加样本多样性
 * 
 * 交叉策略：
 * - CONNECT × BREAK   : 继承连接关系的loopInfos + 通断状态的edges
 * - CONNECT × LOCATION: 继承连接关系的loopInfos + 用电器位置的appPositions
 * - BREAK × LOCATION  : 继承通断状态的edges + 用电器位置的appPositions
 * - LENGTH × CONNECT  : CONNECT的拓扑 + 对结果施加长度扰动
 * - LENGTH × BREAK    : BREAK的拓扑 + 对结果施加长度扰动
 * - LOCATION × CONNECT: 继承用电器位置的appPositions + 连接关系的loopInfos
 */
public class CrossMutationManager {

    private final Random random = new Random();
    private final int crossoverCountPerPair;

    public CrossMutationManager(int crossoverCountPerPair) {
        this.crossoverCountPerPair = crossoverCountPerPair;
    }

    /**
     * 执行交叉变异
     *
     * @param connectSamples   连接关系扰动样本 [{jsonMapCopy, serviceableStatue}, ...]
     * @param breakSamples     通断扰动样本
     * @param lengthSamples    长度扰动样本
     * @param locationSamples  用电器位置扰动样本
     * @param normList         分支ID列表
     * @param edges            原始边信息
     * @param jsonMap          原始JSON数据
     * @param appPositions     用电器位置
     * @param eleclection      电器选择
     * @param mutexMap         互斥关系
     * @param chooseOneList    多选一列表
     * @param togetherBCList   组团列表
     * @param elecFixedLocationLibrary 电器位置库
     * @param filePath         输出文件路径
     * @throws Exception
     */
    public void performCrossMutation(
            List<Map<String, Object>> connectSamples,
            List<Map<String, Object>> breakSamples,
            List<Map<String, Object>> lengthSamples,
            List<Map<String, Object>> locationSamples,
            List<String> normList,
            List<Map<String, Object>> edges,
            Map<String, Object> jsonMap,
            List<Map<String, String>> appPositions,
            Map<String, String> eleclection,
            Map<String, Map<String, List<String>>> mutexMap,
            List<Map<String, List<String>>> chooseOneList,
            List<List<String>> togetherBCList,
            Map<String, Map<String, String>> elecFixedLocationLibrary,
            String filePath) throws Exception {

        HarnessBranchTopoOptimize harnessBranchTopoOptimize = new HarnessBranchTopoOptimize();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();

        List<Map<String, String>> pointList = (List<Map<String, String>>) jsonMap.get("points");

        // 定义交叉配对: {类型A, 类型B, A的样本池, B的样本池}
        Object[][] pairConfigs = {
            {PerturbType.CONNECT, PerturbType.BREAK,    connectSamples, breakSamples},
            {PerturbType.CONNECT, PerturbType.LOCATION, connectSamples, locationSamples},
            {PerturbType.BREAK,    PerturbType.LOCATION, breakSamples,    locationSamples},
            {PerturbType.LENGTH,   PerturbType.CONNECT,  lengthSamples,   connectSamples},
            {PerturbType.LENGTH,   PerturbType.BREAK,    lengthSamples,   breakSamples},
            {PerturbType.LOCATION, PerturbType.CONNECT,  locationSamples, connectSamples},
        };

        for (Object[] config : pairConfigs) {
            PerturbType typeA = (PerturbType) config[0];
            PerturbType typeB = (PerturbType) config[1];
            List<Map<String, Object>> poolA = (List<Map<String, Object>>) config[2];
            List<Map<String, Object>> poolB = (List<Map<String, Object>>) config[3];

            if (poolA == null || poolA.isEmpty() || poolB == null || poolB.isEmpty()) {
                continue;
            }

            for (int i = 0; i < crossoverCountPerPair; i++) {
                Map<String, Object> sampleA = poolA.get(random.nextInt(poolA.size()));
                Map<String, Object> sampleB = poolB.get(random.nextInt(poolB.size()));

                // 构建交叉后的jsonMap
                Map<String, Object> crossedJsonMap = buildCrossedJsonMap(
                        sampleA, typeA, sampleB, typeB, jsonMap);

                // 使用交叉后的jsonMap重新计算回路信息
                String projectInfo = projectCircuitInfoOutput.projectCircuitInfoOutput(
                        objectMapper.writeValueAsString(crossedJsonMap));
                if (projectInfo == null || "".equals(projectInfo)) {
                    continue;
                }

                // 提取交叉后的分支状态用于约束检查
                List<String> crossedStatue = extractStatueList(crossedJsonMap, normList);
                List<Map<String, Object>> crossedEdges = (List<Map<String, Object>>) crossedJsonMap.get("edges");

                // 约束检查
                Boolean valid = harnessBranchTopoOptimize.checkFirstOption(
                        normList, crossedStatue, crossedEdges,
                        appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                if (!valid) {
                    continue;
                }

                Map<String, Object> resultMap = jsonToMap.TransJsonToMap(projectInfo);
                Map<String, Object> circuitInfo = (Map<String, Object>) resultMap.get("projectCircuitInfo");
                Float totalPrice = Float.parseFloat(circuitInfo.get("总成本").toString());
                Float totalLength = Float.parseFloat(circuitInfo.get("回路总长度").toString());
                Float totalWeight = Float.parseFloat(circuitInfo.get("回路总重量").toString());

                // 构建特征数组
                int[][] edgeIndex = buildEdgeIndex(crossedEdges);
                float[][] edgeAttr = buildEdgeAttr(crossedStatue, crossedEdges, typeA == PerturbType.LENGTH || typeB == PerturbType.LENGTH);
                float[][] x = buildNodeFeature(crossedJsonMap, crossedEdges, pointList, elecFixedLocationLibrary,
                        jsonToMap, objectMapper, projectCircuitInfoOutput);

                // 保存样本
                TypeCheckUtils.countType("cross_" + typeA + "_" + typeB);
                SampleSave.saveSample(edgeIndex, edgeAttr, x, filePath, totalPrice, totalLength, totalWeight);
            }
        }
    }

    /**
     * 构建交叉后的jsonMap：从sampleA继承typeA的修改域，从sampleB继承typeB的修改域
     */
    private Map<String, Object> buildCrossedJsonMap(
            Map<String, Object> sampleA, PerturbType typeA,
            Map<String, Object> sampleB, PerturbType typeB,
            Map<String, Object> jsonMap) {

        Map<String, Object> crossed = deepCopyJsonMap(jsonMap);

        // 从sampleA继承typeA的扰动域
        applyDomain(crossed, sampleA, typeA);
        // 从sampleB继承typeB的扰动域
        applyDomain(crossed, sampleB, typeB);

        return crossed;
    }

    /**
     * 将某个类型扰动的修改域应用到目标jsonMap
     */
    private void applyDomain(Map<String, Object> target, Map<String, Object> source, PerturbType type) {
        Map<String, Object> sourceJsonMap = (Map<String, Object>) source.get("jsonMapCopy");
        if (sourceJsonMap == null) {
            return;
        }
        switch (type) {
            case CONNECT:
                if (sourceJsonMap.containsKey("loopInfos")) {
                    target.put("loopInfos", deepCopyList((List<?>) sourceJsonMap.get("loopInfos")));
                }
                break;
            case BREAK:
                if (sourceJsonMap.containsKey("edges")) {
                    target.put("edges", deepCopyList((List<?>) sourceJsonMap.get("edges")));
                }
                break;
            case LENGTH:
                break;
            case LOCATION:
                if (sourceJsonMap.containsKey("appPositions")) {
                    target.put("appPositions", deepCopyList((List<?>) sourceJsonMap.get("appPositions")));
                }
                break;
        }
    }

    /**
     * 从交叉后的edges中提取分支状态列表
     */
    private List<String> extractStatueList(Map<String, Object> crossedJsonMap, List<String> normList) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) crossedJsonMap.get("edges");
        List<String> statueList = new ArrayList<>();
        for (String ignored : normList) {
            statueList.add("B");
        }
        for (Map<String, Object> edge : edges) {
            String branchId = edge.get("branchId").toString();
            String topologyStatusCode = edge.get("topologyStatusCode").toString();
            int index = normList.indexOf(branchId);
            if (index >= 0) {
                statueList.set(index, topologyStatusCode);
            }
        }
        return statueList;
    }

    /**
     * 构建边索引数组 [2, N]
     */
    private int[][] buildEdgeIndex(List<Map<String, Object>> crossedEdges) {
        Set<String> nameSet = new LinkedHashSet<>();
        List<String> startNames = new ArrayList<>();
        List<String> endNames = new ArrayList<>();
        for (Map<String, Object> edge : crossedEdges) {
            String start = edge.get("startPointName").toString();
            String end = edge.get("endPointName").toString();
            nameSet.add(start);
            nameSet.add(end);
            startNames.add(start);
            endNames.add(end);
        }
        List<String> allNames = new ArrayList<>(nameSet);
        int[][] edgeIndex = new int[2][crossedEdges.size()];
        for (int i = 0; i < crossedEdges.size(); i++) {
            edgeIndex[0][i] = allNames.indexOf(startNames.get(i));
            edgeIndex[1][i] = allNames.indexOf(endNames.get(i));
        }
        return edgeIndex;
    }

    /**
     * 构建分支特征数组 [N, 4]: [B状态, C状态, S状态, 分支长度]
     */
    private float[][] buildEdgeAttr(List<String> statueList, List<Map<String, Object>> crossedEdges,
                                    boolean applyLengthPerturbation) {
        int count = statueList.size();
        float[][] edgeAttr = new float[count][4];
        for (int i = 0; i < count; i++) {
            String s = statueList.get(i);
            switch (s) {
                case "B":
                    break;
                case "C":
                    edgeAttr[i][1] = 1.0f;
                    break;
                case "S":
                    edgeAttr[i][2] = 1.0f;
                    break;
            }
            edgeAttr[i][3] = getBranchLength(crossedEdges.get(i), applyLengthPerturbation);
        }
        return edgeAttr;
    }

    /**
     * 获取分支长度（可选长度扰动）
     */
    private float getBranchLength(Map<String, Object> edge, boolean perturb) {
        DecimalFormat df = new DecimalFormat("0.0000");
        float length = 0.0f;
        if (edge.get("length") != null && !edge.get("length").toString().isEmpty()) {
            length = Float.parseFloat(edge.get("length").toString());
        } else if (edge.get("referenceLength") != null && !edge.get("referenceLength").toString().isEmpty()) {
            length = Float.parseFloat(edge.get("referenceLength").toString());
        } else if ("C".equals(edge.get("topologyStatusCode")) || "S".equals(edge.get("topologyStatusCode"))) {
            length = 200;
        }
        if (perturb) {
            double factor = 0.75 + random.nextDouble() * 0.5;
            length = (float) (length * factor);
        }
        return Float.parseFloat(df.format(length));
    }

    /**
     * 构建节点特征矩阵 x [nodeCount, nodeCount + 1]
     */
    private float[][] buildNodeFeature(Map<String, Object> crossedJsonMap,
                                       List<Map<String, Object>> crossedEdges,
                                       List<Map<String, String>> pointList,
                                       Map<String, Map<String, String>> elecFixedLocationLibrary,
                                       JsonToMap jsonToMap, ObjectMapper objectMapper,
                                       ProjectCircuitInfoOutput projectCircuitInfoOutput) throws Exception {

        // 重新计算获得circuitInfo
        String projectInfo = projectCircuitInfoOutput.projectCircuitInfoOutput(
                objectMapper.writeValueAsString(crossedJsonMap));
        Map<String, Object> resultMap = jsonToMap.TransJsonToMap(projectInfo);
        List<Map<String, Object>> circuitList = (List<Map<String, Object>>) resultMap.get("circuitInfo");

        Set<String> nameSet = new LinkedHashSet<>();
        for (Map<String, Object> edge : crossedEdges) {
            nameSet.add(edge.get("startPointName").toString());
            nameSet.add(edge.get("endPointName").toString());
        }
        List<String> allNameList = new ArrayList<>(nameSet);
        int nodeCount = allNameList.size();
        float[][] x = new float[nodeCount][nodeCount + 1];

        Map<String, Float> circuitPrice = new HashMap<>();
        Map<String, Float> wetCost = new HashMap<>();

        for (Map<String, Object> objectMap : circuitList) {
            String startName = objectMap.get("起点用电器名称").toString();
            String endName = objectMap.get("终点用电器名称").toString();
            String wireType = objectMap.get("导线选型").toString();
            Map<String, String> materialsMsg = elecFixedLocationLibrary.get(wireType);
            if (materialsMsg == null) {
                continue;
            }
            String price = materialsMsg.get("导线单位商务价（元/米）");
            if (price == null) {
                continue;
            }
            if ((startName.startsWith("[") || endName.startsWith("["))
                    && objectMap.get("焊点位置名称") == null) {
                continue;
            }
            String startPos = startName.startsWith("[") ? objectMap.get("焊点位置名称").toString()
                    : objectMap.get("起点位置名称").toString();
            String endPos = endName.startsWith("[") ? objectMap.get("焊点位置名称").toString()
                    : objectMap.get("终点位置名称").toString();
            if (allNameList.indexOf(startPos) == -1 || allNameList.indexOf(endPos) == -1) {
                continue;
            }
            String key = startPos + ":" + endPos;
            Float existing = circuitPrice.get(key);
            circuitPrice.put(key, existing == null ? Float.parseFloat(price) : existing + Float.parseFloat(price));

            String startParam = SampleSave.getWaterParam(startPos, pointList);
            String endParam = SampleSave.getWaterParam(endPos, pointList);
            if ("w".equalsIgnoreCase(startParam) || "w".equalsIgnoreCase(endParam)) {
                Float wet = wetCost.get(startPos);
                wetCost.put(startPos, wet == null ? Float.parseFloat(price) : wet + Float.parseFloat(price));
            }
        }

        circuitPrice.forEach((k, v) -> {
            String[] parts = k.split(":");
            x[allNameList.indexOf(parts[0])][allNameList.indexOf(parts[1])] = v;
        });
        wetCost.forEach((k, v) -> x[allNameList.indexOf(k)][nodeCount] = v);

        return x;
    }

    private Map<String, Object> deepCopyJsonMap(Map<String, Object> original) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            if (entry.getValue() instanceof List) {
                copy.put(entry.getKey(), deepCopyList((List<?>) entry.getValue()));
            } else if (entry.getValue() instanceof Map) {
                copy.put(entry.getKey(), new HashMap<>((Map<?, ?>) entry.getValue()));
            } else {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private List<Map<String, Object>> deepCopyList(List<?> original) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Object item : original) {
            if (item instanceof Map) {
                copy.add(new HashMap<>((Map<String, Object>) item));
            }
        }
        return copy;
    }

    enum PerturbType {
        CONNECT, BREAK, LENGTH, LOCATION
    }
}