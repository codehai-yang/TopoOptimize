package HarnessPackOpti.ErrorOutput;

import HarnessPackOpti.DiagnoseLibrary.PowerDistributionDriveLibrary;
import HarnessPackOpti.InfoRead.ReadPowerPropertiesInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配电驱动优化检查
 */
public class PowerDistributionDriveErrorOuput {
    public String powerDistributionDriveErrorOuput(String fileStringFormat) throws Exception {
        //获取配置
        JsonToMap jsonToMap = new JsonToMap();
        ReadPowerPropertiesInfo readProjectInfo = new ReadPowerPropertiesInfo();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
        List<Map<String, Object>> mapList = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
        Map<String, Object> optimizeRecord = (Map<String, Object>) mapFile.get("optimizeRecord");
        //配电驱动要优化的回路类型
        String powerType = optimizeRecord.get("type").toString();
        LinkedHashMap<String ,List<String>> listMap=new LinkedHashMap<>();
        PowerDistributionDriveLibrary powerDistributionDriveLibrary=new PowerDistributionDriveLibrary();

        //回路属性缺失
        List<String> powerPropertyLack = powerDistributionDriveLibrary.loopAttrLack(mapList);
        listMap.put("回路类型缺失-error",powerPropertyLack);

        //所属系统缺失 warning
        List<String> systemBelongLack = powerDistributionDriveLibrary.systemBelongLack(mapList);
        listMap.put("回路所属系统缺失-warning",systemBelongLack);

        //回路信号名缺失 warning
        List<String> circuitSignalLack = powerDistributionDriveLibrary.powerSignalLack(mapList);
        listMap.put("回路信号名缺失-warn",circuitSignalLack);

        //回路导线选型缺失 error
        List<String> wireTypeLack = powerDistributionDriveLibrary.wireTypeLack(mapList);
        listMap.put("导线选型缺失-error",wireTypeLack);

        //回路起点用电器缺失 error
        List<String> strElecLack = powerDistributionDriveLibrary.strElecLack(mapList);
        listMap.put("起点用电器缺失-error",strElecLack);

        //回路终点用电器缺失 error
        List<String> endElecLack = powerDistributionDriveLibrary.endElecLack(mapList);
        listMap.put("终点用电器缺失-error",endElecLack);

        //终点电器件与起点电器件检查
        List<String> startPos = powerDistributionDriveLibrary.startPositionCheck(mapList);
        listMap.put("起点电器件可连接的终点电器件未选择-error", startPos);
        List<String> endPos = powerDistributionDriveLibrary.endPositionCheck(mapList);
        listMap.put("终点电器件可连接的起点电器件未选择-error", endPos);
        //组队连接关系与互斥连接关系矛盾
        List<String> strings = powerDistributionDriveLibrary.teamAndExclusiveConflict(mapList);
        listMap.put("组队连接关系与互斥连接关系矛盾-error", strings);
        //配电分配优化配置检查
        if(powerType != null && !powerType.isEmpty()) {
            if ("3".equals(powerType)) {
                List<String> powerList = powerDistributionDriveLibrary.powerCircuitError(mapList);
                listMap.put("回路类型只能选择“配电回路\"&”主供电回路”-error", powerList);
            }
            if ("4".equals(powerType)) {
                List<String> powerList = powerDistributionDriveLibrary.driverCircuitError(mapList);
                listMap.put("回路类型只能选择“驱动回路”-error", powerList);
            }
        }

        //用电器选择的位置变种点不在分支上
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) projectInfo.get("用电器信息");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) projectInfo.get("所有分支信息");
        List<Map<String, Object>> points = (List<Map<String, Object>>) projectInfo.get("所有端点信息");
        List<String> variantNotOnBranch = powerDistributionDriveLibrary.appVariantPointNotOnBranch(
                appPositions, edges, points);
        listMap.put("用电器选择的位置变种点不在分支上-error", variantNotOnBranch);

        //用电器未选择指定变种点
        List<String> variantNotSelected = powerDistributionDriveLibrary.appVariantPointNotSelected(appPositions);
        listMap.put("用电器未选择指定的变种点-error", variantNotSelected);

        //用电器可以生成的变种和数量过多
        List<String> variantCountExceeded = powerDistributionDriveLibrary.appVariantCountExceeded(appPositions, edges);
        listMap.put("用电器可以生成的变种和数量过多-warning", variantCountExceeded);
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(listMap);// 将Map转换为JSON字符串
        
        System.out.println("第三页回路设置中的错误有:\n" +json);
        return json;
    }
}
