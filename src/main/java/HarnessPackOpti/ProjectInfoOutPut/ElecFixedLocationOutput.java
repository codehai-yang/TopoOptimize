package HarnessPackOpti.ProjectInfoOutPut;



import HarnessPackOpti.Algorithm.FindElecFixedLocationFromLibrary;
import HarnessPackOpti.InfoRead.ReadElecLocationInfo;
import HarnessPackOpti.JsonToMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElecFixedLocationOutput {
    public Map<String,String> elecFixedLocationOutput(String fileStringFormat) throws Exception {
        //读取到的Json格式字符串转换为Map
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(fileStringFormat);


        ReadElecLocationInfo readEleLocationInfo=new ReadElecLocationInfo();
        Map<String, Object> elecLocationInfo = readEleLocationInfo.getElectionInfo(mapFile);

        FindElecFixedLocationFromLibrary findElecFixedLocationFromLibrary = new FindElecFixedLocationFromLibrary();
        Map<String, String> election = findElecFixedLocationFromLibrary.getElecLocationFromLibrary(elecLocationInfo);
        Map<String,String> locationFixedElec =new HashMap<>();
        List<String> locationUnfixedElec= new ArrayList<>();
        for (Map.Entry<String, String> entry : election.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (value==null){
                locationUnfixedElec.add(name);
            }else {
                locationFixedElec.put(name,value);
            }
        }
//        System.out.println("未读取到位置固化的用电器:"+locationUnfixedElec+"，共计"+locationUnfixedElec.size()+"个");
        System.out.println("已读取到位置固化的用电器:"+locationFixedElec+"，共计"+locationFixedElec.size()+"个");
        return locationFixedElec;
    }
}
