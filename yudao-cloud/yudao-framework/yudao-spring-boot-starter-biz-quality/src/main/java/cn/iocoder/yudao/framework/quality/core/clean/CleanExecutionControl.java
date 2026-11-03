package cn.iocoder.yudao.framework.quality.core.clean;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 清洗函数执行期的取消与派生结果计价，不持有业务状态或跨线程传播。 */
public final class CleanExecutionControl {

    private static final ThreadLocal<Control> CURRENT = new ThreadLocal<>();

    private record Control(Consumer<Map<String, Object>> retainOutput, Runnable checkpoint) {

    }

    /**
     * 初始化 {@code CleanExecutionControl}。
     */
    private CleanExecutionControl() {
    }

    /**
     * 返回当前限定范围。
     */
    static <T> T scoped(Consumer<Map<String, Object>> retainOutput, Runnable checkpoint, Supplier<T> action) {
        Control previous = CURRENT.get();
        CURRENT.set(new Control(Objects.requireNonNull(retainOutput), Objects.requireNonNull(checkpoint)));
        try {
            checkpoint();
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 返回检查点。
     */
    public static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalArgumentException("清洗任务已取消");
        }
        Control control = CURRENT.get();
        if (control != null) {
            control.checkpoint.run();
        }
    }

    /** 在新行进入结果集合或新键进入去重索引前申请调用方配额。 */
    public static void retainOutput(Map<String, Object> row) {
        checkpoint();
        Control control = CURRENT.get();
        if (control != null) {
            control.retainOutput.accept(row);
        }
    }
}
