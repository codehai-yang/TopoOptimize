package HarnessPackOpti.Optimize.elec;


import HarnessPackOpti.Algorithm.FindElecLocation;
import HarnessPackOpti.Algorithm.SplitCircuitByInterDirectConn;
import HarnessPackOpti.CircuitInfoCalculate.CalculateCircuitInfo;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.collections4.map.LinkedMap;

import java.text.DecimalFormat;
import java.util.*;

public class ElecProjectCircuitInfoOutput {

    public String projectCircuitInfoOutput(String fileStringFormat,
                                           Map<String, Object> filtration,
                                           Map<String, Map<String, String>> elecFixedLocationLibrary,
                                           Map<String, Double> elecBusinessPrice) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        ReadProjectInfo readProjectInfo = new ReadProjectInfo();
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
        List<Map<String, Object>> points = (List<Map<String, Object>>) projectInfo.get("所有端点信息");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) projectInfo.get("回路用电器信息");
        Map<String, Object> caseInfo = (Map<String, Object>) projectInfo.get("方案信息");
        Boolean whetherToChange = caseInfo.get("直连接口是否发生变化") == null || caseInfo.get("直连接口是否发生变化").toString().equals("false") ? false : true;

//       在points 找出所有可能发生变化点   并且将同一组的放在一起
        Map<String, List<String>> interfaceCodegroup = new HashMap<>();
        Set<String> pointNameSet = new HashSet<>();
        if (whetherToChange) {
            for (Map<String, Object> point : points) {
                if (point.get("端点接口直连编号") != null && !"".equals(point.get("端点接口直连编号").toString())) {
                    String interfaceCode = point.get("端点接口直连编号").toString();
                    String pointName = point.get("端点名称").toString();
                    interfaceCode = interfaceCode.substring(0, interfaceCode.length() - 1);
                    if (interfaceCodegroup.containsKey(interfaceCode)) {
                        interfaceCodegroup.get(interfaceCode).add(pointName);
                    } else {
                        List<String> pointNames = new ArrayList<>();
                        pointNames.add(pointName);
                        interfaceCodegroup.put(interfaceCode, pointNames);
                    }
                    pointNameSet.add(pointName);
                }
            }
        }

//        找出所有在这些点的用电器
        Set<String> functionPointSet = new HashSet<>();
        for (Map<String, String> loopInfo : loopInfos) {
            if (loopInfo.get("回路起点用电器").startsWith("[") || loopInfo.get("回路终点用电器").startsWith("[")) {
                if (loopInfo.get("回路起点用电器").startsWith("[")) {
                    functionPointSet.add(loopInfo.get("回路起点用电器"));
                }
                if (loopInfo.get("回路终点用电器").startsWith("[")) {
                    functionPointSet.add(loopInfo.get("回路终点用电器"));
                }
            }
        }
//        找出用电器存在可变点的集合
        List<String> electricalSet = new ArrayList<>();
        FindElecLocation findElecLocation = new FindElecLocation();
        List<Map<String, String>> mapList = findElecLocation.getEleclection(projectInfo);
//        用电器对应的可变位置点 {BCM:[位置1、位置2、位置3],CPM:[位置四、位置5]}
        Map<String, List<String>> eleclectionAddress = new HashMap<>();
        for (Map<String, String> map : mapList) {
            if (map.get("value") != null && pointNameSet.contains(map.get("value").toString())) {
                electricalSet.add(map.get("key"));
                String value = map.get("value").toString();
                for (String interfaceCode : interfaceCodegroup.keySet()) {
                    List<String> list = interfaceCodegroup.get(interfaceCode);
                    if (list.contains(value)) {
                        eleclectionAddress.put(map.get("key"), list);
                    }
                }
            }
        }

        Map<String, Object> group = group(projectInfo, electricalSet, elecFixedLocationLibrary, functionPointSet);

        List<Map<String, Object>> fixedLoops = (List<Map<String, Object>>) group.get("fixedLoops");
        List<List<Map<String, Object>>> grouplists = (List<List<Map<String, Object>>>) group.get("groupLoops");
        List<Map<String, Object>> nonfixedNotGroupLoops = (List<Map<String, Object>>) group.get("nonfixedNotGroupLoops");

