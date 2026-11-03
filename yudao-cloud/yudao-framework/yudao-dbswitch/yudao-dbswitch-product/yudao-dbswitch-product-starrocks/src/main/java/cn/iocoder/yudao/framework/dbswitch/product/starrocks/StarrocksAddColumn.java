package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 无默认值的可空 StarRocks ADD COLUMN。
 * 模式、表和列由 {@link RestrictedSelectGuard} 按 STARROCKS 规则加引号。
 * 类型标记使用关闭备注的现有 OLAP {@code getFieldDefinition}，而非 MySQL 子句。
 * 此类不执行 SQL，也不代表真实服务器认证。
 */
public final class StarrocksAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.STARROCKS;

    /** 此类已经输出的类型。有意不包括 MySQL TINYINT/TEXT/BLOB/DECIMAL(p, s)。 */
    private static final Pattern OLAP_TYPE = Pattern.compile(
            "DATETIME|DATE|BOOLEAN|BIGINT|INT|DOUBLE|STRING|DECIMAL\\([1-9][0-9]*,0\\)|VARCHAR\\([1-9][0-9]*\\)");

    private static final Pattern AGGREGATE = Pattern.compile(
            "(?i)(^|[^A-Za-z0-9_])(AGGREGATE|SUM|MIN|MAX|REPLACE_IF_NOT_NULL|REPLACE|HLL_UNION|BITMAP_UNION|PERCENTILE_UNION)([^A-Za-z0-9_]|$)");

    private static final Pattern KEY_MODEL = Pattern.compile(
            "(?i)(^|[^A-Za-z0-9_])(PRIMARY\\s+KEY|PRIMARY|KEY)([^A-Za-z0-9_]|$)");

    private static final Pattern NOT_NULL = Pattern.compile("(?i)(^|[^A-Za-z0-9_])NOT\\s+NULL([^A-Za-z0-9_]|$)");

    private static final Pattern DEFAULT = Pattern.compile("(?i)(^|[^A-Za-z0-9_])DEFAULT([^A-Za-z0-9_]|$)");

    private static final Pattern GENERATED = Pattern.compile("(?i)GENERATED|\\bAS\\s*\\(");

    private static final Pattern AUTO_INCREMENT = Pattern.compile("(?i)AUTO_INCREMENT");

    private static final Pattern MYSQL_CLAUSE = Pattern.compile(
            "(?i)(CHARACTER\\s+SET|CHARSET|COLLATE|UNSIGNED|\\bBINARY\\b|\\bCOMMENT\\b|\\bALGORITHM\\b|\\bENGINE\\b|\\bAFTER\\b|\\bFIRST\\b)");

    /**
     * 初始化 {@code StarrocksAddColumn}。
     */
    private StarrocksAddColumn() {
    }

    /**
     * 根据当前StarRocks方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseStarrocksImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            StarrocksAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseStarrocksImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || schema == null || table == null || column == null) {
            throw new IllegalArgumentException("StarRocks add column target is required");
        }
        if (database.getDatabaseType() != PRODUCT) {
            throw unsupported("StarRocks nullable add column does not apply to this product");
        }
        if (column.getDbType() != PRODUCT) {
            throw unsupported("StarRocks nullable add column does not accept another product column");
        }
        ColumnMetaData meta = column.getMetaData();
        reject(column, meta);
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, meta.getName());
        // 备注保持关闭。此可空且无默认值的添加操作不包含 COMMENT。
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String type = typeOf(definition, quotedColumn);
        if (!OLAP_TYPE.matcher(type).matches() || type.indexOf(';') >= 0) {
            throw unsupported("StarRocks nullable add column does not support an unproven type or MySQL clause");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 识别当前值或声明对应的数据类型。
     */
    private static String typeOf(String definition, String quotedColumn) throws SQLFeatureNotSupportedException {
        String prefix = " " + quotedColumn + "  ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("StarRocks field definition did not use the quoted physical column");
        }
        return definition.substring(prefix.length()).strip();
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column, ColumnMetaData meta) throws SQLFeatureNotSupportedException {
        ColumnMetadataEvidence evidence = column.getMetadataEvidence();
        String declared = declaredClause(column);
        if (AGGREGATE.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support an aggregate column");
        }
        if (column.isPk() || KEY_MODEL.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support a primary key model");
        }
        if (!column.isNullable()
                || (evidence.nullable().state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(
                evidence.nullable().value()))
                || NOT_NULL.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support NOT NULL");
        }
        if (column.getDefaultValue() != null || evidence.defaultExpression().state() == MetadataValue.State.VALUE
                || DEFAULT.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support a default");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE || GENERATED.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support a generated column");
        }
        if (column.isAutoIncrement() || evidence.identity().state() == MetadataValue.State.VALUE
                || AUTO_INCREMENT.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support AUTO_INCREMENT");
        }
        if (evidence.charset().state() == MetadataValue.State.VALUE || evidence.collation()
                .state() == MetadataValue.State.VALUE) {
            throw unsupported("StarRocks nullable add column does not support character set or collation");
        }
        if (MYSQL_CLAUSE.matcher(declared).find()) {
            throw unsupported("StarRocks nullable add column does not support a MySQL clause");
        }
        if (!mapped(meta.getType())) {
            throw unsupported("StarRocks nullable add column requires a mapped field type");
        }
    }

    /** OLAP 字段定义的显式分支。默认分支会默默输出 STRING。 */
    private static boolean mapped(int type) {
        return type == ColumnMetaData.TYPE_TIMESTAMP || type == ColumnMetaData.TYPE_TIME
                || type == ColumnMetaData.TYPE_DATE || type == ColumnMetaData.TYPE_BOOLEAN
                || type == ColumnMetaData.TYPE_NUMBER || type == ColumnMetaData.TYPE_INTEGER
                || type == ColumnMetaData.TYPE_BIGNUMBER || type == ColumnMetaData.TYPE_STRING;
    }

    /** 仅包含字段类型和原生类型文本。JDBC 标志和证据状态单独检查。 */
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
