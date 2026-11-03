package cn.iocoder.yudao.framework.etl.core.runtime;

import cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.KeyValue;

import java.util.*;

/** 非列级溯源信息；除非转换执行器明确证明可以保留，否则将其丢弃。 */
public record EtlRowOrigin(int version, String sourceNode, Long connectionId, String configurationRevision,
                           String connectionHash, String schema, String table, Map<String, KeyValue> keys,
                           String sourceHash, String emittedHash) {

    /**
     * 校验构造参数并初始化 {@code EtlRowOrigin}。
     */
    public EtlRowOrigin {
        keys = Collections.unmodifiableMap(new TreeMap<>(keys));
        if (version != 1 || sourceNode == null || sourceNode.isBlank() || sourceNode.length() > 128
                || connectionId == null || configurationRevision == null || configurationRevision.isBlank()
                || configurationRevision.length() > 256 || schema == null || schema.isBlank()
                || schema.length() > 128 || table == null || table.isBlank() || table.length() > 128 || keys.isEmpty() || keys.size() > 4
                || keys.keySet().stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 128)
                || !hash(connectionHash) || !hash(sourceHash) || !hash(emittedHash)) {
            throw new IllegalArgumentException("Invalid source row provenance");
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static boolean hash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    /**
     * 返回估算字节数。
     */
    public long estimatedBytes() {
        return 1024L + sourceNode.length() * 2L + schema.length() * 2L + table.length() * 2L
                + keys.entrySet().stream()
                .mapToLong(e -> 128L + e.getKey().length() * 2L + e.getValue().value().length() * 2L).sum();
    }
}
