package HarnessPackOpti.InfoRead;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReadElecLocationInfo {
    public  Map<String, Object> getElectionInfo(Map<String,Object> mapFromProject) {
        /*
        需要读取的参数信息有：
        -来自map中的edges列表： 所有分支信息  分支编号id、分支起点名称startPointName，分支终点名称endPointName，分支名称edgeName，
                                         分支颜色color、分支公式formula、用户确认的分支长度length，参考长度referenceLength
        -来自map中的projectInfo列表：项目基本信息    基本信息id、项目名称project、左右驾信息drivingType、车辆类型bodyStyle、
                                                动力类型powerType
        -来自map中的appPositions列表:    用电器名称appName、用电器位置是否固化positionRegular、用电器固化位置点id regularPointId
                                        用电器固化位置点名称regularPointName、  用户更改后用电器位置id  unregularPointId、
                                          用户更改后用电器位置名称  unregularPointName
        */

        Map<String, Object> AllInfo = new HashMap<>();

        List<Map<String, Object>> edgesMap = (List<Map<String, Object>>) mapFromProject.get("edges");
        List<Map<String, Object>> appPositionsMap = (List<Map<String, Object>>) mapFromProject.get("appPositions");
        Map<String, String> projectInfoMap = (Map<String, String>) mapFromProject.get("projectInfo");


        List<Map<String, Object>> appPositions = new ArrayList<>();
        for (Map<String, Object> k : appPositionsMap) {
            Map<String, Object> appPosition = new HashMap<>();
            appPosition.put("用电器名称",k.get("appName"));
            appPosition.put("用电器位置是否固化",k.get("positionRegular"));
            appPosition.put("用电器固化位置点id",k.get("regularPointId"));
            appPosition.put("用电器固化位置点名称",k.get("regularPointName"));
            appPosition.put("用户更改后用电器位置id",k.get("unregularPointId"));
            appPosition.put("用户更改后用电器位置名称",k.get("unregularPointName"));
            appPositions.add(appPosition);
        }


        Map<String,String> projectInfo=new HashMap<>();
        projectInfo.put("左右驾",projectInfoMap.get("drivingType"));
        projectInfo.put("车辆类型",projectInfoMap.get("bodyStyle"));
        projectInfo.put("动力类型",projectInfoMap.get("powerType"));


        List<Map<String, Object>> edges = new ArrayList<>();
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


        AllInfo.put("用电器信息",appPositions);
        AllInfo.put("项目基本信息",projectInfo);
        AllInfo.put("所有分支信息",edges);


        return AllInfo;
    }
}
