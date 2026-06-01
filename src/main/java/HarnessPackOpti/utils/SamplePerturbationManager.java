package HarnessPackOpti.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.ProjectInfoOutPut.ProjectCircuitInfoOutput;

/**
 * 样本扰动管理器
 * 统一管理所有类型的样本扰动操作，并执行交叉变异
 */
public class SamplePerturbationManager {

        private final GenerateBreakNoise breakNoiseGenerator;
        private final GenerateLengthNoise lengthNoiseGenerator;
        private final GenerateLocationNoise locationNoiseGenerator;
        private final GeneratePriceNoise priceNoiseGenerator;
        private final GenerateConnectNoise connectNoiseGenerator;
        private final CrossMutationManager crossMutationManager;

        public SamplePerturbationManager() {
                this.breakNoiseGenerator = new GenerateBreakNoise();
                this.lengthNoiseGenerator = new GenerateLengthNoise();
                this.locationNoiseGenerator = new GenerateLocationNoise();
                this.priceNoiseGenerator = new GeneratePriceNoise();
                this.connectNoiseGenerator = new GenerateConnectNoise();
                this.crossMutationManager = new CrossMutationManager(HarnessBranchTopoOptimize.HybridizationLessRandomSamleNumber);
        }

        public SamplePerturbationManager(int crossoverCountPerPair) {
                this.breakNoiseGenerator = new GenerateBreakNoise();
                this.lengthNoiseGenerator = new GenerateLengthNoise();
                this.locationNoiseGenerator = new GenerateLocationNoise();
                this.priceNoiseGenerator = new GeneratePriceNoise();
                this.connectNoiseGenerator = new GenerateConnectNoise();
                this.crossMutationManager = new CrossMutationManager(crossoverCountPerPair);
        }

        /**
         * 执行所有类型的样本扰动
         *
         * @param normList                 分支ID列表
         * @param wareHouseTemp            待扰动的拓扑样本仓库
         * @param edges                    原始边信息
         * @param jsonMap                  JSON映射数据
         * @param elecFixedLocationLibrary 电器位置库
         * @param edgeChooseBS             可选BS状态的边列表
         * @param filePath                 样本输出文件路径
         * @throws Exception 扰动过程中的异常
         */
        public void executeAllPerturbations(
                        List<String> normList,
                        List<List<String>> wareHouseTemp,
                        List<Map<String, Object>> edges,
                        Map<String, Object> jsonMap,
                        Map<String, Map<String, String>> elecFixedLocationLibrary,
                        List<String> edgeChooseBS,
                        String filePath, List<Map<String, String>> appPositions, Map<String, String> eleclection,
                        Map<String, Map<String, List<String>>> mutexMap,
                        List<Map<String, List<String>>> chooseOneList,
                        List<List<String>> togetherBCList) throws Exception {

                List<Map<String, Object>> connectSamples = Collections.synchronizedList(new ArrayList<>());
                List<Map<String, Object>> breakSamples = Collections.synchronizedList(new ArrayList<>());
                List<Map<String, Object>> lengthSamples = Collections.synchronizedList(new ArrayList<>());
                List<Map<String, Object>> locationSamples = Collections.synchronizedList(new ArrayList<>());

                // 回路连接关系扰动
                long connectStartTime = System.currentTimeMillis();
                connectNoiseGenerator.generateConnectNoise(
                                normList, wareHouseTemp, edges, jsonMap,
                                elecFixedLocationLibrary, edgeChooseBS, filePath, appPositions, eleclection, mutexMap,
                                chooseOneList, togetherBCList, connectSamples);
                System.out.println("回路连接关系扰动耗时：" + (System.currentTimeMillis() - connectStartTime));

                // 分支通断扰动
                long breakStartTime = System.currentTimeMillis();
                breakNoiseGenerator.projectCalculate(
                                normList, wareHouseTemp, edges, jsonMap,
                                elecFixedLocationLibrary, edgeChooseBS, filePath, appPositions, eleclection, mutexMap,
                                chooseOneList, togetherBCList, breakSamples);
                System.out.println("分支通断扰动耗时：" + (System.currentTimeMillis() - breakStartTime));

                // 分支长度扰动
                long lengthStartTime = System.currentTimeMillis();
                lengthNoiseGenerator.generateLengthNoise(
                                normList, wareHouseTemp, edges, jsonMap,
                                elecFixedLocationLibrary, edgeChooseBS, filePath, lengthSamples);
                System.out.println("分支长度扰动耗时：" + (System.currentTimeMillis() - lengthStartTime));

                // 用电器位置扰动
                long locationStartTime = System.currentTimeMillis();
                locationNoiseGenerator.generateLocationNoise(
                                normList, wareHouseTemp, edges, jsonMap,
                                elecFixedLocationLibrary, edgeChooseBS, filePath, appPositions, eleclection, mutexMap,
                                chooseOneList, togetherBCList, locationSamples);
                System.out.println("用电器位置扰动耗时：" + (System.currentTimeMillis() - locationStartTime));

                // 回路单价扰动
                long priceStartTime = System.currentTimeMillis();
                priceNoiseGenerator.generatePriceNoise(
                                normList, wareHouseTemp, edges, jsonMap,
                                elecFixedLocationLibrary, edgeChooseBS, filePath);
                System.out.println("回路单价扰动耗时：" + (System.currentTimeMillis() - priceStartTime));

                // 交叉变异
                long crossoverStartTime = System.currentTimeMillis();
                crossMutationManager.performCrossMutation(
                                connectSamples, breakSamples, lengthSamples, locationSamples,
                                normList, edges, jsonMap, appPositions, eleclection,
                                mutexMap, chooseOneList, togetherBCList, elecFixedLocationLibrary, filePath);
                System.out.println("交叉变异耗时：" + (System.currentTimeMillis() - crossoverStartTime));
        }

        /**
         * 执行所有扰动并返回总耗时
         *
         * @param normList      分支ID列表
         * @param wareHouseTemp 待扰动的拓扑样本仓库
         * @param edges         原始边信息
         * @param jsonMap       JSON映射数据
         * @param edgeChooseBS  可选BS状态的边列表
         * @param filePath      样本输出文件路径
         * @return 总耗时（毫秒）
         * @throws Exception 扰动过程中的异常
         */
        public long executeAllPerturbationsWithTotalTime(
                        List<String> normList,
                        List<List<String>> wareHouseTemp,
                        List<Map<String, Object>> edges,
                        Map<String, Object> jsonMap,
                        List<String> edgeChooseBS,
                        String filePath, List<Map<String, String>> appPositions, Map<String, String> eleclection,
                        Map<String, Map<String, List<String>>> mutexMap,
                        List<Map<String, List<String>>> chooseOneList,
                        List<List<String>> togetherBCList) throws Exception {

                long totalStartTime = System.currentTimeMillis();

                executeAllPerturbations(
                                normList,
                                wareHouseTemp,
                                edges,
                                jsonMap,
                                ProjectCircuitInfoOutput.elecFixedLocationLibrary,
                                edgeChooseBS,
                                filePath, appPositions, eleclection, mutexMap, chooseOneList, togetherBCList);

                long totalTime = System.currentTimeMillis() - totalStartTime;
                System.out.println("=== 所有扰动完成，总耗时：" + totalTime + "ms ===");

                return totalTime;
        }
}
