package HarnessPackOpti.utils;

import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * 负责AI训练的样本生成,方案二，值判断是否连通以及周围存在分支
 */
public class GenerateBreakNoise {

    /**
     * @Description: 对传进来的样本进行判断
     * @input: normList   当前分支id的排序情况
     * @input: changeList  所有裂变出新的分支的打断状况
     * @input: edges  生成需要检查的分支
     * @input: appPositions 没有解析txt中的用电器像信息
     * @input: eleclection  用电器对应的位置
     * @input: mutexMap   互斥的情况集合
     * @Return: 根据给定的方案检查，归类样本，生成训练数，导出json文件
     */
    public void exportJsonTwo(List<Map<String,Object>> allResult,String filePath) throws Exception{
        //json文件存储
//        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\caseTwo.txt");
//        String allTypeData = TypeCheckUtils.getAllTypeData();
//        Files.write(file.toPath(),allTypeData.getBytes());
        //二进制存储位置
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath,true)));
        for (Map<String,Object> resultTemp : allResult) {
            //边属性
            List<List<Integer>> edge_attr = (List<List<Integer>>)resultTemp.get("edge_attr");
            //分支通断与分支长度特征
            List<List<Float>>  branchFeatureList = (List<List<Float>> )resultTemp.get("branchFeatureList");
            //分支点特征，表示回路与湿区成本
            List<List<Float>>  circuitCostList = (List<List<Float>> )resultTemp.get("circuitCostList");
            Float  totalCost = Float.parseFloat(resultTemp.get("totalCost").toString());
            Float  baseWeight = Float.parseFloat(resultTemp.get("baseWeight").toString());
            Float  baseLength = Float.parseFloat(resultTemp.get("baseLength").toString());
            //二进制写入 边属性字节长度:[0:1688],数据长度:[2,211]
            //1688字节
            for (List<Integer> integers : edge_attr) {
                for (Integer integer : integers) {
                    dos.writeInt(integer);
                }
            }
            //3376字节
            for (List<Float> floats : branchFeatureList) {      //分支特征参数长度[211,4],字节数：[1688:5064]
                for (Float aFloat : floats) {
                    dos.writeFloat(aFloat);
                }
            }
            //123208字节
            for (List<Float> floats : circuitCostList) {        //回路成本加湿区分支点成本，长度:[175*176],字节数：[5064:128264]
                for (Float aFloat : floats) {
                    dos.writeFloat(aFloat);
                }
            }
            dos.writeFloat(totalCost);
            dos.writeFloat(baseWeight);
            dos.writeFloat(baseLength);
        }
        dos.close();
        System.out.println("json文件已生成");
    }

    public void projectCalculate(List<String> normList, List<List<String>> changeList, List<Map<String, Object>> edges,
                                   List<Map<String, String>> appPositions, Map<String, String> eleclection,
                                   Map<String, Map<String, List<String>>> mutexMap,
                                   List<Map<String, List<String>>> chooseOneList,
                                   List<List<String>> togetherBCList,Map<String, Object> jsonMap,Map<String, Map<String, String>> elecFixedLocationLibrary,
                                   Map<String,List<String>> togetherBCMap,Map<String, Map<String, List<String>>> chooseOneMap,String filePath) throws Exception{
        HarnessBranchTopoOptimize harnessBranchTopoOptimize = new HarnessBranchTopoOptimize();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();
        List<Callable<Map<String,Object>>> tasks = new ArrayList<>();
        List<String> edgesTemp = changeList.get(0);
        List<Map<String, Object>> edgeFirst = harnessBranchTopoOptimize.createNewEdges(edgesTemp, edges, normList);
        List<Map<String,Object>> allResult = new ArrayList<>();
        //分支id分配编号
//        Map<String, String> branchIdMap = new HashMap<>();
//        //分支起点和终点对应的编号
//        Map<String,String> branchPointIdMap = new HashMap<>();
        List<String> startNameList = new ArrayList<>();
        List<String> endNameList = new ArrayList<>();
//        int j = 0;
        for (int i = 0; i < edgeFirst.size(); i++) {
            //生成起点和终点列表
            Map<String,Object> branch = edgeFirst.get(i);
            startNameList.add(branch.get("startPointName").toString());
            endNameList.add(branch.get("endPointName").toString());
//            Map<String, Object> branch = edgeFirst.get(i-1);
//            branchIdMap.put(branch.get("id").toString(),"E" + i);
//            //如果库里没有对应分支编号
//            if(branchPointIdMap.get(branch.get("startPointName").toString()) == null || "".equals(branchPointIdMap.get(branch.get("startPointName").toString()))){
//                branchPointIdMap.put(branch.get("startPointName").toString(),"N" + ++j);
//            }
//            if(branchPointIdMap.get(branch.get("endPointName").toString()) == null || "".equals(branchPointIdMap.get(branch.get("endPointName").toString()))){
//                branchPointIdMap.put(branch.get("endPointName").toString(),"N" + ++j);
//            }
        }
        System.out.println("一共需要生成的样本数量：" + changeList.size());
        //分支起点集合编号
//        List<String> branchStartPointList = new ArrayList<>();
        //分支终点集合编号
//        List<String> branchEndPointList = new ArrayList<>();
        //分支id集合编号
//        List<String> branchIdList = new ArrayList<>();
        //分支点ID列表集合编号
//        Set<String> branchIdPointLiST = new LinkedHashSet<>();
        //有顺序的分支点名称列表
        Set<String> branchPointNameList = new LinkedHashSet<>();
        //构建分支有关的数据
        for (int i = 0; i < normList.size(); i++) {
//            branchIdList.add(branchIdMap.get(normList.get(i)));
            for (Map<String, Object> newEdge : edgeFirst) {
                if(newEdge.get("id").equals(normList.get(i))){
                    String startPointName = newEdge.get("startPointName").toString();
                    String endPointName = newEdge.get("endPointName").toString();
//                    branchIdPointLiST.add(branchPointIdMap.get(startPointName));
//                    //起点编号存储
//                    branchStartPointList.add(branchPointIdMap.get(startPointName));
//                    branchIdPointLiST.add(branchPointIdMap.get(endPointName));
//                    //终点编号存储
//                    branchEndPointList.add(branchPointIdMap.get(endPointName));
                    //名称添加
                    branchPointNameList.add(startPointName);
                    branchPointNameList.add(endPointName);
                    break;
                }
            }
        }
        List<String> allNameList = new ArrayList<>(branchPointNameList);
        List<List<Integer>> edgeAttr = new ArrayList<>();
        //起点和终点二进制化，转为AI可直接训练的形式,长度[2,211]
        List<Integer> startIndex = new ArrayList<>();
        List<Integer> endIndex = new ArrayList<>();
        for (String s : startNameList) {
            startIndex.add(allNameList.indexOf(s));
        }
        for (String s : endNameList) {
            endIndex.add(allNameList.indexOf(s));
        }
        edgeAttr.add(startIndex);
        edgeAttr.add(endIndex);
        //分支长度集合
        Map<String,Object> lengthMap = getBranchLength(normList, edgeFirst);
        List<Float> branchLengthList = (List<Float>)lengthMap.get("branchLength");
//        Float minLength = Float.parseFloat(lengthMap.get("minLength").toString());
//        Float maxLength = Float.parseFloat(lengthMap.get("maxLength").toString());
//        Map<String, Float> costMinMax = TypeCheckUtils.costMinMax;
//        if(costMinMax.get("minLength") == null){
//            costMinMax.put("minLength",minLength);
//        }else {
//            costMinMax.put("minLength",Math.min(costMinMax.get("minLength"),minLength));
//        }
//        if(costMinMax.get("maxLength") == null){
//            costMinMax.put("maxLength",maxLength);
//        }else {
//            costMinMax.put("maxLength",Math.max(costMinMax.get("maxLength"),maxLength));
//        }
        //对所有方案进行计算
        for (List<String> list : changeList) {
            tasks.add(() -> {
                Map<String,Object> result = new LinkedHashMap<>();
                //对每个方案进行整车计算
                //创建新的边
                if(list == null){
                    return null;
                }
                List<Map<String, Object>> newEdges = harnessBranchTopoOptimize.createNewEdges(list, edges, normList);
//                List<List<String>> lists = harnessBranchTopoOptimize.recognizeLoopNew(newEdges);
                Map<String ,Object> jsonMapCopy = new HashMap<>(jsonMap);
                jsonMapCopy.put("edges",newEdges);
                String projectInfo = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMapCopy));
                if(projectInfo == null || "".equals(projectInfo)){
                    return null;
                }
                Map<String, Object> stringObjectMap = jsonToMap.TransJsonToMap(projectInfo);
                Map<String,Object>  projectCircuitlnfo = (Map<String,Object>)stringObjectMap.get("projectCircuitInfo");
                Float baseCost = Float.parseFloat(projectCircuitlnfo.get("总成本").toString());
                Float baseWeight = Float.parseFloat(projectCircuitlnfo.get("回路总重量").toString());
                Float baseLength = Float.parseFloat(projectCircuitlnfo.get("回路总长度").toString());

                //分支特征参数列表 B：[0,0,0],C[0,1,0],S[0,0,1]
                List<List<Float>> branchFeatureList = new ArrayList<>();
                //状态转换
                for (String s : list) {
                    //默认断开
                    List<Float> statue = new ArrayList<>();
                    switch ( s){
                        case "B":
                            statue = new ArrayList<>(Arrays.asList(0.0f,0.0f,0.0f));
                            break;
                        case "C":
                            statue = new ArrayList<>(Arrays.asList(0.0f,1.0f,0.0f));
                            break;
                        case "S":
                            statue = new ArrayList<>(Arrays.asList(0.0f,0.0f,1.0f));
                            break;
                        default:
                            break;
                    }
                    branchFeatureList.add( statue);
                }
                for (int i = 0; i < branchLengthList.size(); i++) {
                    List<Float> integers = branchFeatureList.get(i);
                    integers.add(branchLengthList.get(i));
                }

                //分支点之间的连接关系,回路连接表达,这是预测成本需要用到的数据
                Map<String,Object> circuitCostList = calculateConnect(stringObjectMap, branchPointNameList, elecFixedLocationLibrary,jsonMapCopy);
                //获取单位成本和湿区成本
                List<List<Float>> circuitCost = (List<List<Float>>)circuitCostList.get("circuitCost");

                //构造1维湿区成本矩阵
//                List<Float> wetCostList = new ArrayList<>();
//                Set<String> strings = wetCostMap.keySet();
//                //这里只计算分支点的出度的回路湿区成本总值
//                for (String s : branchPointNameList) {
//                    Float wetCost = 0;
//                    for (String string : strings) {
//                        String s1 = string.split("-")[0];
//                        if(s.equals(s1)){
//                            Float v = wetCostMap.get(string);
//                            wetCost += v;
//                        }
//                    }
//                    wetCostList.add(wetCost);
//                }
//                Float calculateMinCost = (Float) circuitCostList.get("minWetCost");
//                Float calculateMaxCost = (Float) circuitCostList.get("maxWetCost");

//                int[][] circuitMatrix = calculateCircuit(stringObjectMap, branchPointNameList, elecFixedLocationLibrary, jsonMapCopy);
                //分支约束检测Label
//                Map<String, Boolean> stringBooleanMap = checkFirstOption(normList, list, newEdges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
//                List<Boolean> flags = new ArrayList<>();
                // 用电器特征,分支点表示，这个分支点位置有用电器则用1，没有用0
                //用电器列表特征
//                int[] elecList = new int[branchIdPointLiST.size()];
//                List<Map<String,Object>> circuitInfo = (List<Map<String,Object>>)stringObjectMap.get("circuitInfo");
//                //TODO 焊点也用1表示的话，有的焊点位置为空,可以遇到焊点直接标注1，这里先默认焊点用1，不考虑为什么焊点位置为null
//                for (Map<String, Object> objectMap : circuitInfo) {
//                    List<String> list1 = new ArrayList<>(branchIdPointLiST);
//
//                    //获取回路起点和终点位置，焊点也算用电器
//                    //回路起点和终点对应的分支点编号
//                    Object circuitStartPointName = objectMap.get("起点位置名称") != null? objectMap.get("起点位置名称") : objectMap.get("焊点位置名称");
//                    Object circuitEndPointName = objectMap.get("终点位置名称") != null? objectMap.get("终点位置名称") : objectMap.get("焊点位置名称");
//                    if(circuitStartPointName != null){
//                        String s = branchPointIdMap.get(circuitStartPointName.toString());
//                        int i = list1.indexOf(s);
//                        elecList[i] = 1;
//                    }
//                    if(circuitEndPointName != null){
//                        String s = branchPointIdMap.get(circuitEndPointName.toString());
//                        int i = list1.indexOf(s);
//                        elecList[i] = 1;
//                    }
//
//                }
//                flags.add(stringBooleanMap.get("breakRec"));        //回路是否打通
//                flags.add(stringBooleanMap.get("existEdge"));       //用电器周围是否存在分支
                //TODO 矩阵位置的随意变换，也就是用电器位置




                // TODO 训练集返回最终返回结果(确实分支特征比如互斥那些)    分支的干湿
                result.put("edge_attr",edgeAttr);           //连接关系属性edge属性
                result.put("branchFeatureList",branchFeatureList);      //分支特征参数(通，断)
//                result.put("branchLength",branchLengthList);            //分支特征参数2(长度)
//                result.put("branchIdPointList",branchIdPointLiST);      //分支点id列表
//                result.put("circuitMatrix",circuitMatrix);              //回路矩阵信息，用于节点对取GINE模型节点训练MLP
                result.put("circuitCostList",circuitCost);          //分支点特征(连通的显示为导线商务单价，元/米),有单价的表示两点之间有回路+湿区成本总和
//                result.put("circuitDryWet",circuitCostList.get("circuitDryWet"));           //回路干湿(回路为湿显示湿区成本)
                //TODO 这里先用一维向量表示用电器特征，看是否为每个分支点分配向量
//                result.put("elecList",Arrays.asList(elecList));             //用电器特征(分支点有用电器表示为1)

                // Label标签
//                result.put("breakRec", stringBooleanMap.get("breakRec")? 1 : 0);                //拓扑连通
//                result.put("existEdge", stringBooleanMap.get("existEdge")? 1 : 0);              //用电器周围至少存在一个分支
                result.put("totalCost", baseCost);                                              //总成本
                result.put("baseWeight",baseWeight);                                            //总重量
                result.put("baseLength", baseLength);                                           //总长度
//                flags.add(true);
                TypeCheckUtils.countType("type1");

                return result;
            });
        }
        //获取结果
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Callable<Map<String, Object>> task : tasks) {
            if(task != null) {
                futures.add(HarnessBranchTopoOptimize.threadPool.submit(task));
            }
        }
        for (Future<Map<String, Object>> future : futures) {
            try {
                Map<String, Object> result = future.get( 600, TimeUnit.SECONDS);
                if(result != null) {
                    allResult.add(result);
                }
            }catch (Exception e){
//                e.printStackTrace();
            }
        }
        //文件直接写入
        exportJsonTwo(allResult,filePath);
