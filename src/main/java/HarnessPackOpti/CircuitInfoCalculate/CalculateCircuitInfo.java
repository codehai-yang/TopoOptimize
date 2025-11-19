package HarnessPackOpti.CircuitInfoCalculate;

import HarnessPackOpti.Algorithm.FindBranchByNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class CalculateCircuitInfo {
    /**
     * @Description: 计算回路信息
     * @input: wireType 导线选型
     * @inputExample: USB Core 0.22
     * @input: pathNode 途径点名称
     * @inputExample: {前围板外中点，前舱左纵梁后点}
     * @input: map  解析完成的txt文件
     * @Return: 当前回路信息：长度、重量等一系列信息{回路重量=3.7390186666666665, 回路分支打断清单=[d89d0217-8ef0-41f2-835c-49ca8f7e069c], inline湿区防水塞成本补偿=0.0, 所有分支=[199eecf0-3320-4b2a-86e6-036442fdc317, a6300929-2419-4a1f-b257-7c68d9a3e3c1,8a464b8f-d481-465a-a7ce-f320d548d83e, d89d0217-8ef0-41f2-835c-49ca8f7e069c, 1710d6e7-d788-4bdb-adb8-10cfc0160f26, 263afced-fd0d-4ddc-9bfe-355643f80812, 674c6947-31a3-4879-9f73-5db9f7b7b43e, 027dc9a1-bb19-425f-aa16-55a6c26bdb62, 0577f318-7a61-4e13-82fb-4ddd7bbf8167], 外径=2.1, 回路打断成本=0.2, 回路导线成本=42.765026, 回路长度=7.01066, inline湿区连接器成本补偿=0.0}
     */

    public Map<String, Object> calculateCircuitInfo(String wireType, List<String> pathNode, Map<String, Object> map,Map<String, Map<String, String>> elecFixedLocationLibrary) {
        Map<String, Object> resultMap = new HashMap<>();
        List<Map<String, String>> edges = (List<Map<String, String>>) map.get("所有分支信息");
//      读取线径excel文件
        Map<String, String> materialsMsg = elecFixedLocationLibrary.get(wireType);
//        根据路径获取途径点获取分支
        FindBranchByNode findBranchByNode = new FindBranchByNode();
        Map<String, Object> MapbranchByNode = findBranchByNode.findBranchByNode(pathNode, edges);
        List<String> edgeIdList = (List<String>)MapbranchByNode.get("idList");
        List<String> edgeNameList = (List<String>)MapbranchByNode.get("nameList");
//        获取回路长度
        CalculatePathLength calculatePathLength = new CalculatePathLength();
        //回路长度
        Map<String, Object> pathLength = calculatePathLength.calculatePathLength(edgeIdList, map);
        double length = (Double) pathLength.get("长度")+200;
//        回路重量
        CalculatePathWireWeight calculatePathWireWeight = new CalculatePathWireWeight();
        double weight = calculatePathWireWeight.calculatePathWireWeight(wireType, length / 1000,elecFixedLocationLibrary);
//        导线成本
        CalculatePathWireCost calculatePathWireCost = new CalculatePathWireCost();
        double cost = calculatePathWireCost.calculatePathWireCost(wireType, length / 1000,elecFixedLocationLibrary);
//        回路打断次数
        CalculatePathBreakNumber calculatePathBreakNumber = new CalculatePathBreakNumber();
        Map<String, Object> objectMap = calculatePathBreakNumber.calculatePathBreakNumber(edgeIdList, map);
//        分支打断id
        List<String> topologyStatusCodeIdList = (List<String>) objectMap.get("idList");
//        分支打断名称
        List<String> topologyStatusCodeNameList = (List<String>) objectMap.get("nameList");

        Integer breakNumber = topologyStatusCodeIdList.size();
//        回路打断成本
        double breakPrice = Double.parseDouble(materialsMsg.get("导线打断成本（元/次）"));
        double breakCost = breakNumber * breakPrice;
//        回路湿区个数
        CalculateInlineWet calculateInlineWet = new CalculateInlineWet();
        Map<String, String> inlineWet = calculateInlineWet.calculateInlineWet(topologyStatusCodeNameList, map);
        int count = 0;
        for (Object mapValue : inlineWet.values()) {
            if ("w".toUpperCase().equals(mapValue.toString())) {
                count++;
            }
        }
//        回路湿区成本
        double connectPrice = Double.parseDouble(materialsMsg.get("湿区成本补偿——连接器塑壳（元/端）"));
        double defensePrice = Double.parseDouble(materialsMsg.get("湿区成本补偿——防水赛（元/个）"));
        double connectCost = count * connectPrice * 2;
        double defenseCost = count * defensePrice * 2;
//        外径
        double diameter = Double.parseDouble(materialsMsg.get("导线外径（毫米）"));
        resultMap.put("回路长度", length / 1000);
        resultMap.put("回路重量", weight);
        resultMap.put("回路打断成本", breakCost);
        resultMap.put("inline湿区连接器成本补偿", connectCost);
        resultMap.put("inline湿区防水塞成本补偿", defenseCost);
        resultMap.put("外径", diameter);
        resultMap.put("回路分支打断清单", topologyStatusCodeIdList);
        resultMap.put("回路分支打断清单名称", topologyStatusCodeNameList);
        resultMap.put("所有分支", edgeIdList);
        resultMap.put("所有分支名称", edgeNameList);
        resultMap.put("回路导线成本", cost);
        return resultMap;
    }
}