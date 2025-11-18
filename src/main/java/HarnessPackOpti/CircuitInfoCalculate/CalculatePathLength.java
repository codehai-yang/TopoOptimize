package HarnessPackOpti.CircuitInfoCalculate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CalculatePathLength {
    /**
     * @Description: 回路长度   根据传入的分支id去获取分支的长度 有用户确认的分支长度的用用户确认的分支长度  没有选参考长度   在没有的情况下默认200mm  将这些长度累加起来
     * @input: id 分支id
     * @inputExample: [199eecf0-3320-4b2a-86e6-036442fdc317,199eecf0-3320-4b2a-86e6-036442fdc317]
     * @input: map  txt解析完成所有信息
     * @Return: Map<String, Object>  [{长度：20.0}]
     */

    public Map<String, Object> calculatePathLength(List<String> id, Map<String, Object> map) {
        Double length = 0.0;
        List<String> idList = new ArrayList<>();
        Map<String, Object> objectMap = new HashMap<>();
        for (String s : id) {
            List<Map<String, String>> edgeList = (List<Map<String, String>>) map.get("所有分支信息");
            Boolean flag = false;
            for (Map<String, String> stringStringMap : edgeList) {
                if (s.equals(stringStringMap.get("分支id编号"))) {
                    flag = true;
//                    参考长度
                    String referenceLength = null;
//                    "用户确认的分支长度
                    String verifyLength = null;
                    if (stringStringMap.get("参考长度") != null) {
                        referenceLength = String.valueOf(stringStringMap.get("参考长度"));
                    }
                    if (stringStringMap.get("用户确认的分支长度") != null) {
                        verifyLength = String.valueOf(stringStringMap.get("用户确认的分支长度"));
                    }
                    if (verifyLength != null && !verifyLength.isEmpty()) {
                        length += Double.parseDouble(verifyLength);
                    } else {
                        if (!referenceLength.isEmpty()) {
                            length += Double.parseDouble(referenceLength);
                        } else {
                            length += 200;
                        }
                    }
                }
            }

        }

        objectMap.put("长度", length);

        return objectMap;
    }
}
