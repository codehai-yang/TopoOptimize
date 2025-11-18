package HarnessPackOpti.Algorithm;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;

//SplitCircuitByInterFaceDirectConnect
public class SplitCircuitByInterDirectConn {

    /**
     *
     * @param loopInfos  不固定回路
     * @param pointList     //可变用电器焊点集合
     * @return
     * @throws JsonProcessingException
     */
    public List<List<Map<String, Object>>> groupLoops(List<Map<String, Object>> loopInfos, List<String> pointList) throws JsonProcessingException {
        List<Map<String, Object>> remainingcircuitList = new ArrayList<>();
        //不固定回路集合
        remainingcircuitList.addAll(loopInfos);
        List<List<Map<String, Object>>> familyList = new ArrayList<>();
        while (remainingcircuitList.size() > 0) {
            Map<String, Object> loopInfo = remainingcircuitList.get(0);
            List<Map<String, Object>> group = new ArrayList<>();
            group.add(loopInfo);
            String startApp = loopInfo.get("回路起点用电器").toString();
            String endApp = loopInfo.get("回路终点用电器").toString();
            String startAppPort = loopInfo.get("回路起点用电器接口编号") != null ? (String) loopInfo.get("回路起点用电器接口编号") : null;
            String endAppPort = loopInfo.get("回路终点用电器接口编号") != null ? (String) loopInfo.get("回路终点用电器接口编号") : null;
            Set<String> searchList = new HashSet<>();
            //可变用电器焊点集合是否包含指定用电器
            if (pointList.contains(startApp)) {
                //回路起点不为空且为焊点
                if (!(startAppPort == null || startAppPort.isEmpty()) || startApp.startsWith("[")) {
                    searchList.add(getKey(startApp, startAppPort));
                }
            }
            if (pointList.contains(endApp)) {
                //回路终点不为空且为焊点
                if (!(endAppPort == null || endAppPort.isEmpty()) || endApp.startsWith("[")) {
                    searchList.add(getKey(endApp, endAppPort));
                }
            }
            if (searchList.size()==0){
                familyList.add(group);
                remainingcircuitList.remove(loopInfo);
                continue;
            }
            remainingcircuitList.remove(loopInfo);
            while (searchList.size() > 0) {
                Iterator<Map<String, Object>> iterator = remainingcircuitList.iterator();
                Set<String> temp = new HashSet<>();
                while (iterator.hasNext()) {
                    Map<String, Object> objectMap = iterator.next();
                    String remainingstartApp = objectMap.get("回路起点用电器").toString();
                    String remainingendApp = objectMap.get("回路终点用电器").toString();
                    String remainingstartAppPort = objectMap.get("回路起点用电器接口编号") != null ? (String) objectMap.get("回路起点用电器接口编号") : null;
                    String remainingendAppPort = objectMap.get("回路终点用电器接口编号") != null ? (String) objectMap.get("回路终点用电器接口编号") : null;
                    //看其他回路里是否包含指定焊点，如果有指定焊点，则归为一组
                    if (searchList.contains(getKey(remainingstartApp, remainingstartAppPort)) || searchList.contains(getKey(remainingendApp, remainingendAppPort))) {
                        //条件通过则证明回路有相同的焊点
                        group.add(objectMap);
                        //可变用电器名称集合是否包含回路起点用电器并且可变用电器集合里是否起点用电器-起点用电器接口编号，起点用电器接口编号不能为空或起点用电器为焊点
                        if (pointList.contains(remainingstartApp)  && !searchList.contains(getKey(remainingstartApp, remainingstartAppPort))  && (!(remainingstartAppPort == null || remainingstartAppPort.isEmpty()) || remainingstartApp.startsWith("["))  ) {
                            temp.add(getKey(remainingstartApp, remainingstartAppPort));
                        }
                        if (pointList.contains(remainingendApp) && !searchList.contains(getKey(remainingendApp, remainingendAppPort))  && (!(remainingendAppPort == null || remainingendAppPort.isEmpty()) || remainingendApp.startsWith("["))) {
                            temp.add(getKey(remainingendApp, remainingendAppPort));
                        }
                        iterator.remove(); // 使用迭代器安全地移除元素
                    }
                }
                searchList = temp;
            }

            familyList.add(group);
        }
        return familyList;
    }

    private static String getKey(String app, String appPort) {
        if (appPort == null || appPort.isEmpty()) {
            return app;
        }
        return app + "_" + appPort;
    }


}
