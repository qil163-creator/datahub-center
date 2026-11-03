package cn.iocoder.yudao.framework.dbswitch.product.oracle;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.function.Function;

/**
 * 一个可空列：{@code ALTER TABLE "schema"."table" ADD ("column" type)}。
 * 按输入原样引用名称；未加引号名称的大写折叠由解析器处理，此处不改变。
 */
public final class OracleAddColumn {

    /**
     * 初始化 {@code OracleAddColumn}。
     */
    private OracleAddColumn() {
    }

    /**
     * 根据当前Oracle方言生成 SQL 语句或片段。
     */
    public static String sql(String schema, String table, ColumnDescription column,
            Function<ColumnMetaData, String> fieldDefinition) {
        reject(column);
        if (column.getMetaData().getType() == ColumnMetaData.TYPE_NONE) {
            unsupported("Oracle nullable ADD COLUMN requires a recognized field type");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE,
                column.getFieldName());
        String definition = fieldDefinition.apply(column.getMetaData());
        if (definition == null) {
            throw new IllegalArgumentException("Oracle field definition is required");
        }
        String trimmed = definition.trim();
        if (!trimmed.startsWith(quotedColumn)) {
            throw new IllegalArgumentException("Oracle field definition did not quote the physical column");
        }
        String type = trimmed.substring(quotedColumn.length()).trim();
        if (type.isEmpty() || hasConstraint(type)) {
            unsupported("Oracle nullable add column accepts a type only");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD (" + quotedColumn + " " + type + ")";
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) {
        if (column == null || column.getFieldName() == null || column.getFieldName().isBlank()) {
            throw new IllegalArgumentException("Oracle add column requires a physical column name");
        }
        var evidence = column.getMetadataEvidence();
        if (!column.isNullable() || refusedNullable(evidence.nullable())) {
            unsupported("Oracle nullable add column does not accept NOT NULL");
        }
        if (column.isPk()) {
            unsupported("Oracle nullable add column does not accept a primary key");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE) {
            unsupported("Oracle nullable add column does not accept a default");
        }
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE) {
            unsupported("Oracle nullable add column does not accept an identity column");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE) {
            unsupported("Oracle nullable add column does not accept a generated column");
        }
    }

    /**
     * 根据当前列可空性生成拒绝原因或映射结果。
     */
    private static boolean refusedNullable(MetadataValue<Boolean> nullable) {
        return nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value());
    }

    /**
     * 判断是否具有约束。
     */
    private static boolean hasConstraint(String type) {
        String upper = type.toUpperCase(Locale.ROOT);
        return upper.contains(" NOT NULL") || upper.contains(" DEFAULT") || upper.contains(" GENERATED")
                || upper.contains(" IDENTITY") || upper.contains(" PRIMARY");
    }

    /** 受检拒绝异常，不扩宽 {@code getAddColumnSql} 的方法签名。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
