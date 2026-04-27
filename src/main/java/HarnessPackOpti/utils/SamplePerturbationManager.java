package HarnessPackOpti.utils;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;
import java.util.List;
import java.util.Map;

/**
 * 样本扰动管理器
 * 统一管理所有类型的样本扰动操作
 */
public class SamplePerturbationManager {

    private final GenerateBreakNoise breakNoiseGenerator;
    private final GenerateLengthNoise lengthNoiseGenerator;
    private final GenerateLocationNoise locationNoiseGenerator;
    private final GeneratePriceNoise priceNoiseGenerator;
    private final GenerateConnectNoise connectNoiseGenerator;

    public SamplePerturbationManager() {
        this.breakNoiseGenerator = new GenerateBreakNoise();
        this.lengthNoiseGenerator = new GenerateLengthNoise();
        this.locationNoiseGenerator = new GenerateLocationNoise();
        this.priceNoiseGenerator = new GeneratePriceNoise();
        this.connectNoiseGenerator = new GenerateConnectNoise();
    }

    /**
     * 执行所有类型的样本扰动
     *
     * @param normList 分支ID列表
     * @param wareHouseTemp 待扰动的拓扑样本仓库
     * @param edges 原始边信息
     * @param jsonMap JSON映射数据
     * @param elecFixedLocationLibrary 电器位置库
     * @param edgeChooseBS 可选BS状态的边列表
     * @param filePath 样本输出文件路径
     * @throws Exception 扰动过程中的异常
     */
    public void executeAllPerturbations(
            List<String> normList,
            List<List<String>> wareHouseTemp,
            List<Map<String, Object>> edges,
            Map<String, Object> jsonMap,
            Map<String, Map<String, String>> elecFixedLocationLibrary,
            List<String> edgeChooseBS,
            String filePath) throws Exception {

        // 回路连接关系扰动
        long connectStartTime = System.currentTimeMillis();
        connectNoiseGenerator.generateConnectNoise(
                normList, wareHouseTemp, edges, jsonMap,
                elecFixedLocationLibrary, edgeChooseBS, filePath
        );
        System.out.println("回路连接关系扰动耗时：" + (System.currentTimeMillis() - connectStartTime));

        // 分支通断扰动
        long breakStartTime = System.currentTimeMillis();
        breakNoiseGenerator.projectCalculate(
                normList, wareHouseTemp, edges, jsonMap,
                elecFixedLocationLibrary, edgeChooseBS, filePath
        );
        System.out.println("分支通断扰动耗时：" + (System.currentTimeMillis() - breakStartTime));

        // 分支长度扰动
        long lengthStartTime = System.currentTimeMillis();
        lengthNoiseGenerator.generateLengthNoise(
                normList, wareHouseTemp, edges, jsonMap,
                elecFixedLocationLibrary, edgeChooseBS, filePath
        );
        System.out.println("分支长度扰动耗时：" + (System.currentTimeMillis() - lengthStartTime));

        // 用电器位置扰动
        long locationStartTime = System.currentTimeMillis();
        locationNoiseGenerator.generateLocationNoise(
                normList, wareHouseTemp, edges, jsonMap,
                elecFixedLocationLibrary, edgeChooseBS, filePath
        );
        System.out.println("用电器位置扰动耗时：" + (System.currentTimeMillis() - locationStartTime));

        // 回路单价扰动
        long priceStartTime = System.currentTimeMillis();
        priceNoiseGenerator.generatePriceNoise(
                normList, wareHouseTemp, edges, jsonMap,
                elecFixedLocationLibrary, edgeChooseBS, filePath
        );
        System.out.println("回路单价扰动耗时：" + (System.currentTimeMillis() - priceStartTime));
    }

    /**
     * 执行所有扰动并返回总耗时
     *
     * @param normList 分支ID列表
     * @param wareHouseTemp 待扰动的拓扑样本仓库
     * @param edges 原始边信息
     * @param jsonMap JSON映射数据
     * @param edgeChooseBS 可选BS状态的边列表
     * @param filePath 样本输出文件路径
     * @return 总耗时（毫秒）
     * @throws Exception 扰动过程中的异常
     */
    public long executeAllPerturbationsWithTotalTime(
            List<String> normList,
            List<List<String>> wareHouseTemp,
            List<Map<String, Object>> edges,
            Map<String, Object> jsonMap,
            List<String> edgeChooseBS,
            String filePath) throws Exception {

        long totalStartTime = System.currentTimeMillis();

        executeAllPerturbations(
                normList,
                wareHouseTemp,
                edges,
                jsonMap,
                ProjectCircuitInfoOutput.elecFixedLocationLibrary,
                edgeChooseBS,
                filePath
        );

        long totalTime = System.currentTimeMillis() - totalStartTime;
        System.out.println("=== 所有扰动完成，总耗时：" + totalTime + "ms ===");

        return totalTime;
    }
}
