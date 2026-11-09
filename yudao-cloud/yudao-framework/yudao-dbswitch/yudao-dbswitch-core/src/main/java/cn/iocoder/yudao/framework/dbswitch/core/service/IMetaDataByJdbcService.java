package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.core.model.SqlExplainResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.DataSecurityParam;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;

import java.util.List;

/**
 * 元信息获取接口定义
 *
 * @author jrl
 */
public interface IMetaDataByJdbcService {

    /**
     * 以元数据角色借用并关闭连接，执行产品固定的只读健康探测。
     * timeoutMillis 约束已建立连接上的探测；建连期限由部署驱动配置约束。
     * 不接受调用方 SQL，不要求 JDBC networkTimeout 支持；旧实现显式拒绝。
     */
    default void probeConnection(String jdbcUrl, String username, String password, int timeoutMillis) {
        throw new UnsupportedOperationException("当前元数据实现不支持有界连接探测");
    }

    /**
     * 读取当前连接数据库的已用字节数，不代表宿主机容量或剩余空间。
     * 本组件持有并关闭有界元数据连接；不支持的产品显式拒绝。
     * MySQL 值来自当前账号可见表的目录统计，可能近似且不包含日志文件。
     */
    default long queryDatabaseUsedBytes(String jdbcUrl, String username, String password, int timeoutMillis) {
        throw new UnsupportedOperationException("当前产品未提供数据库容量采集");
    }

    /** 本组件持有的单个有界元数据连接；旧门面会拒绝，而非返回调用方名称。 */
    default cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity resolveCatalogRelation(
            String jdbcUrl, String username, String password, String schemaName, String tableName, int timeoutMillis) {
        throw new UnsupportedOperationException("当前元数据实现不支持原生 catalog 关系名解析");
    }

    /**
     * 通过本组件持有的单个元数据提供方连接，读取基本列和有序主键。
     * 保留产品现有语句及原生超时；不要求支持网络超时，
     * 不补齐默认值或索引，也不执行完整原生结构认证。键读取
     * 失败必须保持失败。此契约不提供事务一致的结构快照，
     * 也不承诺重建完整 DDL。旧实现默认显式拒绝。
     */
    default cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata queryTableColumnsAndKeys(
            String jdbcUrl, String username, String password, String schemaName, String tableName) {
        throw new UnsupportedOperationException("当前元数据实现不支持基础表列与主键联合读取");
    }

    /** 精确物理 schema（不是 JDBC pattern）内有界列表；未知产品不降级为模糊匹配。 */
    default List<TableDescription> queryExactTableList(String jdbcUrl, String username, String password,
            String schemaName, int maxTables, int timeoutMillis) {
        throw new UnsupportedOperationException("当前元数据实现不支持精确表列表");
    }

    /** 零行读取精确物理关系的列；projection 为 null 读取全部列，空集合不代表全部列。 */
    default List<ColumnDescription> queryExactTableColumns(String jdbcUrl, String username, String password,
            String schemaName, String tableName,
            List<String> projection, int timeoutMillis) {
        throw new UnsupportedOperationException("当前元数据实现不支持精确列元数据");
    }

    /**
     * 仅当调用方的原生目录观测在本组件持有的元数据连接上仍匹配时，才读取列，
     * 并在零行读取前后都进行检查。标识不等于授权。
     * 旧实现会拒绝，而非静默丢弃预期代际。
     */
    default List<ColumnDescription> queryExactTableColumns(String jdbcUrl, String username, String password,
            cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity expectedRelation,
            List<String> projection, int timeoutMillis) {
        throw new UnsupportedOperationException("当前元数据实现不支持原生关系身份绑定的精确列读取");
    }

    /** 受总读取期限约束的表结构读取；未知实现必须显式拒绝，不能退化为无限等待。 */
    default cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata queryTableSchema(
            String jdbcUrl, String username, String password, String schemaName, String tableName,
            int timeoutMillis) {
        throw new UnsupportedOperationException("当前元数据实现不支持有界表结构读取");
    }

