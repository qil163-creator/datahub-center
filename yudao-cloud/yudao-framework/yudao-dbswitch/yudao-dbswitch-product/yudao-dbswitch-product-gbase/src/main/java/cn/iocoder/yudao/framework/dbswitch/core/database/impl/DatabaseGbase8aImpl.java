package cn.iocoder.yudao.framework.dbswitch.core.database.impl;


import org.apache.commons.lang3.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.product.gbase8a.Gbase8aAddColumn;

import java.util.List;
import java.util.Map;

/**
 * 支持GBase8a数据库的元信息实现
 *
 * @author tang
 */
public class DatabaseGbase8aImpl extends DatabaseMysqlImpl {

    /** GBase8a 显式采用已有 information_schema 注释目录，不继承 MySQL CDC 或写计划。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            java.sql.Connection connection, String namespace, String table, int timeoutSeconds) throws java.sql.SQLException {
        if (getDatabaseType()!=ProductTypeEnum.GBASE8A) return super.probeExtendedProperties(connection,namespace,table,timeoutSeconds);
        return mysqlExtendedProperties(connection,namespace,table,timeoutSeconds);
    }


    /** GBase8a 显式声明数据库进入检查，不自动继承 MySQL 的 CDC 或写入计划。 */
    @Override
    public void probeNamespace(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        mysqlNamespace(connection, namespace, timeoutSeconds);
    }

    /** GBase8a 账号授权目录仅作观测，命名空间使用权仍由服务器 USE 检查。 */
    @Override
    public void probeAccountPrivileges(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        mysqlAccountPrivileges(connection, namespace, timeoutSeconds);
    }

    /** GBase8a 原生 information_schema 基本表目录；与 MySQL 分开声明、分开验证。 */
    @Override
    public java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) throws java.sql.SQLException {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("GBase8a 诊断参数必须完整且有界");
        var result = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription>();
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new java.sql.SQLException("GBase8a 目录超过上限，拒绝截断", "54000");
                    var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return java.util.List.copyOf(result);
    }

    /**
     * 初始化 {@code DatabaseGbase8aImpl}及其父类状态。
     */
    public DatabaseGbase8aImpl() {
        super(ProductTypeEnum.GBASE8A.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.GBASE8A;
    }

    /**
     * 只生成可空、无默认值的加列。NOT NULL、主键、默认值、identity、生成列，以及父类未在 GBase 证明的类型子句，直接拒绝。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return Gbase8aAddColumn.statement(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 建表语句后置内容：固定追加 InnoDB 引擎与 utf8mb4 字符集，有表注释时内联 COMMENT。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        builder.append("ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        if (StringUtils.isNotBlank(tblComment)) {
            builder.append(String.format(" COMMENT='%s' ", tblComment.replace("'", "\\'")));
        }
    }
}
