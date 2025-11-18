package HarnessPackOpti.ErrorOutput;

import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.FindTopoLoop;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.DiagnoseLibrary.TopoDiagnoseLibrary;
import HarnessPackOpti.InfoRead.ReadTopologyInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class TopoErrorOutput {
    public String topoErrorOutput(String fileStringFormat) throws Exception {
        //读取到的Json格式字符串转换为Map
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        ReadTopologyInfo readTopologyInfo = new ReadTopologyInfo();
        Map<String, Object> topologyInfo = readTopologyInfo.getTopologyInfo(mapFile);

        List<Map<String, Object>> points = (List<Map<String, Object>>) topologyInfo.get("所有端点信息");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topologyInfo.get("所有分支信息");


        Map<String, String>map=new HashMap<>();
        for (Map<String, Object> edge : edges) {
            map.put(edge.get("分支id编号").toString(),edge.get("分支名称").toString());
        }




        TopoDiagnoseLibrary topoDiagnose = new TopoDiagnoseLibrary();
        LinkedHashMap<String, Object> TopoErrorMap;

        //识别重复的端点名称，存入List
        List<String> repeatPoint = topoDiagnose.repeatPoint(topologyInfo);
        //RepeatPoint中的一个个端点名称转为端点id编号： 新建一个list，以RepeatPoint中每一个元素为key，获取pointIdMap中key对应的value，并添加到list中
        List<List<String>> repeatPointId = new ArrayList<>();
        for (String pointName : repeatPoint) {
            List<String> list=new ArrayList<>();
            for (Map<String, Object> point : points) {
                if ( point.get("端点名称") != null && pointName.equals(point.get("端点名称").toString())) {
                    list.add(point.get("端点id编号").toString());
                }
            }
            repeatPointId.add(list);
        }


        //识别重复的分支名称，存入List
        List<String> repeatEdge = topoDiagnose.repeatEdge(topologyInfo);
        //RepeatEdge中的一个个分支名称转为分支id编号：  新建一个list，以RepeatEdge中每一个元素为key，获取edgeIdMap中key对应的value，并添加到list中
        List<List<String>> repeatEdgeId = new ArrayList<>();
        for (String edgeName : repeatEdge) {
            List<String> list=new ArrayList<>();
            for (Map<String, Object> edge : edges) {
                if (edge.get("分支名称") != null && edgeName.equals(edge.get("分支名称").toString())) {
                    list.add(edge.get("分支id编号").toString());
                }
            }
            repeatEdgeId.add(list);
        }


        //识别分支起点名称与分支终点名称是否有缺失
        List<List<String>> strEndPointLack = topoDiagnose.strEndPointLack(topologyInfo);
        //把strEndPointLackId中的一个个端点名称转为端点id编号:    新建一个list，以strEndPointLack.get(0)中每一个元素为key，获取pointIdMap中key对应的value，并添加到list中
        List<String> strPointLackId = strEndPointLack.get(0);
        List<String> endPointLackId = strEndPointLack.get(1);


        //InterfaceError0————检查检查所有接口直连编号，编号不重复。
        //InterfaceError1————检查所有接口直连编号，要求格式正确，”String前缀-String后缀“的组合，例如：A-1，A-2，A-3
        //InterfaceError2————检查所有接口直连编号，同一个String前缀的编号数量必须≥2。
        List<List<String>> InterfaceError = topoDiagnose.interfaceError(topologyInfo);

        //把InterfaceError.get(0) list中一个个接口名，转换为一组组对应的端点id编号     例如：A-1转换为 [XXX-XXX-XXX,XXX-XXX-XXX]
        List<List<String>> InterfaceError0 = new ArrayList<>();
        for (String id : InterfaceError.get(0)) {//id是一个个接口编号
            List<String> keysWithTargetValue = new ArrayList<>();
            for (Map<String, Object> point : points) {
                if ( point.get("端点接口直连编号") != null && id.equals(point.get("端点接口直连编号").toString())) {
                    keysWithTargetValue.add(point.get("端点id编号").toString());
                }
            }
            InterfaceError0.add(keysWithTargetValue);
        }
//        System.out.println("InterfaceError1:"+InterfaceError1);

        //把InterfaceError.get(1) list中一个个接口名，转换为一组组对应的端点id编号       例如：A-1转换为 [XXX-XXX-XXX,XXX-XXX-XXX]
        List<String> InterfaceError1 = new ArrayList<>();
        for (String id : InterfaceError.get(1)) {//id是一个个接口编号
            List<String> keysWithTargetValue = new ArrayList<>();
            for (Map<String, Object> point : points) {
                if (point.get("端点接口直连编号") !=null && id.equals(point.get("端点接口直连编号").toString())) {
                    keysWithTargetValue.add(point.get("端点id编号").toString());
                }
            }
            InterfaceError1.add(keysWithTargetValue.get(0));
        }

        //把InterfaceError.get(2) list中一个个接口名，转换为一组组对应的端点id编号      例如：A-1转换为 [XXX-XXX-XXX,XXX-XXX-XXX]
        List<String> InterfaceError2 = new ArrayList<>();
        for (String id : InterfaceError.get(2)) {//id是一个个接口编号
            List<String> keysWithTargetValue = new ArrayList<>();
            for (Map<String, Object> point : points) {
                if (point.get("端点接口直连编号") !=null && id.equals(point.get("端点接口直连编号").toString())) {
                    keysWithTargetValue.add(point.get("端点id编号").toString());
                }
            }
            InterfaceError2.add(keysWithTargetValue.get(0));//之所以只取get(0)是由于keysWithTargetValue中只有1个值
        }
//        端点干湿信息缺失
        List<String> pointWaterParamInfoLack = topoDiagnose.pointWaterParamInfoLack(points);

//        分支通断是否缺失
      List<String> branchLinkInfoLack = topoDiagnose.branchLinkInfoLack(edges);

        List<List<String>> strToEndFamily = new ArrayList<>();
        List<List<String>> strToEndLoop = new ArrayList<>();
        if (branchLinkInfoLack.size()>0){
            TopoErrorMap = new LinkedHashMap<>();
            TopoErrorMap.put("端点名称重复-error", repeatPointId);
            TopoErrorMap.put("分支名称重复-error", repeatEdgeId);
            TopoErrorMap.put("终点缺失-error", strPointLackId);
            TopoErrorMap.put("起点缺失-error", endPointLackId);
            //将InterfaceError0、InterfaceError1、InterfaceError2存放到map中
            TopoErrorMap.put("直连端口重复-error", InterfaceError0);
            TopoErrorMap.put("直连端口不符合‘字符-字符’格式-error", InterfaceError1);
            TopoErrorMap.put("同一直连端口前缀少于2项-error", InterfaceError2);
            //将strToEndFamily存放到map中
            TopoErrorMap.put("分支不连通-error", strToEndFamily);
            //将strToEndLoop存放到map中
            TopoErrorMap.put("分支闭环-warning", strToEndLoop);
            TopoErrorMap.put("分支通断缺失-error", branchLinkInfoLack);
            TopoErrorMap.put("端点干湿缺失-error", pointWaterParamInfoLack);

            //将TopoErrorMap转为json文件
            ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
            String json = objectMapper.writeValueAsString(TopoErrorMap);// 将Map转换为JSON字符串
            System.out.println("第一页拓扑设置中的错误有:\n" + json);

            return json;
        }



        //识别分支之间是否有断点
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


        FindTopoBreak breakRecognize = new FindTopoBreak();
        List<List<String>> breakRec = breakRecognize.recognizeBreak(adjacencyMatrixGraph.getAdj(),
                adjacencyMatrixGraph.getAllPoint());

        //将二维list BreakRecognize中[[族群A内端点1名称、族群A内端点2名称、族群A内端点3名称、...][族群B内端点1名称、族群B内端点2名称、族群B内端点3名称、...]]
        //转换为list<map> [{族群A的某个端点的起点=端点的终点，族群A的某个端点的终点=端点的起点，...}，{族群B的某个端点的起点=端点的终点，族群B的某个端点的终点=端点的起点，...}，...]
        //再转换为list<map> [{族群A的边1，族群A的边2，族群A的边3，.....},{族群B的边1，族群B的边2，族群B的边3，.....}]

        //找出BreakRecognize中每一个元素对应的起点端点名称与终点端点名称

        Map<String,List<String>> breakRecIdList = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String start = edge.get("分支起点名称").toString();
            String end = edge.get("分支终点名称").toString();
            for (int i = 0; i < breakRec.size(); i++) {
                List<String> list = breakRec.get(i);
                if (list.contains(start) && list.contains(end)) {
                    if (breakRecIdList.containsKey(Integer.toString(i))){
                        breakRecIdList.get(Integer.toString(i)).add(edge.get("分支id编号").toString());
                    }else {
                        List<String> list1 = new ArrayList<>();
                        list1.add(edge.get("分支id编号").toString());
                        breakRecIdList.put(Integer.toString(i),list1);
                    }
                }
            }

        }
        Set<String> stringSet = breakRecIdList.keySet();
        for (String key : stringSet) {
            strToEndFamily.add(breakRecIdList.get(key));
        }



        //只有当RepeatPointId、RepeatEdgeId检查出来没问题，且strToEndFamily没有打断仅有一个family时，才能继续做闭环检查
        //将二维list LoopRecognize中[[闭环A内端点1名称、闭环A内端点2名称、闭环A内端点3名称、...][闭环B内端点1名称、闭环B内端点2名称、闭环B内端点3名称、...]]
        //转换为list<map> [{闭环A的某个端点的起点=端点的终点，闭环A的某个端点的终点=端点的起点，...}，{闭环B的某个端点的起点=端点的终点，闭环B的某个端点的终点=端点的起点，...}，...]
        //再转换为list<map> [{闭环A的边1，闭环A的边2，闭环A的边3，.....},{闭环B的边1，闭环B的边2，闭环B的边3，.....}]




        if (repeatPointId.isEmpty() && repeatEdgeId.isEmpty() && strToEndFamily.size() == 1) {


            //识别分支之间是否有断点
            List<List<String>> loopbranchBreakList = new ArrayList<>();
            for (Map<String, Object> edge : edges) {
                if ("B".equals(edge.get("分支打断")) || "S".equals(edge.get("分支打断"))) {
                    List<String> interruptedEdgelist = new ArrayList<>();
                    interruptedEdgelist.add(edge.get("分支起点名称").toString());
                    interruptedEdgelist.add(edge.get("分支终点名称").toString());
                    loopbranchBreakList.add(interruptedEdgelist);
                }
            }


            GenerateTopoMatrix loopAdjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, loopbranchBreakList);//获取邻接矩阵基本信息
            loopAdjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
            loopAdjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
            loopAdjacencyMatrixGraph.getAdj();




            FindTopoLoop loopRecognize = new FindTopoLoop();
            List<List<String>> loopRec = loopRecognize.recognizeLoopNew(loopAdjacencyMatrixGraph.getAdj(), loopAdjacencyMatrixGraph.getAllPoint(), edges);

            Map<String,List<String>> loopbreakRecIdList = new HashMap<>();
            for (Map<String, Object> edge : edges) {
                String start = edge.get("分支起点名称").toString();
                String end = edge.get("分支终点名称").toString();
                for (int i = 0; i < loopRec.size(); i++) {
                    List<String> list = loopRec.get(i);
                    if (list.contains(start) && list.contains(end)) {
                        if (loopbreakRecIdList.containsKey(Integer.toString(i))){
                            loopbreakRecIdList.get(Integer.toString(i)).add(edge.get("分支id编号").toString());
                        }else {
                            List<String> list1 = new ArrayList<>();
                            list1.add(edge.get("分支id编号").toString());
                            loopbreakRecIdList.put(Integer.toString(i),list1);
                        }
                    }
                }
            }
            Set<String> stringSetList = loopbreakRecIdList.keySet();
            for (String key : stringSetList) {
                strToEndLoop.add(loopbreakRecIdList.get(key));
            }
        }


        //将RepeatPoint、repeatEdge、strEndPointLack、InterfaceError、BreakRecognize存放到map中
        TopoErrorMap = new LinkedHashMap<>();

        TopoErrorMap.put("端点名称重复-error", repeatPointId);
        TopoErrorMap.put("分支名称重复-error", repeatEdgeId);
        TopoErrorMap.put("终点缺失-error", strPointLackId);
        TopoErrorMap.put("起点缺失-error", endPointLackId);
        //将InterfaceError0、InterfaceError1、InterfaceError2存放到map中
        TopoErrorMap.put("直连端口重复-error", InterfaceError0);
        TopoErrorMap.put("直连端口不符合‘字符-字符’格式-error", InterfaceError1);
        TopoErrorMap.put("同一直连端口前缀少于2项-error", InterfaceError2);
        //将strToEndFamily存放到map中
        TopoErrorMap.put("分支不连通-error", strToEndFamily.size()>1?strToEndFamily:new ArrayList<>());
        //将strToEndLoop存放到map中
        TopoErrorMap.put("分支闭环-warning", strToEndLoop);
        TopoErrorMap.put("分支通断缺失-error", branchLinkInfoLack);
        TopoErrorMap.put("端点干湿缺失-error", pointWaterParamInfoLack);


        //将TopoErrorMap转为json文件
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(TopoErrorMap);// 将Map转换为JSON字符串
        System.out.println("第一页拓扑设置中的错误有:\n" + json);

        return json;
    }
}
