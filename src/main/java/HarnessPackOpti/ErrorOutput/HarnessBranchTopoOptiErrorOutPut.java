package HarnessPackOpti.ErrorOutput;


import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

//在进行分支拓扑优化前，对给定的格式进行检查，查看是否符合要求
public class HarnessBranchTopoOptiErrorOutPut {

    public static void main(String[] args) throws Exception {
        File file = new File("data/DataCoopWithEB/拓扑检查问题1.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
        HarnessBranchTopoOptiErrorOutPut harnessBranchTopoOptiErrorOutPut = new HarnessBranchTopoOptiErrorOutPut();
        harnessBranchTopoOptiErrorOutPut.topoOptimizeOutput(jsonContent);
    }


    public Map<String, Object> topoOptimizeOutput(String jsonContent) throws Exception {
//        首先对txt文件进行解析
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();

        Map<String, Object> map = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) map.get("edges");
//      以防万一    将勾选的BSC  NULL   全部替换为”“
        for (Map<String, Object> edge : edges) {
            if (edge.get("statusB") == null) {
                edge.put("statusB", "");
            }
            if (edge.get("statusC") == null) {
                edge.put("statusC", "");
            }
            if (edge.get("statusS") == null) {
                edge.put("statusS", "");
            }
        }


        Map<String, Object> errorOutput = new HashMap<>();

        //    分支未选择通断变种
        List<String> edgeWhereChooseStatus = edgeWhereChooseStatus(edges);
        errorOutput.put("分支未选择通断变种-error", edgeWhereChooseStatus);

        if (edgeWhereChooseStatus.size() > 0) {
            errorOutput.put("用电器所在点未连接分支:点两侧分支不能同时只选B-error", new ArrayList<>());
            errorOutput.put("整车分支不连通-error", new ArrayList<>());
            errorOutput.put("组内分支一起变:分支未同时选B&C-error", new ArrayList<>());
            errorOutput.put("组与组变种互斥:分支未包含B-error", new ArrayList<>());
            errorOutput.put("组与组变种互斥:分支不能只选B-error", new ArrayList<>());
            errorOutput.put("组内只保留一个C:组内有两条只能为C的分支", new ArrayList<>());
            errorOutput.put("组内只保留一个C:分支不参与“团内分支一起变”-error", new ArrayList<>());
            errorOutput.put("闭环不发生在制定分支:分支不能只选S-error", new ArrayList<>());
            return errorOutput;
        }
        Map<String, Object> topoMeaningfulresult = topoMeaningful(map);
        //    用电器所在点未连接分支
        errorOutput.put("用电器所在点未连接分支:点两侧分支不能同时只选B-error", topoMeaningfulresult.get("elecRoundExistEdges"));
        //    整车分支不连通
        List<List<String>> breakRec = (List<List<String>>) topoMeaningfulresult.get("breakRec");
        errorOutput.put("整车分支不连通-error", breakRec.size() > 1 ? breakRec : new ArrayList<>());
        //    团内分支一起变 分支未同时选B&C
        List<String> list = groupCheck(map);
        errorOutput.put("组内分支一起变:分支未同时选B&C-error", list);
        //    团与团变种互斥：分支未包含B
        Map<String, List<String>> listMap = mutexGroup(edges);
        errorOutput.put("组与组变种互斥:分支未包含B-error", listMap.get("NotChooseB"));
        //    团与团变种互斥：分支不能只选B
        errorOutput.put("组与组变种互斥:分支不能只选B-error", listMap.get("onlyOneB"));
        //    团内只保留一个C：分支未包含C
        Map<String, Object> chooseOneCMap = chooseOneC(edges);
        errorOutput.put("组内只保留一个C:组内有两条只能为C的分支-error", chooseOneCMap.get("inexistenceCList"));
        //    团内只保留一个C：分支不参与“团内分支一起变”
        errorOutput.put("组内只保留一个C:分支不参与“团内分支一起变”-error", chooseOneCMap.get("clashGroupList"));
        //    闭环不发生在制定分支：分支不能只选S
        List<String> list1 = wearOnlyS(edges);
        errorOutput.put("闭环不发生在指定分支:分支不能只选S-error", list1);
        String jsonString = objectMapper.writeValueAsString(errorOutput);
        return errorOutput;
    }

