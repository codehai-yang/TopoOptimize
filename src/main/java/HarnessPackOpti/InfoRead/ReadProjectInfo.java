package HarnessPackOpti.InfoRead;

import java.util.*;

public class ReadProjectInfo {
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
            caseInfo.put("方案名称",caseInfoMap.get("caseName"));
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
                appPosition.put("用电器id",k.get("id"));
                appPosition.put("用电器位置是否固化",k.get("positionRegular"));
                appPosition.put("用电器固化位置点id",k.get("regularPointId"));
                appPosition.put("用电器固化位置点名称",k.get("regularPointName"));
                appPosition.put("用户更改后用电器位置id",k.get("unregularPointId"));
                appPosition.put("用户更改后用电器位置名称",k.get("unregularPointName"));
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
                loopInfos.add(loopInfosMap);
            }
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
}
