package cn.iocoder.yudao.framework.dbswitch.product.mysql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.regex.Pattern;

/** 无默认值的可空 MySQL ADD COLUMN，不构成实时服务端认证。 */
public final class MysqlAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.MYSQL;

    private static final Pattern FORBIDDEN_CLAUSE = Pattern.compile(
            "(?i)(^|\\s)(NOT\\s+NULL|DEFAULT|AUTO_INCREMENT|GENERATED|PRIMARY\\s+KEY)(\\s|$)");

    /**
     * 初始化 {@code MysqlAddColumn}。
     */
    private MysqlAddColumn() {
    }

    /**
     * 根据当前MySQL方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseMysqlImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || database.getClass() != DatabaseMysqlImpl.class || column == null) {
            throw unsupported("MySQL nullable add column requires the MySQL dialect and a column");
        }
        if (column.getMetaData().getType() == cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_NONE) {
            throw unsupported("Nullable ADD COLUMN requires a recognized field type");
        }
        reject(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String type = typeOf(definition, quotedColumn);
        if (type.isEmpty() || FORBIDDEN_CLAUSE.matcher(type).find()) {
            throw unsupported("MySQL add column type still contains a rejected clause");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 识别当前值或声明对应的数据类型。
     */
    private static String typeOf(String definition, String quotedColumn) throws SQLFeatureNotSupportedException {
        String prefix = " " + quotedColumn + "  ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("MySQL field definition did not use the quoted physical column");
        }
        return definition.substring(prefix.length()).strip();
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        var nullable = column.getMetadataEvidence().nullable();
        if (!column.isNullable() || (nullable.state() == MetadataValue.State.VALUE && Boolean.FALSE.equals(
                nullable.value()))) {
            throw unsupported("MySQL nullable add column does not accept NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("MySQL nullable add column does not accept a primary key");
        }
        if (hasDefault(column)) {
            throw unsupported("MySQL nullable add column does not accept a default");
        }
        if (column.isAutoIncrement()
                || column.getMetadataEvidence().identity().state() == MetadataValue.State.VALUE
                || column.getMetadataEvidence().sequence().state() == MetadataValue.State.VALUE) {
            throw unsupported("MySQL nullable add column does not accept identity");
        }
        String typeName = column.getFieldTypeName();
        if (column.getMetadataEvidence().generatedExpression().state() == MetadataValue.State.VALUE
                || (typeName != null && typeName.toLowerCase(java.util.Locale.ROOT).contains("generated"))) {
            throw unsupported("MySQL nullable add column does not accept a generated column");
        }
    }

    /** 采用与 {@code DatabaseMysqlImpl#getFieldDefinition} 相同的无默认值标记，其他文本均视为默认值。 */
    private static boolean hasDefault(ColumnDescription column) {
        if (column.getMetadataEvidence().defaultExpression().state() == MetadataValue.State.VALUE) {
            return true;
        }
        String value = column.getDefaultValue();
        return value != null && !"null".equals(value) && !"NULL".equals(value);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
