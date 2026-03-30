package HarnessPackOpti.utils;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class SampleSave {
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
}