//       接下来就是对所有的回路进行一个计算并且返回回路的最终计算结果
        Map<String, Object> loopdetails = new HashMap<>();
//        对已经固定的回路进行计算
        Map<String, Object> fixclassifyCircuit = new HashMap<>();
        fixclassifyCircuit.put("回路用电器信息", fixedLoops);
//        1、对这些回路进行一个分类 ：两点的    焊点的
        List<Map<String, String>> fixTwoPoints = loopInfos;

//        进行计算  并且将最终的结果添加到 loopdetails里面
//        对固定两点的进行计算
        for (Map<String, String> list : fixTwoPoints) {
            Map<String, Object> twoPointInfo = findTwoPointInfo(list, projectInfo, elecFixedLocationLibrary, true, null, electricalSet, elecBusinessPrice, filtration);
            loopdetails.put(twoPointInfo.get("回路id").toString(), twoPointInfo);
        }

        List<Map<String, Object>> finallyBestLoop = new ArrayList<>();
//        对可变的回路进行计算  并且将最优结果添加到loopdetails里面  对grouplist每一组进行计算
        for (List<Map<String, Object>> grouplist : grouplists) {
//            找出回路中的所有的可变用电器 将接口添加数组中去 并且找出所有的可变点
            List<String> nonFixElectrical = new ArrayList<>();
            Map<String, Set<String>> electricalInterFace = new HashMap<>();
            for (Map<String, Object> map : grouplist) {
                if (electricalSet.contains(map.get("回路起点用电器").toString()) || electricalSet.contains(map.get("回路终点用电器").toString())) {
                    if (electricalSet.contains(map.get("回路起点用电器").toString())) {
                        if (map.get("回路起点用电器接口编号") != null) {
                            if (electricalInterFace.containsKey(map.get("回路起点用电器").toString())) {
                                electricalInterFace.get(map.get("回路起点用电器").toString()).add(map.get("回路起点用电器接口编号").toString());
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add(map.get("回路起点用电器接口编号").toString());
                                electricalInterFace.put(map.get("回路起点用电器").toString(), list);
                            }
                        } else {
                            if (electricalInterFace.containsKey(map.get("回路起点用电器").toString())) {
                                electricalInterFace.get(map.get("回路起点用电器").toString()).add("null");
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add("null");
                                electricalInterFace.put(map.get("回路起点用电器").toString(), list);
                            }
                        }
                    }
                    if (electricalSet.contains(map.get("回路终点用电器").toString())) {
                        if (map.get("回路终点用电器接口编号") != null) {
                            if (electricalInterFace.containsKey(map.get("回路终点用电器").toString())) {
                                electricalInterFace.get(map.get("回路终点用电器").toString()).add(map.get("回路终点用电器接口编号").toString());
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add(map.get("回路终点用电器接口编号").toString());
                                electricalInterFace.put(map.get("回路终点用电器").toString(), list);
                            }
                        } else {
                            if (electricalInterFace.containsKey(map.get("回路终点用电器").toString())) {
                                electricalInterFace.get(map.get("回路终点用电器").toString()).add("null");
                            } else {
                                Set<String> list = new HashSet<>();
                                list.add("null");
                                electricalInterFace.put(map.get("回路终点用电器").toString(), list);
                            }
                        }
                    }
                }
            }
//            对可能存在的情况进行一个排列组合
            List<Map<String, Object>> allpossibility = new ArrayList<>();
            for (String elec : electricalInterFace.keySet()) {
                List<Map<String, Object>> currentpossibility = new ArrayList<>(allpossibility);
//            用电器对应的接口
                Set<String> list = electricalInterFace.get(elec);
//            用电器对应的位置
                List<String> addressList = eleclectionAddress.get(elec);

                for (String s : list) {
                    if (allpossibility.size() == 0) {
                        for (String address : addressList) {
                            Map<String, Object> map = new HashMap<>();
                            Map<String, String> interfaceMap = new HashMap<>();
                            interfaceMap.put(s, address);
                            map.put(elec, interfaceMap);
                            currentpossibility.add(map);
                        }
                    } else {
                        List<Map<String, Object>> tempPossibility = new ArrayList<>();
                        for (String address : addressList) {
                            for (Map<String, Object> objectMap : currentpossibility) {
                                Map<String, Object> copy = new HashMap<>(objectMap);
                                if (copy.containsKey(elec)) {
                                    Map<String, Object> map1 = (Map<String, Object>) copy.get(elec);
                                    map1.put(s, address);
                                } else {
                                    Map<String, String> interfaceMap = new HashMap<>();
                                    interfaceMap.put(s, address);
                                    copy.put(elec, interfaceMap);
                                }
                                tempPossibility.add(copy);
                            }
                        }
                        currentpossibility = tempPossibility;
                    }
                    allpossibility = currentpossibility;
                }
            }
//            将当前的grouplist进行一个分组   两点直连的   存在焊点的
            Map<String, Object> nonfixclassifyCircuit = new HashMap<>();
            nonfixclassifyCircuit.put("回路用电器信息", grouplist);
            List<Map<String, String>> nonfixTwoPoints = loopInfos;
//            存储当组里面所有的每种可能性对应的回路信息
            List<Map<String, Object>> allpossibilityLoopInfoList = new ArrayList<>();
//            对所有的情况都进行一个计算
            for (Map<String, Object> objectMap : allpossibility) {
                List<Map<String, Object>> currentloopdetails = new ArrayList<>();
//            对两点直连的 进行计算 并将最优的结果添加到loopdetails里面
                Boolean flag = true;//该方案是否可行
                for (Map<String, String> list : nonfixTwoPoints) {
                    Map<String, Object> twoPointInfo = findTwoPointInfo(list, projectInfo, elecFixedLocationLibrary, false, objectMap, electricalSet, elecBusinessPrice, filtration);
                    if (twoPointInfo == null) {
                        flag = false;
                        break;
                    }
                    currentloopdetails.add(twoPointInfo);
                }
                if (!flag) {
                    continue;
                }

                Map<String, Double> stringDoubleMap = calculateLoppListCost(currentloopdetails);
                Map<String, Object> currentSchemeMap = new HashMap<>();
                currentSchemeMap.put("回路信息", currentloopdetails);
                currentSchemeMap.put("成本", stringDoubleMap);
                currentSchemeMap.put("可变方案", objectMap);
                allpossibilityLoopInfoList.add(currentSchemeMap);
            }
//            在allpossibilityLoopInfoList 找出最优的一个方案
            if (allpossibilityLoopInfoList.size() > 1) {
                Map<String, Object> bestPossibility = findBestPossibility(allpossibilityLoopInfoList);
                List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) bestPossibility.get("回路信息");
                finallyBestLoop.add(bestPossibility);
            } else if (allpossibilityLoopInfoList.size() == 1) {
                Map<String, Object> bestPossibility = allpossibilityLoopInfoList.get(0);
                List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) bestPossibility.get("回路信息");
                finallyBestLoop.addAll(mapList1);
            }
        }

