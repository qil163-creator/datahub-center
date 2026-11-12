package cn.iocoder.yudao.module.governance.enums.lineage;

/**
 * 血缘上报契约的数量与长度上限；生产者应在发送前自行裁剪，超限的报告整体被拒绝。
 */
public final class LineageContractConstants {

    /** 单次报告最多问题条数 */
    public static final int MAX_ISSUES = 200;
    /** 单次报告最多表级边条数 */
    public static final int MAX_TABLE_EDGES = 2000;
    /** 单次报告最多字段级边条数 */
    public static final int MAX_COLUMN_EDGES = 20000;

    /** 来源名称最大长度 */
    public static final int MAX_SOURCE_NAME_LENGTH = 255;
    /** 问题编码最大长度 */
    public static final int MAX_ISSUE_CODE_LENGTH = 64;
    /** 问题描述最大长度 */
    public static final int MAX_ISSUE_MESSAGE_LENGTH = 500;
    /** schema 名最大长度 */
    public static final int MAX_SCHEMA_NAME_LENGTH = 128;
    /** 表名最大长度 */
    public static final int MAX_TABLE_NAME_LENGTH = 255;
    /** 外部数据集标识最大长度 */
    public static final int MAX_EXTERNAL_KEY_LENGTH = 512;
    /** 节点展示名最大长度 */
    public static final int MAX_DISPLAY_NAME_LENGTH = 255;
    /** 字段名最大长度 */
    public static final int MAX_COLUMN_NAME_LENGTH = 255;

    /** 内容摘要格式：64 位小写十六进制 SHA-256 */
    public static final String CONTENT_HASH_PATTERN = "^[0-9a-f]{64}$";

    /** 表示整行/任意字段的通配字段名（如 count(*)） */
    public static final String WILDCARD_COLUMN = "*";

    private LineageContractConstants() {
    }

}