    //    检查 ：闭环不发生在制定分支：分支不能只选S
    public List<String> wearOnlyS(List<Map<String, Object>> edges) {
        List<String> resultList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (edge.get("closedLoop") != null && edge.get("closedLoop").toString().equals("O") && !edge.get("statusS").toString().isEmpty()) {
                if (edge.get("statusC").toString().isEmpty() && edge.get("statusB").toString().isEmpty()) {
                    resultList.add(edge.get("id").toString());
                }
            }
        }
        return resultList;
    }

    //    检查：团内只保留一个C：分支不参与“团内分支一起变”   团内只保留一个C：分支未包含C
    public Map<String, Object> chooseOneC(List<Map<String, Object>> edges) {
        Map<String, Object> resultMap = new HashMap<>();
        List<String> clashGroupList = new ArrayList<>();
        Map<String, Map<String, List<String>>> chooseOneMap = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            if (edge.get("oneC") != null) {
                if (edge.get("changeTogether")!=null && !edge.get("changeTogether").toString().isEmpty()) {
                    clashGroupList.add(edge.get("id").toString());
                    continue;
                }
                String chooseName = edge.get("oneC").toString();
                List<String> chooselist = new ArrayList<>();
                if (!edge.get("statusB").toString().isEmpty()) {
                    chooselist.add("B");
                }
                if (!edge.get("statusS").toString().isEmpty()) {
                    chooselist.add("S");
                }
                if (!edge.get("statusC").toString().isEmpty()) {
                    chooselist.add("C");
                }

                if (chooseOneMap.containsKey(chooseName)) {
                    chooseOneMap.get(chooseName).put(edge.get("id").toString(), chooselist);
                } else {
                    Map<String, List<String>> listMap = new HashMap<>();
                    listMap.put(edge.get("id").toString(), chooselist);
                    chooseOneMap.put(chooseName, listMap);
                }
            }
        }

        List<List<String>> inexistenceCList = new ArrayList<>();
        for (String key : chooseOneMap.keySet()) {
            Map<String, List<String>> listMap = chooseOneMap.get(key);

            Set<String> set = listMap.keySet();
            int  numberC=0;
            for (String edgeId : set) {
                List<String> list = listMap.get(edgeId);
                if (list.size() == 1 && list.get(0).equals("C")){
                    numberC++;
                }
            }
            if (numberC>1){
                inexistenceCList.add(new ArrayList<>(set));
            }
        }


        resultMap.put("inexistenceCList", inexistenceCList);
        resultMap.put("clashGroupList", clashGroupList);


        return resultMap;
    }

    //    检查： 团与团变种互斥：分支不能只选B  团与团变种互斥：分支未包含B
    public Map<String, List<String>> mutexGroup(List<Map<String, Object>> edges) {
        List<String> onlyOneB = new ArrayList<>();
        List<String> NotChooseB = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (edge.get("mutualExclusion") != null && !edge.get("mutualExclusion").toString().isEmpty()) {
                if (!edge.get("statusB").toString().isEmpty()) {
                    if (!(!edge.get("statusC").toString().isEmpty() || !edge.get("statusS").toString().isEmpty())) {
                        onlyOneB.add(edge.get("id").toString());
                    }
                } else {
                    NotChooseB.add(edge.get("id").toString());
                }
            }
        }
        Map<String, List<String>> reultMap = new HashMap<>();
        reultMap.put("onlyOneB", onlyOneB);
        reultMap.put("NotChooseB", NotChooseB);
        return reultMap;

    }

    //    检查：团内分支一起变 分支未同时选B&C
    public List<String> groupCheck(Map<String, Object> jsonMap) throws Exception {
        List<String> questionList = new ArrayList<>();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        for (Map<String, Object> edge : edges) {
            if (edge.get("changeTogether") != null && !edge.get("changeTogether").toString().isEmpty()) {
                if (edge.get("statusB").toString().isEmpty() || edge.get("statusC").toString().isEmpty()) {
                    questionList.add(edge.get("id").toString());
                }

            }
        }
        return questionList;
    }

    //    检查：分支未选择通断变种    用电器所在点未连接分支
    public Map<String, Object> topoMeaningful(Map<String, Object> jsonMap) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        List<Map<String, String>> points = (List<Map<String, String>>) jsonMap.get("points");

        Map<String, String> eleclection = getEleclection(appPositions);
