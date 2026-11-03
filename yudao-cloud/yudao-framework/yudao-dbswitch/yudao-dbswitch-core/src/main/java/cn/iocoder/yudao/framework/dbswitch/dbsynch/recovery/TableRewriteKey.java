package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

/** 逻辑资源在不同物理代际间持续占用；operation 是持久化的尝试 ID。 */
public record TableRewriteKey(String scope, String logicalResource, String operation) {

    /**
     * 初始化 {@code TableRewriteKey}。
     */
    public TableRewriteKey {
        GroupRewriteKey.fingerprint(scope);
        GroupRewriteKey.fingerprint(logicalResource);
        GroupRewriteKey.fingerprint(operation);
    }

    /**
     * 校验并取得指纹。
     */
    public static void requireFingerprint(String value) {
        GroupRewriteKey.fingerprint(value);
    }
}
