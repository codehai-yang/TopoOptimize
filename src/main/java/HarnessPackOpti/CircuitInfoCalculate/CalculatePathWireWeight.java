package HarnessPackOpti.CircuitInfoCalculate;

import java.util.Map;

public class CalculatePathWireWeight {
    /**
     * @Description: 回路重量    从excel中获取导线单位重量（单位g/m）然后乘以长度）
     * @input: wireType 导线选型
     * @inputExample: USB Core 0.22
     * @input: length 回路的长度（单位m）
     * @Return:   整个回路的重量（单位g）
     */
    public  double calculatePathWireWeight(String wireType,Double length ,Map<String, Map<String, String>> elecFixedLocationLibrary) {
        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
        return  length* Double.parseDouble(map.get("导线单位重量（单位g/m）"));
    }
}
