package cn.iocoder.yudao.framework.dbswitch.product.gbase8a;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGbase8aImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 允许空值且无默认值的 GBase 8a ADD COLUMN。schema、表和列的加引号处理使用
 * {@link RestrictedSelectGuard#quotePhysicalIdentifier}。类型文本来自 {@code getFieldDefinition}，
 * 生成定义时关闭备注、主键及自增开关，因此不复制 MySQL COMMENT / AUTO_INCREMENT /
 * VARCHAR BINARY。拒绝此产品尚未验证的类型和子句。
 * 此类不执行 SQL，也不提供实时服务器认证。
 */
public final class Gbase8aAddColumn {

    /** 已为 8.6.2.43-R7-free 记录的目录事实：decimal 65/30、varchar/text 8191 个字符、tinytext 255。 */
    private static final int DECIMAL_PRECISION = 65;

    private static final int DECIMAL_SCALE = 30;

    private static final int VARCHAR_CHARS = 8191;

    private static final int TEXT_CHARS = 8191;

    private static final int TINYTEXT_CHARS = 255;

    private static final Pattern TYPE = Pattern.compile(
            "DATETIME|TIME|DATE|TINYINT|BIGINT|INT|DECIMAL\\((\\d+)(?:, (\\d+))?\\)|CHAR\\(1\\)|VARCHAR\\((\\d+)\\)|TINYTEXT|TEXT",
            Pattern.CASE_INSENSITIVE);

    /**
     * 初始化 {@code Gbase8aAddColumn}。
     */
    private Gbase8aAddColumn() {
    }

    /** 与 {@link #sql} 相同的受检失败，不包装异常，使调用方直接看到功能异常。 */
    public static String statement(DatabaseGbase8aImpl database, String schema, String table,
            ColumnDescription column) {
        try {
            return sql(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            throw sneaky(unsupported);
        }
    }

    /**
     * 根据当前数据库方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseGbase8aImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null) {
            throw new IllegalArgumentException("GBase 8a dialect is required");
        }
        rejectConstrained(column);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GBASE8A, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GBASE8A, table);
        ColumnMetaData meta = column.getMetaData();
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GBASE8A, meta.getName());
        // pks/useAutoInc/withRemarks 保持关闭：这些开关会注入 AUTO_INCREMENT、BINARY 和 COMMENT。
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String type = typeTail(definition, quotedColumn);
        acceptType(type, meta);
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN" + definition;
    }

    /**
     * 拒绝带有当前路径未支持约束的目标对象。
     */
    private static void rejectConstrained(ColumnDescription column) throws SQLFeatureNotSupportedException {
        if (column == null) {
            throw new IllegalArgumentException("column");
        }
        ColumnMetadataEvidence evidence = column.getMetadataEvidence();
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
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE) {
            throw unsupported("identity");
        }
        String typeName = column.getFieldTypeName();
        boolean generated = evidence.generatedExpression().state() == MetadataValue.State.VALUE
                || (typeName != null && typeName.toLowerCase(Locale.ROOT).contains("generated"));
        if (generated) {
            throw unsupported("generated column");
        }
    }

    /**
     * 判断是否具有默认。
     */
    private static boolean hasDefault(ColumnDescription column) {
        if (column.getMetadataEvidence().defaultExpression().state() == MetadataValue.State.VALUE) {
            return true;
        }
        String value = column.getDefaultValue();
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        // JDBC 经常将缺失默认值报告为单词 null。其他文本均为实际默认值。
        return !trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed);
    }

    /**
     * 解析类型声明的长度、精度及后缀部分。
     */
    private static String typeTail(String definition, String quotedColumn) throws SQLFeatureNotSupportedException {
        String prefix = " " + quotedColumn;
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("unproven column quotation");
        }
        String type = definition.substring(prefix.length()).strip();
        if (type.isEmpty() || type.indexOf(';') >= 0) {
            throw unsupported("unproven type or MySQL clause");
        }
        return type;
    }

    /**
     * 检查当前原生类型是否属于允许的值域。
     */
    private static void acceptType(String type, ColumnMetaData meta) throws SQLFeatureNotSupportedException {
        Matcher matcher = TYPE.matcher(type);
        if (!matcher.matches()) {
            throw unsupported("unproven type or MySQL clause: " + type);
        }
        if (matcher.group(1) != null) {
            int precision = Integer.parseInt(matcher.group(1));
            int scale = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            if (precision < 1 || precision > DECIMAL_PRECISION || scale < 0 || scale > DECIMAL_SCALE || scale > precision) {
                throw unsupported("unproven decimal precision or scale: " + type);
            }
        }
        if (matcher.group(3) != null) {
            int length = Integer.parseInt(matcher.group(3));
            if (length < 1 || length > VARCHAR_CHARS) {
                throw unsupported("unproven varchar length: " + type);
            }
        }
        if (type.equalsIgnoreCase("TEXT") && (meta.getLength() < 1 || meta.getLength() > TEXT_CHARS)) {
            throw unsupported("TEXT length outside the probed 8191-character limit");
        }
        if (type.equalsIgnoreCase(
                "TINYTEXT") && meta.getLength() != 0 && (meta.getLength() < 1 || meta.getLength() > TINYTEXT_CHARS)) {
            throw unsupported("TINYTEXT length outside the probed 255-character limit");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException("GBase 8a nullable ADD COLUMN does not support " + feature, "0A000");
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
