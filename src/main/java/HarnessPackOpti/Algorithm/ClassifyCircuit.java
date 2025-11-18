package HarnessPackOpti.Algorithm;

import org.apache.commons.collections4.map.LinkedMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ClassifyCircuit {
    /**
    * @Description  对所有回路进行分类 分为两点回路 和 多点回路
    * @input  projectInfo 解析完成的整车信息
    * @Return  Map<String,Object> twoPoint->回路信息  multiLoopInfos-> 族群->回路信息
    */

    public Map<String, Object> classifyCircuit(Map<String, Object> projectInfo) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) projectInfo.get("回路用电器信息");
//        单个回路的信息
        List<Map<String, String>> twoPoint = new ArrayList<>();
//        多个回路的信息
        Map<String, List<Map<String,String>>> multiLoopInfos = new LinkedMap<>();
        for (Map<String, String> loopInfo : loopInfos) {
            if (loopInfo.get("回路起点用电器").startsWith("[") || loopInfo.get("回路终点用电器").startsWith("[")) {
                if (loopInfo.get("回路起点用电器").startsWith("[")) {
                    if (multiLoopInfos.containsKey(loopInfo.get("回路起点用电器"))) {
                        multiLoopInfos.get(loopInfo.get("回路起点用电器")).add(loopInfo);
                    } else {
                        List<Map<String,String>> list=new ArrayList<>();
                        list.add(loopInfo);
                        multiLoopInfos.put(loopInfo.get("回路起点用电器"), list);
                    }
                }
                if (loopInfo.get("回路终点用电器").startsWith("[")) {
                    if (multiLoopInfos.containsKey(loopInfo.get("回路终点用电器"))) {
                        multiLoopInfos.get(loopInfo.get("回路终点用电器")).add(loopInfo);
                    } else {
                        List<Map<String,String>> list=new ArrayList<>();
                        list.add(loopInfo);
                        multiLoopInfos.put(loopInfo.get("回路终点用电器"), list);
                    }
                }
            } else {
                twoPoint.add(loopInfo);
            }
        }
        map.put("twoPoint", twoPoint);
        map.put("multiLoopInfos", multiLoopInfos);
        return map;
    }

}
