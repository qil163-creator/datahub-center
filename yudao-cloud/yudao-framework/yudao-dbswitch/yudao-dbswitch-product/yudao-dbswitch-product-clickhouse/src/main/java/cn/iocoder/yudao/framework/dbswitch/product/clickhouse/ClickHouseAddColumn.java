package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为单个普通 ClickHouse 列生成可空的 {@code ADD COLUMN}。可空性保留在
 * {@code getFieldDefinition} 返回的 {@code Nullable(T)} 文本内；语句不追加 NOT NULL。
 * 标识符使用 {@link RestrictedSelectGuard#quotePhysicalIdentifier}。此类不打开连接。
 */
public final class ClickHouseAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.CLICKHOUSE;

    private static final Pattern TYPE = Pattern.compile(
            "Nullable\\((?:Int16|Int32|Int64|Float32|Float64|Bool|Date32|DateTime64\\(9\\)|String"
                    + "|Decimal\\(([0-9]{1,2}),([0-9]{1,2})\\))\\)");

    /**
     * 初始化 {@code ClickHouseAddColumn}。
     */
    private ClickHouseAddColumn() {
    }

    /**
     * 根据当前ClickHouse方言生成 SQL 语句或片段。
     */
    public static String sql(String schema, String table, ColumnDescription column,
            Function<ColumnMetaData, String> fieldDefinition) throws SQLFeatureNotSupportedException {
        if (column == null || fieldDefinition == null) {
            throw new IllegalArgumentException(
                    "ClickHouse add column requires a column and the existing field definition");
        }
        refuse(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        String type = typeOnly(quotedColumn, fieldDefinition.apply(column.getMetaData()));
        var nativeType = column.getMetadataEvidence().nativeType();
        if (column.getMetadataEvidence().provenance().nativeProduct(PRODUCT)
                && nativeType.state() == MetadataValue.State.VALUE && !nativeType.value().equals(type)) {
            throw unsupported("ClickHouse nullable add column does not rewrite a native type");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 拒绝当前路径无法安全处理的输入或目标状态。
     */
    private static void refuse(ColumnDescription column) throws SQLFeatureNotSupportedException {
        var evidence = column.getMetadataEvidence();
        if (!column.isNullable() || Boolean.FALSE.equals(evidence.nullable().value())) {
            throw unsupported("ClickHouse nullable add column does not support NOT NULL");
        }
        if (column.isPk() || contains(column.getFieldTypeName(), "PRIMARY KEY")) {
            throw unsupported("ClickHouse nullable add column does not support a primary key");
        }
        if (lowCardinality(column.getFieldTypeName())
                || (evidence.nativeType().state() == MetadataValue.State.VALUE && lowCardinality(
                evidence.nativeType().value()))) {
            throw unsupported("ClickHouse nullable add column does not support LowCardinality");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE
                || contains(column.getFieldTypeName(), "DEFAULT")) {
            throw unsupported("ClickHouse nullable add column does not support DEFAULT");
        }
        if (evidence.defaultExpression().state() == MetadataValue.State.UNSUPPORTED
                || evidence.generatedExpression().state() == MetadataValue.State.UNSUPPORTED
                || evidence.generatedExpression().state() == MetadataValue.State.VALUE
                || contains(column.getFieldTypeName(), "MATERIALIZED")
                || contains(column.getFieldTypeName(), "ALIAS")
                || contains(column.getFieldTypeName(), "EPHEMERAL")) {
            throw unsupported("ClickHouse nullable add column does not support MATERIALIZED, ALIAS or EPHEMERAL");
        }
        if (column.isAutoIncrement()
                || evidence.identity().state() == MetadataValue.State.VALUE
                || evidence.identity().state() == MetadataValue.State.UNSUPPORTED
                || evidence.sequence().state() == MetadataValue.State.VALUE
                || evidence.sequence().state() == MetadataValue.State.UNSUPPORTED) {
            throw unsupported("ClickHouse nullable add column does not support identity");
        }
        if (evidence.charset().state() == MetadataValue.State.VALUE || evidence.collation()
                .state() == MetadataValue.State.VALUE) {
            throw unsupported("ClickHouse nullable add column does not support charset or collation");
        }
    }

    /** {@code getFieldDefinition} 以空格和带引号名称开头。只能复用其类型尾部。 */
    private static String typeOnly(String quotedColumn, String definition) throws SQLFeatureNotSupportedException {
        String prefix = " " + quotedColumn + " ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "ClickHouse field definition is not the existing getFieldDefinition layout");
        }
        String type = definition.substring(prefix.length());
        if (type.isBlank() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("ClickHouse field definition is not a bare column type");
        }
        if (!type.startsWith("Nullable(") || type.toUpperCase(Locale.ROOT).contains("NOT NULL")) {
            throw unsupported("ClickHouse nullable add column does not support NOT NULL");
        }
        if (lowCardinality(type)) {
            throw unsupported("ClickHouse nullable add column does not support LowCardinality");
        }
        Matcher decimal = TYPE.matcher(type);
        if (!decimal.matches()) {
            throw unsupported("ClickHouse nullable add column does not support this type text");
        }
        if (decimal.group(1) != null) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            if (precision < 1 || precision > 76 || scale > precision) {
                throw unsupported("ClickHouse nullable add column does not support this type text");
            }
        }
        return type;
    }

    /**
     * 判断当前类型声明是否使用 ClickHouse LowCardinality 包装。
     */
    private static boolean lowCardinality(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).contains("lowcardinality");
    }

    /**
     * 检查当前集合或结构是否包含指定对象。
     */
    private static boolean contains(String type, String word) {
        return type != null && type.toUpperCase(Locale.ROOT).contains(word);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
