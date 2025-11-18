package HarnessPackOpti.CircuitInfoCalculate;

import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;

import java.util.Map;

public class CalculateSealCost {
    /**
     * @Description: 根据导线选型  获取湿区成本补偿——连接器塑壳（元/端）
     * @input: wireType 导线选型
     * @inputExample: USB Core 0.22
     * @Return: 返回湿区成本补偿——连接器塑壳（元/端）
     */
    public double calculateSealCost(String wireType) {
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
        return Double.parseDouble(map.get("湿区成本补偿——连接器塑壳（元/端）"));
    }
}