//        String s = objectMapper.writeValueAsString(allResult);
//        System.out.println("生成的各类型方案数量:" + TypeCheckUtils.getAllTypeCounts());
//        return s;
    }

    /**
     * 分支长度
     * @param normList  分支排列顺序id
     * @param edges
     * @return
     */
    public Map<String,Object> getBranchLength(List<String> normList, List<Map<String, Object>> edges){
        List<Float> branchLengthList = new ArrayList<>();
        Map<String,Object> result = new HashMap<>();
        DecimalFormat df = new DecimalFormat("0.0000");
        for (String branchId : normList) {
            Float length = 0.0f;
            for (Map<String, Object> edge : edges) {
                if(branchId.equals(edge.get("id"))){
                    //参考长度
                    String referenceLength = null;
                    //用户确认的分支长度
                    String verifyLength = null;
                    //参考长度
                    if (edge.get("referenceLength") != null) {
                        referenceLength = String.valueOf(edge.get("referenceLength"));
                    }
                    //用户确认的分支长度
                    if (edge.get("length") != null) {
                        verifyLength = String.valueOf(edge.get("length"));
                    }
                    if (verifyLength != null && !verifyLength.isEmpty()) {
                        length += Float.parseFloat(verifyLength);
                    } else {
                        if (!referenceLength.isEmpty()) {
                            length += Float.parseFloat(referenceLength);
                        } else if("C".equals(edge.get("topologyStatusCode")) || "S".equals(edge.get("topologyStatusCode"))){
                            length += 200;
                        }else {
                            //打断状态直接设0
                            length = 0f;
                        }
                    }
                    break;
                }
            }
            branchLengthList.add(Float.parseFloat(df.format(length)));
        }
        result.put("branchLength",branchLengthList);
        return result;
    }

    /**
     * 回路连接关系表示
     * @param stringObjectMap
     * @return
     */
    public Map<String,Object> calculateConnect(Map<String, Object> stringObjectMap,Set<String> branchPointNameList,Map<String, Map<String, String>> elecFixedLocationLibrary,Map<String ,Object> jsonMapCopy){
        //获取回路信息
        List<Map<String, Object>> circuitList = (List<Map<String, Object>>) stringObjectMap.get("circuitInfo");
        //各回路导线价格元/米
        List<List<Float>> circuitCost = new ArrayList<>();
        List<Map<String,String>> points = (List<Map<String,String>>)jsonMapCopy.get("points");
        //各回路干湿（回路湿区成本加成）
        List<List<Float>> circuitDryWet = new ArrayList<>();
        Map<String,Object> result = new HashMap<>();
        DecimalFormat df = new DecimalFormat("0.0000");
        List<Float> perPriceCompare = new ArrayList<>();
        //针对两个节点之间有多个map创建
        Map<String,Float> perPriceMap = new HashMap<>();
        Map<String,Float> wetCostMap = new HashMap<>();
        //查找两个位置点之间的回路
        for (String startPointName : branchPointNameList) {
            List<Float> circuitPrice = new ArrayList<>();
            List<Float> circuitDryWetList = new ArrayList<>();
            String endName = null;
            for (String endPointName : branchPointNameList) {
                endName = endPointName;
                //元/米 没有相关联的回路，默认为0
                Float perPrice = 0.0f;
                //TODO 这里用湿区成本展示
                Float cost = 0.0f;
                for (Map<String, Object> objectMap : circuitList) {
                    //获取起点位置名称和终点位置名称
                    //回路两端都是湿才为湿
                    Object circuitStartPointName = objectMap.get("起点位置名称") != null? objectMap.get("起点位置名称") : objectMap.get("焊点位置名称");
                    Object circuitEndPointName = objectMap.get("终点位置名称") != null? objectMap.get("终点位置名称") : objectMap.get("焊点位置名称");
                    if((startPointName.equals(circuitStartPointName) && endPointName.equals(circuitEndPointName))){
                        String wireType = objectMap.get("导线选型").toString();
                        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
                         perPrice = Float.parseFloat(map.get("导线单位商务价（元/米）"));
                        if(perPriceMap.get(startPointName + "-" + endPointName) == null) {
                            perPriceMap.put(startPointName + "-" + endPointName, perPrice);
                        }else{
                            Float v = perPriceMap.get(startPointName + "-" + endPointName);
                            perPriceMap.put(startPointName + "-" + endPointName, v + perPrice);
                        }
                        cost = Float.parseFloat(objectMap.get("回路湿区成本加成").toString());
                        if(wetCostMap.get(startPointName + "-" + endPointName) == null){
                            wetCostMap.put(startPointName + "-" + endPointName, cost);
                        }else {
                            Float v = wetCostMap.get(startPointName + "-" + endPointName);
                            wetCostMap.put(startPointName + "-" + endPointName, v + cost);
                        }

                    }
                }

                Float resultPerPrice = 0.0f;
                Float FloatIntegerMap = perPriceMap.get(startPointName + "-" + endPointName);
                if(FloatIntegerMap != null){
                    resultPerPrice = FloatIntegerMap;
                    perPriceCompare.add(resultPerPrice);
                }

                //导线商务
                circuitPrice.add(Float.parseFloat(df.format(resultPerPrice)));
            }
            //湿区成本初始化
            Float resultWetCost = 0.0f;
            Float v = wetCostMap.get(startPointName + "-" + endName);
            if(v != null){
                resultWetCost = v;
            }
            circuitPrice.add(Float.parseFloat(df.format(resultWetCost)));
            //一对回路单价完成
            circuitCost.add(circuitPrice);
            circuitDryWet.add(circuitDryWetList);
        }
        result.put("circuitCost",circuitCost);
        result.put("wetCostMap",wetCostMap);
        return result;
    }

    /**
     * 回路邻接矩阵构建
     * @param stringObjectMap
     * @param branchPointNameList
     * @param elecFixedLocationLibrary
     * @param jsonMapCopy
     * @return
     */
    public int[][] calculateCircuit(Map<String, Object> stringObjectMap,Set<String> branchPointNameList,Map<String, Map<String, String>> elecFixedLocationLibrary,Map<String ,Object> jsonMapCopy) {
        int[][] circuitMatrix = new int[branchPointNameList.size()][branchPointNameList.size()];
        List<Map<String, Object>> circuitList = (List<Map<String, Object>>) stringObjectMap.get("circuitInfo");
        List<String> branchPointNameListCopy = new ArrayList<>(branchPointNameList);
        for (Map<String, Object> objectMap : circuitList) {
            Object circuitStartPointName = objectMap.get("起点位置名称") != null? objectMap.get("起点位置名称") : objectMap.get("焊点位置名称");
            Object circuitEndPointName = objectMap.get("终点位置名称") != null? objectMap.get("终点位置名称") : objectMap.get("焊点位置名称");
            //有些焊点没有位置名称，前端给的数据没有
            if(circuitStartPointName == null || circuitEndPointName == null){
                continue;
            }
            circuitMatrix[branchPointNameListCopy.indexOf(circuitStartPointName.toString())][branchPointNameListCopy.indexOf(circuitEndPointName.toString())] = 1;
        }
        return circuitMatrix;
    }

    /**
     * 判断回路干湿
     * @return
     */
    public String getWaterParam(String name, List<Map<String, String>> maps) {
        for (Map<String, String> map : maps) {
            if (name.equals(map.get("pointName"))) {
                return map.get("waterParam");
            }
        }
        return null;
    }

    /**
     * @Description: 对生成的方案进行一个检查： 1、是否存在互斥的情况 2、回路是否导通 3、用电器周围是否至少存在一个分支
     * @input: normList   当前分支id的排序情况
     * @input: changeList  分支的打断状况
     * @input: edges  生成需要检查的分支
     * @input: appPositions 没有解析txt中的用电器像信息
     * @input: eleclection  用电器对应的位置
     * @input: mutexMap   互斥的情况集合
     * @Return: 根据给定的方案检查   返回是否符合的状态
     */
    public Map<String,Boolean> checkFirstOption(List<String> normList, List<String> changeList, List<Map<String, Object>> edges,
                                                List<Map<String, String>> appPositions, Map<String, String> eleclection,
                                                Map<String, Map<String, List<String>>> mutexMap,
                                                List<Map<String, List<String>>> chooseOneList,
                                                List<List<String>> togetherBCList) {
        Map<String,Boolean> result = new LinkedHashMap<>();
        Map<String,Boolean> returnResult = new LinkedHashMap<>();


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

        FindTopoBreak breakRecognize = new FindTopoBreak();
        List<List<String>> breakRec = breakRecognize.recognizeBreak(adjacencyMatrixGraph.getAdj(),
                adjacencyMatrixGraph.getAllPoint());
//            2、 每个用电器周围至少存在一个分支  3、生成的方案必须使得每个回路导通
        if (breakRec.size() != 1) {
            result.put("breakRec", false);
        }else if(breakRec.size() == 1){
            result.put("breakRec", true);
        }else {
            result.put("breakRec", false);
        }

//        Boolean existEdge = true;
//
//        for (Map<String, String> appPosition : appPositions) {
//            if (!appPosition.get("appName").startsWith("[")) {
//                String pointName = eleclection.get(appPosition.get("appName"));
//                if (!checkElecEdge(pointName, edges)) {
//                    result.put("existEdge", false);
//                    existEdge = false;
//                    break;
//                }
//            }
//        }

//        if ( existEdge) {
//            result.put("existEdge", true);
//        }
        returnResult.put("breakRec", result.get("breakRec"));
//        returnResult.put("existEdge", result.get("existEdge"));
        return returnResult;
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
}
