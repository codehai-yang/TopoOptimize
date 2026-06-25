package HarnessPackOpti.utils;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import HarnessPackOpti.JsonToMap;
import HarnessPackOpti.main;

/**
 * 从JSON文件导出优化结果到Excel
 */
public class JsonToExcel {
    public static void main(String[] args) throws Exception {
        // 读取原始输入文件，获取edges列表
        JsonToMap jsonToMap = new JsonToMap();
        InputStream inputStream = main.class.getClassLoader().getResourceAsStream("BS4EM项目json优化设置.txt");
        if (inputStream == null) {
            throw new RuntimeException("找不到资源文件: BS4EM初始json包含优化设置.txt");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        String jsonContent = sb.toString();
        Map<String, Object> jsonMap = jsonToMap.TransJsonToMap(jsonContent);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) jsonMap.get("edges");

        // 读取topooutputAI.json，导出第一个方案的Excel
        InputStream topoInputStream = main.class.getClassLoader().getResourceAsStream("最新.json");
        if (topoInputStream == null) {
            throw new RuntimeException("找不到资源文件: topooutputAI.json");
        }
        BufferedReader topoReader = new BufferedReader(new InputStreamReader(topoInputStream, StandardCharsets.UTF_8));
        StringBuilder topoSb = new StringBuilder();
        String topoLine;
        while ((topoLine = topoReader.readLine()) != null) {
            topoSb.append(topoLine);
        }
        topoReader.close();
        String topoJsonContent = topoSb.toString();

        String outputExcelPath = "F:\\office\\idearProjects\\project20251009\\src\\main\\resources\\BS4EM闭环算法时间优化结果.xlsx";
        exportTopoResultToExcel(topoJsonContent, edges, outputExcelPath);
        System.out.println("Excel导出完成: " + outputExcelPath);
    }

    /**
     * @Description: 将topooutputAI.json中的所有方案导出到同一个Excel文件
     *               每个方案占3列（分支起点名称、终点名称、分支状态），方案之间空2列
     * @param topoOutputJsonContent topooutputAI.json的JSON字符串内容（JSON数组格式）
     * @param edges                 原始输入中的分支信息列表，每个元素包含id、startPointName、endPointName
     * @param outputExcelPath       输出Excel文件路径
     */
    public static void exportTopoResultToExcel(String topoOutputJsonContent,
            List<Map<String, Object>> edges,
            String outputExcelPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // 解析topooutputAI.json（JSON数组格式）
        List<Map<String, Object>> solutionList = mapper.readValue(topoOutputJsonContent,
                new TypeReference<List<Map<String, Object>>>() {
                });
        if (solutionList == null || solutionList.isEmpty()) {
            throw new RuntimeException("topooutputAI.json 内容为空");
        }

        // 构建edgeId -> {startPointName, endPointName} 的映射，加速查找
        Map<String, Map<String, String>> edgeIdToNameMap = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String edgeId = (String) edge.get("id");
            if (edgeId != null) {
                Map<String, String> nameMap = new HashMap<>();
                nameMap.put("startPointName", edge.get("startPointName") != null
                        ? edge.get("startPointName").toString()
                        : "");
                nameMap.put("endPointName", edge.get("endPointName") != null
                        ? edge.get("endPointName").toString()
                        : "");
                edgeIdToNameMap.put(edgeId, nameMap);
            }
        }

        // 创建Excel工作簿，所有方案放在同一个sheet中
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("全部方案");
        List<String> allNotFoundEdgeIds = new ArrayList<>();

        // 每个方案占3列，方案之间空2列，即步长=5
        int colStep = 5;
        int maxRowCount = 0;

        for (int s = 0; s < solutionList.size(); s++) {
            Map<String, Object> solution = solutionList.get(s);
            List<Map<String, String>> topoOptimizeResult = (List<Map<String, String>>) solution
                    .get("topoOptimizeResult");
            Map<String,Object> projectCircuitInfo = (Map<String,Object>) solution
                    .get("projectCircuitInfo");
            if (topoOptimizeResult == null || topoOptimizeResult.isEmpty()) {
                System.out.println("警告: 方案 " + s + " 的 topoOptimizeResult 为空，跳过");
                continue;
            }
            String totalCost = projectCircuitInfo.get("总成本").toString();
            int colStart = s * colStep;

            // 写入表头（第0行）
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                headerRow = sheet.createRow(0);
            }
            headerRow.createCell(colStart).setCellValue("方案" + (s + 1) + " 起点");
            headerRow.createCell(colStart + 1).setCellValue("终点");
            headerRow.createCell(colStart + 2).setCellValue("状态");
            headerRow.createCell(colStart + 3).setCellValue("总成本");


            // 写入数据行
            int rowIndex = 1;
            Row row1 = sheet.getRow(rowIndex);
                if (row1 == null) {
                    row1 = sheet.createRow(rowIndex);
                }
            row1.createCell(colStart + 3).setCellValue(totalCost);
            List<String> notFoundEdgeIds = new ArrayList<>();
            for (Map<String, String> result : topoOptimizeResult) {
                String edgeId = result.get("edgeId");
                String statue = result.get("statue");

                Row dataRow = sheet.getRow(rowIndex);
                if (dataRow == null) {
                    dataRow = sheet.createRow(rowIndex);
                }
                Map<String, String> nameMap = edgeIdToNameMap.get(edgeId);
                if (nameMap != null) {
                    dataRow.createCell(colStart).setCellValue(nameMap.get("startPointName"));
                    dataRow.createCell(colStart + 1).setCellValue(nameMap.get("endPointName"));
                } else {
                    dataRow.createCell(colStart).setCellValue("未找到: " + edgeId);
                    dataRow.createCell(colStart + 1).setCellValue("");
                    notFoundEdgeIds.add(edgeId);
                }
                dataRow.createCell(colStart + 2).setCellValue(statue);
                rowIndex++;
            }

            maxRowCount = Math.max(maxRowCount, topoOptimizeResult.size());
            allNotFoundEdgeIds.addAll(notFoundEdgeIds);
            System.out.println("方案 " + (s + 1) + ": 导出 " + topoOptimizeResult.size() + " 条记录");
        }

        // 自动调整列宽（每个方案的3列）
        for (int s = 0; s < solutionList.size(); s++) {
            int colStart = s * colStep;
            for (int c = 0; c < 3; c++) {
                try {
                    sheet.autoSizeColumn(colStart + c);
                } catch (Exception e) {
                    // 忽略空列
                }
            }
        }

        // 输出Excel文件
        try (FileOutputStream fos = new FileOutputStream(outputExcelPath)) {
            workbook.write(fos);
        }
        workbook.close();

        if (!allNotFoundEdgeIds.isEmpty()) {
            System.out.println("警告: 共有 " + allNotFoundEdgeIds.size() + " 条分支ID未在edges中找到");
        }
        System.out.println("共导出 " + solutionList.size() + " 个方案到 " + outputExcelPath);
    }
}
