package HarnessPackOpti.ErrorOutput;

import HarnessPackOpti.DiagnoseLibrary.PowerDistributionDriveLibrary;
import HarnessPackOpti.DiagnoseLibrary.PowerTopoOptimizeDiagnoseLibrary;
import HarnessPackOpti.Algorithm.FindElecLocation;
import HarnessPackOpti.InfoRead.ReadPowerPropertiesInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表单和拓扑表设置检查
 */
public class PowerTopoOptimizeErrorOutput {
    public String powerTopoOptimizeErrorOutput(String fileStringFormat) throws Exception {
        //获取配置
        JsonToMap jsonToMap = new JsonToMap();
        ReadPowerPropertiesInfo readProjectInfo = new ReadPowerPropertiesInfo();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
        List<Map<String, Object>> mapList = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
        Map<String, Object> optimizeRecord = (Map<String, Object>) mapFile.get("optimizeRecord");
        LinkedHashMap<String ,List<String>> listMap=new LinkedHashMap<>();
        PowerTopoOptimizeDiagnoseLibrary powerDistributionDriveLibrary=new PowerTopoOptimizeDiagnoseLibrary();

        //用电器选择的位置变种点不在分支上
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) projectInfo.get("用电器信息");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) projectInfo.get("所有分支信息");
        List<Map<String, Object>> points = (List<Map<String, Object>>) projectInfo.get("所有端点信息");

        // 调用 FindElecLocation 计算每个用电器最终选择的位置名称（用户更改 > 固化）
        Map<String, String> eleclectionMap = new java.util.HashMap<>();
        FindElecLocation findElecLocation = new FindElecLocation();
        List<Map<String, String>> eleclectionList = findElecLocation.getEleclection(projectInfo);
        for (Map<String, String> m : eleclectionList) {
            eleclectionMap.put(m.get("key"), m.get("value"));
        }

        List<String> variantNotOnBranch = powerDistributionDriveLibrary.appVariantPointNotOnBranch(
                appPositions, edges, points, eleclectionMap);
        listMap.put("用电器选择的位置变种点不在分支上-error", variantNotOnBranch);

        //用电器未选择指定变种点
        List<String> variantNotSelected = powerDistributionDriveLibrary.appVariantPointNotSelected(
                appPositions, eleclectionMap);
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
