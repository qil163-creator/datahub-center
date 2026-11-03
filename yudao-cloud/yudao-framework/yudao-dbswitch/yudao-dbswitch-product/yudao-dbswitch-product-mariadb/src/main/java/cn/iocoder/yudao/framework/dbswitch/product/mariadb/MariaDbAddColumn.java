package cn.iocoder.yudao.framework.dbswitch.product.mariadb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMariaDBImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 无默认值的可空 MariaDB ADD COLUMN。
 * 模式、表与列由 {@link RestrictedSelectGuard} 按 MARIADB 规则引用。
 * 类型令牌取自不附带备注的 {@link DatabaseMariaDBImpl#getFieldDefinition}。
 * 本类不执行 SQL，也不构成实时服务端认证。
 */
public final class MariaDbAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.MARIADB;

    private static final Pattern FORBIDDEN_CLAUSE = Pattern.compile(
            "(?i)(^|\\s)(NOT\\s+NULL|DEFAULT|AUTO_INCREMENT|GENERATED|PRIMARY\\s+KEY)(\\s|$)");

    /**
     * 初始化 {@code MariaDbAddColumn}。
     */
    private MariaDbAddColumn() {
    }

    /**
     * 根据当前MariaDB方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseMariaDBImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            MariaDbAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseMariaDBImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || schema == null || table == null || column == null) {
            throw new IllegalArgumentException("MariaDB add column target is required");
        }
        if (database.getDatabaseType() != PRODUCT) {
            throw unsupported("MariaDB nullable add column does not apply to this product");
        }
        if (column.getDbType() != null && column.getDbType() != PRODUCT) {
            throw unsupported("MariaDB add column does not accept another product column");
        }
        if (column.getMetaData().getType() == cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_NONE) {
            throw unsupported("Nullable ADD COLUMN requires a recognized field type");
        }
        reject(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        // 不附带备注；COMMENT 不属于此次可空、无默认值的加列操作。
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String type = typeOf(definition, quotedColumn);
        if (type.isEmpty() || type.indexOf(';') >= 0 || FORBIDDEN_CLAUSE.matcher(type).find()) {
            throw unsupported("MariaDB add column type still contains a rejected clause");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 识别当前值或声明对应的数据类型。
     */
    private static String typeOf(String definition, String quotedColumn) throws SQLFeatureNotSupportedException {
        String prefix = " " + quotedColumn + "  ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("MariaDB field definition did not use the quoted physical column");
        }
        return definition.substring(prefix.length()).strip();
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        var nullable = column.getMetadataEvidence().nullable();
        if (!column.isNullable() || (nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(
                nullable.value()))) {
            throw unsupported("MariaDB nullable add column does not accept NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("MariaDB nullable add column does not accept a primary key");
        }
        if (column.getDefaultValue() != null
                || column.getMetadataEvidence().defaultExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("MariaDB nullable add column does not accept a default");
        }
        if (column.isAutoIncrement() || column.getMetadataEvidence().identity().state() == MetadataValue.State.VALUE
                || identityType(column.getFieldTypeName())) {
            throw unsupported("MariaDB nullable add column does not accept identity");
        }
        if (column.getMetadataEvidence().generatedExpression().state() == MetadataValue.State.VALUE
                || generatedType(column.getFieldTypeName())) {
            throw unsupported("MariaDB nullable add column does not accept a generated column");
        }
    }

    /**
     * 识别当前列定义中的标识列类型。
     */
    private static boolean identityType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        String folded = typeName.toLowerCase(Locale.ROOT);
        return folded.contains("auto_increment") || folded.contains("identity");
    }

    /**
     * 识别当前列定义中的生成列表达式类型。
     */
    private static boolean generatedType(String typeName) {
        return typeName != null && typeName.toLowerCase(Locale.ROOT).contains("generated");
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
