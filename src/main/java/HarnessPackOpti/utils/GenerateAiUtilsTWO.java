package HarnessPackOpti.utils;

import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.elec.ElecPositionVariantCalculation;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.ls.LSException;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 负责AI训练的样本生成,方案二，值判断是否连通以及周围存在分支
 */
public class GenerateAiUtilsTWO {
    public static ElecPositionVariantCalculation elecPositionVariantCalculation = new ElecPositionVariantCalculation();

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
    public void exportJsonTwo(List<String> normList, List<List<String>> changeList, List<Map<String, Object>> edges,
                           List<Map<String, String>> appPositions, Map<String, String> eleclection,
                           Map<String, Map<String, List<String>>> mutexMap,
                           List<Map<String, List<String>>> chooseOneList,
                           List<List<String>> togetherBCList,Map<String, Object> jsonMap,Map<String, Map<String, String>> elecFixedLocationLibrary,
                           Map<String,List<String>> togetherBCMap,Map<String, Map<String, List<String>>> chooseOneMap) throws Exception{
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\caseTwo.txt");
        String json = projectCalculate(normList, changeList, edges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList,jsonMap,elecFixedLocationLibrary,togetherBCMap,chooseOneMap);
        Files.write(file.toPath(),json.getBytes());
        System.out.println("json文件已生成");
    }

