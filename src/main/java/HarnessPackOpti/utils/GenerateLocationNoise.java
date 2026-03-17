package HarnessPackOpti.utils;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用电器位置扰动
 */
public class GenerateLocationNoise {
    Random random = new Random();

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

    public void generateLocationNoise(List<String> normList, List<List<String>> changeList, List<Map<String, Object>> edges,
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
        TypeCheckUtils typeCheckUtils = new TypeCheckUtils();
        //记录所有特征字段的最大最小值，方便统计归一化
        List<Float> perPriceCompare = new ArrayList<>();
        elecFixedLocationLibrary.forEach((key,value)->{
            String s = value.get("导线单位商务价（元/米）");
            perPriceCompare.add(Float.parseFloat(s));
        });
        List<String> edgesTemp = changeList.get(0);
        List<Map<String, Object>> edgeFirst = harnessBranchTopoOptimize.createNewEdges(edgesTemp, edges, normList);

        List<Map<String,Object>> allResult = new ArrayList<>();
        List<String> startNameList = new ArrayList<>();
        List<String> endNameList = new ArrayList<>();
//        int j = 0;
        for (int i = 0; i < edgeFirst.size(); i++) {
            //生成起点和终点列表
            Map<String,Object> branch = edgeFirst.get(i);
            startNameList.add(branch.get("startPointName").toString());
            endNameList.add(branch.get("endPointName").toString());

        }
        System.out.println("一共需要生成的样本数量：" + changeList.size());
        //有顺序的分支点名称列表
        Set<String> branchPointNameList = new LinkedHashSet<>();
        //构建分支有关的数据
        for (int i = 0; i < normList.size(); i++) {
            for (Map<String, Object> newEdge : edgeFirst) {
                if(newEdge.get("id").equals(normList.get(i))){
                    String startPointName = newEdge.get("startPointName").toString();
                    String endPointName = newEdge.get("endPointName").toString();
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
        //拿到用电器所有位置
        List<Map<String,String>>  eleclection1 = getEleclection(appPositions);

        //对所有方案进行计算
        for (List<String> list : changeList) {
            tasks.add(() -> {
                Map<String,Object> result = new LinkedHashMap<>();
                //对每个方案进行整车计算
                //创建新的边
                if(list == null){
                    return null;
                }
                //每个方案都是长度经过扰动的
                List<Map<String, Object>> newEdges = createNewEdges(list, edges, normList);
                //分支长度集合
                Map<String,Object> lengthMap = getBranchLength(normList, newEdges);
                List<Float> branchLengthList = (List<Float>)lengthMap.get("branchLength");
                Map<String ,Object> jsonMapCopy = new HashMap<>(jsonMap);
                jsonMapCopy.put("edges",newEdges);
                //用电器位置改变
                List<Map<String, String>> appPositionsCopy = (List<Map<String, String>>)jsonMapCopy.get("appPositions");
                int size = appPositionsCopy.size();
                int countToModify = (int) Math.ceil(size * 0.25);
                // 创建索引列表并打乱顺序
                List<Integer> indices = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    indices.add(i);
                }
                Collections.shuffle(indices);
                // 获取前 25% 的索引
                List<Integer> selectedIndices = indices.subList(0, Math.min(countToModify, size));
                for (Integer selectedIndex : selectedIndices) {
                    Map<String, String> map = appPositionsCopy.get(selectedIndex);
                    Map<String, String> randomMap = eleclection1.get(random.nextInt(eleclection1.size()));
                    String key = randomMap.keySet().iterator().next();
                    map.put("unregularPointId",randomMap.get(key));
                    map.put("unregularPointName",key);
                }
                jsonMapCopy.put("appPositions",appPositionsCopy);
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
                result.put("edge_attr",edgeAttr);           //连接关系属性edge属性
                result.put("branchFeatureList",branchFeatureList);      //分支特征参数(通，断)
                result.put("circuitCostList",circuitCost);          //分支点特征(连通的显示为导线商务单价，元/米),有单价的表示两点之间有回路+湿区成本总和
                result.put("totalCost", baseCost);                                              //总成本
                result.put("baseWeight",baseWeight);                                            //总重量
                result.put("baseLength", baseLength);                                           //总长度
                typeCheckUtils.getType("type3");
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
    }
    /**
     * @Description: 根据传入的分支打断状况  返回一条新的分支详情
     * @input: edgeStatue  分支打断状况
     * @input: edgeDetails   分支模板
     * @input: normList   分支的id编号
     * @Return: 根据传入的分支打断情况  创建一个分支详情
     */
    public List<Map<String, Object>> createNewEdges(List<String> edgeStatue, List<Map<String, Object>> edgeDetails, List<String> normList) {
        List<Map<String, Object>> newEdges = edgeDetails.stream().collect(Collectors.toList());
        for (Map<String, Object> newEdge : newEdges) {
            String id = (String) newEdge.get("id");
            int number = normList.indexOf(id);
            newEdge.put("topologyStatusCode", edgeStatue.get(number));
            //高斯扰动
            //参考长度
            if (newEdge.get("referenceLength") != null) {
                newEdge.put("referenceLength", perturbLengthGaussian((Float) newEdge.get("referenceLength")));
            }
            //用户确认的分支长度
            if (newEdge.get("length") != null) {
                newEdge.put("length", perturbLengthGaussian((Float) newEdge.get("length")));
            }
        }
        return newEdges;
    }

    //   找到所有用电器对应的位置点
    public List<Map<String,String>>  getEleclection(List<Map<String, String>> mapList) {
        List<Map<String,String>> nameList = new ArrayList<>();
        for (Map<String, String> stringMap : mapList) {
            Map<String,String> temp = new HashMap<>();
            String result = "";
            if (stringMap.get("unregularPointName") != null) {
                result = stringMap.get("unregularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") != null) {
                result = stringMap.get("regularPointName");
            } else if (stringMap.get("unregularPointName") == null && stringMap.get("regularPointName") == null) {
                result = null;
            }

            temp.put(result,stringMap.get("unregularPointId"));
            nameList.add( temp);
        }
//        System.out.println("从txt中读取到的用电器，经过位置判断后共计"+resultList.size()+"个");
        return nameList;
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
            float v = Float.parseFloat(df.format(length));
            double v1 = perturbLengthGaussian(v);
            Float resultV = (float) v1;
            branchLengthList.add(resultV);
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
     * 高斯扰动
     * 3*标准差=扰动范围，让分支的扰动更接近小偏差
     * @param originalLength
     * @return
     */
    double perturbLengthGaussian(Float originalLength) {
        // 均值1.0，标准差0.083，3σ ≈ 25%
        double factor = 1.0 + random.nextGaussian() * 0.083;
        // 截断，防止超出 ±25% 范围
        factor = Math.max(0.75, Math.min(1.25, factor));
        return originalLength * factor;
    }


}
