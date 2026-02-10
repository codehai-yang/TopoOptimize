package HarnessPackOpti.Optimize.topo;

import HarnessPackOpti.Algorithm.FindBest;
import HarnessPackOpti.Algorithm.FindShortestPath;
import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import HarnessPackOpti.utils.GenerateAiCaseUtils;
import HarnessPackOpti.utils.ThreadPool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.beans.PropertyEditorSupport;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class HarnessBranchTopoOptimize {
    //    随机变换样本数量
    public static Integer LessRandomSamleNumber = 15;
    //   迭代最少样本数量
    public static Integer HybridizationLessRandomSamleNumber = 100;
    //    top几的数量规定
    public static final Integer TopNumber = 20;
    //    每次迭代最优的成本
    public static Map<String, Double> BestCost = new HashMap<>();
    //    最优样本重复次数
    public static Integer BestRepetitionNumber = 0;
    //    迭代重复的次数限值
    public static Integer IterationRestrictNumber = 30;
    //    定义一个仓库
    public static List<List<String>> WareHouse = new CopyOnWriteArrayList<>();
    //定义仓库(所有裂变生成的方案，用于AI)
    public static List<List<String>> WareHouseAI = new CopyOnWriteArrayList<>();
    //AI仓库存放的样本数量限制
    public static Integer AutoCompleteNumberLimit = 1000;
    //    变异的次数
    public static Integer VariationNumber = 1;
    //每次迭代得到的top20
    public static List<Map<String, Object>> TopDetail = new ArrayList<>();
    //    初始化自动补全得次数
    public static Integer InitializeAutoCompleteNumber = 1000;
    //    自动补全得次数
    public static Integer AutoCompleteNumber = 30;
    //遗传算法迭代次数
    public static Integer IterationNumber = 1;


    //    定义一个仓库
    public static List<List<String>> WareHouseTop = new ArrayList<>();
    //每次迭代得到的top10
    public static List<Map<String, Object>> TopCostDetail = new ArrayList<>();
    public static ThreadPool threadPool = new ThreadPool(11, 11);


    //    当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    public HarnessBranchTopoOptimize() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance(); // 使用Store的单例实例
    }

    public String topoOptimize(String jsonContent) throws Exception {
        long start = System.currentTimeMillis();
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        CaseId = caseInfo.get("id").toString();
        optimizeRecordId = optimizeRecord.get("id").toString();
        optimizeStopStatusStore.setKey(optimizeRecordId);

        //整车信息计算
        String initializeCaseResult = projectCircuitInfoOutput.projectCircuitInfoOutput(jsonContent);
        Map<String, Object> initializeCaseResultMap = jsonToMap.TransJsonToMap(initializeCaseResult);
        initializeCaseResultMap.put("topoId", topoInfoMap.get("id").toString());
        initializeCaseResultMap.put("caseId", caseInfo.get("id").toString());
        List<Map<String, String>> topoOptimizeResult = new ArrayList<>();
        for (Map<String, Object> map : edges) {
            Map<String, String> result = new HashMap<>();
            result.put("edgeId", map.get("id").toString());
            result.put("statue", map.get("topologyStatusCode").toString());
            topoOptimizeResult.add(result);
        }
        initializeCaseResultMap.put("topoOptimizeResult", topoOptimizeResult);
        initializeCaseResultMap.put("initializationScheme", true);

        Map<String, String> eleclection = getEleclection(appPositions);
//        首先对所有的分支进行一个分类    固定的，非固定的
        //固定状态分支(仅B/C/S之一)
        Map<String, List<String>> completefixedMap = new HashMap<>();
        //组团一起变化的分支
        Map<String, List<String>> togetherBCMap = new HashMap<>();
        //可选BC的单独分支
        List<String> singleBCList = new ArrayList<>();
        //可选SC的单独分支
        List<String> singleSCList = new ArrayList<>();
        //可选BS的单独分支
        List<String> singleBSList = new ArrayList<>();
        //可选BSC的单独分支
        List<String> singleBSCList = new ArrayList<>();
        List<String> normList = new ArrayList<>();
//        初始方案得分支打断状况
        List<String> primeList = new ArrayList<>();
//       穿腔的id(涉及闭环的关键分支)
        List<String> wearId = new ArrayList<>();
//        互斥的情况  互斥分支(一组为B则另一组必须为C)
        Map<String, Map<String, List<String>>> mutexMap = new HashMap<>();
//        互斥团的情况
        Map<String, List<String>> mutexGroupMap = new HashMap<>();
//        多选一的情况(N个分支中至多一个为C)
        Map<String, Map<String, List<String>>> chooseOneMap = new HashMap<>();
//        可以变为S的id集合
        List<String> canChangeS = new ArrayList<>();
//        分支可供选择的是BS的这种集合
        List<String> edgeChooseBS = new ArrayList<>();
        //方案分类与检查初始状态设置
        long l = System.currentTimeMillis();
        System.out.println("开始对txt进行一个分类");
        //       找出那些符合变B的情况：用在随机取B  算闭环平均数
        List<String> conformList = new ArrayList<>();

        for (Map<String, Object> edge : edges) {
            normList.add(edge.get("id").toString());
            primeList.add(edge.get("topologyStatusCode").toString());
            if (edge.get("statusB") == null) {
                edge.put("statusB", "");
            }
            if (edge.get("statusC") == null) {
                edge.put("statusC", "");
            }
            if (edge.get("statusS") == null) {
                edge.put("statusS", "");
            }

            //只要分支可以为b，则添加到符合变b条件的分支
            if ((edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")) ||
                    (edge.get("statusC").toString().equals("C") && edge.get("statusB").toString().equals("B")) ||
                    (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().equals("C"))) {
                conformList.add(edge.get("id").toString());
            }

            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().isEmpty()) {
                edgeChooseBS.add(edge.get("id").toString());
            }
//            找出那些可变S的情况，可以变为s状态的分支
            if (edge.get("oneC") == null || "".equals(edge.get("oneC"))) {
                if ((edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S")) || (edge.get("statusC").toString().equals("C") && edge.get("statusS").toString().equals("S"))
                        || (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().equals("C"))) {
                    canChangeS.add(edge.get("id").toString());
                }
            }


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
                    //互斥状态-分支id
                    sonMap.put(mutexName, idList);
                    mutexMap.put(split[0], sonMap);
                }
//                考虑互斥是否存在组团的情况如果存在   记录下来
                if (edge.get("changeTogether") != null && !edge.get("changeTogether").toString().isEmpty()) {
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


//            对多选的一个情况进行一个记录，具有相同onec值的分支属于同一组
            if (edge.get("oneC") != null && !"".equals(edge.get("oneC"))) {
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
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleSCList.add(edge.get("id").toString());
                    continue;
                }
            }

//            当前为bs的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().isEmpty()) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBSList.add(edge.get("id").toString());
                    continue;
                }
            }
//            当前为bsc的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().equals("S") && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBSCList.add(edge.get("id").toString());
                    continue;
                }
            }
//            当前为bc的的
            if (edge.get("statusB").toString().equals("B") && edge.get("statusS").toString().isEmpty() && edge.get("statusC").toString().equals("C")) {
                if (edge.get("changeTogether") == null || edge.get("changeTogether").toString().isEmpty()) {
                    singleBCList.add(edge.get("id").toString());
                    continue;
                }

            }
//            剩下的都是都是在BC中进行一个挑选
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
            //如果组团一起变的中包含互斥组团，那么将该组团加入到互斥组团里，并且从togetherBCMap中删除该组
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
//       分支可以进行打B的集合
//        List<String> initialCanchangeBList = new ArrayList<>();
//        initialCanchangeBList.addAll(singleBCList);
//        initialCanchangeBList.addAll(singleBSList);
//        initialCanchangeBList.addAll(singleBSCList);
//        分支可以由S转为B的集合
        List<String> initialCanchangeSToBList = new ArrayList<>();
        initialCanchangeSToBList.addAll(singleBSList);
        initialCanchangeSToBList.addAll(singleBSCList);
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
//   initialScheme  当前方案下的分支打断情况，把不是为b的分支都设置为C，包括S
        List<String> initialScheme = new ArrayList<>();
        List<Map<String, Object>> coppyedges = edges.stream().collect(Collectors.toList());
        for (Map<String, Object> coppyedge : coppyedges) {
            String id = (String) coppyedge.get("id");
            if (!onlyNameB.contains(id)) {
                coppyedge.put("topologyStatusCode", "C");
                initialScheme.add("C");
            } else {
                initialScheme.add("B");
            }
        }
