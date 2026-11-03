package cn.iocoder.yudao.framework.dbswitch.core.model;

/** 通用连接诊断配置；单项最多五秒，探测总预算默认三十秒，事务恢复、临时对象和会话清理分别使用独立预算。 */
public record ConnectionDiagnosticOptions(String schemaName, boolean writeChecks, int timeoutMillis) {

    /**
     * 校验构造参数并初始化 {@code ConnectionDiagnosticOptions}。
     */
    public ConnectionDiagnosticOptions {
        if (timeoutMillis < 1 || timeoutMillis > 5_000) {
            throw new IllegalArgumentException("诊断单项超时必须在 1 至 5000 毫秒之间");
        }
        if (schemaName != null && (schemaName.length() > 256 || schemaName.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("诊断目标名称不合法");
        }
        if (schemaName != null && schemaName.isBlank()) {
            schemaName = null;
        }
    }

    /**
     * 创建当前组件的默认配置。
     */
    public static ConnectionDiagnosticOptions defaults() {
        return new ConnectionDiagnosticOptions(null, false, 5_000);
    }
}
