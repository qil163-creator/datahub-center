package cn.iocoder.yudao.framework.dbswitch.product.uxdb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/**
 * 可空、无默认值的单列 ADD。类型文本复用既有 {@code getFieldDefinition}。
 * 标识符只走 UXDB 普通名引用。语句是 DDL，不查询 ux_catalog，也不使用 pg_catalog。
 */
public final class UxdbAddColumn {

    /**
     * 初始化 {@code UxdbAddColumn}。
     */
    private UxdbAddColumn() {
    }

    /**
     * 根据当前数据库方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseUxdbImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || database.getDatabaseType() != ProductTypeEnum.UXDB || column == null) {
            throw unsupported("UXDB ADD COLUMN requires the UXDB dialect and a column");
        }
        reject(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB,
                column.getFieldName());
        ColumnMetaData meta = column.getMetaData();
        if (meta.getType() == ColumnMetaData.TYPE_NONE) {
            throw unsupported("UXDB ADD COLUMN requires a known field type");
        }
        String definition = database.getFieldDefinition(meta, null, false, false, true);
        String prefix = " " + quotedColumn + "   ";
        if (!definition.startsWith(prefix)) {
            throw unsupported("UXDB ADD COLUMN could not reuse the existing field definition");
        }
        String type = definition.substring(prefix.length()).strip();
        String upper = type.toUpperCase(Locale.ROOT);
        if (type.isEmpty() || upper.contains(" NOT NULL") || upper.contains(" DEFAULT")) {
            throw unsupported("UXDB ADD COLUMN accepts only a nullable column without a default");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        var evidence = column.getMetadataEvidence();
        if (!column.isNullable()
                || (evidence.nullable().state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(
                evidence.nullable().value()))) {
            throw unsupported("UXDB ADD COLUMN does not add NOT NULL columns");
        }
        if (column.isPk()) {
            throw unsupported("UXDB ADD COLUMN does not add primary key columns");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("UXDB ADD COLUMN does not add columns with a default");
        }
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE) {
            throw unsupported("UXDB ADD COLUMN does not add identity columns");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("UXDB ADD COLUMN does not add generated columns");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