//        将finallyBestLoop 进行一个提取 1、回路添加到loopdetails里面   接口位置点进行一个整合
        Map<String, Object> bestInterFaceInfo = new HashMap<>();
        for (Map<String, Object> map : finallyBestLoop) {
            List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) map.get("回路信息");
            for (Map<String, Object> objectMap : mapList1) {
                loopdetails.put(objectMap.get("回路id").toString(), objectMap);
            }
            Map<String, Object> elecinterface = (Map<String, Object>) map.get("可变方案");
            for (String s : elecinterface.keySet()) {
                Map<String, String> elecinterfacedetail = (Map<String, String>) elecinterface.get(s);
                if (bestInterFaceInfo.containsKey(s)) {
                    Map<String, String> map1 = (Map<String, String>) bestInterFaceInfo.get(s);
                    map1.putAll(elecinterfacedetail);
                } else {
                    bestInterFaceInfo.put(s, elecinterfacedetail);
                }
            }
        }
//        接下来就是对 剔除部分的回路进行计算
        Map<String, Object> nonfixedNotGroupLoopsMap = new HashMap<>();
        nonfixedNotGroupLoopsMap.put("回路用电器信息", nonfixedNotGroupLoops);
        List<Map<String, String>> nonfixedNotGroupLoopsTwo = loopInfos;
