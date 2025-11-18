package HarnessPackOpti.ErrorOutput;

import HarnessPackOpti.DiagnoseLibrary.TopoDiagnoseLibrary;
import HarnessPackOpti.InfoRead.ReadBunLenInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class BunLenErrorOuput {

    public String budLenErrorOutput(String fileStringFormat) throws Exception {
        //读取到的Json格式字符串转换为Map
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
        ReadBunLenInfo readBunLenInfo = new ReadBunLenInfo();
        Map<String, Object> bunLenInfo = readBunLenInfo.getBunLenInfo(mapFile);


        //------------------------------------------------------------------------------------------------------------------------

        //新建一个map，key是String格式来自harnessTopoMap中所有分支信息中的分支名称，value是String格式来自harnessTopoMap中所有分支信息中的分支id编号
        Map<String, String> edgeIdMap = new HashMap<>();
        for (Map<String, Object> edgeInfo : (List<Map<String, Object>>) bunLenInfo.get("所有分支信息")) {
            edgeIdMap.put((String) edgeInfo.get("分支名称"), (String) edgeInfo.get("分支id编号"));
        }
//        System.out.println("edgeIdMap:" + edgeIdMap);

        //------------------------------------------------------------------------------------------------------------------------


        TopoDiagnoseLibrary topoDiagnose = new TopoDiagnoseLibrary();


        //识别缺失长度的分支名称，存入List
        List<String> branchLengthLack = topoDiagnose.branchLengthLack(bunLenInfo);
        //branchLengthLack中的一个个分支名称转为分支id编号：  新建一个list，以branchLengthLack中每一个元素为key，获取edgeIdMap中key对应的value，并添加到list中
        List<String> branchLengthLackID = new ArrayList<>();
        for (String edgeName : branchLengthLack) {
            branchLengthLackID.add(edgeIdMap.get(edgeName));
        }


        //缺失长度值的关键尺寸编号
        List<String> criticalDimensionLackID = topoDiagnose.criticalDimensionLack(bunLenInfo);


        //缺失内容的项目基本信息：项目名称缺失、左右驾信息缺失、车辆类型缺失、动力类型缺失
        List<String> projectInfoLack = topoDiagnose.projectInfoLack(bunLenInfo);


        LinkedHashMap<String, Object> bunLenErrorMap = new LinkedHashMap<>();
        //将branchLengthLackID存放到map中
        bunLenErrorMap.put("分支长度缺失-error", branchLengthLackID);
        //将criticalDimensionLackID存放到map中
        bunLenErrorMap.put("关键尺寸长度缺失-error", criticalDimensionLackID);
        //将projectInfoLack存放到map中
        bunLenErrorMap.put("项目基本信息缺失-error", projectInfoLack);
//        System.out.println("strToEndLoop的子list个数:" + strToEndLoop.size());


        //将bunLenErrorMap转为json文件
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(bunLenErrorMap);// 将Map转换为JSON字符串
        System.out.println("第二页整车关键尺寸设置中的错误有：\n" + json);

        return json;
    }
}