//        获取一定范围的分支
        jsonMap.put("edges", coppyedges);
        //分支id-分支打断代价 获取每条分支的打断代价
        Map<String, Double> breakCostMap = new HashMap<>();
        String detail = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
        Map<String, Object> objectMap = jsonToMap.TransJsonToMap(detail);
        //提取经过各个分支的所有回路信息
        //TODO 待优化项，剔除不必要的字段以节省时间
        Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) objectMap.get("bundeleRelatedCircuitInfo");
        //所有回路详细信息（最优方案）
        List<Map<String, Object>> circuitInfoList = (List<Map<String, Object>>) objectMap.get("circuitInfo");
        //统计所有分支的打断代价
        for (String s : bundeleRelatedCircuitInfo.keySet()) {
            Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
            //分支详细信息
            Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
            breakCostMap.put(s, Double.parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
        }
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        //按照导线单位商务价降序排序
        Map<String, Map<String, String>> sortedMapExcel = sortMapByInnerCostValue(elecFixedLocationLibrary);
        //打断代价从高到低排序
        Map<String, Double> sortedMap = sortMapByDoubleValue(breakCostMap);


//        从第一个B开始循环    selectNumberB 选取的B的数量
        int selectNumberB = 1;
//        每个B对应的的平均闭环数量
        System.out.println("结束分类以及初始方案检查，所用时间：" + (System.currentTimeMillis() - l));
        //TODO 当用户输入的方案数量<1000时，使用枚举法直接计算最有方案
        long totalCombinations = 1L;
        //TODO 这里限制BSC的数量，枚举方法完成后直接废除
//        if (singleBSCList.size() > 1) {
//            singleBSCList = new ArrayList<>(singleBSCList.subList(5, 6));
//        }
//        if (singleSCList.size() > 1) {
//            singleSCList = new ArrayList<>(singleSCList.subList(0, 2));
//        }
        if (!singleBCList.isEmpty()) {
            totalCombinations *= Math.pow(2, singleBCList.size());
        }

        // SC分支：每条有2种选择
        if (!singleSCList.isEmpty()) {
            totalCombinations *= Math.pow(2, singleSCList.size());
        }

        // BS分支：每条有2种选择
        singleBSList.clear();
        if (!singleBSList.isEmpty()) {
            totalCombinations *= Math.pow(2, singleBSList.size());
        }

        // BSC分支：每条有3种选择
        if (!singleBSCList.isEmpty()) {
            totalCombinations *= Math.pow(3, singleBSCList.size());
        }
        if (totalCombinations < 1000) {
            //递归枚举优化
            System.out.println("递归开始");
            long algorithm = System.currentTimeMillis();
            String s = "";
            try {
                s = branchEnum(singleBCList, singleSCList, singleBSList, singleBSCList, edges, normList, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList, jsonMap, initializeCaseResultMap, wearId, topoOptimizeResult);
            } finally {
                threadPool.terminateNow();
            }
            System.out.println("递归结束，所用时间：" + (System.currentTimeMillis() - algorithm));
            return s;
        }

        l = System.currentTimeMillis();
        System.out.println("开始计算不同B闭环的平均值");
        //打断B的数量-平均闭环数
        Map<Integer, Double> averageNumberB = new HashMap<>();

        while (true) {
            //store存储的默认值为true，一个状态存储对象，用于跟踪优化任务的运行状态
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                initializeCaseResultMap.put("finishStatue", "abnormal");
                List<Map<String, Object>> mapList = handleAndShowTop(jsonMap, "abnormal", singleBCList, singleSCList, singleBSList, singleBSCList, normList, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
                mapList.add(initializeCaseResultMap);
                String s = objectMapper.writeValueAsString(mapList);
                return objectMapper.writeValueAsString(mapList);
            }
//            是否结束当前循环
            Boolean breakLoop = false;
//            闭环总数
            Double loopTotal = 0.0;
//            累计样本成功数量
            Double simpleSuccess = 0.0;
//            累计样本失败数量
            int simpleFail = 0;
            while (true) {
//                      分支状态可为b的集合  在符合要求的范围内选定量的数量
                List<String> list = selectId(conformList, selectNumberB);
//                needCahngeId  选取的点当中可能存在组团的情况，
                List<String> needCahngeId = new ArrayList<>();
                for (String s : list) {
                    List<String> changeId = new ArrayList<>();
                    changeId.add(s);
                    for (List<String> strings : togetherBCList) {
                        if (strings.contains(s)) {
                            changeId = strings;
                        }
                    }
                    needCahngeId.addAll(changeId);
                }
//                检查是否符合用电器周围是否存在分支  以及所有回路是否导通的要求
                //除了b其他都为c
                //copy一份初始方案的分支打断情况(B,C)
                List<String> changeList = initialScheme.stream().collect(Collectors.toList());
                //copy原始方案分支
                //原始方案分支
                List<Map<String, Object>> coppysonedges = edges.stream().collect(Collectors.toList());
                for (String s : needCahngeId) {
                    int i = normList.indexOf(s);
                    changeList.set(i, "B");
                }
                for (String s : onlyNameS) {
                    int i = normList.indexOf(s);
                    changeList.set(i, "S");
                }
                //给复制的edge添加打断状态(覆盖之前的)
                for (Map<String, Object> coppyedge : coppysonedges) {
                    String id = (String) coppyedge.get("id");
                    int number = normList.indexOf(id);
                    String s = changeList.get(number);
                    coppyedge.put("topologyStatusCode", s);
                }
                //判断生成的方案是否存在断点
                Boolean sonSate = checkFirstOption(coppysonedges, appPositions, eleclection);
//                计算当前方案的分支闭合数量  对应的数量进行一个添加
                //计算当前方案是否存在断点，true：不存在断点，所有分支都可以联通
                if (sonSate) {
                    //传入新的方案,计算新的方案的平均闭环数
                    List<List<String>> lists = recognizeLoopNew(coppysonedges);
                    //样本成功数
                    simpleSuccess++;
                    //方案闭环数量
                    loopTotal = loopTotal + lists.size();
                } else {
                    //存在断点的方案数
                    simpleFail++;
                }
                //样本成功数大于可打断分支的一半，
                if (simpleSuccess > (conformList.size() / 2 + 1) || simpleFail > 10000) {
                    //当成功生成的个体数量超过这个阈值时，认为已经生成了足够多的有效个体，可以退出
                    if (simpleSuccess != 0.0) {
                        //比如60个方案中产生了180个闭环数，就是180/60
                        double v = loopTotal / simpleSuccess;
                        if (v < 1) {
                            breakLoop = true;
                            break;
                        } else {
                            //分支打断数量-平均闭环数量
                            averageNumberB.put(selectNumberB, v);
                            break;
                        }
                    } else {
                        breakLoop = true;
                        break;
                    }
                }
            }
            if (breakLoop) {
                break;
            }
            selectNumberB++;
        }


        //       对当前的平均数做一个排序找到第一个闭合平均值小于10的B的数量；降序排列
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(averageNumberB.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<Integer, Double>>() {
            @Override
            public int compare(Map.Entry<Integer, Double> o1, Map.Entry<Integer, Double> o2) {
                return o2.getValue().compareTo(o1.getValue());
            }
        });
        Integer minLoopNumber = null;
        Integer maxLoopNumber = null;
        for (Map.Entry<Integer, Double> entry : list) {
            //筛选平均闭环数小于10的B的数量
            if (entry.getValue() < 10 && minLoopNumber == null) {
                minLoopNumber = entry.getKey();
            }
        }
        if (list.size() > 0) {
            maxLoopNumber = list.get(list.size() - 1).getKey();
        }
        System.out.println("结束计算不同B闭环的平均值，所用时间：" + (System.currentTimeMillis() - l));
        l = System.currentTimeMillis();
        System.out.println("开始生成初代样本");
//        接下来就是根据上面找到的key进行一个样本的创建 这里会生成指定数量的方案，满足约束条件，闭环下价值评估的方案
        List<List<String>> simpleList = initialOptimize(minLoopNumber, maxLoopNumber, initialScheme, togetherBCList, conformList, normList, onlyNameS, edges, appPositions, eleclection, mutexMap,
                mutexGroupList, chooseOneList, sortedMapExcel, sortedMap, circuitInfoList);
        //添加初始方案分支打断状况
        simpleList.add(primeList);
        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            initializeCaseResultMap.put("finishStatue", "abnormal");
            List<Map<String, Object>> mapList = handleAndShowTop(jsonMap, "abnormal", singleBCList, singleSCList, singleBSList, singleBSCList, normList, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
            mapList.add(initializeCaseResultMap);
            System.out.println(objectMapper.writeValueAsString(mapList));
            return objectMapper.writeValueAsString(mapList);
        }
        System.out.println("生成初代样本结束，所用时间" + (System.currentTimeMillis() - l));
        //        将生成的方案存放到仓库当中去
        WareHouse.addAll(simpleList);
//       找出初始样本的的最优值
        System.out.println("找出初代样本的最优值");
        l = System.currentTimeMillis();
        //对初始生成的方案进行处理和优化，找出最佳方案，通过将闭环中可更改分支状态为s来消除闭环
        //对上面生成的闭环方案进行计算，计算他们的成本，按价格排序 ，返回成本最优的20条方案
        List<Map<String, Object>> findBest = changeAndFindBest(simpleList, edges, normList, wearId, canChangeS, jsonMap, edgeChooseBS);
        TopDetail = findBest;
        if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
            initializeCaseResultMap.put("finishStatue", "abnormal");
            List<Map<String, Object>> mapList = handleAndShowTop(jsonMap, "abnormal", singleBCList, singleSCList, singleBSList, singleBSCList, normList, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
            mapList.add(initializeCaseResultMap);
            System.out.println(objectMapper.writeValueAsString(mapList));
            return objectMapper.writeValueAsString(mapList);
        }
//        将结果记录到excel中去
        System.out.println("初代样本的最优值寻找完成，并记录到excel中，所用时间：" + (System.currentTimeMillis() - l));
        int hybridizationNumber = 1;
//       对初始方案进行进行一个记录  并且开启迭代
        System.out.println("开始进行迭代");

//        将初始化方案也放入到迭代中去
        Map<String, Object> addtoMap = new HashMap<>();
        addtoMap.put("serviceableStatue", primeList);
        WareHouseAI.add(primeList);
        findBest.add(addtoMap);
        long functionStartTime = System.currentTimeMillis();
        System.out.println("遗传算法前WarehouseAi仓库样本数量：" + WareHouseAI.size());
        //遗传算法
        while (true) {
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                initializeCaseResultMap.put("finishStatue", "abnormal");
                List<Map<String, Object>> mapList = handleAndShowTop(jsonMap, "abnormal", singleBCList, singleSCList, singleBSList, singleBSCList, normList, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
                mapList.add(initializeCaseResultMap);
                System.out.println(objectMapper.writeValueAsString(mapList));
                String s = objectMapper.writeValueAsString(mapList);
                return objectMapper.writeValueAsString(mapList);
            }
            System.out.println(hybridizationNumber + "代迭代开始");
            long startTime = System.currentTimeMillis();
//            只有当迭代的结果top10都是同一个值的时候    才结束迭代
            findBest = hybridization(findBest, onlyNameS, normList, conformList, togetherBCList, canChangeS, edges, appPositions, eleclection,
                    mutexMap, minLoopNumber, maxLoopNumber, initialScheme, wearId, jsonMap, edgeChooseBS, chooseOneList, mutexGroupList, sortedMapExcel, sortedMap, circuitInfoList);
            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                initializeCaseResultMap.put("finishStatue", "abnormal");
                List<Map<String, Object>> mapList = handleAndShowTop(jsonMap, "abnormal", singleBCList, singleSCList, singleBSList, singleBSCList, normList, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
                mapList.add(initializeCaseResultMap);
                System.out.println(objectMapper.writeValueAsString(mapList));
                String s = objectMapper.writeValueAsString(mapList);
                return objectMapper.writeValueAsString(mapList);
            }
            TopDetail = findBest;
            System.out.println("第" + hybridizationNumber + "代迭代结束，耗时：" + (System.currentTimeMillis() - startTime));
            if (hybridizationNumber == 1) {
                double costTotal = Double.parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double.parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double.parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总重量").toString());
                BestCost.put("总成本", costTotal);
                BestCost.put("总长度", costLenth);
                BestCost.put("总重量", costWeight);
            } else {
                //获取当前最优解的各项指标
                double costTotal = Double.parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总成本").toString());
                double costLenth = Double.parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总长度").toString());
                double costWeight = Double.parseDouble(((Map<String, Object>) findBest.get(0).get("成本")).get("总重量").toString());
//            当前最优解中的长度判断当前的成本、长度、重量是都一样
                //判断是否与历史最优解基本相同（允许微小误差)
                if (Math.abs(BestCost.get("总成本") - costTotal) < 0.000001
                        && Math.abs(BestCost.get("总长度") - costLenth) < 0.000001
                        && Math.abs(BestCost.get("总重量") - costWeight) < 0.000001) {
                    BestRepetitionNumber = BestRepetitionNumber + 1;        //相同则计数器加1
                } else {
                    BestRepetitionNumber = 0;           //不同则重置计数器
                    //更新历史最优解
                    BestCost.put("总成本", costTotal);
                    BestCost.put("总长度", costLenth);
                    BestCost.put("总重量", costWeight);
                }
            }
            //TODO 迭代次数过多，多余的迭代次数会增加耗时
            if (BestRepetitionNumber == IterationRestrictNumber) {
                System.out.println("迭代结束原因：迭代次数达到限制，后续与上一代结果相同达到30次");
                break;
            }
            hybridizationNumber++;
            if (hybridizationNumber > IterationNumber) {
                break;
            }
        }
        System.out.println("遗传算法后WarehouseAi仓库样本数量：" + WareHouseAI.size());
        long functionendTime = System.currentTimeMillis();
        System.out.println("遗传算法总迭代耗时：" + (functionendTime - functionStartTime));
        System.out.println("遗传算法后WarehouseAi样本数量：" + WareHouseAI.size());
        TopDetail = findBest;
        List<Map<String, Object>> mapList = handleAndShowTop(jsonMap, "normal", singleBCList, singleSCList, singleBSList, singleBSCList, normList, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
        System.out.println("方案再优化后，WarehouseAi样本数量:" + WareHouseAI.size());
        //AI样本生成
        GenerateAiCaseUtils generateAiCaseUtils = new GenerateAiCaseUtils();
        System.out.println("AI样本开始生成");
        long generateAiCase = System.currentTimeMillis();
        generateAiCaseUtils.exportJson(normList, WareHouseAI, edges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList, jsonMap, ProjectCircuitInfoOutput.elecFixedLocationLibrary, togetherBCMap, chooseOneMap);
        System.out.println("AI样本生成结束，耗时：" + (System.currentTimeMillis() - generateAiCase));
        initializeCaseResultMap.put("finishStatue", "normal");
        mapList.add(initializeCaseResultMap);
        String s = objectMapper.writeValueAsString(mapList);
        long end = System.currentTimeMillis();
        System.out.println("算法总耗时长：" + (end - start));
        threadPool.terminateNow();
        return objectMapper.writeValueAsString(mapList);
    }

    /**
     * 分支枚举
     *
     * @param singleBCList
     * @param singleSCList
     * @param singleBSList
     * @param singleBSCList
     * @param edges         原方案分支详情
     * @param normList      原方案分支顺序
     */
    public String branchEnum(List<String> singleBCList, List<String> singleSCList, List<String> singleBSList, List<String> singleBSCList,
                             List<Map<String, Object>> edges, List<String> normList, List<Map<String, String>> appPositions,
                             Map<String, String> eleclection, Map<String, Map<String, List<String>>> mutexMap, List<Map<String, List<String>>> chooseOneList,
                             List<List<String>> togetherBCList, Map<String, Object> jsonMap, Map<String, Object> initializeCaseResultMap, List<String> wearId, List<Map<String, String>> topoOptimizeResultStatute) throws Exception {
        //合并分支
        //分支id-分支可选状态
        long startTime = System.currentTimeMillis();
        List<Map<String, List<String>>> branchList = new ArrayList<>();
        //需要排序的分支id
        List<String> idsList = new ArrayList<>();
        List<List<String>> branchTypeList = new ArrayList<>();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        if (singleBSCList.size() > 0) {
            for (String s : singleBSCList) {
                Map<String, List<String>> branchType = new LinkedHashMap<>();
                branchType.put(s, Arrays.asList("B", "C", "S"));
                branchList.add(branchType);
                branchTypeList.add(Arrays.asList("B", "C", "S"));
                idsList.add(s);
            }
        }
        if (singleSCList.size() > 0) {
            for (String s : singleSCList) {
                Map<String, List<String>> branchType = new LinkedHashMap<>();
                branchType.put(s, Arrays.asList("S", "C"));
                branchList.add(branchType);
                branchTypeList.add(Arrays.asList("S", "C"));
                idsList.add(s);
            }
        }
        if (singleBSList.size() > 0) {
            for (String s : singleBSList) {
                Map<String, List<String>> branchType = new LinkedHashMap<>();
                branchType.put(s, Arrays.asList("B", "S"));
                branchList.add(branchType);
                branchTypeList.add(Arrays.asList("B", "S"));
                idsList.add(s);
            }
        }
        if (singleBCList.size() > 0) {
            for (String s : singleBCList) {
                Map<String, List<String>> branchType = new LinkedHashMap<>();
                branchType.put(s, Arrays.asList("B", "C"));
                branchTypeList.add(Arrays.asList("B", "C"));
                branchList.add(branchType);
                idsList.add(s);
            }
        }
        List<Callable<List<Map<String, String>>>> tasks = new ArrayList<>();
        //递归对分支状态进行枚举
        for (int i = 0; i < branchTypeList.get(0).size(); i++) {
            final int index = i;
            tasks.add(() -> {
                List<List<String>> branchTypeListCopy = branchTypeList.stream().collect(Collectors.toList());
                List<String> idsListCopy = idsList.stream().collect(Collectors.toList());
                Map<String, List<String>> branchTypeInfo = branchList.get(0);
                //拿到第一个分支的状态
                List<String> branchType = branchTypeInfo.get(idsList.get(0));
                //每个线程分支初始化状态
                String s = branchType.get(index);
                List<Map<String, String>> optimizeList = new ArrayList<>();
                Map<String, String> cases = new LinkedHashMap<>();
                cases.put(idsList.get(0), s);
                List<Map<String, String>> result = new ArrayList<>();

                //递归
                int branchIndex = 0;
                branchTypeListCopy.remove(0);
                idsListCopy.remove(0);
                List<Map<String, String>> maps = recursionEnum(branchTypeListCopy, branchIndex, cases, result, idsListCopy);
                return maps;
            });
        }
        //获取线程池执行结果
        List<Future<List<Map<String, String>>>> futures = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        for (Callable<List<Map<String, String>>> task : tasks) {
            futures.add(threadPool.submit(task));
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (Future<List<Map<String, String>>> future : futures) {
            try {
                List<Map<String, String>> maps = future.get();
                result.addAll(maps);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("递归获取结果异常");
            }
        }
        System.out.println("递归结束耗时：" + (System.currentTimeMillis() - startTime));
        //分支状态替换
        List<Map<String, Object>> totalCase = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> projectInfo = (Map<String, Object>) jsonMap.get("projectInfo");
        long costTime = System.currentTimeMillis();
        List<Callable<Map<String, Object>>> tasksResult = new ArrayList<>();
        List<List<String>> total = new ArrayList<>();
        for (Map<String, String> types : result) {
            tasksResult.add(() -> {
                List<Map<String, Object>> edgesCopy = edges.stream().collect(Collectors.toList());

                List<String> collect = new ArrayList<>();
                topoOptimizeResultStatute.forEach(map -> {
                    collect.add(map.get("statue"));
                });
                types.forEach((id, type) -> {
                    //分支id索引
                    int i = normList.indexOf(id);
                    collect.set(i, type);
                });
                if (containsList(collect, total)) {
                    return null;
                } else {
                    total.add(collect);
                }
                //每生成一个方案进行检查约束
                List<Map<String, Object>> edgesDetail = createNewEdges(collect, edgesCopy, normList);
                Boolean staute = checkFirstOption(normList, collect, edgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                Map<String, Object> map = new LinkedHashMap<>();
                if (staute) {
                    //计算成本
                    Map<String, Object> threadLocalJsonMap = new HashMap<>(jsonMap);
                    threadLocalJsonMap.put("edges", edgesDetail);

                    String projectCircuitInfoOutputRsult = projectCircuitInfoOutput.projectCircuitInfoOutput(mapper.writeValueAsString(threadLocalJsonMap));
                    Map<String, Object> objectMap = jsonToMap.TransJsonToMap(projectCircuitInfoOutputRsult);
                    Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMap.get("projectCircuitInfo");

                    Map<String, Object> costResultData = new HashMap<>();
                    costResultData.put("总成本", projectCircuitInfo.get("总成本"));
                    costResultData.put("总长度", projectCircuitInfo.get("回路总长度"));
                    costResultData.put("总重量", projectCircuitInfo.get("回路总重量"));
                    List<Map<String, String>> topoOptimizeResult = new ArrayList<>();
                    for (Map<String, Object> map1 : edgesDetail) {
                        Map<String, String> temp = new HashMap<>();
                        temp.put("edgeId", map1.get("id").toString());
                        temp.put("statue", map1.get("topologyStatusCode").toString());
                        topoOptimizeResult.add(temp);
                    }

                    map.put("成本", costResultData);
                    map.put("topoId", topoInfoMap.get("id").toString());
                    map.put("caseId", projectInfo.get("caseId").toString());
                    map.put("topoOptimizeResult", topoOptimizeResult);
                    map.put("finishStatue", "normal");
                    map.put("initializationScheme", false);
                    map.putAll(objectMap);
                }
                return map;
            });
        }
        List<Future<Map<String, Object>>> futuresResult = new ArrayList<>();
        for (Callable<Map<String, Object>> mapCallable : tasksResult) {
            futuresResult.add(threadPool.submit(mapCallable));
        }
        for (Future<Map<String, Object>> future : futuresResult) {
            try {
                Map<String, Object> map = future.get();
                if (map.size() != 0 && map != null && "normal".equals(map.get("finishStatue"))) {
                    synchronized (totalCase) {
                        totalCase.add(map);
                    }
                }
            } catch (Exception e) {

            }
        }
        System.out.println("方案成本与约束检查总耗时：" + (System.currentTimeMillis() - costTime));
        FindBest findBest = new FindBest();
        ObjectMapper objectMapper = new ObjectMapper();
        totalCase = findBest.findBest(totalCase, "成本", TopNumber);
        for (Map<String, Object> map : totalCase) {
            map.remove("成本");
        }
        initializeCaseResultMap.put("finishStatue", "normal");
        totalCase.add(initializeCaseResultMap);
        String s = objectMapper.writeValueAsString(totalCase);
        return s;
    }

    /**
     * 递归枚举
     *
     * @param branchTypeList 分支可选类型集合
     * @param branchIndex    当前处理的分支索引
     * @param cases          生成的方案
     */
    public List<Map<String, String>> recursionEnum(List<List<String>> branchTypeList, int branchIndex, Map<String, String> cases, List<Map<String, String>> result, List<String> idsList) {
        //终止条件,如果到达最后一层
        if (branchIndex > branchTypeList.size() - 1) {
            //对生成的组合进行约束检查,这里一定不要对原始的方案进行影响
            result.add(new LinkedHashMap<>(cases));
            return result;
        }
        List<String> types = branchTypeList.get(branchIndex);
        String branchId = idsList.get(branchIndex);
        for (String type : types) {
            //分支选择状态
            cases.put(branchId, type);
            //递归
            recursionEnum(branchTypeList, branchIndex + 1, cases, result, idsList);
            //回溯
            if (types.indexOf(type) == types.size() - 1) {
                break;
            }
            cases.remove(branchId);
        }
        return result;
    }


    /**
     * @Description: 对top的方案进行再次变异   结果中分支打断状况为S的 可以变化的进行变化  在总成本小于3的情况下 选择当前的方案(主要为了降低S的数量)
     * @input: findBest  当前最优的top10 方案
     * @input: singleBCList  分支打断可选BC的集合
     * @input: singleSCList 分支打断可选SC的集合
     * @input: singleBSLis 分支打断可选BS的集合
     * @input: singleBSCList 分支打断可选BSC的集合
     * @input: normList 按照顺序的id排放顺序
     * @input: jsonMap  最初获取的json字符串，转为map格式
     * @input: eleclection 用电器对应的位置点
     * @input: wearId   穿腔的分支
     * @input: mutexMap  互斥的情况的分支
     * @input: chooseOneList   多选一的分支
     * @input:togetherBCList 组团一起变的分支
     * @Return: 返回修改后的top的方案
     */
    public List<Map<String, Object>> bestOptionVariation(List<Map<String, Object>> findBest,
                                                         List<String> singleBCList,
                                                         List<String> singleSCList,
                                                         List<String> singleBSList,
                                                         List<String> singleBSCList,
                                                         List<String> normList,
                                                         Map<String, Object> jsonMap,
                                                         Map<String, String> eleclection,
                                                         List<String> wearId,
                                                         Map<String, Map<String, List<String>>> mutexMap,
                                                         List<Map<String, List<String>>> chooseOneList,
                                                         List<List<String>> togetherBCList) throws Exception {
        //状态检查
        if (threadPool.shouldStop()) {
            return null;
        }
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        List<Map<String, Object>> bestOption = new ArrayList<>();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
//        可打B的分支
        List<String> canChangeToB = new ArrayList<>();
        canChangeToB.addAll(singleBCList);
        canChangeToB.addAll(singleBSList);
        canChangeToB.addAll(singleBSCList);
//        S可还原为C的集合
        List<String> restore = new ArrayList<>();
        restore.addAll(singleSCList);
        restore.addAll(singleBSCList);

        for (Map<String, Object> map : findBest) {
            Map<String, Double> costDetail = (Map<String, Double>) map.get("成本");
            double costTotal = costDetail.get("总成本");
            List<String> statueList = (List<String>) map.get("serviceableStatue");

//            首先进行一个分支打断代价计算，将当中S的打断代价为0并且在符合分支拓扑约束的条件下 将S改为B
            List<Map<String, Object>> firstEdgesDetail = createNewEdges(statueList, edges, normList);
            jsonMap.put("edges", firstEdgesDetail);
            String firstoptimizeInterface = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> firstObjectMap = jsonToMap.TransJsonToMap(firstoptimizeInterface);
//           计算每一个分支的打断代价
            Map<String, Object> firstbundeleRelatedCircuitInfo = (Map<String, Object>) firstObjectMap.get("bundeleRelatedCircuitInfo");
            Map<String, Double> firstbreakCostMap = new HashMap<>();
            for (String s : firstbundeleRelatedCircuitInfo.keySet()) {
                Map<String, Object> edgeMap = (Map<String, Object>) firstbundeleRelatedCircuitInfo.get(s);
                Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                firstbreakCostMap.put(s, Double.parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
            }
//            找出分支为S的id 打断代价s为0的改为b
            for (int i = 0; i < statueList.size(); i++) {
                if (statueList.get(i).equals("S")) {
                    String id = normList.get(i);
                    if (firstbreakCostMap.get(id) == 0) {
                        List<String> newEdges = statueList.stream().collect(Collectors.toList());
                        newEdges.set(normList.indexOf(id), "B");
                        List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
                        Boolean flag = checkFirstOption(normList, newEdges, edgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                        if (flag) {
                            statueList.set(normList.indexOf(id), "B");
                        }
                    }
                }
            }
            //       找出当中可还原S的集合
            List<String> canRestoreid = new ArrayList<>();
            for (int i = 0; i < statueList.size(); i++) {
                if (statueList.get(i).equals("S") && restore.contains(normList.get(i))) {
                    canRestoreid.add(normList.get(i));
                }
            }
//            对每一个S 还原成C     检查是否存在闭环  如果存在闭环  在闭环里面能够变为B的分支  检查当前的方案是否合理   合理的情况下计算成本   如果成本与原方案相比小于3  则用该方案
            for (String s : canRestoreid) {
                List<String> newEdges = statueList.stream().collect(Collectors.toList());
                newEdges.set(normList.indexOf(s), "C");
                List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
//                对当前的方案进行一个检查
                while (true) {
                    List<List<String>> lists = recognizeLoopNew(edgesDetail);
                    Set<String> loopList = new HashSet<>();
                    for (String s1 : wearId) {
                        for (List<String> list : lists) {
                            if (list.contains(s1)) {
                                loopList.addAll(list);
                            }
                        }
                    }
                    loopList.retainAll(canChangeToB);
                    Map<String, Double> costMap = new HashMap<>();
//                如果存在闭环
                    if (loopList.size() == 0) {
                        statueList.set(normList.indexOf(s), "C");
                        break;
                    }
                    if (loopList.size() > 0) {
                        //对存在闭环的分支进行逐个打断，看打断后的新方案是否符合约束条件，如果符合则存储，对生成的方案和原始方案成本进行比较，小于3
                        for (String s1 : loopList) {
                            List<String> calculateLoop = newEdges.stream().collect(Collectors.toList());
                            calculateLoop.set(normList.indexOf(s1), "B");
                            List<Map<String, Object>> calculateEdgesDetail = createNewEdges(calculateLoop, edges, normList);
                            Boolean sonSate = checkFirstOption(normList, calculateLoop, calculateEdgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                            if (!sonSate) {
                                continue;
                            }
                            jsonMap.put("edges", calculateEdgesDetail);
                            String optimizeInterfacesresult = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
                            Map<String, Object> objectMap = jsonToMap.TransJsonToMap(optimizeInterfacesresult);
                            Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMap.get("projectCircuitInfo");
                            Double currentalCost = (Double) projectCircuitInfo.get("总成本");
                            costMap.put(s1, currentalCost);
                        }
                    }
                    if (costMap.size() > 0) {
                        String minKey = null;
                        double minValue = Double.MAX_VALUE;
                        for (Map.Entry<String, Double> entry : costMap.entrySet()) {
                            if (entry.getValue() < minValue) {
                                minValue = entry.getValue();
                                minKey = entry.getKey();
                            }
                        }
                        Double aDouble = costMap.get(minKey);
                        if (aDouble - costTotal < 3) {
                            newEdges.set(normList.indexOf(minKey), "B");
                            edgesDetail = createNewEdges(newEdges, edges, normList);
                            statueList.set(normList.indexOf(s), "C");
                            statueList.set(normList.indexOf(minKey), "B");
                            costTotal = aDouble;
                        } else {
                            statueList.set(normList.indexOf(s), "S");
                            break;
                        }
                    }
//                    else {
//                        //如果对存在闭环的分支都进行打断还是没有符合约束规则的优化方案，则不需要一直while了
//                        break;
//                    }
                }
            }
            List<Map<String, Object>> EdgesDetail = createNewEdges(statueList, edges, normList);
            List<String> newEdges1 = statueList.stream().collect(Collectors.toList());
            Boolean sonSate = checkFirstOption(normList, newEdges1, EdgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
            if (!sonSate) {
                continue;
            }
            jsonMap.put("edges", EdgesDetail);
            String optimizeInterface = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> objectMap = jsonToMap.TransJsonToMap(optimizeInterface);
//           计算每一个分支的打断代价
            Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) objectMap.get("bundeleRelatedCircuitInfo");
            Map<String, Double> breakCostMap = new HashMap<>();
            for (String s : bundeleRelatedCircuitInfo.keySet()) {
                Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
                Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                breakCostMap.put(s, Double.parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
            }
            breakCostMap = sortMapByDoubleValue(breakCostMap);
//            满足条件的分支打断代价等于0的分支id
            List<String> list0 = new ArrayList<>();
//            找出当中分支打断代价等于0的分支
            breakCostMap.entrySet().stream().filter(entry -> entry.getValue() == 0).forEach(entry -> {
                String key = entry.getKey();
                list0.add(key);
            });
            for (String s : list0) {
                List<String> newEdges = statueList.stream().collect(Collectors.toList());
                newEdges.set(normList.indexOf(s), "B");
                List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
//                对当前的方案进行一个检查
                Boolean flag = checkFirstOption(normList, newEdges, edgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);

                if (flag) {
                    statueList.set(normList.indexOf(s), "B");
                } else {
                    continue;
                }
            }


//           将分支打断代价小于三块的改为B 计算总成本，如果新的总成本不超过之前的三块  则就用这个方案
            List<Map<String, Object>> betweenEdgeresult = createNewEdges(statueList, edges, normList);
            jsonMap.put("edges", betweenEdgeresult);
            String betweenoptimizeInterfacesresult = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> betweenobjectMapresult = jsonToMap.TransJsonToMap(betweenoptimizeInterfacesresult);
            Map<String, Object> betweenprojectCircuitInfo = (Map<String, Object>) betweenobjectMapresult.get("projectCircuitInfo");
            Double betweencurrentalCost = (Double) betweenprojectCircuitInfo.get("总成本");
//            找出分支打断代价小于3的id
            List<String> list3 = new ArrayList<>();
            breakCostMap.entrySet().stream().filter(entry -> 0 < entry.getValue() && entry.getValue() < 3).forEach(entry -> {
                String key = entry.getKey();
                if (canChangeToB.contains(key)) {
                    list3.add(key);
                }

            });
//            list3逐一进行检擦
            for (String s : list3) {
                List<String> newEdges = statueList.stream().collect(Collectors.toList());
                newEdges.set(normList.indexOf(s), "B");
                List<Map<String, Object>> edgesDetail = createNewEdges(newEdges, edges, normList);
                Boolean flag = checkFirstOption(normList, newEdges, edgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                if (flag) {
                    jsonMap.put("edges", edgesDetail);
                    String betweenoptimizeInterfacesresultSon = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
                    Map<String, Object> betweenobjectMapresultSon = jsonToMap.TransJsonToMap(betweenoptimizeInterfacesresultSon);
                    Map<String, Object> betweenprojectCircuitInfoSon = (Map<String, Object>) betweenobjectMapresultSon.get("projectCircuitInfo");
                    Double betweencurrentalCostSon = (Double) betweenprojectCircuitInfoSon.get("总成本");

                    if (betweencurrentalCostSon < betweencurrentalCost || betweencurrentalCostSon - betweencurrentalCost < 2) {
                        statueList.set(normList.indexOf(s), "B");
                        betweencurrentalCost = betweencurrentalCostSon;
                    }
                } else {
                    continue;
                }
            }


//            对最终的方案进行一个计算  并且按照格式进行一个返回
            List<Map<String, Object>> finalEdgeresult = createNewEdges(statueList, edges, normList);
            jsonMap.put("edges", finalEdgeresult);
            String optimizeInterfacesresult = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
            Map<String, Object> objectMapresult = jsonToMap.TransJsonToMap(optimizeInterfacesresult);
            Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMapresult.get("projectCircuitInfo");
            Map<String, Double> finalCostDetail = new HashMap<>();
            finalCostDetail.put("总成本", (Double) projectCircuitInfo.get("总成本"));
            finalCostDetail.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));
            finalCostDetail.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("成本", finalCostDetail);
            finalResult.put("serviceableStatue", statueList);
            System.out.println(statueList);
            finalResult.put("serviceableEdges", finalEdgeresult);
            bestOption.add(finalResult);
        }
        return bestOption;
    }


    /**
     * @Description: 对top20的数据进行一个处理返回给前端
     * @input: jsonMap  最初的json字串转为map格式
     * @input: finishStatue   返回的状态
     * @Return: 返回top10的方案，对返回的格式进行了修改
     */
    public List<Map<String, Object>> handleAndShowTop(Map<String, Object> jsonMap,
                                                      String finishStatue,
                                                      List<String> singleBCList,
                                                      List<String> singleSCList,
                                                      List<String> singleBSList,
                                                      List<String> singleBSCList,
                                                      List<String> normList,
                                                      Map<String, String> eleclection,
                                                      List<String> wearId,
                                                      Map<String, Map<String, List<String>>> mutexMap,
                                                      List<Map<String, List<String>>> chooseOneList,
                                                      List<List<String>> togetherBCList) throws Exception {
        FindBest findBest = new FindBest();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> projectInfo = (Map<String, Object>) jsonMap.get("projectInfo");
        List<Map<String, Object>> resultList = new ArrayList<>();
        List<Map<String, Double>> costDeail = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        if (TopCostDetail != null) {
            List<Map<String, Object>> sortcost = findBest(TopCostDetail, "成本");
            for (Map<String, Object> sortcostMap : sortcost) {
                tasks.add(() -> {
//                if (resultList.size() == TopNumber) {
//                    break;
//                }
                    List<Map<String, Object>> mapArrayList = new ArrayList<>();
                    mapArrayList.add(sortcostMap);
                    long startTime = System.currentTimeMillis();
                    List<Map<String, Object>> handleList = bestOptionVariation(mapArrayList, singleBCList, singleSCList, singleBSList, singleBSCList, normList, jsonMap, eleclection, wearId, mutexMap, chooseOneList, togetherBCList);
                    System.out.println("方案变异时间:" + (System.currentTimeMillis() - startTime));
                    if (handleList.size() == 0) {
                        return null;
                    }
                    Map<String, Object> objectMap = handleList.get(0);
                    //变异后分支状态
                    List<String> serviceableStatute = (List<String>) objectMap.get("serviceableStatue");
                    synchronized (WareHouseAI) {
//                        if (!containsList(serviceableStatute, WareHouseAI) && WareHouseAI.size() < AutoCompleteNumberLimit) {
                        if ( WareHouseAI.size() < AutoCompleteNumberLimit) {
                            List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
                            List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
                            List<Map<String, Object>> edgesDetail = createNewEdges(serviceableStatute, edges, normList);
                            Boolean flag = checkFirstOption(normList, serviceableStatute, edgesDetail, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                            if (!flag) {
                                System.out.println("不符合约束");
                            }
                            List<List<String>> lists = recognizeLoopNew(edgesDetail);
                            if (lists.size() != 0) {
                                System.out.println("存在回路");
                            }
                            WareHouseAI.add(serviceableStatute);
                        }
                    }
                    Map<String, Double> cost = (Map<String, Double>) objectMap.get("成本");
                    if (costDeail.contains(cost)) {
                        System.out.println("成本重复");
                        return null;
                    }
                    synchronized (costDeail) {
                        if (costDeail.contains(cost)) {
                            System.out.println("成本重复");
                            return null;
                        }
                        // 保持线程安全
                        costDeail.add(cost);
                    }
                    List<Map<String, Object>> mapList = (List<Map<String, Object>>) objectMap.get("serviceableEdges");
                    //保持线程安全 浅拷贝一份
                    HashMap<String, Object> newJsonMap = new HashMap<>(jsonMap);
                    newJsonMap.put("edges", mapList);
                    String s = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(newJsonMap));
                    List<Map<String, String>> topoOptimizeResult = new ArrayList<>();
                    for (Map<String, Object> map : mapList) {
                        Map<String, String> result = new HashMap<>();
                        result.put("edgeId", map.get("id").toString());
                        result.put("statue", map.get("topologyStatusCode").toString());
                        topoOptimizeResult.add(result);
                    }
                    Map<String, Object> map = jsonToMap.TransJsonToMap(s);
                    Map<String, Object> projectCircuitInfo = (Map<String, Object>) map.get("projectCircuitInfo");
                    Map<String, Double> projectCost = new HashMap<>();
                    projectCost.put("总成本", (Double) projectCircuitInfo.get("总成本"));
                    projectCost.put("总重量", (Double) projectCircuitInfo.get("回路总重量"));
                    projectCost.put("总长度", (Double) projectCircuitInfo.get("回路总长度"));

                    map.put("成本", projectCost);
                    map.put("topoId", topoInfoMap.get("id").toString());
                    map.put("caseId", projectInfo.get("caseId").toString());
                    map.put("topoOptimizeResult", topoOptimizeResult);
                    map.put("finishStatue", finishStatue);
                    map.put("initializationScheme", false);
                    return map;
                });
            }
        }
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        List<Future<Map<String, Object>>> completeFutures = new ArrayList<>();
        int submittedCount = 0;
        //每次提交10个任务
        int batchSize = 10;
        for (Callable<Map<String, Object>> task : tasks) {
            //检查状态，防止多次提交
            if (resultList.size() == TopNumber) {
                threadPool.terminateNow();
                break;
            }
            Future<Map<String, Object>> submit = threadPool.submit(task);
            futures.add(submit);
            submittedCount++;
            if (submittedCount % batchSize == 0 || submittedCount == tasks.size()) {
                //获取已完成的任务结果
                for (Future<Map<String, Object>> future : futures) {
                    if (completeFutures.contains(future)) {
                        continue;  // 已处理过的跳过
                    }
                    try {
                        Map<String, Object> result = future.get(240, java.util.concurrent.TimeUnit.SECONDS);
                        synchronized (resultList) {
                            if (result != null) {
                                resultList.add(result);
                            }
                            completeFutures.add(future);  // 添加到已完成列表
                        }

                        if (resultList.size() == TopNumber) {
                            System.out.println("方案数量已经达到20个");
                            break;
                        }
                    } catch (Exception e) {
                        synchronized (resultList) {
                            completeFutures.add(future);  // 异常也算完成，添加到已完成列表
                        }
                    }

                }
                if (resultList.size() == TopNumber) {
                    break;
                }
            }
            if (resultList.size() == TopNumber) {
                break;
            }
        }

        System.out.println("所有任务完成，结果数: " + resultList.size());
        resultList = findBest(resultList, "成本");
        for (Map<String, Object> map : resultList) {
            map.remove("成本");
        }

        return resultList;
    }


    /**
     * @Description 根据给定的top10进行一个不断的迭代
     * @input findBest   给定的top10
     * @input onlyNameS  只能为S的分支
     * @input normList   分支的排放顺序
     * @input conformList  可以变为B的分支id集合
     * @input togetherBCList  组团的分支id集合
     * @input canChangeS 可以改为s分支
     * @input edges txt当中对应的分支信息
     * @input appPositions  txt中对应的用电器西悉尼
     * @input eleclection   用电器对应的位置点
     * @input mutexMap  互斥的情况
     * @input minLoopNumber 最小选取B的数量
     * @input maxLoopNumber 最大选取B的数量
     * @input initialScheme 初始化的分支打断情况（除了固定B的别的全为C）
     * @input wearId 存在穿腔的分支id
     * @input jsonMap 将txt转为Map
     * @input edgeChooseBS 分支打断可以选BS的集合
     * @input chooseOneList 多选一的分支情况
     * @input mutexGroupList 组团互斥的情况
     * @input sortedMapExcel   导线选型excel中sheet1中的西悉尼   并且按照导线的商务价格继续进行一个排序
     * @input sortedMap 分支的打断代价并按照打断代价进行一个升序
     * @input circuitInfoList   回路信息
     * @Return 返回迭代过后的top10
     */
    public List<Map<String, Object>> hybridization(List<Map<String, Object>> findBest,
                                                   List<String> onlyNameS,
                                                   List<String> normList,
                                                   List<String> conformList,
                                                   List<List<String>> togetherBCList,
                                                   List<String> canChangeS,
                                                   List<Map<String, Object>> edges,
                                                   List<Map<String, String>> appPositions,
                                                   Map<String, String> eleclection,
                                                   Map<String, Map<String, List<String>>> mutexMap,
                                                   int minLoopNumber, int maxLoopNumber,
                                                   List<String> initialScheme,
                                                   List<String> wearId,
                                                   Map<String, Object> jsonMap,
                                                   List<String> edgeChooseBS,
                                                   List<Map<String, List<String>>> chooseOneList,
                                                   List<List<String>> mutexGroupList,
                                                   Map<String, Map<String, String>> sortedMapExcel,
                                                   Map<String, Double> sortedMap,
                                                   List<Map<String, Object>> circuitInfoList) throws Exception {
        //利用约束变异开始时间
        long constraintStartTime = System.currentTimeMillis();
        List<List<String>> simple = new ArrayList<>();
        Random random = new Random();
//        首先将给定的top20的方案进行一个还原   将里面符合要求的S就是可以变s的分支改为C
        List<List<String>> topTenList = new ArrayList<>();
        for (Map<String, Object> objectMap : findBest) {
            List<String> serviceableStatue = (List<String>) objectMap.get("serviceableStatue");
            List<String> copyList = serviceableStatue.stream().collect(Collectors.toList());
            for (int i = 0; i < serviceableStatue.size(); i++) {
                if (serviceableStatue.get(i).equals("S")) {
                    String id = normList.get(i);
                    if (canChangeS.contains(id)) {
                        copyList.set(i, "C");
                    }
                }
            }
            topTenList.add(copyList);
        }

//        对多选一的情况进行随机变异
        for (List<String> list : topTenList) {
            for (Map<String, List<String>> listMap : chooseOneList) {
                Set<String> set = listMap.keySet();
//                检查是否存在C的情况
                Boolean flag = false;
                for (String s : set) {
                    int i = normList.indexOf(s);
                    if (list.get(i).equals("C")) {
                        flag = true;
                    }
                }
                if (!flag) {
                    //                不存在C 随机选取一个S变为C  前提是这个S
//                找出分支为S的集合
                    List<String> canchangeId = new ArrayList<>();
                    for (String s : set) {
                        if (list.get(normList.indexOf(s)).equals("S")) {
                            canchangeId.add(s);
                        }
                    }
//               随机选取一个S变为C
                    if (canchangeId.size() > 0) {
                        String s = canchangeId.get(random.nextInt(canchangeId.size()));
                        list.set(normList.indexOf(s), "C");
                    }
                }
            }
        }
//        将之前的top20的方案也添加到新的容器里面
        simple.addAll(topTenList);
        //第一轮变异
        List<List<String>> changebTOc = new ArrayList<>(topTenList);  //B-C
        List<List<String>> changecTOb = new ArrayList<>(topTenList);    //C-B
        for (int i = 0; i < VariationNumber; i++) {
            List<List<String>> bTOc = new ArrayList<>();
            List<List<String>> cTOb = new ArrayList<>();
//            将B改成C
            for (List<String> list : changebTOc) {
                for (int j = 0; j < list.size(); j++) {
                    if (conformList.contains(normList.get(j)) && list.get(j).equals("B")) {
//                            检查当前id是都是组团的的    如果是  就一起改变
                        List<String> changeId = new ArrayList<>();
                        changeId.add(normList.get(j));
                        for (List<String> strings : togetherBCList) {
                            if (strings.contains(normList.get(j))) {
                                changeId = strings;
                            }
                        }
                        List<String> copyList = list.stream().collect(Collectors.toList());
                        for (String s : changeId) {
                            int index = normList.indexOf(s);
                            copyList.set(index, "C");
                        }
                        if (!containsList(copyList, bTOc) && !containsList(copyList, changebTOc)) {
                            bTOc.add(copyList);
                        }

                    }
                }
            }
//            将C改成B
            for (List<String> list : changecTOb) {
                for (int j = 0; j < list.size(); j++) {
                    if (conformList.contains(normList.get(j)) && list.get(j).equals("C")) {
//                            检查当前id是都是组团的的    如果是  就一起改变
                        List<String> changeId = new ArrayList<>();
                        changeId.add(normList.get(j));
                        for (List<String> strings : togetherBCList) {
                            if (strings.contains(normList.get(j))) {
                                changeId = strings;
                            }
                        }
                        List<String> copyList = list.stream().collect(Collectors.toList());
                        for (String s : changeId) {
                            int index = normList.indexOf(s);
                            copyList.set(index, "B");
                        }
                        if (!containsList(copyList, cTOb) && !containsList(copyList, changecTOb)) {
                            cTOb.add(copyList);
                        }

                    }
                }
            }
            changebTOc.addAll(bTOc);
            changecTOb.addAll(cTOb);
        }
//        在第一次变异完成后的结果里面面每一个方案在进行一次随机变异
        List<List<String>> twochangebTOc = new ArrayList<>();
        List<List<String>> twochangecTOb = new ArrayList<>();
        for (List<String> list : changebTOc) {

//            找出可以随机变异的id
            List<String> changeId = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (conformList.contains(normList.get(i)) && list.get(i).equals("B")) {
                    String id = normList.get(i);
                    changeId.add(id);
                }
            }
            if (changeId.size() > 0) {
                double percentNumber = 0.25;
                while (percentNumber < 1) {
                    Map<String, List<String>> percentage = getPercentage(sortedMapExcel, sortedMap, circuitInfoList, percentNumber);
                    List<String> getTop25intersection = percentage.get("getTop25intersection");
                    List<String> getTopunion = percentage.get("getTopunion");
                    Set<String> intersection = new HashSet<>(getTop25intersection);
                    //上面取出后与符合变更的分支取交集
                    intersection.retainAll(changeId);
                    if (intersection.size() > 0) {
                        changeId = new ArrayList<>(intersection);
                        break;
                    }
                    Set<String> union = new HashSet<>(getTopunion);
                    union.retainAll(changeId);
                    if (union.size() > 0) {
                        changeId = new ArrayList<>(union);
                        break;
                    }
                    percentNumber += 0.01;
                }
            }
//            随机挑选一个B改为C
            if (changeId.size() > 0) {
                int randomIndex = random.nextInt(changeId.size());
                String randomId = changeId.get(randomIndex);
                List<String> changeList = new ArrayList<>();
                changeList.add(randomId);
//                考虑我所选的这个是否是组团的情况
                for (List<String> strings : togetherBCList) {
                    if (strings.contains(randomId)) {
                        changeList = strings;
                    }
                }
                List<String> copyList = list.stream().collect(Collectors.toList());
                for (String s : changeList) {
                    copyList.set(normList.indexOf(s), "C");
                }
                twochangebTOc.add(copyList);
            }
        }
        for (List<String> list : changecTOb) {
            //            找出可以随机变异的id
            List<String> changeId = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (conformList.contains(normList.get(i)) && list.get(i).equals("C")) {
                    String id = normList.get(i);
                    changeId.add(id);
                }
            }
            if (changeId.size() > 0) {
                double percentNumber = 0.25;
                while (percentNumber < 1) {
                    Map<String, List<String>> percentage = getPercentage(sortedMapExcel, sortedMap, circuitInfoList, percentNumber);
                    List<String> getLast25intersection = percentage.get("getLastintersection");
                    List<String> getLastunion = percentage.get("getLastunion");
                    Set<String> intersection = new HashSet<>(getLast25intersection);
                    intersection.retainAll(changeId);
                    if (intersection.size() > 0) {
                        changeId = new ArrayList<>(intersection);
                        break;
                    }
                    Set<String> union = new HashSet<>(getLastunion);
                    union.retainAll(changeId);
                    if (union.size() > 0) {
                        changeId = new ArrayList<>(union);
                        break;
                    }
                    percentNumber += 0.01;
                }
            }
            if (changeId.size() > 0) {
                int randomIndex = random.nextInt(changeId.size());
                String randomId = changeId.get(randomIndex);
                List<String> changeList = new ArrayList<>();
                changeList.add(randomId);
                for (List<String> strings : togetherBCList) {
                    if (strings.contains(randomId)) {
                        changeList = strings;
                    }
                }
                List<String> copyList = list.stream().collect(Collectors.toList());
                for (String s : changeList) {
                    copyList.set(normList.indexOf(s), "B");
                }
                twochangecTOb.add(copyList);
            }
        }

        for (List<String> list : twochangecTOb) {
            if (!containsList(list, changecTOb)) {
                changecTOb.add(list);
            }
        }
        for (List<String> list : twochangebTOc) {
            if (!containsList(list, changebTOc)) {
                changebTOc.add(list);
            }
        }
        long constraintEndTime = System.currentTimeMillis();
        System.out.println("约束变异时间：" + (constraintEndTime - constraintStartTime));

        //仓库中的方案检查看是否存在在仓库中时间
        long warehouseStartTime = System.currentTimeMillis();
        //TODO 裂变出的所有方案必须去重

//        变异的样本进行一个检查   如果不存在仓库或者容器当中  添加到容器当中
        for (List<String> list : changebTOc) {
            if (!containsList(list, WareHouse) && !containsList(list, simple)) {

                List<Map<String, Object>> coppysonedges = edges.stream().collect(Collectors.toList());
                for (Map<String, Object> coppyedge : coppysonedges) {
                    String id = (String) coppyedge.get("id");
                    int number = normList.indexOf(id);
                    String s = list.get(number);
                    coppyedge.put("topologyStatusCode", s);
                }
                if (!containsList(list, WareHouseAI) && WareHouseAI.size() < AutoCompleteNumberLimit) {
                    WareHouseAI.add(list);
                }
                Boolean sonSate = checkFirstOption(normList, list, coppysonedges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                if (sonSate) {
                    simple.add(list);
                }
            }
        }
        for (List<String> list : changecTOb) {
            if (!containsList(list, WareHouse) && !containsList(list, simple)) {

                List<Map<String, Object>> coppysonedges = edges.stream().collect(Collectors.toList());
                for (Map<String, Object> coppyedge : coppysonedges) {
                    String id = (String) coppyedge.get("id");
                    int number = normList.indexOf(id);
                    String s = list.get(number);
                    coppyedge.put("topologyStatusCode", s);
                }
//                if(!containsList(list,WareHouseAI) && WareHouseAI.size() < AutoCompleteNumberLimit) {
//                    WareHouseAI.add(list);
//                }
                Boolean sonSate = checkFirstOption(normList, list, coppysonedges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
                if (sonSate) {
                    simple.add(list);
                }
            }
        }
        long warehouseEndTime = System.currentTimeMillis();
        System.out.println("仓库检查时间：" + (warehouseEndTime - warehouseStartTime));

//        将simple添加到仓库里面
        for (List<String> list : simple) {
            if (!containsList(list, topTenList)) {
                WareHouse.add(list);
            }
        }

        int completeNumber = 1;
        //方案补充次数耗时时间
        long completeStartTime = System.currentTimeMillis();
        //如果方案数量不足，那么会生成额外的方案
        //TODO 要求生成的样本数太高，实际生成的样本数没有这个多，但是会强制循环500次
        while (simple.size() < HybridizationLessRandomSamleNumber) {
            List<List<String>> simpleList = initialOptimize(minLoopNumber, maxLoopNumber, initialScheme, togetherBCList,
                    conformList, normList, onlyNameS, edges, appPositions, eleclection, mutexMap, mutexGroupList, chooseOneList, sortedMapExcel, sortedMap, circuitInfoList);
            System.out.println("实际增加的方案数量：" + simpleList.size());
            if (simpleList != null && simpleList.size() > 0) {
                for (List<String> list : simpleList) {
                    if (!containsList(list, WareHouse) && !containsList(list, simple)) {
                        if (!containsList(list, WareHouseAI) && WareHouseAI.size() < AutoCompleteNumberLimit) {
                            WareHouseAI.add(list);
                        }
                        simple.add(list);
                        WareHouse.add(list);
                    }
                }
            }


            //TODO 自动不全次数稍微有点多，无效补充会导致时间增加，可适当降低，目前是30
            if (completeNumber > AutoCompleteNumber) {
                break;
            }
            completeNumber++;
        }
        long completeEndTime = System.currentTimeMillis();
        System.out.println("方案补充时间：" + (completeEndTime - completeStartTime));

        //查找每一代最优结果耗时
        long topTenStartTime = System.currentTimeMillis();
//        接下来就是对simple 进行一个分支闭环的检查
        List<Map<String, Object>> mapList = changeAndFindBest(simple, edges, normList, wearId, canChangeS, jsonMap, edgeChooseBS);
        System.out.println("查找每一代最优结果耗时：" + (System.currentTimeMillis() - topTenStartTime));
        return mapList;
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
        }
        return newEdges;
    }


    /**
     * @Description: 根据给定的分支打断状况集合（符合要求的） 对他们进行一个分支的闭环检查   修改S  将最终的分支打断情况进行一个计算   返回最优的是个方案
     * @input: simpleList  分支打断情况的集合
     * @input: edges txt中没解析的分支部分
     * @input: normList 分支id的集合
     * @input: wearId  穿孔id
     * @input: canChangeS   可以变s的分支id
     * @input: jsonMap txt内容单纯的转为map
     * @input: edgeChooseBS 分支打断可以选BS的集合
     * @Return: 返回最优的top20方案
     */
    public List<Map<String, Object>> changeAndFindBest(List<List<String>> simpleList,
                                                       List<Map<String, Object>> edges,
                                                       List<String> normList,
                                                       List<String> wearId,
                                                       List<String> canChangeS,
                                                       Map<String, Object> jsonMap,
                                                       List<String> edgeChooseBS) throws Exception {
        FindBest findBest = new FindBest();
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Boolean whetherOnLoop = caseInfo.get("loopcreate").toString().equals("true") ? true : false;

        System.out.println("一共需要计算方案：" + simpleList.size());
        JsonToMap jsonToMap = new JsonToMap();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        ObjectMapper mapper = new ObjectMapper();
//       检查生成的方案是否存在穿腔如果存在 将对应的闭环中   将打断成本最小的分支情况进行一个替换
        System.out.println("每个方案开始加s");
        List<Map<String, Object>> resultList = new ArrayList<>();
        //创建Callable任务列表
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (List<String> strings : simpleList) {
            tasks.add(() -> {
                Map<String, Object> map = new HashMap<>();
//            if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
//               break;
//            }
                List<String> serviceableStatue = strings.stream().collect(Collectors.toList());
                for (int i = 0; i < serviceableStatue.size(); i++) {
                    if (serviceableStatue.get(i).equals("C") && edgeChooseBS.contains(normList.get(i))) {
                        serviceableStatue.set(i, "S");
                    }
                }

                List<Map<String, Object>> serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                Map<String, Object> threadLocalJsonMap = new HashMap<>(jsonMap);
                threadLocalJsonMap.put("edges", serviceableEdge);


                Map<String, Double> breakCostMap = new HashMap<>();
                //节省时间，剔除不必要字段
                String projectCircuitInfoOutputRsult = projectCircuitInfoOutput.projectCircuitInfoOutput(mapper.writeValueAsString(threadLocalJsonMap));
                Map<String, Object> objectMap = jsonToMap.TransJsonToMap(projectCircuitInfoOutputRsult);
                Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMap.get("projectCircuitInfo");

                Map<String, Object> costResultData = new HashMap<>();
                costResultData.put("总成本", projectCircuitInfo.get("总成本"));
                costResultData.put("总长度", projectCircuitInfo.get("回路总长度"));
                costResultData.put("总重量", projectCircuitInfo.get("回路总重量"));


                Map<String, Object> bundeleRelatedCircuitInfo = (Map<String, Object>) objectMap.get("bundeleRelatedCircuitInfo");
                for (String s : bundeleRelatedCircuitInfo.keySet()) {
                    Map<String, Object> edgeMap = (Map<String, Object>) bundeleRelatedCircuitInfo.get(s);
                    Map<String, Object> edgeDetail = (Map<String, Object>) edgeMap.get("circuitInfoIntergation");
                    breakCostMap.put(s, Double.parseDouble(edgeDetail.get("分支打断代价") != null ? edgeDetail.get("分支打断代价").toString() : "0"));
                }
//            对当前的情况进行一个检查   当存在闭环的状况 将当中最打断成本最小的进行打S   直到没有闭环的时候跳出循环
                boolean scrapOrNot = false;
                while (true) {
                    serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                    List<List<String>> recognizeLoopList = recognizeLoopNew(serviceableEdge);
//             每一个闭环中存在一个穿腔的分支的    整组成整个闭环的分支进行记录
                    List<String> recognizeLoopIdList = new ArrayList<>();
                    for (List<String> loop : recognizeLoopList) {
                        for (String s : loop) {
                            if (wearId.contains(s)) {
                                recognizeLoopIdList.addAll(loop);
                                break;
                            }
                        }
                    }

                    //检查当前方案中是否存在寻妖处理的闭环
                    if (recognizeLoopIdList.size() != 0) {
//                 将recognizeLoopIdList 里面分支打断成本最小的打断状况修改为S
                        String minCostKey = null;
                        List<String> keyList = findMinCostKey(recognizeLoopIdList, breakCostMap);
                        for (String s : keyList) {
                            if (canChangeS.contains(s)) {
                                minCostKey = s;
                                break;
                            }
                        }
                        if (minCostKey == null) {
                            scrapOrNot = true;
                            break;
                        }
                        costResultData.put("总成本", (Double) costResultData.get("总成本") + breakCostMap.get(minCostKey));
                        serviceableStatue.set(normList.indexOf(minCostKey), "S");
                    } else {
//                   看是否开启闭环消除
                        if (whetherOnLoop) {
                            while (true) {
                                serviceableEdge = createNewEdges(serviceableStatue, edges, normList);
                                List<List<String>> recognizeLoopListSon = recognizeLoopNew(serviceableEdge);
                                if (recognizeLoopListSon.size() == 0) {
                                    break;
                                } else {
                                    Set<String> son = new HashSet<>();
                                    for (List<String> loop : recognizeLoopListSon) {
                                        son.addAll(loop);
                                    }
                                    List<String> keyList = findMinCostKey(son.stream().collect(Collectors.toList()), breakCostMap);
                                    String minCostKey = null;
                                    for (String s : keyList) {
                                        if (canChangeS.contains(s)) {
                                            minCostKey = s;
                                            break;
                                        }
                                    }
//                                如果当前的方案中没有可以打断的分支，则勾选一个打断代价最小的进行打断
                                    if (minCostKey == null) {
                                        minCostKey = keyList.get(0);
                                    }
                                    costResultData.put("总成本", (Double) costResultData.get("总成本") + breakCostMap.get(minCostKey));
                                    serviceableStatue.set(normList.indexOf(minCostKey), "S");
                                }
                            }
                        }
//                    serviceableList.add(serviceableStatue);
                        map.put("成本", costResultData);
                        map.put("serviceableEdges", serviceableEdge);
                        map.put("serviceableStatue", serviceableStatue);
                        break;
                    }
                }
                //这里先按null返回，因为如果跳出大的循环，则其余方案无法检测到
                if (scrapOrNot) {
                    return null;
                }

                return map;
            });
        }
        //线程池提交任务
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Callable<Map<String, Object>> task : tasks) {
            Future<Map<String, Object>> submit = threadPool.submit(task);
            futures.add(submit);
        }
        //获取线程池结果
        for (Future<Map<String, Object>> future : futures) {
            try {
                Map<String, Object> result = future.get(300, java.util.concurrent.TimeUnit.SECONDS);
                if (result != null) {
                    resultList.add(result);
                }
            } catch (Exception e) {
//                e.printStackTrace();
            }

        }
//        每个方案进行计算
        List<Map<String, Object>> topBeat = findBest.findBest(resultList, "成本", TopNumber);

        for (Map<String, Object> map : topBeat) {
            List<String> list = (List<String>) map.get("serviceableStatue");
            if (!containsList(list, WareHouseTop)) {
                WareHouseTop.add(list);
                TopCostDetail.add(map);
            }
            synchronized (WareHouseAI) {
                if (!containsList(list, WareHouseAI) && WareHouseAI.size() < AutoCompleteNumberLimit) {
                    WareHouseAI.add(list);
                }
            }
        }
        return topBeat;
    }


    /**
     * @Description: 生成随机方案
     * @input: minLoopNumber B的最小个数
     * @input: maxLoopNumber B的最大个数
     * @input: initialScheme 初始方案
     * @input: togetherBCList 组合的B集合
     * @input: conformList  可变的B集合
     * @input: normList  可变的B集合
     * @input: onlyNameS  可变的B集合
     * @input: edges txt中egges部分
     * @input: appPositions  txt中appPositions部分
     * @input: eleclection  用电器对应的位置
     * @input: mutexMap 互斥的情况
     * @input: mutexGroupList 组团互斥的分支
     * @input: chooseOneList 多选一的分支
     * @input: sortedMapExcel 导线选型excel中sheet1中的西悉尼   并且按照导线的商务价格继续进行一个排序
     * @input: sortedMap 分支的打断代价并按照打断代价进行一个升序
     * @input: circuitInfoList 回路信息
     * @Return: 返回随机生成的随机方案
     */
    public List<List<String>> initialOptimize(int minLoopNumber,
                                              int maxLoopNumber,
                                              List<String> initialScheme,
                                              List<List<String>> togetherBCList,
                                              List<String> conformList,
                                              List<String> normList,
                                              List<String> onlyNameS,
                                              List<Map<String, Object>> edges,
                                              List<Map<String, String>> appPositions,
                                              Map<String, String> eleclection,
                                              Map<String, Map<String, List<String>>> mutexMap,
                                              List<List<String>> mutexGroupList,
                                              List<Map<String, List<String>>> chooseOneList,
                                              Map<String, Map<String, String>> sortedMapExcel,
                                              Map<String, Double> sortedMap,
                                              List<Map<String, Object>> circuitInfoList) throws InterruptedException {
        List<List<String>> resultList = Collections.synchronizedList(new ArrayList<>());
        Random random = new Random();
        int totalNumber = 0;
//        最少生成随机方案数量  不然一直循环
        int completeNumber = 0;

        //方案数量必须为100
        while (resultList.size() < LessRandomSamleNumber) {
            //b的数量
            for (int index = minLoopNumber; index <= maxLoopNumber; index++) {
                if (optimizeStopStatusStore.get(optimizeRecordId) == false) {
                    threadPool.terminateNow();
                    return null;
                }
                final int i = index;
                threadPool.execute(() -> {
                    List<String> needChangeBId = new ArrayList<>();
                    List<String> needChangeSId = new ArrayList<>();
                    Set<String> mutexKey = mutexMap.keySet();
//                组团互斥的情况  当一组为B的情况下   另一组也可以为B、C、S
                    for (String s : mutexKey) {
                        Map<String, List<String>> mutexDetail = mutexMap.get(s);
                        Set<String> set = mutexDetail.keySet();
                        Object[] objects = set.toArray();
                        String firststatue = null;
                        for (int j = 0; j < objects.length; j++) {
                            if (j == 0) {
//                           根据产生的随机整数 来决定随机选择一个B或者S   0：B  1：C
                                int randomNumber = random.nextInt(2);
                                if (randomNumber == 0) {
                                    firststatue = "B";
                                    List<String> list1 = mutexDetail.get((String) objects[j]);
                                    for (String s1 : list1) {
//                            是否存在存在组团的情况
                                        Boolean flag = false;
                                        for (List<String> strings : mutexGroupList) {
                                            if (strings.contains(s1)) {
                                                needChangeBId.addAll(strings);
                                                flag = true;
                                            }
                                        }
                                        if (!flag) {
                                            needChangeBId.add(s1);
                                        }
                                    }
                                } else {
                                    firststatue = "C";
                                }
                            }
                            if (j == 1) {
                                if (firststatue.equals("B")) {
                                    int randomNumber = random.nextInt(2);
                                    if (randomNumber == 0) {
                                        List<String> list1 = mutexDetail.get((String) objects[j]);
                                        for (String s1 : list1) {
//                            是否存在存在组团的情况
                                            Boolean flag = false;
                                            for (List<String> strings : mutexGroupList) {
                                                if (strings.contains(s1)) {
                                                    needChangeBId.addAll(strings);
                                                    flag = true;
                                                }
                                            }
                                            if (!flag) {
                                                needChangeBId.add(s1);
                                            }
                                        }
                                    }
                                } else if (firststatue.equals("C")) {
                                    List<String> list1 = mutexDetail.get((String) objects[j]);
                                    for (String s1 : list1) {
//                            是否存在存在组团的情况
                                        Boolean flag = false;
                                        for (List<String> strings : mutexGroupList) {
                                            if (strings.contains(s1)) {
                                                needChangeBId.addAll(strings);
                                                flag = true;
                                            }
                                        }
                                        if (!flag) {
                                            needChangeBId.add(s1);
                                        }
                                    }
                                }

                            }
                        }
                    }
                    List<Map<String, String>> chooseResultList = new ArrayList<>();
//                对三选一的情况进行一个生成
                    for (Map<String, List<String>> listMap : chooseOneList) {
                        Set<String> set = listMap.keySet();
                        while (true) {
                            int numberC = 0;
                            Map<String, String> chooseResult = new HashMap<>();
                            for (String s : set) {
                                List<String> list1 = listMap.get(s);
                                String edgeStatue = list1.get(random.nextInt(list1.size()));
                                chooseResult.put(s, edgeStatue);
                                if (edgeStatue.equals("C")) {
                                    numberC++;
                                }
                            }
                            if (numberC < 2) {
                                chooseResultList.add(chooseResult);
                                break;
                            }

                        }
                    }

                    for (Map<String, String> map : chooseResultList) {
                        for (String s : map.keySet()) {
                            if (map.get(s).equals("B")) {
                                needChangeBId.add(s);
                            }
                            if (map.get(s).equals("S")) {
                                needChangeSId.add(s);
                            }
                        }
                    }

//               组团变化每一个组根据随机生成的数字惊醒选择B 还是C   0为B  1为C
                    for (List<String> list : togetherBCList) {
                        int randomNumber = random.nextInt(2);
                        if (randomNumber == 0) {
                            needChangeBId.addAll(list);
                        }
                    }

                    if (needChangeBId.size() > i) {
                        return;
                    }

                    //这个方法的目的：获取后50%的并集分支列表，这些是打断代价相对较低且低成本导线相关分支，从这些分支中随机选择一部分作为补充B的状态分支
                    Map<String, List<String>> percentage = getPercentage(sortedMapExcel, sortedMap, circuitInfoList, 0.5);
                    List<String> getTopintersection = percentage.get("getLastunion");
                    //进行筛选，只保留同时存在在conformList中的分支,conformList包含了所有可以设置为b状态的分支id集合
                    getTopintersection.retainAll(conformList);

                    //如果经过约束后需要打断的b的数量小于目标打断数量，则从可打断分支集合中随机选择分支进行打断，已达到目标打断数量
                    List<String> list = selectId(getTopintersection, i - needChangeBId.size());
                    needChangeBId.addAll(list);
                    List<String> changeList = initialScheme.stream().collect(Collectors.toList());

                    for (String s : needChangeBId) {
                        int number = normList.indexOf(s);
                        changeList.set(number, "B");
                    }
                    for (String s : needChangeSId) {
                        int number = normList.indexOf(s);
                        changeList.set(number, "S");
                    }
                    for (String s : onlyNameS) {
                        int number = normList.indexOf(s);
                        changeList.set(number, "S");
                    }
                    //新方案，所有的初始的分支，所有分支id集合,重新返回分支详情
                    List<Map<String, Object>> coppysonedges = createNewEdges(changeList, edges, normList);
                    Boolean sonSate = checkFirstOption(normList, changeList, coppysonedges, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);
//               判断生成的list方案是否可行，满足所有约束，  是否在仓库里界面    再判断是否在resultList集合里面
                    if (sonSate) {
                        synchronized (resultList) {
                            if (!containsList(changeList, WareHouse)) {
                                if (!containsList(changeList, resultList)) {
                                    resultList.add(changeList);
                                }
                            }
                        }
                        //样本仓库添加
                        synchronized (WareHouseAI) {
                            if (!containsList(changeList, WareHouseAI)) {
                                WareHouseAI.add(changeList);
                            }
                        }
                    }
                });
            }
            completeNumber++;
            //如果生成的方案大于1000，跳出循环
            //TODO 规定生成的方案数太多，需要优化
            if (completeNumber > InitializeAutoCompleteNumber) {
                break;
            }
        }
        List<List<String>> resultListCopy = resultList.stream().collect(Collectors.toList());
        System.out.println("初始化方案数量：" + resultListCopy.size());
        return resultListCopy;
    }

    /**
     * @Description: List<String> id 在Map < String, Double> breakCostMap中每一个id作为一个key对应的 double最小的一个key
     * @input: ids  id集合
     * @input: breakCostMap   所有分支的打断成本状况
     * @Return: 按照分支打断代价  对id进行一个排序
     */
    public List<String> findMinCostKey(List<String> ids, Map<String, Double> breakCostMap) {
        List<String> validIds = new ArrayList<>();
        for (String id : ids) {
            if (breakCostMap.containsKey(id)) {
                validIds.add(id);
            }
        }
        Collections.sort(validIds, Comparator.comparing(breakCostMap::get));
        return validIds;
    }


    /**
     * @Description: 分支闭环数量检查
     * @input: edges  分支详情
     * @Return: 返回每一个闭环详情
     */
    public List<List<String>> recognizeLoopNew(List<Map<String, Object>> edges) {


        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        //统计B，S状态，打断的分支
        List<List<String>> branchBreakList = new ArrayList<>();
        //获取起点名称和终点名称
        for (Map<String, Object> k : edges) {
            strPointNameList.add((String) k.get("startPointName"));
            endPointNameList.add((String) k.get("endPointName"));
        }
        for (Map<String, Object> edge : edges) {
            if (edge.get("topologyStatusCode").equals("B") || edge.get("topologyStatusCode").equals("S")) {
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
        List<List<Integer>> adj = adjacencyMatrixGraph.getAdj();


        List<List<String>> recognizeLoop = new ArrayList<>();
//        对分支进行循环
        for (Map<String, Object> objectMap : edges) {
            if (objectMap.get("topologyStatusCode").toString().equalsIgnoreCase("B") || objectMap.get("topologyStatusCode").toString().equalsIgnoreCase("S")) {
                continue;
            }
//            获取到分支起点和分支终点
            String startPointName = (String) objectMap.get("startPointName");
            String endPointName = (String) objectMap.get("endPointName");
//            判断分支起点或者分支终点是否存在在allpoint里面  不存在直接跳出当前循环
            if (!allPoint.contains(startPointName) || !allPoint.contains(endPointName)) {
                continue;
            }
            Integer startPointNumber = allPoint.indexOf(startPointName);
            Integer endPointNumber = allPoint.indexOf(endPointName);
//            拷贝一份adj
            List<List<Integer>> copyAdj = new ArrayList<>(adj);
//            删除当前与他有关的分支有关的路劲关系
            copyAdj.get(startPointNumber).remove(endPointNumber);
            copyAdj.get(endPointNumber).remove(startPointNumber);
//            查看删除后的adj的是否存在最短路劲的状况
            FindShortestPath findShortestPath = new FindShortestPath();
            List<Integer> shortestPathBetweenTwoPoint = findShortestPath.findShortestPathBetweenTwoPoint(copyAdj, startPointNumber, endPointNumber);
            if (shortestPathBetweenTwoPoint != null) {
                recognizeLoop.add(findPathLoop(allPoint, shortestPathBetweenTwoPoint, edges));
            }
        }
        return recognizeLoop;
    }

    //    将对应的点数字编号转为名称
    public List<String> findPathLoop(List<String> allPoints, List<Integer> path, List<Map<String, Object>> edges) {
        List<String> list = new ArrayList<>();
        for (Integer integer : path) {
            list.add(allPoints.get(integer));
        }

        Set<String> set = new HashSet<>();

        for (int i = 0; i < list.size(); i++) {
            if (i != list.size() - 1) {
                set.add(list.get(i) + "-" + list.get(i + 1));
                set.add(list.get(i + 1) + "-" + list.get(i));
            } else {
                set.add(list.get(i) + "-" + list.get(0));
                set.add(list.get(0) + "-" + list.get(i));
            }
        }
        List<String> edgeNameList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String startPointName = (String) edge.get("startPointName");
            String endPointName = (String) edge.get("endPointName");
            String name = (String) edge.get("edgeName");
            if (set.contains(name)) {
                String edgeName = edge.get("id").toString();
                edgeNameList.add(edgeName);
            }
        }
        return edgeNameList;
    }


    //    根据提供的list从中随机选取是个id进行返回
    public List<String> selectId(List<String> edgeId, int selectnumber) {
        Set<String> returnSet = new HashSet<>();
        Random random = new Random();
        while (returnSet.size() < selectnumber) {
            int number = random.nextInt(edgeId.size());
            returnSet.add(edgeId.get(number));
        }

        return returnSet.stream().collect(Collectors.toList());
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
    public Boolean checkFirstOption(List<String> normList, List<String> changeList, List<Map<String, Object>> edges,
                                    List<Map<String, String>> appPositions, Map<String, String> eleclection,
                                    Map<String, Map<String, List<String>>> mutexMap,
                                    List<Map<String, List<String>>> chooseOneList,
                                    List<List<String>> togetherBCList) {
//        组团的检查
        for (List<String> list : togetherBCList) {
            String statue = changeList.get(normList.indexOf(list.get(0)));
            for (String s : list) {
                if (!statue.equals(changeList.get(normList.indexOf(s)))) {
                    return false;
                }
            }
        }

//        对多选一的情况进行一个检查   首先检查分支状态是否符合要求   其次再检查C的数量
        for (Map<String, List<String>> map : chooseOneList) {
            int numberC = 0;
            Set<String> set = map.keySet();
            for (String s : set) {
                int i = normList.indexOf(s);
                String s1 = changeList.get(i);
                List<String> list = map.get(s);
                if (!list.contains(s1)) {
                    return false;
                }
                if (s1.equals("C")) {
                    numberC++;
                }

            }
            if (numberC > 1) {
                return false;
            }
        }


        //       对互斥的情况进行一个检查
        Set<String> mutexName = mutexMap.keySet();
        for (String s : mutexName) {
            Map<String, List<String>> listMap = mutexMap.get(s);
            Set<String> sonset = listMap.keySet();
            int cycleNumber = 1;
            String statue = null;
            for (String edgeId : sonset) {
                List<String> list = listMap.get(edgeId);
                if (cycleNumber == 1) {
                    statue = changeList.get(normList.indexOf(list.get(0)));
                    if (statue.equals("B")) {
                        for (String topologyStatusCode : list) {
                            if (!changeList.get(normList.indexOf(topologyStatusCode)).equals("B")) {
                                return false;
                            }
                        }
                    } else {
                        for (String topologyStatusCode : list) {
                            if (!(changeList.get(normList.indexOf(topologyStatusCode)).equals("C") || changeList.get(normList.indexOf(topologyStatusCode)).equals("S"))) {
                                return false;
                            }
                        }
                    }
                } else {
                    if (statue.equals("B")) {
                        for (String topologyStatusCode : list) {
                            if (!(changeList.get(normList.indexOf(topologyStatusCode)).equals("C") || changeList.get(normList.indexOf(topologyStatusCode)).equals("S") || changeList.get(normList.indexOf(topologyStatusCode)).equals("B"))) {
                                return false;
                            }
                        }
                    } else {
                        for (String topologyStatusCode : list) {
                            if (!changeList.get(normList.indexOf(topologyStatusCode)).equals("B")) {
                                return false;
                            }
                        }
                    }
                }
                cycleNumber++;
            }
        }


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
            return false;
        }

        Boolean edgesFlag = true;

        for (Map<String, String> appPosition : appPositions) {
            if (!appPosition.get("appName").startsWith("[")) {
                String pointName = eleclection.get(appPosition.get("appName"));
                if (!checkElecEdge(pointName, edges)) {
                    return false;
                }
            }
        }

        if (breakRec.size() == 1 && edgesFlag) {
            return true;
        }
        return false;
    }


    /**
     * @Description: 对生成的方案进行一个检查：  1、回路是否导通 2、用电器周围是否至少存在一个分支
     * @input: edges  生成需要检查的分支
     * @input: appPositions 没有解析txt中的用电器像信息
     * @input: eleclection  用电器对应的位置
     * @input: mutexMap   互斥的情况集合
     * @Return: 根据给定的方案检查   返回是否符合的状态
     */
    public Boolean checkFirstOption(List<Map<String, Object>> edges, List<Map<String, String>> appPositions, Map<String, String> eleclection) {


        List<String> strPointNameList = new ArrayList<>();
        List<String> endPointNameList = new ArrayList<>();
        Integer number = 0;
        for (Map<String, Object> k : edges) {
            if (k.get("topologyStatusCode").toString().equals("B")) {
                number++;
            }
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
        //如果size大于1，说明打断导致拓扑图分成了多个族群，说明存在断点，优化算法会拒绝这个方案，只能保留保持拓扑联通的
        if (breakRec.size() > 1) {
            return false;
        }

        Boolean edgesFlag = true;

        for (Map<String, String> appPosition : appPositions) {
            if (!appPosition.get("appName").startsWith("[")) {
                String pointName = eleclection.get(appPosition.get("appName"));
                if (!checkElecEdge(pointName, edges)) {
                    return false;
                }
            }
        }

        if (breakRec.size() == 1 && edgesFlag) {
            return true;
        }
        return false;
    }

    /**
     * @Description: 获取百分比数据
     * @input: sortedMapExcel   导线选型的excel中导线价格从高到低排序
     * @input: sortedMap   最理想案下  分支的打断代价
     * @input: circuitInfoList  最理想方案下 计算完的回路信息
     * @input: number 百分比  给我用小数展示 0.5 -》百分之五十
     * @Return: 返回前百分之多少的交集 并集分支 后百分之多少的交集 并集分支
     */
    public Map<String, List<String>> getPercentage(Map<String, Map<String, String>> sortedMapExcel,
                                                   Map<String, Double> sortedMap,
                                                   List<Map<String, Object>> circuitInfoList,
                                                   double number) {
        Map<String, List<String>> result = new HashMap<>();
//       后百分之几
        List<String> getLastKeys = getLast25Keys(sortedMap, 1 - number);
        List<String> getLastKeysExcel = getLast25KeysExcel(sortedMapExcel, 1 - number);
        Set<String> getLastedgeId = new HashSet<>();
        for (Map<String, Object> map : circuitInfoList) {
            if (getLastKeysExcel.contains(map.get("导线选型").toString())) {
                List<String> list = (List<String>) map.get("回路所有分支id");
                for (String s : list) {
                    if (s.length() > 0) {
                        getLastedgeId.add(s);
                    }
                }
            }
        }
        Set<String> getLastintersection = new HashSet<>(getLastKeys);
        getLastintersection.retainAll(getLastedgeId);
        Set<String> getLastunion = new HashSet<>(getLastKeys);
        getLastunion.addAll(getLastedgeId);
//        前百分之几
        List<String> getTopKeys = getTop25Keys(sortedMap, number);
        List<String> getTopKeysExcel = getTop25KeysExcel(sortedMapExcel, number);
        Set<String> getTopedgeId = new HashSet<>();
        for (Map<String, Object> map : circuitInfoList) {
            if (getTopKeysExcel.contains(map.get("导线选型").toString())) {
                List<String> list = (List<String>) map.get("回路所有分支id");
                for (String s : list) {
                    if (s.length() > 0) {
                        getTopedgeId.add(s);
                    }
                }
            }
        }
        Set<String> getTop25intersection = new HashSet<>(getTopKeys);
        getTop25intersection.retainAll(getTopedgeId);
        Set<String> getTopunion = new HashSet<>(getTopKeys);
        getTopunion.addAll(getTopedgeId);
        //后百分之number的交集，并集，以及前百分之多少的交集，并集
        result.put("getLastintersection", new ArrayList<>(getLastintersection));
        result.put("getLastunion", new ArrayList<>(getLastunion));
        result.put("getTop25intersection", new ArrayList<>(getTop25intersection));
        result.put("getTopunion", new ArrayList<>(getTopunion));
        return result;
    }


    /**
     * @Description: 获取  最理想案下  分支的打断代价从高到低排序前百分之多少的分支id
     * @input: sortedMap  最理想案下  分支的打断代价
     * @input: number 百分比  给我用小数展示 0.5 -》百分之五十
     * @Return: 返回理想案下  分支的打断代价从高到低排序前百分之多少的分支id
     */
    public List<String> getTop25KeysExcel(Map<String, Map<String, String>> sortedMap, Double number) {
        int size = sortedMap.size();
        int top25Size = (int) Math.ceil(size * number);

        List<String> keysList = new ArrayList<>(sortedMap.keySet());

        List<String> top25Keys = new ArrayList<>();
        for (int i = 0; i < top25Size; i++) {
            top25Keys.add(keysList.get(i));
        }
        return top25Keys;
    }

    /**
     * @Description: 获取 导线选型的excel中导线价格从高到低排序前百分之多少的分支id
     * @input: sortedMap 导线选型的excel中导线价格从高到低排序
     * @input: number 百分比  给我用小数展示 0.5 -》百分之五十
     * @Return: 返回导线选型的excel中导线价格从高到低排序前百分之多少的分支id
     */
    public List<String> getTop25Keys(Map<String, Double> sortedMap, Double number) {
        int size = sortedMap.size();
        int top25Size = (int) Math.ceil(size * number);

        List<Map.Entry<String, Double>> entryList = new ArrayList<>(sortedMap.entrySet());

        List<String> top25Keys = new ArrayList<>();
        for (int i = 0; i < top25Size; i++) {
            Map.Entry<String, Double> entry = entryList.get(i);
            top25Keys.add(entry.getKey());
        }

        return top25Keys;
    }

    /**
     * @Description: 获取  最理想案下  分支的打断代价从高到低排序后百分之多少的分支id
     * @input: sortedMap  最理想案下  分支的打断代价
     * @input: number 百分比  给我用小数展示 0.5 -》百分之五十
     * @Return: 返回理想案下  分支的打断代价从高到低排序后百分之多少的分支id
     */
    public List<String> getLast25KeysExcel(Map<String, Map<String, String>> sortedMap, Double number) {
        int size = sortedMap.size();
        int top25Size = (int) Math.ceil(size * number);
        List<String> keysList = new ArrayList<>(sortedMap.keySet());
        List<String> top25Keys = new ArrayList<>();
        for (int i = top25Size; i < size; i++) {
            top25Keys.add(keysList.get(i));
        }

        return top25Keys;
    }

    /**
     * @Description: 获取 导线选型的excel中导线价格从高到低排序后百分之多少的分支id
     * @input: sortedMap 导线选型的excel中导线价格从高到低排序
     * @input: number 百分比  给我用小数展示 0.5 -》百分之五十
     * @Return: 返回导线选型的excel中导线价格从高到低排序后百分之多少的分支id
     */
    public List<String> getLast25Keys(Map<String, Double> sortedMap, Double number) {
        int size = sortedMap.size();
        int top25Size = (int) Math.ceil(size * number);

        List<Map.Entry<String, Double>> entryList = new ArrayList<>(sortedMap.entrySet());

        List<String> top25Keys = new ArrayList<>();
        for (int i = top25Size; i < size; i++) {
            Map.Entry<String, Double> entry = entryList.get(i);
            top25Keys.add(entry.getKey());
        }

        return top25Keys;
    }

    /**
     * @Description: 在理想条件下 按照打断代价从高到低排序
     * @input: originalMap 理想条件下 按照打断代价
     * @Return: 按照从高到低排序后的map
     */
    public Map<String, Double> sortMapByDoubleValue(Map<String, Double> originalMap) {
        // 将Map的键值对转换为List
        List<Map.Entry<String, Double>> entryList = new ArrayList<>(originalMap.entrySet());

        // 对List进行排序，按照Double值从小到大排序
        entryList.sort((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()));

        // 将排序后的List转换回Map，并保持插入顺序（使用LinkedHashMap）
        return entryList.stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                ));
    }

    /**
     * @Description: 在理想条件下 按照打断代价从高到低排序
     * @input: originalMap 理想条件下 按照打断代价
     * @Return: 按照从高到低排序后的map
     */
    public Map<String, Map<String, String>> sortMapByInnerCostValue(Map<String, Map<String, String>> originalMap) {
        List<Map.Entry<String, Map<String, String>>> entryList = new ArrayList<>(originalMap.entrySet());

        entryList.sort((entry1, entry2) -> {
            String costValue1 = entry1.getValue().get("导线单位商务价（元/米）");
            String costValue2 = entry2.getValue().get("导线单位商务价（元/米）");
            return Double.compare(Double.parseDouble(costValue2), Double.parseDouble(costValue1));
        });

        return entryList.stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                ));
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
//        System.out.println("从txt中读取到的用电器，经过位置判断后共计"+resultList.size()+"个");
        return resultMap;
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


    /**
     * @Description: 判断targetList 是否在 listOfLists中
     * @input: targetList
     * @input: listOfLists
     * @Return:
     */
    public boolean containsList(List<String> targetList, List<List<String>> listOfLists) {
        for (List<String> list : listOfLists) {
            if (list.equals(targetList)) {
                return true;
            }
        }
        return false;
    }


    /**
     * @Description 获取最优的几条方案（首先找出这些回路中的 回路总成本、回路总量、回路长度的最大值和最小值 然后按照 （总成本-总成本最小值）/（总成本最大值-总成本最大值）+（回路长度-回路长度最小值）/（回路长度最大值-回路长度最大值）+（回路重量-回路重量最小值）/（回路重量最大值-回路重量最大值） 其值最小的一条回路 ）
     * @input radomList:所有路径信息
     * @input name:所有路径信息
     * @Return 最优的几条方案
     */
    public List<Map<String, Object>> findBest(List<Map<String, Object>> radomList, String name) {
//       找出当中的最大值最小值
        double minCost = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总成本").toString());
        double maxCost = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总成本").toString());
        double minWeight = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总重量").toString());
        double maxWeight = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总重量").toString());
        double minLength = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总长度").toString());
        double maxLength = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总长度").toString());
//        首先最大最小值
        for (Map<String, Object> map : radomList) {
            if (minCost > Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString())) {
                minCost = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString());
            }
            if (maxCost < Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString())) {
                maxCost = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString());
            }
            if (minWeight > Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString())) {
                minWeight = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString());
            }
            if (maxWeight < Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString())) {
                maxWeight = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString());
            }

            if (minLength > Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString())) {
                minLength = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString());
            }
            if (maxLength < Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString())) {
                maxLength = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString());
            }
        }
