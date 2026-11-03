package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

/** 可信作用域，以及产品观测的物理资源和规范分组标识。 */
public record GroupRewriteKey(String scope, String resource, String group) {

    /**
     * 初始化 {@code GroupRewriteKey}。
     */
    public GroupRewriteKey {
        fingerprint(scope);
        fingerprint(resource);
        fingerprint(group);
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    static void fingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid recovery fingerprint");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "GroupRewriteKey[redacted]";
    }
}
