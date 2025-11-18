package HarnessPackOpti.ErrorOutput;

import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class ElecPositionVariantOutput {
    public static void main(String[] args) throws Exception {
        File file = new File("data/DataCoopWithEB/elec/1206.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
        ElecPositionVariantOutput elecPositionVariantOutput = new ElecPositionVariantOutput();
        elecPositionVariantOutput.ElecPositionVariantOutput(jsonContent);
    }

    public String ElecPositionVariantOutput(String jsonContent) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> initmapFile = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) initmapFile.get("appPositions");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) initmapFile.get("edges");
        List<Map<String, Object>> points = (List<Map<String, Object>>) initmapFile.get("points");
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) initmapFile.get("loopInfos");
        ObjectMapper objectMapper = new ObjectMapper();

        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        for (Map<String, Object> k : edges) {
            strPointNameList.add(k.get("startPointName").toString());
            endPointNameList.add(k.get("endPointName").toString());
        }
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (edge.get("topologyStatusCode").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("startPointName").toString());
                interruptedEdgelist.add(edge.get("endPointName").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointNameList, endPointNameList, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        List<String> allPoint = adjacencyMatrixGraph.getAllPoint();


        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();

        Map<String, Object> errorOutput = new HashMap<>();
//        检查用电器是否设置位置变种
//        用电器选择了 在指定不过点上变更 却没有选点
//        用电器选择了 在指定点上变更 选的点不在分支上
        List<String> lackAddress = new ArrayList<>();
        Map<String, List<String>> addressError = new HashMap<>();
        for (Map<String, Object> appPosition : appPositions) {
            String appName = appPosition.get("id").toString();
            if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("1")) {
                List<String> list=new ArrayList<>();
                if (appPosition.get("specifyPoints") !=null  && !appPosition.get("specifyPoints").toString().isEmpty()){
                    String specifyPoints = appPosition.get("specifyPoints").toString();
                    List<String> collect = Arrays.stream(specifyPoints.split(",")).collect(Collectors.toList());
                    for (String s : collect) {
                        list.add(findNameById(s, points));
                    }
                    if (list.size() == 0) {
                        lackAddress.add(appName);
                        continue;
                    }
                }else if (appPosition.get("specifyPoints") == null || appPosition.get("specifyPoints").toString().isEmpty()) {
                    lackAddress.add(appName);
                    continue;
                }

            }

            if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("1")) {
                List<String> list=new ArrayList<>();
                if (appPosition.get("specifyPoints") !=null  && !appPosition.get("specifyPoints").toString().isEmpty()){
                    String specifyPoints = appPosition.get("specifyPoints").toString();
//                    specifyPoints = specifyPoints.substring(0, specifyPoints.length() - 1);
                    List<String> collect = Arrays.stream(specifyPoints.split(",")).collect(Collectors.toList());
                    for (String s : collect) {
                        list.add(findNameById(s, points));
                    }
                }
                List<String> error = new ArrayList<>();
                for (String s : list) {
                    if (!allPoint.contains(s)) {
                        error.add(s);
                    }
                }
                if (error.size() > 0) {
                    addressError.put(appName, error);
                }
            }
        }

        errorOutput.put("用电器选择了在指定不过点上变更却没有选点-error", lackAddress);
        errorOutput.put("用电器选择了在指定点上变更选的点不在分支上-error", addressError);
        errorOutput.put("用电器可以生成的变种和数量过多-warning", null);
        if ( lackAddress.size()>0 || addressError.keySet().size()>0){
            String json = objectMapper.writeValueAsString(errorOutput);
            return json;
        }

//        用电器的位置变种过多

        //        首先找出当中位置不固定的用电器
        Map<String, List<String>> elecChangeablePosition = new HashMap<>();
        for (Map<String, Object> appPosition : appPositions) {
            if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("1")) {
                String appName = appPosition.get("appName").toString();
                List<String> list=new ArrayList<>();
                if (appPosition.get("specifyPoints") !=null  && !appPosition.get("specifyPoints").toString().isEmpty()){
                    String specifyPoints = appPosition.get("specifyPoints").toString();
                    String[] parts = specifyPoints.split(",");
                    List<String> collect = new ArrayList<>();
                    for (String part : parts) {
                        collect.add(part);
                    }
                    for (String s : collect) {
                        list.add(findNameById(s, points));
                    }
                }
                list.retainAll(allPoint);
                elecChangeablePosition.put(appName, list);
            } else if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("2")) {
                String appName = appPosition.get("appName").toString();
                elecChangeablePosition.put(appName, allPoint);
            }
        }
        Set<String> elecChangeablePositionSet = new HashSet<>(elecChangeablePosition.keySet());
