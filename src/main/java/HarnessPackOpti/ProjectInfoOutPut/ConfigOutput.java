package HarnessPackOpti.ProjectInfoOutPut;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class ConfigOutput {

    public static Properties resource;

    public ConfigOutput() throws IOException {
        resource = new Properties();
        //读取resources文件夹下的HarnessOpti.properties文件
        try (InputStream input = this.getClass().getClassLoader().getResourceAsStream("HarnessOpti.properties")) {
            if (input != null) {
                resource.load(input);
            } else {
                System.err.println("Sorry, unable to find HarnessOpti.properties");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public String getConfig() throws JsonProcessingException {

        Map<String, Object> configMap = new HashMap<>();

        // resource.getProperty("drivingType")是字符串。例：左驾,右驾
        // .split(",")是按逗号切割字符串后的数组。
        // Arrays.asList()将上述数组转换为 List。例：[左驾, 右驾]
        List<String> drivingType = Arrays.asList(resource.getProperty("drivingType").split(","));//左右驾信息
        List<String> bodyStyle = Arrays.asList(resource.getProperty("bodyStyle").split(","));//车辆类型
        List<String> powerType = Arrays.asList(resource.getProperty("powerType").split(","));//动力类型
        List<String> wireType = Arrays.asList(resource.getProperty("wireType").split(","));//导线选型
        List<String> circuitProperty = Arrays.asList(resource.getProperty("circuitProperty").split(","));//回路属性

        configMap.put("drivingType", drivingType);
        configMap.put("bodyStyle", bodyStyle);
        configMap.put("powerType", powerType);
        configMap.put("wireType", wireType);
        configMap.put("circuitProperty", circuitProperty);

        //将configMap转为json文件
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(configMap);// 将Map转换为JSON字符串

        System.out.println("配置信息：" + json);
        return json;


        /*
        新增回路相关配置输出
         */

    }


    public String getTopoConfig() throws JsonProcessingException {

        Map<String, Object> configMap = new HashMap<>();

        List<String> B = Arrays.asList(resource.getProperty("B").split(","));//B
        List<String> C = Arrays.asList(resource.getProperty("C").split(","));//S
        List<String> S = Arrays.asList(resource.getProperty("S").split(","));//C
        List<String> changeTogher = Arrays.asList(resource.getProperty("changeTogher").split(","));//组团
        List<String> mutex = Arrays.asList(resource.getProperty("mutex").split(","));//互斥
        List<String> groupChoose = Arrays.asList(resource.getProperty("groupChoose").split(","));//多选以
        List<String> wear = Arrays.asList(resource.getProperty("wear").split(","));//穿腔


        configMap.put("statusB", B);
        configMap.put("statusS", S);
        configMap.put("statusC", C);
        configMap.put("changeTogher", changeTogher);
        configMap.put("mutualExclusion", mutex);
        configMap.put("oneC", groupChoose);
        configMap.put("closedLoop", wear);

        //将configMap转为json文件
        ObjectMapper objectMapper = new ObjectMapper();// 创建ObjectMapper实例
        String json = objectMapper.writeValueAsString(configMap);// 将Map转换为JSON字符串

        System.out.println("配置信息：" + json);
        return json;


        /*
        新增回路相关配置输出
         */

    }
}
