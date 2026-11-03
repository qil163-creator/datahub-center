package cn.iocoder.yudao.framework.dbswitch.common.entity;

/** 不可变的转换标识。未声明版本的旧回调无法证明重放等价性。 */
public record RecordTransformDefinition(String id, String version, String configurationFingerprint) {

    /**
     * 校验构造参数并初始化 {@code RecordTransformDefinition}。
     */
    public RecordTransformDefinition {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid transform identity");
        }
        if ((version == null) != (configurationFingerprint == null)) {
            throw new IllegalArgumentException(
                    "Transform version and configuration fingerprint must be supplied together");
        }
        if (version != null && (version.isBlank() || version.length() > 128
                || !configurationFingerprint.matches("[a-fA-F0-9]{64}"))) {
            throw new IllegalArgumentException("Invalid transform version or SHA-256 fingerprint");
        }
    }

    /**
     * 返回当前旧版。
     */
    public static RecordTransformDefinition legacy() {
        return new RecordTransformDefinition("legacy-callback", null, null);
    }

    /** 声明必须有可执行代码对应；未声明的回调沿用旧标识。 */
    public static RecordTransformDefinition resolve(RecordTransformDefinition declared, RecordTransformer callback) {
        if (callback == null) {
            if (declared != null) {
                throw new IllegalArgumentException("A declared record transform requires a callback");
            }
            return null;
        }
        return declared == null ? legacy() : declared;
    }

    /**
     * 为当前编码或数据结构补充版本信息。
     */
    public boolean versioned() {
        return version != null;
    }
}
