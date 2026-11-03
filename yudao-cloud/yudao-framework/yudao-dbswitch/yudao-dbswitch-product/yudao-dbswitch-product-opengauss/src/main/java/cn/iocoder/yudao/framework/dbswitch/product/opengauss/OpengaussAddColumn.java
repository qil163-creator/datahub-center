package cn.iocoder.yudao.framework.dbswitch.product.opengauss;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOpengaussImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/**
 * 仅 {@code sql_compatibility=A} 的可空、无默认单列 {@code ADD COLUMN}。
 * 标识符只走 OPENGAUSS 普通名引用。类型文本复用既有 {@code getFieldDefinition}。
 * 不查询目录，不为 B、PG、C 或其他模式生成语句，也不回落到 PostgreSQL 父类语法。
 */
public final class OpengaussAddColumn {

    private static final String SQL_STATE = "0A000";

    /**
     * 初始化 {@code OpengaussAddColumn}。
     */
    private OpengaussAddColumn() {
    }

    /** 方言入口。兼容模式固定为 A，其它模式不会从这里生成。 */
    public static String sql(DatabaseOpengaussImpl database, String schema, String table, ColumnDescription column) {
        return sql(database, schema, table, column, "A");
    }

    /**
     * @param compatibility {@code current_setting('sql_compatibility')} 的值；只有精确的 {@code A} 被接受
     */
    public static String sql(DatabaseOpengaussImpl database, String schema, String table, ColumnDescription column,
            String compatibility) {
        try {
            return checked(database, schema, table, column, compatibility);
        } catch (SQLFeatureNotSupportedException unsupported) {
            OpengaussAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseOpengaussImpl database, String schema, String table,
            ColumnDescription column, String compatibility) throws SQLFeatureNotSupportedException {
        if (database == null || database.getDatabaseType() != ProductTypeEnum.OPENGAUSS) {
            throw unsupported("openGauss ADD COLUMN requires the openGauss dialect");
        }
        if (!"A".equals(compatibility)) {
            throw unsupported("openGauss ADD COLUMN does not apply outside sql_compatibility A");
        }
        if (column == null) {
            throw unsupported("openGauss ADD COLUMN requires a column");
        }
        if (column.getDbType() != null && column.getDbType() != ProductTypeEnum.OPENGAUSS) {
            throw unsupported("openGauss ADD COLUMN does not accept another product column");
        }
        reject(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS,
                column.getFieldName());
        String type = typeToken(database, column, quotedColumn);
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        var evidence = column.getMetadataEvidence();
        MetadataValue<Boolean> nullable = evidence.nullable();
        if (!column.isNullable()
                || nullable.state() == MetadataValue.State.UNSUPPORTED
                || (nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value()))) {
            throw unsupported("openGauss ADD COLUMN does not add NOT NULL columns");
        }
        if (column.isPk()) {
            throw unsupported("openGauss ADD COLUMN does not add primary key columns");
        }
        MetadataValue<String> defaults = evidence.defaultExpression();
        if (column.getDefaultValue() != null
                || defaults.state() == MetadataValue.State.VALUE
                || defaults.state() == MetadataValue.State.UNSUPPORTED) {
            throw unsupported("openGauss ADD COLUMN does not add columns with a default");
        }
        MetadataValue<String> identity = evidence.identity();
        if (column.isAutoIncrement()
                || identity.state() == MetadataValue.State.VALUE
                || identity.state() == MetadataValue.State.UNSUPPORTED
                || identityType(column.getFieldTypeName())) {
            throw unsupported("openGauss ADD COLUMN does not add identity columns");
        }
        MetadataValue<String> generated = evidence.generatedExpression();
        MetadataValue<?> sequence = evidence.sequence();
        if (generated.state() == MetadataValue.State.VALUE
                || generated.state() == MetadataValue.State.UNSUPPORTED
                || sequence.state() == MetadataValue.State.VALUE
                || sequence.state() == MetadataValue.State.UNSUPPORTED
                || generatedType(column.getFieldTypeName())) {
            throw unsupported("openGauss ADD COLUMN does not add generated columns");
        }
    }

    /**
     * 识别当前列定义中的标识列类型。
     */
    private static boolean identityType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        String folded = typeName.toLowerCase(Locale.ROOT).trim();
        return folded.equals("serial") || folded.equals("bigserial") || folded.equals("smallserial")
                || folded.contains("identity");
    }

    /**
     * 识别当前列定义中的生成列表达式类型。
     */
    private static boolean generatedType(String typeName) {
        return typeName != null && typeName.toLowerCase(Locale.ROOT).contains("generated");
    }

    /** {@code getFieldDefinition} 前导带引号列名。时间类型只有一个空格，其余类型是三个空格。 */
    private static String typeToken(DatabaseOpengaussImpl database, ColumnDescription column, String quotedColumn)
            throws SQLFeatureNotSupportedException {
        ColumnMetaData meta = column.getMetaData();
        if (meta.getType() == ColumnMetaData.TYPE_NONE) {
            throw unsupported("openGauss ADD COLUMN requires a known field type");
        }
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String marker = " " + quotedColumn + " ";
        if (definition == null || !definition.startsWith(marker)) {
            throw unsupported("openGauss ADD COLUMN could not reuse the existing field definition");
        }
        String type = definition.substring(marker.length()).strip();
        String upper = type.toUpperCase(Locale.ROOT);
        if (type.isEmpty()
                || upper.contains(" NOT NULL")
                || upper.contains(" DEFAULT")
                || upper.contains("PRIMARY")
                || upper.contains("GENERATED")
                || upper.contains("IDENTITY")
                || upper.contains("SERIAL")
                || type.indexOf(';') >= 0
                || type.indexOf('\n') >= 0
                || type.indexOf('\r') >= 0) {
            throw unsupported("openGauss ADD COLUMN accepts only a nullable column without a default");
        }
        return type;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, SQL_STATE);
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneaky(Throwable exception) throws E {
        throw (E) exception;
    }
}