    public String projectCalculate(List<String> normList, List<List<String>> changeList, List<Map<String, Object>> edges,
                                   List<Map<String, String>> appPositions, Map<String, String> eleclection,
                                   Map<String, Map<String, List<String>>> mutexMap,
                                   List<Map<String, List<String>>> chooseOneList,
                                   List<List<String>> togetherBCList,Map<String, Object> jsonMap,Map<String, Map<String, String>> elecFixedLocationLibrary,
                                   Map<String,List<String>> togetherBCMap,Map<String, Map<String, List<String>>> chooseOneMap) throws Exception{
        HarnessBranchTopoOptimize harnessBranchTopoOptimize = new HarnessBranchTopoOptimize();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();
        List<Callable<Map<String,Object>>> tasks = new ArrayList<>();
        TypeCheckUtils typeCheckUtils = new TypeCheckUtils();
        List<Map<String,Object>> allResult = new ArrayList<>();
        System.out.println("一共需要生成的样本数量：" + changeList.size());
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
                List<List<String>> lists = harnessBranchTopoOptimize.recognizeLoopNew(newEdges);
                Map<String ,Object> jsonMapCopy = new HashMap<>(jsonMap);
                jsonMapCopy.put("edges",newEdges);
                //TODO 这里有可能需要重新启方法，只需要成本等字段，会有其他逻辑拦截
                String projectInfo = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMapCopy));
                if(projectInfo == null || "".equals(projectInfo)){
                    return null;
                }
                Map<String, Object> stringObjectMap = jsonToMap.TransJsonToMap(projectInfo);
                Map<String,Object>  projectCircuitlnfo = (Map<String,Object>)stringObjectMap.get("projectCircuitInfo");
                double baseCost = (Double) projectCircuitlnfo.get("总成本");
                double baseWeight = (Double) projectCircuitlnfo.get("回路总重量");
                double baseLength = (Double) projectCircuitlnfo.get("回路总长度");
                //分支id分配编号
                Map<String, String> branchIdMap = new HashMap<>();
                //分支起点和终点对应的编号
                Map<String,String> branchPointIdMap = new HashMap<>();
                //分支特征参数列表 B：[0,0,0],C[0,1,0],S[0,0,1]
                List<List<Integer>> branchFeatureList = new ArrayList<>();
                int j = 0;
                for (int i = 1; i <= newEdges.size(); i++) {
                    Map<String, Object> branch = newEdges.get(i-1);
                    branchIdMap.put(branch.get("id").toString(),"E_" + i);
                    //如果库里没有对应分支编号
                    if(branchPointIdMap.get(branch.get("startPointName").toString()) == null || "".equals(branchPointIdMap.get(branch.get("startPointName").toString()))){
                        branchPointIdMap.put(branch.get("startPointName").toString(),"N_" + (j + i));
                    }
                    if(branchPointIdMap.get(branch.get("endPointName").toString()) == null || "".equals(branchPointIdMap.get(branch.get("endPointName").toString()))){
                        j++;
                        branchPointIdMap.put(branch.get("endPointName").toString(),"N_" + ++j);
                    }
                }
                //状态转换
                for (String s : list) {
                    //默认断开
                    List< Integer> statue = new ArrayList<>();
                    switch ( s){
                        case "B":
                            statue = Arrays.asList(0,0,0);
                            break;
                        case "C":
                            statue = Arrays.asList(0,1,0);
                            break;
                        case "S":
                            statue = Arrays.asList(0,0,1);
                            break;
                        default:
                            break;
                    }
                    branchFeatureList.add( statue);
                }
                //分支起点集合编号
                List<String> branchStartPointList = new ArrayList<>();
                //分支终点集合编号
                List<String> branchEndPointList = new ArrayList<>();
                //分支id集合编号
                List<String> branchIdList = new ArrayList<>();
                //分支点ID列表集合编号
                Set<String> branchIdPointLiST = new LinkedHashSet<>();
                //有顺序的分支点名称列表
                Set<String> branchPointNameList = new LinkedHashSet<>();
                //构建分支有关的数据
                for (int i = 0; i < normList.size(); i++) {
                    branchIdList.add(branchIdMap.get(normList.get(i)));
                    for (Map<String, Object> newEdge : newEdges) {
                        if(newEdge.get("id").equals(normList.get(i))){
                            String startPointName = newEdge.get("startPointName").toString();
                            String endPointName = newEdge.get("endPointName").toString();
                            branchIdPointLiST.add(branchPointIdMap.get(startPointName));
                            //起点编号存储
                            branchStartPointList.add(branchPointIdMap.get(startPointName));
                            branchIdPointLiST.add(branchPointIdMap.get(endPointName));
                            //终点编号存储
                            branchEndPointList.add(branchPointIdMap.get(endPointName));
                            //名称添加
                            branchPointNameList.add(startPointName);
                            branchPointNameList.add(endPointName);
                        }
                    }
                }
                //分支长度集合
                List<Double> branchLengthList = getBranchLength(normList, newEdges);
                //分支点之间的连接关系,回路连接表达
                Map<String,Object> circuitCostList = calculateConnect(stringObjectMap, branchPointNameList, elecFixedLocationLibrary,jsonMapCopy);
                //分支约束检测Label
                Map<String, Boolean> stringBooleanMap = checkFirstOption(normList, list, newEdges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                List<Boolean> flags = new ArrayList<>();
                //TODO 用电器特征,分支点表示，这个分支点位置有用电器则用1，没有用0
                //用电器列表特征
                int[] elecList = new int[branchIdPointLiST.size()];
                List<Map<String,Object>> circuitInfo = (List<Map<String,Object>>)stringObjectMap.get("circuitInfo");
                for (Map<String, Object> objectMap : circuitInfo) {
                    //获取回路起点和终点位置，焊点也算用电器
                    Object circuitStartPointName = objectMap.get("起点位置名称") != null? objectMap.get("起点位置名称") : objectMap.get("焊点位置名称");
                    Object circuitEndPointName = objectMap.get("终点位置名称") != null? objectMap.get("终点位置名称") : objectMap.get("焊点位置名称");
                    //回路起点和终点对应的分支点编号
                    if(circuitStartPointName == null){
                        System.out.println("null");
                    }
                    String startStr = branchPointIdMap.get(circuitStartPointName.toString());

                    String endStr = branchPointIdMap.get(circuitEndPointName.toString());
                    List<String> list1 = new ArrayList<>(branchIdPointLiST);
                    int i = list1.indexOf(startStr);
                    int k = list1.indexOf(endStr);
                    elecList[i] = 1;
                    elecList[k] = 1;
                }
                flags.add(stringBooleanMap.get("breakRec"));        //回路是否打通
                flags.add(stringBooleanMap.get("existEdge"));       //用电器周围是否存在分支
                typeCheckUtils.getType(flags);




                // TODO 训练集返回最终返回结果(确实分支特征比如互斥那些)    分支的干湿
                result.put("branchStartList",branchStartPointList);     //分支起点
                result.put("branchEndList",branchEndPointList);         //分支终点
                result.put("branchIdList",branchIdList);                //分支id列表
                result.put("branchFeatureList",branchFeatureList);      //分支特征参数(通，断)
                result.put("branchLength",branchLengthList);            //分支特征参数2(长度)
                result.put("branchIdPointList",branchIdPointLiST);      //分支点id列表
                result.put("circuitCostList",circuitCostList.get("circuitCost"));          //分支点特征(连通的显示为导线商务单价，元/米),有单价的表示两点之间有回路
                result.put("circuitDryWet",circuitCostList.get("circuitDryWet"));           //回路干湿(两端全为w为湿，否则为干)
                result.put("elecList",Arrays.asList(elecList));             //用电器特征(分支点有用电器表示为1)

                // Label标签
                result.put("breakRec", stringBooleanMap.get("breakRec")? 1 : 0);                //拓扑连通
                result.put("existEdge", stringBooleanMap.get("existEdge")? 1 : 0);              //用电器周围至少存在一个分支
                result.put("totalCost", baseCost);                                              //总成本
                result.put("baseWeight",baseWeight);                                            //总重量
                result.put("baseLength", baseLength);                                           //总长度
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
                e.printStackTrace();
            }

        }
        String s = objectMapper.writeValueAsString(allResult);
        System.out.println("生成的各类型方案数量:" + TypeCheckUtils.getAllTypeCounts());
        return s;
    }

    /**
     * 分支长度
     * @param normList  分支盘列顺序id
     * @param edges
     * @return
     */
    public List<Double> getBranchLength(List<String> normList, List<Map<String, Object>> edges){
        List<Double> branchLengthList = new ArrayList<>();
        for (String branchId : normList) {
            double length = 0.0;
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
                        length += Double.parseDouble(verifyLength);
                    } else {
                        if (!referenceLength.isEmpty()) {
                            length += Double.parseDouble(referenceLength);
                        } else if("C".equals(edge.get("topologyStatusCode")) || "S".equals(edge.get("topologyStatusCode"))){
                            length += 200;
                        }else {
                            //打断状态直接设0
                            length = 0;
                        }
                    }
                    break;
                }
            }
            branchLengthList.add(length);
        }
        return branchLengthList;
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
        List<List<Double>> circuitCost = new ArrayList<>();
        List<Map<String,String>> points = (List<Map<String,String>>)jsonMapCopy.get("points");
        //各回路干湿
        List<List<Integer>> circuitDryWet = new ArrayList<>();
        Map<String,Object> result = new HashMap<>();;
        //查找两个位置点之间的回路
        for (String startPointName : branchPointNameList) {
            List<Double> circuitPrice = new ArrayList<>();
            List<Integer> circuitDryWetList = new ArrayList<>();
            for (String endPointName : branchPointNameList) {
                //元/米 没有相关联的回路，默认为0
                double perPrice = 0.0;
                int statue = 0;
                for (Map<String, Object> objectMap : circuitList) {
                    //获取起点位置名称和终点位置名称
                    //回路两端都是湿才为湿
                    Object circuitStartPointName = objectMap.get("起点位置名称") != null? objectMap.get("起点位置名称") : objectMap.get("焊点位置名称");
                    Object circuitEndPointName = objectMap.get("终点位置名称") != null? objectMap.get("终点位置名称") : objectMap.get("焊点位置名称");
                    if((startPointName.equals(objectMap.get("起点位置名称") != null? objectMap.get("起点位置名称") : objectMap.get("焊点位置名称")) && endPointName.equals(objectMap.get("终点位置名称") != null? objectMap.get("终点位置名称") : objectMap.get("焊点位置名称")))){
                        String wireType = objectMap.get("导线选型").toString();
                        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
                        perPrice = Double.parseDouble(map.get("导线单位商务价（元/米）"));
                        String waterStatNameParam = elecPositionVariantCalculation.getWaterParam(circuitStartPointName.toString(), points);
                        String waterEndNameParam = elecPositionVariantCalculation.getWaterParam(circuitEndPointName.toString(), points);
                        if("w".toUpperCase().equals(waterStatNameParam) && "w".toUpperCase().equals(waterEndNameParam)){
                            statue = 1;
                        }
                        break;
                    }
                }
                circuitPrice.add(perPrice);
                circuitDryWetList.add(statue);
            }
            //一对回路单价完成
            circuitCost.add(circuitPrice);
            circuitDryWet.add(circuitDryWetList);
        }
        result.put("circuitCost",circuitCost);
        result.put("circuitDryWet",circuitDryWet);
        return result;
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

        Boolean existEdge = true;

        for (Map<String, String> appPosition : appPositions) {
            if (!appPosition.get("appName").startsWith("[")) {
                String pointName = eleclection.get(appPosition.get("appName"));
                if (!checkElecEdge(pointName, edges)) {
                    result.put("existEdge", false);
                    existEdge = false;
                    break;
                }
            }
        }

        if ( existEdge) {
            result.put("existEdge", true);
        }
        returnResult.put("breakRec", result.get("breakRec"));
        returnResult.put("existEdge", result.get("existEdge"));
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
