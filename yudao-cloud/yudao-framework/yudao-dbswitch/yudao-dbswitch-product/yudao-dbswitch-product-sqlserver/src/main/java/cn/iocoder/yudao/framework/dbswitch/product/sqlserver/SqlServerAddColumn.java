package cn.iocoder.yudao.framework.dbswitch.product.sqlserver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.function.Function;

/**
 * 一条可空、无默认值的加列语句：{@code ALTER TABLE [schema].[table] ADD [column] type}。
 * 本产品现有 DDL 用 {@code ADD}，不用 {@code ADD COLUMN}。标识符只走
 * {@link RestrictedSelectGuard#quotePhysicalIdentifier} 的方括号。不打开连接。
 */
public final class SqlServerAddColumn {

    /**
     * 初始化 {@code SqlServerAddColumn}。
     */
    private SqlServerAddColumn() {
    }

    /**
     * 根据当前SQL Server方言生成 SQL 语句或片段。
     */
    public static String sql(String schema, String table, ColumnDescription column,
            Function<ColumnMetaData, String> fieldDefinition) {
        if (column == null || fieldDefinition == null) {
            throw new IllegalArgumentException(
                    "SQL Server add column requires a column and the existing field definition");
        }
        reject(column);
        if (column.getMetaData().getType() == ColumnMetaData.TYPE_NONE) {
            unsupported("SQL Server nullable ADD COLUMN requires a recognized field type");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER,
                column.getFieldName());
        String definition = fieldDefinition.apply(column.getMetaData());
        String prefix = " " + quotedColumn + "  ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw new IllegalArgumentException("SQL Server field definition did not quote the physical column");
        }
        String type = definition.substring(prefix.length()).strip();
        if (type.isEmpty() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("SQL Server field definition is not a bare column type");
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains(" NOT NULL")) {
            unsupported("SQL Server nullable add column does not accept NOT NULL");
        }
        if (upper.contains(" DEFAULT")) {
            unsupported("SQL Server nullable add column does not accept a default");
        }
        if (upper.contains(" IDENTITY")) {
            unsupported("SQL Server nullable add column does not accept an identity column");
        }
        if (upper.contains(" GENERATED") || upper.contains(" AS(") || upper.contains(" AS (")) {
            unsupported("SQL Server nullable add column does not accept a generated column");
        }
        if (upper.contains(" PRIMARY")) {
            unsupported("SQL Server nullable add column does not accept a primary key");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD " + quotedColumn + " " + type;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) {
        if (column.getFieldName() == null || column.getFieldName().isBlank()) {
            throw new IllegalArgumentException("SQL Server add column requires a physical column name");
        }
        var evidence = column.getMetadataEvidence();
        if (!column.isNullable() || refusedNullable(evidence.nullable())) {
            unsupported("SQL Server nullable add column does not accept NOT NULL");
        }
        if (column.isPk()) {
            unsupported("SQL Server nullable add column does not accept a primary key");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE) {
            unsupported("SQL Server nullable add column does not accept a default");
        }
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE) {
            unsupported("SQL Server nullable add column does not accept an identity column");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE) {
            unsupported("SQL Server nullable add column does not accept a generated column");
        }
    }

    /**
     * 根据当前列可空性生成拒绝原因或映射结果。
     */
    private static boolean refusedNullable(MetadataValue<Boolean> nullable) {
        return nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value());
    }

    /** 以受检异常拒绝，不扩展 {@code getAddColumnSql} 的方法签名。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