//        对每一个list 添加一个评分
        for (Map<String, Object> map : radomList) {
            double allCost = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString());
            double weight = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString());
            double length = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString());
            double score = (allCost - minCost) / ((maxCost - minCost) + 0.0001) * 0.98 + (weight - minWeight) / ((maxWeight - minWeight) + 0.0001) * 0.01 + (length - minLength) / ((maxLength - minLength) + 0.0001) * 0.01;
            map.put("score", score);
        }
        List<Map<String, Object>> score = findTopTenMinDoubleMaps(radomList, "score");
        for (Map<String, Object> objectMap : score) {
            objectMap.remove("score");
        }
        return score;
    }

    /**
     * @Description: 找出分数前几的数据
     * @input: maps   需要筛选的数据
     * @input: key   以什么字段进行筛选
     * @Return: 返回前几的数据
     */
    public List<Map<String, Object>> findTopTenMinDoubleMaps(List<Map<String, Object>> maps, String key) {
        return maps.stream()
                .sorted((m1, m2) -> {
                    Double value1 = getDoubleValue(m1, key);
                    Double value2 = getDoubleValue(m2, key);
                    return value1.compareTo(value2);
                }).collect(Collectors.toList());
    }

    /**
     * @Description: 从给定的 Map<String, Object> 中获取指定键对应的值 并将其转换为 Double 类型
     * @input: map   需要获取的数据
     * @input: key   以什么字段进行筛选
     * @Return: 返回double类型的值
     */
    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Double) {
            return (Double) value;
        } else {
            return Double.MAX_VALUE;
        }
    }


}

