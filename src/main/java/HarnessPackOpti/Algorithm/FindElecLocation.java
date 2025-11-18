package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FindElecLocation {
    /*
    读取ReadElecLocationInfo生成的map中每个用电器的“用户更改后用电器位置名称”、“用电器固化位置点名称”。
    再做判断每个用电器最后选择哪个位置点：
        用电器固化位置点名称 =null，用户更改后用电器位置名称 =null   =====>  用电器位置：null
        用电器固化位置点名称 =null，用户更改后用电器位置名称 !=null   =====>  用电器位置：用户更改后用电器位置名称
        用电器固化位置点名称 !=null，用户更改后用电器位置名称 =null   =====>  用电器位置：用电器固化位置点名称
        用电器固化位置点名称 !=null，用户更改后用电器位置名称 !=null   =====>  用电器位置：用户更改后用电器位置名称

     */
    public List<Map<String,String>> getEleclection(Map<String,Object> elecLocationInfo)  {
        List<Map<String,String >> mapList = (List<Map<String,String >>)elecLocationInfo.get("用电器信息");
        List<Map<String,String>> resultList = new ArrayList<>();
        for (Map<String, String> stringMap : mapList) {
            Map<String,String> stringMap1=new HashMap<>();
            String result = "";
            if (stringMap.get("用户更改后用电器位置名称") !=null){
                result= stringMap.get("用户更改后用电器位置名称");
            } else if (stringMap.get("用户更改后用电器位置名称") ==null && stringMap.get("用电器固化位置点名称") !=null ) {
                result= stringMap.get("用电器固化位置点名称");
            }else if (stringMap.get("用户更改后用电器位置名称") ==null && stringMap.get("用电器固化位置点名称") ==null ) {
                result= null;
            }

            stringMap1.put("key",stringMap.get("用电器名称"));
            stringMap1.put("value",result);
            resultList.add(stringMap1);
        }
//        System.out.println("从txt中读取到的用电器，经过位置判断后共计"+resultList.size()+"个");
        return resultList;
    }
}
