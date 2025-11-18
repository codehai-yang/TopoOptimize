package HarnessPackOpti.InfoRead;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ReadElecFixedLocationLibrary {
/*
获取用电器位置固化库中的 用电器位置信息
 */

    public static Map<String, List> getElecFixedLocationLibrary() {

        try {
            URL filePath = ReadElecFixedLocationLibrary.class.getResource("/ElecFixedLocationLibrary.xlsx");//这里的路径是以斜杠 / 开头的，表示从类路径的根开始查找资源
            InputStream inputStream = filePath.openStream();
            Workbook workbook = WorkbookFactory.create(inputStream);//URL转换为InputStream
            Sheet sheet = workbook.getSheetAt(0);


            //获取工作表最后一行的行数值，在没有空行的前提下，可视为总行数
            int totalRowNum = sheet.getLastRowNum();
            Row row = null;
            Map<String, List> nameMap = new HashMap<>();
            for (int i = 2; i <= totalRowNum; i++) {
                row = sheet.getRow(i);
                List<Map> mapList = new LinkedList<>();

                //读取用电器固化位置1与固化位置2中所适配的“左右驾”、"车辆类型"、"动力类型"信息，存入mapList
                String stringCellValue1 = row.getCell(1).getStringCellValue();
                if (!stringCellValue1.isEmpty() && !stringCellValue1.equals("/")) {
                    Map<String, String> stringMap = new HashMap<>();
                    stringMap.put("左右驾", row.getCell(2).getStringCellValue());
                    stringMap.put("车辆类型", row.getCell(3).getStringCellValue());
                    stringMap.put("动力类型", row.getCell(4).getStringCellValue());
                    Map<String, Map<String, String>> localMap = new HashMap<>();
                    localMap.put(stringCellValue1, stringMap);
                    mapList.add(localMap);
                }
                String stringCellValue2 = row.getCell(5).getStringCellValue();
                if (!stringCellValue2.isEmpty() && !stringCellValue2.equals("/")) {
                    Map<String, String> stringMap = new HashMap<>();
                    stringMap.put("左右驾", row.getCell(6).getStringCellValue());
                    stringMap.put("车辆类型", row.getCell(7).getStringCellValue());
                    stringMap.put("动力类型", row.getCell(8).getStringCellValue());
                    Map<String, Map<String, String>> localMap = new HashMap<>();
                    localMap.put(stringCellValue2, stringMap);
                    mapList.add(localMap);
                }
                if (mapList.size() > 0) {
                    nameMap.put(row.getCell(0).getStringCellValue(), mapList);
                }
            }
            return nameMap;


        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

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
