package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.TidbRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.tidb.TidbAddColumn;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * TiDB原生关系和显式验证能力的元信息实现
 *
 * @author jrl
 */
@Slf4j
public class DatabaseTidbImpl extends DatabaseMysqlImpl implements IDatabaseInterface {

    /** TiDB 独立声明 USE 数据库实际权限检查；不启用 MySQL 的 binlog 或临时写入能力。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) { super.probeNamespace(connection, namespace, timeoutSeconds); return; }
        mysqlNamespace(connection, namespace, timeoutSeconds);
    }

    /** TiDB SHOW GRANTS 只读目录观测，不把角色目录可见性等同于普通表写权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) { super.probeAccountPrivileges(connection, namespace, timeoutSeconds); return; }
        mysqlAccountPrivileges(connection, namespace, timeoutSeconds);
    }

    /** TiDB 显式启用其 information_schema 表列注释字段，严格绑定精确物理名称。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) return super.probeExtendedProperties(connection, namespace, table, timeoutSeconds);
        return mysqlExtendedProperties(connection, namespace, table, timeoutSeconds);
    }

    /**
     * 初始化 {@code DatabaseTidbImpl}及其父类状态。
     */
    public DatabaseTidbImpl() {
        super(ProductTypeEnum.TIDB.getDriveClassName());
    }

    /**
     * 解析系统目录关系。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity resolveCatalogRelation(
            Connection connection, String schema, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.resolveCatalogRelation(connection, schema, table, timeoutSeconds);
        }
        return TidbRelationIdentity.resolveCatalogRelation(connection, schema, table, timeoutSeconds);
    }

    /**
     * 用于两个独立选择的 MySQL/MariaDB 门面路由的原生选择器。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(
            Connection connection, String schema, int maxTables, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
        }
        return TidbRelationIdentity.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
    }


    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return TidbRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.integerKeyProjection(alias, column);
        }
        return mysqlSignedIntegerProjection(alias, column);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.keyComponentProjection(alias, column, maxBytes);
        }
        return mysqlSignedIntegerComponent(alias, column, maxBytes);
    }

    /**
     * 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。
     */
    @Override
    public BoundedTextKeyProjection textKeyProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.textKeyProjection(alias, column, maxBytes);
        }
        return mysqlExactTextProjection(alias, column, maxBytes, true);
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.textColumnExpression(column);
        }
        return "CAST(" + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TIDB, column) + " AS CHAR)";
    }

    /**
     * 生成文本或整数键分量的查询投影。
     */
    @Override
    public BoundedKeyComponentProjection textAndIntegerKeyComponentProjection(Connection connection,
            String alias, String column, int maxBytes) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TIDB) {
            return super.textAndIntegerKeyComponentProjection(connection, alias, column, maxBytes);
        }
        return mysqlTextAndIntegerComponent(textKeyProjection(alias, column, maxBytes));
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.TIDB;
    }

    /**
     * Connector/J 的 JDBC 列模式处理可能丢弃含标识符引号的物理 TiDB 名称。
     * 原生相等条件保持名称为字面量，包括 '%' 与 '_'，并保留目录序号顺序。
     * 调用方保留连接与事务；此观测不提交事务，也不修改会话。
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName, String tableName) {
        String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE BINARY TABLE_SCHEMA = BINARY ? AND BINARY TABLE_NAME = BINARY ? ORDER BY ORDINAL_POSITION";
        List<String> names = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
            return names;
        } catch (SQLException unavailable) {
            throw new IllegalStateException("TiDB physical column names could not be observed", unavailable);
        }
    }

    /** 仅允许物理名称；列可空、非键列、无默认值、无 AUTO_RANDOM、无 AUTO_INCREMENT 且不是生成列。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return TidbAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }
}
