package HarnessPackOpti.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GINE 模型推理引擎 —— TCP Socket 连接池 + Python 服务器。
 *
 * 首次 predict() 调用时自动启动 Python TCP 服务，建立连接池，后续复用。
 * 连接池大小与 Java 线程数一致，每个线程独占一条连接，避免锁竞争。
 *
 * 配置（JVM 系统属性）：
 * -Dpython.exe=F:\...\python.exe Python 解释器路径（默认: python）
 * -Dpredict.script.dir=/opt/predict 脚本目录（默认自动检测）
 * -Dpredict.port=15000 端口（默认 15000）
 * -Dpredict.pool.size=10 连接池大小（默认取 HarnessBranchTopoOptimize.Threads）
 */
public class GINEInferenceEngine {

    public static ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PYTHON_EXE = System.getProperty("python.exe", "python");
    private static final String SCRIPT_DIR = resolveScriptDir();
    private static final String PREDICT_SCRIPT = SCRIPT_DIR + File.separator + "predict.py";
    private static final int PORT = Integer.getInteger("predict.port", 16000);
    private static final int POOL_SIZE = Integer.getInteger("predict.pool.size",
            HarnessPackOpti.Optimize.topo.HarnessBranchTopoOptimize.Threads);

    private static volatile BlockingQueue<SocketConnection> pool;
    private static volatile boolean initialized = false;
    private static volatile Process pythonProc;
    private static volatile String schemeName = "unknown";
    // 每个接口请求的日志文件名 (方案名_日期_毫秒.log)，通过协议传给 Python，不重启 Python
    private static volatile String logFileName = "unknown.log";
    private static final Object INIT_LOCK = new Object();

