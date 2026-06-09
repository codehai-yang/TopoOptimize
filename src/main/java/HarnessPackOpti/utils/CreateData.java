package HarnessPackOpti.utils;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;

import static HarnessPackOpti.utils.GINEInferenceEngine.objectMapper;

/**
 * 测试类，生成测试数据
 */
public class CreateData {

    public static void main(String[] args) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        Random random = new Random();
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\优化测试后台记录.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));// 将文件中内容转为字符串
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, Object>> appPositions = (List<Map<String, Object>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, Object>> points = (List<Map<String, Object>>) jsonMap.get("points");
        Map<String, String> projectInfo = (Map<String, String>) jsonMap.get("projectInfo");
        projectInfo.put("optimizeType", "3");
        String[] type = { "用电器", "配电单元", "接地点", "控制器", "储电单元", "发电单元" };
        List<Map<String, Object>> appPositionCopy = new ArrayList<>();
        for (Map<String, Object> appPosition : appPositions) {
            String appName = appPosition.get("appName").toString();
            if (!appName.startsWith("[")) {
                appPosition.put("elecAttribute", type[random.nextInt(type.length)]);
                appPosition.put("resourceNumb", objectMapper.writeValueAsString(Arrays.asList("不限", "不限", "不限")));
                appPositionCopy.add(appPosition);
            }
        }
        List<Map<String, String>> loopInfoCopy = new ArrayList<>();
        for (Map<String, String> loopInfos2 : loopInfos) {
            String startApp = loopInfos2.get("startApp");
            String endApp = loopInfos2.get("endApp");
            if (!startApp.startsWith("[") && !endApp.startsWith("[")) {
                loopInfos2.put("loopAttribute", "");
                loopInfos2.put("mutualExclusion", "");
                loopInfos2.put("changeTogether", "");
                loopInfos2.put("endSpecifyPoints", "");
                loopInfos2.put("startSpecifyPoints", "");
                loopInfoCopy.add(loopInfos2);
            }

        }

        // 将修改后的数据转回JSON并保存
        jsonMap.put("appPositions", appPositionCopy);
        jsonMap.put("loopInfos", loopInfoCopy);

        ObjectMapper objectMapper = new ObjectMapper();
        String newJsonContent = objectMapper.writeValueAsString(jsonMap);

        File outputFile = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\配电驱动优化测试数据.txt");
        Files.write(outputFile.toPath(), newJsonContent.getBytes());
        System.out.println("JSON已保存到: " + outputFile.getAbsolutePath());
    }
}
