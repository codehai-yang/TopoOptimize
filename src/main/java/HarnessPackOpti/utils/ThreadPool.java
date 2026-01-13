package HarnessPackOpti.utils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadPool {
    private final Thread[] workers;
    private final BlockingQueue<Runnable> taskQueue;
    private final AtomicInteger activeTasks;
    private final ReentrantLock lock;
    private final Condition allTasksComplete;
    private volatile boolean isShutdown;
    private volatile boolean isTerminated;  // 立即终止标志

    /**
     * 构造函数：创建指定数量的工作线程和队列容量
     * @param threads 线程数量
     * @param queueCapacity 队列容量，建议设置为线程数的2-10倍
     */
    public ThreadPool(int threads, int queueCapacity) {
        if (threads <= 0) {
            throw new IllegalArgumentException("线程数必须大于0");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("队列容量必须大于0");
        }

        this.workers = new Thread[threads];
        this.taskQueue = new ArrayBlockingQueue<>(queueCapacity); // 使用有界队列
        this.activeTasks = new AtomicInteger(0);
        this.lock = new ReentrantLock();
        this.allTasksComplete = lock.newCondition();
        this.isShutdown = false;
        this.isTerminated = false;  // 初始化终止标志

        // 创建并启动工作线程
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                while (true) {
                    try {
                        // 检查是否需要立即终止
                        if (isTerminated) {
                            break;
                        }

                        Runnable task = taskQueue.take();

                        // 检查是否为毒丸（停止信号）
                        if (task == POISON_PILL) {
                            taskQueue.put(POISON_PILL); // 传递给其他线程
                            break;
                        }

                        // 再次检查终止标志（避免执行新任务）
                        if (isTerminated) {
                            break;
                        }

                        task.run();

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } finally {
                        int remaining = activeTasks.decrementAndGet();
                        if (remaining == 0 && taskQueue.isEmpty()) {
                            lock.lock();
                            try {
                                allTasksComplete.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                }
            });
            workers[i].start();
        }
    }

    /**
     * 使用CPU核心数创建线程池，队列容量为线程数的5倍
     */
    public ThreadPool() {
        this(Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors() * 5);
    }

    /**
     * 创建指定线程数的线程池，队列容量为线程数的5倍
     */
    public ThreadPool(int threads) {
        this(threads, threads * 5);
    }

    /**
     * 提交无返回值的任务（阻塞式）
     * 当队列满时，此方法会阻塞等待，直到队列有空位
     * @param task 要执行的任务
     * @throws InterruptedException 如果等待被中断
     */
    public void execute(Runnable task) throws InterruptedException {
        if (isShutdown) {
            throw new RejectedExecutionException("线程池已关闭");
        }

        activeTasks.incrementAndGet();
        // 使用 put() 方法，队列满时会阻塞等待
        taskQueue.put(task);
    }

    /**
     * 提交有返回值的任务（阻塞式）
     * 当队列满时，此方法会阻塞等待，直到队列有空位
     * @param task 要执行的任务
     * @return Future对象，可用于获取结果
     * @throws InterruptedException 如果等待被中断
     */
    public <T> Future<T> submit(Callable<T> task) throws InterruptedException {
        if (isShutdown) {
            throw new RejectedExecutionException("线程池已关闭");
        }

        FutureTask<T> futureTask = new FutureTask<>(task);
        activeTasks.incrementAndGet();
        // 使用 put() 方法，队列满时会阻塞等待
        taskQueue.put(futureTask);
        return futureTask;
    }

    /**
     * 尝试提交任务（非阻塞）
     * @param task 要执行的任务
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 如果在超时前成功提交返回true，否则返回false
     * @throws InterruptedException 如果等待被中断
     */
    public boolean tryExecute(Runnable task, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (isShutdown) {
            throw new RejectedExecutionException("线程池已关闭");
        }

        activeTasks.incrementAndGet();
        boolean offered = taskQueue.offer(task, timeout, unit);
        if (!offered) {
            activeTasks.decrementAndGet();
        }
        return offered;
    }

    /**
     * 等待所有任务完成
     */
    public void awaitCompletion() throws InterruptedException {
        lock.lock();
        try {
            while (activeTasks.get() > 0 || !taskQueue.isEmpty()) {
                allTasksComplete.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取线程池大小
     */
    public int getThreadCount() {
        return workers.length;
    }

    /**
     * 获取待处理任务数
     */
    public int getPendingTaskCount() {
        return taskQueue.size();
    }

    /**
     * 获取活跃任务数
     */
    public int getActiveTaskCount() {
        return activeTasks.get();
    }

    /**
     * 关闭线程池，等待所有任务完成
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }
        isShutdown = true;

        try {
            taskQueue.put(POISON_PILL);
            for (Thread worker : workers) {
                worker.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 立即终止线程池
     * 所有正在执行和等待的任务都会被中断
     * 注意：任务需要正确处理中断信号
     */
    public void terminateNow() {
        if (isTerminated) {
            return;
        }

        isTerminated = true;
        isShutdown = true;

        // 清空队列
        taskQueue.clear();

        // 中断所有工作线程
        for (Thread worker : workers) {
            worker.interrupt();
        }

        System.out.println("[线程池] 立即终止信号已发送");
    }

    /**
     * 检查线程池是否已终止
     */
    public boolean isTerminated() {
        return isTerminated;
    }

    /**
     * 在任务中检查是否应该退出
     * 任务应该定期调用此方法来检查退出条件
     */
    public boolean shouldStop() {
        return isTerminated || Thread.currentThread().isInterrupted();
    }

    // 毒丸对象，用于通知线程退出
    private static final Runnable POISON_PILL = () -> {};

    // 使用示例
    public static void main(String[] args) throws Exception {
        // 示例1：任务中检测到退出条件，立即终止所有任务
        System.out.println("=== 示例1：检测到退出条件，立即终止 ===");
        ThreadPool pool1 = new ThreadPool(4, 8);

        for (int i = 0; i < 20; i++) {
            final int taskId = i;
            pool1.execute(() -> {
                try {
                    for (int step = 0; step < 10; step++) {
                        // 定期检查是否应该退出
                        if (pool1.shouldStop()) {
                            System.out.println("  [任务" + taskId + "] 收到终止信号，退出");
                            return;
                        }

                        System.out.println("  [任务" + taskId + "] 步骤 " + step);
                        Thread.sleep(200);

                        // 模拟：任务5检测到错误条件
                        if (taskId == 5 && step == 3) {
                            System.out.println("  [任务" + taskId + "] ❌ 检测到致命错误！立即终止线程池");
                            pool1.terminateNow();
                            return;
                        }
                    }
                    System.out.println("  [任务" + taskId + "] ✓ 完成");
                } catch (InterruptedException e) {
                    System.out.println("  [任务" + taskId + "] 被中断");
                }
            });
        }

        Thread.sleep(3000); // 等待观察效果
        pool1.shutdown();
        System.out.println("示例1完成\n");

        // 示例2：外部线程监控并终止
        System.out.println("=== 示例2：外部监控线程控制终止 ===");
        ThreadPool pool2 = new ThreadPool(3, 5);

        // 监控线程
        Thread monitor = new Thread(() -> {
            try {
                Thread.sleep(2000); // 2秒后触发终止
                System.out.println("[监控线程] 检测到异常，立即终止线程池！");
                pool2.terminateNow();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        monitor.start();

        // 提交长时间运行的任务
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            pool2.execute(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        if (pool2.shouldStop()) {
                            System.out.println("  [任务" + taskId + "] 停止");
                            return;
                        }
                        System.out.println("  [任务" + taskId + "] 运行中... " + j);
                        Thread.sleep(300);
                    }
                } catch (InterruptedException e) {
                    System.out.println("  [任务" + taskId + "] 被中断");
                }
            });
        }

        monitor.join();
        Thread.sleep(1000);
        pool2.shutdown();
        System.out.println("示例2完成\n");

        // 示例3：优雅关闭 vs 立即终止对比
        System.out.println("=== 示例3：优雅关闭 vs 立即终止 ===");

        // 3.1 优雅关闭
        System.out.println("\n[优雅关闭] 等待所有任务完成：");
        ThreadPool pool3 = new ThreadPool(2, 3);
        for (int i = 0; i < 5; i++) {
            final int taskId = i;
            pool3.execute(() -> {
                try {
                    System.out.println("  任务" + taskId + " 执行中");
                    Thread.sleep(500);
                    System.out.println("  任务" + taskId + " 完成");
                } catch (InterruptedException e) {
                    System.out.println("  任务" + taskId + " 被中断");
                }
            });
        }
        pool3.shutdown(); // 优雅关闭，等待任务完成
        System.out.println("优雅关闭完成");

        // 3.2 立即终止
        System.out.println("\n[立即终止] 中断所有任务：");
        ThreadPool pool4 = new ThreadPool(2, 3);
        for (int i = 0; i < 5; i++) {
            final int taskId = i;
            pool4.execute(() -> {
                try {
                    System.out.println("  任务" + taskId + " 执行中");
                    Thread.sleep(2000);
                    System.out.println("  任务" + taskId + " 完成");
                } catch (InterruptedException e) {
                    System.out.println("  任务" + taskId + " 被强制中断");
                }
            });
        }
        Thread.sleep(500); // 让任务开始执行
        pool4.terminateNow(); // 立即终止
        Thread.sleep(100);
        pool4.shutdown();

        System.out.println("\n所有示例完成！");
    }
}