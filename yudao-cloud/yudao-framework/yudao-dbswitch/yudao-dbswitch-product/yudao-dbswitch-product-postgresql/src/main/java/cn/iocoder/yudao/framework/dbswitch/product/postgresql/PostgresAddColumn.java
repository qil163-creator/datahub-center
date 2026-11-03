package cn.iocoder.yudao.framework.dbswitch.product.postgresql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/** 只生成可空、无默认值的 ADD COLUMN。标识符在这里引用。 */
public final class PostgresAddColumn {

    /**
     * 初始化 {@code PostgresAddColumn}。
     */
    private PostgresAddColumn() {
    }

    /**
     * 根据当前PostgreSQL方言生成 SQL 语句或片段。
     */
    public static String sql(DatabasePostgresImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || database.getDatabaseType() != ProductTypeEnum.POSTGRESQL || column == null) {
            throw unsupported("PostgreSQL ADD COLUMN requires the PostgreSQL dialect and a column");
        }
        if (column.getMetaData().getType() == cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_NONE) {
            throw unsupported("Nullable ADD COLUMN requires a recognized field type");
        }
        reject(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, table);
        ColumnMetaData meta = column.getMetaData();
        String definition = database.getFieldDefinition(meta, null, false, false, true);
        String upper = definition.toUpperCase(Locale.ROOT);
        if (upper.contains(" NOT NULL") || upper.contains(" DEFAULT")) {
            throw unsupported("PostgreSQL ADD COLUMN accepts only a nullable column without a default");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + definition.strip();
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        if (!column.isNullable()) {
            throw unsupported("PostgreSQL ADD COLUMN does not add NOT NULL columns");
        }
        if (column.isPk()) {
            throw unsupported("PostgreSQL ADD COLUMN does not add primary key columns");
        }
        if (column.getDefaultValue() != null) {
            throw unsupported("PostgreSQL ADD COLUMN does not add columns with a default");
        }
        if (column.isAutoIncrement()) {
            throw unsupported("PostgreSQL ADD COLUMN does not add identity columns");
        }
        var evidence = column.getMetadataEvidence();
        if (evidence == null) {
            return;
        }
        if (evidence.nullable().state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(
                evidence.nullable().value())) {
            throw unsupported("PostgreSQL ADD COLUMN does not add NOT NULL columns");
        }
        if (evidence.defaultExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("PostgreSQL ADD COLUMN does not add columns with a default");
        }
        if (evidence.identity().state() == MetadataValue.State.VALUE) {
            throw unsupported("PostgreSQL ADD COLUMN does not add identity columns");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("PostgreSQL ADD COLUMN does not add generated columns");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
