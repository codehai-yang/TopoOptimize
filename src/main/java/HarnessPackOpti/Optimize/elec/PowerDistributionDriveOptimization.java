package HarnessPackOpti.Optimize.elec;

import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.OptimizeStopStatusStore;
import HarnessPackOpti.ProjectInfoOutPut.PowerProjectCircuitInfoOutput;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.ls.LSInput;

import java.util.*;

/**
 * 配电驱动优化
 */
public class PowerDistributionDriveOptimization {

    //    当前方案的id
    private static String CaseId = null;
    private static String optimizeRecordId = null;

    private final OptimizeStopStatusStore optimizeStopStatusStore;

    public PowerDistributionDriveOptimization() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance(); // 使用Store的单例实例
    }
    public String powerDriverOptimize(String jsonContent) throws Exception{
        long categoryTime = System.currentTimeMillis();
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        PowerProjectCircuitInfoOutput powerProjectCircuitInfoOutput = new PowerProjectCircuitInfoOutput();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, Object>> points = (List<Map<String, Object>>) jsonMap.get("points");
        Map<String, String> projectInfo = (Map<String, String>) jsonMap.get("projectInfo");
        CaseId = caseInfo.get("id").toString();
        optimizeRecordId = optimizeRecord.get("id").toString();
        optimizeStopStatusStore.setKey(optimizeRecordId);

        //整车信息计算(初始方案)
        String initializeCaseResult = powerProjectCircuitInfoOutput.powerOptimize(jsonContent);
        //判断是哪种类型优化
        String optimizeType = projectInfo.get("optimizeType");
        //是否开启直连接口
        Boolean whetherToChange = projectInfo.get("whetherToChange") == null || projectInfo.get("whetherToChange").equals("false") ? false : true;
        //主供电回路和配电回路
        List<Map<String,String>> elecLoopList = new ArrayList<>();
        //驱动回路
        List<Map<String,String>> driveLoopList = new ArrayList<>();
        //资源数量读取
        Map<String, List<String>> resourceNum = new HashMap<>();
        //组团一起
        Map<String, List<String>> togetherGroup = new HashMap<>();
        //互斥
        Map<String, List<String>> mutualGroup = new HashMap<>();
        //用电器id-名称
        Map<String,String> elecNameId = new HashMap<>();

        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            strPointName.add(edge.get("分支起点名称").toString());
            endPointName.add(edge.get("分支终点名称").toString());
            if (edge.get("分支打断").equals("B")) {
                List<String> interruptedEdgelist = new ArrayList<>();
                interruptedEdgelist.add(edge.get("分支起点名称").toString());
                interruptedEdgelist.add(edge.get("分支终点名称").toString());
                branchBreakList.add(interruptedEdgelist);
            }
        }
        //获取有向图之间的索引，起点到终点之间的关系
        GenerateTopoMatrix adjacencyMatrixGraph = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);//获取邻接矩阵基本信息
        adjacencyMatrixGraph.adjacencyMatrix();//构建邻接矩阵列表及数组
        adjacencyMatrixGraph.addEdge();//为邻接矩阵添加”边“元素
        adjacencyMatrixGraph.getAdj();
        List<String> allPoint = adjacencyMatrixGraph.getAllPoint();

        //统计用电器可变位置点
        Map<String, List<String>> elecChangeablePosition = new HashMap<>();
        for (Map<String, String> appPosition : appPositions) {
            String appName = appPosition.get("appName");
            if(resourceNum.get(appName) == null){
                List<String> list = objectMapper.readValue(appPosition.get("resourceNumb"), new TypeReference<List<String>>(){});
                resourceNum.put(appName,list);
            }

            if (appPosition.get("changeType") != null && appPosition.get("changeType").toString().equals("1")) {
                List<String> list = new ArrayList<>();
                if (appPosition.get("specifyPoints") != null && !appPosition.get("specifyPoints").toString().isEmpty()) {
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
                elecChangeablePosition.put(appName, allPoint);
            }
            elecNameId.put(appPosition.get("appId"),appName);

        }

        for (Map<String, String> loopInfo : loopInfos) {
            if("主供电回路".equals(loopInfo.get("loopAttribute")) || "配电回路".equals(loopInfo.get("loopAttribute"))){
                elecLoopList.add(loopInfo);
            }else if("驱动回路".equals(loopInfo.get("loopAttribute"))){
                driveLoopList.add(loopInfo);
            }

            //组团查找
            if(loopInfo.get("changeTogether") != null && !loopInfo.get("changeTogether").isEmpty()){
                if(togetherGroup.get(loopInfo.get("changeTogether")) == null){
                    List<String> ids = new ArrayList<>();
                    ids.add(loopInfo.get("id"));
                    togetherGroup.put(loopInfo.get("changeTogether"),ids);
                }else {
                    togetherGroup.get(loopInfo.get("changeTogether")).add(loopInfo.get("id"));
                }
            }
            //互斥查找
            if(loopInfo.get("mutualExclusion") != null && !loopInfo.get("mutualExclusion").isEmpty()){
                if(mutualGroup.get(loopInfo.get("mutualExclusion")) == null){
                    List<String> ids = new ArrayList<>();
                    ids.add(loopInfo.get("id"));
                    mutualGroup.put(loopInfo.get("mutualExclusion"),ids);
                }else {
                    mutualGroup.get(loopInfo.get("mutualExclusion")).add(loopInfo.get("id"));
                }
            }
        }


        //在points 找出所有可能发生变化点   并且将同一组的放在一起
        Map<String, List<String>> interfaceCodegroup = new HashMap<>();
        Set<String> pointNameSet = new HashSet<>();
        if (whetherToChange) {
            for (Map<String, Object> point : points) {
                if (point.get("interfaceCode") != null && !point.get("interfaceCode").toString().trim().isEmpty()) {
                    String interfaceCode = point.get("interfaceCode").toString();
                    String pointName = point.get("pointName").toString();
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
        System.out.println("回路分类耗时:" + (System.currentTimeMillis() - categoryTime));

        //判断是否采用枚举，计算该方案存在的可能性
        if("3".equals(optimizeType)){
            //统计每根回路可变数量，后续会用来计算方案总的排列组合数量
            List<Integer> changeNumb = new ArrayList<>();
            for (Map<String, String> loopInfo : loopInfos) {
                //判断起点和终点是不是可变用电器，判断这条回路可连接多少终点，判断组团
                String startApp = loopInfo.get("startApp");
                String endApp = loopInfo.get("endApp");
                List<String> startAppList = elecChangeablePosition.get(startApp);
                List<String> endAppList = elecChangeablePosition.get(endApp);
                //拿到回路可连接的用电器
                String specifyPoints = loopInfo.get("specifyPoints");
                String[] split = specifyPoints.split(",");
                //判断回路有没有约束
                String changeTogether = loopInfo.get("changeTogether");
                String mutualExclusion = loopInfo.get("mutualExclusion");
                //如果没有组团和互斥约束，直接组合数量
                if ((changeTogether == null && changeTogether.isEmpty()) && (mutualExclusion == null && mutualExclusion.isEmpty())) {
                    changeNumb.add(startAppList.size() * endAppList.size());
                    if(split.length != 0){
                        for (int i = 0; i < split.length; i++) {
                            //获取每个用电器可变位置的数量
                            String elecId = split[i];
                            String elecName = elecNameId.get(elecId);
                            List<String> strings = elecChangeablePosition.get(elecName);
                            changeNumb.add(strings.size());
                        }
                    }
                    continue;
                }
                //计算组团与互斥的情况
                if((changeTogether != null && !changeTogether.isEmpty()) && (mutualExclusion != null && !mutualExclusion.isEmpty())){
                    //取组团一起变的交集
                    List<String> together = togetherGroup.get(changeTogether);
                    //获取终点用电器可变位置(交集)
                    List<String> original = elecChangeablePosition.get(endApp);
                    List<String> copyOriginal = new ArrayList<>(original);
                    //取每个用电器可变位置
                    for (String s : together) {
                        //每个用电器对应的可变位置
                        if(s.equals(loopInfo.get("id"))){
                            continue;
                        }
                        copyOriginal.retainAll(elecChangeablePosition.get(elecNameId.get(s)));
                    }
                    //对存在的互斥组进行计算
                    List<String> mutual = mutualGroup.get(mutualExclusion);
                    List<Integer> mutualNumb = new ArrayList<>();
                    //互斥与组团一起的交集
                    List<String> intersection = new ArrayList<>(original);
                    //总互斥数量
                    int totalMutallNumb = 1;
                    for (String s : mutual) {
                        if(s.equals(loopInfo.get("id"))){
                            continue;
                        }
                        List<String> strings = elecChangeablePosition.get(elecNameId.get(s));
                        List<String> copyMutual = new ArrayList<>(original);
                        copyMutual.retainAll(strings);
                        intersection.retainAll(copyMutual);
                        mutualNumb.add(copyMutual.size());
                        totalMutallNumb *= strings.size();
                    }
                }
            }

        }
        return null;
    }

    /**
     * @Description: 根据位置id获取对应的位置点名称
     * @input: id  位置id
     * @input: appPositions  用电器位置信息
     * @Return: 返回接收到用电器id对应的位置名称
     */
    public String findNameById(String id, List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if (point.get("id").toString().equals(id)) {
                return point.get("pointName").toString();
            }
        }
        return "";
    }
}
