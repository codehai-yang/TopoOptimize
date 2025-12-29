package HarnessPackOpti;

import HarnessPackOpti.ErrorOutput.CircuitErrorOutput;
import HarnessPackOpti.ErrorOutput.HarnessBranchTopoOptiErrorOutPut;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import HarnessPackOpti.Optimize.topo.HarnessBranchTopoTest;

import java.io.File;
import java.nio.file.Files;

public class main {
    public static void main(String[] args) throws Exception {

        File file = new File("E:\\office\\idea\\ideaProject\\project20251009\\src\\main\\resources\\优化测试后台记录.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
        HarnessBranchTopoOptimize harnessBranchTopoOptimize=new HarnessBranchTopoOptimize();
        harnessBranchTopoOptimize.topoOptimize(jsonContent);
        HarnessBranchTopoOptiErrorOutPut harnessBranchTopoOptiErrorOutPut=new HarnessBranchTopoOptiErrorOutPut();
        harnessBranchTopoOptiErrorOutPut.topoOptimizeOutput(jsonContent);

//        File file = new File("data/DataCoopWithEB/topoTxt/拓扑优化.txt");
//        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串；
//        HarnessBranchTopoTest harnessBranchTopoOptimize=new HarnessBranchTopoTest();
//        harnessBranchTopoOptimize.topoOptimize(jsonContent);
//        ToPoOptimize toPoOptimize=new ToPoOptimize();
//        toPoOptimize.topoOptimize(jsonContent);
//        StopTopoOptimize stopTopoOptimize=new StopTopoOptimize();



//        Thread demo1=new Thread(()->{
//            try {
//                toPoOptimize.topoOptimize(jsonContent);
//            }catch (Exception e){
//                throw new RuntimeException(e);
//            }
//        });
//        demo1.start();
//        try {
//            Thread.sleep(100000);
//            stopTopoOptimize.stopTopoOptimize("d6221788-622a-45ba-9386-5f9fc34f2126");
//        }catch (Exception e){
//            throw new RuntimeException(e);
//        }

//        System.out.println("adj"+adj);
////         调用方法寻找最短路径
//        FindShortestPath pathSearch = new FindShortestPath();
//        List<Integer> shortestPath = pathSearch.findShortestPath(adj,5, 134);
//        System.out.println("最短路径: " + shortestPath);
//         调用方法寻找所有路径

//        FindAllPath allPathSearch = new FindAllPath();
//        List<List<Integer>> allPath = allPathSearch.findAllPath(adj, 12, 121);
//        System.out.println("所有路径: " + allPath);
//
//        adjacencyMatrixGraph.printMatrix();
//        FindTopoBreak breakRecognize = new FindTopoBreak();
//        breakRecognize.recognizeBreak(adjacencyMatrixGraph.getAdj() ,adjacencyMatrixGraph.getAllPoint());


        //读取整个文件内容，并转为到字符串

//        File file = new File("data/DataCoopWithEB/1022.txt");
//        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
//
//        ProjectCircuitInfoOutput projectCircuitInfoOutput=new ProjectCircuitInfoOutput();
//        projectCircuitInfoOutput.projectCircuitInfoOutput(jsonContent);
//        TopoErrorOutput topoError = new TopoErrorOutput();
//        topoError.topoErrorOutput(jsonContent);

//        BunLenErrorOuput bunLenError = new BunLenErrorOuput();
//        bunLenError.budLenErrorOutput(jsonContent);
//
//
//
//        ConfigOutput configOutput = new ConfigOutput();
//        configOutput.getTopoConfig();
//
//
        //整车信息计算
//        File file1 = new File("E:\\office\\idea\\ideaProject\\project20251009\\src\\main\\resources\\circuitInfo.txt");
//        String jsonContent1 = new String(Files.readAllBytes(file1.toPath()));//将文件中内容转为字符串
//        CircuitErrorOutput circuitErrorOutput=new CircuitErrorOutput();
//        circuitErrorOutput.circuitErrorOutput(jsonContent1);


//        File file2 = new File("data/DataCoopWithEB/123.txt");
//        File file2 = new File("data/DataCoopWithEB/用电器位置.txt");
//        String jsonContent2 = new String(Files.readAllBytes(file2.toPath()));//将文件中内容转为字符串
//
//        ElecLocationErrorOutput elecLocationErrorOutput = new ElecLocationErrorOutput();
//        elecLocationErrorOutput.electionErrorOutput(jsonContent2);
//
//
//        ElecFixedLocationOutput elecFixedLocationOutput = new ElecFixedLocationOutput();
//        elecFixedLocationOutput.elecFixedLocationOutput(jsonContent2);

//        File file1 = new File("data/DataCoopWithEB/基础信息整合(纯净版).txt");
//        String jsonContent1 = new String(Files.readAllBytes(file1.toPath()));//将文件中内容转为字符串
//        ProjectCircuitInfoOutput p = new ProjectCircuitInfoOutput();
//        p.projectCircuitInfoOutput(jsonContent1);
//
//        String filePath = "data/DataCoopWithEB/整车回路信息.txt";
//        // 创建FileWriter对象，如果文件不存在会自动创建
//        FileWriter writer = new FileWriter(filePath);
//        // 使用BufferedWriter包装FileWriter以提高性能
//        BufferedWriter bufferedWriter = new BufferedWriter(writer);
//        // 将字符串写入文件
//        bufferedWriter.write(json);
//        // 关闭流
//        bufferedWriter.close();
//        HarnessBranchTopoOptimize harnessBranchTopoOptimize=new HarnessBranchTopoOptimize();
//        File file = new File("data/DataCoopWithEB/elec/1206.txt");
//        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串
//        ElecPositionVariantCalculationTest elecPositionVariantCalculation = new ElecPositionVariantCalculationTest();
//        ElecPositionVariantOutput elecPositionVariantOutput=new ElecPositionVariantOutput();
//        String s = elecPositionVariantOutput.ElecPositionVariantOutput(jsonContent);
//        OptimizeStopInput optimizeStopInput = new OptimizeStopInput();
//        System.out.println(s);
//        elecPositionVariantCalculation.ElecPositionVariantCalculation(jsonContent);
//        Thread logicThread2 = new Thread(() -> {
//            try {
//                elecPositionVariantCalculation.ElecPositionVariantCalculation(jsonContent);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//        logicThread2.start();

//        try {
//            Thread.sleep(300000); // 等待5秒
//            optimizeStopInput.stopTopoOptimize("f8867789-63e1-4342-b791-bf3fedc1cac7");
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }




    }
}
