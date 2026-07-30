package HarnessPackOpti.DiagnoseLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配电驱动检查
 */
public class PowerDistributionDriveLibrary {

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
}
