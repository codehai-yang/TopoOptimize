package HarnessPackOpti.utils;

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
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 扰动分支长度样本生成
 */
public class GenerateConnectNoise {
    Random random = new Random();

    public void generateConnectNoise(List<String> normList, List<List<String>> changeList, List<Map<String, Object>> edges,
                                 Map<String, Object> jsonMap,Map<String, Map<String, String>> elecFixedLocationLibrary,
                                 List<String> edgeChooseBS,String filePath) throws Exception {
        HarnessBranchTopoOptimize harnessBranchTopoOptimize = new HarnessBranchTopoOptimize();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        List<Map<String, String>> pointList = (List<Map<String, String>>)jsonMap.get("points");
        List<Map<String, String>> appPositions = (List<Map<String, String>>)jsonMap.get("appPositions");
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();
        List<Callable<Map<String,Object>>> tasks = new ArrayList<>();
        List<String> edgesTemp = changeList.get(0);
        List<Map<String, Object>> edgeFirst = harnessBranchTopoOptimize.createNewEdges(edgesTemp, edges, normList);
        List<String> startNameList = new ArrayList<>();
        List<String> endNameList = new ArrayList<>();
        Set<String> branchPointNameList = new HashSet<>();
        for (int i = 0; i < edgeFirst.size(); i++) {
            //生成起点和终点列表
            Map<String,Object> branch = edgeFirst.get(i);
            startNameList.add(branch.get("startPointName").toString());
            endNameList.add(branch.get("endPointName").toString());
            branchPointNameList.add(branch.get("startPointName").toString());
            branchPointNameList.add(branch.get("endPointName").toString());
        }
        List<String> allNameList = new ArrayList<>(branchPointNameList);
        //起点和终点二进制化，转为AI可直接训练的形式,长度[2,211]
        List<Integer> startIndex = new ArrayList<>();
        List<Integer> endIndex = new ArrayList<>();
        for (String s : startNameList) {
            startIndex.add(allNameList.indexOf(s));
        }
        for (String s : endNameList) {
            endIndex.add(allNameList.indexOf(s));
        }
        //最终索引形式
        int[][] edgeIndex = new int[2][startIndex.size()];
        for (int i = 0; i < startIndex.size(); i++) {
            edgeIndex[0][i] = startIndex.get(i);
            edgeIndex[1][i] = endIndex.get(i);
        }
        //分支长度集合
        List<Float> branchLengthList = SampleSave.getBranchLength( edgeFirst);
        //获取所有用电器名称
        List<String> allAppName = new ArrayList<>();
        for (Map<String, String> appPosition : appPositions) {
            if(!appPosition.get("appName").startsWith("[")) {
                allAppName.add(appPosition.get("appName"));
            }
        }
        for (List<String> list : changeList) {
            tasks.add(() -> {
                Map<String, Object> result = new HashMap<>();
                if (list == null) {
                    return null;
                }
                List<String> serviceableStatue = list.stream().collect(Collectors.toList());
                for (int i = 0; i < serviceableStatue.size(); i++) {
                    if (serviceableStatue.get(i).equals("C") && edgeChooseBS.contains(normList.get(i))) {
                        serviceableStatue.set(i, "S");
                    }
                }
                List<Map<String, Object>> newEdges = harnessBranchTopoOptimize.createNewEdges(serviceableStatue, edges, normList);
                Map<String, Object> jsonMapCopy = new HashMap<>(jsonMap);
                jsonMapCopy.put("edges", newEdges);

                List<Map<String,Object>> topoCopy = new ArrayList<>();
                List< Map<String, Object>> topo = (List< Map<String, Object>>) jsonMapCopy.get("loopInfos");
                for (Map<String, Object> stringObjectMap : topo) {
                    topoCopy.add(new HashMap<>(stringObjectMap));
                }
                //回路链接关系扰动
                int size = topoCopy.size();
                List<Integer> indexes = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    indexes.add(i);
                }
                Collections.shuffle(indexes);
                int countToModify = (int) Math.ceil(size * 0.25);
                // 获取前 25% 的索引
                List<Integer> selectedIndices = indexes.subList(0, Math.min(countToModify, size));

                for (Integer selectedIndex : selectedIndices) {
                    Map<String, Object> stringObjectMap = topoCopy.get(selectedIndex);
                    String startApp = stringObjectMap.get("startApp").toString();
                    String endApp = stringObjectMap.get("endApp").toString();
                    String s = allAppName.get(random.nextInt(allAppName.size()));
                    if(!startApp.equals(s)){
                        stringObjectMap.put("endApp",s);
                    }
                }

                jsonMapCopy.put("loopInfos", topoCopy);

                String projectInfo = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMapCopy));
                if (projectInfo == null || "".equals(projectInfo)) {
                    return null;
                }
                Map<String, Object> stringObjectMap = jsonToMap.TransJsonToMap(projectInfo);
                Map<String, Object> projectCircuitlnfo = (Map<String, Object>) stringObjectMap.get("projectCircuitInfo");
                Float baseCost = Float.parseFloat(projectCircuitlnfo.get("总成本").toString());
                Float baseWeight = Float.parseFloat(projectCircuitlnfo.get("回路总重量").toString());
                Float baseLength = Float.parseFloat(projectCircuitlnfo.get("回路总长度").toString());
                // 创建二维数组存储分支特征 [分支数量, 4]
                int branchCount = serviceableStatue.size();
                float[][] branchFeatureArray = new float[branchCount][4];

                //状态转换
                for (int i = 0; i < serviceableStatue.size(); i++) {
                    String s = serviceableStatue.get(i);
                    // 初始化前3列为0
                    branchFeatureArray[i][0] = 0.0f;
                    branchFeatureArray[i][1] = 0.0f;
                    branchFeatureArray[i][2] = 0.0f;

                    switch (s) {
                        case "B":
                            // 已经是 [0, 0, 0]，无需修改
                            break;
                        case "C":
                            branchFeatureArray[i][1] = 1.0f;  // [0, 1, 0]
                            break;
                        case "S":
                            branchFeatureArray[i][2] = 1.0f;  // [0, 0, 1]
                            break;
                        default:
                            break;
                    }
                }

                // 添加分支长度作为第4个特征
                for (int i = 0; i < branchLengthList.size(); i++) {
                    branchFeatureArray[i][3] = branchLengthList.get(i);
                }
                //获取回路信息
                List<Map<String, Object>> circuitList = (List<Map<String, Object>>) stringObjectMap.get("circuitInfo");
                //175*176特征
                float[][] x = new float[allNameList.size()][allNameList.size() + 1];
                //回路单价总和
                Map<String, Float> circuitPrice = new HashMap<>();
                //分支点为湿区的成本
                Map<String, Float> wetCost = new HashMap<>();
                for (Map<String, Object> objectMap : circuitList) {
                    String startName = objectMap.get("起点用电器名称").toString();
                    String endName = objectMap.get("终点用电器名称").toString();
                    String wireType = objectMap.get("导线选型").toString();
                    Map<String, String> materialsMsg = elecFixedLocationLibrary.get(wireType);
                    String price = materialsMsg.get("导线单位商务价（元/米）");
                    if((startName.startsWith("[") || endName.startsWith("[")) && objectMap.get("焊点位置名称") == null){
                        continue;
                    }
                    String startAppPosition = null;
                    String endAppPosition = null;
                    if (startName.startsWith("[")) {
                        startAppPosition = objectMap.get("焊点位置名称").toString();
                    } else {
                        startAppPosition = objectMap.get("起点位置名称").toString();
                    }
                    if (endName.startsWith("[")) {
                        endAppPosition = objectMap.get("焊点位置名称").toString();
                    } else {
                        endAppPosition = objectMap.get("终点位置名称").toString();
                    }
                    if (allNameList.indexOf(startAppPosition) == -1 || allNameList.indexOf(endAppPosition) == -1) {
                        continue;
                    }
                    if (circuitPrice.get(startAppPosition + ":" + endAppPosition) == null) {
                        circuitPrice.put(startAppPosition + ":" + endAppPosition, Float.parseFloat(price));
                    } else {
                        circuitPrice.put(startAppPosition + ":" + endAppPosition, circuitPrice.get(startAppPosition + ":" + endAppPosition) + Float.parseFloat(price));
                    }

                    //湿区成本，用回路单价替代
                    String startParam =SampleSave.getWaterParam(startAppPosition, pointList);
                    String endParam = SampleSave.getWaterParam(endAppPosition, pointList);
                    if ("w".toUpperCase().equals(startParam) || "w".toUpperCase().equals(endParam)) {
                        if (wetCost.get(startAppPosition) == null) {
                            wetCost.put(startAppPosition, Float.parseFloat(price));
                        } else {
                            wetCost.put(startAppPosition, wetCost.get(startAppPosition) + Float.parseFloat(price));
                        }
                    }
                }
                //x矩阵构建
                circuitPrice.forEach((k, v) -> {
                    x[allNameList.indexOf(k.split(":")[0])][allNameList.indexOf(k.split(":")[1])] = v;
                });
                wetCost.forEach((k, v) -> {
                    x[allNameList.indexOf(k)][allNameList.size()] = v;
                });

                result.put("x", x);
                result.put("edgeIndex", edgeIndex);
                result.put("edgeAttr", branchFeatureArray);
                result.put("totalPrice",baseCost);
                result.put("totalLength",baseLength);
                result.put("totalWeight",baseWeight);
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
                    TypeCheckUtils.countType("connect");
                    float[][] x = (float[][])result.get("x");
                    int[][] edgeIndex1 = (int[][])result.get("edgeIndex");
                    float[][] edgeAttr = (float[][])result.get("edgeAttr");
                    Float totalPrice = Float.parseFloat(result.get("totalPrice").toString());
                    Float totalLength = Float.parseFloat(result.get("totalLength").toString());
                    Float totalWeight = Float.parseFloat(result.get("totalWeight").toString());
                    //样本写入
                    SampleSave.saveSample(edgeIndex1,edgeAttr,x,filePath,totalPrice,totalLength,totalWeight);
                }
            }catch (Exception e){
//                e.printStackTrace();
            }
        }
    }
}
