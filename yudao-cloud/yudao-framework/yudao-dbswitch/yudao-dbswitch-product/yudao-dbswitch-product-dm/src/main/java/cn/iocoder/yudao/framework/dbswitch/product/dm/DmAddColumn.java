package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.function.Function;

/**
 * 为一个普通 DM 列执行可空 ADD；本模块原生 DDL 为 {@code ALTER TABLE ... ADD}，
 * 不使用 MySQL 的 {@code ADD COLUMN}；引用通过 {@link RestrictedSelectGuard} 处理，因为
 * CASE_SENSITIVE=1 将未加引号的名称按大写存储；本类不打开连接。
 */
public final class DmAddColumn {

    /**
     * 初始化 {@code DmAddColumn}。
     */
    private DmAddColumn() {
    }

    /**
     * 拒绝当前实现未支持的 {@code sql} 操作。
     */
    public static String sql(String schema, String table, ColumnDescription column,
            Function<ColumnMetaData, String> fieldDefinition) throws SQLFeatureNotSupportedException {
        if (column == null || fieldDefinition == null) {
            throw new IllegalArgumentException("DM add column requires a column and the existing field definition");
        }
        refuse(column);
        if (column.getMetaData().getType() == ColumnMetaData.TYPE_NONE) {
            throw new SQLFeatureNotSupportedException("DM nullable ADD COLUMN requires a recognized field type");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, column.getFieldName());
        String type = typeOnly(column.getMetaData().getName(), fieldDefinition.apply(column.getMetaData()));
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD " + quotedColumn + " " + type;
    }

    /**
     * 拒绝当前路径无法安全处理的输入或目标状态。
     */
    private static void refuse(ColumnDescription column) throws SQLFeatureNotSupportedException {
        if (!column.isNullable() || Boolean.FALSE.equals(column.getMetadataEvidence().nullable().value())) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support NOT NULL");
        }
        if (column.isPk()) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support primary keys");
        }
        String fallback = column.getDefaultValue();
        MetadataValue<String> declared = column.getMetadataEvidence().defaultExpression();
        boolean jdbcDefault = fallback != null && !"null".equals(fallback) && !"NULL".equals(fallback);
        if (jdbcDefault || declared.state() == MetadataValue.State.VALUE || declared.state() == MetadataValue.State.UNSUPPORTED) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support defaults");
        }
        MetadataValue<String> identity = column.getMetadataEvidence().identity();
        if (column.isAutoIncrement() || identity.state() == MetadataValue.State.VALUE
                || identity.state() == MetadataValue.State.UNSUPPORTED) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support identity");
        }
        MetadataValue<String> generated = column.getMetadataEvidence().generatedExpression();
        if (generated.state() == MetadataValue.State.VALUE || generated.state() == MetadataValue.State.UNSUPPORTED) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support generated columns");
        }
    }

    /** {@code getFieldDefinition} 以带引号的名称开头；仅类型尾部可复用。 */
    private static String typeOnly(String name, String definition) throws SQLFeatureNotSupportedException {
        String prefix = " " + DmSqlNames.quote(name) + "    ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw new IllegalArgumentException("DM field definition is not the existing getFieldDefinition layout");
        }
        String type = definition.substring(prefix.length());
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains(" NOT NULL")) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support NOT NULL");
        }
        if (upper.contains(" DEFAULT")) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support defaults");
        }
        if (upper.contains(" IDENTITY") || upper.contains(" AUTO_INCREMENT")) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support identity");
        }
        if (upper.contains(" GENERATED") || upper.contains(" AS(")) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support generated columns");
        }
        if (upper.contains(" PRIMARY KEY")) {
            throw new SQLFeatureNotSupportedException("DM nullable add column does not support primary keys");
        }
        if (type.isBlank() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("DM field definition is not a bare column type");
        }
        return type;
    }
}
