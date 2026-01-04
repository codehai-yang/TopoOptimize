package HarnessPackOpti.Algorithm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FindBest {
    /**
     * @Description 获取最优的几条方案（首先找出这些回路中的 回路总成本、回路总量、回路长度的最大值和最小值 然后按照 （总成本-总成本最小值）/（总成本最大值-总成本最大值）+（回路长度-回路长度最小值）/（回路长度最大值-回路长度最大值）+（回路重量-回路重量最小值）/（回路重量最大值-回路重量最大值） 其值最小的一条回路 ）
     * @input radomList:所有路径信息
     * @input name:所有路径信息
     * @Return 最优的几条方案
     */
    public List<Map<String, Object>> findBest(List<Map<String, Object>> radomList, String name,Integer topNumber) {
//       找出当中的最大值最小值
        double minCost = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总成本").toString());
        double maxCost = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总成本").toString());
        double minWeight = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总重量").toString());
        double maxWeight = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总重量").toString());
        double minLength = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总长度").toString());
        double maxLength = Double.parseDouble(((Map<String, Object>) radomList.get(0).get(name)).get("总长度").toString());
//        首先最大最小值
        for (Map<String, Object> map : radomList) {
            //这里过滤掉初始方案
            if(map.size() != 3){
                continue;
            }
            if (minCost > Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString())) {
                minCost = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString());
            }
            if (maxCost < Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString())) {
                maxCost = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString());
            }
            if (minWeight > Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString())) {
                minWeight = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString());
            }
            if (maxWeight < Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString())) {
                maxWeight = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString());
            }

            if (minLength > Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString())) {
                minLength = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString());
            }
            if (maxLength < Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString())) {
                maxLength = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString());
            }
        }
//        对每一个list 添加一个评分
        for (Map<String, Object> map : radomList) {
            double allCost = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总成本").toString());
            double weight = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总重量").toString());
            double length = Double.parseDouble(((Map<String, Object>) map.get(name)).get("总长度").toString());
            double score = (allCost - minCost) / ((maxCost - minCost) + 0.0001) * 0.98 + (weight - minWeight) / ((maxWeight - minWeight) + 0.0001) * 0.01 + (length - minLength) / ((maxLength - minLength) + 0.0001) * 0.01;
            map.put("score", score);
        }
        List<Map<String, Object>> score = findTopTenMinDoubleMaps(radomList, "score", topNumber);
        for (Map<String, Object> objectMap : score) {
            objectMap.remove("score");
        }
        return score;
    }
    /**
     * @Description: 找出分数前几的数据
     * @input: maps   需要筛选的数据
     * @input: key   以什么字段进行筛选
     * @Return: 返回前几的数据
     */
    public List<Map<String, Object>> findTopTenMinDoubleMaps(List<Map<String, Object>> maps, String key,Integer topNumber) {
        return maps.stream()
                .sorted((m1, m2) -> {
                    Double value1 = getDoubleValue(m1, key);
                    Double value2 = getDoubleValue(m2, key);
                    return value1.compareTo(value2);
                })
                .limit(topNumber)
                .collect(Collectors.toList());
    }
    /**
     * @Description: 从给定的 Map<String, Object> 中获取指定键对应的值 并将其转换为 Double 类型
     * @input: map   需要获取的数据
     * @input: key   以什么字段进行筛选
     * @Return: 返回double类型的值
     */
    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Double) {
            return (Double) value;
        } else {
            return Double.MAX_VALUE;
        }
    }
}
