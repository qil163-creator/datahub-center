package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.PageSqlModel;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcUrlUtils;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisAddColumn;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Doris元信息实现。
 *
 * @author jrl
 */
public class DatabaseDorisImpl extends DatabaseMysqlCompatibleOlapImpl implements IDatabaseInterface {

    /** 服务端参数绑定避免 NO_BACKSLASH_ESCAPES 下客户端把字符串转换为 Doris 不支持的 VARBINARY 比较。 */
    @Override
    public String preparePageQueryJdbcUrl(String jdbcUrl) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) return super.preparePageQueryJdbcUrl(jdbcUrl);
        return JdbcUrlUtils.overrideMysqlQueryParams(jdbcUrl, java.util.Map.of(
                "useServerPrepStmts", true, "emulateUnsupportedPstmts", false));
    }

    /** Doris 4.0 服务端预编译不接受 LIMIT 参数，业务参数仍由 PreparedStatement 原样绑定。 */
    @Override
    public PageSqlModel getPageModel(String sql, Integer page, Integer limit) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) return super.getPageModel(sql, page, limit);
        if (page == null || page < 1 || limit == null || limit < 1) {
            throw new IllegalArgumentException("分页页码和大小必须为正整数");
        }
        long offset = Math.multiplyExact(page.longValue() - 1L, limit.longValue());
        var prepared = SqlStatementClassifier.prepareReadOnlyQueryPlan(ProductTypeEnum.DORIS, sql);
        if (prepared.parameterizedPagination()) {
            throw new IllegalArgumentException("Doris 暂不支持 SQL 内 LIMIT/OFFSET 参数，请使用固定整数分页");
        }
        String statement = prepared.sql();
        String pageSource = prepared.topLevelPagination()
                ? "SELECT * FROM (\n" + statement + "\n) datahub_page" : statement;
        var model = new PageSqlModel();
        model.setPageSql(pageSource + "\nLIMIT " + limit + " OFFSET " + offset);
        model.setPageCountSql("SELECT COUNT(1) FROM (" + statement + "\n) t");
        model.setBindPageParameters(false);
        return model;
    }

    /** 使用原生 FE 配置读取 Doris binlog 前置条件，不继承 MySQL binlog。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.product.doris.DorisConnectionDiagnostics
                .cdcReadiness(connection, timeoutSeconds);
    }

    /** 同一会话的 UNIQUE/MoW 临时表；普通表和跨连接写入器不参与诊断。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.product.doris.DorisConnectionDiagnostics.temporaryTable(randomName);
    }

    /** 执行前必须通过实际 FE 版本核验，不能使用 MySQL 协议版本。 */
    @Override
    public void validateTemporaryTableDiagnosticConnection(Connection connection, int timeoutSeconds) throws SQLException {
        cn.iocoder.yudao.framework.dbswitch.product.doris.DorisConnectionDiagnostics.verifyVersion(connection, timeoutSeconds);
    }

    /**
     * 建表语句后置内容：按 Doris Key 模型、分桶和副本配置生成。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder sql, String comment, java.util.List<String> keys,
            java.util.Map<String, String> properties, String hashField) {
        if (properties != null && properties.containsKey(
                cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY)) {
            sql.append(" ENGINE=OLAP ");
        }
        super.postAppendCreateTableSql(sql, comment, keys, properties, hashField);
    }

    /**
     * 应用创建表配置选项。
     */
    @Override
    public String applyCreateTableOptions(String generatedSql, String schema, String table,
            java.util.Map<String, String> properties) {
        String encoded = properties == null ? null : properties.get(
                cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY);
        if (encoded == null) {
            return generatedSql;
        }
        var options = cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.fromJson(encoded);
        var definition = cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.parse(generatedSql);
        return cn.iocoder.yudao.framework.dbswitch.product.doris.DorisTableLayouts.apply(definition, null, options)
                .ddl(schema, table);
    }

    /**
     * 初始化 {@code DatabaseDorisImpl}及其父类状态。
     */
    public DatabaseDorisImpl() {
        super(ProductTypeEnum.DORIS.getDriveClassName());
    }

    /**
     * 初始化 {@code DatabaseDorisImpl}及其父类状态。
     */
    public DatabaseDorisImpl(String driverClassName) {
        super(driverClassName);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.DORIS) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return DorisRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) {
            return super.textColumnExpression(column);
        }
        return olapTextColumnExpression(column);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String tableAlias, String column) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) {
            return super.integerKeyProjection(tableAlias, column);
        }
        return olapIntegerComponent(ProductTypeEnum.DORIS, tableAlias, column, 21, false);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String tableAlias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) {
            return super.keyComponentProjection(tableAlias, column, maxBytes);
        }
        if (maxBytes <= 0 || maxBytes > 256) {
            throw new IllegalArgumentException("Invalid key byte bound");
        }
        return olapIntegerComponent(ProductTypeEnum.DORIS, tableAlias, column, Math.min(maxBytes, 21), false);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.DORIS;
    }

    /**
     * 只生成可空、无默认值的加列。KEY 模型、聚合列、NOT NULL、主键、默认值、identity 和生成列直接拒绝。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return DorisAddColumn.statement(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> queryTableColumnMeta(
            Connection c, String schema, String table) {
        try {
            return cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport.inspect(c, schema, table, 30)
                    .table().columns().stream()
                    .map(cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.Column::description)
                    .toList();
        } catch (Exception failure) {
            throw new IllegalStateException("Doris native metadata could not be confirmed");
        }
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> queryTableColumnMetaOnly(
            Connection c, String schema, String table) {
        return queryTableColumnMeta(c, schema, table);
    }

    /** JDBC getColumns 将物理名称当作模式，在 Doris 4.0 上会丢失带引号的单引号。 */
    @Override
    public java.util.List<String> queryTableColumnName(Connection connection, String schema, String table) {
        if (getDatabaseType() != ProductTypeEnum.DORIS) {
            return super.queryTableColumnName(connection, schema, table);
        }
        var columns = new java.util.ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        // STRCMP 避免 Doris 将 TABLE_NAME 等值判断下推为模式匹配。分别验证两个物理名称。
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME FROM internal.information_schema.columns "
                        + "WHERE STRCMP(TABLE_SCHEMA,?)=0 AND STRCMP(TABLE_NAME,?)=0 ORDER BY ORDINAL_POSITION")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(1025);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(3);
                    if (!schema.equals(rows.getString(1)) || !table.equals(rows.getString(2))
                            || name == null || !seen.add(name) || columns.size() >= 1024) {
                        throw new SQLException("Doris physical column observation is ambiguous or exceeds its bound");
                    }
                    columns.add(name);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Doris physical columns could not be confirmed");
        }
        return columns;
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public java.util.List<String> queryTablePrimaryKeys(Connection c, String schema, String table) {
        try {
            var definition = cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport.inspect(c, schema,
                    table, 30).table();
            return definition.unique() ? definition.keys() : java.util.List.of();
        } catch (Exception failure) {
            throw new IllegalStateException("Doris native keys could not be confirmed");
        }
    }
}
