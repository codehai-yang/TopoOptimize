package HarnessPackOpti.utils;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import HarnessPackOpti.main;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 闭环检测算法
 */
public class recognizeLoopNewTest {
    public static void main(String[] args) throws Exception {
        HarnessBranchTopoOptimize harnessBranchTopoOptimize = new HarnessBranchTopoOptimize();
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();

        InputStream inputStream = main.class.getClassLoader().getResourceAsStream("BS4EM项目json优化设置.txt");
        if (inputStream == null) {
            throw new RuntimeException("找不到资源文件: BS4EM初始json包含优化设置.txt");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        String jsonContent = sb.toString();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");
        ObjectMapper mapper = new ObjectMapper();

        InputStream topoInputStream = main.class.getClassLoader().getResourceAsStream("500数据测试bs4em.json");
        if (topoInputStream == null) {
            throw new RuntimeException("找不到资源文件: topooutputAI.json");
        }
        BufferedReader topoReader = new BufferedReader(new InputStreamReader(topoInputStream, StandardCharsets.UTF_8));
        StringBuilder topoSb = new StringBuilder();
        String topoLine;
        while ((topoLine = topoReader.readLine()) != null) {
            topoSb.append(topoLine);
        }
        topoReader.close();
        //原来json结果
        String topoJsonContent = topoSb.toString();
        //找到第一个方案的状态
        List<Map<String, Object>> solutionList = mapper.readValue(topoJsonContent,
                new TypeReference<List<Map<String, Object>>>() {
                });
        List<String> edgeStute = new ArrayList<>();
        for (int i = 0; i < solutionList.size(); i++) {
            if(i != 0){
                continue;
            }
            Map<String, Object> solution = solutionList.get(i);
            List<Map<String, String>> topoOptimizeResult = (List<Map<String, String>>) solution
                    .get("topoOptimizeResult");
            for (Map<String, String> result : topoOptimizeResult) {
                String edgeId = result.get("edgeId");
                String statue = result.get("statue");
                edgeStute.add(statue);
                for (int j = 0; j < edges.size(); j++) {
                    if(edges.get(j).get("id").equals(edgeId)) {
                        edges.get(j).put("topologyStatusCode", statue);
                    }
                }

            }
            break;
        }

        List<List<String>> recognizeLoopList = harnessBranchTopoOptimize.recognizeLoopNew(edges);
        if(recognizeLoopList.size() > 0){
            System.out.println("存在闭环");
            System.out.println("闭环数量:" + recognizeLoopList.size());
        }
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        jsonMap.put("edges",edges);
        String projectCircuitInfoOutputRsult = projectCircuitInfoOutput.projectCircuitInfoOutput(objectMapper.writeValueAsString(jsonMap));
        Map<String, Object> objectMap = jsonToMap.TransJsonToMap(projectCircuitInfoOutputRsult);
        Map<String, Object> projectCircuitInfo = (Map<String, Object>) objectMap.get("projectCircuitInfo");
        System.out.println("总成本" + projectCircuitInfo.get("总成本"));
    }
}
