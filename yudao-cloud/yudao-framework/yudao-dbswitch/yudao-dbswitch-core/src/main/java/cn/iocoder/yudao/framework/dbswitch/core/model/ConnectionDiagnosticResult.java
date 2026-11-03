package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.List;

/** 仅返回能力观察和安全错误类别，不返回端点、凭据、对象名称或驱动原始消息。 */
public record ConnectionDiagnosticResult(String status, boolean connectionSuccessful, long durationMillis,
                                         List<Check> checks) {

    /**
     * 初始化 {@code ConnectionDiagnosticResult}。
     */
    public ConnectionDiagnosticResult {
        checks = List.copyOf(checks);
    }

    /** group: BASIC/TARGET/METADATA/READ/WRITE/ADVANCED；status: PASS/FAIL/WARN/SKIPPED。 */
    public record Check(String group, String key, String label, String status, long durationMillis,
                        String message, String sqlState, Integer vendorCode, String reasonCode) {

        /** 保留原有调用契约；旧探针未提供原因时不能推断为产品不支持。 */
        public Check(String group, String key, String label, String status, long durationMillis,
                     String message, String sqlState, Integer vendorCode) {
            this(group, key, label, status, durationMillis, message, sqlState, vendorCode,
                    "SKIPPED".equals(status) ? "UNVERIFIED" : null);
        }
    }
}
