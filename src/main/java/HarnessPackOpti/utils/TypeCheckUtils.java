package HarnessPackOpti.utils;

import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

/**
 * 类型检查
 */
public class TypeCheckUtils {
    private static final Map<Integer, String> TYPE_MAP = new HashMap<>();
    // 存储每个类型的具体数据
    private static final Map<String, List<Object>> TYPE_DATA_MAP = new ConcurrentHashMap<>();

    static {
        //类型说明：互斥，组团，多选一，连通，用电器周围至少存在一个分支，不符合约束，存在闭环，成本，重量，长度
        //0和1都按上面进行排序：0：违反某一约束，1：符合某一约束
//        TYPE_MAP.put(0b0111100, "TYPE_1");
//        TYPE_MAP.put(0b0111101, "TYPE_2");
//        TYPE_MAP.put(0b0011100, "TYPE_3");
//        TYPE_MAP.put(0b0011101, "TYPE_4");
//        TYPE_MAP.put(0b0001100, "TYPE_5");
//        TYPE_MAP.put(0b0001101, "TYPE_6");
//        TYPE_MAP.put(0b0000100, "TYPE_7");
//        TYPE_MAP.put(0b0000101, "TYPE_8");
//        TYPE_MAP.put(0b0000000, "TYPE_9");
//        TYPE_MAP.put(0b0100001, "TYPE_10");
//        TYPE_MAP.put(0b0110000, "TYPE_11");
//        TYPE_MAP.put(0b0110001, "TYPE_12");
//        TYPE_MAP.put(0b0111000, "TYPE_13");
//        TYPE_MAP.put(0b0111001, "TYPE_14");
//        TYPE_MAP.put(0b0010100, "TYPE_15");
//        TYPE_MAP.put(0b0010101, "TYPE_16");
//        TYPE_MAP.put(0b0010000, "TYPE_17");
//        TYPE_MAP.put(0b0010001, "TYPE_18");
//        TYPE_MAP.put(0b0011000, "TYPE_19");
//        TYPE_MAP.put(0b0011001, "TYPE_20");
//        TYPE_MAP.put(0b0001000, "TYPE_21");
//        TYPE_MAP.put(0b0001001, "TYPE_22");
//        TYPE_MAP.put(0b0001100, "TYPE_23");
//        TYPE_MAP.put(0b0001101, "TYPE_24");
//        TYPE_MAP.put(0b1111110, "TYPE_25");
//        TYPE_MAP.put(0b1111111, "TYPE_26");
//        TYPE_MAP.put(0b1110000, "TYPE_27");
//        TYPE_MAP.put(0b1110100, "TYPE_28");
//        TYPE_MAP.put(0b1011100, "TYPE_29");
//        TYPE_MAP.put(0b1101100, "TYPE_30");
//        TYPE_MAP.put(0b1100100, "TYPE_31");
//        TYPE_MAP.put(0b0101100, "TYPE_32");
//        TYPE_MAP.put(0b1101000, "TYPE_33");
//        TYPE_MAP.put(0b1111000, "TYPE_34");
//        TYPE_MAP.put(0b1111001, "TYPE_35");
        TYPE_MAP.put(0b1, "TYPE_36");
        TYPE_MAP.put(0b0, "TYPE_37");
//        TYPE_MAP.put(0b00, "TYPE_38");
        TYPE_MAP.put(0b10, "TYPE_39");

    }

    public static String getType(List<Boolean> flags, Object data) {
        int mask = 0;
        for (int i = 0; i < flags.size(); i++) {
            if (flags.get(i)) {
                mask |= (1 << (flags.size() - 1 - i));
            }
        }
        String type = TYPE_MAP.getOrDefault(mask, "UNKNOWN_TYPE");
        if (type.equals("UNKNOWN_TYPE")) {
            System.out.println("未知类型：" + flags + " mask:" + mask);
        }

        // 线程安全地检查数量并添加数据
        synchronized (TYPE_DATA_MAP) {
            List<Object> dataList = TYPE_DATA_MAP.computeIfAbsent(type, k -> new ArrayList<>());
            if (dataList.size() < HarnessBranchTopoOptimize.AutoCompleteNumberLimit) {
                dataList.add(data);
                return type;
            } else {
                return type + "_LIMIT_EXCEEDED";
            }
        }
    }

    public static String addLittleCase(List<Boolean> flags, Object data) {
        int mask = 0;
        for (int i = 0; i < flags.size(); i++) {
            if (flags.get(i)) {
                mask |= (1 << (flags.size() - 1 - i));
            }
        }
        String type = TYPE_MAP.getOrDefault(mask, "UNKNOWN_TYPE");
        if (type.equals("UNKNOWN_TYPE")) {
            System.out.println("未知类型：" + flags + " mask:" + mask);
        }
        // 线程安全更新数据列表
        TYPE_DATA_MAP.computeIfAbsent(type, k -> new ArrayList<>()).add(data);
        return type;
    }


    // 保持原有的方法签名兼容性
    public static String getType(List<Boolean> flags) {
        return getType(flags, null);
    }

    // 查询指定类型的数据列表
    public static List<Object> getTypeData(String type) {
        return TYPE_DATA_MAP.getOrDefault(type, new ArrayList<>());
    }

    // 获取指定类型的数据数量
    public static int getTypeCount(String type) {
        return TYPE_DATA_MAP.getOrDefault(type, new ArrayList<>()).size();
    }

    // 获取所有类型的数据统计
    public static Map<String, Integer> getAllTypeCounts() {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, List<Object>> entry : TYPE_DATA_MAP.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return result;
    }

    // 获取所有类型的数据详情，json字符串格式
    public static String getAllTypeData() {
        try {
            // 将所有value放到一个集合里
            List<Object> allValues = new ArrayList<>();
            for (List<Object> valueList : TYPE_DATA_MAP.values()) {
                allValues.addAll(valueList);
            }

            // JSON序列化
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(allValues);
        } catch (Exception e) {
            e.printStackTrace();
            return "[]"; // 出错时返回空数组的JSON
        }
    }
}