    /**
     * 获取数据库类型
     *
     * @return
     */
    ProductTypeEnum getDatabaseType();

    /**
     * 获取数据库的schema模式列表
     *
     * @param jdbcUrl  数据库连接的JDBC-URL
     * @param username 数据库连接的帐号
     * @param password 数据库连接的密码
     * @return
     */
    List<String> querySchemaList(String jdbcUrl, String username, String password);

    /**
     * 获取指定Schema下所有的表列表
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName 模式名称
     * @return
     */
    List<TableDescription> queryTableList(String jdbcUrl, String username, String password,
            String schemaName);

    /**
     * 获取物理表的DDL建表语句
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return
     */
    String getTableDDL(String jdbcUrl, String username, String password, String schemaName,
            String tableName);

    /**
     * 获取视图表的DDL建表语句
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return
     */
    String getViewDDL(String jdbcUrl, String username, String password, String schemaName,
            String tableName);

    /**
     * 获取指定schema.table的表结构字段信息
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName 模式名称
     * @param tableName  表或视图名称
     * @return
     */
    List<ColumnDescription> queryTableColumnMeta(String jdbcUrl, String username, String password,
            String schemaName, String tableName);

    /**
     * 获取指定schema.table的表结构字段信息
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName 模式名称
     * @param tableName  表或视图名称
     * @return
     */
    List<ColumnDescription> queryTableColumnMetaOnly(String jdbcUrl, String username, String password,
            String schemaName, String tableName);

    /**
     * 获取指定SQL结构字段信息
     *
     * @param jdbcUrl  数据库连接的JDBC-URL
     * @param username 数据库连接的帐号
     * @param password 数据库连接的密码
     * @param querySql 查询的SQL语句
     * @return
     */
    List<ColumnDescription> querySqlColumnMeta(String jdbcUrl, String username, String password,
            String querySql);

    /**
     * 获取表的主键信息字段列表
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName Schema模式名称
     * @param tableName  Table表名称
     * @return
     */
    List<String> queryTablePrimaryKeys(String jdbcUrl, String username, String password,
            String schemaName,
            String tableName);

    /**
     * 执行查询 SQL 并读取结果数据。
     */
    SchemaTableData queryTableDataBySql(String jdbcUrl, String username, String password,
            String sql, int rowCount);

    /**
     * 执行查询 SQL 并读取结果数据。
     */
    SchemaTableData queryTableDataBySql(String jdbcUrl, String username, String password, String scheamName,
            String sql, int rowCount);

    /**
     * 为查询 SQL 应用分页边界并读取结果。
     */
    JdbcSelectResult pageDataBySql(String jdbcUrl, String username, String password,
            String sql, List<Object> params, Integer page, Integer limit, DataSecurityParam dataSecurityParam);

    /**
     * 为查询 SQL 应用分页边界并读取结果；数据查询与 count 查询各自受 JDBC 查询超时约束。
     * 超时由驱动取消语句，异常链中可用 {@code QueryTimeoutConnection.isQueryTimeout} 识别。
     *
     * @param queryTimeoutSeconds 每条语句的超时秒数，小于 1 表示不限制
     */
    JdbcSelectResult pageDataBySql(String jdbcUrl, String username, String password,
            String sql, List<Object> params, Integer page, Integer limit, DataSecurityParam dataSecurityParam,
            int queryTimeoutSeconds);

    /**
     * 执行给定 SQL 并返回当前操作结果。
     */
    JdbcSelectResult executeBySql(String jdbcUrl, String dbType, String username, String password,
            String sql, Integer openTrans, int rowCount, DataSecurityParam dataSecurityParam);

    /**
     * 构建当前规则或执行计划的说明信息。
     */
    List<SqlExplainResult> explain(String sql, String dbType);

    /**
     * 测试数据库SQL查询
     *
     * @param jdbcUrl  数据库连接的JDBC-URL
     * @param username 数据库连接的帐号
     * @param password 数据库连接的密码
     * @param sql      待查询的SQL语句
     */
    void testQuerySQL(String jdbcUrl, String username, String password, String sql);

