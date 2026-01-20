package HarnessPackOpti.utils;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;

public class ExportExcelUtils {
    public static void  exportExcel(Map<String, Object> systemCircuitInfo, Map<String,Object> elecRelatedCircuitInfo, Map<String, Object> caseInfo, Map<String, Object> loopdetails) throws IOException {
        Workbook workbook = null;
        try{
            //创建工作簿
            workbook = new XSSFWorkbook();
            Sheet systemSheet = workbook.createSheet("系统回路信息");
            Sheet elecSheet = workbook.createSheet("用电器回路信息");

            //创建样式
            CellStyle titleStyle = createTitleStyle(workbook);
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle subHeaderStyle = createSubHeaderStyle(workbook);

            //创建表头
            createHead(systemSheet, elecSheet, titleStyle, headerStyle, subHeaderStyle);
            // 填充实际数据
            populateSystemSheet(systemSheet, systemCircuitInfo, headerStyle, subHeaderStyle, caseInfo,loopdetails);
            populateElecSheet(elecSheet, elecRelatedCircuitInfo, headerStyle, subHeaderStyle,caseInfo,loopdetails);

            // 自动调整所有列的宽度
            for (int i = 0; i < systemSheet.getRow(0).getLastCellNum(); i++) {
                int maxWidth = 0;

                // 遍历该列的所有行
                for (int j = 0; j <= systemSheet.getLastRowNum(); j++) {
                    Row row = systemSheet.getRow(j);
                    if (row != null) {
                        Cell cell = row.getCell(i);
                        if (cell != null) {
                            String cellValue = cell.toString();
                            int cellWidth = cellValue.getBytes("GBK").length * 300 + 600;
                            maxWidth = Math.max(maxWidth, cellWidth);
                        }
                    }
                }

                // 设置最大宽度限制（防止过宽）
                maxWidth = Math.min(maxWidth, 255 * 256);
                systemSheet.setColumnWidth(i, maxWidth);
            }
            for (int i = 0; i < elecSheet.getRow(0).getLastCellNum(); i++) {
                int maxWidth = 0;

                // 遍历该列的所有行
                for (int j = 0; j <= elecSheet.getLastRowNum(); j++) {
                    Row row = elecSheet.getRow(j);
                    if (row != null) {
                        Cell cell = row.getCell(i);
                        if (cell != null) {
                            String cellValue = cell.toString();
                            int cellWidth = cellValue.getBytes("GBK").length * 300 + 600;
                            maxWidth = Math.max(maxWidth, cellWidth);
                        }
                    }
                }

                // 设置最大宽度限制（防止过宽）
                maxWidth = Math.min(maxWidth, 255 * 256);
                elecSheet.setColumnWidth(i, maxWidth);
            }
            String directory = "C:\\Users\\yang\\Desktop";
            //构建完整路径
            String filePath = Paths.get(directory, "系统与用电器信息.xlsx").toString();
            File file = new File(filePath);
            //写入文件
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            workbook.write(fileOutputStream);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            if (workbook != null) {
                workbook.close();
            }
        }

    }


