package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.DorisTableDefinition;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Apache Doris 建表 DDL 构建器（跨模块通用）
 * <p>
 * 按 {@link DorisTableDefinition} 生成 CREATE TABLE，覆盖表模型（DUPLICATE/UNIQUE/AGGREGATE）、
 * Key、分区、分桶、副本、Merge-on-Write、Sequence 列。
 * 通用方言 {@link cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlCompatibleOlapImpl}
 * 面向「源表迁移」仅支持简化建表形态；「设计建模 → Doris 物理表」的完整语义由本构建器承载。
 * 列类型输入为 JDBC 类型名（保留设计精度），映射规则见 {@link #dorisColumnType}。
 *
 * @author Li
 */
public final class DorisDdlBuilder {

    private static final Set<String> AGG_TYPES = Set.of(
            "SUM", "MAX", "MIN", "REPLACE", "REPLACE_IF_NOT_NULL");

    /** VARCHAR 最大长度（字节） */
    private static final int VARCHAR_MAX_LENGTH = 65_533;

    /**
     * 初始化 {@code DorisDdlBuilder}。
     */
    private DorisDdlBuilder() {
    }

    // ==================== 校验 ====================

    /**
     * 建表定义校验，返回全部问题（空列表表示通过）
     */
    public static List<String> validate(DorisTableDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<DorisTableDefinition.Column> columns = definition.getColumns() == null
                ? List.of() : definition.getColumns();
        Set<String> columnNames = new LinkedHashSet<>();
        columns.forEach(column -> columnNames.add(lower(column.getName())));

        String tableModel = StringUtils.defaultIfBlank(
                definition.getTableModel(), DorisTableDefinition.MODEL_DUPLICATE);
        boolean unique = DorisTableDefinition.MODEL_UNIQUE.equals(tableModel);
        boolean aggregate = DorisTableDefinition.MODEL_AGGREGATE.equals(tableModel);
        boolean duplicate = DorisTableDefinition.MODEL_DUPLICATE.equals(tableModel);
        if (!unique && !aggregate && !duplicate) {
            errors.add("表模型必须为 DUPLICATE/UNIQUE/AGGREGATE");
            return errors;
        }

        // Key 列
        List<String> keyColumns = definition.getKeyColumns() == null
                ? List.of() : definition.getKeyColumns();
        if ((unique || aggregate) && keyColumns.isEmpty()) {
            errors.add("主键模型/聚合模型必须配置 Key 列");
        }
        keyColumns.forEach(key -> {
            if (!columnNames.contains(lower(key))) {
                errors.add(String.format("Key 列 %s 不存在于列定义中", key));
            }
        });
        Set<String> keySet = new LinkedHashSet<>();
        keyColumns.forEach(key -> keySet.add(lower(key)));
        // Key 列类型不允许 STRING（无长度文本）
        for (DorisTableDefinition.Column column : columns) {
            if (keySet.contains(lower(column.getName()))
                    && "STRING".equals(dorisColumnType(column))) {
                errors.add(String.format(
                        "Key 列 %s 的类型映射为 STRING，Doris 不支持，请改为限定长度的 varchar",
                        column.getName()));
            }
        }

        // 聚合模型：非 Key 列必须有聚合类型
        if (aggregate) {
            for (DorisTableDefinition.Column column : columns) {
                if (keySet.contains(lower(column.getName()))) {
                    continue;
                }
                if (StringUtils.isBlank(column.getAggType())) {
                    errors.add(String.format("聚合模型 Value 列 %s 必须配置聚合类型", column.getName()));
                } else if (!AGG_TYPES.contains(column.getAggType().toUpperCase(Locale.ROOT))) {
                    errors.add(String.format("列 %s 的聚合类型 %s 不支持（可选 %s）",
                            column.getName(), column.getAggType(), String.join("/", AGG_TYPES)));
                }
            }
        }

        // 分桶
        String distributionType = StringUtils.defaultIfBlank(
                definition.getDistributionType(), DorisTableDefinition.DISTRIBUTION_HASH);
        if (DorisTableDefinition.DISTRIBUTION_RANDOM.equals(distributionType)) {
            if (!duplicate) {
                errors.add("RANDOM 分桶仅支持明细模型（DUPLICATE）");
            }
        } else {
            List<String> distributionColumns = definition.getDistributionColumns() == null
                    ? List.of() : definition.getDistributionColumns();
            if (distributionColumns.isEmpty()) {
                errors.add("HASH 分桶必须配置分桶列");
            }
            distributionColumns.forEach(column -> {
                if (!columnNames.contains(lower(column))) {
                    errors.add(String.format("分桶列 %s 不存在于列定义中", column));
                } else if ((unique || aggregate) && !keySet.contains(lower(column))) {
                    errors.add(String.format("主键/聚合模型的分桶列 %s 必须属于 Key 列", column));
                }
            });
        }
        if (DorisTableDefinition.BUCKET_FIXED.equals(definition.getBucketMode())
                && (definition.getBucketCount() == null || definition.getBucketCount() <= 0)) {
            errors.add("固定桶数模式必须配置大于 0 的桶数");
        }

        // 分区
        String partitionMode = StringUtils.defaultIfBlank(
                definition.getPartitionMode(), DorisTableDefinition.PARTITION_NONE);
        if (!DorisTableDefinition.PARTITION_NONE.equals(partitionMode)) {
            List<String> partitionColumns = definition.getPartitionColumns() == null
                    ? List.of() : definition.getPartitionColumns();
            if (partitionColumns.isEmpty()) {
                errors.add("分区模式下必须配置分区列");
            }
            partitionColumns.forEach(column -> {
                if (!columnNames.contains(lower(column))) {
                    errors.add(String.format("分区列 %s 不存在于列定义中", column));
                } else if (unique && !keySet.contains(lower(column))) {
                    errors.add(String.format("主键模型的分区列 %s 必须属于 Key 列", column));
                }
            });
        }

        // 主键模型专属
        if (!unique) {
            if (Boolean.TRUE.equals(definition.getEnableMow())) {
                errors.add("Merge-on-Write 仅主键模型（UNIQUE）可用");
            }
            if (StringUtils.isNotBlank(definition.getSequenceColumn())) {
                errors.add("Sequence 列仅主键模型（UNIQUE）可用");
            }
        } else if (StringUtils.isNotBlank(definition.getSequenceColumn())
                && !columnNames.contains(lower(definition.getSequenceColumn()))) {
            errors.add(String.format("Sequence 列 %s 不存在于列定义中", definition.getSequenceColumn()));
        }
        return errors;
    }

    // ==================== DDL 生成 ====================

    /**
     * 生成 CREATE TABLE IF NOT EXISTS（Key 列自动排到最前）
     */
    public static String buildCreateTable(DorisTableDefinition definition) {
        String tableModel = StringUtils.defaultIfBlank(
                definition.getTableModel(), DorisTableDefinition.MODEL_DUPLICATE);
        boolean aggregate = DorisTableDefinition.MODEL_AGGREGATE.equals(tableModel);
        boolean unique = DorisTableDefinition.MODEL_UNIQUE.equals(tableModel);
        List<DorisTableDefinition.Column> columns = definition.getColumns();
        List<String> keyColumns = definition.getKeyColumns() == null
                ? List.of() : definition.getKeyColumns();
        Set<String> keySet = new LinkedHashSet<>();
        keyColumns.forEach(key -> keySet.add(lower(key)));

        // Key 列必须位于 Value 列之前
        List<DorisTableDefinition.Column> ordered = new ArrayList<>();
        for (String key : keyColumns) {
            columns.stream()
                    .filter(column -> lower(column.getName()).equals(lower(key)))
                    .findFirst().ifPresent(ordered::add);
        }
        columns.stream()
                .filter(column -> !keySet.contains(lower(column.getName())))
                .forEach(ordered::add);

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS `").append(definition.getTableName()).append("` (\n");
        for (int i = 0; i < ordered.size(); i++) {
            DorisTableDefinition.Column column = ordered.get(i);
            boolean isKey = keySet.contains(lower(column.getName()));
            sql.append("  `").append(column.getName()).append("` ")
                    .append(dorisColumnType(column));
            // 聚合模型 Value 列携带聚合函数
            if (aggregate && !isKey && StringUtils.isNotBlank(column.getAggType())) {
                sql.append(' ').append(column.getAggType().toUpperCase(Locale.ROOT));
            }
            boolean notNull = isKey || Boolean.FALSE.equals(column.getNullable());
            sql.append(notNull ? " NOT NULL" : " NULL");
            if (StringUtils.isNotBlank(column.getComment())) {
                sql.append(" COMMENT '")
                        .append(column.getComment().replace("'", "\\'")).append('\'');
            }
            sql.append(i == ordered.size() - 1 ? "\n" : ",\n");
        }
        sql.append(") ENGINE=OLAP\n");

        // 表模型与 Key
        if (!keyColumns.isEmpty()) {
            sql.append(tableModel).append(" KEY(").append(quoteJoin(keyColumns)).append(")\n");
        }
        if (StringUtils.isNotBlank(definition.getTableComment())) {
            sql.append("COMMENT '")
                    .append(definition.getTableComment().replace("'", "\\'")).append("'\n");
        }

        // 分区
        String partitionMode = StringUtils.defaultIfBlank(
                definition.getPartitionMode(), DorisTableDefinition.PARTITION_NONE);
        List<String> partitionColumns = definition.getPartitionColumns() == null
                ? List.of() : definition.getPartitionColumns();
        switch (partitionMode) {
            case DorisTableDefinition.PARTITION_RANGE:
                sql.append("PARTITION BY RANGE(").append(quoteJoin(partitionColumns)).append(") ()\n");
                break;
            case DorisTableDefinition.PARTITION_LIST:
                sql.append("PARTITION BY LIST(").append(quoteJoin(partitionColumns)).append(") ()\n");
                break;
            case DorisTableDefinition.PARTITION_AUTO_RANGE:
                String expr = StringUtils.defaultIfBlank(definition.getPartitionExpr(),
                        String.format("date_trunc(`%s`, 'day')", partitionColumns.get(0)));
                sql.append("AUTO PARTITION BY RANGE (").append(expr).append(") ()\n");
                break;
            case DorisTableDefinition.PARTITION_AUTO_LIST:
                sql.append("AUTO PARTITION BY LIST (").append(quoteJoin(partitionColumns)).append(") ()\n");
                break;
            default:
                // NONE：不分区
                break;
        }

        // 分桶
        String distributionType = StringUtils.defaultIfBlank(
                definition.getDistributionType(), DorisTableDefinition.DISTRIBUTION_HASH);
        if (DorisTableDefinition.DISTRIBUTION_RANDOM.equals(distributionType)) {
            sql.append("DISTRIBUTED BY RANDOM");
        } else {
            List<String> distributionColumns = definition.getDistributionColumns() == null
                    ? List.of() : definition.getDistributionColumns();
            sql.append("DISTRIBUTED BY HASH(").append(quoteJoin(distributionColumns)).append(')');
        }
        if (DorisTableDefinition.BUCKET_FIXED.equals(definition.getBucketMode())
                && definition.getBucketCount() != null) {
            sql.append(" BUCKETS ").append(definition.getBucketCount());
        } else {
            sql.append(" BUCKETS AUTO");
        }
        sql.append('\n');

        // PROPERTIES
        List<String> properties = new ArrayList<>();
        properties.add(String.format("\"replication_num\" = \"%s\"",
                definition.getReplicationNum() == null ? 1 : definition.getReplicationNum()));
        if (unique) {
            properties.add(String.format("\"enable_unique_key_merge_on_write\" = \"%s\"",
                    !Boolean.FALSE.equals(definition.getEnableMow())));
            if (StringUtils.isNotBlank(definition.getSequenceColumn())) {
                properties.add(String.format("\"function_column.sequence_col\" = \"%s\"",
                        definition.getSequenceColumn()));
            }
        }
        sql.append("PROPERTIES (\n  ").append(String.join(",\n  ", properties)).append("\n)");
        return sql.toString();
    }

    /**
     * JDBC 类型名映射为 Doris 类型（varchar 长度按 3 字节/字符放大，与迁移方言口径一致）
     */
    public static String dorisColumnType(DorisTableDefinition.Column column) {
        String type = StringUtils.defaultIfBlank(column.getTypeName(), "varchar")
                .toLowerCase(Locale.ROOT);
        Integer length = column.getLength();
        Integer precision = column.getPrecision();
        if (type.contains("bigint")) {
            return "BIGINT";
        }
        if (type.contains("smallint")) {
            return "SMALLINT";
        }
        if (type.contains("tinyint")) {
            return "TINYINT";
        }
        if (type.equals("int") || type.contains("integer") || type.contains("serial")) {
            return "INT";
        }
        if (type.contains("decimal") || type.contains("numeric") || type.contains("number")) {
            int len = length == null || length <= 0 ? 18 : length;
            int scale = precision == null || precision < 0 ? 4 : precision;
            return String.format("DECIMAL(%d,%d)", len, scale);
        }
        if (type.contains("double") || type.equals("real")) {
            return "DOUBLE";
        }
        if (type.contains("float")) {
            return "FLOAT";
        }
        if (type.contains("bool") || type.equals("bit")) {
            return "BOOLEAN";
        }
        if (type.equals("date")) {
            return "DATE";
        }
        if (type.contains("timestamp") || type.contains("datetime") || type.equals("time")) {
            return "DATETIME";
        }
        if (type.contains("char")) {
            return length == null || length <= 0 ? "VARCHAR(255)"
                    : String.format("VARCHAR(%d)", Math.min(length * 3, VARCHAR_MAX_LENGTH));
        }
        return "STRING";
    }

    /**
     * 引用连接。
     */
    private static String quoteJoin(List<String> columns) {
        return "`" + String.join("`, `", columns) + "`";
    }

    /**
     * 返回当前下界。
     */
    private static String lower(String name) {
        return StringUtils.defaultString(name).toLowerCase(Locale.ROOT);
    }

}
