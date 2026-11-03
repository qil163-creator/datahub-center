package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无默认值的可空 Doris ADD COLUMN。模式、表和列使用
 * {@link RestrictedSelectGuard#quotePhysicalIdentifier} 加引号。类型文本采用关闭备注的 OLAP
 * {@code getFieldDefinition}，因此不会复制父级 COMMENT 子句。
 * 该定义不输出 AUTO_INCREMENT 或 BINARY。KEY 列、聚合列以及
 * 此产品尚未证明的子句均拒绝。此类不执行 SQL，也不代表
 * 真实服务器认证。
 */
public final class DorisAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.DORIS;

    /** 此产品的 Doris CREATE TABLE 路径和 OLAP 字段定义已输出的类型。 */
    private static final Pattern TYPE = Pattern.compile(
            "DATETIME|DATE|TINYINT|BIGINT|INT|DOUBLE|STRING|VARCHAR\\((\\d+)\\)|DECIMAL\\((\\d+),0\\)");

    private static final Pattern AGGREGATE = Pattern.compile(
            "(?i)\\b(REPLACE_IF_NOT_NULL|BITMAP_UNION|HLL_UNION|QUANTILE_UNION|SUM|MAX|MIN|REPLACE)\\b");

    private static final Pattern KEY_WORD = Pattern.compile("(?i)\\bkey\\b");

    private static final int VARCHAR_BYTES = 65_533;

    private static final int DECIMAL_PRECISION = 38;

    /**
     * 初始化 {@code DorisAddColumn}。
     */
    private DorisAddColumn() {
    }

    /** 与 {@link #sql} 相同的受检异常，直接传播，不包装。 */
    public static String statement(DatabaseDorisImpl database, String schema, String table, ColumnDescription column) {
        try {
            return sql(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            throw sneaky(unsupported);
        }
    }

    /**
     * 根据当前Doris方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseDorisImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || database.getClass() != DatabaseDorisImpl.class) {
            throw new IllegalArgumentException("Doris dialect is required");
        }
        rejectConstrained(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        ColumnMetaData meta = column.getMetaData();
        rejectValueType(meta.getType());
        // 备注、键列表及自增保持关闭。启用这些开关会复制 COMMENT。
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String type = typeTail(definition, meta.getName());
        acceptType(type);
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type + " NULL";
    }

    /**
     * 拒绝带有当前路径未支持约束的目标对象。
     */
    private static void rejectConstrained(ColumnDescription column) throws SQLFeatureNotSupportedException {
        if (column == null) {
            throw new IllegalArgumentException("column");
        }
        rejectNativeName(column.getFieldTypeName());
        var evidence = column.getMetadataEvidence();
        boolean notNull = !column.isNullable()
                || (evidence.nullable().state() == MetadataValue.State.VALUE && Boolean.FALSE.equals(
                evidence.nullable().value()));
        if (notNull) {
            throw unsupported("NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("primary key");
        }
        if (hasDefault(column)) {
            throw unsupported("default");
        }
        if (column.isAutoIncrement()
                || evidence.identity().state() == MetadataValue.State.VALUE
                || evidence.sequence().state() == MetadataValue.State.VALUE) {
            throw unsupported("identity");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE) {
            throw unsupported("generated column");
        }
    }

    /**
     * 裸 DATETIME 是 OLAP 字段定义。不重现括号内的精度。
     */
    private static void rejectNativeName(String typeName) throws SQLFeatureNotSupportedException {
        if (typeName == null || typeName.isBlank()) {
            return;
        }
        String normalized = typeName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("generated")) {
            throw unsupported("generated column");
        }
        if (AGGREGATE.matcher(typeName).find()) {
            throw unsupported("aggregate column");
        }
        if (normalized.contains("auto_increment")) {
            throw unsupported("identity");
        }
        if (normalized.contains("primary key")) {
            throw unsupported("primary key");
        }
        if (KEY_WORD.matcher(typeName).find()) {
            throw unsupported("KEY model");
        }
        if (normalized.contains("binary") || normalized.contains("blob")) {
            throw unsupported("BINARY");
        }
        if (normalized.equals("time") || normalized.startsWith("time(")
                || normalized.equals("timestamp") || normalized.startsWith("timestamp(")) {
            throw unsupported("TIME");
        }
        // 裸 DATETIME 是 OLAP 字段定义。不重现括号内的精度。
        if (normalized.startsWith("datetime(")) {
            throw unsupported("unproven type or MySQL clause");
        }
    }

    /**
     * 拒绝当前增列路径不支持的逻辑值类型。
     */
    private static void rejectValueType(int type) throws SQLFeatureNotSupportedException {
        switch (type) {
            case ColumnMetaData.TYPE_STRING:
            case ColumnMetaData.TYPE_DATE:
            case ColumnMetaData.TYPE_BOOLEAN:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_BIGNUMBER:
            case ColumnMetaData.TYPE_TIMESTAMP:
                return;
            case ColumnMetaData.TYPE_BINARY:
                throw unsupported("BINARY");
            case ColumnMetaData.TYPE_TIME:
                throw unsupported("TIME");
            default:
                throw unsupported("unproven type or MySQL clause");
        }
    }

    /** 与 OLAP {@code getFieldDefinition} 使用相同的无默认值文本；其他任何文本都表示默认值。 */
    private static boolean hasDefault(ColumnDescription column) {
        if (column.getMetadataEvidence().defaultExpression().state() == MetadataValue.State.VALUE) {
            return true;
        }
        String value = column.getDefaultValue();
        return value != null && !"null".equals(value) && !"NULL".equals(value);
    }

    /**
     * 解析类型声明的长度、精度及后缀部分。
     */
    private static String typeTail(String definition, String name) throws SQLFeatureNotSupportedException {
        String prefix = " `" + name + "`  ";
        if (definition == null || name == null || !definition.startsWith(prefix)) {
            throw unsupported("unproven column quotation");
        }
        String type = definition.substring(prefix.length()).strip();
        if (type.isEmpty() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw unsupported("unproven type or MySQL clause");
        }
        return type;
    }

    /**
     * 检查当前原生类型是否属于允许的值域。
     */
    private static void acceptType(String type) throws SQLFeatureNotSupportedException {
        Matcher matcher = TYPE.matcher(type);
        if (!matcher.matches()) {
            throw unsupported("unproven type or MySQL clause: " + type);
        }
        if (matcher.group(1) != null) {
            int length = Integer.parseInt(matcher.group(1));
            if (length < 1 || length > VARCHAR_BYTES) {
                throw unsupported("unproven varchar length: " + type);
            }
        }
        if (matcher.group(2) != null) {
            int precision = Integer.parseInt(matcher.group(2));
            if (precision < 1 || precision > DECIMAL_PRECISION) {
                throw unsupported("unproven decimal precision: " + type);
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException("Doris nullable ADD COLUMN does not support " + feature, "0A000");
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
