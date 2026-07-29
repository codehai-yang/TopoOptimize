package HarnessPackOpti.DiagnoseLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配电驱动检查
 */
public class PowerDistributionDriveLibrary {
    /** 变种组合数超过此阈值则提示 */
    private static final long VARIANT_COUNT_THRESHOLD = 10_000_000L;

    /**
     * 回路属性缺失
     * @param circuitList
     * @return
     */
    public List<String> loopAttrLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路属性") && (objectMap.get("回路属性") == null || objectMap.get("回路属性").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    //     所属系统缺失
    public List<String> systemBelongLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("所属系统")  && (objectMap.get("所属系统") == null || objectMap.get("所属系统").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    //    回路信号名缺失
    public List<String> powerSignalLack(List<Map<String, Object>> circuitList) {
        List<String> infoNameLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路信号名") && (objectMap.get("回路信号名") == null || objectMap.get("回路信号名").toString().isEmpty())) {
                infoNameLack.add(objectMap.get("回路id").toString());
            }
        }
        return infoNameLack;
    }
    //    回路导线选型缺失
    public List<String> wireTypeLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路导线选型") && (objectMap.get("回路导线选型") == null || objectMap.get("回路导线选型").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    // 回路起点用电器缺失
    public List<String> strElecLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路起点用电器") && (objectMap.get("回路起点用电器") == null || objectMap.get("回路起点用电器").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }


    //    回路终点用电器缺失
    public List<String> endElecLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路终点用电器") && (objectMap.get("回路终点用电器") == null || objectMap.get("回路终点用电器").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    public List<String> startPositionCheck(List<Map<String, Object>> circuitList) throws Exception {
        List<String> circuitPropertyList = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if(objectMap.get("起点电器件可连接的终点电器件")==null || objectMap.get("起点电器件可连接的终点电器件").toString().isEmpty()){
                circuitPropertyList.add(objectMap.get("回路id").toString());
            }
        }
        return circuitPropertyList;
    }
    public List<String> endPositionCheck(List<Map<String, Object>> circuitList) throws Exception {
        List<String> circuitPropertyList = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if(objectMap.get("终点电器件可连接的起点电器件")==null || objectMap.get("终点电器件可连接的起点电器件").toString().isEmpty()){
                circuitPropertyList.add(objectMap.get("回路id").toString());
            }
        }
        return circuitPropertyList;
    }

    public List<String> teamAndExclusiveConflict(List<Map<String, Object>> circuitList) {
        List<String> conflictIds = new ArrayList<>();

        // 1. 按组队分组，统计每组的互斥约束数量
        Map<String, List<String>> teamExclusiveMap = new java.util.HashMap<>();

        for (Map<String, Object> circuit : circuitList) {
            String teamConnRel = getStringValue(circuit, "组队连接关系");
            String exclusiveConnRel = getStringValue(circuit, "互斥连接关系");

            // 只有同时有组队和互斥约束的回路才需要检查
            if (!teamConnRel.isEmpty() && !exclusiveConnRel.isEmpty()) {
                teamExclusiveMap.computeIfAbsent(teamConnRel, k -> new ArrayList<>())
                        .add(exclusiveConnRel);
            }
        }

        // 2. 检查每组的互斥约束是否超过1个（不同的互斥组算多个）
        Map<String, Integer> teamConflictCount = new java.util.HashMap<>();

        for (Map.Entry<String, List<String>> entry : teamExclusiveMap.entrySet()) {
            String team = entry.getKey();
            List<String> exclusiveList = entry.getValue();

            // 统计不同的互斥组数量
            java.util.Set<String> uniqueExclusive = new java.util.HashSet<>(exclusiveList);

            // 如果组队成员>1且互斥组数量>1，说明有矛盾
            if (exclusiveList.size() > 1 && uniqueExclusive.size() > 1) {
                teamConflictCount.put(team, uniqueExclusive.size());
            }
        }

        // 3. 找出所有有矛盾的回路
        for (Map<String, Object> circuit : circuitList) {
            String teamConnRel = getStringValue(circuit, "组队连接关系");
            String exclusiveConnRel = getStringValue(circuit, "互斥连接关系");

            // 如果该回路所在的组队有矛盾，且该回路有互斥约束
            if (!teamConnRel.isEmpty() && !exclusiveConnRel.isEmpty()
                    && teamConflictCount.containsKey(teamConnRel)) {
                conflictIds.add(getStringValue(circuit, "回路id"));
            }
        }

        return conflictIds;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        if (map.containsKey(key) && map.get(key) != null) {
            String value = map.get(key).toString().trim();
            return value.isEmpty() ? "" : value;
        }
        return "";
    }

    public List<String> powerCircuitError(List<Map<String, Object>> circuitList) throws Exception {
        List<String> circuitPropertyList = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if(objectMap.get("回路属性")!=null && !objectMap.get("回路属性").toString().isEmpty() && !objectMap.get("回路属性").equals("主供电回路") && !objectMap.get("回路属性").equals("配电回路")){
                circuitPropertyList.add(objectMap.get("回路id").toString());
            }
        }
        return circuitPropertyList;
    }




    public List<String> driverCircuitError(List<Map<String, Object>> circuitList) throws Exception {
        List<String> circuitPropertyList = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if(objectMap.get("回路属性")!=null && !objectMap.get("回路属性").toString().isEmpty() && !objectMap.get("回路属性").equals("驱动回路") ){
                circuitPropertyList.add(objectMap.get("回路id").toString());
            }
        }
        return circuitPropertyList;
    }

    /**
     * 用电器选择的位置变种点不在分支上
     * 仅检查 changeType="1"（指定点可变）的用电器：
     * 其 specifyPoints 中的每个分支点 ID 必须在拓扑图的分支（edges）中存在。
     *
     * @param appPositions 用电器信息列表（含位置变种类型、指定变种点id列表、端点id编号）
     * @param edges        所有分支信息（含分支起点名称、分支终点名称）
     * @param points       所有端点信息（含端点id编号、端点名称）
     * @return 变种点不在分支上的用电器 id 列表
     */
    public List<String> appVariantPointNotOnBranch(
            List<Map<String, Object>> appPositions,
            List<Map<String, Object>> edges,
            List<Map<String, Object>> points) {

        List<String> errorAppIds = new ArrayList<>();

        // 1) 收集所有分支上出现过的端点名称
        java.util.Set<String> branchPointNames = new java.util.HashSet<>();
        for (Map<String, Object> edge : edges) {
            Object start = edge.get("分支起点名称");
            Object end = edge.get("分支终点名称");
            if (start != null) branchPointNames.add(start.toString());
            if (end != null) branchPointNames.add(end.toString());
        }

        // 2) 构建 端点id → 端点名称 映射
        java.util.Map<String, String> pointIdToName = new java.util.HashMap<>();
        for (Map<String, Object> pt : points) {
            Object pid = pt.get("端点id编号");
            Object pname = pt.get("端点名称");
            if (pid != null && pname != null) {
                pointIdToName.put(pid.toString(), pname.toString());
            }
        }

        // 3) 检查 changeType="1" 的用电器
        for (Map<String, Object> app : appPositions) {
            Object changeType = app.get("位置变种类型");
            if (changeType == null || !"1".equals(changeType.toString())) {
                continue;
            }

            Object specifyPoints = app.get("指定变种点id列表");
            if (specifyPoints == null) {
                errorAppIds.add(app.get("用电器id").toString());
                continue;
            }

            String[] ids = specifyPoints.toString().split(",");
            for (String pointId : ids) {
                pointId = pointId.trim();
                if (pointId.isEmpty()) continue;

                String pointName = pointIdToName.get(pointId);
                // 点 ID 未找到 或 点名称不在任何分支上
                if (pointName == null || !branchPointNames.contains(pointName)) {
                    errorAppIds.add(app.get("用电器id").toString());
                    break; // 该用电器已判定有问题，检查下一个
                }
            }
        }

        return errorAppIds;
    }

    /**
     * 用电器未选择指定变种点
     * changeType="1" 表示指定点可变，但 specifyPoints 为空则未选择任何变种点。
     *
     * @param appPositions 用电器信息列表
     * @return 未选择变种点的用电器 id 列表
     */
    public List<String> appVariantPointNotSelected(List<Map<String, Object>> appPositions) {
        List<String> errorAppIds = new ArrayList<>();
        for (Map<String, Object> app : appPositions) {
            Object changeType = app.get("位置变种类型");
            if (changeType == null || !"1".equals(changeType.toString())) {
                continue;
            }
            Object specifyPoints = app.get("指定变种点id列表");
            if (specifyPoints == null || specifyPoints.toString().trim().isEmpty()) {
                errorAppIds.add(app.get("用电器id").toString());
            }
        }
        return errorAppIds;
    }

    /**
     * 用电器可以生成的变种组合数过多
     * 收集所有设置了变种位置（changeType=1或2）的用电器 id，
     * 如果它们的变种点数量乘积超过阈值，则在列表末尾追加一条提示。
     * changeType=0 → 1（默认位置），1 → specifyPoints 个数，2 → 所有分支点总数
     *
     * @param appPositions 用电器信息列表
     * @param edges        所有分支信息（用于算 changeType=2 的总分支点数）
     * @return 设置了变种位置的用电器 id 列表，超标时末尾追加 "组合数:xxx > 阈值:xxx"
     */
    public List<String> appVariantCountExceeded(List<Map<String, Object>> appPositions,
            List<Map<String, Object>> edges) {
        List<String> variantAppIds = new ArrayList<>();
        java.util.Set<String> allBranchPointNames = new java.util.HashSet<>();
        for (Map<String, Object> edge : edges) {
            Object s = edge.get("分支起点名称");
            Object e = edge.get("分支终点名称");
            if (s != null) allBranchPointNames.add(s.toString());
            if (e != null) allBranchPointNames.add(e.toString());
        }
        int totalBranchPoints = allBranchPointNames.size();

        long product = 1L;
        boolean exceeded = false;
        for (Map<String, Object> app : appPositions) {
            Object changeType = app.get("位置变种类型");
            if (changeType == null) continue;
            String ct = changeType.toString();
            if ("0".equals(ct)) continue;  // 默认位置不算变种
            variantAppIds.add(app.get("用电器id").toString());

            int count = 1;
            if ("1".equals(ct)) {
                Object sp = app.get("指定变种点id列表");
                if (sp != null && !sp.toString().trim().isEmpty()) {
                    count = sp.toString().split(",").length;
                }
            } else if ("2".equals(ct)) {
                count = totalBranchPoints;
            }
            product *= count;
            if (product > VARIANT_COUNT_THRESHOLD) {
                exceeded = true;
            }
        }

        if (exceeded) {
            variantAppIds.add("组合数:" + product + " > 阈值:" + VARIANT_COUNT_THRESHOLD);
        }
        return variantAppIds;
    }
}
