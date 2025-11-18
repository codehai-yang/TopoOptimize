package HarnessPackOpti;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class JsonToMap {
    public Map<String, Object> TransJsonToMap(String file)throws Exception{

//        // 读取整个文件内容到字符串
//        File file = new File(filePath);
//        String jsonContent = new String(Files.readAllBytes(file.toPath()));

        // 将JSON字符串转换为Map
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> map = objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {
        });

        // 打印Map集合
//        System.out.println(map);
        return map;
    }
    public List<Map<String,Object>> TransJsonToList(String file)throws Exception{


        // 将JSON字符串转换为Map
        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String,Object>> list = objectMapper.readValue(file, List.class);

        // 打印Map集合
//        System.out.println(map);
        return list;
    }
}
