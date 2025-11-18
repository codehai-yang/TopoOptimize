package HarnessPackOpti.Optimize;

import java.util.concurrent.ConcurrentHashMap;

public class OptimizeStopStatusStore {
    private static OptimizeStopStatusStore instance;
    private final ConcurrentHashMap<String, Boolean> store = new ConcurrentHashMap<>();

    // 私有构造方法，防止外部直接创建实例
    private OptimizeStopStatusStore() {}

    // 公共静态方法，获取唯一的Store实例
    public static OptimizeStopStatusStore getInstance() {
        if (instance == null) {
            synchronized (OptimizeStopStatusStore.class) {
                if (instance == null) {
                    instance = new OptimizeStopStatusStore();
                }
            }
        }
        return instance;
    }

    public void setKey(String id) {
        store.put(id, true);
    }

    public Boolean get(String id) {
        return store.get(id);
    }

    public void setFalseByKey(String id) {
        store.put(id, false);
    }
}