package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 为 SQLite 添加可空且无默认值的列。
 * 名称经 {@link RestrictedSelectGuard#quotePhysicalIdentifier} 按 SQLITE3 规则处理。
 * 类型文本取自 {@link DatabaseSqliteImpl#getFieldDefinition} 尾部，此类不自行构造类型。
 * sqlite-jdbc 3.31.1 接受 {@code ALTER TABLE "schema"."table" ADD COLUMN "column" type}，其中类型使用
 * 此方法生成的普通标记；服务器拒绝主键、无非空默认值的 NOT NULL、UNIQUE、非常量默认值和
 * STORED 生成列，因此此处提前拒绝，而不生成这些语句。
 * 常量默认值、具有常量默认值的 NOT NULL、CHECK、外键和 VIRTUAL 生成列也不在
 * 此能力范围内，同样予以拒绝；不打开连接。
 */
public final class Sqlite3AddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.SQLITE3;

    /** {@code getFieldDefinition} 实际追加的非键标记。 */
    private static final Set<String> PLAIN_TYPES = Set.of("TEXT", "INTEGER", "NUMERIC", "BLOB", "DATETIME", "CHAR(1)");

    private static final Pattern WORD = Pattern.compile(
            "(^|[^a-z0-9_])(unique|default|check|references|collate|stored|virtual|autoincrement|identity)([^a-z0-9_]|$)");

    /**
     * 初始化 {@code Sqlite3AddColumn}。
     */
    private Sqlite3AddColumn() {
    }

    /**
     * 根据当前SQLite方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseSqliteImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            Sqlite3AddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseSqliteImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || column == null) {
            throw new IllegalArgumentException("SQLite add column requires the SQLite dialect and a column");
        }
        if (database.getDatabaseType() != PRODUCT) {
            throw unsupported("SQLite nullable add column does not apply to this product");
        }
        if (column.getDbType() != null && column.getDbType() != PRODUCT) {
            throw unsupported("SQLite add column does not accept another product column");
        }
        reject(column);
        int valueType = column.getMetaData().getType();
        if (!recognized(valueType)) {
            throw unsupported("SQLite nullable add column requires a recognized field type");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String type = typeTail(definition, column.getFieldName());
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        String declared = column.getFieldTypeName() == null ? "" : column.getFieldTypeName().toLowerCase(Locale.ROOT);
        MetadataValue<Boolean> nullable = column.getMetadataEvidence().nullable();
        if (!column.isNullable()
                || (nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value()))
                || declared.contains("not null")) {
            throw unsupported("SQLite nullable add column does not support NOT NULL");
        }
        if (column.isPk() || declared.contains("primary key")) {
            throw unsupported("SQLite nullable add column does not support primary keys");
        }
        if (word(declared, "unique") || uniqueIndex(column)) {
            throw unsupported("SQLite nullable add column does not support unique constraints");
        }
        MetadataValue<String> defaults = column.getMetadataEvidence().defaultExpression();
        if (column.getDefaultValue() != null || blocked(defaults) || word(declared, "default")) {
            throw unsupported("SQLite nullable add column does not support defaults");
        }
        if (column.isAutoIncrement() || blocked(column.getMetadataEvidence().identity())
                || blocked(column.getMetadataEvidence().sequence())
                || word(declared, "autoincrement") || word(declared, "identity")) {
            throw unsupported("SQLite nullable add column does not support identity");
        }
        if (blocked(column.getMetadataEvidence().generatedExpression())
                || declared.contains("generated") || word(declared, "stored") || word(declared, "virtual")) {
            throw unsupported("SQLite nullable add column does not support generated columns");
        }
        if (word(declared, "check")) {
            throw unsupported("SQLite nullable add column does not support check constraints");
        }
        if (word(declared, "references")) {
            throw unsupported("SQLite nullable add column does not support foreign keys");
        }
        if (word(declared, "collate")) {
            throw unsupported("SQLite nullable add column does not support collations");
        }
    }

    /** JDBC NON_UNIQUE 为 false 且有索引名才能表示唯一索引，仅默认 false 标志不能证明唯一。 */
    private static boolean uniqueIndex(ColumnDescription column) {
        String indexName = column.getIndexName();
        return indexName != null && !indexName.isBlank() && !column.isNonIndexUnique();
    }

    /**
     * 返回当前已识别。
     */
    private static boolean recognized(int valueType) {
        return valueType == ColumnMetaData.TYPE_STRING
                || valueType == ColumnMetaData.TYPE_INTEGER
                || valueType == ColumnMetaData.TYPE_NUMBER
                || valueType == ColumnMetaData.TYPE_BIGNUMBER
                || valueType == ColumnMetaData.TYPE_BOOLEAN
                || valueType == ColumnMetaData.TYPE_DATE
                || valueType == ColumnMetaData.TYPE_TIME
                || valueType == ColumnMetaData.TYPE_TIMESTAMP
                || valueType == ColumnMetaData.TYPE_BINARY;
    }

    /** {@code getFieldDefinition} 以一个空格、原始带引号名称和三个空格开头。 */
    private static String typeTail(String definition, String fieldName) throws SQLFeatureNotSupportedException {
        String prefix = " \"" + fieldName + "\"   ";
        if (definition == null || fieldName == null || !definition.startsWith(prefix)) {
            throw unsupported("SQLite field definition did not use the existing quoted column layout");
        }
        String type = definition.substring(prefix.length());
        rejectTail(type);
        return type;
    }

    /**
     * 拒绝增列类型声明中未支持的约束或后缀。
     */
    private static void rejectTail(String type) throws SQLFeatureNotSupportedException {
        String folded = type.toLowerCase(Locale.ROOT);
        if (folded.contains("not null")) {
            throw unsupported("SQLite nullable add column does not support NOT NULL");
        }
        if (folded.contains("primary") || folded.contains("autoincrement")) {
            throw unsupported("SQLite nullable add column does not support primary keys");
        }
        if (word(folded, "unique")) {
            throw unsupported("SQLite nullable add column does not support unique constraints");
        }
        if (word(folded, "default")) {
            throw unsupported("SQLite nullable add column does not support defaults");
        }
        if (folded.contains("generated") || word(folded, "stored") || word(folded, "virtual")) {
            throw unsupported("SQLite nullable add column does not support generated columns");
        }
        if (word(folded, "check")) {
            throw unsupported("SQLite nullable add column does not support check constraints");
        }
        if (word(folded, "references")) {
            throw unsupported("SQLite nullable add column does not support foreign keys");
        }
        if (word(folded, "collate")) {
            throw unsupported("SQLite nullable add column does not support collations");
        }
        if (!PLAIN_TYPES.contains(type)) {
            throw unsupported("SQLite nullable add column does not support this field definition");
        }
    }

    /**
     * 记录当前目标受阻或不可继续处理的状态。
     */
    private static boolean blocked(MetadataValue<?> value) {
        return value.state() == MetadataValue.State.VALUE || value.state() == MetadataValue.State.UNSUPPORTED;
    }

    /**
     * 按当前解析位置读取或匹配一个词元。
     */
    private static boolean word(String folded, String word) {
        var matcher = WORD.matcher(folded);
        while (matcher.find()) {
            if (word.equals(matcher.group(2))) {
                return true;
            }
        }
        return false;
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
