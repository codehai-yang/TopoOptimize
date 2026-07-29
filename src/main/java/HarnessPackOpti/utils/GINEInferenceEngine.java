package HarnessPackOpti.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * GINE 模型推理引擎 —— TCP Socket 连接池 + Python 服务器。
 *
 * 首次 predict() 调用时自动启动 Python TCP 服务，建立连接池，后续复用。
 * 连接池大小与 Java 线程数一致，每个线程独占一条连接，避免锁竞争。
 *
 * 配置（JVM 系统属性）：
 *   -Dpython.exe=F:\...\python.exe       Python 解释器路径（默认: python）
 *   -Dpredict.script.dir=/opt/predict    脚本目录（默认自动检测）
 *   -Dpredict.port=15000                 端口（默认 15000）
 *   -Dpredict.pool.size=10               连接池大小（默认取 HarnessBranchTopoOptimize.Threads）
 */
public class GINEInferenceEngine {

    public static ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PYTHON_EXE = System.getProperty("python.exe", "python");
    private static final String SCRIPT_DIR = resolveScriptDir();
    private static final String PREDICT_SCRIPT = SCRIPT_DIR + File.separator + "predict.py";
    private static final int PORT = Integer.getInteger("predict.port", 15000);
    private static final int POOL_SIZE = Integer.getInteger("predict.pool.size",
            HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize.Threads);

    private static volatile BlockingQueue<SocketConnection> pool;
    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();

    // ── 脚本目录解析 ──
    private static String resolveScriptDir() {
        String prop = System.getProperty("predict.script.dir");
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();
        File dev = new File("src/main/resources/scripts");
        if (dev.isDirectory()) return dev.getAbsolutePath();
        try {
            Path tmp = Files.createTempDirectory("gine_predict_");
            for (String name : new String[]{"predict.py", "best_model.pt", "normalization_params.json"}) {
                try (InputStream in = GINEInferenceEngine.class.getClassLoader()
                        .getResourceAsStream("scripts/" + name)) {
                    if (in == null) throw new FileNotFoundException("classpath 缺少 scripts/" + name);
                    Files.copy(in, tmp.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tmp.toFile().deleteOnExit();
            return tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("无法提取预测脚本，请用 -Dpredict.script.dir 指定目录", e);
        }
    }

    // ── 启动 Python 服务器（仅一次）──
    private static void initOnce() throws IOException {
        System.out.println("[GINE] 启动 Python 服务器...");
        ProcessBuilder pb = new ProcessBuilder(PYTHON_EXE, PREDICT_SCRIPT, "--port", String.valueOf(PORT));
        pb.directory(new File(SCRIPT_DIR));
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // JVM 退出时杀掉子进程，避免残留卡死
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (proc.isAlive()) proc.destroyForcibly();
        }));

        // 等待端口就绪（最长 120s，模型加载可能较慢）
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", PORT)) {
                Thread.sleep(200);
                break;
            } catch (IOException | InterruptedException e) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
        }

        // 建立连接池
        pool = new ArrayBlockingQueue<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.offer(new SocketConnection("127.0.0.1", PORT));
        }
        System.out.println("[GINE] 连接池就绪, 池大小=" + POOL_SIZE);
    }

    // ── 推理入口 ──
    public float predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws Exception {
        if (!initialized) {
            synchronized (INIT_LOCK) {
                if (!initialized) {
                    try {
                        initOnce();
                    } catch (IOException e) {
                        System.err.println("[GINE] Python 启动失败: " + e.getMessage());
                    }
                    initialized = true;
                }
            }
            if (pool == null) {
                throw new IOException("Python 推理服务未就绪");
            }
        }

        // 序列化
        int nodeDim = matrix.length, edgeDim = edgeIndex[0].length;
        int edgeFeatDim = edgeAttr[0].length, nodeFeatDim = matrix[0].length;
        int payloadBytes = 8 + (edgeDim * 2 * 4) + (edgeDim * edgeFeatDim * 4) + (nodeDim * nodeFeatDim * 4);
        ByteBuffer payload = ByteBuffer.allocate(payloadBytes).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(nodeDim); payload.putInt(edgeDim);
        for (long[] row : edgeIndex) for (long v : row) payload.putInt((int) v);
        for (float[] row : edgeAttr) for (float v : row) payload.putFloat(v);
        for (float[] row : matrix)   for (float v : row) payload.putFloat(v);

        ByteBuffer frame = ByteBuffer.allocate(4 + payloadBytes).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(payloadBytes);
        frame.put(payload.array());

        // 借一条连接
        SocketConnection conn = pool.take();
        try {
            return conn.predict(frame.array());
        } catch (IOException e) {
            conn.close();
            throw e;
        } finally {
            if (conn.isConnected()) {
                pool.offer(conn);
            } else {
                try { pool.offer(new SocketConnection("127.0.0.1", PORT)); }
                catch (IOException e) { System.err.println("[GINE] 重建连接失败: " + e.getMessage()); }
            }
        }
    }

    // ── Socket 连接 ──
    private static class SocketConnection {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        SocketConnection(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 4096));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 4096));
        }

        synchronized float predict(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n') break;
                sb.append((char) ch);
            }
            String line = sb.toString().trim();
            if (line.isEmpty()) throw new IOException("Python 返回空响应");
            JsonNode json = MAPPER.readTree(line);
            if (json.has("error")) throw new RuntimeException("预测错误: " + json.get("error").asText());
            return (float) json.get("predicted_cost").asDouble();
        }

        boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        void close() {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
