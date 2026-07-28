package HarnessPackOpti.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * GINE 模型推理引擎 —— 通过子进程调用本地 Python 脚本完成预测。
 *
 * 调用方式：Java 将特征数据序列化为 Big-Endian 二进制写入 Python 进程的 stdin，
 *          Python 推理后通过 stdout 返回 JSON。
 *
 * 部署要求：
 *   服务器上需安装 Python 3.9+ 及依赖库（torch, torch_geometric, numpy）。
 *   脚本目录需包含 predict.py、best_model.pt、normalization_params.json。
 *
 * 配置（通过 JVM 系统属性）：
 *   -Dpython.exe=/usr/bin/python3       Python 解释器路径（默认: python）
 *   -Dpredict.script.dir=/opt/predict   脚本&模型存放目录
 *       不设置时自动检测: 先找开发路径 src/main/resources/scripts，不存在则从 JAR 提取到临时目录
 */
public class GINEInferenceEngine {

    public static ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 可配置路径 ──
    private static final String PYTHON_EXE = System.getProperty("python.exe", "python");
    private static final String SCRIPT_DIR = resolveScriptDir();
    private static final String PREDICT_SCRIPT = SCRIPT_DIR + File.separator + "predict.py";

    // 单例延迟初始化（确保脚本目录已就绪）
    private static volatile boolean dirReady = false;

    // ── 脚本目录解析 ──
    private static String resolveScriptDir() {
        // 1) 系统属性显式指定
        String prop = System.getProperty("predict.script.dir");
        if (prop != null && !prop.trim().isEmpty()) {
            return prop.trim();
        }
        // 2) 开发环境：项目源码下的 resources/scripts
        File devDir = new File("src/main/resources/scripts");
        if (devDir.isDirectory()) {
            return devDir.getAbsolutePath();
        }
        // 3) 生产环境：从 JAR 中提取到临时目录
        return extractFromJar();
    }

    /**
     * 从 classpath 中提取 predict.py + 模型文件到临时目录，仅执行一次。
     */
    private static synchronized String extractFromJar() {
        try {
            Path tmpDir = Files.createTempDirectory("gine_predict_");
            String[] resources = {"predict.py", "best_model.pt", "normalization_params.json"};
            for (String name : resources) {
                try (InputStream in = GINEInferenceEngine.class.getClassLoader()
                        .getResourceAsStream("scripts/" + name)) {
                    if (in == null) {
                        throw new FileNotFoundException("classpath 中找不到 scripts/" + name
                                + "，请用 -Dpredict.script.dir 指定外部目录");
                    }
                    Files.copy(in, tmpDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tmpDir.toFile().deleteOnExit();
            String path = tmpDir.toAbsolutePath().toString();
            System.out.println("[GINE] 已从 JAR 提取脚本到: " + path);
            return path;
        } catch (IOException e) {
            throw new RuntimeException("无法从 JAR 提取预测脚本，请用 -Dpredict.script.dir 指定外部目录", e);
        }
    }

    private static void ensureDirReady() {
        if (!dirReady) {
            synchronized (GINEInferenceEngine.class) {
                if (!dirReady) {
                    // 触发一次 resolveScriptDir 确保提取完成
                    File f = new File(PREDICT_SCRIPT);
                    if (!f.exists()) {
                        throw new RuntimeException("预测脚本不存在: " + PREDICT_SCRIPT
                                + "，请检查 -Dpredict.script.dir 配置");
                    }
                    dirReady = true;
                }
            }
        }
    }

    // ── 推理入口 ──
    public float predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws Exception {
        ensureDirReady();

        int nodeDim = matrix.length;
        int edgeDim = edgeIndex[0].length;
        int edgeFeatDim = edgeAttr[0].length;
        int nodeFeatDim = matrix[0].length;

        // 序列化为 Big-Endian 二进制（与 predict.py deserialize_binary 格式一致）
        int totalBytes = 8
                + (edgeDim * 2 * Integer.BYTES)
                + (edgeDim * edgeFeatDim * Float.BYTES)
                + (nodeDim * nodeFeatDim * Float.BYTES);

        ByteBuffer buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(nodeDim);
        buffer.putInt(edgeDim);

        // edge_index (int32)
        for (long[] row : edgeIndex) {
            for (long val : row) {
                buffer.putInt((int) val);
            }
        }
        // edge_attr (float32)
        for (float[] row : edgeAttr) {
            for (float val : row) {
                buffer.putFloat(val);
            }
        }
        // x (float32)
        for (float[] row : matrix) {
            for (float val : row) {
                buffer.putFloat(val);
            }
        }

        // 启动子进程
        ProcessBuilder pb = new ProcessBuilder(PYTHON_EXE, PREDICT_SCRIPT);
        pb.directory(new File(SCRIPT_DIR));
        pb.redirectErrorStream(false);

        Process process = pb.start();
        String stdout;
        String stderr;

        try {
            // 写入 stdin
            try (OutputStream out = process.getOutputStream()) {
                out.write(buffer.array());
                out.flush();
            }

            // 读取 stdout / stderr（带超时保护，单次预测应在 30 秒内完成）
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python 预测超时（30s），已强制终止");
            }

            stdout = readAll(process.getInputStream());
            stderr = readAll(process.getErrorStream());
        } finally {
            buffer.clear();
            process.destroyForcibly(); // 确保子进程被回收
        }

        int exitCode = process.exitValue();
        if (exitCode != 0 || (stderr != null && !stderr.trim().isEmpty())) {
            throw new RuntimeException("Python 预测失败 (exit=" + exitCode + "): " + stderr);
        }

        // 解析 JSON 响应: {"predicted_cost": xxx, "elapsed_ms": xxx}
        JsonNode jsonNode = MAPPER.readTree(stdout);
        if (jsonNode.has("error")) {
            throw new RuntimeException("Python 预测错误: " + jsonNode.get("error").asText());
        }

        return (float) jsonNode.get("predicted_cost").asDouble();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toString("UTF-8").trim();
    }
}
