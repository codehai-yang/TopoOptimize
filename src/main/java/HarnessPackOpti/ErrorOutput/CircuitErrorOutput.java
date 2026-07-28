package HarnessPackOpti.ErrorOutput;

import HarnessPackOpti.DiagnoseLibrary.CircuitDiagnoseLibrary;
import HarnessPackOpti.InfoRead.ReadCircuitInfo;
import HarnessPackOpti.InfoRead.ReadProjectInfo;
import HarnessPackOpti.JsonToMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CircuitErrorOutput {
    public String circuitErrorOutput(String fileStringFormat) throws Exception {
      //获取配置
      JsonToMap jsonToMap = new JsonToMap();
      ReadProjectInfo readProjectInfo = new ReadProjectInfo();
      Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);
      Map<String, Object> projectInfo = readProjectInfo.getProjectInfo(mapFile);
      List<Map<String, Object>> mapList = (List<Map<String, Object>>) projectInfo.get("回路用电器信息");
      Map<String, Object> optimizeRecord = (Map<String, Object>) mapFile.get("optimizeRecord");
      //配电驱动要优化的回路
      String powerType = optimizeRecord.get("type").toString();
      //读取到的Json格式字符串转换为List
//        List<Map<String,Object>> maps = jsonToMap.TransJsonToList(fileStringFormat);
//        ReadCircuitInfo readCircuitInfo=new ReadCircuitInfo();
//      List<Map<String,Object>> mapList= readCircuitInfo.getCircuitInfo(maps);


      LinkedHashMap<String ,List<String>> listMap=new LinkedHashMap<>();

        CircuitDiagnoseLibrary circuitDiagnoseLibrary=new CircuitDiagnoseLibrary();

//        回路起点用电器缺失 error
        List<String> strElecLack = circuitDiagnoseLibrary.strElecLack(mapList);
        listMap.put("回路起点用电器缺失-error",strElecLack);
//    回路终点用电器缺失 error
        List<String> endElecLack = circuitDiagnoseLibrary.endElecLack(mapList);
        listMap.put("回路终点用电器缺失-error",endElecLack);
//    回路导线选型缺失 error
        List<String> wireTypeLack = circuitDiagnoseLibrary.wireTypeLack(mapList);
        listMap.put("回路导线选型缺失-error",wireTypeLack);
//    回路信号名缺失 warning
        List<String> circuitSignalLack = circuitDiagnoseLibrary.circuitSignalLack(mapList);
        listMap.put("回路信号名缺失-warn",circuitSignalLack);
//    回路属性缺失 warning
        List<String> circuitPropertyLack = circuitDiagnoseLibrary.circuitPropertyLack(mapList);
        listMap.put("回路属性缺失-warn",circuitPropertyLack);
//    所属系统缺失 warning
        List<String> systemBelongLack = circuitDiagnoseLibrary.systemBelongLack(mapList);
        listMap.put("所属系统缺失-warn",systemBelongLack);
//    回路起点用电器接口编号缺失 warning
      List<String> strElecInterfaceLack = circuitDiagnoseLibrary.strElecInterfaceLack(mapList);
      listMap.put("回路起点用电器接口编号缺失-warn",strElecInterfaceLack);
//    回路终点用电器接口编号缺失 warning
      List<String> endElecInterfaceLack = circuitDiagnoseLibrary.endElecInterfaceLack(mapList);
      listMap.put("回路终点用电器接口编号缺失-warn",endElecInterfaceLack);
      List<String> circuitPropertyError = circuitDiagnoseLibrary.circuitPropertyError(mapList);
      listMap.put("回路属性选择不存在-error",circuitPropertyError);

      if(wireTypeLack.size()==0){
        List<String> wireTypeInexistence = circuitDiagnoseLibrary.wireTypeInexistence(mapList);
        listMap.put("回路导线选型不存在-error",wireTypeInexistence);
      }else {
        listMap.put("回路导线选型不存在-error",null);
      }
      //配电分配优化配置检查
      if(powerType != null && !powerType.isEmpty()) {
        if ("3".equals(powerType)) {
          List<String> powerList = circuitDiagnoseLibrary.powerCircuitError(mapList);
          listMap.put("回路类型只能选择“配电回路\"&”主供电回路”-error", powerList);
        }
        if ("4".equals(powerType)) {
          List<String> powerList = circuitDiagnoseLibrary.driverCircuitError(mapList);
          listMap.put("回路类型只能选择“驱动回路”-error", powerList);
        }
        //终点电器件与起点电器件检查
        List<String> startPos = circuitDiagnoseLibrary.startPositionCheck(mapList);
        listMap.put("起点电器件可连接的终点电器件未选择-error", startPos);
        List<String> endPos = circuitDiagnoseLibrary.endPositionCheck(mapList);
        listMap.put("终点电器件可连接的起点电器件未选择-error", endPos);
        //组队连接关系与互斥连接关系矛盾
        List<String> strings = circuitDiagnoseLibrary.teamAndExclusiveConflict(mapList);
        listMap.put("组队连接关系与互斥连接关系矛盾-error", strings);
      }
      //将bunLenErrorMap转为json文件
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(listMap);// 将Map转换为JSON字符串

      System.out.println("第三页回路设置中的错误有:\n" +json);
        return json;
    }


}
