package HarnessPackOpti.Optimize;

public class OptimizeStopInput {
    private final OptimizeStopStatusStore optimizeStopStatusStore;

    public OptimizeStopInput() {
        this.optimizeStopStatusStore = OptimizeStopStatusStore.getInstance(); // 使用Store的单例实例
    }

    public void stopTopoOptimize(String caseId) {
        optimizeStopStatusStore.setFalseByKey(caseId);
    }
}
