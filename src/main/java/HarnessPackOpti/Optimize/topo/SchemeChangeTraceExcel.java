package HarnessPackOpti.Optimize.topo;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 方案状态变更追踪 Excel 写出工具。
 * 每个最终方案占 3 列:精确前 / 精确后 / 绕线后,方案之间留 2 列空隙;行是分支。
 * 顶部附加:入口索引(500中、100中)、总成本/总重量/总长度,便于核对。
 * 输入的 finalSchemes 取自 windingOptimize 的返回,需包含:
 *   - serviceableStatue: 绕线后分支状态
 *   - _windingInputServiceableStatue: 绕线前(即精确后)状态
 *   - _windingInputIndex: 0..99,精确后阶段的索引
 *   - _inputServiceableStatue: 精确前状态
 *   - _inputIndex: 0..499,精确前阶段的索引
 *   - 成本: {总成本, 总重量, 总长度} (绕线后)
 */
public class SchemeChangeTraceExcel {

    /** 每个方案占用的列数(精确前/精确后/绕线后) */
    private static final int COLS_PER_SCHEME = 3;
    /** 方案之间的空列数 */
    private static final int GAP_COLS = 2;

    /**
     * 写出 Excel 追踪表。返回写入的文件路径。
     */
    public static String writeTrace(String outputDir,
            String caseId,
            List<String> normList,
            List<Map<String, Object>> finalSchemes) throws Exception {
        if (normList == null || normList.isEmpty()) {
            System.out.println("[SchemeChangeTraceExcel] normList 为空,跳过 Excel 写出");
            return null;
        }
        if (finalSchemes == null || finalSchemes.isEmpty()) {
            System.out.println("[SchemeChangeTraceExcel] finalSchemes 为空,跳过 Excel 写出");
            return null;
        }

        int numSchemes = finalSchemes.size();
        int numBranches = normList.size();
        int totalCols = numSchemes * (COLS_PER_SCHEME + GAP_COLS) - GAP_COLS;

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("方案状态变更追踪");
            // 冻结前 6 行(标题 + 索引 + 表头)
            sheet.createFreezePane(0, 6);
            // 列宽自适应(状态列固定窄一些)
            for (int c = 0; c < totalCols; c++) {
                sheet.setColumnWidth(c, 1100);
            }
            // 左侧分支名列宽
            // 实际方案列从第 1 列开始(第 0 列留给"分支ID"),这里写 1..totalCols

            // 样式
            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle groupStyle = createGroupHeaderStyle(wb);
            CellStyle stageStyle = createStageHeaderStyle(wb);
            CellStyle dataStyle = createDataStyle(wb);
            CellStyle indexStyle = createIndexStyle(wb);
            CellStyle branchHeaderStyle = createBranchHeaderStyle(wb);

            // 行 0: 标题(合并所有列)
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(28);
            Cell titleCell = titleRow.createCell(0);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            titleCell.setCellValue("方案状态变更追踪表 - 案例 " + caseId + " (" + numSchemes + " 方案 x "
                    + numBranches + " 分支,生成于 " + stamp + ")");
            titleCell.setCellStyle(titleStyle);
            if (totalCols > 0) {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols));
            }

            // 行 1: 入口索引(500中) - 跨 3 列合并,显示该方案源自 500 中的哪个
            Row index500Row = sheet.createRow(1);
            index500Row.setHeightInPoints(18);
            for (int s = 0; s < numSchemes; s++) {
                int firstCol = s * (COLS_PER_SCHEME + GAP_COLS);
                int lastCol = firstCol + COLS_PER_SCHEME - 1;
                Cell c = index500Row.createCell(firstCol);
                Object idxObj = finalSchemes.get(s).get("_inputIndex");
                c.setCellValue("入口(500)#" + (idxObj == null ? "-" : idxObj.toString()));
                c.setCellStyle(indexStyle);
                if (lastCol > firstCol) {
                    sheet.addMergedRegion(new CellRangeAddress(1, 1, firstCol, lastCol));
                }
            }

            // 行 2: 入口索引(100中) - 跨 3 列合并
            Row index100Row = sheet.createRow(2);
            index100Row.setHeightInPoints(18);
            for (int s = 0; s < numSchemes; s++) {
                int firstCol = s * (COLS_PER_SCHEME + GAP_COLS);
                int lastCol = firstCol + COLS_PER_SCHEME - 1;
                Cell c = index100Row.createCell(firstCol);
                Object idxObj = finalSchemes.get(s).get("_windingInputIndex");
                c.setCellValue("入口(100)#" + (idxObj == null ? "-" : idxObj.toString()));
                c.setCellStyle(indexStyle);
                if (lastCol > firstCol) {
                    sheet.addMergedRegion(new CellRangeAddress(2, 2, firstCol, lastCol));
                }
            }

            // 行 3: 方案名分组(方案1 / 方案2 ...) 跨 3 列合并
            Row groupRow = sheet.createRow(3);
            groupRow.setHeightInPoints(22);
            for (int s = 0; s < numSchemes; s++) {
                int firstCol = s * (COLS_PER_SCHEME + GAP_COLS);
                int lastCol = firstCol + COLS_PER_SCHEME - 1;
                Cell c = groupRow.createCell(firstCol);
                c.setCellValue("方案" + (s + 1));
                c.setCellStyle(groupStyle);
                if (lastCol > firstCol) {
                    sheet.addMergedRegion(new CellRangeAddress(3, 3, firstCol, lastCol));
                }
            }

            // 行 4: 阶段名(精确前 / 精确后 / 绕线后)
            Row stageRow = sheet.createRow(4);
            stageRow.setHeightInPoints(20);
            // 第 0 列写"分支ID" 表头
            Cell branchIdHeader = stageRow.createCell(0);
            branchIdHeader.setCellValue("分支ID");
            branchIdHeader.setCellStyle(branchHeaderStyle);
            for (int s = 0; s < numSchemes; s++) {
                int baseCol = s * (COLS_PER_SCHEME + GAP_COLS);
                String[] stages = {"精确前", "精确后", "绕线后"};
                for (int k = 0; k < COLS_PER_SCHEME; k++) {
                    Cell c = stageRow.createCell(baseCol + k);
                    c.setCellValue(stages[k]);
                    c.setCellStyle(stageStyle);
                }
            }

            // 行 5: 状态数据(分支作为行)
            // 第 0 列是分支ID
            for (int b = 0; b < numBranches; b++) {
                Row dataRow = sheet.createRow(5 + b);
                dataRow.setHeightInPoints(16);
                Cell branchIdCell = dataRow.createCell(0);
                branchIdCell.setCellValue(normList.get(b));
                branchIdCell.setCellStyle(branchHeaderStyle);
                for (int s = 0; s < numSchemes; s++) {
                    int baseCol = s * (COLS_PER_SCHEME + GAP_COLS);
                    Map<String, Object> scheme = finalSchemes.get(s);
                    String preVal = statusAt(scheme, "_inputServiceableStatue", b);
                    String midVal = statusAt(scheme, "_windingInputServiceableStatue", b);
                    String postVal = statusAt(scheme, "serviceableStatue", b);
                    String[] vals = {preVal, midVal, postVal};
                    for (int k = 0; k < COLS_PER_SCHEME; k++) {
                        Cell c = dataRow.createCell(baseCol + k);
                        c.setCellValue(vals[k]);
                        c.setCellStyle(dataStyle);
                    }
                }
            }

            // 附加:总成本/总重量/总长度(3 个数据行,每个方案占 3 列:精确前/精确后/绕线后)
            int costRowStart = 5 + numBranches + 1; // 空一行
            // 成本行
            writeCostRow(sheet, costRowStart, "总成本", numSchemes, finalSchemes, "总成本", "元");
            writeCostRow(sheet, costRowStart + 1, "总重量", numSchemes, finalSchemes, "总重量", "kg");
            writeCostRow(sheet, costRowStart + 2, "总长度", numSchemes, finalSchemes, "总长度", "m");

            // 输出文件
            String fileName = "SchemeChangeTrace_" + caseId + "_" + stamp + ".xlsx";
            String outputPath = (outputDir == null || outputDir.isEmpty()
                    ? "src/main/resources/"
                    : (outputDir.endsWith("/") || outputDir.endsWith("\\") ? outputDir : outputDir + "/"))
                    + fileName;
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                wb.write(out);
            }
            System.out.println("[SchemeChangeTraceExcel] 已写出方案状态变更追踪表: " + outputPath
                    + " (方案=" + numSchemes + ", 分支=" + numBranches + ")");
            return outputPath;
        }
    }

    /**
     * 安全取出方案某阶段的某分支状态。
     */
    private static String statusAt(Map<String, Object> scheme, String field, int branchIndex) {
        Object listObj = scheme.get(field);
        if (!(listObj instanceof List)) {
            return "-";
        }
        List<?> list = (List<?>) listObj;
        if (branchIndex < 0 || branchIndex >= list.size()) {
            return "-";
        }
        Object v = list.get(branchIndex);
        return v == null ? "-" : v.toString();
    }

    /**
     * 写出成本/重量/长度行:每个方案 3 列,值取自精确前/精确后/绕线后 3 个数据源。
     * 精确前: 来自 _inputIndex 指向的 500 输入方案的成本 - 但这里我们没有保留该 500 输入,
     *         退化为从 finalScheme._inputServiceableStatue 推断不出成本,留 "-"。
     * 精确后: 取 _windingInputServiceableStatue 那个方案,本类不直接持有;
     *         简化处理: 写 "-" 占位,由调用方在扩展时补充。
     * 绕线后: 取 finalScheme.成本.
     */
    private static void writeCostRow(Sheet sheet, int rowIndex, String label,
            int numSchemes, List<Map<String, Object>> finalSchemes, String costKey, String unit) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(18);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label + "(" + unit + ")");
        labelCell.setCellStyle(createBranchHeaderStyle(sheet.getWorkbook()));
        for (int s = 0; s < numSchemes; s++) {
            int baseCol = s * (COLS_PER_SCHEME + GAP_COLS);
            // 绕线后: 来自 finalScheme.成本
            Object costObj = finalSchemes.get(s).get("成本");
            String postVal = "-";
            if (costObj instanceof Map) {
                Object v = ((Map<?, ?>) costObj).get(costKey);
                if (v != null) {
                    try {
                        double d = Double.parseDouble(v.toString());
                        postVal = String.format("%.2f", d);
                    } catch (NumberFormatException ignore) {
                        postVal = v.toString();
                    }
                }
            }
            for (int k = 0; k < COLS_PER_SCHEME; k++) {
                Cell c = row.createCell(baseCol + k);
                if (k == COLS_PER_SCHEME - 1) {
                    c.setCellValue(postVal);
                } else {
                    c.setCellValue("-");
                }
                c.setCellStyle(createDataStyle(sheet.getWorkbook()));
            }
        }
    }

    // ===== 样式 =====
    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyBorder(s);
        return s;
    }

    private static CellStyle createGroupHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyBorder(s);
        return s;
    }

    private static CellStyle createStageHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyBorder(s);
        return s;
    }

    private static CellStyle createIndexStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 10);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyBorder(s);
        return s;
    }

    private static CellStyle createDataStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorder(s);
        return s;
    }

    private static CellStyle createBranchHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyBorder(s);
        return s;
    }

    private static void applyBorder(CellStyle s) {
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
    }
}