//            对两点直连的 进行计算 并将最优的结果添加到loopdetails里面
        for (Map<String, String> list : nonfixedNotGroupLoopsTwo) {
            Map<String, Object> twoPointInfo = findTwoPointInfo(list, projectInfo, elecFixedLocationLibrary, false, bestInterFaceInfo, electricalSet, elecBusinessPrice, filtration);
            loopdetails.put(twoPointInfo.get("回路id").toString(), twoPointInfo);
        }
        Map<String, Object> projectCircuitInfo = circuitProjectInfo(loopdetails);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("projectCircuitInfo", projectCircuitInfo);
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(resultMap);// 将Map转换为JSON字符串
        return json;
    }

    public Map<String, Object> group(Map<String, Object> projectInfo, List<String> electricalSet,
                                     Map<String, Map<String, String>> elecFixedLocationLibrary,
                                     Set<String> functionPointSet) throws JsonProcessingException {
        List<Map<String, Object>> loopInfos = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
        Map<String, Object> resultMap = new HashMap<>();
        //        对集合里面的回路进行一个分类 一类是可变的  一类是不可变的
//        不固定回路参与分组
        List<Map<String, Object>> nonfixedLoops = new ArrayList<>();
//        不固定回路不参与分组
        List<Map<String, Object>> nonfixedNotGroupLoops = new ArrayList<>();
//        固定回路
        List<Map<String, Object>> fixedLoops = new ArrayList<>();
        List<Map<String, Object>> twoPointMaps = loopInfos;
//        所有不固定回路
        List<Map<String, Object>> allNonfixedLoops = new ArrayList<>();
//
        Map<String, Object> circuitProjectInfo = new HashMap<>();
//        筛选出当中需要进行分组分的回路 1、起点用电器和终点电器 以及接口编号都一样的 的所有回路  单位价格总和大于三块   2、焊点的单条回路单位价格大于三块  也进行保留
        for (Map<String, Object> objectMap : twoPointMaps) {
            if (electricalSet.contains(objectMap.get("回路起点用电器").toString()) || electricalSet.contains(objectMap.get("回路终点用电器").toString())) {
                allNonfixedLoops.add(objectMap);
                if (electricalSet.contains(objectMap.get("回路起点用电器").toString()) && electricalSet.contains(objectMap.get("回路终点用电器").toString())) {
                    String startApp = objectMap.get("回路起点用电器").toString();
                    String endApp = objectMap.get("回路终点用电器").toString();
                    String startAppPort = objectMap.get("回路起点用电器接口编号") == null ? "" : objectMap.get("回路起点用电器接口编号").toString();
                    String endAppPort = objectMap.get("回路终点用电器接口编号") == null ? "" : objectMap.get("回路终点用电器接口编号").toString();
                    if (circuitProjectInfo.containsKey(startApp + startAppPort + "-" + endApp + endAppPort)) {
                        List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) circuitProjectInfo.get(startApp + startAppPort + "-" + endApp + endAppPort);
                        mapList1.add(objectMap);
                    } else if (circuitProjectInfo.containsKey(endApp + endAppPort + "-" + startApp + startAppPort)) {
                        List<Map<String, Object>> mapList1 = (List<Map<String, Object>>) circuitProjectInfo.get(endApp + endAppPort + "-" + startApp + startAppPort);
                        mapList1.add(objectMap);
                    } else {
                        String key = startApp + startAppPort + "-" + endApp + endAppPort;
                        List<Map<String, Object>> mapList1 = new ArrayList<>();
                        mapList1.add(objectMap);
                        circuitProjectInfo.put(key, mapList1);
                    }
                }
            } else {
                fixedLoops.add(objectMap);
            }
        }
