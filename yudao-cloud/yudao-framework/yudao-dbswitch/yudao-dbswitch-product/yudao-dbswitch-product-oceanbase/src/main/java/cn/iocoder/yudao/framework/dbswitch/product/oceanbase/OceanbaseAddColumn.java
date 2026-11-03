package cn.iocoder.yudao.framework.dbswitch.product.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 实际观测兼容模式后，生成允许空值且无默认值的 OceanBase ADD COLUMN。
 * schema、表和列由 {@link RestrictedSelectGuard} 按 OCEANBASE 规则加反引号。
 * 类型标记来自关闭备注的现有 MySQL 模式 {@code getFieldDefinition}。
 * Oracle 模式复用 Oracle 允许空值的 ADD 渲染器及类型定义。此类不执行 SQL。
 */
public final class OceanbaseAddColumn {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.OCEANBASE;

    private static final Pattern FORBIDDEN_CLAUSE = Pattern.compile(
            "(?i)(^|\\s)(NOT\\s+NULL|DEFAULT|AUTO_INCREMENT|GENERATED|PRIMARY\\s+KEY|COMMENT)(\\s|$)");

    /**
     * 初始化 {@code OceanbaseAddColumn}。
     */
    private OceanbaseAddColumn() {
    }

    /**
     * 根据当前OceanBase方言生成 SQL 语句或片段。
     */
    public static String sql(DatabaseOceanbaseImpl database, String schema, String table, ColumnDescription column) {
        try {
            return checked(database, schema, table, column);
        } catch (SQLFeatureNotSupportedException unsupported) {
            OceanbaseAddColumn.<RuntimeException>sneaky(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 检查数据库、模式、表是否满足当前操作的约定。
     */
    private static String checked(DatabaseOceanbaseImpl database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (database == null || schema == null || table == null || column == null) {
            throw new IllegalArgumentException("OceanBase add column target is required");
        }
        if (database.getDatabaseType() != PRODUCT) {
            throw unsupported("OceanBase nullable add column does not apply to this product");
        }
        Boolean mode = database.observedMysqlMode();
        if (mode == null) {
            throw new IllegalStateException(
                    "OceanBase DDL requires compatibility mode verified from the target connection");
        }
        if (column.getDbType() != null && column.getDbType() != PRODUCT) {
            throw unsupported("OceanBase add column does not accept another product column");
        }
        reject(column);
        if (!mode) {
            return cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleAddColumn.sql(schema, table, column,
                    meta -> database.getFieldDefinition(meta, null, false, false, false));
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, column.getFieldName());
        ColumnMetaData meta = column.getMetaData();
        if (meta.getType() == ColumnMetaData.TYPE_NONE) {
            throw unsupported("OceanBase nullable ADD COLUMN requires a recognized field type");
        }
        if (absentDefaultWord(column.getDefaultValue())) {
            // MySQL 模式时间 DDL 为 JDBC 的单词 null 生成 DEFAULT NULL；该单词不表示默认值。
            meta.setDefaultValue(null);
        }
        // 备注保持关闭。此允许空值且无默认值的添加操作不包含 COMMENT。
        String definition = database.getFieldDefinition(meta, null, false, false, false);
        String lead = " " + quotedColumn;
        if (definition == null || !definition.startsWith(lead)) {
            throw unsupported("OceanBase field definition did not use the quoted physical column");
        }
        String tail = definition.substring(lead.length());
        if (tail.isBlank() || tail.indexOf(';') >= 0 || FORBIDDEN_CLAUSE.matcher(tail).find()) {
            throw unsupported("OceanBase add column type still contains a rejected clause");
        }
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD COLUMN" + lead + tail;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(ColumnDescription column) throws SQLFeatureNotSupportedException {
        ColumnMetadataEvidence evidence = column.getMetadataEvidence();
        String declared = declaredClause(column);
        if (!column.isNullable()
                || evidence.nullable().state() == MetadataValue.State.UNSUPPORTED
                || (evidence.nullable().state() == MetadataValue.State.VALUE && !Boolean.TRUE.equals(
                evidence.nullable().value()))
                || declared.contains("NOT NULL")) {
            throw unsupported("OceanBase nullable add column does not accept NOT NULL");
        }
        if (column.isPk() || declared.contains("PRIMARY")) {
            throw unsupported("OceanBase nullable add column does not accept a primary key");
        }
        if (hasDefault(column) || evidence.defaultExpression().state() == MetadataValue.State.UNSUPPORTED
                || declared.contains("DEFAULT")) {
            throw unsupported("OceanBase nullable add column does not accept a default");
        }
        if (column.isAutoIncrement()
                || evidence.identity().state() == MetadataValue.State.VALUE
                || evidence.identity().state() == MetadataValue.State.UNSUPPORTED
                || evidence.sequence().state() == MetadataValue.State.VALUE
                || evidence.sequence().state() == MetadataValue.State.UNSUPPORTED
                || declared.contains("AUTO_INCREMENT") || declared.contains("IDENTITY")) {
            throw unsupported("OceanBase nullable add column does not accept identity");
        }
        if (evidence.generatedExpression().state() == MetadataValue.State.VALUE
                || evidence.generatedExpression().state() == MetadataValue.State.UNSUPPORTED
                || declared.contains("GENERATED") || declared.contains(" AS (")) {
            throw unsupported("OceanBase nullable add column does not accept a generated column");
        }
    }

    /** 与非时间 MySQL 模式 {@code getFieldDefinition} 使用相同的缺失默认值单词。 */
    private static boolean hasDefault(ColumnDescription column) {
        if (column.getMetadataEvidence().defaultExpression().state() == MetadataValue.State.VALUE) {
            return true;
        }
        String value = column.getDefaultValue();
        return value != null && !absentDefaultWord(value);
    }

    /**
     * 判断当前默认值文本是否显式表示 SQL NULL。
     */
    private static boolean absentDefaultWord(String value) {
        return "null".equals(value) || "NULL".equals(value);
    }

    /**
     * 合并列类型声明与原生证据，供后续约束检查。
     */
    private static String declaredClause(ColumnDescription column) {
        return (text(column.getFieldTypeName()) + "\n" + text(column.getMetadataEvidence().nativeType()))
                .toUpperCase(Locale.ROOT);
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
