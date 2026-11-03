package cn.iocoder.yudao.framework.dbswitch.product.db2;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.function.Function;

/**
 * 一个可空 DB2 列：{@code ALTER TABLE "schema"."table" ADD "column" type}。
 * IBM 将 {@code COLUMN} 标记为可选；本类现有 DDL 不输出该关键字，此语句也不是 MySQL 的 {@code ADD COLUMN}。
 * 模式、表及列分别引用；类型尾部使用现有 {@code getFieldDefinition} 文本。
 * 本类不打开连接，也不认证服务端。
 */
public final class Db2AddColumn {

    /**
     * 初始化 {@code Db2AddColumn}。
     */
    private Db2AddColumn() {
    }

    /**
     * 根据当前DB2方言生成 SQL 语句或片段。
     */
    public static String sql(String schema, String table, ColumnDescription column,
            Function<ColumnMetaData, String> fieldDefinition) {
        if (column == null || fieldDefinition == null) {
            throw new IllegalArgumentException("DB2 add column requires a column and the existing field definition");
        }
        refuse(column);
        if (column.getMetaData().getType() == ColumnMetaData.TYPE_NONE) {
            unsupported("DB2 nullable ADD COLUMN requires a recognized field type");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DB2, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DB2, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DB2, column.getFieldName());
        String type = typeOnly(column.getFieldName(), fieldDefinition.apply(column.getMetaData()));
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD " + quotedColumn + " " + type;
    }

    /**
     * 拒绝当前路径无法安全处理的输入或目标状态。
     */
    private static void refuse(ColumnDescription column) {
        if (!column.isNullable() || Boolean.FALSE.equals(column.getMetadataEvidence().nullable().value())) {
            unsupported("DB2 nullable add column does not support NOT NULL");
        }
        if (column.isPk()) {
            unsupported("DB2 nullable add column does not support primary keys");
        }
        String fallback = column.getDefaultValue();
        MetadataValue<String> declared = column.getMetadataEvidence().defaultExpression();
        boolean jdbcDefault = fallback != null && !"null".equals(fallback) && !"NULL".equals(fallback);
        if (jdbcDefault || declared.state() == MetadataValue.State.VALUE || declared.state() == MetadataValue.State.UNSUPPORTED) {
            unsupported("DB2 nullable add column does not support defaults");
        }
        MetadataValue<String> identity = column.getMetadataEvidence().identity();
        if (column.isAutoIncrement() || identity.state() == MetadataValue.State.VALUE
                || identity.state() == MetadataValue.State.UNSUPPORTED) {
            unsupported("DB2 nullable add column does not support identity");
        }
        MetadataValue<String> generated = column.getMetadataEvidence().generatedExpression();
        if (generated.state() == MetadataValue.State.VALUE || generated.state() == MetadataValue.State.UNSUPPORTED) {
            unsupported("DB2 nullable add column does not support generated columns");
        }
    }

    /** {@code getFieldDefinition} 以带引号的原始名称及三个空格开头；仅类型尾部可复用。 */
    private static String typeOnly(String name, String definition) {
        String prefix = " " + Db2CursorMetadata.quote(name) + "   ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw new IllegalArgumentException("DB2 field definition is not the existing getFieldDefinition layout");
        }
        String type = definition.substring(prefix.length());
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains(" NOT NULL")) {
            unsupported("DB2 nullable add column does not support NOT NULL");
        }
        if (upper.contains(" DEFAULT")) {
            unsupported("DB2 nullable add column does not support defaults");
        }
        if (upper.contains(" IDENTITY") || upper.contains(" AUTO_INCREMENT")) {
            unsupported("DB2 nullable add column does not support identity");
        }
        if (upper.contains(" GENERATED") || upper.contains(" AS(")) {
            unsupported("DB2 nullable add column does not support generated columns");
        }
        if (upper.contains(" PRIMARY KEY")) {
            unsupported("DB2 nullable add column does not support primary keys");
        }
        if (type.isBlank() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("DB2 field definition is not a bare column type");
        }
        return type;
    }

    /** {@code getAddColumnSql} 不声明该受检拒绝异常。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason);
    }
}
