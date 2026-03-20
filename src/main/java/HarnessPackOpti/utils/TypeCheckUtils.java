package HarnessPackOpti.utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类型计数器统计工具
 */
public class TypeCheckUtils {
    private static final Map<String, Integer> TYPE_COUNT_MAP = new ConcurrentHashMap<>();

    /**
     * 对指定类型进行计数并返回类型标识
     */
    public static String countType(String typeKey) {
        TYPE_COUNT_MAP.merge(typeKey, 1, Integer::sum);
        return typeKey;
    }

    /**
     * 获取指定类型的计数值
     */
    public static int getCount(String type) {
        return TYPE_COUNT_MAP.getOrDefault(type, 0);
    }

    /**
     * 获取所有类型的统计结果
     */
    public static int getAllCounts() {
        return TYPE_COUNT_MAP.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 以 JSON 格式输出所有类型统计数据
     */
    public static String toJsonString() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(TYPE_COUNT_MAP);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }

}
