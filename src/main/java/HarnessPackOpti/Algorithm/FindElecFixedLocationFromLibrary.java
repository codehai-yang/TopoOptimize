package HarnessPackOpti.Algorithm;

import HarnessPackOpti.InfoRead.ReadElecFixedLocationLibrary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FindElecFixedLocationFromLibrary {

    //从用电器位置固化库readElecLocationLibrary中获得匹配项目基本信息的用电器固化位置
    public Map<String, String> getElecLocationFromLibrary(Map<String, Object> elecLocationInfo) {//mapFile来自ReadElecLocationInfo

        //读取ReadElecLocationInfo生成的map中的 项目基本信息：“左右驾”、"车辆类型"、"动力类型"
        HashMap<String, String> projectInfoMap = (HashMap<String, String>) elecLocationInfo.get("项目基本信息");
        String drivingType = projectInfoMap.get("左右驾");
        String bodyStyle = projectInfoMap.get("车辆类型");
        String powerType = projectInfoMap.get("动力类型");

        //读取用电器位置固化库readElecLocationLibrary生成的map中 位置固化的用电器所适配的“左右驾”、"车辆类型"、"动力类型"信息
        ReadElecFixedLocationLibrary readElecLocationLibrary = new ReadElecFixedLocationLibrary();
        Map<String, List> reserveListWithHarness = readElecLocationLibrary.getElecFixedLocationLibrary();


        List<Map<String, String>> mapList = (List<Map<String, String>>) elecLocationInfo.get("用电器信息");


        //通过用电器名称及项目信息“左右驾”、"车辆类型"、"动力类型"，与固化库中的“左右驾”、"车辆类型"、"动力类型"信息进行匹配，从而找到对应的位置
        Map<String, String> localName = new HashMap<>();
        for (Map<String, String> stringMap : mapList) {
            String elecName = stringMap.get("用电器名称");
            List<Map<String, Object>> list = findValueIgnoreCase(reserveListWithHarness, elecName);
            if (list !=null) {
                if (list.size() == 1) {
                    Map<String, Object> objectMap = (Map<String, Object>) list.get(0);
                    for (Map.Entry<String, Object> entry : objectMap.entrySet()) {
                        String name = entry.getKey();
                        Map<String, String> value = (Map<String, String>) entry.getValue();
                        String driving = value.get("左右驾");
                        String body = value.get("车辆类型");
                        String power = value.get("动力类型");
                        Boolean flag = true;
                        if (!driving.isEmpty() && !"/".equals(driving) && !"All".equals(driving)) {
                            if (!driving.equals(drivingType)) {
                                flag = false;
                            }
                        }
                        if (!body.isEmpty() && !"/".equals(body) && !"All".equals(body)) {
                            if (!body.equals(bodyStyle)) {
                                flag = false;
                            }
                        }
                        if (!power.isEmpty() && !"/".equals(power) && !"All".equals(power)) {
                            if (!power.equals(powerType)) {
                                flag = false;
                            }
                        }
                        if (flag) {
                            localName.put(elecName, name);
                        } else {
                            localName.put(elecName, null);
                        }
                    }
                } else {
                    Map<String, Object> objectMap = (Map<String, Object>) list.get(0);
                    for (Map.Entry<String, Object> entry : objectMap.entrySet()) {
                        String name = entry.getKey();
                        Map<String, String> value = (Map<String, String>) entry.getValue();
                        String driving = value.get("左右驾");
                        String body = value.get("车辆类型");
                        String power = value.get("动力类型");
                        Boolean flag = true;
                        if (!driving.isEmpty() && !"/".equals(driving) && !"All".equals(driving)) {
                            if (!driving.equals(drivingType)) {
                                flag = false;
                            }
                        }
                        if (!body.isEmpty() && !"/".equals(body) && !"All".equals(body)) {
                            if (!body.equals(bodyStyle)) {
                                flag = false;
                            }
                        }
                        if (!power.isEmpty() && !"/".equals(power) && !"All".equals(power)) {
                            if (!power.equals(powerType)) {
                                flag = false;
                            }
                        }
                        if (flag) {
                            localName.put(elecName, name);
                        } else {
                            Map<String, Object> objectMap2 = (Map<String, Object>) list.get(1);
                            for (Map.Entry<String, Object> entry2 : objectMap2.entrySet()) {
                                String name2 = entry2.getKey();
                                Map<String, String> value2 = (Map<String, String>) entry2.getValue();
                                String driving2 = value2.get("左右驾");
                                String body2 = value2.get("车辆类型");
                                String power2 = value2.get("动力类型");
                                Boolean flag2 = true;
                                if (!driving2.isEmpty() && !"/".equals(driving2) && !"All".equals(driving2)) {
                                    if (!driving2.equals(drivingType)) {
                                        flag2 = false;
                                    }
                                }
                                if (!body2.isEmpty() && !"/".equals(body2) && !"All".equals(body2)) {
                                    if (!body2.equals(bodyStyle)) {
                                        flag2 = false;
                                    }
                                }
                                if (!power2.isEmpty() && !"/".equals(power2) && !"All".equals(power2)) {
                                    if (!power2.equals(powerType)) {
                                        flag2 = false;
                                    }
                                }
                                if (flag2) {
                                    localName.put(elecName, name2);
                                } else {
                                    localName.put(elecName, null);
                                }
                            }
                        }
                    }
                }
            } else {
                localName.put(elecName, null);
            }
        }

        return localName;
    }


    public static List findValueIgnoreCase(Map<String, List> map, String key) {
        for (Map.Entry<String, List> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
