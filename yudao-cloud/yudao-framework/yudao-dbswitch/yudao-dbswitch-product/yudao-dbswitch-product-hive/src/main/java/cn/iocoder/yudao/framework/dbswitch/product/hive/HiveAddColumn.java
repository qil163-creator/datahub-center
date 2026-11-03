package cn.iocoder.yudao.framework.dbswitch.product.hive;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 使用 {@code ALTER TABLE ... ADD COLUMNS} 为普通外部 TEXTFILE 表追加单个允许空值的列。
 * 不读取目录，也不声称已在服务器执行。拒绝分区表、ACID 表、视图、
 * NOT NULL、默认值、CASCADE 和 REPLACE。
 */
public final class HiveAddColumn {

    private static final String SQL_STATE = "0A000";

    /** 与 Hive 4.1.0 目录读取器使用相同的普通 TEXTFILE 标记。此类不调用该读取器。 */
    public static final String EXTERNAL_TABLE = "EXTERNAL_TABLE";

    public static final String TEXT_INPUT = "org.apache.hadoop.mapred.TextInputFormat";

    public static final String LAZY_SERDE = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe";

    /** 方言默认值，不代表已观测实际表。 */
    public static final TableShape ORDINARY_EXTERNAL_TEXTFILE = new TableShape(
            EXTERNAL_TABLE, TEXT_INPUT, LAZY_SERDE, false, false, false, "ADD");

    /**
     * 初始化 {@code HiveAddColumn}。
     */
    private HiveAddColumn() {
    }

    /**
     * 追加单列。Hive 语法为 {@code ADD COLUMNS (name type)}，而不是 {@code ADD COLUMN}。
     * 语句不含 CASCADE、RESTRICT、REPLACE、PARTITION 或 COMMENT 子句。
     */
    public static String sql(DatabaseHiveImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        return sql(database, schema, table, column, ORDINARY_EXTERNAL_TEXTFILE);
    }

    /**
     * 根据当前Hive方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseHiveImpl database, String schema, String table, ColumnDescription column,
            TableShape shape) throws SQLFeatureNotSupportedException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        refuseShape(shape);
        if (!column.isNullable() || explicitlyNotNull(column.getMetadataEvidence())) {
            throw unsupported("Hive ADD COLUMNS does not support NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("Hive ADD COLUMNS does not support a primary key");
        }
        if (hasDefault(column)) {
            throw unsupported("Hive ADD COLUMNS does not support a default");
        }
        if (generated(column)) {
            throw unsupported("Hive ADD COLUMNS does not support a generated column");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE,
                column.getFieldName());
        String type = columnType(database, column, quotedColumn);
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMNS (" + quotedColumn + " " + type + ")";
    }

    /** 与 {@link #sql} 使用同一语句。被拒绝的功能仍以 {@link SQLFeatureNotSupportedException} 抛出。 */
    public static String dialectSql(DatabaseHiveImpl database, String schema, String table, ColumnDescription column) {
        try {
            return sql(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            throw sneakyThrow(unsupported);
        }
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }

    /**
     * 拒绝当前增列路径无法证明安全的表结构。
     */
    private static void refuseShape(TableShape shape) throws SQLFeatureNotSupportedException {
        if (shape == null) {
            throw unsupported("Hive ADD COLUMNS does not support an unknown table");
        }
        if (shape.view() || (shape.tableType() != null && shape.tableType().toUpperCase(Locale.ROOT)
                .contains("VIEW"))) {
            throw unsupported("Hive ADD COLUMNS does not support views");
        }
        if (shape.partitioned()) {
            throw unsupported("Hive ADD COLUMNS does not support partitioned tables");
        }
        if (shape.transactional()) {
            throw unsupported("Hive ADD COLUMNS does not support ACID tables");
        }
        String alteration = shape.alteration();
        if ("CASCADE".equals(alteration)) {
            throw unsupported("Hive ADD COLUMNS does not support CASCADE");
        }
        if ("REPLACE".equals(alteration)) {
            throw unsupported("Hive ADD COLUMNS does not support REPLACE");
        }
        if ("RESTRICT".equals(alteration)) {
            throw unsupported("Hive ADD COLUMNS does not support RESTRICT");
        }
        if (!"ADD".equals(alteration)) {
            throw unsupported("Hive ADD COLUMNS does not support an unknown alteration");
        }
        if (!EXTERNAL_TABLE.equals(shape.tableType()) || !TEXT_INPUT.equals(shape.inputFormat())
                || !LAZY_SERDE.equals(shape.serde())) {
            throw unsupported("Hive ADD COLUMNS requires an ordinary external TEXTFILE table");
        }
    }

    /**
     * 判断列定义是否明确声明非空。
     */
    private static boolean explicitlyNotNull(ColumnMetadataEvidence evidence) {
        MetadataValue<Boolean> nullable = evidence.nullable();
        return nullable.state() == MetadataValue.State.UNSUPPORTED
                || (nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value()));
    }

    /**
     * 判断是否具有默认。
     */
    private static boolean hasDefault(ColumnDescription column) {
        if (column.getDefaultValue() != null) {
            return true;
        }
        MetadataValue<String> expression = column.getMetadataEvidence().defaultExpression();
        return expression.state() == MetadataValue.State.VALUE || expression.state() == MetadataValue.State.UNSUPPORTED;
    }

    /**
     * 返回当前生成列。
     */
    private static boolean generated(ColumnDescription column) {
        if (column.isAutoIncrement()) {
            return true;
        }
        ColumnMetadataEvidence evidence = column.getMetadataEvidence();
        return present(evidence.generatedExpression()) || present(evidence.identity()) || present(evidence.sequence());
    }

    /**
     * 返回当前存在性。
     */
    private static boolean present(MetadataValue<?> value) {
        return value.state() == MetadataValue.State.VALUE || value.state() == MetadataValue.State.UNSUPPORTED;
    }

    /**
     * 根据列定义生成当前产品的类型声明。
     */
    private static String columnType(DatabaseHiveImpl database, ColumnDescription column, String quotedColumn)
            throws SQLFeatureNotSupportedException {
        String definition = database.getFieldDefinition(column.getMetaData(), List.of(), false, false, false);
        String prefix = quotedColumn + " ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("Hive ADD COLUMNS could not take a type from getFieldDefinition");
        }
        String type = definition.substring(prefix.length()).trim();
        if (!type.matches("[A-Za-z0-9_(),]+")
                || type.matches(
                "(?is).*\\b(not\\s+null|default|comment|cascade|replace|partition|primary\\s+key)\\b.*")) {
            throw unsupported("Hive ADD COLUMNS type must not include a constraint, comment, or default");
        }
        return type;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, SQL_STATE);
    }

    /**
     * 调用方提供的表事实。仅接受 {@code alteration} {@code ADD} 子句；
     * 仍不会将 CASCADE、RESTRICT 或 REPLACE 写入语句。
     */
    public record TableShape(String tableType, String inputFormat, String serde, boolean partitioned,
                             boolean transactional, boolean view, String alteration) {

    }
}