//            对回路两端都是可变用电器的回路单位成本进行检查
        List<String> withoutRegardIDList = new ArrayList<>();
        for (String s : circuitProjectInfo.keySet()) {
            List<Map<String, Object>> circuitProjectInfoList = (List<Map<String, Object>>) circuitProjectInfo.get(s);
            Double degression = 0.0;
            List<String> idList = new ArrayList<>();
            for (Map<String, Object> map : circuitProjectInfoList) {
                idList.add(map.get("回路id").toString());
                String loopWireway = map.get("回路导线选型").toString();
                Map<String, String> map1 = elecFixedLocationLibrary.get(loopWireway);
                degression = degression + Double.parseDouble(map1.get("导线单位商务价（元/米）"));
            }
            if (degression < 5) {
                withoutRegardIDList.addAll(idList);
            }
        }
//     将符合要求的分支添加里面进行分组
        for (Map<String, Object> allNonfixedLoop : allNonfixedLoops) {
            if (withoutRegardIDList.contains(allNonfixedLoop.get("回路id").toString())) {
                nonfixedNotGroupLoops.add(allNonfixedLoop);
            } else {
                nonfixedLoops.add(allNonfixedLoop);
            }
        }
        SplitCircuitByInterDirectConn splitCircuitByInterDirectConn = new SplitCircuitByInterDirectConn();
        Set<String> allElecSet = new HashSet<>();
        allElecSet.addAll(functionPointSet);
        allElecSet.addAll(electricalSet);
        List<List<Map<String, Object>>> grouplists = splitCircuitByInterDirectConn.groupLoops(nonfixedLoops, new ArrayList<>(allElecSet));

        resultMap.put("groupLoops", grouplists);
        resultMap.put("fixedLoops", fixedLoops);
        resultMap.put("nonfixedNotGroupLoops", nonfixedNotGroupLoops);
        return resultMap;
    }


    /**
     * @Description 根据分支id获取回路信息
     * @input pointList 需要计算的处理后分支信息
     * @Return 当前分支下面的回路信息 包括：总成本、回路湿区成本总加成、回路打断总成本、回路两端端子总成本、回路导线总成本、回路总重量、总理论直径、回路总长度
     */
    public Map<String, Object> circuitProjectInfo(Map<String, Object> pointList) {
//        总成本
        Map<String, Object> totalCost = new HashMap<>();
        totalCost.put("总成本", 0.0);
        totalCost.put("回路总重量", 0.0);
        totalCost.put("回路总长度", 0.0);
        double lenght = 0.0;
        DecimalFormat df = new DecimalFormat("0.00");
        Set multiLoopInfosSet = pointList.keySet();
        for (Object o : multiLoopInfosSet) {
            Map<String, Object> objectMap = (Map<String, Object>) pointList.get(o);
            totalCost.put("总成本", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("总成本").toString()) + Double.parseDouble(objectMap.get("回路总成本").toString()))));
            totalCost.put("回路总重量", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) + Double.parseDouble(objectMap.get("回路重量").toString()))));
            totalCost.put("回路总长度", Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) + Double.parseDouble(objectMap.get("回路长度").toString()))));
        }
        return totalCost;
    }


    //    在所有的方案中找出最优的一个方案
    public Map<String, Object> findBestPossibility(List<Map<String, Object>> allpossibilityLoopInfoList) {
        Map<String, Object> bestPossibility = new HashMap<>();
        Map<String, Object> minCostMap = null;
        double minTotalCost = Double.MAX_VALUE;

        for (Map<String, Object> map : allpossibilityLoopInfoList) {
            Map<String, Double> costMap = (Map<String, Double>) map.get("成本");
            double totalCost = costMap.get("总成本");

            if (totalCost < minTotalCost) {
                minTotalCost = totalCost;
                minCostMap = map;
            }
        }
        Map<String, Double> standardMap = (Map<String, Double>) minCostMap.get("成本");
        Double standardCost = standardMap.get("总成本");
        Double standardWeight = standardMap.get("总重量");

        for (Map<String, Object> map : allpossibilityLoopInfoList) {

            Map<String, Double> costMap = (Map<String, Double>) map.get("成本");
            if (costMap.get("总成本") != minTotalCost) {
                Double cost = costMap.get("总成本");
                Double weight = costMap.get("总重量");
                Double score = (weight - standardWeight) / (cost - standardCost + 0.001);
                map.put("得分", score);
            } else {
                map.put("得分", -4.0);
            }

        }


        Map<String, Object> finallyCostMap = null;
        double finallyScore = Double.MAX_VALUE;

        for (Map<String, Object> map : allpossibilityLoopInfoList) {
            double score = (Double) map.get("得分");
            if (score < finallyScore) {
                finallyScore = score;
                finallyCostMap = map;
            }
        }

        return finallyCostMap;
    }


    //    根据给定的回路信息  计算当前list的总成本、总长度、总重量
    public Map<String, Double> calculateLoppListCost(List<Map<String, Object>> loopList) {
        Map<String, Double> resultCost = new HashMap<>();
        resultCost.put("总成本", 0.0);
        resultCost.put("总长度", 0.0);
        resultCost.put("总重量", 0.0);
        for (Map<String, Object> map : loopList) {
            Double cost = (Double) map.get("回路总成本");
            Double length = (Double) map.get("回路长度");
            Double weight = (Double) map.get("回路重量");
            resultCost.put("总成本", resultCost.get("总成本") + cost);
            resultCost.put("总长度", resultCost.get("总长度") + length);
            resultCost.put("总重量", resultCost.get("总重量") + weight);
        }
        return resultCost;

    }


    /**
     * @Description: 单条路径的方法
     * @input: twoMap 分类完成的单个回路信息
     * @input: projectInfo 解析完成的txt的文件信息
     * @input: adjacencyMatrixGraph 构建的邻接矩阵
     * @input: elecFixedLocationLibrary excel表中的线径信息
     * @Return: 回路信息 当前回路中最优的一条信息
     * {起点用电器名称=BMS, 起点用电id=ee8600dc-1ab6-4fa2-a043-1e0a0cb1c6d7, 起点位置名称=前围板外中点, 起点位置id=f753b6fe-39bd-4602-8cb3-b663c79af003, 终点用电器名称=LVManuSrvcDscnctr, 终点用电id=2955c804-a8bd-463f-baff-ce063f0f5695, 终点位置名称=车身线左后inline点, 终点位置id=b9e1aee2-7e19-4bf0-9618-5b16de00aa84, 回路属性=null, 导线选型=FLRY-B 0.5, 回路编号=5349, 方案号=85342e86-dd12-498d-b6a4-a45a3bd96fce, 所属系统=系统Others, 回路起点用电器接口编号=, 回路终点用电器接口编号=, 回路信号名=BMS-KL30.2, 湿区两端连接器成本补偿=0.0, 湿区两端防水塞成本补偿=0.0, 焊点名称=null, 回路id=017874d0-de96-4a88-b253-0d4f42b2849f, 焊点位置名称=null, 焊点位置id=null, 回路途径分支点=[前围板外中点, 前围板外左点, 前舱左纵梁后点, 前舱线左后inline点, 车身线左前inline点, 左门槛中点, 左门槛后点, 左后轮包顶点, 后围板内左点, 车身线左后inline点], 回路总成本=43.365026, 回路湿区成本加成=0.0, inline湿区连接器成本补偿=0.0, inline湿区防水塞成本补偿=0.0, 回路打断成本=0.2, 回路导线成本=42.765026, 回路总重量=3.7390186666666665, 回路总长度=7.01066, 回路打断分支=[d89d0217-8ef0-41f2-835c-49ca8f7e069c], 回路打断次数=1, 回路所有分支=[199eecf0-3320-4b2a-86e6-036442fdc317, a6300929-2419-4a1f-b257-7c68d9a3e3c1, 8a464b8f-d481-465a-a7ce-f320d548d83e, d89d0217-8ef0-41f2-835c-49ca8f7e069c, 1710d6e7-d788-4bdb-adb8-10cfc0160f26, 263afced-fd0d-4ddc-9bfe-355643f80812, 674c6947-31a3-4879-9f73-5db9f7b7b43e, 027dc9a1-bb19-425f-aa16-55a6c26bdb62, 0577f318-7a61-4e13-82fb-4ddd7bbf8167], 回路所有分支数量=9, 回路直径=2.1}
     */
    public Map<String, Object> findTwoPointInfo(Map<String, String> twoMap, Map<String, Object> projectInfo,
                                                Map<String, Map<String, String>> elecFixedLocationLibrary,
                                                Boolean whetherFix,
                                                Map<String, Object> objectMap,
                                                List<String> electricalSet,
                                                Map<String, Double> elecBusinessPrice,
                                                Map<String, Object> filtration) {
        List<Map<String, String>> appPositions = (List<Map<String, String>>) projectInfo.get("用电器信息");
        List<Map<String, String>> pointList = (List<Map<String, String>>) projectInfo.get("所有端点信息");


        String start = twoMap.get("回路起点用电器");
        String end = twoMap.get("回路终点用电器");
        String materials = twoMap.get("回路导线选型");
        String circuitId = twoMap.get("回路id");

//        根据导线选型选择对应的信息
        Map<String, String> materialsMsg = elecFixedLocationLibrary.get(materials);
        String startName = "";
        String endName = "";
        if (whetherFix) {
            startName = findNode(start, appPositions);
            endName = findNode(end, appPositions);
        } else {
            if (electricalSet.contains(start)) {
                if (twoMap.get("回路起点用电器接口编号") != null) {
                    String startAppPort = twoMap.get("回路起点用电器接口编号").toString();
                    if (objectMap.containsKey(start)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(start);
                        startName = map.get(startAppPort);
                    } else {
                        startName = findNode(start, appPositions);
                    }
                    if (startName == null) {
                        startName = findNode(start, appPositions);
                    }

                } else {
                    if (objectMap.containsKey(start)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(start);
                        startName = map.get("null");
                    } else {
                        startName = findNode(start, appPositions);
                    }
                }
            } else {
                startName = findNode(start, appPositions);
            }
            if (electricalSet.contains(end)) {
                if (twoMap.get("回路终点用电器接口编号") != null) {
                    String endAppPort = twoMap.get("回路终点用电器接口编号").toString();
                    if (objectMap.containsKey(end)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(end);
                        endName = map.get(endAppPort);
                    } else {
                        endName = findNode(end, appPositions);
                    }
                    if (endName == null) {
                        endName = findNode(end, appPositions);
                    }
                } else {
                    if (objectMap.containsKey(end)) {
                        Map<String, String> map = (Map<String, String>) objectMap.get(end);
                        endName = map.get("null");
                    } else {
                        endName = findNode(end, appPositions);
                    }
                }
            } else {
                endName = findNode(end, appPositions);
            }
        }

        List<Map<String, Object>> pathList = new ArrayList<>();
        String name = startName + "-" + endName;
        if (filtration.containsKey(name)) {
            List<Map<String, Object>> objects = (List<Map<String, Object>>) filtration.get(name);
            DecimalFormat df = new DecimalFormat("0.00");
            for (Map<String, Object> object : objects) {
                Double length = (Double) object.get("回路长度");
                Integer breakNumber = (Integer) object.get("打断次数");
                Integer count = (Integer) object.get("湿区个数");

                Map<String, Object> sinaglePath = new LinkedMap<>();
                CalculateCircuitInfo acceptLoopInfo = new CalculateCircuitInfo();


                Double lengthCocst=length * Double.parseDouble(materialsMsg.get("导线单位商务价（元/米）"));
                if (elecBusinessPrice.containsKey(start) || elecBusinessPrice.containsKey(end)) {
                    if (elecBusinessPrice.containsKey(start)) {
                        lengthCocst=lengthCocst + elecBusinessPrice.get(start);
                    } else {
                        lengthCocst=lengthCocst + elecBusinessPrice.get(end);
                    }
                }
                sinaglePath.put("回路总成本", Double.parseDouble(df.format(Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）")) * count +
                        Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）")) * count +
                        Double.parseDouble(materialsMsg.get("导线打断成本（元/次）")) * breakNumber
                        + Double.parseDouble(materialsMsg.get("端子成本（元/端）")) * 2 +
                        +lengthCocst)));
                sinaglePath.put("回路id", circuitId);
                sinaglePath.put("回路长度", Double.parseDouble(df.format(length)));
                sinaglePath.put("回路长度", Double.parseDouble(df.format(length)));
                sinaglePath.put("回路重量", Double.parseDouble(df.format(length* Double.parseDouble(materialsMsg.get("导线单位重量（单位g/m）").toString()))));
                pathList.add(sinaglePath);
            }
        } else {
            return null;
        }
        //        对当前的路径取最优的一种情况
        Map<String, Object> bestMap = pathSelectBetweenPoint(pathList);
        return bestMap;
    }
    /**
     * @Description 获取最优的一条路径（首先找出这些回路中的 回路总成本、回路总量、回路长度的最大值和最小值 然后按照 （总成本-总成本最小值）/（总成本最大值-总成本最大值）+（回路长度-回路长度最小值）/（回路长度最大值-回路长度最大值）+（回路重量-回路重量最小值）/（回路重量最大值-回路重量最大值） 其值最小的一条回路 ）
     * @input maps:所有路径信息
     * @Return 最优的一条路径
     */
    public Map<String, Object> pathSelectBetweenPoint(List<Map<String, Object>> maps) {
        if (maps.size() > 1) {
            Map<String, Object> minMap = new HashMap<>();
            Double minCost = null;
            for (Map<String, Object> map : maps) {
                Double cost = (Double) map.get("回路总成本");
                if (minCost == null) {
                    minCost = cost;
                    minMap = map;
                } else if (minCost > cost) {
                    minCost = cost;
                    minMap = map;
                }
            }

            Map<String, Object> compareMap = new HashMap<>();
            Double compareScore = null;
//        接下来就是对这个这些回路进行一个打分
            for (Map<String, Object> map : maps) {
                if ((Double) map.get("回路总成本") != minCost) {
                    Double score = ((Double) map.get("回路重量") - (Double) minMap.get("回路重量")) / ((Double) map.get("回路总成本") - minCost + 0.001);
                    if (compareScore == null) {
                        compareScore = score;
                        compareMap = map;
                    } else if (compareScore > score) {
                        compareScore = score;
                        compareMap = map;
                    }
                }
            }
            if (compareScore > -4) {
                return minMap;
            } else {
                return compareMap;
            }
        } else {
            return maps.get(0);
        }

    }

    public String findNode(String appName, List<Map<String, String>> appPositions) {
        for (Map<String, String> appPosition : appPositions) {
            if (appPosition.get("用电器名称").equals(appName)) {
                if (appPosition.get("用户更改后用电器位置名称") != null) {
                    return appPosition.get("用户更改后用电器位置名称");
                } else if (appPosition.get("用户更改后用电器位置名称") == null && appPosition.get("用电器固化位置点名称") != null) {
                    return appPosition.get("用电器固化位置点名称");
                } else if (appPosition.get("用户更改后用电器位置名称") == null && appPosition.get("用电器固化位置点名称") == null) {
                    return null;
                }
            }
        }
        return null;
    }

}
