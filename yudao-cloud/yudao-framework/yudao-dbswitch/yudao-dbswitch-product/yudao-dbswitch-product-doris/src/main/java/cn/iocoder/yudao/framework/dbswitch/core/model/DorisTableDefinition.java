package cn.iocoder.yudao.framework.dbswitch.core.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Doris 建表定义（跨模块通用的建表参数对象）
 * <p>
 * 供 {@link cn.iocoder.yudao.framework.dbswitch.core.util.DorisDdlBuilder} 生成建表 DDL。
 * 与迁移场景的 {@link ColumnDescription} 不同，本定义面向「设计字段 → Doris 物理表」：
 * 列类型使用 JDBC 类型名（varchar/bigint/timestamp 等）以保留设计精度，
 * 表模型/Key/分区/分桶等 Doris 专属语义显式建模。
 *
 * @author Li
 */
@Data
@Builder
public class DorisTableDefinition {

    /** 表模型：明细模型 */
    public static final String MODEL_DUPLICATE = "DUPLICATE";

    /** 表模型：主键模型 */
    public static final String MODEL_UNIQUE = "UNIQUE";

    /** 表模型：聚合模型 */
    public static final String MODEL_AGGREGATE = "AGGREGATE";

    /** 分区模式 */
    public static final String PARTITION_NONE = "NONE";

    public static final String PARTITION_RANGE = "RANGE";

    public static final String PARTITION_LIST = "LIST";

    public static final String PARTITION_AUTO_RANGE = "AUTO_RANGE";

    public static final String PARTITION_AUTO_LIST = "AUTO_LIST";

    /** 分桶方式 */
    public static final String DISTRIBUTION_HASH = "HASH";

    public static final String DISTRIBUTION_RANDOM = "RANDOM";

    /** 桶数模式 */
    public static final String BUCKET_AUTO = "AUTO";

    public static final String BUCKET_FIXED = "FIXED";

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表注释
     */
    private String tableComment;

    /**
     * 列定义（Key 列生成时自动排到最前）
     */
    private List<Column> columns;

    /**
     * 表模型：DUPLICATE/UNIQUE/AGGREGATE（空默认 DUPLICATE）
     */
    private String tableModel;

    /**
     * Key 列（按顺序）
     */
    private List<String> keyColumns;

    /**
     * 分区模式：NONE/RANGE/LIST/AUTO_RANGE/AUTO_LIST（空默认 NONE）
     */
    private String partitionMode;

    /**
     * 分区列
     */
    private List<String> partitionColumns;

    /**
     * AUTO RANGE 分区函数表达式（空则按首个分区列生成 date_trunc 按天）
     */
    private String partitionExpr;

    /**
     * 分桶方式：HASH/RANDOM（空默认 HASH）
     */
    private String distributionType;

    /**
     * HASH 分桶列
     */
    private List<String> distributionColumns;

    /**
     * 桶数模式：AUTO/FIXED（空默认 AUTO）
     */
    private String bucketMode;

    /**
     * 固定桶数（bucketMode=FIXED 时生效）
     */
    private Integer bucketCount;

    /**
     * 副本数（空默认 1）
     */
    private Integer replicationNum;

    /**
     * 主键模型启用 Merge-on-Write（空默认 true）
     */
    private Boolean enableMow;

    /**
     * 主键模型 Sequence 列
     */
    private String sequenceColumn;

    /**
     * Doris 建表列定义
     */
    @Data
    @Builder
    public static class Column {

        /**
         * 列名
         */
        private String name;

        /**
         * JDBC 类型名（varchar/bigint/decimal/timestamp 等，大小写不敏感）
         */
        private String typeName;

        /**
         * 长度（字符串长度或数值有效位数）
         */
        private Integer length;

        /**
         * 精度（小数位数）
         */
        private Integer precision;

        /**
         * 是否可空（空默认可空；Key 列强制 NOT NULL）
         */
        private Boolean nullable;

        /**
         * 列注释
         */
        private String comment;

        /**
         * 聚合类型（仅聚合模型 Value 列）：SUM/MAX/MIN/REPLACE/REPLACE_IF_NOT_NULL
         */
        private String aggType;

    }

}
