package HarnessPackOpti.utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类型检查
 */
public class TypeCheckUtils {
    private static final Map<String, Integer> TYPE_MAP = new HashMap<>();
    private static final Map<String, Integer> TYPE_DATA_MAP = new ConcurrentHashMap<>();

    static {
        TYPE_MAP.put("type1", 0);       //分支通断样本数量
        TYPE_MAP.put("type2", 0);
        TYPE_MAP.put("type3", 0);
        TYPE_MAP.put("type4", 0);
        TYPE_MAP.put("type5", 0);
    }

    public static String getType(String typeKey) {


        synchronized (TYPE_DATA_MAP) {
            int count = TYPE_DATA_MAP.getOrDefault(typeKey, 0);
            TYPE_DATA_MAP.put(typeKey, count + 1);
            return typeKey;
        }
    }

    public static String addLittleCase(String typeKey) {
        return getType(typeKey);
    }

    public static List<Object> getTypeData(String type) {
        throw new UnsupportedOperationException("已改为只统计数量，不存储具体数据");
    }

    public static int getTypeCount(String type) {
        return TYPE_DATA_MAP.getOrDefault(type, 0);
    }

    public static Map<String, Integer> getAllTypeCounts() {
        return new HashMap<>(TYPE_DATA_MAP);
    }

    public static String getAllTypeData() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(TYPE_DATA_MAP);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }
}
