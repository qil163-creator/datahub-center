package cn.iocoder.yudao.framework.dbswitch.product.tidb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 无默认值的可空 TiDB ADD COLUMN。
 * 模式、表与列由 {@link RestrictedSelectGuard} 按 TIDB 规则引用。
 * 类型令牌取自不附带备注的 {@link DatabaseTidbImpl#getFieldDefinition}。
 * 本类不执行 SQL，也不构成实时服务端认证。
 */
public final class TidbAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.TIDB;

    private static final Pattern FORBIDDEN_CLAUSE = Pattern.compile(
            "(?i)(^|\\s)(NOT\\s+NULL|DEFAULT|AUTO_INCREMENT|AUTO_RANDOM|GENERATED|PRIMARY\\s+KEY)(\\s|$)");

    /**
     * 初始化 {@code TidbAddColumn}。
     */
    private TidbAddColumn() {
    }

    /**
     * 根据当前数据库方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseTidbImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            TidbAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseTidbImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || schema == null || table == null || column == null) {
            throw new IllegalArgumentException("TiDB add column target is required");
        }
        if (database.getDatabaseType() != PRODUCT) {
            throw unsupported("TiDB nullable add column does not apply to this product");
        }
        ColumnMetaData meta = column.getMetaData();
        reject(column, meta);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        // 不附带备注；COMMENT 不属于此次可空、无默认值的加列操作。
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String type = typeOf(definition, quotedColumn);
        if (type.isEmpty() || type.indexOf(';') >= 0 || FORBIDDEN_CLAUSE.matcher(type).find()) {
            throw unsupported("TiDB add column type still contains a rejected clause");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 识别当前值或声明对应的数据类型。
     */
    private static String typeOf(String definition, String quotedColumn) throws SQLFeatureNotSupportedException {
        String prefix = " " + quotedColumn + "  ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("TiDB field definition did not use the quoted physical column");
        }
        return definition.substring(prefix.length()).strip();
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column, ColumnMetaData meta) throws SQLFeatureNotSupportedException {
        if (column.getDbType() != null && column.getDbType() != PRODUCT) {
            throw unsupported("TiDB add column does not accept another product column");
        }
        if (meta.getType() == ColumnMetaData.TYPE_NONE) {
            throw unsupported("TiDB nullable add column requires a mapped field type");
        }
        ColumnMetadataEvidence evidence = column.getMetadataEvidence();
        String declared = declaredClause(column);
        if (!column.isNullable()
                || (evidence.nullable().state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(
                evidence.nullable().value()))
                || declared.contains("NOT NULL")) {
            throw unsupported("TiDB nullable add column does not support NOT NULL");
        }
        if (column.isPk() || declared.contains("PRIMARY")) {
            throw unsupported("TiDB nullable add column does not support a primary key");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE
                || declared.contains("DEFAULT")) {
            throw unsupported("TiDB nullable add column does not support a default");
        }
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE
                || declared.contains("AUTO_RANDOM") || declared.contains("AUTO_INCREMENT")) {
            throw unsupported("TiDB nullable add column does not support AUTO_RANDOM or AUTO_INCREMENT");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE
                || declared.contains("GENERATED") || declared.contains(" AS (")) {
            throw unsupported("TiDB nullable add column does not support a generated column");
        }
        if (evidence.charset().state() == MetadataValue.State.VALUE || evidence.collation()
                .state() == MetadataValue.State.VALUE) {
            throw unsupported("TiDB nullable add column does not support character set or collation clauses");
        }
    }

    /** 仅包含字段类型与原生类型文本；JDBC 标志及凭据状态分别校验。 */
    private static String declaredClause(ColumnDescription column) {
        return (text(column.getFieldTypeName()) + "\n" + text(column.getMetadataEvidence().nativeType())).toUpperCase(
                Locale.ROOT);
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(MetadataValue<String> value) {
        return value.state() == MetadataValue.State.VALUE ? text(value.value()) : "";
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
