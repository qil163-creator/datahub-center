package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.MariaDbRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.mariadb.MariaDbAddColumn;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 支持MariaDB数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseMariaDBImpl extends DatabaseMysqlImpl {

    /**
     * 初始化 {@code DatabaseMariaDBImpl}及其父类状态。
     */
    public DatabaseMariaDBImpl() {
        super(ProductTypeEnum.MARIADB.getDriveClassName());
    }

    /** 原生 MariaDB 验证的 USE 权限检查，不因继承 MySQL 自动启用。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            super.probeNamespace(connection, namespace, timeoutSeconds);
            return;
        }
        mysqlNamespace(connection, namespace, timeoutSeconds);
    }

    /** 读取当前账号授权并验证目标数据库进入权限，不声称普通表写权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            super.probeAccountPrivileges(connection, namespace, timeoutSeconds);
            return;
        }
        mysqlAccountPrivileges(connection, namespace, timeoutSeconds);
    }

    /** MariaDB 独立原生验证的 information_schema 表列注释目录。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.probeExtendedProperties(connection, namespace, table, timeoutSeconds);
        }
        return mysqlExtendedProperties(connection, namespace, table, timeoutSeconds);
    }

    /** 读取 MariaDB binlog 条件；BINLOG MONITOR 是新版本对 REPLICATION CLIENT 的权限拆分。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.probeCdcReadiness(connection, timeoutSeconds);
        }
        boolean logs = mysqlBinlogConfigurationReady(connection, timeoutSeconds);
        var privileges = mysqlObservedGlobalPrivileges(connection, timeoutSeconds);
        boolean replication = privileges.contains("ALL PRIVILEGES") || privileges.contains("REPLICATION SLAVE")
                && (privileges.contains("REPLICATION CLIENT") || privileges.contains("BINLOG MONITOR"));
        if (!replication) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT CURRENT_ROLE()")) {
                    if (!rows.next()) {
                        throw new SQLException("MariaDB 活动角色证据缺失");
                    }
                    if (rows.getString(1) != null) {
                        throw new java.sql.SQLFeatureNotSupportedException(
                                "MariaDB 活动角色的递归复制授权尚未验证，不能据直接授权判定未授权", "0A000");
                    }
                }
            }
        }
        return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(logs, replication);
    }

    /** MariaDB 真实引擎验证的会话临时 InnoDB 表；清理不能删除普通表。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.temporaryTableDiagnosticPlan(randomName);
        }
        return mysqlTemporaryDiagnosticPlan(randomName);
    }

    /** 当前库内账号可见表的数据与索引目录统计；InnoDB 数值是近似占用，不包含日志。 */
    @Override
    public long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.queryDatabaseUsedBytes(connection, timeoutSeconds);
        }
        return queryVisibleTableStorageBytes(connection, timeoutSeconds);
    }

    /**
     * 可选的部署固定源代际；绝不静默忽略为另一产品设置的固定信息。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference,
            SourceGenerationPin pin) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MARIADB || pin == null) {
            return super.queryRelationFingerprint(connection, reference, pin);
        }
        return MariaDbRelationIdentity.fingerprint(connection, reference, pin);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String tableAlias, String column) {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.integerKeyProjection(tableAlias, column);
        }
        return mysqlSignedIntegerProjection(tableAlias, column);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String tableAlias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.keyComponentProjection(tableAlias, column, maxBytes);
        }
        // MariaDB 将表达式元数据中的 ENUM 映射为 VARCHAR，不能借用 MySQL 的文本凭据。
        return mysqlSignedIntegerComponent(tableAlias, column, maxBytes);
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.textColumnExpression(column);
        }
        return "CAST(" + cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .quotePhysicalIdentifier(ProductTypeEnum.MARIADB, column) + " AS CHAR)";
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.MARIADB;
    }

    /** 仅允许物理名称；列可空、非键列、无默认值、无标识生成且不是生成列。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return MariaDbAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

}
