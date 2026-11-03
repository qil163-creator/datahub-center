package cn.iocoder.yudao.framework.dbswitch.core.service.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.DatabaseDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByDescriptionService;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcUrlUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 用IP:PORT等参数配置的元数据获取服务
 *
 * @author jrl
 */
public class MetaDataByDescriptionServiceImpl implements IMetaDataByDescriptionService {

    private static int connectTimeOut = 6;

    protected AbstractDatabase database = null;

    protected DatabaseDescription databaseDesc = null;

    private final JdbcConnectionProvider connections;

    /**
     * 校验构造参数并初始化 {@code MetaDataByDescriptionServiceImpl}。
     */
    public MetaDataByDescriptionServiceImpl(DatabaseDescription databaseDesc, DbSwitchProductRegistry products,
            JdbcConnectionProvider connections) {
        this.databaseDesc = databaseDesc;
        this.database = products.metadata(databaseDesc.getType());
        this.connections = java.util.Objects.requireNonNull(connections, "connections");
    }

    /**
     * 获取数据库的连接信息。
     */
    @Override
    public DatabaseDescription getDatabaseConnection() {
        return this.databaseDesc;
    }

    /**
     * 获取数据库的schema模式列表。
     */
    @Override
    public List<String> querySchemaList() {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return database.querySchemaList(connection);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定Schema下所有的表列表。
     */
    @Override
    public List<TableDescription> queryTableList(String schemaName) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return database.queryTableList(connection, schemaName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取物理表的DDL建表语句。
     */
    @Override
    public String getTableDDL(String schemaName, String tableName) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return database.getTableDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取物理表的DDL建表语句。
     */
    @Override
    public String getViewDDL(String schemaName, String tableName) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return database.getViewDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定schema.table的表结构字段信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(String schemaName, String tableName) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            List<ColumnDescription> columnDescriptions = database.queryTableColumnMeta(connection, schemaName,
                    tableName);
            database.setColumnDefaultValue(connection, schemaName, tableName, columnDescriptions);
            database.setColumnIndexInfo(connection, schemaName, tableName, columnDescriptions);
            return columnDescriptions;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定SQL结构字段信息。
     */
    @Override
    public List<ColumnDescription> querySqlColumnMeta(String querySql) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return database.querySelectSqlColumnMeta(connection, querySql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的主键信息字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(String schemaName, String tableName) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return database.queryTablePrimaryKeys(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的元数据。
     */
    @Override
    public SchemaTableMeta queryTableMeta(String schemaName, String tableName) {
        SchemaTableMeta tableMeta = new SchemaTableMeta();
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            TableDescription tableDesc = this.database.queryTableMeta(connection, schemaName, tableName);
            if (null == tableDesc) {
                throw new IllegalArgumentException("Table Or View Not Exist");
            }
            List<ColumnDescription> columns = this.queryTableColumnMeta(schemaName, tableName);

            List<String> pks;
            String createSql;
            if (tableDesc.isViewTable()) {
                pks = Collections.emptyList();
                createSql = this.database.getViewDDL(connection, schemaName, tableName);
            } else {
                pks = this.database.queryTablePrimaryKeys(connection, schemaName, tableName);
                createSql = this.database.getTableDDL(connection, schemaName, tableName);
            }

            tableMeta.setSchemaName(schemaName);
            tableMeta.setTableName(tableName);
            tableMeta.setTableType(tableDesc.getTableType());
            tableMeta.setRemarks(tableDesc.getRemarks());
            tableMeta.setColumns(columns);
            tableMeta.setPrimaryKeys(pks);
            tableMeta.setCreateSql(createSql);

            return tableMeta;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的数据内容。
     */
    @Override
    public SchemaTableData queryTableData(String schemaName, String tableName, int rowCount) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();

        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            return this.database.queryTableData(connection, schemaName, tableName, rowCount);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 测试数据库SQL查询。
     */
    @Override
    public void testQuerySQL(String sql) {
        String jdbcUrl = JdbcUrlUtils.getJdbcUrl(
                this.databaseDesc, MetaDataByDescriptionServiceImpl.connectTimeOut);
        String username = this.databaseDesc.getUsername();
        String password = this.databaseDesc.getPassword();
        try (Connection connection = connections.open(jdbcUrl, username, password)) {
            database.testQuerySQL(connection, sql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 根据字段结构信息组装对应数据库的建表DDL语句。
     */
    @Override
    public String getDDLCreateTableSQL(ProductTypeEnum type, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, boolean autoIncr) {
        if (type != database.getDatabaseType()) {
            throw new IllegalArgumentException("DDL product differs from metadata provider");
        }
        return GenerateSqlUtils.getDDLCreateTableSQL(
                database, fieldNames, primaryKeys, schemaName, tableName, autoIncr);
    }

}
