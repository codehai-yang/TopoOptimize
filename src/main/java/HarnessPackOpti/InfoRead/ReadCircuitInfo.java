package HarnessPackOpti.InfoRead;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReadCircuitInfo {
    /*
    loopNo 回路编号
id 回路id
caseId 方案号
loopSys 所属系统
startApp 回路起点用电器
startAppPort 回路起点用电器接口编号
endApp 回路终点用电器
endAppPort 回路终点用电器接口编号
loopAttr 回路属性
loopWireway 回路导线选型
infoName  回路信号名
     */
    public  List<Map<String,Object>> getCircuitInfo(List<Map<String,Object>> mapFromProject){
        List<Map<String,Object>> objectList=new ArrayList<>();
        for (int i = 0; i < mapFromProject.size(); i++) {
            Map<String,Object> objectMap=new HashMap<>();
            objectMap.put("回路编号",mapFromProject.get(i).get("loopNo"));
            objectMap.put("回路id",mapFromProject.get(i).get("id"));
            objectMap.put("方案号",mapFromProject.get(i).get("caseId"));
            objectMap.put("所属系统",mapFromProject.get(i).get("loopSys"));
            objectMap.put("回路起点用电器",mapFromProject.get(i).get("startApp"));
            objectMap.put("回路起点用电器接口编号",mapFromProject.get(i).get("startAppPort"));
            objectMap.put("回路终点用电器",mapFromProject.get(i).get("endApp"));
            objectMap.put("回路终点用电器接口编号",mapFromProject.get(i).get("endAppPort"));
            objectMap.put("回路属性",mapFromProject.get(i).get("loopAttr"));
            objectMap.put("回路导线选型",mapFromProject.get(i).get("loopWireway"));
            objectMap.put("回路信号名",mapFromProject.get(i).get("infoName"));
            objectList.add(objectMap);

        }
        return  objectList;
    }
}
