package cn.iocoder.yudao.framework.dbswitch.data.cursor;

/** 内存中的可信编排钩子，绝不能从请求配置中重建。 */
public record CursorRunBinding(String fingerprint, CursorCheckpoint previous, CheckpointStore store,
                               cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin sourceGenerationPin,
                               cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin targetGenerationPin) {

    /**
     * 使用给定参数初始化 {@code CursorRunBinding}。
     */
    public CursorRunBinding(String fingerprint, CursorCheckpoint previous, CheckpointStore store) {
        this(fingerprint, previous, store, null, null);
    }

    /**
     * 使用给定参数初始化 {@code CursorRunBinding}。
     */
    public CursorRunBinding(String fingerprint, CursorCheckpoint previous, CheckpointStore store,
            cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin sourceGenerationPin) {
        this(fingerprint, previous, store, sourceGenerationPin, null);
    }

    @FunctionalInterface
    public interface CheckpointStore {

        /**
         * 比较及集合。
         */
        boolean compareAndSet(CursorCheckpoint next);
    }

    /**
     * 校验构造参数并初始化 {@code CursorRunBinding}。
     */
    public CursorRunBinding {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || store == null
                || previous != null && !fingerprint.equals(previous.bindingFingerprint())) {
            throw new IllegalArgumentException("Cursor binding changed; explicit reconciliation or reset is required");
        }
    }
}
