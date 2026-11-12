package cn.iocoder.yudao.module.governance.service.lineage;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageNodeTypeEnum;

import java.util.Locale;

/**
 * 血缘节点与字段的规范化规则，上报去重和查询定位共用同一份实现。
 *
 * <ul>
 *     <li>TABLE：{@code TABLE|数据源|小写schema|小写表名}，schema 为空记为空串；</li>
 *     <li>EXTERNAL：{@code EXTERNAL|种类|标识}，标识大小写敏感、只去首尾空白；</li>
 *     <li>分量中的 {@code \} 与 {@code |} 被转义，避免不同分量拼出同一个键；</li>
 *     <li>超过 {@value #MAX_KEY_LENGTH} 字符时改用 {@code SHA256:<64 位十六进制>}。</li>
 * </ul>
 * 字段名统一小写（Locale.ROOT），{@code *} 原样保留。
 */
public final class LineageNodeKeys {

    /** node_key 列宽 */
    public static final int MAX_KEY_LENGTH = 512;

    private static final String HASH_PREFIX = "SHA256:";

    private LineageNodeKeys() {
    }

    /**
     * TABLE 节点键。
     */
    public static String tableKey(Long datasourceId, String schemaName, String tableName) {
        return finish(LineageNodeTypeEnum.TABLE.name() + '|' + datasourceId + '|' + escape(lower(schemaName))
                + '|' + escape(lower(tableName)));
    }

    /**
     * EXTERNAL 节点键。
     */
    public static String externalKey(String externalKind, String externalKey) {
        return finish(LineageNodeTypeEnum.EXTERNAL.name() + '|' + escape(trim(externalKind)) + '|'
                + escape(trim(externalKey)));
    }

    /**
     * 字段名规范化：去首尾空白并小写；空值返回 null。
     */
    public static String column(String columnName) {
        String value = trim(columnName);
        return value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
    }

    /**
     * 去首尾空白，空白串返回 null。
     */
    public static String blankToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String finish(String raw) {
        return raw.length() <= MAX_KEY_LENGTH ? raw : HASH_PREFIX + DigestUtil.sha256Hex(raw);
    }

    private static String lower(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.strip();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

}
