package HarnessPackOpti.utils;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.util.concurrent.Semaphore;

public class HttpClientPoolManager {

    // 1. 创建一个静态的连接池管理器
    private static final PoolingHttpClientConnectionManager CONNECTION_MANAGER;

    // 2. 创建一个全局共享的HttpClient实例
    private static final CloseableHttpClient HTTP_CLIENT;

    // 3. 信号量：设置为 Python worker 数量的 1.5-2 倍
    // Python 有 11 个 worker，这里设为 16-20
    // 这样既能保持 Python 队列有一点任务（避免空闲），又不会堆积太多
    private static final Semaphore REQUEST_SEMAPHORE = new Semaphore(16);

    static {
        // --- 初始化连接池管理器 ---
        CONNECTION_MANAGER = new PoolingHttpClientConnectionManager();

        // 最大连接数：与信号量值一致
        CONNECTION_MANAGER.setMaxTotal(20);

        // 单路由最大连接数：与信号量值一致
        CONNECTION_MANAGER.setDefaultMaxPerRoute(16);

        // 空闲连接存活检测：1秒，快速发现失效连接
        CONNECTION_MANAGER.setValidateAfterInactivity(1000);

        // --- 配置请求参数 ---
        RequestConfig requestConfig = RequestConfig.custom()
                // 从连接池获取连接的超时时间
                .setConnectionRequestTimeout(10000)
                // 建立TCP连接的超时时间
                .setConnectTimeout(10000)
                // 等待服务端返回数据的超时时间
                .setSocketTimeout(60000)
                .build();

        // --- 构建全局HttpClient实例 ---
        HTTP_CLIENT = HttpClients.custom()
                .setConnectionManager(CONNECTION_MANAGER)
                .setDefaultRequestConfig(requestConfig)
                // 启用连接保活
                .setKeepAliveStrategy((response, context) -> 30000)
                .build();

        // 添加一个JVM钩子，在应用关闭时优雅地释放连接池资源
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                HTTP_CLIENT.close();
                CONNECTION_MANAGER.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }

    // 提供一个静态方法，供你的11个线程获取同一个HttpClient实例
    public static CloseableHttpClient getHttpClient() {
        return HTTP_CLIENT;
    }

    /**
     * 获取信号量许可，如果当前并发请求数已达上限，则阻塞等待
     */
    public static void acquireRequestPermit() throws InterruptedException {
        REQUEST_SEMAPHORE.acquire();
    }

    /**
     * 释放信号量许可
     */
    public static void releaseRequestPermit() {
        REQUEST_SEMAPHORE.release();
    }

    /**
     * 获取当前等待的线程数（用于监控）
     */
    public static int getWaitingThreadCount() {
        return REQUEST_SEMAPHORE.getQueueLength();
    }

    /**
     * 获取当前可用的许可数
     */
    public static int getAvailablePermits() {
        return REQUEST_SEMAPHORE.availablePermits();
    }
}
