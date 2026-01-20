package HarnessPackOpti.Algorithm;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IntergateCircuitInfo {

    /**
     * @Description 整合回路信息汇总计算
     * @input pathId 回路id
     * @inputExample [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input pointList 整车回路整合后信息
     * @Return 整合后的回路信息
     */
    public Map<String, Object> intergateCircuitInfo(List<String> pathId, Map<String, Object> pointList) {
        Map<String, Object> resultMap = new HashMap<>();
//        总成本
        Map<String, Object> totalCost = new HashMap<>();
        totalCost.put("总成本", 0.0);
        totalCost.put("回路湿区成本总加成", 0.0);
        totalCost.put("回路打断总成本", 0.0);
        totalCost.put("回路两端端子总成本", 0.0);
        totalCost.put("回路导线总成本", 0.0);
        totalCost.put("回路总重量", 0.0);
        totalCost.put("回路总长度", 0.0);
        totalCost.put("端子总成本", 0.0);
        totalCost.put("连接器塑壳总成本", 0.0);
        totalCost.put("防水塞总成本", 0.0);
        totalCost.put("回路绕线长度总值", 0.0);
        totalCost.put("回路绕线长度均值", 0.0);
        totalCost.put("回路打断总次数", 0);
        totalCost.put("回路打断数量占比", "0.00%");
        totalCost.put("回路打断成本代价均值", 0.0);
        double lenght = 0.0;
        int coiling = 0;
        int circuitBreakNum = 0;
        DecimalFormat df = new DecimalFormat("0.00");
        int count = 0;
        for (String s : pathId) {
            Map<String, Object> objectMap = (Map<String, Object>) pointList.get(s);
            //排除分支信息为空的
            if(objectMap == null){
                continue;
            }
            totalCost.put("总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("总成本").toString()) + Double.parseDouble(objectMap.get("回路总成本").toString()))));
            totalCost.put("回路湿区成本总加成",Double.parseDouble( df.format(Double.parseDouble( totalCost.get("回路湿区成本总加成").toString()) + Double.parseDouble(objectMap.get("回路湿区成本加成").toString()))));
            totalCost.put("回路打断总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路打断总成本").toString()) + Double.parseDouble(objectMap.get("回路打断成本").toString()))));
            totalCost.put("回路两端端子总成本",Double.parseDouble(df.format(Double.parseDouble( totalCost.get("回路两端端子总成本").toString()) + Double.parseDouble(objectMap.get("回路两端端子成本").toString()))));
            totalCost.put("回路导线总成本",Double.parseDouble( df.format(Double.parseDouble( totalCost.get("回路导线总成本").toString()) + Double.parseDouble(objectMap.get("回路导线成本").toString()))));
            totalCost.put("回路总重量",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) + Double.parseDouble(objectMap.get("回路重量").toString()))));
            totalCost.put("回路总长度",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) + Double.parseDouble(objectMap.get("回路长度").toString()))));
            totalCost.put("端子总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("端子总成本").toString()) + Double.parseDouble(objectMap.get("端子成本").toString()))));
            totalCost.put("连接器塑壳总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("连接器塑壳总成本").toString()) + Double.parseDouble(objectMap.get("连接器塑壳成本").toString()))));
            totalCost.put("防水塞总成本",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("防水塞总成本").toString()) + Double.parseDouble(objectMap.get("防水塞成本").toString()))));
            totalCost.put("回路绕线长度总值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路绕线长度总值").toString()) + Double.parseDouble(objectMap.get("回路绕线长度").toString()))));
            lenght += Double.parseDouble( objectMap.get("回路理论直径").toString()) * Double.parseDouble( objectMap.get("回路理论直径").toString());
            if( Double.parseDouble(objectMap.get("回路绕线长度").toString()) > 0 ){
                coiling++;
            }
            if(Double.parseDouble(objectMap.get("回路打断次数").toString()) > 0){
                circuitBreakNum++;
            }
            //回路打断后计算
            int i = Integer.parseInt(objectMap.get("回路打断次数").toString());
            i += 1;
            count += i;
        }
        totalCost.put("回路打断总次数", circuitBreakNum);
        if(coiling > 0){
            totalCost.put("回路绕线长度均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路绕线长度总值").toString()) / coiling)));
        }
        //回路打断前与打断后统计
        totalCost.put("回路绕线数量",coiling);
        if(pathId.size() > 0){
            double coilingPercent = (double)coiling / pathId.size() * 100;
            double breakNumb = Double.parseDouble(totalCost.get("回路打断总次数").toString()) / pathId.size() * 100;
            totalCost.put("回路打断成本代价均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路打断总成本").toString()) / pathId.size())));
            totalCost.put("回路绕线数量占比",df.format(coilingPercent) + "%");
            totalCost.put("回路打断数量占比",df.format(breakNumb) + "%");
        }else {
            totalCost.put("回路绕线数量占比","0.00%");
            totalCost.put("回路打断数量占比","0.00%");
        }
        totalCost.put("回路重量均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("回路总重量").toString()) / pathId.size())));
        totalCost.put("回路数量(打断前)", pathId.size());
        totalCost.put("回路数量(打断后)", count);
        totalCost.put("回路成本均值",Double.parseDouble( df.format(Double.parseDouble(totalCost.get("总成本").toString()) / pathId.size())));
        //回路均值打断前
        double avgLength = 0.00;
        if(pathId.size() > 0){
            avgLength = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / pathId.size()));
        }
        totalCost.put("回路长度均值(打断前)",avgLength);
        //回路均值打断后
        double avgLength2 = 0.00;
        if(count > 0){
            avgLength2 = Double.parseDouble(df.format(Double.parseDouble(totalCost.get("回路总长度").toString()) / count));
        }
        totalCost.put("回路长度均值(打断后)",avgLength2);
        totalCost.put("总理论直径",Double.parseDouble( df.format(Math.sqrt(lenght)*1.3)));
        totalCost.put("分支直径RGB坐标",getlengthColor((Double) totalCost.get("总理论直径")));
        resultMap.put("circuitInfoIntergation", totalCost);
        resultMap.put("circuitList", pathId);
        return resultMap;
    }
    //    根据传入的值找到对应的颜色
    public static String getlengthColor(double number) {
        if (number == 0) {
            return "rgb(248,246,231)";
        } else if (number >= 0 && number <= 5) {
            return "rgb(0,0,255)";
        } else if (number >= 5 && number <= 10) {
            return "rgb(0,255,255)";
        } else if (number >= 10 && number <= 15) {
            return "rgb(0,255,0)";
        } else if (number >= 15 && number <= 20) {
            return "rgb(127,255,0)";
        } else if (number >= 20 && number <= 25) {
            return "rgb(255,255,0)";
        } else if (number >= 25 && number <= 30) {
            return "rgb(255,165,0)";
        } else if (number >= 30 && number <= 35) {
            return "rgb(255,69,0)";
        } else if (number >= 35 && number <= 40) {
            return "rgb(255,0,0)";
        } else if (number >= 40 && number <= 45) {
            return "rgb(139,0,0)";
        } else {
            return "rgb(0,0,0)";
        }
    }

}
