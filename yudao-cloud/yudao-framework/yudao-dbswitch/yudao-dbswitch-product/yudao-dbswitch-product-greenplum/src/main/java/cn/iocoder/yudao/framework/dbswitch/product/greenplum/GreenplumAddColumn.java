package cn.iocoder.yudao.framework.dbswitch.product.greenplum;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 为一个可空且无默认值的堆表列生成 PostgreSQL 风格的 {@code ALTER TABLE ... ADD COLUMN}。
 * 拒绝分布键、追加优化存储、列存储、NOT NULL、主键、默认值和
 * 生成列；不读取目录，也不宣称已经在服务器执行。
 */
public final class GreenplumAddColumn {

    private static final String SQL_STATE = "0A000";

    /**
     * 初始化 {@code GreenplumAddColumn}。
     */
    private GreenplumAddColumn() {
    }

    /** 方言入口：仅生成堆表语句，新增列不声明为分布键。 */
    public static String sql(DatabaseGreenplumImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        return sql(database, schema, table, column, "heap", List.of());
    }

    /**
     * @param storage {@code heap}，或必须拒绝的追加优化或列式访问方法
     * @param distributionKeys 已属于或请求加入分布键的物理名称；若匹配新增列，
     *                         则予以拒绝；空集合不能证明已观测到该表。
     */
    public static String sql(DatabaseGreenplumImpl database, String schema, String table, ColumnDescription column,
            String storage, Collection<String> distributionKeys) throws SQLFeatureNotSupportedException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        if (!"heap".equals(storage)) {
            throw unsupported(
                    "Greenplum ADD COLUMN supports only heap tables, not append-optimized or column-oriented storage");
        }
        if (column.getMetaData().getType() == cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_NONE) {
            throw unsupported("Nullable ADD COLUMN requires a recognized field type");
        }
        String field = column.getFieldName();
        if (distributionKeys == null) {
            throw unsupported("Greenplum ADD COLUMN does not support an unknown distribution key");
        }
        for (String key : distributionKeys) {
            if (key == null || key.equals(field)) {
                throw unsupported("Greenplum ADD COLUMN does not support a distribution key");
            }
        }
        if (!column.isNullable() || explicitlyNotNull(column.getMetadataEvidence())) {
            throw unsupported("Greenplum ADD COLUMN does not support NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("Greenplum ADD COLUMN does not support a primary key");
        }
        if (hasDefault(column)) {
            throw unsupported("Greenplum ADD COLUMN does not support a default");
        }
        if (generated(column)) {
            throw unsupported("Greenplum ADD COLUMN does not support a generated column");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, field);
        String type = columnType(database, column);
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN " + quotedColumn + " " + type;
    }

    /** 与 {@link #sql} 生成相同语句；被拒绝的特性仍通过 {@link SQLFeatureNotSupportedException} 报告。 */
    public static String dialectSql(DatabaseGreenplumImpl database, String schema, String table,
            ColumnDescription column) {
        try {
            return sql(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            throw sneakyThrow(unsupported);
        }
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }

    /**
     * 判断列定义是否明确声明非空。
     */
    private static boolean explicitlyNotNull(ColumnMetadataEvidence evidence) {
        MetadataValue<Boolean> nullable = evidence.nullable();
        return nullable.state() == MetadataValue.State.UNSUPPORTED
                || (nullable.state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(nullable.value()));
    }

    /**
     * 判断是否具有默认。
     */
    private static boolean hasDefault(ColumnDescription column) {
        if (column.getDefaultValue() != null) {
            return true;
        }
        MetadataValue<String> expression = column.getMetadataEvidence().defaultExpression();
        return expression.state() == MetadataValue.State.VALUE || expression.state() == MetadataValue.State.UNSUPPORTED;
    }

    /**
     * 返回当前生成列。
     */
    private static boolean generated(ColumnDescription column) {
        if (column.isAutoIncrement()) {
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

    /**
     * 根据列定义生成当前产品的类型声明。
     */
    private static String columnType(DatabaseGreenplumImpl database, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        String definition = database.getFieldDefinition(column.getMetaData(), List.of(), false, false, false);
        String prefix = " \"" + column.getFieldName() + "\"";
        if (definition == null || !definition.startsWith(prefix)) {
            throw unsupported("Greenplum ADD COLUMN could not take a type from getFieldDefinition");
        }
        String type = definition.substring(prefix.length()).trim();
        if (!type.matches("[A-Za-z0-9_(), ]+")
                || type.matches(
                "(?is).*\\b(not\\s+null|default|primary\\s+key|generated|distributed|bigserial|smallserial|serial)\\b.*")) {
            throw unsupported("Greenplum ADD COLUMN type must not include a constraint or default");
        }
        return type;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, SQL_STATE);
    }
}