    /**
     * 给指定行的指定列范围设置颜色
     * @param row 行对象
     * @param startIndex 起始列索引（从0开始）
     * @param endIndex 结束列索引（包含）
     * @param workbook 工作簿对象
     * @param backgroundColor 背景色
     */
    public static void colorRowRange(Row row, int startIndex, int endIndex, Workbook workbook, IndexedColors backgroundColor) {
        CellStyle colorStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        colorStyle.setFont(font);
        colorStyle.setAlignment(HorizontalAlignment.CENTER);
        colorStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        colorStyle.setWrapText(true);
        colorStyle.setFillForegroundColor(backgroundColor.getIndex());
        colorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        colorStyle.setBorderBottom(BorderStyle.THIN);
        colorStyle.setBorderTop(BorderStyle.THIN);
        colorStyle.setBorderRight(BorderStyle.THIN);
        colorStyle.setBorderLeft(BorderStyle.THIN);

        for (int i = startIndex; i <= endIndex; i++) {
            Cell cell = row.getCell(i);
            if (cell == null) {
                cell = row.createCell(i);
            }
            cell.setCellStyle(colorStyle);
        }
    }
    /**
     * 填充系统回路信息表
     * @param sheet 系统回路表
     * @param systemCircuitInfo 系统回路信息数据
     * @param headerStyle 表头样式
     * @param subHeaderStyle 子表头样式
     */
    public static void populateSystemSheet(Sheet sheet, Map<String, Object> systemCircuitInfo, CellStyle headerStyle, CellStyle subHeaderStyle,Map<String, Object> caseInfo,Map<String, Object> loopdetails) {
        int rowNum = 3; // 从第4行开始填充数据（前面3行是表头）
        for (String systemName : systemCircuitInfo.keySet()) {
            Map<String, Object> systemData = (Map<String, Object>) systemCircuitInfo.get(systemName);
            Map<String, Object> circuitInfo = (Map<String, Object>) systemData.get("circuitInfoIntergation");
            //系统相关回路信息
            List<String> circuitList = (List<String>) systemData.get("circuitList");

            Row row = sheet.createRow(rowNum++);
            int lastCellNum = sheet.getRow(1).getLastCellNum();
            // 填充基础信息（前8列）
            createCell(row, 0, caseInfo.get("方案名称").toString(), headerStyle); // 回路信息
            createCell(row, 1, systemName, headerStyle); // 所属系统
            createCell(row, 2, "", headerStyle); // 回路编号
            createCell(row, 3, "", headerStyle); // 起点用电器名称
            createCell(row, 4, "", headerStyle); // 终点用电器名称
            createCell(row, 5, "", headerStyle); // 回路信号名
            createCell(row, 6, "", headerStyle); // 导线选型
            createCell(row, 7, "", headerStyle); // 回路属性

            // 填充计算结果（后续列）
            int colIndex = 8; // 从第9列开始
            createCell(row, colIndex++, circuitInfo.get("总理论直径") != null ?
                    circuitInfo.get("总理论直径").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路总长度") != null ?
                    circuitInfo.get("回路总长度").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路总重量") != null ?
                    circuitInfo.get("回路总重量").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("总成本") != null ?
                    circuitInfo.get("总成本").toString() : "0", headerStyle);

            // 填充成本分解
            createCell(row, colIndex++, circuitInfo.get("回路导线总成本") != null ?
                    circuitInfo.get("回路导线总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("端子总成本") != null ?
                    circuitInfo.get("端子总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("连接器塑壳总成本") != null ?
                    circuitInfo.get("连接器塑壳总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("防水塞总成本") != null ?
                    circuitInfo.get("防水塞总成本").toString() : "0", headerStyle);

            // 填充量化指标
            createCell(row, colIndex++, circuitInfo.get("回路数量(打断后)") != null ?
                    circuitInfo.get("回路数量(打断后)").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路数量(打断前)") != null ?
                    circuitInfo.get("回路数量(打断前)").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路重量均值") != null ?
                    circuitInfo.get("回路重量均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路成本均值") != null ?
                    circuitInfo.get("回路成本均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路长度均值(打断前)") != null ?
                    circuitInfo.get("回路长度均值(打断前)").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断总次数") != null ?
                    circuitInfo.get("回路打断总次数").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断数量占比") != null ?
                    circuitInfo.get("回路打断数量占比").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断总成本") != null ?
                    circuitInfo.get("回路打断总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断成本代价均值") != null ?
                    circuitInfo.get("回路打断成本代价均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线数量") != null ?
                    circuitInfo.get("回路绕线数量").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线数量占比") != null ?
                    circuitInfo.get("回路绕线数量占比").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线长度总值") != null ?
                    circuitInfo.get("回路绕线长度总值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线长度均值") != null ?
                    circuitInfo.get("回路绕线长度均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路总数量") != null ?
                    circuitInfo.get("能量流绕路总数量").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路数量占比") != null ?
                    circuitInfo.get("能量流绕路数量占比").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路长度总值") != null ?
                    circuitInfo.get("能量流绕路长度总值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路长度均值") != null ?
                    circuitInfo.get("能量流绕路长度均值").toString() : "0", headerStyle);
            colorRowRange(row, 0, lastCellNum - 1, sheet.getWorkbook(), IndexedColors.YELLOW);
            //填充子级信息
            for (int i = 0; i < circuitList.size(); i++) {

                Row anotherRow = sheet.createRow(rowNum++);
                //回路id
                String id = circuitList.get(i);
                Map<String,Object> loopInfo = (Map<String,Object>)loopdetails.get(id);
                // 填充基础信息（前8列）
                createCell(anotherRow, 0, caseInfo.get("方案名称").toString(), headerStyle); // 回路信息
                createCell(anotherRow, 1, systemName, headerStyle); // 所属系统
                createCell(anotherRow, 2, loopInfo.get("回路编号").toString(), headerStyle); // 回路编号
                createCell(anotherRow, 3, loopInfo.get("起点用电器名称").toString(), headerStyle); // 起点用电器名称
                createCell(anotherRow, 4, loopInfo.get("终点用电器名称").toString(), headerStyle); // 终点用电器名称
                createCell(anotherRow, 5, loopInfo.get("回路信号名").toString(), headerStyle); // 回路信号名
                createCell(anotherRow, 6, loopInfo.get("导线选型").toString(), headerStyle); // 导线选型
                createCell(anotherRow, 7, loopInfo.get("回路属性") == null?"null":loopInfo.get("回路属性").toString(), headerStyle); // 回路属性

                // 填充计算结果（后续列）
                int cell =8;
                createCell(anotherRow, cell++, loopInfo.get("回路理论直径") != null ?
                        loopInfo.get("回路理论直径").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路长度") != null ?
                        loopInfo.get("回路长度").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路重量") != null ?
                        loopInfo.get("回路重量").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路总成本") != null ?
                        loopInfo.get("回路总成本").toString() : "0", headerStyle);

                // 填充成本分解
                createCell(anotherRow, cell++, loopInfo.get("回路导线成本") != null ?
                        loopInfo.get("回路导线成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("端子成本") != null ?
                        loopInfo.get("端子成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("连接器塑壳成本") != null ?
                        loopInfo.get("连接器塑壳成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("防水塞成本") != null ?
                        loopInfo.get("防水塞成本").toString() : "0", headerStyle);

                DecimalFormat percentFormat = new DecimalFormat("0.00%");
                double percentage = Double.parseDouble(loopInfo.get("回路打断次数").toString()) / 1.0;
                String percentageString = percentFormat.format(percentage);
                //回路打断总数量
                int breakNumber = Integer.parseInt(loopInfo.get("回路打断次数").toString()) + 1;
                //回路绕线数量
                double backNumber = Double.parseDouble(loopInfo.get("回路绕线长度").toString()) == 0 ? 0:1;
                double percentBack = backNumber / 1.0;
                String percentBackString = percentFormat.format(percentBack);
                // 填充量化指标
                createCell(anotherRow, cell++, breakNumber + "", headerStyle);
                createCell(anotherRow, cell++, "1", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路重量") != null ?
                        loopInfo.get("回路重量").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路总成本") != null ?
                        loopInfo.get("回路总成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路长度") != null ?
                        loopInfo.get("回路长度").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路打断次数") != null ?
                        loopInfo.get("回路打断次数").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, percentageString, headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路打断成本") != null ?
                        loopInfo.get("回路打断成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, Double.parseDouble(loopInfo.get("回路打断成本").toString()) / breakNumber + "", headerStyle);
                createCell(anotherRow, cell++, backNumber + "", headerStyle);
                createCell(anotherRow, cell++, percentBackString, headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路绕线长度") != null ?
                        loopInfo.get("回路绕线长度").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, backNumber == 0? 0 + "":(Double.parseDouble(loopInfo.get("回路绕线长度").toString()) / 1) + "", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路总数量") != null ?
                        loopInfo.get("能量流绕路总数量").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路数量占比") != null ?
                        loopInfo.get("能量流绕路数量占比").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路长度总值") != null ?
                        loopInfo.get("能量流绕路长度总值").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路长度均值") != null ?
                        loopInfo.get("能量流绕路长度均值").toString() : "0", headerStyle);
            }
        }
    }

    /**
     * 填充用电器回路信息表
     * @param sheet 用电器回路表
     * @param elecRelatedCircuitInfo 用电器回路信息数据
     * @param headerStyle 表头样式
     * @param subHeaderStyle 子表头样式
     */
    public static void populateElecSheet(Sheet sheet, Map<String, Object> elecRelatedCircuitInfo, CellStyle headerStyle, CellStyle subHeaderStyle,Map<String, Object> caseInfo,Map<String, Object> loopdetails) {
        int rowNum = 3; // 从第4行开始填充数据（前面3行是表头）

        for (String elecName : elecRelatedCircuitInfo.keySet()) {
            Map<String, Object> elecData = (Map<String, Object>) elecRelatedCircuitInfo.get(elecName);
            Map<String, Object> circuitInfo = (Map<String, Object>) elecData.get("circuitInfoIntergation");
            List<String> circuitList = (List<String>) elecData.get("circuitList");

            Row row = sheet.createRow(rowNum++);
            int lastCellNum = sheet.getRow(1).getLastCellNum();
            // 填充基础信息（前9列）
            createCell(row, 0, caseInfo.get("方案名称").toString(), headerStyle); // 方案名称
            createCell(row, 1, elecName, headerStyle); // 相关用电器
            createCell(row, 2, "", headerStyle); // 相关用电器接口编号
            createCell(row, 3, "", headerStyle); // 回路编号
            createCell(row, 4, "", headerStyle); // 起点用电器名称
            createCell(row, 5, "", headerStyle); // 终点用电器名称
            createCell(row, 6, "", headerStyle); // 回路信号名
            createCell(row, 7, "", headerStyle); // 导线选型
            createCell(row, 8, "", headerStyle); // 回路属性

            // 填充计算结果（后续列）
            int colIndex = 9; // 从第10列开始
            createCell(row, colIndex++, circuitInfo.get("总理论直径") != null ?
                    circuitInfo.get("总理论直径").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路总长度") != null ?
                    circuitInfo.get("回路总长度").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路总重量") != null ?
                    circuitInfo.get("回路总重量").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("总成本") != null ?
                    circuitInfo.get("总成本").toString() : "0", headerStyle);

            // 填充成本分解
            createCell(row, colIndex++, circuitInfo.get("回路导线总成本") != null ?
                    circuitInfo.get("回路导线总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("端子总成本") != null ?
                    circuitInfo.get("端子总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("连接器塑壳总成本") != null ?
                    circuitInfo.get("连接器塑壳总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("防水塞总成本") != null ?
                    circuitInfo.get("防水塞总成本").toString() : "0", headerStyle);

            // 填充量化指标
            createCell(row, colIndex++, circuitInfo.get("回路数量(打断后)") != null ?
                    circuitInfo.get("回路数量(打断后)").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路数量(打断前)") != null ?
                    circuitInfo.get("回路数量(打断前)").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路重量均值") != null ?
                    circuitInfo.get("回路重量均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路成本均值") != null ?
                    circuitInfo.get("回路成本均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路长度均值(打断前)") != null ?
                    circuitInfo.get("回路长度均值(打断前)").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断总次数") != null ?
                    circuitInfo.get("回路打断总次数").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断数量占比") != null ?
                    circuitInfo.get("回路打断数量占比").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断总成本") != null ?
                    circuitInfo.get("回路打断总成本").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路打断成本代价均值") != null ?
                    circuitInfo.get("回路打断成本代价均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线数量") != null ?
                    circuitInfo.get("回路绕线数量").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线数量占比") != null ?
                    circuitInfo.get("回路绕线数量占比").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线长度总值") != null ?
                    circuitInfo.get("回路绕线长度总值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("回路绕线长度均值") != null ?
                    circuitInfo.get("回路绕线长度均值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路总数量") != null ?
                    circuitInfo.get("能量流绕路总数量").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路数量占比") != null ?
                    circuitInfo.get("能量流绕路数量占比").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路长度总值") != null ?
                    circuitInfo.get("能量流绕路长度总值").toString() : "0", headerStyle);
            createCell(row, colIndex++, circuitInfo.get("能量流绕路长度均值") != null ?
                    circuitInfo.get("能量流绕路长度均值").toString() : "0", headerStyle);
            colorRowRange(row, 0, lastCellNum-1, sheet.getWorkbook(), IndexedColors.YELLOW);
            //填充子级信息
            for (int i = 0; i < circuitList.size(); i++) {

                Row anotherRow = sheet.createRow(rowNum++);
                //回路id
                String id = circuitList.get(i);
                Map<String,Object> loopInfo = (Map<String,Object>)loopdetails.get(id);
                // 填充基础信息（前8列）
                createCell(anotherRow, 0, caseInfo.get("方案名称").toString(), headerStyle); // 回路信息
                createCell(anotherRow, 1, elecName, headerStyle); // 所属系统
                createCell(anotherRow, 2, loopInfo.get("回路起点用电器接口编号") + "/" + loopInfo.get("回路终点用电器接口编号"), headerStyle); // 回路编号
                createCell(anotherRow, 3, loopInfo.get("回路编号").toString(), headerStyle); // 回路编号
                createCell(anotherRow, 4, loopInfo.get("起点用电器名称").toString(), headerStyle); // 起点用电器名称
                createCell(anotherRow, 5, loopInfo.get("终点用电器名称").toString(), headerStyle); // 终点用电器名称
                createCell(anotherRow, 6, loopInfo.get("回路信号名").toString(), headerStyle); // 回路信号名
                createCell(anotherRow, 7, loopInfo.get("导线选型").toString(), headerStyle); // 导线选型
                createCell(anotherRow, 8, loopInfo.get("回路属性") == null ? "null" : loopInfo.get("回路属性").toString(), headerStyle); // 回路属性

                // 填充计算结果（后续列）
                int cell =9;
                createCell(anotherRow, cell++, loopInfo.get("回路理论直径") != null ?
                        loopInfo.get("回路理论直径").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路长度") != null ?
                        loopInfo.get("回路长度").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路重量") != null ?
                        loopInfo.get("回路重量").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路总成本") != null ?
                        loopInfo.get("回路总成本").toString() : "0", headerStyle);

                // 填充成本分解
                createCell(anotherRow, cell++, loopInfo.get("回路导线成本") != null ?
                        loopInfo.get("回路导线成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("端子成本") != null ?
                        loopInfo.get("端子成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("连接器塑壳成本") != null ?
                        loopInfo.get("连接器塑壳成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("防水塞成本") != null ?
                        loopInfo.get("防水塞成本").toString() : "0", headerStyle);

                DecimalFormat percentFormat = new DecimalFormat("0.00%");
                double percentage = Double.parseDouble(loopInfo.get("回路打断次数").toString()) / 1.0;
                String percentageString = percentFormat.format(percentage);
                //回路打断总数量
                int breakNumber = Integer.parseInt(loopInfo.get("回路打断次数").toString()) + 1;
                //回路绕线数量
                double backNumber = Double.parseDouble(loopInfo.get("回路绕线长度").toString()) == 0 ? 0:1;
                double percentBack = backNumber / 1.0;
                String percentBackString = percentFormat.format(percentBack);
                // 填充量化指标
                createCell(anotherRow, cell++, breakNumber + "", headerStyle);
                createCell(anotherRow, cell++, "1", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路重量") != null ?
                        loopInfo.get("回路重量").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路总成本") != null ?
                        loopInfo.get("回路总成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路长度") != null ?
                        loopInfo.get("回路长度").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路打断次数") != null ?
                        loopInfo.get("回路打断次数").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, percentageString, headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路打断成本") != null ?
                        loopInfo.get("回路打断成本").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, Double.parseDouble(loopInfo.get("回路打断成本").toString()) / breakNumber + "", headerStyle);
                createCell(anotherRow, cell++, backNumber + "", headerStyle);
                createCell(anotherRow, cell++, percentBackString, headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("回路绕线长度") != null ?
                        loopInfo.get("回路绕线长度").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, backNumber == 0? 0 + "":(Double.parseDouble(loopInfo.get("回路绕线长度").toString()) / 1) + "", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路总数量") != null ?
                        loopInfo.get("能量流绕路总数量").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路数量占比") != null ?
                        loopInfo.get("能量流绕路数量占比").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路长度总值") != null ?
                        loopInfo.get("能量流绕路长度总值").toString() : "0", headerStyle);
                createCell(anotherRow, cell++, loopInfo.get("能量流绕路长度均值") != null ?
                        loopInfo.get("能量流绕路长度均值").toString() : "0", headerStyle);
            }
        }
    }

    /**
     * 创建单元格并设置值
     * @param row 行对象
     * @param columnIndex 列索引
     * @param value 值
     * @param style 样式
     */
    private static void createCell(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        if (value != null) {
            try {
                // 尝试将字符串转换为数值
                double numericValue = Double.parseDouble(value);
                cell.setCellValue(numericValue);
            } catch (NumberFormatException e) {
                // 如果不是数值格式，则作为字符串处理
                cell.setCellValue(value);
            }
        } else {
            cell.setCellValue("");
        }
        cell.setCellStyle(style);
    }

    //表头前半部分
    public static void createHead(Sheet systemSheet, Sheet elecSheet,CellStyle titleStyle,
                                  CellStyle headerStyle, CellStyle subHeaderStyl) {
        Sheet[] sheets = new Sheet[]{systemSheet, elecSheet};
        for (int j = 0; j < sheets.length; j++) {
            if(j == 0){
                //第0行表头(系统回路表头)
                Row titleRow = systemSheet.createRow(0);
                Row row2 = systemSheet.createRow(1);
                Row row3 = systemSheet.createRow(2);
                for (int i = 0; i < 8; i++) {
                    Cell cell = titleRow.createCell(i);
                    if (i == 0) {
                        cell.setCellValue("回路信息");
                        cell.setCellStyle(titleStyle);
                    } else {
                        cell.setCellStyle(titleStyle);
                    }
                    switch (i){
                        case 0:
                            Cell cell2 = row2.createCell(i);
                            Cell cell3 = row3.createCell(i);
                            cell2.setCellValue("方案名称");
                            cell2.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 1:
                            Cell cell4 = row2.createCell(i);
                            Cell cell5 = row3.createCell(i);
                            cell4.setCellValue("所属系统");
                            cell4.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 2:
                            Cell cell6 = row2.createCell(i);
                            Cell cell7 = row3.createCell(i);
                            cell6.setCellValue("回路编号");
                            cell6.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 3:
                            Cell cell8 = row2.createCell(i);
                            Cell cell9 = row3.createCell(i);
                            cell8.setCellValue("起点用电器名称");
                            cell8.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 4:
                            Cell cell10 = row2.createCell(i);
                            Cell cell11 = row3.createCell(i);
                            cell10.setCellStyle(headerStyle);
                            cell10.setCellValue("终点用电器名称");
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 5:
                            Cell cell12 = row2.createCell(i);
                            Cell cell13 = row3.createCell(i);
                            cell12.setCellValue("回路信号名");
                            cell12.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 6:
                            Cell cell14 = row2.createCell(i);
                            Cell cell15 = row3.createCell(i);
                            cell14.setCellValue("导线选型");
                            cell14.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 7:
                            Cell cell16 = row2.createCell(i);
                            Cell cell17 = row3.createCell(i);
                            cell16.setCellValue("回路属性");
                            cell16.setCellStyle(headerStyle);
                            systemSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        default:
                            break;
                    }
                }
                //合并第0行
                systemSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

            }else {
                //第0行表头(系统回路表头)
                Row elecTitleRow = elecSheet.createRow(0);
                Row row2 = elecSheet.createRow(1);
                Row row3 = elecSheet.createRow(2);
                for (int i = 0; i < 9; i++) {
                    Cell cell = elecTitleRow.createCell(i);
                    if (i == 0) {
                        cell.setCellValue("回路信息");
                        cell.setCellStyle(titleStyle);
                    }else {
                        cell.setCellStyle(titleStyle);
                    }
                    switch (i){
                        case 0:
                            Cell cell2 = row2.createCell(i);
                            Cell cell3 = row3.createCell(i);
                            cell2.setCellValue("方案名称");
                            cell2.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 1:
                            Cell cell4 = row2.createCell(i);
                            Cell cell5 = row3.createCell(i);
                            cell4.setCellValue("相关用电器");
                            cell4.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 2:
                            Cell cell6 = row2.createCell(i);
                            Cell cell7 = row3.createCell(i);
                            cell6.setCellValue("相关用电器接口编号");
                            cell6.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 3:
                            Cell cell8 = row2.createCell(i);
                            Cell cell9 = row3.createCell(i);
                            cell8.setCellValue("回路编号");
                            cell8.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 4:
                            Cell cell10 = row2.createCell(i);
                            Cell cell11 = row3.createCell(i);
                            cell10.setCellValue("起点用电器名称");
                            cell10.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 5:
                            Cell cell12 = row2.createCell(i);
                            Cell cell13 = row3.createCell(i);
                            cell12.setCellValue("起点用电器名称");
                            cell12.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 6:
                            Cell cell14 = row2.createCell(i);
                            Cell cell15 = row3.createCell(i);
                            cell14.setCellValue("回路信号名");
                            cell14.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 7:
                            Cell cell16 = row2.createCell(i);
                            Cell cell17 = row3.createCell(i);
                            cell16.setCellValue("导线选型");
                            cell16.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        case 8:
                            Cell cell18 = row2.createCell(i);
                            Cell cell19 = row3.createCell(i);
                            cell18.setCellValue("回路属性");
                            cell18.setCellStyle(headerStyle);
                            elecSheet.addMergedRegion(new CellRangeAddress(1, 2, i, i));
                            break;
                        default:
                            break;
                    }
                }
                //合并第0行
                elecSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 8));

            }
        }
        //制作后面的表头(两个sheet字段一样)
        createLastHeader(systemSheet, elecSheet, headerStyle,  subHeaderStyl, titleStyle);
    }

    /**
     * 制作后面的表头
     * @param systemSheet
     * @param elecSheet
     */
    public static void createLastHeader(Sheet systemSheet, Sheet elecSheet,CellStyle headerStyle, CellStyle subHeaderStyl,CellStyle titleStyle){
        Sheet[] sheets = new Sheet[]{systemSheet, elecSheet};
        for (int i = 0; i < sheets.length; i++) {
            Sheet caseSheet = sheets[i];
            Row row = caseSheet.getRow(0);
            int lastCellNum = row.getLastCellNum() - 1;
            Row row1 = caseSheet.getRow(1);
            int lastCellNum1 = row1.getLastCellNum() - 1;
            Row row2 = caseSheet.getRow(2);
            int lastCellNum2 = row2.getLastCellNum() - 1;
            //后半部分表头
            for (int j = 1; j <= 25; j++) {
                Cell cell = row.createCell(lastCellNum + j);
                Cell cell2 = row1.createCell(lastCellNum1 + j);
                Cell cell3 = row2.createCell(lastCellNum2 + j);
                switch (j){
                    case 1:
                        cell2.setCellValue("理论直径(毫米)");
                        cell.setCellValue("计算结果");
                        cell2.setCellStyle(headerStyle);
                        cell.setCellStyle(titleStyle);
                        caseSheet.addMergedRegion(new CellRangeAddress(1, 2, lastCellNum + j, lastCellNum + j));
                        break;
                    case 2:
                        cell2.setCellValue("回路总长度(米)");
                        cell2.setCellStyle(headerStyle);
                        caseSheet.addMergedRegion(new CellRangeAddress(1, 2, lastCellNum + j, lastCellNum + j));
                        break;
                    case 3:
                        cell2.setCellValue("回路总重量(克)");
                        cell2.setCellStyle(headerStyle);
                        caseSheet.addMergedRegion(new CellRangeAddress(1, 2, lastCellNum + j, lastCellNum + j));
                        break;
                    case 4:
                        cell2.setCellValue("回路总成本(元)");
                        cell2.setCellStyle(headerStyle);
                        caseSheet.addMergedRegion(new CellRangeAddress(1, 2, lastCellNum + j, lastCellNum + j));
                        break;
                    case 5:
                        cell2.setCellValue("回路成本分解");
                        cell3.setCellValue("导线成本(元)");
                        cell2.setCellStyle(headerStyle);
                        cell3.setCellStyle(headerStyle);
                        break;
                    case 6:
                        cell3.setCellValue("端子成本(元)");
                        cell3.setCellStyle(headerStyle);
                        break;
                    case 7:
                        cell3.setCellValue("连接器塑壳成本(元)");
                        cell3.setCellStyle(headerStyle);
                        break;
                    case 8:
                        cell3.setCellValue("防水赛成本(元)");
                        cell3.setCellStyle(headerStyle);
                        caseSheet.addMergedRegion(new CellRangeAddress(0, 0, lastCellNum + 1, lastCellNum + j));
                        caseSheet.addMergedRegion(new CellRangeAddress(1, 1, lastCellNum + 5, lastCellNum + j));
                        break;
                    case 9:
                        cell.setCellValue("量化指标");
                        cell.setCellStyle(titleStyle);
                        cell2.setCellValue("回路数量-A类(根)");
                        cell3.setCellValue("按照回路打断后计算");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 10:
                        cell2.setCellValue("回路数量-B类(根)");
                        cell3.setCellValue("按照回路打断前计算");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 11:
                        cell2.setCellValue("回路重量均值(克/根)");
                        cell3.setCellValue("回路重量总值+回路数量-B类");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 12:
                        cell2.setCellValue("回路成本均值(元/根)");
                        cell3.setCellValue("回路成本总值+回路数量-B类");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 13:
                        cell2.setCellValue("回路长度均值(米/根)");
                        cell3.setCellValue("回路长度总值+回路数量-B类");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 14:
                        cell2.setCellValue("回路打断总次数(根)");
                        cell3.setCellValue("回路数量-B类的所有回路共计被打断了多少次");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 15:
                        cell2.setCellValue("回路打断数量占比(百分比)");
                        cell3.setCellValue("回路打断总次数-回路数量-B类");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 16:
                        cell2.setCellValue("回路打断成本总值(元)");
                        cell3.setCellValue("inline的端子/塑壳/密封塞");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 17:
                        cell2.setCellValue("回路打断成本均值(元/根)");
                        cell3.setCellValue("回路打断成本总值+回路打断总数量");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 18:
                        cell2.setCellValue("回路绕线总数量(根)");
                        cell3.setCellValue("回路数量-B类的回路中有几根绕线");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 19:
                        cell2.setCellValue("回路绕线数量占比(百分比)");
                        cell3.setCellValue("回路绕线总数量+回路数量-B类");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 20:
                        cell2.setCellValue("回路绕线长度总值(米)");
                        cell3.setCellValue("回路绕线后长度-回路不绕线长度");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 21:
                        cell2.setCellValue("回路绕线长度均值(米)");
                        cell3.setCellValue("回路绕线长度总值+回路绕线总数量");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 22:
                        cell2.setCellValue("能量流绕线总数量(根)");
                        cell3.setCellValue("回路数量-B类的回路中有几根能量流绕路");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 23:
                        cell2.setCellValue("能量流绕线数量占比(百分比)");
                        cell3.setCellValue("能量流绕路总数量+回路数量-B类");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 24:
                        cell2.setCellValue("能量流绕路长度总值(米)");
                        cell3.setCellValue("能量流绕路后长度-能量流不绕路长度");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        break;
                    case 25:
                        cell2.setCellValue("能量流绕路长度均值(米/根)");
                        cell3.setCellValue("能量流绕路长度总值+能量流绕路总数量");
                        cell3.setCellStyle(headerStyle);
                        cell2.setCellStyle(headerStyle);
                        caseSheet.addMergedRegion(new CellRangeAddress(0, 0, lastCellNum + 9, lastCellNum + j));
                        break;
                    default:
                        break;
                }
            }

        }
    }
    /**
     * 创建标题样式
     */
    private static CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
//        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    /**
     * 创建表头样式
     */
    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    /**
     * 创建子表头样式
     */
    private static CellStyle createSubHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }
}