//        筛选出可变的回路
        List<Map<String, Object>> unfixedLoopInfoList = new ArrayList<>();
        for (Map<String, Object> loopInfo : loopInfos) {
            String startApp = loopInfo.get("startApp").toString();
            String endApp = loopInfo.get("endApp").toString();
            if (elecChangeablePositionSet.contains(startApp) || elecChangeablePositionSet.contains(endApp)) {
                unfixedLoopInfoList.add(loopInfo);
            }
        }
        List<String> elecChangeablePositionList = elecChangeablePositionSet.stream().collect(Collectors.toList());
        List<List<String>> correlationElec = new ArrayList<>();
//      可变用电器一一比对   将符合要求的放到一个组里面
        for (int i = 0; i < elecChangeablePositionList.size() - 1; i++) {
            for (int j = i + 1; j < elecChangeablePositionList.size(); j++) {
                String elec1 = elecChangeablePositionList.get(i);
                String elec2 = elecChangeablePositionList.get(j);
                List<Map<String, Object>> group = new ArrayList<>();
                for (Map<String, Object> map : unfixedLoopInfoList) {
                    if ((map.get("startApp").equals(elec1) && map.get("endApp").equals(elec2))
                            || (map.get("startApp").equals(elec2) && map.get("endApp").equals(elec1))) {
                        group.add(map);
                    }
                }
//                计算当前是否达到一个阈值
                double costNumber = 0.0;
                for (Map<String, Object> map : group) {
                    String loopWireway = map.get("loopWireway").toString();
                    Map<String, String> map1 = elecFixedLocationLibrary.get(loopWireway);
                    costNumber += Double.parseDouble(map1.get("导线单位商务价（元/米）"));
                }
                if (costNumber > 2) {
                    List<String> elec = new ArrayList<>();
                    elec.add(elecChangeablePositionList.get(i));
                    elec.add(elecChangeablePositionList.get(j));
                    correlationElec.add(elec);
                }
            }
        }
//        对当前的用电器进行一个分组
        List<List<String>> lists = elecGroup(elecChangeablePositionSet.stream().collect(Collectors.toList()), correlationElec);
//        按照当前的分组计算
        Map<String, BigInteger> costNumberMap=new HashMap<>();
        for (List<String> group : lists) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < group.size(); i++) {
                sb.append(group.get(i));
                if (i < group.size() - 1) {
                    sb.append("&");
                }
            }
            String resultElectrical = sb.toString();
            BigInteger currentPossibility =new BigInteger("1");
            for (String s : group) {
                currentPossibility =currentPossibility.multiply(new BigInteger(String.valueOf(elecChangeablePosition.get(s).size()))) ;
            }
            costNumberMap.put(resultElectrical, currentPossibility);
        }
//        计算出所有的
        BigInteger allPossibilityNumber = new BigInteger("0");
        for (BigInteger value : costNumberMap.values()) {
            allPossibilityNumber=allPossibilityNumber.add(value);
        }
        if (allPossibilityNumber.compareTo(new BigInteger("100000000000")) ==1){
            StringBuilder errorString = new StringBuilder();
            List<String> costSet = costNumberMap.keySet().stream().collect(Collectors.toList());
            for (int i = 0; i < costSet.size(); i++) {
                errorString.append(costSet.get(i)+"的可变数量为"+costNumberMap.get(costSet.get(i))+"个");
                if (i < costSet.size() - 1) {
                    errorString.append(",");
                }
            }
            String resultElectrical = errorString.toString();
            errorOutput.put("用电器可以生成的变种和数量过多-warning",resultElectrical);
            String json = objectMapper.writeValueAsString(errorOutput);
            return json;
        }else {
            errorOutput.put("用电器可以生成的变种和数量过多-warning",null);
        }

        String json = objectMapper.writeValueAsString(errorOutput);
        return json;

    }


    public List<List<String>> elecGroup(List<String> elecName, List<List<String>> correlationElec) {
        Map<String, List<String>> graph = new HashMap<>();
        for (List<String> relation : correlationElec) {
            String node1 = relation.get(0);
            String node2 = relation.get(1);
            graph.computeIfAbsent(node1, k -> new ArrayList<>()).add(node2);
            graph.computeIfAbsent(node2, k -> new ArrayList<>()).add(node1);
        }

        // 记录节点是否已被访问过，避免重复访问
        Map<String, Boolean> visited = new HashMap<>();
        for (String elec : elecName) {
            visited.put(elec, false);
        }

        List<List<String>> groups = new ArrayList<>();
        for (String elec : elecName) {
            if (!visited.get(elec)) {
                List<String> group = new ArrayList<>();
                dfs(elec, graph, visited, group);
                groups.add(group);
            }
        }

        return groups;
    }

    private  void dfs(String node, Map<String, List<String>> graph, Map<String, Boolean> visited, List<String> group) {
        visited.put(node, true);
        group.add(node);
        if (graph.containsKey(node)) {
            List<String> neighbors = graph.get(node);
            for (String neighbor : neighbors) {
                if (!visited.get(neighbor)) {
                    dfs(neighbor, graph, visited, group);
                }
            }
        }
    }
    public String findNameById(String id, List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if (point.get("id").toString().equals(id)) {
               return point.get("pointName").toString() ;
            }
        }
        return "";
    }

}
