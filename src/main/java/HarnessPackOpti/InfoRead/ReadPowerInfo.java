package HarnessPackOpti.InfoRead;

import HarnessPackOpti.Optimize.elec.ElecPositionVariantCalculation;
import HarnessPackOpti.Optimize.elec.PowerDistributionDriveOptimization;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ReadPowerInfo {
    private Map<String, Double> elecBusinessCostAdditionMap = new HashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    //选型配置，最后回写到配置文件
    private Map<String, List<String>> dropdownOptionsMap = new HashMap<>();
    private static final Map<String, BiConsumer<String, Map<String, List<String>>>> TYPE_HANDLERS = new LinkedHashMap<>();
    private static final Map<String, Class<?>> TYPE_CLASS_MAPPING = new HashMap<>();

    static {
        // 类型名称 -> 对应的类
        TYPE_CLASS_MAPPING.put("线束拓扑优化", HarnessBranchTopoOptimize.class);
        TYPE_CLASS_MAPPING.put("配电驱动优化", PowerDistributionDriveOptimization.class);
        TYPE_CLASS_MAPPING.put("控制器位置优化", ElecPositionVariantCalculation.class);
        TYPE_CLASS_MAPPING.put("整车计算", ProjectCircuitInfoOutput.class); // 假设存在这个类
    }

    static {
        // type前缀 -> 处理函数(key, dropdownOptionsMap) -> 实际存储的key
        TYPE_HANDLERS.put("特定用电器相关回路商务成本加成", (name, map) -> {}); // 单独处理

        TYPE_HANDLERS.put("整车尺寸确认页-左右驾", (name, map) -> map.computeIfAbsent("drivingType", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("整车尺寸确认页-车辆类型", (name, map) -> map.computeIfAbsent("bodyStyle", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("整车尺寸确认页-动力类型", (name, map) -> map.computeIfAbsent("powerType", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("回路信息确认页-回路导线选型列", (name, map) -> map.computeIfAbsent("wireType", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("回路信息确认页-回路属性列", (name, map) -> map.computeIfAbsent("circuitProperty", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("用电器位置确认页-用电器类型列", (name, map) -> map.computeIfAbsent("appType", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-断开", (name, map) -> map.computeIfAbsent("B", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-导通", (name, map) -> map.computeIfAbsent("C", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-inline打断", (name, map) -> map.computeIfAbsent("S", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-组内分支一起I变", (name, map) -> map.computeIfAbsent("changeTogher", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-组与组分支触发互斥", (name, map) -> map.computeIfAbsent("mutex", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-组内分支仅有一个C", (name, map) -> map.computeIfAbsent("groupChoose", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("线束主干拓扑优化设置页-闭环不发生在指定分支", (name, map) -> map.computeIfAbsent("wear", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("整车控制器布局优化设置页-用电器变种选择", (name, map) -> map.computeIfAbsent("elecAppChangeType", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("配电驱动功能优化下拉设置项-电器件位置变种选择", (name, map) -> map.computeIfAbsent("elecChangeType", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("配电驱动功能优化下拉设置项-设置组队连接关系", (name, map) -> map.computeIfAbsent("powerChangeTogether", k -> new ArrayList<>()).add(name));
        TYPE_HANDLERS.put("配电驱动功能优化下拉设置项-设置互斥连接关系", (name, map) -> map.computeIfAbsent("powerMutex", k -> new ArrayList<>()).add(name));
    }

    public Map<String,Object> getProjectInfo(Map<String,Object> mapFromProject){
        //创建MAP用来存放读取到的信息
        Map<String, Object> AllInfo = new HashMap<>();
        Map<String, Object> topoInfo = new HashMap<>();
        List<Map<String, Object>> points = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Object>  projectInfo = new HashMap<>();
        List<Map<String, Object>> appPositions = new ArrayList<>();
        List<Map<String,Object>> loopInfos=new LinkedList<>();
        Map<String, Object>  caseInfo = new HashMap<>();


        if (mapFromProject.containsKey("caseInfo")){
            HashMap<String, Object> caseInfoMap= (HashMap<String, Object>) mapFromProject.get("caseInfo");
            caseInfo.put("直连接口是否发生变化",caseInfoMap.get("connect"));
            caseInfo.put("是否开启消除闭环",caseInfoMap.get("loopcreate"));
        }



        if (mapFromProject.containsKey("topoInfo")){
            HashMap<String, Object> topoInfoMap = (HashMap<String, Object>) mapFromProject.get("topoInfo");
            topoInfo.put("方案编号",topoInfoMap.get("topologyCode"));
            topoInfo.put("拓扑名称",topoInfoMap.get("topologyName"));
            topoInfo.put("拓扑类型",topoInfoMap.get("topologyType"));
            topoInfo.put("版本",topoInfoMap.get("version"));
        }

        if (mapFromProject.containsKey("projectInfo")){
            HashMap<String, Object>  projectInfoMap= (HashMap<String, Object>) mapFromProject.get("projectInfo");
            projectInfo.put("左右驾信息",projectInfoMap.get("drivingType"));
            projectInfo.put("车辆类型",projectInfoMap.get("bodyStyle"));
            projectInfo.put("动力类型",projectInfoMap.get("powerType"));
            projectInfo.put("直连接口是否发生变化",projectInfoMap.get("whetherToChange"));
            projectInfo.put("是否开启消除闭环",projectInfoMap.get("whetherOnLoop"));
        }
        if (mapFromProject.containsKey("edges")){
            List<Map<String, Object>> edgesMap = (List<Map<String, Object>>) mapFromProject.get("edges");
            for (Map<String, Object> k:edgesMap){
                Map<String, Object> edge = new HashMap<>();//内层的map，存放单个分支的信息
                edge.put("分支id编号",k.get("id"));
                edge.put("分支起点名称",k.get("startPointName"));
                edge.put("分支起点x坐标",k.get("startXCoordinate"));
                edge.put("分支起点y坐标",k.get("startYCoordinate"));

                edge.put("分支终点名称",k.get("endPointName"));
                edge.put("分支终点x坐标",k.get("endXCoordinate"));
                edge.put("分支终点y坐标",k.get("endYCoordinate"));


                edge.put("分支名称",k.get("edgeName"));
                edge.put("分支颜色",k.get("color"));
                edge.put("分支公式",k.get("formula"));
                edge.put("参考长度",k.get("referenceLength"));
                edge.put("用户确认的分支长度",k.get("length"));
                edge.put("分支打断",k.get("topologyStatusCode"));
                edges.add(edge);//每个分支的信息放入列表
            }
        }

        if (mapFromProject.containsKey("appPositions")){
            List<Map<String, Object>> appPositionsMap = (List<Map<String, Object>>) mapFromProject.get("appPositions");
            for (Map<String, Object> k : appPositionsMap) {
                Map<String, Object> appPosition = new HashMap<>();
                appPosition.put("用电器名称",k.get("appName"));
                appPosition.put("用电器类型",k.get("appType"));
                appPosition.put("用电器id",k.get("id"));
                appPosition.put("用电器位置是否固化",k.get("positionRegular"));
                appPosition.put("用电器固化位置点id",k.get("regularPointId"));
                appPosition.put("用电器固化位置点名称",k.get("regularPointName"));
                appPosition.put("用户更改后用电器位置id",k.get("unregularPointId"));
                appPosition.put("用户更改后用电器位置名称",k.get("unregularPointName"));
                appPosition.put("位置变种类型",k.get("changeType"));
                appPosition.put("指定变种点id列表",k.get("specifyPoints"));
                appPositions.add(appPosition);
            }
        }

        if (mapFromProject.containsKey("points")){
            List<Map<String, Object>> pointsMap = (List<Map<String, Object>>) mapFromProject.get("points");
            for (Map<String, Object> k:pointsMap){
                Map<String, Object> point = new HashMap<>();//内层的map，存放单个端点的信息
                point.put("端点id编号",k.get("id"));
                point.put("端点名称",k.get("pointName"));
                point.put("端点x坐标",k.get("xCoordinate"));
                point.put("端点y坐标",k.get("yCoordinate"));
                point.put("端点干湿",k.get("waterParam"));
                point.put("端点接口直连编号",k.get("interfaceCode"));
                points.add(point);//每个端点的信息放入列表
            }
        }

        if (mapFromProject.containsKey("loopInfos")){
            List<Map<String, Object>> loopInfosList = (List<Map<String, Object>>) mapFromProject.get("loopInfos");
            for (Map<String, Object> k : loopInfosList) {
                Map<String, Object> loopInfosMap = new HashMap<>();
                loopInfosMap.put("回路编号",k.get("loopNo"));
                loopInfosMap.put("回路id",k.get("id"));
                loopInfosMap.put("方案号",k.get("caseId"));
                loopInfosMap.put("所属系统",k.get("loopSys"));
                loopInfosMap.put("回路起点用电器",k.get("startApp"));
                loopInfosMap.put("回路起点用电器接口编号",k.get("startAppPort"));
                loopInfosMap.put("回路终点用电器",k.get("endApp"));
                loopInfosMap.put("回路终点用电器接口编号",k.get("endAppPort"));
                loopInfosMap.put("回路属性",k.get("loopAttr"));
                loopInfosMap.put("回路导线选型",k.get("loopWireway"));
                loopInfosMap.put("回路信号名",k.get("infoName"));
                loopInfosMap.put("起点电器件可连接的终点电器件",k.get("startConnEndApps"));
                loopInfosMap.put("终点电器件可连接的起点电器件",k.get("selectedEndApp"));
                loopInfosMap.put("组队连接关系",k.get("teamConnRel"));
                loopInfosMap.put("互斥连接关系",k.get("exclusiveConnRel"));
                loopInfos.add(loopInfosMap);
            }
        }
        if(ProjectCircuitInfoOutput.elecBusinessPrice == null) {
            if (mapFromProject.containsKey("eeParamConfigList")) {
                List<Map<String, Object>> eeParamConfigList = (List<Map<String, Object>>) mapFromProject.get("eeParamConfigList");
                for (Map<String, Object> map : eeParamConfigList) {
                    String paramName = map.get("paramName").toString();
                    Object paramValue = map.get("paramValue");
                    String type = map.get("type") != null ? map.get("type").toString() : "";

                    // 统一处理，一行搞定
                    processParam(type, paramName, paramValue);
                }
                HarnessPackOpti.ProjectInfoOutPut.ConfigOutput.populateResource(dropdownOptionsMap);
                ProjectCircuitInfoOutput.elecBusinessPrice = elecBusinessCostAdditionMap;
            }
        }
            Map<String, Map<String, String>> dataMap = new HashMap<>();
            if ( mapFromProject.containsKey("eeParamMaterialList")) {
                //物料配置表
                List<Map<String, Object>> eeParamMaterialList = (List<Map<String, Object>>) mapFromProject.get("eeParamMaterialList");
                for (Map<String, Object> map : eeParamMaterialList) {
                    Map<String, String> tempMap = new HashMap<>();
                    String wireType = map.get("wireType").toString();
                    tempMap.put("导线物料价（元/米）", map.get("materialPrice").toString());
                    tempMap.put("导线单位商务价（元/米）", map.get("businessPrice").toString());
                    tempMap.put("导线单位重量（单位g/m）", map.get("unitWeight").toString());
                    tempMap.put("端子成本（元/端）", map.get("terminalPrice").toString());
                    tempMap.put("焊点成本（元/m）", map.get("weldingPointCost").toString());
                    tempMap.put("导线打断成本（元/次）", map.get("dryBreakCost").toString());
                    tempMap.put("湿区成本补偿——连接器塑壳（元/端）", map.get("wetHousingCost").toString());
                    tempMap.put("湿区成本补偿——防水赛（元/个）", map.get("waterproofPlugComp").toString());
                    tempMap.put("导线外径（毫米）", map.get("wireDiameter").toString());
                    tempMap.put("导线两端的连接器塑壳商务价（元/端）", map.get("plasticBusinessPrice").toString());
                    dataMap.put(wireType, tempMap);
                }
                ProjectCircuitInfoOutput.elecFixedLocationLibrary = dataMap;
            }
        AllInfo.put("拓扑基本信息",topoInfo);
        AllInfo.put("所有端点信息",points);
        AllInfo.put("所有分支信息",edges);
        AllInfo.put("项目基本信息",projectInfo);
        AllInfo.put("用电器信息",appPositions);
        AllInfo.put("回路用电器信息",loopInfos);
        AllInfo.put("方案信息",caseInfo);
        return AllInfo;
    }

    // 处理参数的方法
    private void processParam(String type, String paramName, Object paramValue) {
        // 1. 商务成本加成特殊处理
        if ("特定用电器相关回路商务成本加成".equals(type)) {
            double value = paramValue instanceof Number ?
                    ((Number) paramValue).doubleValue() :
                    Double.parseDouble(paramValue.toString());
            elecBusinessCostAdditionMap.put(paramName, value);
            return;
        }

        // 2. 查找匹配的处理器并执行
        for (Map.Entry<String, BiConsumer<String, Map<String, List<String>>>> entry : TYPE_HANDLERS.entrySet()) {
            if (type.startsWith(entry.getKey())) {
                entry.getValue().accept(paramName, dropdownOptionsMap);
                return;
            }
        }

        // 3. 反射注入（原有逻辑）
        Class<?> targetClass = TYPE_CLASS_MAPPING.get(type);
        if (targetClass != null) {
            inject(paramName, paramValue, targetClass);
        } else {
            System.err.println("未找到类型 [" + type + "] 对应的处理逻辑");
        }
    }

    /**
     * 设置字段值（自动类型转换）
     */
    private static void setFieldValue(Field field, Object value) throws IllegalAccessException {
        Class<?> fieldType = field.getType();

        if (value == null) {
            field.set(null, null);
            return;
        }

        if (fieldType == Integer.class || fieldType == int.class) {
            field.set(null, ((Number) value).intValue());
        } else if (fieldType == Double.class || fieldType == double.class) {
            field.set(null, ((Number) value).doubleValue());
        } else if (fieldType == Long.class || fieldType == long.class) {
            field.set(null, ((Number) value).longValue());
        } else if (fieldType == Float.class || fieldType == float.class) {
            field.set(null, ((Number) value).floatValue());
        } else if (fieldType == Boolean.class || fieldType == boolean.class) {
            if (value instanceof Boolean) {
                field.set(null, value);
            } else {
                field.set(null, Boolean.parseBoolean(value.toString()));
            }
        } else if (fieldType == String.class) {
            field.set(null, value.toString());
        } else {
            // 其他类型直接赋值（如List、Map等）
            field.set(null, value);
        }
    }

    /**
     * 在多个类中查找并设置字段
     * @param paramName 参数名（与字段名一致）
     * @param paramValue 参数值
     * @return 是否设置成功
     */
    public static boolean inject(String paramName, Object paramValue, Class<?> targetClass) {
        try {
            // 使用缓存提高性能
            String cacheKey = targetClass.getName() + "." + paramName;
            Field field = FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    Field f = targetClass.getDeclaredField(paramName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    throw new RuntimeException("字段不存在: " + k, e);
                }
            });

            // 类型转换并设置值
            setFieldValue(field, paramValue);

            System.out.printf("✓ [%s] %s.%s = %s%n",
                    targetClass.getSimpleName(), targetClass.getSimpleName(), paramName, paramValue);
            return true;

        } catch (RuntimeException | IllegalAccessException e) {
            System.err.printf("✗ [%s] 参数 [%s] 注入失败: %s%n",
                    targetClass.getSimpleName(), paramName, e.getMessage());
            return false;
        }
    }
}
