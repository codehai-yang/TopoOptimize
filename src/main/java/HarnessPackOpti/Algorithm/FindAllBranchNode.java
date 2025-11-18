package HarnessPackOpti.Algorithm;

import java.util.*;

public class FindAllBranchNode {
    //   读取ReadCircuitInfo生成的map中 分支起点与终点名称，放入一个List清单中，并去重
    public List<String> getAllBranchNode(Map<String,Object> circuitInfo)  {
        List<Map<String, Object>> maps =(  List<Map<String, Object>>)circuitInfo.get("所有分支信息");
        Set<String> mySet = new HashSet<>();
        for (Map<String, Object> map : maps) {
            mySet.add(map.get("分支起点名称").toString());
            mySet.add(map.get("分支终点名称").toString());
        }
        List<String> myList = new ArrayList<>(mySet);
        return myList;
    }
}
