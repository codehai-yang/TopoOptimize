package HarnessPackOpti.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private static final Object INIT_LOCK = new Object();

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

    // ── 启动 Python 服务器（仅一次）──
    private static void initOnce() throws IOException {
        System.out.println("start run python");
        System.out.println("[GINE] python.exe    = " + PYTHON_EXE);
        System.out.println("[GINE] predict.py    = " + PREDICT_SCRIPT);
        System.out.println("[GINE] script.dir    = " + SCRIPT_DIR);
        System.out.println("[GINE] port          = " + PORT);
        System.out.println("[GINE] pool.size     = " + POOL_SIZE);
        // 校验 Python 解释器与脚本文件存在
        if (!new File(PYTHON_EXE).isAbsolute() && !PYTHON_EXE.contains(File.separator)) {
            System.out.println("[GINE] 提示: python.exe 未指定绝对路径，依赖 PATH 解析，线上可能找不到");
        }
        if (!new File(PREDICT_SCRIPT).isFile()) {
            throw new IOException("predict.py 不存在: " + PREDICT_SCRIPT
                    + "，请用 -Dpredict.script.dir=<dir> 指定脚本目录");
        }
        // 校验模型/参数文件（仅当脚本目录是 -Dpredict.script.dir 指定时需要校验；
        // 若是从 classpath 释放到 tmp 目录的，resolveScriptDir 已保证文件存在）
        File modelFile = new File(SCRIPT_DIR, "best_model.pt");
        File normFile = new File(SCRIPT_DIR, "normalization_params.json");
        if (!modelFile.isFile()) {
            throw new IOException("best_model.pt 不存在: " + modelFile.getAbsolutePath());
        }
        if (!normFile.isFile()) {
            throw new IOException("normalization_params.json 不存在: " + normFile.getAbsolutePath());
        }

        // 关键: 加 -u 让 Python stdout/stderr 无缓冲
        // 否则 Python 在 pipe 模式下是块缓冲(4KB)，Java 端看不到任何输出，Python 崩溃也看不到错误
        ProcessBuilder pb = new ProcessBuilder(PYTHON_EXE, "-u", PREDICT_SCRIPT, "--port", String.valueOf(PORT));
        pb.directory(new File(SCRIPT_DIR));
        pb.redirectErrorStream(true);
        // 双保险: 也设置环境变量
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        final Process proc = pb.start();

        // JVM 退出时杀掉子进程，避免残留卡死
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (proc.isAlive())
                proc.destroyForcibly();
        }));

        // 启动后台守护线程实时透传 Python stdout/stderr 到 Java 控制台
        // 否则 Python 进程的输出会写满 pipe 缓冲区，导致 Python 阻塞卡死，端口起不来
        Thread pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[gine-py] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "gine-python-stdout-pump");
        pump.setDaemon(true);
        pump.start();

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
        int payloadBytes = 8 + (edgeDim * 2 * 4) + (edgeDim * edgeFeatDim * 4) + (nodeDim * nodeFeatDim * 4);
        ByteBuffer payload = ByteBuffer.allocate(payloadBytes).order(ByteOrder.BIG_ENDIAN);
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
                try {
                    pool.offer(new SocketConnection("127.0.0.1", PORT));
                } catch (IOException e) {
                    System.err.println("python restart fail: " + e.getMessage());
                }
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
