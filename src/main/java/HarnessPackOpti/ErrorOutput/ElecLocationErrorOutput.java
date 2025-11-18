package HarnessPackOpti.ErrorOutput;


import HarnessPackOpti.DiagnoseLibrary.ElecLocationDiagnoseLibrary;
import HarnessPackOpti.InfoRead.ReadElecLocationInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElecLocationErrorOutput {
    public String electionErrorOutput(String fileStringFormat) throws Exception {
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        ReadElecLocationInfo readElectionInfo=new ReadElecLocationInfo();
        Map<String, Object> electionInfo = readElectionInfo.getElectionInfo(mapFile);


        Map<String ,Object> listMap=new HashMap<>();
        ElecLocationDiagnoseLibrary elecLocationDiagnoseLibrary = new ElecLocationDiagnoseLibrary();
//        用电器位置缺失
        List<String> strings = elecLocationDiagnoseLibrary.elecLocationLack(electionInfo);
        listMap.put("用电器位置缺失-error",strings);
//         用电器不在分支端点上
        List<String> mapList = elecLocationDiagnoseLibrary.elecNotAtBranchNode(electionInfo);
        listMap.put("用电器不在分支端点上-error",mapList);

        //将bunLenErrorMap转为json文件
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(listMap);// 将Map转换为JSON字符串

        System.out.println("第四页用电器位置设置中的错误有:\n" +json);
        return json;
    }
}
