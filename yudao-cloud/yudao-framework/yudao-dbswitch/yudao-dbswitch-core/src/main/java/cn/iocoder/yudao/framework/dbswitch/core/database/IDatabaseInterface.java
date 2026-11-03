package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

/**
 * 数据库访问通用业务接口
 *
 * @author jrl
 */
public interface IDatabaseInterface {

    /** 读取账号角色并验证当前数据库连接和目标命名空间使用权限，不表示任意对象的写权限。 */
    default void probeAccountPrivileges(Connection connection, String namespace,
            int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Account privilege diagnostics are not verified", "0A000");
    }

    /**
     * 为目录采样的固定零行诊断生成物理限定名，不解析任意 SQL、不授予对象权限。
     * 默认沿用既有标识符边界；多模式产品可在借用连接上有界验证模式，不接管连接或事务。
     */
    default String diagnosticTableReference(Connection connection, String namespace, String table,
            int timeoutSeconds) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .qualifiedTableName(getDatabaseType(), namespace, table);
    }

    /** 精确关系的表/列注释目录读取；注释不存在也是有效观察，不读取业务行。 */
    default cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Extended-property diagnostics are not verified", "0A000");
    }

    /** 只读检查 CDC 日志配置和账号复制角色，不创建复制槽、不连接复制流。 */
    default cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("CDC readiness diagnostics are not verified", "0A000");
    }

    /** 在独占诊断会话验证目标命名空间的进入/使用权限；目录可见性不等于可访问。 */
    default void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Namespace access diagnostics are not verified", "0A000");
    }

    /** 只允许组件生成的随机表名；未用真实产品验证的临时写入能力默认不启用。 */
    default cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        throw new SQLFeatureNotSupportedException("Temporary-table diagnostics are not verified", "0A000");
    }

    /** 在写入连接建表前核验产品原生版本或会话能力；默认空操作保留旧产品兼容性。 */
    default void validateTemporaryTableDiagnosticConnection(Connection connection, int timeoutSeconds) throws SQLException {
    }

    /**
     * 借用连接执行产品固定的只读探测，不使用查询校验/DDL 包装。
     * 调用方必须提供有界执行器：旧驱动不实现 setQueryTimeout 时由外层期限兜底。
     * 成功必须实际取得服务器响应；不把 Connection.isValid 的本地状态当作探活。
     */
    default void probeConnection(Connection connection, int timeoutSeconds) throws SQLException {
        if (connection == null || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Probe connection and deadline are required");
        }
        String sql = getDatabaseType().getTestSql();
        if (sql == null || sql.isBlank()) {
            throw new SQLFeatureNotSupportedException("This product requires a native connection probe");
        }
        try (var statement = connection.createStatement()) {
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException |
                     AbstractMethodError unsupported) { /* Enclosing controlled execution owns the deadline. */ } catch (
                    SQLException failure) {
                if (!"0A000".equals(failure.getSQLState())) {
                    throw failure;
                }
            }
            try (var result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new SQLException("Connection probe returned no server response");
                }
            }
        }
    }

    /** 借用当前连接读取当前数据库已用字节；不改变连接状态、不推断磁盘总量。 */
    default long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Database capacity is not verified for " + getDatabaseType());
    }

    /**
     * 获取数据库类型
     *
     * @return 数据库类型
     */
    ProductTypeEnum getDatabaseType();

    /**
     * 在此借用连接上，将分别传入的物理名称解析为原生目录中的名称和对象 ID。
     * 绝不根据输入名称或 JDBC 通配模式推断标识。
     * 不修改或关闭连接，也不授予权限；观测结果之后可能失效。
     * 在原生契约得到验证前，现有产品及扩展仍视为不支持。
     */
    default cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity resolveCatalogRelation(
            Connection connection, String schemaName, String tableName, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Native catalog relation names are not verified for " + getDatabaseType());
    }

    /**
     * 使用目录中的实际名称选择原生基本表，并限制结果规模，不使用 JDBC schema/catalog 模式。
     * 借用的连接及授权仍由调用方负责。绝不返回被截断的列表。
     * 此列表不能证明单张表的标识，也不授予查询权限。
     */
    default List<TableDescription> queryCatalogTableList(
            Connection connection, String schemaName, int maxTables, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Native catalog table listing is not verified for " + getDatabaseType());
    }

    /**
     * 在实际读取连接建立只读一致快照；只接受独占的 autoCommit=true 连接。
     * 正常 close 才能采信读取结果，作用域内不得改事务/连接状态或跨线程使用。
     * 未验证产品默认明确拒绝；不能据此推断同步到数或跨库身份。
     */
    default ReadSnapshot openReadSnapshot(
            Connection connection, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Read snapshot is not verified for " + getDatabaseType());
    }

    /**
     * 在调用方实际读取连接上解析关系身份，返回不含明文端点/账号的 SHA-256 摘要。
     * 不用 JDBC 列结构或未解析的表名替代物理对象身份。调用方需在读取前后复验；
     * 此方法不提供快照隔离、不拥有/关闭连接，也不改变事务、schema 或角色。
     * 不支持的产品、关系种类或缺少身份读取权限时显式拒绝；其他 SQL 故障向上传播。
     * 默认拒绝保持既有 SPI 二进制兼容，兼容协议产品必须独立实现/验证。
     */
    default String queryRelationFingerprint(Connection connection, String relationReference) throws SQLException {
        throw new SQLFeatureNotSupportedException("Relation identity is not verified for " + getDatabaseType());
    }

    /** 可选的部署固定源代际；绝不静默忽略为另一产品设置的固定信息。 */
    default String queryRelationFingerprint(Connection connection, String relationReference, SourceGenerationPin pin)
            throws SQLException {
        if (pin != null) {
            throw new SQLFeatureNotSupportedException(
                    "Managed source generations are not verified for " + getDatabaseType());
        }
        return queryRelationFingerprint(connection, relationReference);
    }

    /** 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。 */
    default String textColumnExpression(String columnName) {
        throw new UnsupportedOperationException("Text column conversion is not verified for " + getDatabaseType());
    }

    /**
     * 为组件生成的普通 SELECT 设置上限，不接受已有 TOP、DISTINCT、limit 或 CTE 的语句。
     * 它不是任意 SQL 解析器、授权边界，也不能限制服务器扫描工作量。
     * 现有 LIMIT 产品保留其精确输出；未验证的产品仍不支持。
     */
    default String limitGeneratedSelect(String sql, int maxRows) {
        if (sql == null || !sql.startsWith("SELECT ") || maxRows < 1 || maxRows > 1_000_001) {
            throw new IllegalArgumentException("Generated SELECT and bounded row count are required");
        }
        return switch (getDatabaseType()) {
            case SQLSERVER -> "SELECT TOP (" + maxRows + ") " + sql.substring(7);
            case ORACLE -> sql + " FETCH FIRST " + maxRows + " ROWS ONLY";
            case POSTGRESQL, MYSQL, MARIADB, DORIS, CLICKHOUSE, OPENGAUSS, STARROCKS, TIDB,
                 OCEANBASE, TDENGINE, INFLUXDB3, HIVE, GREENPLUM, HIGHGO, DM, KINGBASE, OSCAR, UXDB ->
                    sql + " LIMIT " + maxRows;
            default -> throw new UnsupportedOperationException(
                    "Generated SELECT limit is not verified for " + getDatabaseType());
        };
    }

    /** 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。 */
    default BoundedIntegerKeyProjection integerKeyProjection(String tableAlias, String columnName) {
        throw new UnsupportedOperationException("Bounded integer keys are not verified for " + getDatabaseType());
    }

    /** 多兼容模式产品在实际连接上验证方言；默认委托旧 SPI，保留已有产品/扩展的行为。 */
    default BoundedIntegerKeyProjection integerKeyProjection(Connection connection, String tableAlias,
            String columnName) throws SQLException {
        return integerKeyProjection(tableAlias, columnName);
    }

    /** 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。 */
    default BoundedTextKeyProjection textKeyProjection(String tableAlias, String columnName, int maxBytes) {
        throw new UnsupportedOperationException("Bounded text keys are not verified for " + getDatabaseType());
    }

    /**
     * 显式启用产品已验证的文本与整数混合组件。
     * 不扩展现有 keyComponentProjection 契约，也不持有调用方的连接。
     * 调用方必须使用单独且兼容的验证协议；未验证的产品默认拒绝。
     */
    default BoundedKeyComponentProjection textAndIntegerKeyComponentProjection(Connection connection,
            String tableAlias, String columnName, int maxBytes) throws SQLException {
        throw new UnsupportedOperationException(
                "Mixed text/integer components are not verified for " + getDatabaseType());
    }

    /** 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。 */
    default BoundedKeyComponentProjection keyComponentProjection(String tableAlias, String columnName, int maxBytes) {
        throw new UnsupportedOperationException("Bounded key components are not verified for " + getDatabaseType());
    }

    /** 连接感知的复合键投影；不得为了检测模式改变连接状态。 */
    default BoundedKeyComponentProjection keyComponentProjection(Connection connection, String tableAlias,
            String columnName, int maxBytes) throws SQLException {
        return keyComponentProjection(tableAlias, columnName, maxBytes);
    }

    /**
     * 获取数据库的JDBC驱动类
     *
     * @return
     */
    String getDriverClassName();

    /**
     * 获取数据库的模式schema列表
     *
     * @param connection JDBC连接
     * @return 模式名列表
     */
    List<String> querySchemaList(Connection connection);

    /**
     * 获取指定模式Schema内的所有表列表
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @return 表及视图名列表
     */
    List<TableDescription> queryTableList(Connection connection, String schemaName);

    /**
     * 精确获取表或视图的元数据
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表或视图名称
     * @return
     */
    TableDescription queryTableMeta(Connection connection, String schemaName, String tableName);

    /**
     * 获取指定物理表的DDL语句
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return 字段元信息列表
     */
    String getTableDDL(Connection connection, String schemaName, String tableName);

    /**
     * 获取指定视图表的DDL语句
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表或视图名称
     * @return 字段元信息列表
     */
    String getViewDDL(Connection connection, String schemaName, String tableName);

    /**
     * 获取指定模式表的字段列表
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表或视图名称
     * @return 字段元信息列表
     */
    List<String> queryTableColumnName(Connection connection, String schemaName,
            String tableName);

    /**
     * 读取并填充列的默认值信息。
     */
    void setColumnDefaultValue(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions);

    /**
     * 读取并填充列的索引信息。
     */
    void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions);

    /**
     * 获取指定模式表的元信息
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表或视图名称
     * @return 字段元信息列表
     */
    List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName);

    /**
     * 获取指定查询SQL的元信息
     *
     * @param connection JDBC连接
     * @param sql        SQL查询语句
     * @return 字段元信息列表
     */
    List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql);

    /**
     * 读取表结构中的列定义。
     */
    List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName);

    /**
     * 获取指定模式表的主键字段列表
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return 主键字段名称列表
     */
    List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName);

    /**
     * 获取指定模式表的索引列表（移植自 dbswitch 2.0.2）：按索引分组、按键序排列，主键背后的索引也会返回。
     * 部分索引条件与函数/表达式键原样保留，由调用方判断能否重建；没有二级索引概念的产品返回空列表。
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return 索引列表
     */
    List<IndexDescription> queryTableIndexes(Connection connection, String schemaName, String tableName);

    /**
     * 获取指定模式表内的数据
     *
     * @param connection JDBC连接
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param rowCount   记录的行数
     * @return 数据内容
     */
    SchemaTableData queryTableData(Connection connection, String schemaName, String tableName,
            int rowCount);

    /**
     * 执行查询 SQL 并读取结果数据。
     */
    SchemaTableData queryTableDataBySql(Connection connection, String sql, int rowCount);

    /**
     * 测试查询SQL语句的有效性
     *
     * @param connection JDBC连接
     * @param sql        待验证的SQL语句
     */
    void testQuerySQL(Connection connection, String sql);

    /**
     * 获取数据库的表全名
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return 表全名
     */
    String getQuotedSchemaTableCombination(String schemaName, String tableName);

    /**
     * 获取字段列的结构定义
     *
     * @param v           值元数据定义
     * @param pks         主键字段名称列表
     * @param addCr       是否结尾换行
     * @param useAutoInc  是否自增
     * @param withRemarks 是否带有注释
     * @return 字段定义字符串
     */
    String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
            boolean withRemarks);

    /**
     * 主键列转换为逗号分隔的字符串
     *
     * @param pks 主键字段列表
     * @return 主键字段拼接串
     */
    String getPrimaryKeyAsString(List<String> pks);

    /**
     * 获取表和字段的注释定义
     *
     * @param td  表信息定义
     * @param cds 列信息定义
     * @return 定义字符串列表
     */
    List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds);

    /**
     * 获取字段类型
     *
     * @param v
     * @return
     */
    boolean canCreateIndex(ColumnMetaData v);

    /**
     * 根据要同步的字段添加目标表中不存在的字段
     *
     * @param targetSchemaName
     * @param targetTableName
     */
    void addNoExistColumnsByTarget(Connection connection, String targetSchemaName, String targetTableName,
            List<String> allColumns, List<ColumnDescription> targetColumnDescriptions);

    /**
     * 获取添加字段的sql
     *
     * @param targetSchemaName
     * @param targetTableName
     * @param targetColumn
     * @return
     */
    String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn);

    /**
     * 为建表 SQL 追加当前产品需要的前置定义。
     */
    void preAppendCreateTableSql(StringBuilder builder);

    /**
     * 将主键约束追加到建表 SQL。
     */
    void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys);

    /**
     * 为建表 SQL 追加当前产品要求的尾部定义。
     */
    void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled);

    /**
     * 为hive定制的获取联邦建表导数SQL列表
     *
     * @param fieldNames    字段结构信息
     * @param primaryKeys   主键字段信息
     * @param schemaName    模式名称
     * @param tableName     表名称
     * @param autoIncr      是否允许主键自增
     * @param tblProperties 表的属性信息
     * @return 建表导数SQL列表
     */
    default List<String> getCreateTableSqlList(List<ColumnDescription> fieldNames, List<String> primaryKeys,
            String schemaName, String tableName, String tableRemarks, boolean autoIncr,
            Map<String, String> tblProperties) {
        throw new UnsupportedOperationException("Unsupported function!");
    }
}