//        首先对所有的分支进行一个分类    固定的，非固定的
        Map<String, List<String>> completefixedMap = new HashMap<>();
        Map<String, List<String>> togetherBCMap = new HashMap<>();
        List<String> singleBCList = new ArrayList<>();
        List<String> singleSCList = new ArrayList<>();
        List<String> singleBSList = new ArrayList<>();
        List<String> singleBSCList = new ArrayList<>();
        List<String> normList = new ArrayList<>();
        List<String> primeList = new ArrayList<>();
//       穿腔的id
        List<String> wearId = new ArrayList<>();
//        互斥的情况
        Map<String, Map<String, List<String>>> mutexMap = new HashMap<>();
//        互斥团的情况
        Map<String, List<String>> mutexGroupMap = new HashMap<>();
//        多选一的情况
        Map<String, Map<String, List<String>>> chooseOneMap = new HashMap<>();

        for (Map<String, Object> edge : edges) {
            normList.add(edge.get("id").toString());
            primeList.add(edge.get("topologyStatusCode").toString());
//            勾选的情况为 sc的时候 当作c扔到 fixedList中去
//            将穿腔的id添加到wearId中去
            if (edge.get("closedLoop") != null && !edge.get("closedLoop").toString().isEmpty()) {
                wearId.add(edge.get("id").toString());
            }
//            存在互斥的情况 将id添加到mutexMap中去
            if (edge.get("mutualExclusion") != null && !edge.get("mutualExclusion").toString().isEmpty()) {
                String mutexName = edge.get("mutualExclusion").toString();
                String[] split = mutexName.split("-");
                if (mutexMap.containsKey(split[0])) {
                    Map<String, List<String>> map1 = mutexMap.get(split[0]);
                    if (map1.containsKey(mutexName)) {
                        map1.get(mutexName).add(edge.get("id").toString());
                    } else {
                        List<String> idList = new ArrayList<>();
                        idList.add(edge.get("id").toString());
                        map1.put(mutexName, idList);
                    }
                } else {
                    Map<String, List<String>> sonMap = new HashMap<>();
                    List<String> idList = new ArrayList<>();
                    idList.add(edge.get("id").toString());
                    sonMap.put(mutexName, idList);
                    mutexMap.put(split[0], sonMap);
                }
//                考虑互斥是否存在组团的情况如果存在   记录下来
                if (edge.get("changeTogether") !=null && !edge.get("changeTogether").toString().isEmpty()) {
                    if (mutexGroupMap.containsKey(edge.get("changeTogether").toString())) {
                        mutexGroupMap.get(edge.get("changeTogether").toString()).add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        mutexGroupMap.put(edge.get("changeTogether").toString(), list);
                    }
                }
                continue;
            }


//            对多选的一个情况进行一个记录
            if (edge.get("oneC") != null) {
                String chooseName = edge.get("oneC").toString();
                List<String> chooselist = new ArrayList<>();
                if (edge.get("statusB").toString().equals("B")) {
                    chooselist.add("B");
                }
                if (edge.get("statusS").toString().equals("S")) {
                    chooselist.add("S");
                }
                if (edge.get("statusC").toString().equals("C")) {
                    chooselist.add("C");
                }

                if (chooseOneMap.containsKey(chooseName)) {
                    chooseOneMap.get(chooseName).put(edge.get("id").toString(), chooselist);
                } else {
                    Map<String, List<String>> listMap = new HashMap<>();
                    listMap.put(edge.get("id").toString(), chooselist);
                    chooseOneMap.put(chooseName, listMap);
                }
                continue;
            }


//            当只有一个勾选的的情况 将该分支加入对应的框里面
            int trueCount = 0;
            if (edge.get("statusB").toString().equals("B")) {
                trueCount++;
            }
            if (edge.get("statusS").toString().equals("S")) {
                trueCount++;
            }
            if (edge.get("statusC").toString().equals("C")) {
                trueCount++;
            }
            if (trueCount == 1) {
                if (edge.get("statusB").toString().equals("B")) {
                    if (completefixedMap.containsKey("B")) {
                        completefixedMap.get("B").add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        completefixedMap.put("B", list);
                    }
                }

                if (edge.get("statusC").toString().equals("C")) {
                    if (completefixedMap.containsKey("C")) {
                        completefixedMap.get("C").add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        completefixedMap.put("C", list);
                    }
                }
                if (edge.get("statusS").toString().equals("S")) {
                    if (completefixedMap.containsKey("S")) {
                        completefixedMap.get("S").add(edge.get("id").toString());
                    } else {
                        List<String> list = new ArrayList<>();
                        list.add(edge.get("id").toString());
                        completefixedMap.put("S", list);
                    }
                }
                continue;
            }

//            是否为cs这种情况   分别放入到对应的集合中去
            if (edge.get("statusB").toString().isEmpty() && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether")==null || edge.get("changeTogether").toString().isEmpty()) {
                    singleSCList.add(edge.get("id").toString());
                    continue;
                }
            }

