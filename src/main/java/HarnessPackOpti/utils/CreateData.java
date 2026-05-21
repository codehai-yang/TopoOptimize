package HarnessPackOpti.utils;

import HarnessPackOpti.JsonToMap;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 测试类，生成测试数据
 */
public class CreateData {

    public static void main(String[] args) throws  Exception{
        JsonToMap jsonToMap = new JsonToMap();
        Random random = new Random();
        File file = new File("F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\优化测试后台记录.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        List<Map<String, String>> appPositions = (List<Map<String, String>>) jsonMap.get("appPositions");
        Map<String, Object> topoInfoMap = (Map<String, Object>) jsonMap.get("topoInfo");
        Map<String, Object> caseInfo = (Map<String, Object>) jsonMap.get("caseInfo");
        Map<String, Object> optimizeRecord = (Map<String, Object>) jsonMap.get("optimizeRecord");
        List<Map<String, String>> loopInfos = (List<Map<String, String>>) jsonMap.get("loopInfos");
        List<Map<String, Object>> points = (List<Map<String, Object>>) jsonMap.get("points");
        Map<String, String> projectInfo = (Map<String, String>) jsonMap.get("projectInfo");
        projectInfo.put("optimizeType", "3");
        String[] type = {"用电器", "配电单元", "接地点","控制器","储电单元","发电单元"};
        for (Map<String, String> appPosition : appPositions) {
            appPosition.put("elecAttribute", type[random.nextInt(type.length)]);
            appPosition.put("resourceNumb", new ArrayList<>().toString());
        }
    }
}
