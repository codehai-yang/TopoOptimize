package HarnessPackOpti.DiagnoseLibrary;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.ProjectInfoOutPut.ConfigOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class CircuitDiagnoseLibrary {
    public static Properties resource;

    /*
     回路起点用电器缺失 error
    回路终点用电器缺失 error
    回路导线选型缺失 error
    回路信号名缺失 warning
    回路起点用电器接口编号缺失 warning
    回路终点用电器接口编号缺失 warning
    回路属性缺失 warning
    所属系统缺失 warning
     */

    // 回路起点用电器缺失
    public List<String> strElecLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路起点用电器") && (objectMap.get("回路起点用电器") == null || objectMap.get("回路起点用电器").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }


    //    回路终点用电器缺失
    public List<String> endElecLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路终点用电器") && (objectMap.get("回路终点用电器") == null || objectMap.get("回路终点用电器").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    //    回路导线选型缺失
    public List<String> wireTypeLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路导线选型") && (objectMap.get("回路导线选型") == null || objectMap.get("回路导线选型").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    //    回路信号名缺失
    public List<String> circuitSignalLack(List<Map<String, Object>> circuitList) {
        List<String> infoNameLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路信号名") && (objectMap.get("回路信号名") == null || objectMap.get("回路信号名").toString().isEmpty())) {
                infoNameLack.add(objectMap.get("回路id").toString());
            }
        }
        return infoNameLack;
    }

    //    回路起点用电器接口编号缺失
    public List<String> strElecInterfaceLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路起点用电器接口编号") && (objectMap.get("回路起点用电器接口编号") == null || objectMap.get("回路起点用电器接口编号").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    //    回路终点用电器接口编号缺失
    public List<String> endElecInterfaceLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路终点用电器接口编号") && (objectMap.get("回路终点用电器接口编号") == null || objectMap.get("回路终点用电器接口编号").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }

    //    回路属性缺失
    public List<String> circuitPropertyLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("回路属性")) {
                if ((String) objectMap.get("回路属性") == null) {
                    startAppLack.add(objectMap.get("回路id").toString());
                }
            }
        }
        return startAppLack;
    }

    //     所属系统缺失
    public List<String> systemBelongLack(List<Map<String, Object>> circuitList) {
        List<String> startAppLack = new ArrayList<>();
        for (Map<String, Object> objectMap : circuitList) {
            if (objectMap.containsKey("所属系统")  && (objectMap.get("所属系统") == null || objectMap.get("所属系统").toString().isEmpty())) {
                startAppLack.add(objectMap.get("回路id").toString());
            }
        }
        return startAppLack;
    }




//    导线选型不存在
    public List<String> wireTypeInexistence(List<Map<String, Object>> circuitList) throws Exception {
        List<String> inexistenceList = new ArrayList<>();
        ConfigOutput configOutput=new ConfigOutput();
        String config = configOutput.getConfig();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(config);
        List<String> wireType = (List<String>)mapFile.get("wireType");//导线选型
        for (Map<String, Object> objectMap : circuitList) {
            if ( !wireType.contains(objectMap.get("回路导线选型").toString())){
                inexistenceList.add(objectMap.get("回路id").toString());
            }
        }
        return inexistenceList;
    }



    public List<String> circuitPropertyError(List<Map<String, Object>> circuitList) throws Exception {
        List<String> circuitPropertyList = new ArrayList<>();
        ConfigOutput configOutput=new ConfigOutput();
        String config = configOutput.getConfig();
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(config);
        List<String> circuitProperty = (List<String>)mapFile.get("circuitProperty");//导线选型
        for (Map<String, Object> objectMap : circuitList) {
            if(objectMap.get("回路属性")!=null && !objectMap.get("回路属性").toString().isEmpty()
                    && !circuitProperty.contains(objectMap.get("回路属性").toString())){
                circuitPropertyList.add(objectMap.get("回路id").toString());
            }
        }
        return circuitPropertyList;
    }


}
