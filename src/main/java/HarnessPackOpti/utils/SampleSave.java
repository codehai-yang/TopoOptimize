package HarnessPackOpti.utils;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SampleSave {

    /**
     * 样本写入文件
     * @param edgeIndex
     * @param edgeAttr
     * @param x
     */
    public static void saveSample(long[][] edgeIndex,float[][] edgeAttr,float[][] x,String filePath,Float totalPrice,Float totalLength,Float totalWeight){
        try (
                DataOutputStream dos = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(filePath,true)))) {

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
            dos.writeFloat(totalPrice);
            dos.writeFloat(totalLength);
            dos.writeFloat(totalWeight);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 判断回路干湿
     * @return
     */
    public static String getWaterParam(String name, List<Map<String, String>> maps) {
        for (Map<String, String> map : maps) {
            if (name.equals(map.get("pointName"))) {
                return map.get("waterParam");
            }
        }
        return null;
    }

    /**
     * 分支长度
     *
     * @param edges
     * @return
     */
    public static List<Float> getBranchLength( List<Map<String, Object>> edges) {
        List<Float> branchLengthList = new ArrayList<>();
        DecimalFormat df = new DecimalFormat("0.0000");
        for (Map<String, Object> edge : edges) {
            Float length = 0.0f;
            //参考长度
            String referenceLength = null;
            //用户确认的分支长度
            String verifyLength = null;
            //参考长度
            if (edge.get("referenceLength") != null) {
                referenceLength = String.valueOf(edge.get("referenceLength"));
            }
            //用户确认的分支长度
            if (edge.get("length") != null) {
                verifyLength = String.valueOf(edge.get("length"));
            }
            if (verifyLength != null && !verifyLength.isEmpty()) {
                length += Float.parseFloat(verifyLength);
            } else {
                if (!referenceLength.isEmpty()) {
                    length += Float.parseFloat(referenceLength);
                } else if ("C".equals(edge.get("topologyStatusCode")) || "S".equals(edge.get("topologyStatusCode"))) {
                    length += 200;
                } else {
                    //打断状态直接设0
                    length = 0f;
                }
            }
            branchLengthList.add(Float.parseFloat(df.format(length)));
        }
        return branchLengthList;
    }
}
