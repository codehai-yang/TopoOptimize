package HarnessPackOpti.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class GINEInferenceEngine {

    private static GINEInferenceEngine instance;
    public static ObjectMapper objectMapper = new ObjectMapper();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREDICT_URL = "http://localhost:5000/predict_binary";



    public float predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws Exception {
        HttpClientPoolManager.acquireRequestPermit();
        CloseableHttpClient httpClient = HttpClientPoolManager.getHttpClient();
        HttpPost httpPost = new HttpPost(PREDICT_URL);


        // 计算总字节数
        // edge_index: 2×211×4字节(long) = 1688字节
        // edge_attr:  211×4×4字节(float) = 3376字节
        // x:          175×176×4字节(float) = 123200字节
        int totalBytes = 1688 + 3376 + 123200;
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN);
        // 写入edge_index
        for (long[] row : edgeIndex) {
            for (long val : row) {
                buffer.putInt((int) val);  // 用int32省空间
            }
        }
        // 写入edge_attr
        for (float[] row : edgeAttr) {
            for (float val : row) {
                buffer.putFloat(val);
            }
        }
        // 写入x
        for (float[] row : matrix) {
            for (float val : row) {
                buffer.putFloat(val);
            }
        }
        httpPost.setEntity(new ByteArrayEntity(buffer.array()));
        httpPost.setHeader("Content-Type", "application/octet-stream");
        CloseableHttpResponse response = null;
        try {
            // 执行请求
            response = httpClient.execute(httpPost);

            // 检查 HTTP 状态码
            int statusCode = response.getStatusLine().getStatusCode();
            String result = EntityUtils.toString(response.getEntity());

            if (statusCode != 200) {
                throw new RuntimeException("Python服务返回错误 (HTTP " + statusCode + "): " + result);
            }

            // 解析 JSON 响应: {"predicted_cost": xxx, "elapsed_ms": xxx}
            com.fasterxml.jackson.databind.JsonNode jsonNode = MAPPER.readTree(result);
            float predictedCost = (float) jsonNode.get("predicted_cost").asDouble();

            return predictedCost;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            // 关键：确保关闭response，这将释放连接回连接池，而不是关闭连接
            if (response != null) {
                try {
                    response.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            buffer.clear();
            HttpClientPoolManager.releaseRequestPermit();
        }
    }
}