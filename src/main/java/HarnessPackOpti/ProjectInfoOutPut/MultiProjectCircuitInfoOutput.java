package HarnessPackOpti.ProjectInfoOutPut;


import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MultiProjectCircuitInfoOutput {
    public String projectCircuitInfoOutputList(String jsonContent) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String, Object>> mapList = jsonToMap.TransJsonToList(jsonContent);
        List<Map<String, Object>> resultMap = new ArrayList<>();
        ProjectCircuitInfoOutput projectCircuitInfoOutput = new ProjectCircuitInfoOutput();
        for (Map<String, Object> objectMap : mapList) {
            Map<String, Object> topoInfoMap = (Map<String, Object>) objectMap.get("topoInfo");
            Map<String, Object> caseInfoMap = (Map<String, Object>) objectMap.get("caseInfo");
            String json = objectMapper.writeValueAsString(objectMap);
            String s = projectCircuitInfoOutput.projectCircuitInfoOutput(json);
            Map<String, Object> projectInfoMap = jsonToMap.TransJsonToMap(s);
             projectInfoMap.put("topoId", topoInfoMap.get("id").toString());
             projectInfoMap.put("caseId", caseInfoMap.get("id").toString());
             resultMap.add(projectInfoMap);
        }
        String result = objectMapper.writeValueAsString(resultMap);
        return result;
    }
}
