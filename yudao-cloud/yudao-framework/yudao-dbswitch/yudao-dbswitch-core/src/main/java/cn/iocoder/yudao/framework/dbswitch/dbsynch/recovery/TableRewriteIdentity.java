package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import java.nio.charset.StandardCharsets;

/** 产品持有的原生代际依据，不得仅根据表名构造。 */
public record TableRewriteIdentity(String format, String document) {

    /**
     * 校验构造参数并初始化 {@code TableRewriteIdentity}。
     */
    public TableRewriteIdentity {
        if (format == null || !format.matches("[a-z0-9][a-z0-9_-]{0,63}") || document == null
                || document.isBlank() || document.getBytes(StandardCharsets.UTF_8).length > 16384
                || document.indexOf('\0') >= 0 || !document.equals(
                new String(document.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid native table generation evidence");
        }
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public String fingerprint() {
        return GroupRewriteMaterial.sha256((format + '\0' + document).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "TableRewriteIdentity[format=" + format + "]";
    }
}