    /**
     * 测试数据库SQL查询
     *
     * @param jdbcUrl  数据库连接的JDBC-URL
     * @param username 数据库连接的帐号
     * @param password 数据库连接的密码
     * @param sql      待查询的SQL语句
     */
    void executeSql(String jdbcUrl, String username, String password, String sql);

    /**
     * 在当前数据库连接上执行给定 SQL。
     */
    void executeSql(String jdbcUrl, String username, String password, String sql, List<Object> params);


    /**
     * 测试数据库SQL查询
     *
     * @param jdbcUrl  数据库连接的JDBC-URL
     * @param username 数据库连接的帐号
     * @param password 数据库连接的密码
     */
    Object queryMaxVal(String jdbcUrl, String username, String password, String schemaName, String tableName,
            String columnName);

    /**
     * 测试数据库SQL查询
     *
     * @param jdbcUrl  数据库连接的JDBC-URL
     * @param username 数据库连接的帐号
     * @param password 数据库连接的密码
     */
    Object queryMaxVal(String jdbcUrl, String username, String password, String sql, String columnName);

    /**
     * 测试数据库SQL查询
     *
     * @param jdbcUrl   数据库连接的JDBC-URL
     * @param username  数据库连接的帐号
     * @param password  数据库连接的密码
     * @param tableName 表名
     */
    boolean tableExist(String jdbcUrl, String username, String password, String tableName);

    /**
     * 获取(物理/视图)表的元数据
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName Schema模式名称
     * @param tableName  Table表名称
     * @return
     */
    SchemaTableMeta queryTableMeta(String jdbcUrl, String username, String password,
            String schemaName, String tableName);

    /**
     * 获取(物理/视图)表的数据内容
     *
     * @param jdbcUrl    数据库连接的JDBC-URL
     * @param username   数据库连接的帐号
     * @param password   数据库连接的密码
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param rowCount   记录总数
     * @return
     */
    SchemaTableData queryTableData(String jdbcUrl, String username, String password,
            String schemaName, String tableName, int rowCount);

    /**
     * 检查指定模式下是否存在目标表。
     */
    boolean tableExist(String jdbcUrl, String username, String password, String schema, String tableName);

    /**
     * 根据字段结构信息组装对应数据库的建表DDL语句
     *
     * @param type        目的数据库类型
     * @param fieldNames  字段结构信息
     * @param primaryKeys 主键字段信息
     * @param schemaName  模式名称
     * @param tableName   表名称
     * @param autoIncr    是否允许主键自增
     * @return 对应数据库的DDL建表语句
     */
    String getDDLCreateTableSQL(ProductTypeEnum type, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, boolean autoIncr);

    /** 观测调用方持有的目标连接后生成 DDL；绝不关闭或提交该连接。 */
    String getDDLCreateTableSQL(java.sql.Connection connection, ProductTypeEnum type,
            List<ColumnDescription> fieldNames, List<String> primaryKeys,
            String schemaName, String tableName, boolean autoIncr);

    /**
     * 生成当前对象或范围对应的 SELECT 语句。
     */
    String getSqlSelect(List<ColumnDescription> columnDescriptions, String schemaName, String tableName,
            String tableRemarks);

    /**
     * 生成判断重复分组数量大于一的查询 SQL。
     */
    String getCountMoreThanOneSql(String schemaName, String tableName, List<String> columns);

    /**
     * 生成判断分组数量为一的查询 SQL。
     */
    String getCountOneSql(String schemaName, String tableName, List<String> columns);

    /**
     * 检查当前数据范围中的重复值或重复键。
     */
    Boolean checkRepeat(String jdbcUrl, String userName, String password, String databaseSchema, String sourceTableName,
            String name, Object value);

    /**
     * 删除指定数据库表。
     */
    void dropTable(String jdbcUrl, String userName, String password, String schema, String table);
}