//            当前为bs的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().isEmpty()) {
                if (edge.get("changeTogether")==null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBSList.add(edge.get("id").toString());
                    continue;
                }
            }
//            当前为bsc的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether")==null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBSCList.add(edge.get("id").toString());
                    continue;
                }
            }
//            当前为bc的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().isEmpty() && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether")==null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBCList.add(edge.get("id").toString());
                    continue;
                }

            }
//            剩下的都是在BC中进行一个挑选
            if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                singleBCList.add(edge.get("id").toString());
            } else {
                if (togetherBCMap.containsKey(edge.get("changeTogether").toString())) {
                    togetherBCMap.get(edge.get("changeTogether").toString()).add(edge.get("id").toString());
                } else {
                    List<String> list = new ArrayList<>();
                    list.add(edge.get("id").toString());
                    togetherBCMap.put(edge.get("changeTogether").toString(), list);
                }
            }

        }
//        对两个组团进行一个处理
        Set<String> mutexGroupKey = mutexGroupMap.keySet();
        for (String s : mutexGroupKey) {
            if (togetherBCMap.containsKey(s)) {
                mutexGroupMap.get(s).addAll(togetherBCMap.get(s));
                togetherBCMap.remove(s);
            }
        }
        //        将统一的map格式改为list
        List<List<String>> togetherBCList = new ArrayList<>();
        for (String key : togetherBCMap.keySet()) {
            togetherBCList.add(togetherBCMap.get(key));
        }
        List<List<String>> mutexGroupList = new ArrayList<>();
        for (String key : mutexGroupMap.keySet()) {
            mutexGroupList.add(mutexGroupMap.get(key));
        }
        List<Map<String, List<String>>> chooseOneList = new ArrayList<>();
        for (String key : chooseOneMap.keySet()) {
            chooseOneList.add(chooseOneMap.get(key));
        }
//       找出那些符合变B的情况
        List<String> conformList = new ArrayList<>();
        conformList.addAll(singleBCList);
        conformList.addAll(singleBSList);
        conformList.addAll(singleBSCList);
        for (List<String> list : togetherBCList) {
            conformList.add(list.get(0));
        }
//        只要不是指定为B的   全部设置为C   检查是否符合条件
        List<String> onlyNameB = new ArrayList<>();
        if (completefixedMap.containsKey("B")) {
            onlyNameB.addAll(completefixedMap.get("B"));
        }

        List<String> onlyNameS = new ArrayList<>();
        if (completefixedMap.containsKey("S")) {
            List<String> list = completefixedMap.get("S");
            onlyNameS.addAll(list);
        }
//        可以变为S的id集合
        List<String> canChangeS = new ArrayList<>();
        canChangeS.addAll(singleSCList);
        canChangeS.addAll(singleBSList);
        canChangeS.addAll(singleBSCList);
