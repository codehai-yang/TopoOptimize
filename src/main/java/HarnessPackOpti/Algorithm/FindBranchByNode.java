package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FindBranchByNode {
   /**
   * @Description  根据途径点找到所有的分支  返回分支id
   * @input   node 途径点名称
   * @inputExample   [前围板外中点,前围板外中点]
    * @input  edges 所有的分支信息
    * @Return 所有分支id [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
   */

    public Map<String, Object> findBranchByNode(List<String> node,List<Map<String, String>> edges){


        Map<String, Object> resultMap = new HashMap<>();

        List<String> idList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
//        可能存在只有一个点的情况   这种情况分支id就显示为""
        if (node.size()>1){
            for (int i = 0; i < node.size()-1 ; i++) {
                String start = node.get(i);
                String end = node.get(i + 1);
                for (Map<String, String> edge : edges) {
                    if ((edge.get("分支起点名称").equals(start) && edge.get("分支终点名称").equals(end)) || (edge.get("分支终点名称").equals(start) && edge.get("分支起点名称").equals(end))) {
                        idList.add(edge.get("分支id编号").toString());
                        nameList.add(edge.get("分支名称").toString());
                    }
                }
            }
        }else {
            idList.add("");
            nameList.add("");
        }


        resultMap.put("idList", idList);
        resultMap.put("nameList", nameList);
        return resultMap;
    }
}
