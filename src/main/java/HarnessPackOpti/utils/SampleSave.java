package HarnessPackOpti.utils;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;


/**
 * 用于与python端模型调试，验证一致性(适用于ONNX)
 */
public class SampleSave {
    public static Map<Double,Double> modelPredictMap = new ConcurrentHashMap<>();
    public static void saveSample(long[][] edgeIndex,float[][] edgeAttr,float[][] x){
        try (
                DataOutputStream dos = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream("F:\\office\\pythonProjects\\GINEModel\\javaTest\\predict_input.bin",true)))) {

            // 1. 写入 edge_index [2, 211]，int32大端
            for (int i = 0; i < edgeIndex.length; i++) {
                for (int j = 0; j < edgeIndex[i].length; j++) {
                    dos.writeInt((int) edgeIndex[i][j]);
                }
            }

            // 2. 写入 edge_attr [211, 4]，float32大端
            for (int i = 0; i < edgeAttr.length; i++) {
                for (int j = 0; j < edgeAttr[i].length; j++) {
                    dos.writeFloat(edgeAttr[i][j]);
                }
            }

            // 3. 写入 x [175, 176]，float32大端
            for (int i = 0; i < x.length; i++) {
                for (int j = 0; j < x[i].length; j++) {
                    dos.writeFloat(x[i][j]);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 将预测结果 Map 写入 Excel 文件
     * @param predictMap key为预测值, value为真实值
     * @param filePath 输出文件路径
     */
    public static void writePredictionsToExcel(Map<Double, Double> predictMap, String filePath) {
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("预测结果对比");

        // 创建表头样式
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        // 创建表头行
        Row headerRow = sheet.createRow(0);
        String[] headers = {"预测值 (Predicted)", "真实值 (Actual)", "差值 (Difference)"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // 填充数据
        int rowNum = 1;
        // 使用 entrySet 遍历，性能更好
        for (Map.Entry<Double, Double> entry : predictMap.entrySet()) {
            Row row = sheet.createRow(rowNum++);

            double predicted = entry.getKey();
            double actual = entry.getValue();
            double difference = predicted - actual;

            row.createCell(0).setCellValue(predicted);
            row.createCell(1).setCellValue(actual);
            row.createCell(2).setCellValue(difference);
        }

        // 自动调整列宽
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // 写入文件
        try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
            workbook.write(fileOut);
            System.out.println("Excel 文件已成功保存至: " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                workbook.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