//   initialScheme  当前方案下的分支打断情况
        List<Map<String, Object>> coppyedges = edges.stream().collect(Collectors.toList());
        for (Map<String, Object> coppyedge : coppyedges) {
            String id = (String) coppyedge.get("id");
            if (!onlyNameB.contains(id)) {
                coppyedge.put("topologyStatusCode", "C");
            } else {
                coppyedge.put("topologyStatusCode", "B");
            }
        }


        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        for (Map<String, Object> k : coppyedges) {
            strPointNameList.add(k.get("startPointName").toString());
            endPointNameList.add(k.get("endPointName").toString());
        }
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : coppyedges) {
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

        FindTopoBreak breakRecognize = new FindTopoBreak();
        List<List<String>> breakRec = breakRecognize.recognizeBreak(adjacencyMatrixGraph.getAdj(),
                adjacencyMatrixGraph.getAllPoint());
        List<List<String>> strToEndFamily = new ArrayList<>();
        Map<String, List<String>> breakRecIdList = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String start = edge.get("startPointName").toString();
            String end = edge.get("endPointName").toString();
            for (int i = 0; i < breakRec.size(); i++) {
                List<String> list = breakRec.get(i);
                if (list.contains(start) && list.contains(end)) {
                    if (breakRecIdList.containsKey(Integer.toString(i))) {
                        breakRecIdList.get(Integer.toString(i)).add(edge.get("id").toString());
                    } else {
                        List<String> list1 = new ArrayList<>();
                        list1.add(edge.get("id").toString());
                        breakRecIdList.put(Integer.toString(i), list1);
                    }
                }
            }

        }
        Set<String> stringSet = breakRecIdList.keySet();
        for (String key : stringSet) {
            strToEndFamily.add(breakRecIdList.get(key));
        }


        Set<String> elecRoundExistEdges = new HashSet<>();
        for (Map<String, String> appPosition : appPositions) {
            if (!appPosition.get("appName").startsWith("[")) {
                String pointName = eleclection.get(appPosition.get("appName"));
                if (!checkElecEdge(pointName, edges)) {
                    String idByName = findIdByName(pointName, points);
                    elecRoundExistEdges.add(idByName);
                }
            }

        }
        result.put("breakRec", strToEndFamily);
        result.put("elecRoundExistEdges", elecRoundExistEdges.stream().collect(Collectors.toList()));
        return result;

    }

    /**
     * @Description 判断用电器对应位置点两端是否存在分支为C
     * @input 用电器对应的位置点
     * @input 所有的分支信息
     */
    public boolean checkElecEdge(String pointName, List<Map<String, Object>> edges) {
        for (Map<String, Object> edge : edges) {
            if ((edge.get("startPointName").toString().equals(pointName) || edge.get("endPointName").toString().equals(pointName)) && !edge.get("topologyStatusCode").toString().equalsIgnoreCase("B")) {
                return true;
            }
        }
        return false;
    }

    //   找到所有同电器对应的位置点
    public Map<String, String> getEleclection(List<Map<String, String>> mapList) {
        Map<String, String> resultMap = new HashMap<>();
        for (Map<String, String> stringMap : mapList) {
            String result = "";
            if (stringMap.get("unregularPointName") != null) {
                result = stringMap.get("unregularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") != null) {
                result = stringMap.get("regularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") == null) {
                result = null;
            }

            resultMap.put(stringMap.get("appName"), result);

        }
        return resultMap;
    }

    /**
     * @Description: 检查所有分支是否选择通断变种
     * @input:
     * @inputExample:
     * @Return:
     */
    public List<String> edgeWhereChooseStatus(List<Map<String, Object>> edges) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> edge : edges) {

            int selectNumber = 0;
            if (edge.get("statusB") != null && !edge.get("statusB").toString().isEmpty()) {
                selectNumber++;
            }
            if (edge.get("statusS") != null && !edge.get("statusS").toString().isEmpty()) {
                selectNumber++;
            }
            if (edge.get("statusC") != null && !edge.get("statusC").toString().isEmpty()) {
                selectNumber++;
            }
            if (selectNumber == 0) {
                result.add(edge.get("id").toString());
            }
        }


        return result;
    }

    /**
     * @Description 根据位置点名称查找位置点id
     * @input appName 位置点名称
     * @inputExample 前围板外中点
     * @input pointList  txt解析完成后当中所有端点信息
     * @inputExample 前围板外中点
     * @Return 返回位置点名称的id f753b6fe-39bd-4602-8cb3-b663c79af003
     */

    public String findIdByName(String appName, List<Map<String, String>> pointList) {
        String node = "";
        for (Map<String, String> stringMap : pointList) {
            if (stringMap.get("pointName").equals(appName)) {
                node = stringMap.get("id");
                return node;
            }
        }
        return node;
    }
}
