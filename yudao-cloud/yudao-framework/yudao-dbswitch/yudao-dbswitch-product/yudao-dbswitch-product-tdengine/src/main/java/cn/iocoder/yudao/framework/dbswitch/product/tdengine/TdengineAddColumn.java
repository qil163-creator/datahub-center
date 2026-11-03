package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 为单个普通 TDengine 3.3.8.8 表生成可空 ADD COLUMN。类型文本取自
 * {@link DatabaseTdengineImpl#getFieldDefinition} 尾部。此类不输出 ALTER STABLE、ADD TAG、
 * 时间戳主键、NOT NULL、DEFAULT 或 COMPOSITE KEY。3.3.8.8 上相同 ALTER TABLE
 * 文本也会修改超级表，因此拒绝已知的 SUPER_TABLE、STABLE 或 CHILD_TABLE 类型。
 * 不查询关系类型，也不打开连接。
 */
public final class TdengineAddColumn {

    private static final String SQL_STATE = "0A000";

    /** 关闭主键开关后，{@code getFieldDefinition} 已为普通列输出的类型。 */
    private static final Pattern TYPE = Pattern.compile(
            "TIMESTAMP|BOOL|INT|BIGINT|DOUBLE|DECIMAL\\([1-9]\\d?,[0-9]{1,2}\\)|VARBINARY\\([1-9]\\d*\\)|NCHAR\\([1-9]\\d*\\)");

    /**
     * 初始化 {@code TdengineAddColumn}。
     */
    private TdengineAddColumn() {
    }

    /** 普通表语句。不从目录读取类型。 */
    public static String statement(DatabaseTdengineImpl database, String schema, String table,
            ColumnDescription column) {
        try {
            return sql(database, schema, table, column, "NORMAL_TABLE");
        } catch (SQLFeatureNotSupportedException unsupported) {
            throw sneaky(unsupported);
        }
    }

    /**
     * 根据当前TDengine方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseTdengineImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        return sql(database, schema, table, column, "NORMAL_TABLE");
    }

    /**
     * @param relationKind {@code NORMAL_TABLE}，或必须拒绝的超级表、子表类型。
     *                     传入 {@code NORMAL_TABLE} 不代表已观察到该表。
     */
    public static String sql(DatabaseTdengineImpl database, String schema, String table, ColumnDescription column,
            String relationKind) throws SQLFeatureNotSupportedException {
        if (database == null || column == null) {
            throw new IllegalArgumentException("TDengine add column requires the dialect and a column");
        }
        requireOrdinaryTable(relationKind);
        refuseTag(column);
        if (notNull(column)) {
            throw unsupported("NOT NULL");
        }
        ColumnMetaData meta = column.getMetaData();
        boolean composite = "COMPOSITE KEY".equals(note(column));
        if (meta.getType() == ColumnMetaData.TYPE_TIMESTAMP && (column.isPk() || composite)) {
            throw unsupported("a TIMESTAMP primary key");
        }
        if (column.isPk() || composite) {
            throw unsupported("a primary key");
        }
        if (hasDefault(column)) {
            throw unsupported("a default");
        }
        if (generated(column)) {
            throw unsupported("a generated column");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, meta.getName());
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String type = typeOnly(meta.getName(), definition);
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /**
     * 校验并取得普通表。
     */
    private static void requireOrdinaryTable(String relationKind) throws SQLFeatureNotSupportedException {
        if ("NORMAL_TABLE".equals(relationKind)) {
            return;
        }
        if ("SUPER_TABLE".equals(relationKind) || "STABLE".equals(relationKind)) {
            throw unsupported("a super table");
        }
        if ("CHILD_TABLE".equals(relationKind)) {
            throw unsupported("a child table");
        }
        throw unsupported("an unverified table kind");
    }

    /**
     * 拒绝当前写入路径不支持的标签定义或取值。
     */
    private static void refuseTag(ColumnDescription column) throws SQLFeatureNotSupportedException {
        if ("TAG".equals(note(column))) {
            throw unsupported("a TAG");
        }
        String typeName = column.getFieldTypeName();
        if (typeName == null) {
            return;
        }
        String normalized = typeName.trim().toLowerCase(Locale.ROOT);
        if ("tag".equals(normalized) || normalized.equals("json") || normalized.startsWith("json")) {
            throw unsupported("a TAG");
        }
    }

    /**
     * 返回当前注释。
     */
    private static String note(ColumnDescription column) {
        String remarks = column.getRemarks();
        return remarks == null ? "" : remarks.trim();
    }

    /**
     * 检查当前列是否具有明确的非空约束。
     */
    private static boolean notNull(ColumnDescription column) {
        if (!column.isNullable()) {
            return true;
        }
        MetadataValue<Boolean> nullable = column.getMetadataEvidence().nullable();
        if (nullable.state() == MetadataValue.State.UNSUPPORTED) {
            return true;
        }
        return nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value());
    }

    /**
     * 判断是否具有默认。
     */
    private static boolean hasDefault(ColumnDescription column) {
        MetadataValue<String> declared = column.getMetadataEvidence().defaultExpression();
        if (declared.state() == MetadataValue.State.VALUE || declared.state() == MetadataValue.State.UNSUPPORTED) {
            return true;
        }
        String value = column.getDefaultValue();
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed);
    }

    /**
     * 返回当前生成列。
     */
    private static boolean generated(ColumnDescription column) {
        if (column.isAutoIncrement()) {
            return true;
        }
        String typeName = column.getFieldTypeName();
        if (typeName != null && typeName.toLowerCase(Locale.ROOT).contains("generated")) {
            return true;
        }
        ColumnMetadataEvidence evidence = column.getMetadataEvidence();
        return present(evidence.generatedExpression()) || present(evidence.identity()) || present(evidence.sequence());
    }

    /**
     * 返回当前存在性。
     */
    private static boolean present(MetadataValue<?> value) {
        return value.state() == MetadataValue.State.VALUE || value.state() == MetadataValue.State.UNSUPPORTED;
    }

    /** {@code getFieldDefinition} 以带引号名称开头。只能复用其普通类型尾部。 */
    private static String typeOnly(String name, String definition) throws SQLFeatureNotSupportedException {
        String prefix = " " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, name) + " ";
        if (definition == null || !definition.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "TDengine field definition is not the existing getFieldDefinition layout");
        }
        String type = definition.substring(prefix.length());
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains(" COMPOSITE KEY") || upper.contains("PRIMARY KEY")) {
            throw unsupported("a primary key");
        }
        if (upper.contains(" NOT NULL")) {
            throw unsupported("NOT NULL");
        }
        if (upper.contains(" DEFAULT")) {
            throw unsupported("a default");
        }
        if (upper.contains(" TAG")) {
            throw unsupported("a TAG");
        }
        if (upper.contains(" GENERATED") || upper.contains(" AUTO_INCREMENT")) {
            throw unsupported("a generated column");
        }
        if (!TYPE.matcher(type).matches() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf(
                '\r') >= 0) {
            throw unsupported("a type outside the existing ordinary-table DDL");
        }
        return type;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException("TDengine nullable add column does not support " + feature,
                SQL_STATE);
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
