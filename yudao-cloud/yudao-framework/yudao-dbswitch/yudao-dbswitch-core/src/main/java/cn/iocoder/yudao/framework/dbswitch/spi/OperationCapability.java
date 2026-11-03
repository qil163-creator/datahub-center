package cn.iocoder.yudao.framework.dbswitch.spi;

import java.util.Objects;

/** 实现声明刻意不代表真实数据库认证。 */
public record OperationCapability(Status status, String reason) {

    public enum Status {DECLARED, UNVERIFIED, VERIFIED, UNSUPPORTED}

    /**
     * 校验构造参数并初始化 {@code OperationCapability}。
     */
    public OperationCapability {
        Objects.requireNonNull(status, "status");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Capability reason is required");
        }
    }

    /**
     * 构造已经声明但尚未证明的能力状态。
     */
    public static OperationCapability declared() {
        return new OperationCapability(Status.DECLARED, "IMPLEMENTATION_REQUIRES_DATABASE_CERTIFICATION");
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    public static OperationCapability unsupported(String reason) {
        return new OperationCapability(Status.UNSUPPORTED, reason);
    }
}
