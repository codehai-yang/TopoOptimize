package HarnessPackOpti.utils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.util.HashMap;
import java.util.Map;

public class GINEInferenceEngine implements AutoCloseable {

    private static GINEInferenceEngine instance;
    private final OrtEnvironment env;
    private final OrtSession session;

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

    public float[][] predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws OrtException {
        OnnxTensor xTensor = OnnxTensor.createTensor(env, matrix);
        OnnxTensor edgeIndexTensor = OnnxTensor.createTensor(env, edgeIndex);
        OnnxTensor edgeAttrTensor = OnnxTensor.createTensor(env, edgeAttr);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("x", xTensor);
        inputs.put("edge_index", edgeIndexTensor);
        inputs.put("edge_attr", edgeAttrTensor);

        try (OrtSession.Result result = session.run(inputs)) {
            Object outputValue = result.get(0).getValue();
            if (outputValue instanceof float[][]) {
                return (float[][]) outputValue;
            } else if (outputValue instanceof Float) {
                float value = (Float) outputValue;
                return new float[][]{{value}};
            } else {
                throw new RuntimeException("Unsupported return type: " + outputValue.getClass().getName());
            }
        } finally {
            xTensor.close();
            edgeIndexTensor.close();
            edgeAttrTensor.close();
        }
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