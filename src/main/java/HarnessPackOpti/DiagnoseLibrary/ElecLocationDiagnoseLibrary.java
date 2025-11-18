package HarnessPackOpti.DiagnoseLibrary;


import HarnessPackOpti.Algorithm.FindAllBranchNode;
import HarnessPackOpti.Algorithm.FindElecLocation;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ElecLocationDiagnoseLibrary {

    //用电器位置点缺失
    public List<String> elecLocationLack(Map<String, Object> map) {
        FindElecLocation findElecLocation = new FindElecLocation();
        List<Map<String, String>> mapList = findElecLocation.getEleclection(map);
        List<String> list = new ArrayList<>();
        for (Map<String, String> stringMap : mapList) {
            if (stringMap.get("value") == null && !stringMap.get("key").startsWith("[")) {
                list.add(stringMap.get("key"));
            }
        }
        return list;
    }


    //用电器不在分支的端点上
    public List<String> elecNotAtBranchNode(Map<String, Object> map) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) map.get("所有分支信息");
        List<String> mapList = new ArrayList<>();
        FindAllBranchNode findAllBranchNode = new FindAllBranchNode();
        FindElecLocation findElecLocation = new FindElecLocation();
//        查询所有端点信息
//        List<String> strings = findAllBranchNode.getAllBranchNode(map);




        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        List<String> topologyStatusCode = new ArrayList<>();
        for (Map<String, Object> k : edges) {
            strPointName.add((String) k.get("分支起点名称"));
            endPointName.add((String) k.get("分支终点名称"));
            topologyStatusCode.add((String) k.get("分支打断"));
        }

        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if ("B".equals(edge.get("分支打断"))) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("分支起点名称").toString());
                interruptedEdgelist.add(edge.get("分支终点名称").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }


        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        List<String> strings = adjacencyMatrixGraph.getAllPoint();

//        用电位置描述
        List<Map<String, String>> list = findElecLocation.getEleclection(map);

        for (Map<String, String> stringMap : list) {

            if (stringMap.get("value")!= null) {
                if (!strings.contains(stringMap.get("value"))) {
                    mapList.add(stringMap.get("key"));
                }
            }
        }
        return mapList;
    }


//    回路中的用电器未体现于该界面

//    public List<String> elecLack(Map<String, Object> map){
//
//    }


}
