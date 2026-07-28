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
 * GINE 模型推理引擎 —— TCP Socket 连接池 + Python 多线程服务器。
 *
 * Java 端维护一个到达 Python TCP 服务的 Socket 连接池，
 * 每个 predict() 调用从中借用一个连接，用完归还。
 * 连接池大小 = Java 线程数，实现真正并发。
 *
 * Python 端使用 socketserver.ThreadingMixIn + ThreadPoolExecutor，
 * 一个进程内模型只加载一次，多连接多线程并行推理。
 *
 * 配置（JVM 系统属性）：
 *   -Dpython.exe=F:\...\python.exe             Python 解释器（默认: python）
 *   -Dpredict.script.dir=/opt/predict          脚本目录
 *   -Dpredict.port=15000                       Python TCP 端口（默认: 15000）
 *   -Dpredict.pool.size=10                     连接池大小（默认: 10）
 */
public class GINEInferenceEngine {

    public static ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 配置 ──
    private static final String PYTHON_EXE = System.getProperty("python.exe", "python");
    private static final String SCRIPT_DIR = resolveScriptDir();
    private static final String PREDICT_SCRIPT = SCRIPT_DIR + File.separator + "predict.py";
    private static final int PORT = Integer.getInteger("predict.port", 15000);
    // 连接池大小：优先 -D 参数，否则跟 Java 线程数（默认 10）
    private static final int POOL_SIZE = Integer.getInteger("predict.pool.size",
            HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize.Threads);

    // ── 状态 ──
    private static volatile Process pythonProcess;
    private static volatile BlockingQueue<SocketConnection> pool;
    private static volatile boolean initialized = false;

    // ── 脚本目录 ──
    private static String resolveScriptDir() {
        String prop = System.getProperty("predict.script.dir");
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();
        File devDir = new File("src/main/resources/scripts");
        if (devDir.isDirectory()) return devDir.getAbsolutePath();
        return extractFromJar();
    }

    private static synchronized String extractFromJar() {
        try {
            Path tmpDir = Files.createTempDirectory("gine_predict_");
            String[] res = {"predict.py", "best_model.pt", "normalization_params.json"};
            for (String name : res) {
                try (InputStream in = GINEInferenceEngine.class.getClassLoader()
                        .getResourceAsStream("scripts/" + name)) {
                    if (in == null) throw new FileNotFoundException("classpath 缺少 scripts/" + name);
                    Files.copy(in, tmpDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tmpDir.toFile().deleteOnExit();
            System.out.println("[GINE] 已从 JAR 提取脚本到: " + tmpDir);
            return tmpDir.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("无法提取预测脚本", e);
        }
    }

    // ── 初始化（惰性，仅一次） ──
    private static synchronized void init() throws IOException {
        if (initialized) return;

        // 1) 启动 Python TCP 服务器
        System.out.println("[GINE] 启动 Python TCP 服务器...");
        ProcessBuilder pb = new ProcessBuilder(PYTHON_EXE, PREDICT_SCRIPT,
                "--port", String.valueOf(PORT));
        pb.directory(new File(SCRIPT_DIR));
        pb.redirectErrorStream(false);
        // 管道用于读取 Python 启动日志
        pythonProcess = pb.start();

        // 读取 Python stderr 中的 "模型就绪" 确认
        Thread logReader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(pythonProcess.getErrorStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[Python] " + line);
                }
            } catch (IOException ignored) { }
        });
        logReader.setDaemon(true);
        logReader.start();

        // 2) 等待服务就绪（轮询连接）
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (!pythonProcess.isAlive()) {
                throw new IOException("Python 进程已退出，请检查上方 [Python] 日志");
            }
            try (Socket s = new Socket("127.0.0.1", PORT)) {
                break; // 连接成功
            } catch (IOException e) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
        }

        // 3) 建立连接池
        pool = new ArrayBlockingQueue<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.offer(new SocketConnection("127.0.0.1", PORT));
        }
        initialized = true;

        // JVM 退出时清理
        Runtime.getRuntime().addShutdownHook(new Thread(GINEInferenceEngine::shutdown));

        System.out.println("[GINE] TCP 连接池就绪, 池大小=" + POOL_SIZE + ", 端口=" + PORT);
    }

    private static void shutdown() {
        initialized = false;
        if (pool != null) {
            for (SocketConnection conn : pool) {
                conn.sendExit();
                conn.close();
            }
        }
        if (pythonProcess != null) {
            pythonProcess.destroyForcibly();
        }
    }

    // ── 推理入口 ──
    public float predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws Exception {
        if (!initialized) init();

        int nodeDim = matrix.length, edgeDim = edgeIndex[0].length;
        int edgeFeatDim = edgeAttr[0].length, nodeFeatDim = matrix[0].length;

        // 序列化 payload
        int payloadBytes = 8 + (edgeDim * 2 * 4) + (edgeDim * edgeFeatDim * 4) + (nodeDim * nodeFeatDim * 4);
        ByteBuffer payload = ByteBuffer.allocate(payloadBytes).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(nodeDim);
        payload.putInt(edgeDim);
        for (long[] row : edgeIndex) for (long v : row) payload.putInt((int) v);
        for (float[] row : edgeAttr) for (float v : row) payload.putFloat(v);
        for (float[] row : matrix) for (float v : row) payload.putFloat(v);

        ByteBuffer frame = ByteBuffer.allocate(4 + payloadBytes).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(payloadBytes);
        frame.put(payload.array());

        // 从连接池借一条连接
        SocketConnection conn = pool.take();
        try {
            return conn.predict(frame.array());
        } catch (IOException e) {
            // 连接挂了就关掉，等池子里其他连接（或重建）
            conn.close();
            throw e;
        } finally {
            if (conn.isConnected()) {
                pool.offer(conn);
            } else {
                // 重建一条补回池子
                try {
                    pool.offer(new SocketConnection("127.0.0.1", PORT));
                } catch (IOException e) {
                    System.err.println("[GINE] 重建连接失败: " + e.getMessage());
                }
            }
        }
    }

    // ── Socket 连接封装 ──
    private static class SocketConnection {
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;

        SocketConnection(String host, int port) throws IOException {
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            this.socket.setSoTimeout(30000);
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 4096));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 4096));
        }

        synchronized float predict(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();

            // 读取一行响应
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n') break;
                sb.append((char) ch);
            }
            String line = sb.toString().trim();
            if (line.isEmpty()) {
                throw new IOException("Python 返回空响应，服务可能已退出");
            }
            try {
                JsonNode json = MAPPER.readTree(line);
                if (json.has("error")) {
                    throw new RuntimeException("预测错误: " + json.get("error").asText());
                }
                return (float) json.get("predicted_cost").asDouble();
            } catch (Exception e) {
                throw new IOException("解析响应失败: " + line, e);
            }
        }

        boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        void sendExit() {
            try {
                byte[] exit = {0, 0, 0, 0}; // payload_len=0
                out.write(exit);
                out.flush();
            } catch (IOException ignored) { }
        }

        void close() {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
