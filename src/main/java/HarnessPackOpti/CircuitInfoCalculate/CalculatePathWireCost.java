package HarnessPackOpti.CircuitInfoCalculate;

import java.util.Map;

public class CalculatePathWireCost {
    /**
     * @Description: 回路成本    从excel中获取导线单位商务价（元/米）然后乘以长度）
     * @input: wireType 导线选型
     * @inputExample: USB Core 0.22
     * @input: length 回路的长度（单位m）
     * @Return:   整个回路的成本
     */

    public double calculatePathWireCost(String wireType, Double length , Map<String, Map<String, String>> elecFixedLocationLibrary) {
        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
        return length * Double.parseDouble(map.get("导线单位商务价（元/米）"));
    }
}
