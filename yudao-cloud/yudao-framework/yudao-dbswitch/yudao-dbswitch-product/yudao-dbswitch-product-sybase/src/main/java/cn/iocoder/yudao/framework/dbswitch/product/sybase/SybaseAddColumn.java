package cn.iocoder.yudao.framework.dbswitch.product.sybase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSybaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/**
 * 为 Sybase ASE 添加可空且无默认值的列。
 * 名称经 {@link RestrictedSelectGuard#quotePhysicalIdentifier} 按 SYBASE 规则使用方括号。
 * 类型文本取自 {@link DatabaseSybaseImpl#getFieldDefinition} 尾部，此类不自行构造类型。
 * 此处 ASE 语法为 {@code ALTER TABLE [schema].[table] ADD [column] type}，而非 {@code ADD COLUMN}。
 * 不打开连接。
 */
public final class SybaseAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.SYBASE;

    /**
     * 初始化 {@code SybaseAddColumn}。
     */
    private SybaseAddColumn() {
    }

    /**
     * 根据当前数据库方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseSybaseImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            SybaseAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseSybaseImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || column == null) {
            throw new IllegalArgumentException("Sybase add column requires the Sybase dialect and a column");
        }
        if (database.getDatabaseType() != PRODUCT) {
            throw unsupported("Sybase nullable add column does not apply to this product");
        }
        if (column.getDbType() != null && column.getDbType() != PRODUCT) {
            throw unsupported("Sybase add column does not accept another product column");
        }
        if (column.getMetaData().getType() == cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_NONE) {
            throw unsupported("Nullable ADD COLUMN requires a recognized field type");
        }
        reject(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String type = typeTail(definition, column.getFieldName());
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD " + quotedColumn + " " + type;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        MetadataValue<Boolean> nullable = column.getMetadataEvidence().nullable();
        if (!column.isNullable()
                || (nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value()))) {
            throw unsupported("Sybase nullable add column does not accept NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("Sybase nullable add column does not accept a primary key");
        }
        MetadataValue<String> defaults = column.getMetadataEvidence().defaultExpression();
        if (column.getDefaultValue() != null || blocked(defaults)) {
            throw unsupported("Sybase nullable add column does not accept a default");
        }
        if (column.isAutoIncrement() || blocked(column.getMetadataEvidence().identity())
                || blocked(column.getMetadataEvidence().sequence()) || identityType(column.getFieldTypeName())) {
            throw unsupported("Sybase nullable add column does not accept identity");
        }
        if (blocked(column.getMetadataEvidence().generatedExpression()) || generatedType(column.getFieldTypeName())) {
            throw unsupported("Sybase nullable add column does not accept a generated column");
        }
    }

    /** {@code getFieldDefinition} 以 {@code [name]} 和两个空格开头，仅复用类型尾部文本。 */
    private static String typeTail(String definition, String fieldName) throws SQLFeatureNotSupportedException {
        String prefix = " [" + fieldName + "]  ";
        if (definition == null || fieldName == null || !definition.startsWith(prefix)) {
            throw unsupported("Sybase field definition did not use the existing quoted column layout");
        }
        String type = definition.substring(prefix.length());
        if (type.isBlank() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Sybase field definition is not a bare column type");
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains(" NOT NULL")) {
            throw unsupported("Sybase nullable add column does not accept NOT NULL");
        }
        if (upper.contains(" DEFAULT")) {
            throw unsupported("Sybase nullable add column does not accept a default");
        }
        if (upper.contains(" IDENTITY")) {
            throw unsupported("Sybase nullable add column does not accept identity");
        }
        if (upper.contains(" PRIMARY")) {
            throw unsupported("Sybase nullable add column does not accept a primary key");
        }
        return type;
    }

    /**
     * 记录当前目标受阻或不可继续处理的状态。
     */
    private static boolean blocked(MetadataValue<?> value) {
        return value.state() == MetadataValue.State.VALUE || value.state() == MetadataValue.State.UNSUPPORTED;
    }

    /**
     * 识别当前列定义中的标识列类型。
     */
    private static boolean identityType(String typeName) {
        return typeName != null && typeName.toLowerCase(Locale.ROOT).contains("identity");
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
