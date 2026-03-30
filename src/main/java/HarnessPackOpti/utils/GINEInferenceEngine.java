package HarnessPackOpti.utils;

import HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class GINEInferenceEngine implements AutoCloseable {

    private static GINEInferenceEngine instance;
    private final OrtEnvironment env;
    private final OrtSession session;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))   // 连接超时
            .executor(Executors.newFixedThreadPool(11)) // 和Python端worker数对应
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREDICT_URL = "http://localhost:5000/predict";

    private GINEInferenceEngine(String modelPath) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(modelPath, new OrtSession.SessionOptions());
        System.out.println("ONNX 模型加载完成");
    }

    public static GINEInferenceEngine getInstance(String modelPath) throws OrtException {
        if (instance == null) {
            instance = new GINEInferenceEngine(modelPath);
        }
        return instance;
    }

    public float predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("x", matrix);
        body.put("edge_index", edgeIndex);
        body.put("edge_attr", edgeAttr);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PREDICT_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))         // 请求超时
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body)
                ))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            throw new RuntimeException("预测失败: " + response.body());
        }
        Map<String, Object> result = MAPPER.readValue(response.body(), Map.class);
        return ((Number) result.get("predicted_cost")).floatValue();
    }

    @Override
    public void close() throws OrtException {
        session.close();
        env.close();
    }

    public OrtEnvironment getEnv() {
        return env;
    }
}