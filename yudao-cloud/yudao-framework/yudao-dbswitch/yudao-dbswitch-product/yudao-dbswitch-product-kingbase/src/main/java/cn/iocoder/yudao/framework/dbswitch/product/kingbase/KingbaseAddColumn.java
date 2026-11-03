package cn.iocoder.yudao.framework.dbswitch.product.kingbase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/**
 * 为 KingbaseES 添加可空且无默认值的列。
 * 模式、表和列由 {@link RestrictedSelectGuard} 按 KINGBASE 规则加引号。
 * 此处不探测或添加兼容模式；类型文本取自
 * {@link DatabaseKingbaseImpl#getFieldDefinition}，复用前拒绝 NOT NULL、键、默认值、自增标识
 * 和生成列。
 */
public final class KingbaseAddColumn {

    /**
     * 初始化 {@code KingbaseAddColumn}。
     */
    private KingbaseAddColumn() {
    }

    /**
     * 根据当前Kingbase方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseKingbaseImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            KingbaseAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseKingbaseImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || schema == null || table == null || column == null) {
            throw new IllegalArgumentException("Kingbase add column target is required");
        }
        if (database.getDatabaseType() != ProductTypeEnum.KINGBASE) {
            throw unsupported("Kingbase nullable add column does not apply to this product");
        }
        if (column.getDbType() != null && column.getDbType() != ProductTypeEnum.KINGBASE) {
            throw unsupported("Kingbase add column does not accept another product column");
        }
        var evidence = column.getMetadataEvidence();
        if (!column.isNullable() || nullableFalse(evidence.nullable())) {
            throw unsupported("Kingbase NOT NULL add column is not supported");
        }
        if (column.isPk()) {
            throw unsupported("Kingbase primary key add column is not supported");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("Kingbase default add column is not supported");
        }
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE || identityType(
                column.getFieldTypeName())) {
            throw unsupported("Kingbase identity add column is not supported");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE || generatedType(
                column.getFieldTypeName())) {
            throw unsupported("Kingbase generated add column is not supported");
        }
        ColumnMetaData meta = column.getMetaData();
        if (meta.getType() == ColumnMetaData.TYPE_NONE) {
            throw unsupported("Kingbase add column requires a recognized field type");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE,
                column.getFieldName());
        String type = typeToken(database.getFieldDefinition(meta, null, false, false, false), meta.getName());
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 判断当前元数据是否明确声明列不可为空。
     */
    private static boolean nullableFalse(MetadataValue<Boolean> nullable) {
        return nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value());
    }

    /**
     * 识别当前列定义中的标识列类型。
     */
    private static boolean identityType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        String folded = typeName.toLowerCase(Locale.ROOT).trim();
        return folded.equals("serial") || folded.equals("bigserial") || folded.equals("smallserial") || folded.contains(
                "identity");
    }

    /**
     * 识别当前列定义中的生成列表达式类型。
     */
    private static boolean generatedType(String typeName) {
        return typeName != null && typeName.toLowerCase(Locale.ROOT).contains("generated");
    }

    /**
     * 提取并规范化原生类型名称词元。
     */
    private static String typeToken(String definition, String fieldName) throws SQLFeatureNotSupportedException {
        String prefix = " \"" + fieldName + "\"   ";
        if (definition == null || fieldName == null || !definition.startsWith(prefix)) {
            throw unsupported("Kingbase field definition did not expose a reusable type");
        }
        String type = definition.substring(prefix.length());
        if (!type.matches("[A-Za-z][A-Za-z0-9_(), ]*") || type.contains("  ")) {
            throw unsupported("Kingbase field definition type is not a plain type token");
        }
        String folded = type.toUpperCase(Locale.ROOT);
        if (folded.contains("DEFAULT") || folded.contains("NOT NULL") || folded.contains("SERIAL")
                || folded.contains("IDENTITY") || folded.contains("GENERATED") || folded.contains("PRIMARY")) {
            throw unsupported("Kingbase field definition is not a nullable plain type");
        }
        return type;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneaky(Throwable exception) throws E {
        throw (E) exception;
    }
}
