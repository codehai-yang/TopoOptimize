package HarnessPackOpti.CircuitInfoCalculate;

import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;

import java.util.Map;

public class CalculateConnerWetExtraCost {
    /**
     * @Description: 根据excel表格中导线选型获取湿区成本补偿——连接器塑壳（元/端）
     * @input: wireType 导线选型名称
     * @inputExample: USB Core 0.22
     * @Return: 湿区成本补偿——连接器塑壳（元/端）
     */
    public  double calculateConnerWetExtraCost(String wireType) {
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
        return  Double.parseDouble(map.get("湿区成本补偿——连接器塑壳（元/端）"));
    }
}
