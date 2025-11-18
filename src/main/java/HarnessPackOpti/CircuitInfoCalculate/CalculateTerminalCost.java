package HarnessPackOpti.CircuitInfoCalculate;

import HarnessPackOpti.InfoRead.ReadWireInfoLibrary;

import java.util.Map;

public class CalculateTerminalCost {
    /**
     * @Description: 根据导线选型  获取端子成本（元/端）
     * @input: wireType 导线选型
     * @inputExample: USB Core 0.22
     * @Return: 返回端子成本（元/端）
     */
    public  double calculateTerminalCost(String wireType) {
        ReadWireInfoLibrary readWireInfoLibrary = new ReadWireInfoLibrary();
        Map<String, Map<String, String>> elecFixedLocationLibrary = readWireInfoLibrary.getElecFixedLocationLibrary();
        Map<String, String> map = elecFixedLocationLibrary.get(wireType);
        return  Double.parseDouble(map.get("端子成本（元/端）"));
    }

}
