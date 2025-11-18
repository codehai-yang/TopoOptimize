package HarnessPackOpti.CircuitInfoCalculate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CalculateInlineWet {
    /**
     * @Description: 根据excel表格中导线选型获取湿区成本补偿——连接器塑壳（元/端） 两端全为w为湿  否则就是干
     * @input: id 分支id
     * @inputExample:  [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return:  Map<String,String>  id-> inline干湿   [{199eecf0-3320-4b2a-86e6-036442fdc317:"W"}]
     */
    public Map<String ,String> calculateInlineWet(List<String> branchName, Map<String, Object> map) {
       Map<String,String> stringMap=new HashMap<>();

        for (String s : branchName) {
            List<Map<String, String>> edgeList = (List<Map<String, String>>) map.get("所有分支信息");
            for (Map<String, String> stringStringMap : edgeList) {
                if (s.equals(stringStringMap.get("分支名称"))){
                    String startPointName =  stringStringMap.get("分支起点名称");
                    String endPointName =  stringStringMap.get("分支终点名称");
                    String startPoint = getWaterParam(startPointName, (List<Map<String, String>>) map.get("所有端点信息"));
                    String endPoint = getWaterParam(endPointName, (List<Map<String, String>>) map.get("所有端点信息"));
                    if ("D".equals(startPoint)){
                        stringMap.put(s,"D");
                        continue;
                    }else {
                        if ("D".equals(endPoint)){
                            stringMap.put(s,"D");
                            continue;
                        }else {
                            stringMap.put(s,"W");
                            continue;
                        }
                    }
                }
            }
        }
        return stringMap;
    }


    public static String getWaterParam(String name,List<Map<String,String>> maps){
        for (Map<String, String> map : maps) {
            if (name.equals(map.get("端点名称"))){
                return map.get("端点干湿");
            }
        }
        return null;
    }
}
