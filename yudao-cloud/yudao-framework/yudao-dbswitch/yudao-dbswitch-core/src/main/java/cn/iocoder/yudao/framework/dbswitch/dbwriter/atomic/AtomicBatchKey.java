package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

/** 调用方负责授权及稳定命名空间代际；此组件绝不推导租户标识。 */
public record AtomicBatchKey(String scope, String batchKey) {

    /**
     * 初始化 {@code AtomicBatchKey}。
     */
    public AtomicBatchKey {
        bounded(scope);
        bounded(batchKey);
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    private static void bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A bounded batch scope and key are required");
        }
    }
}
