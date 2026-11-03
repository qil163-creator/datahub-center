package cn.iocoder.yudao.framework.etl.core.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 可选的协作停止及如实的资源退出探测。回调不得阻塞于 JDBC 或 RPC。 */
public final class EtlExecutionControl {

    private record Resource(Runnable stop, BooleanSupplier exited) {

    }

    private final AtomicBoolean stopped = new AtomicBoolean();

    private final List<Resource> resources = new CopyOnWriteArrayList<>();

    private final List<Runnable> exitCallbacks = new CopyOnWriteArrayList<>();

    /**
     * 停止已请求。
     */
    public boolean stopRequested() {
        return stopped.get();
    }

    /**
     * 登记受管资源的停止回调及实际退出检查。
     */
    public void register(Runnable stop, BooleanSupplier exited) {
        var resource = new Resource(java.util.Objects.requireNonNull(stop), java.util.Objects.requireNonNull(exited));
        resources.add(resource);
        if (stopped.get()) {
            invoke(stop);
        }
    }

    /**
     * 记录停止请求并通知所有已登记的运行资源。
     */
    public void requestStop() {
        stopped.set(true);
        resources.forEach(resource -> invoke(resource.stop()));
    }

    /**
     * 检查所有受管资源是否已经实际退出。
     */
    public boolean resourcesExited() {
        for (var resource : resources) {
            try {
                if (!resource.exited().getAsBoolean()) {
                    return false;
                }
            } catch (RuntimeException failure) {
                return false;
            }
        }
        for (var callback : exitCallbacks) {
            if (exitCallbacks.remove(callback)) {
                invoke(callback);
            }
        }
        return true;
    }

    /** 仅在资源登记完成后注册，例如引擎返回时。 */
    public void whenResourcesExited(Runnable callback) {
        exitCallbacks.add(callback);
        resourcesExited();
    }

    /**
     * 调用当前受控运行时的目标方法并传播调用结果。
     */
    private static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) { /* 请求失败不代表取得了退出回执。 */ }
    }
}
