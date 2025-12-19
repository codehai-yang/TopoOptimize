package HarnessPackOpti.InfoRead;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class ReadWireInfoLibrary {

/*
* 读取excel文件
* */
    public  Map<String,Map<String,String >> getElecFixedLocationLibrary() {

        try {
//            String filePath = "./data/DataCoopWithEB/WireInfo.xlsx";
//
//            FileInputStream inputStream = new FileInputStream(filePath);
            URL filePath = ReadWireInfoLibrary.class.getResource("/WireInfo.xlsx");//这里的路径是以斜杠 / 开头的，表示从类路径的根开始查找资源
            InputStream inputStream = filePath.openStream();
            Workbook workbook = WorkbookFactory.create(inputStream);//URL转换为InputStream
            Sheet sheet = workbook.getSheetAt(0);


            //获取工作表最后一行的行数值，在没有空行的前提下，可视为总行数
            int totalRowNum = sheet.getLastRowNum();
            Row row = null;
            Map<String,Map<String,String>> dataMap = new HashMap<>();
            for (int i = 1; i <= totalRowNum; i++) {
                Map<String,String> map=new HashMap<>();
                row = sheet.getRow(i);
                map.put("导线物料价（元/米）",getNUmer(row.getCell(1)));
                map.put("导线单位商务价（元/米）",getNUmer(row.getCell(2)));
                map.put("导线单位重量（单位g/m）",getNUmer(row.getCell(3)));
                map.put("端子成本（元/端）",getNUmer(row.getCell(4)));
                map.put("焊点成本（元/m）",getNUmer(row.getCell(5)));
                map.put("导线打断成本（元/次）",getNUmer(row.getCell(6)));
                map.put("湿区成本补偿——连接器塑壳（元/端）",getNUmer(row.getCell(7)));
                map.put("湿区成本补偿——防水赛（元/个）",getNUmer(row.getCell(8)));
                map.put("导线外径（毫米）",getNUmer(row.getCell(9)));
                map.put("用电器负载工作电流<的数值（70℃时）（安培）",getNUmer(row.getCell(10)));
                map.put("电流匹配的供电回路截面积（平方毫米）",getNUmer(row.getCell(11)));
                map.put("电流匹配的供电回路单位重量（克/米）",getNUmer(row.getCell(12)));
                map.put("电流匹配的保险丝类型",getNUmer(row.getCell(13)));
                map.put("电流匹配的保险丝表面积（平方毫米）",getNUmer(row.getCell(14)));
                map.put("电流匹配的继电器类型",getNUmer(row.getCell(15)));
                map.put("电流匹配的继电器表面积（平方毫米）",getNUmer(row.getCell(16)));
                map.put("导线两端的连接器塑壳商务价（元/端）",getNUmer(row.getCell(11)));
                dataMap.put(row.getCell(0).getStringCellValue(),map);
            }
            return dataMap;


        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
    public  Map<String,Double> getElecBusinessPrice() {

        try {
//            String filePath = "./data/DataCoopWithEB/WireInfo.xlsx";
//            FileInputStream inputStream = new FileInputStream(filePath);
            URL filePath = ReadWireInfoLibrary.class.getResource("/WireInfo.xlsx");//这里的路径是以斜杠 / 开头的，表示从类路径的根开始查找资源
            InputStream inputStream = filePath.openStream();
            Workbook workbook = WorkbookFactory.create(inputStream);//URL转换为InputStream
            Sheet sheet = workbook.getSheetAt(1);

            //获取工作表最后一行的行数值，在没有空行的前提下，可视为总行数
            int totalRowNum = sheet.getLastRowNum();
            Row row = null;
            Map<String,Double> map=new HashMap<>();
            for (int i = 2; i <= totalRowNum; i++) {
                row = sheet.getRow(i);
                map.put(getNUmer(row.getCell(0)),Double.parseDouble(getNUmer(row.getCell(1))));
            }
            return map;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
    public static String getNUmer(Cell cell){
        String cellvalue = "";
        if (cell != null) {
            switch (cell.getCellType()) {
                case NUMERIC:
                    cellvalue = String.valueOf(cell.getNumericCellValue());
                    break;
                case FORMULA: {
                    cellvalue= String.valueOf(cell.getNumericCellValue());
                    break;
                }
                case STRING:
                    cellvalue = cell.getRichStringCellValue().getString();
                    break;
                default:
                    cellvalue = "";
            }
        } else {
            cellvalue = "";
        }
        return cellvalue;
    }


    private static Sheet getWorkbookSheet(String filePath, int sheetIndex) {
        InputStream fis = null;
        try {
            fis = new FileInputStream(filePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        Workbook book = null;
        if (filePath.endsWith(".xlsx")) {
            try {
                book = new XSSFWorkbook(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (filePath.endsWith(".xls")) {
            try {
                book = new HSSFWorkbook(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //获取工作表（第sheetIndex页）
        Sheet sheet = book.getSheetAt(sheetIndex);
        return sheet;
    }

}
