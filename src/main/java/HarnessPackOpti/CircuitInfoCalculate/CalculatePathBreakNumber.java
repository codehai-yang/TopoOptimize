package HarnessPackOpti.CircuitInfoCalculate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CalculatePathBreakNumber {
    /**
     * @Description: 分支打断的状况   分支打断为S为打断
     * @input: id 分支id
     * @inputExample:  [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return:   List<String> 打断的分支id   {199eecf0-3320-4b2a-86e6-036442fdc317，199eecf0-3320-4b2a-86e6-036442fdc317}
     */
    public  Map<String, Object> calculatePathBreakNumber(List<String> id, Map<String, Object> map) {

        Map<String, Object> resultMap = new HashMap<>();
//        id
        List<String> idList = new ArrayList<>();
//        name
        List<String> nameList = new ArrayList<>();
        for (String s : id) {
            List<Map<String, String>> edgeList = (List<Map<String, String>>) map.get("所有分支信息");
            for (Map<String, String> stringMap : edgeList) {
                if (s.equals(stringMap.get("分支id编号"))) {
                    String topologyStatusCode = stringMap.get("分支打断");
                    if ("S".toUpperCase().equals(topologyStatusCode)) {
                        idList.add(stringMap.get("分支id编号"));
                        nameList.add(stringMap.get("分支名称"));
                    }
                }
            }
        }

        resultMap.put("idList", idList);
        resultMap.put("nameList", nameList);

        return resultMap;
    }
}
