package HarnessPackOpti.InfoRead;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReadTopologyInfo {
   public Map<String,Object> getTopologyInfo(Map<String,Object> mapFromProject){
        /*
        需要读取的参数信息有：
        -来自map中的topoInfo：拓扑基本信息   方案编号topologyCode、拓扑名称topologyName、拓扑类型topologyType、版本version
        -来自map中的points列表：所有端点信息  端点编号id、端点名称pointName、端点x坐标xCoordinate、端点y坐标yCoordinate、
                                         端点干湿waterParam、端点接口直连编号（若有）interfaceCode
        -来自map中的edges列表： 所有分支信息  分支编号id、分支起点名称startPointName，分支终点名称endPointName，分支名称edgeName，
                                         分支颜色color、分支公式formula、用户确认的分支长度length，参考长度referenceLength
         */

        // 读取外部输入Map中的topoInfo、points列表、edges列表信息
        HashMap<String, Object> topoInfoMap = (HashMap<String, Object>) mapFromProject.get("topoInfo");
        List<Map<String, Object>> pointsMap = (List<Map<String, Object>>) mapFromProject.get("points");
        List<Map<String, Object>> edgesMap = (List<Map<String, Object>>) mapFromProject.get("edges");
//        System.out.println("topoInfoMap:"+topoInfoMap);
//        System.out.println("pointsMap:"+pointsMap);
//        System.out.println("edgesMap:"+edgesMap);


        //创建MAP用来存放读取到的信息
        Map<String, Object> AllInfo = new HashMap<>();//总的最外层的map
        Map<String, Object> topoInfo = new HashMap<>();//中层的map，存放拓扑基本信息
        List<Map<String, Object>> points = new ArrayList<>();//中层的map列表，存放所有端点的信息
        List<Map<String, Object>> edges = new ArrayList<>();//中层的map列表，存放所有分支的信息





        //往MAP中存放拓扑基本信息
        topoInfo.put("方案编号",topoInfoMap.get("topologyCode"));
        topoInfo.put("拓扑名称",topoInfoMap.get("topologyName"));
        topoInfo.put("拓扑类型",topoInfoMap.get("topologyType"));
        topoInfo.put("版本",topoInfoMap.get("version"));
//        System.out.println("topoInfo:"+topoInfo);




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
//        System.out.println("points:"+points);




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
//        System.out.println("edges:"+edges);



       AllInfo.put("拓扑基本信息",topoInfo);
       AllInfo.put("所有端点信息",points);
       AllInfo.put("所有分支信息",edges);
//        System.out.println("项目完整信息:"+AllInfo);

        return AllInfo;
    }

}