    // 设置方案名称 (仅记录，不重启 Python)
    public static void setSchemeName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            schemeName = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        }
    }

    // 设置当前接口请求的日志文件名，每次 predict 会把它附在 payload 前面传给 Python
    public static void setLogFileName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            logFileName = name.trim();
        }
    }

    // 连接池大小：AI 预测分批提交时，每批任务数不超过连接池容量，避免队列积压死锁
    public static int connectionPoolSize() {
        return POOL_SIZE;
    }

    // ── 脚本目录解析 ──
    private static String resolveScriptDir() {
        String prop = System.getProperty("predict.script.dir");
        if (prop != null && !prop.trim().isEmpty())
            return prop.trim();
        File dev = new File("src/main/resources/scripts");
        if (dev.isDirectory())
            return dev.getAbsolutePath();
        try {
            Path tmp = Files.createTempDirectory("gine_predict_");
            for (String name : new String[] { "predict.py", "best_model.pt", "normalization_params.json" }) {
                try (InputStream in = GINEInferenceEngine.class.getClassLoader()
                        .getResourceAsStream("scripts/" + name)) {
                    if (in == null)
                        throw new FileNotFoundException("classpath 缺少 scripts/" + name);
                    Files.copy(in, tmp.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tmp.toFile().deleteOnExit();
            return tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("无法提取预测脚本，请用 -Dpredict.script.dir 指定目录", e);
        }
    }

    // 杀掉占用指定端口的进程（Windows）
    private static void killProcessOnPort(int port) {
        try {
            Process netstat = new ProcessBuilder("cmd", "/c", "netstat -ano | findstr \":" + port + "\"").start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(netstat.getInputStream()));
            java.util.Set<String> pids = new java.util.HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 5 && parts[3].equals("LISTENING")) {
                    pids.add(parts[4]);
                }
            }
            netstat.waitFor();
            for (String pid : pids) {
                new ProcessBuilder("cmd", "/c", "taskkill /f /pid " + pid).start().waitFor();
                System.out.println("[GINE] killed orphan process on port " + port + ", pid=" + pid);
            }
        } catch (Exception e) {
            System.err.println("[GINE] killProcessOnPort fail: " + e.getMessage());
        }
    }

    // ── 启动 Python 服务器（仅一次）──
    private static void initOnce() throws IOException {
        // 杀掉旧 Python 进程，避免端口冲突残留
        if (pythonProc != null && pythonProc.isAlive()) {
            pythonProc.destroyForcibly();
            pythonProc = null;
        }
        // 杀掉占用端口的孤儿 Python 进程（上次 Java 崩溃残留的）
        killProcessOnPort(PORT);
        System.out.println("start run python");
        System.out.println("[GINE] python.exe    = " + PYTHON_EXE);
        System.out.println("[GINE] predict.py    = " + PREDICT_SCRIPT);
        System.out.println("[GINE] script.dir    = " + SCRIPT_DIR);
        System.out.println("[GINE] port          = " + PORT);
        System.out.println("[GINE] pool.size     = " + POOL_SIZE);
        // 校验 Python 解释器与脚本文件存在
        if (!new File(PYTHON_EXE).isAbsolute() && !PYTHON_EXE.contains(File.separator)) {
            System.out.println("[GINE] python.exe not found");
        }
        if (!new File(PREDICT_SCRIPT).isFile()) {
            throw new IOException("predict.py not found: " + PREDICT_SCRIPT
                    + "，请用 -Dpredict.script.dir=<dir> 指定脚本目录");
        }
        // 校验模型/参数文件（仅当脚本目录是 -Dpredict.script.dir 指定时需要校验；
        // 若是从 classpath 释放到 tmp 目录的，resolveScriptDir 已保证文件存在）
        File modelFile = new File(SCRIPT_DIR, "best_model.pt");
        File normFile = new File(SCRIPT_DIR, "normalization_params.json");
        if (!modelFile.isFile()) {
            throw new IOException("best_model.pt not found : " + modelFile.getAbsolutePath());
        }
        if (!normFile.isFile()) {
            throw new IOException("normalization_params.json not found : " + normFile.getAbsolutePath());
        }

        // 关键: 加 -u 让 Python stdout/stderr 无缓冲
        // 传日志目录给 Python，方案名/日志文件名通过每次 predict 的协议传递，不重启 Python
        String logBaseDir = System.getProperty("user.dir");
        File pythonLogsDir = new File(logBaseDir, "python_logs");
        if (!pythonLogsDir.isDirectory() && !pythonLogsDir.mkdirs()) {
            System.err.println("[GINE] warn: create python_logs dir fail: " + pythonLogsDir.getAbsolutePath());
        }
        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_EXE, "-u", PREDICT_SCRIPT,
                "--port", String.valueOf(PORT),
                "--log-dir", pythonLogsDir.getAbsolutePath());
        pb.directory(new File(SCRIPT_DIR));
        pb.redirectErrorStream(true);
        // 双保险: 也设置环境变量
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        // 不再 Java 端重定向输出到单个文件，由 Python 自行写到 python_logs/方案名+日期.log
        // Java 8 没有 Redirect.DISCARD，使用系统空设备丢弃 Python 标准输出(Python 端已自行重定向 fd 到日志文件)
        String osName = System.getProperty("os.name", "").toLowerCase();
        File discard = new File(osName.contains("win") ? "NUL" : "/dev/null");
        pb.redirectOutput(ProcessBuilder.Redirect.to(discard));

        final Process proc = pb.start();
        pythonProc = proc;

        // JVM 退出时杀掉子进程，避免残留卡死
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (proc.isAlive())
                proc.destroyForcibly();
        }));

        // 等待端口就绪（最长 120s，模型加载可能较慢）
        long deadline = System.currentTimeMillis() + 120_000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            // 若 Python 进程已退出，立即失败（避免空等 120s）
            if (!proc.isAlive()) {
                int code = proc.exitValue();
                throw new IOException("Python run fail");
            }
            try (Socket s = new Socket("127.0.0.1", PORT)) {
                Thread.sleep(200);
                ready = true;
                break;
            } catch (IOException | InterruptedException e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (!ready) {
            proc.destroyForcibly();
            throw new IOException("Python run fail");
        }

        // 二次校验: port check 通过后，Python 可能立即崩溃(如 import 失败、CUDA DLL 缺失)
        // 这里 sleep 300ms 再检查一次，避免误判已就绪
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        if (!proc.isAlive()) {
            int code = proc.exitValue();
            throw new IOException("Python server crashes immediately after startup");
        }

        // 建立连接池 (带重试，避免首次 connect 失败)
        pool = new ArrayBlockingQueue<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            boolean ok = false;
            for (int retry = 0; retry < 3 && !ok; retry++) {
                try {
                    pool.offer(new SocketConnection("127.0.0.1", PORT));
                    ok = true;
                } catch (IOException e) {
                    if (retry == 2) {
                        proc.destroyForcibly();
                        throw new IOException("Python Failed to establish connection pool");
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        System.out.println("Python connection pool ready=" + POOL_SIZE);
    }

    // ── 推理入口 ──
    public float predict(float[][] matrix, long[][] edgeIndex, float[][] edgeAttr) throws Exception {
        if (!initialized) {
            synchronized (INIT_LOCK) {
                if (!initialized) {
                    try {
                        initOnce();
                    } catch (IOException e) {
                        System.err.println("[GINE] Python run fail: " + e.getMessage());
                    }
                    initialized = true;
                }
            }
            if (pool == null) {
                throw new IOException("Python start fail");
            }
        }

        // 序列化
        int nodeDim = matrix.length, edgeDim = edgeIndex[0].length;
        int edgeFeatDim = edgeAttr[0].length, nodeFeatDim = matrix[0].length;
        // payload 结构: [logNameLen(4)][logName(UTF-8)][nodeDim(4)][edgeDim(4)][edgeIndex...][edgeAttr...][nodeFeat...]
        byte[] logNameBytes = logFileName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int payloadBytes = 4 + logNameBytes.length + 8
                + (edgeDim * 2 * 4) + (edgeDim * edgeFeatDim * 4) + (nodeDim * nodeFeatDim * 4);
        ByteBuffer payload = ByteBuffer.allocate(payloadBytes).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(logNameBytes.length);
        payload.put(logNameBytes);
        payload.putInt(nodeDim);
        payload.putInt(edgeDim);
        for (long[] row : edgeIndex)
            for (long v : row)
                payload.putInt((int) v);
        for (float[] row : edgeAttr)
            for (float v : row)
                payload.putFloat(v);
        for (float[] row : matrix)
            for (float v : row)
                payload.putFloat(v);

        ByteBuffer frame = ByteBuffer.allocate(4 + payloadBytes).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(payloadBytes);
        frame.put(payload.array());

        // 借一条连接，失败重试最多3次
        int maxRetries = 3;
        IOException lastException = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            SocketConnection conn = pool.take();
            try {
                return conn.predict(frame.array());
            } catch (IOException e) {
                lastException = e;
                conn.close();
                System.err.println("[GINE] predict attempt " + (attempt + 1) + " failed: " + e.getMessage());
            } finally {
                if (conn.isConnected()) {
                    pool.offer(conn);
                } else {
                    try {
                        pool.offer(new SocketConnection("127.0.0.1", PORT));
                    } catch (IOException ex) {
                        System.err.println("[GINE] reconnect fail: " + ex.getMessage());
                    }
                }
            }
        }
        // 重试全失败，重置initialized让下次调用自动重启Python
        initialized = false;
        pool.clear();
        throw new IOException("predict failed after " + maxRetries + " retries", lastException);
    }

    // ── Socket 连接 ──
    private static class SocketConnection {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        SocketConnection(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(600000);
            socket.setKeepAlive(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 4096));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 4096));
        }

        synchronized float predict(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n')
                    break;
                sb.append((char) ch);
            }
            String line = sb.toString().trim();
            if (line.isEmpty())
                throw new IOException("Python return fail");
            JsonNode json = MAPPER.readTree(line);
            if (json.has("error"))
                throw new RuntimeException("python predict fail: " + json.get("error").asText());
            return (float) json.get("predicted_cost").asDouble();
        }

        boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        void close() {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
