package cn.iocoder.yudao.framework.dbswitch.core.service.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.StringUtil;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.DataSecurityParam;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.SqlExplainResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * 使用JDBC连接串的元数据获取服务
 * <p>
 * 职责：元数据服务的“门面”实现。调用方只需提供 jdbcUrl/username/password，
 * 本类通过显式传入的 {@link JdbcConnectionProvider} 建立连接，并把请求委托给对应数据库方言的
 * {@link AbstractDatabase} 实例执行，最后确保连接关闭（try-with-resources）。
 * <p>
 * 提供的能力包括：schema/表/字段/主键元数据查询、表与视图 DDL 获取、数据预览、
 * 分页查询、SQL 脚本执行（executeBySql）、SQL 语法检查（explain）、
 * 建表 DDL 生成、表存在性检测等。
 * <p>
 * 与 MetaDataByDataSourceServiceImpl 的区别：本类每次调用都基于连接串新建连接，
 * 后者复用传入的 DataSource（连接池）
 *
 * @author jrl
 */
public class MetaDataByJdbcServiceImpl implements IMetaDataByJdbcService {

    /** 数据库类型 */
    protected ProductTypeEnum dbType;

    /** 当前运行时产品注册表提供的方言实现。 */
    protected AbstractDatabase database;

    private final JdbcConnectionProvider connectionProvider;

    private final JdbcConnectionProvider workloadProvider;

    /**
     * 使用给定参数初始化 {@code MetaDataByJdbcServiceImpl}。
     */
    public MetaDataByJdbcServiceImpl(ProductTypeEnum type, DbSwitchProductRegistry products,
            JdbcConnectionProvider connectionProvider) {
        this(type, products, connectionProvider, connectionProvider);
    }

    /** 元数据预留资源绝不能用于任意查询、脚本或修改操作。 */
    public MetaDataByJdbcServiceImpl(ProductTypeEnum type, DbSwitchProductRegistry products,
            JdbcConnectionProvider metadataProvider, JdbcConnectionProvider workloadProvider) {
        this.dbType = java.util.Objects.requireNonNull(type, "product");
        this.database = products.metadata(type);
        this.connectionProvider = java.util.Objects.requireNonNull(metadataProvider, "metadataProvider");
        this.workloadProvider = java.util.Objects.requireNonNull(workloadProvider, "workloadProvider");
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return this.dbType;
    }

    /**
     * 探测数据库连接并返回连接诊断结果。
     */
    @Override
    public void probeConnection(String jdbcUrl, String username, String password, int timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("连接探测期限必须在一至六万毫秒之间");
        }
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.invoke(connection, timeoutMillis, () -> {
                database.probeConnection(connection, Math.max(1, (timeoutMillis + 999) / 1000));
                return null;
            });
        } catch (java.sql.SQLFeatureNotSupportedException unsupported) {
            throw new UnsupportedOperationException("当前驱动未提供受控连接探测", unsupported);
        } catch (SQLException failure) {
            throw new IllegalStateException("数据库连接探测失败", failure);
        }
    }

    /**
     * 查询数据库占用的存储字节数。
     */
    @Override
    public long queryDatabaseUsedBytes(String jdbcUrl, String username, String password, int timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("容量读取期限必须在一至六万毫秒之间");
        }
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            long bytes = cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.invoke(connection, timeoutMillis,
                    () -> database.queryDatabaseUsedBytes(connection, Math.max(1, (timeoutMillis + 999) / 1000)));
            if (bytes < 0) {
                throw new IllegalStateException("数据库容量不能为负数");
            }
            return bytes;
        } catch (java.sql.SQLFeatureNotSupportedException unsupported) {
            throw new UnsupportedOperationException("当前产品未提供数据库容量采集", unsupported);
        } catch (SQLException failure) {
            throw new IllegalStateException("数据库容量读取失败", failure);
        }
    }

    /**
     * 解析系统目录关系。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity resolveCatalogRelation(
            String jdbcUrl, String username, String password, String schemaName, String tableName, int timeoutMillis) {
        if (schemaName == null || schemaName.isBlank() || tableName == null || tableName.isBlank()
                || timeoutMillis < 1 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("明确的 schema/table 与一至六万毫秒期限是必需的");
        }
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password);
             var deadline = new cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline(connection,
                     timeoutMillis)) {
            var result = database.resolveCatalogRelation(connection, schemaName, tableName,
                    Math.max(1, (timeoutMillis + 999) / 1000));
            if (result == null || result.product() != dbType) {
                throw new IllegalStateException("原生 catalog 未返回匹配产品的完整关系身份");
            }
            deadline.check();
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException("原生 catalog 关系名解析失败", failure);
        }
    }

    /** 基础读取有意绕过门面接口的完整模式读取、信息补充和截止时间处理路径。 */
    @Override
    public BasicTableMetadata queryTableColumnsAndKeys(String jdbcUrl, String username, String password,
            String schemaName, String tableName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            List<ColumnDescription> columns = database.queryTableColumnMeta(connection, schemaName, tableName);
            List<String> keys = database.queryTablePrimaryKeys(connection, schemaName, tableName);
            return new BasicTableMetadata(columns, keys);
        } catch (SQLException failure) {
            throw new IllegalStateException("Basic table column and primary-key metadata read failed", failure);
        }
    }

    /**
     * 精确物理 schema（不是 JDBC pattern）内有界列表；未知产品不降级为模糊匹配。
     */
    @Override
    public List<TableDescription> queryExactTableList(String jdbcUrl, String username, String password,
            String schemaName, int maxTables, int timeoutMillis) {
        // 复用同一精确标识符契约，避免 schema 中的 %/_ 变成元数据匹配模式。
        RestrictedSelectGuard.quotePhysicalIdentifier(dbType, schemaName);
        if (maxTables < 1 || maxTables > 10_000) {
            throw new IllegalArgumentException("表列表上限必须在一至一万之间");
        }
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password);
             var deadline = new cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline(connection,
                     timeoutMillis)) {
            if (dbType == ProductTypeEnum.TIDB || dbType == ProductTypeEnum.MYSQL
                    || dbType == ProductTypeEnum.MARIADB) {
                var tables = database.queryCatalogTableList(connection, schemaName, maxTables,
                        Math.max(1, (timeoutMillis + 999) / 1000));
                deadline.check();
                return tables;
            }
            var metadata = connection.getMetaData();
            String escape = metadata.getSearchStringEscape();
            if (escape == null || escape.isEmpty()) {
                throw new UnsupportedOperationException("驱动未声明元数据模式转义能力");
            }
            String pattern = schemaName.replace(escape, escape + escape)
                    .replace("%", escape + "%").replace("_", escape + "_");
            Map<String, TableDescription> tables = new java.util.TreeMap<>();
            try (var rows = metadata.getTables(connection.getCatalog(), pattern, "%", new String[]{"TABLE", "VIEW"})) {
                while (rows.next()) {
                    deadline.check();
                    if (!schemaName.equals(rows.getString("TABLE_SCHEM"))) {
                        continue;
                    }
                    var table = new TableDescription();
                    table.setSchemaName(rows.getString("TABLE_SCHEM"));
                    table.setTableName(rows.getString("TABLE_NAME"));
                    table.setTableType(rows.getString("TABLE_TYPE"));
                    table.setRemarks(rows.getString("REMARKS"));
                    tables.put(table.getTableName(), table);
                    if (tables.size() > maxTables) {
                        throw new IllegalArgumentException("表列表超过读取上限，不能返回被截断的结果");
                    }
                }
            }
            deadline.check();
            return List.copyOf(tables.values());
        } catch (SQLException ex) {
            throw new IllegalStateException("精确表列表读取失败", ex);
        }
    }

    /**
     * 零行读取精确物理关系的列；projection 为 null 读取全部列，空集合不代表全部列。
     */
    @Override
    public List<ColumnDescription> queryExactTableColumns(String jdbcUrl, String username, String password,
            String schemaName, String tableName,
            List<String> projection, int timeoutMillis) {
        return queryExactTableColumns(jdbcUrl, username, password, schemaName, tableName, projection, timeoutMillis,
                null);
    }

    /**
     * 按物理名称精确读取表列元数据。
     */
    @Override
    public List<ColumnDescription> queryExactTableColumns(String jdbcUrl, String username, String password,
            cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity expectedRelation,
            List<String> projection, int timeoutMillis) {
        if (expectedRelation == null || expectedRelation.product() != dbType || dbType != ProductTypeEnum.TIDB) {
            throw new UnsupportedOperationException("原生身份绑定列读取仅接受已验证产品的完整关系观察");
        }
        return queryExactTableColumns(jdbcUrl, username, password, expectedRelation.schemaName(),
                expectedRelation.tableName(), projection, timeoutMillis, expectedRelation);
    }

    /**
     * 按物理名称精确读取表列元数据。
     */
    private List<ColumnDescription> queryExactTableColumns(String jdbcUrl, String username, String password,
            String schemaName, String tableName, List<String> projection, int timeoutMillis,
            cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity expectedRelation) {
        String relation = RestrictedSelectGuard.qualifiedTableName(dbType, schemaName, tableName);
        String columns = "*";
        if (projection != null) {
            if (projection.isEmpty() || projection.size() > 2000
                    || new java.util.HashSet<>(projection).size() != projection.size()) {
                throw new IllegalArgumentException("列投影为空、重复或超过上限");
            }
            columns = projection.stream().map(column -> RestrictedSelectGuard.quotePhysicalIdentifier(dbType, column))
                    .collect(Collectors.joining(", "));
        }
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password);
             var deadline = new cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline(connection,
                     timeoutMillis)) {
            cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity observed = null;
            if (dbType == ProductTypeEnum.TIDB) {
                observed = database.resolveCatalogRelation(connection, schemaName, tableName,
                        Math.max(1, (timeoutMillis + 999) / 1000));
                if (observed == null || observed.product() != dbType
                        || expectedRelation != null && !expectedRelation.equals(observed)) {
                    throw new IllegalStateException("TiDB 原生关系身份已变化或无法确认");
                }
                relation = RestrictedSelectGuard.qualifiedTableName(dbType, observed.schemaName(),
                        observed.tableName());
            }
            // 禁用空操作函数时，TiDB 8.5.6 会拒绝 Connector/J 的 SET TRANSACTION READ ONLY。
            // 此受控元数据路径仅执行组件自身带引号的零行 SELECT，
            // 以及原生目录读取；不要启用不受支持的服务器模式或更改事务。
            // 已验证的数据库产品继续遵循原有 JDBC 只读契约。
            if (dbType != ProductTypeEnum.TIDB) {
                connection.setReadOnly(true);
            }
            try (var statement = connection.prepareStatement(
                    "SELECT " + columns + " FROM " + relation + " WHERE 1 = 0")) {
                statement.setQueryTimeout(Math.max(1, (timeoutMillis + 999) / 1000));
                try (var result = statement.executeQuery()) {
                    var metadata = result.getMetaData();
                    List<ColumnDescription> resultColumns = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        var column = new ColumnDescription();
                        // TiDB 的列标签会原样返回请求别名的大小写；原生的原始名称
                        // 字段保留目录中的拼写，必须用它构造精确的权限键。
                        column.setFieldName(dbType == ProductTypeEnum.TIDB
                                ? metadata.getColumnName(i) : metadata.getColumnLabel(i));
                        column.setFieldTypeName(metadata.getColumnTypeName(i));
                        resultColumns.add(column);
                    }
                    if (observed != null && !observed.equals(database.resolveCatalogRelation(connection,
                            observed.schemaName(), observed.tableName(), Math.max(1, (timeoutMillis + 999) / 1000)))) {
                        throw new IllegalStateException("TiDB 原生关系在精确列读取期间变化");
                    }
                    deadline.check();
                    return List.copyOf(resultColumns);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("精确列元数据读取失败", ex);
        }
    }

    /**
     * 受总读取期限约束的表结构读取；未知实现必须显式拒绝，不能退化为无限等待。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata queryTableSchema(
            String jdbcUrl, String username, String password, String schemaName, String tableName,
            int timeoutMillis) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password);
             cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline deadline =
                     new cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline(connection, timeoutMillis)) {
            boolean oscarCatalog = dbType == ProductTypeEnum.OSCAR
                    && cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog
                    .admits(connection, schemaName, tableName);
            boolean sql2000Catalog = dbType == ProductTypeEnum.SQLSERVER2000
                    && cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqlServer2000NativeCatalog
                    .admits(connection, schemaName, tableName);
            if (dbType == ProductTypeEnum.POSTGRESQL || dbType == ProductTypeEnum.MYSQL || dbType == ProductTypeEnum.TIDB
                    || oscarCatalog || sql2000Catalog) {
                var metadata = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(
                        connection, dbType, schemaName, tableName, Math.max(1, (timeoutMillis + 999) / 1000));
                deadline.check();
                return metadata;
            }
            if (dbType == ProductTypeEnum.OSCAR) {
                // 未验证的版本或非常规名称继续使用原有 UNKNOWN JDBC 契约。
                // 旧版 Oscar 封装保留名称原有大小写，但无法安全转义名称内嵌的
                // 引号；应拒绝这种形式，不能改变其物理目标或直接插值。
                for (String name : new String[]{schemaName, tableName}) {
                    if (name == null || name.isBlank() || name.indexOf('"') >= 0 || name.indexOf('\0') >= 0) {
                        throw new java.sql.SQLFeatureNotSupportedException(
                                "Oscar legacy metadata identifier is unsupported", "0A000");
                    }
                }
            }
            List<ColumnDescription> columns = queryTableColumnMeta(connection, schemaName, tableName);
            List<String> keys = database.queryTablePrimaryKeys(connection, schemaName, tableName);
            deadline.check();
            return new cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata(columns, keys);
        } catch (SQLException ex) {
            throw new IllegalStateException("受控表结构读取失败", ex);
        }
    }

    /**
     * 查询所有 schema 名称列表（新建连接后委托给方言实现）
     */
    @Override
    public List<String> querySchemaList(String jdbcUrl, String username, String password) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return database.querySchemaList(connection);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询指定 schema 下的表与视图列表
     */
    @Override
    public List<TableDescription> queryTableList(String jdbcUrl, String username, String password,
            String schemaName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return database.queryTableList(connection, schemaName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的建表 DDL 语句
     */
    @Override
    public String getTableDDL(String jdbcUrl, String username, String password, String schemaName,
            String tableName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return database.getTableDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取视图的定义 DDL 语句
     */
    @Override
    public String getViewDDL(String jdbcUrl, String username, String password, String schemaName,
            String tableName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return database.getViewDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询表的完整字段元数据（含类型、注释、默认值、索引信息，见私有重载方法）
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(String jdbcUrl, String username,
            String password, String schemaName, String tableName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return queryTableColumnMeta(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询表的完整字段元数据（从传入的连接池取连接的重载版本）
     */
    public List<ColumnDescription> queryTableColumnMeta(DataSource dataSource, String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return queryTableColumnMeta(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 仅查询表的基础字段元数据（不补充默认值与索引信息）
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(String jdbcUrl, String username, String password,
            String schemaName, String tableName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return database.queryTableColumnMeta(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 解析查询 SQL 的结果字段元数据（自定义 SQL 抽取模式下确定字段列表）
     */
    @Override
    public List<ColumnDescription> querySqlColumnMeta(String jdbcUrl, String username,
            String password, String querySql) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            return querySqlColumnMeta(connection, querySql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 读取查询 SQL 返回的列结构。
     */
    public List<ColumnDescription> querySqlColumnMeta(DataSource dataSource, String querySql) {
        try (Connection connection = dataSource.getConnection()) {
            return querySqlColumnMeta(connection, querySql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询表的主键字段名列表
     */
    @Override
    public List<String> queryTablePrimaryKeys(String jdbcUrl, String username, String password,
            String schemaName, String tableName) {
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            return database.queryTablePrimaryKeys(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询表的完整字段元数据：先取基础元数据（字段名/类型/精度等），
     * 再依次补充字段默认值+注释、索引信息
     */
    private List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName)
            throws SQLException {
        List<ColumnDescription> columnDescriptions = database.queryTableColumnMeta(connection, schemaName, tableName);
        database.setColumnDefaultValue(connection, schemaName, tableName, columnDescriptions);
        database.setColumnIndexInfo(connection, schemaName, tableName, columnDescriptions);
        return columnDescriptions;
    }

    /**
     * 读取查询 SQL 返回的列结构。
     */
    private List<ColumnDescription> querySqlColumnMeta(Connection connection, String querySql) throws SQLException {
        return database.querySelectSqlColumnMeta(connection, querySql);
    }

    /**
     * 查询单表的聚合元信息
     * <p>
     * 主要流程：
     * 1. 查询表基本信息（不存在则抛出异常）；
     * 2. 查询字段元数据；
     * 3. 视图取视图定义 DDL 且无主键；普通表取主键列表与建表 DDL；
     * 4. 组装为 SchemaTableMeta（表名、类型、注释、字段、主键、建表语句）
     *
     * @return 聚合了表结构信息的元数据对象
     */
    @Override
    public SchemaTableMeta queryTableMeta(String jdbcUrl, String username, String password,
            String schemaName, String tableName) {
        SchemaTableMeta tableMeta = new SchemaTableMeta();
        try (Connection connection = connectionProvider.open(jdbcUrl, username, password)) {
            TableDescription tableDesc = database.queryTableMeta(connection, schemaName, tableName);
            if (null == tableDesc) {
                throw new IllegalArgumentException("Table Or View Not Exist");
            }

            List<ColumnDescription> columns = database
                    .queryTableColumnMeta(connection, schemaName, tableName);

            List<String> pks;
            String createSql;
            List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> indexes;
            if (tableDesc.isViewTable()) {
                pks = Collections.emptyList();
                createSql = database.getViewDDL(connection, schemaName, tableName);
                indexes = Collections.emptyList();
            } else {
                pks = database.queryTablePrimaryKeys(connection, schemaName, tableName);
                createSql = database.getTableDDL(connection, schemaName, tableName);
                indexes = database.queryTableIndexes(connection, schemaName, tableName);
            }

            tableMeta.setSchemaName(schemaName);
            tableMeta.setTableName(tableName);
            tableMeta.setTableType(tableDesc.getTableType());
            tableMeta.setRemarks(tableDesc.getRemarks());
            tableMeta.setColumns(columns);
            tableMeta.setPrimaryKeys(pks);
            tableMeta.setCreateSql(createSql);
            tableMeta.setIndexes(indexes);

            return tableMeta;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 预览表数据（最多返回 rowCount 行）
     */
    @Override
    public SchemaTableData queryTableData(String jdbcUrl, String username, String password,
            String schemaName, String tableName, int rowCount) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            return database.queryTableData(connection, schemaName, tableName, rowCount);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 按自定义 SQL 预览数据（最多返回 rowCount 行）
     */
    @Override
    public SchemaTableData queryTableDataBySql(String jdbcUrl, String username, String password,
            String sql, int rowCount) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            return database.queryTableDataBySql(connection, sql, rowCount);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 按自定义 SQL 预览数据（带 schema 的重载）：先尝试执行 USE schema 切库（失败忽略），再执行查询
     */
    @Override
    public SchemaTableData queryTableDataBySql(String jdbcUrl, String username, String password, String scheamName,
            String sql, int rowCount) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            try {
                database.executeSql(connection, "USE " + scheamName);
            } catch (Exception ignored) {
            }
            return database.queryTableDataBySql(connection, sql, rowCount);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 通用分页查询（委托给方言实现的 pageDataBySql，自动包装分页 SQL 与 count SQL）
     */
    @Override
    public JdbcSelectResult pageDataBySql(String jdbcUrl, String username, String password,
            String sql, List<Object> params, Integer page, Integer limit, DataSecurityParam dataSecurityParam) {
        return pageDataBySql(jdbcUrl, username, password, sql, params, page, limit, dataSecurityParam, 0);
    }

    @Override
    public JdbcSelectResult pageDataBySql(String jdbcUrl, String username, String password,
            String sql, List<Object> params, Integer page, Integer limit, DataSecurityParam dataSecurityParam,
            int queryTimeoutSeconds) {
        try (Connection connection = workloadProvider.open(database.preparePageQueryJdbcUrl(jdbcUrl), username, password)) {
            return database.pageDataBySql(
                    cn.iocoder.yudao.framework.dbswitch.core.util.QueryTimeoutConnection.wrap(connection, queryTimeoutSeconds),
                    sql, params, page, limit, dataSecurityParam);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /** 自行创建并关闭连接；openTrans=1 时显式管理本次脚本的事务。 */
    @Override
    public JdbcSelectResult executeBySql(String jdbcUrl, String dbType, String username, String password, String sql,
            Integer openTrans, int rowCount, DataSecurityParam dataSecurityParam) {
        // 完整计划在建连前完成，拒绝未验证产品和不支持的语法。
        SqlScriptPlan plan = SqlScriptPlan.parse(this.dbType, sql);
        return executePlan(jdbcUrl, username, password, plan, List.of(), openTrans, rowCount,
                dataSecurityParam, ignored -> {
                });
    }

    /** 接收已经授权的不可变计划；整份绑定数量与事务能力在建连前校验。 */
    public JdbcSelectResult executePlan(String jdbcUrl, String username, String password, SqlScriptPlan plan,
            List<Object> bindings, Integer openTrans, int rowCount, DataSecurityParam dataSecurityParam,
            java.util.function.IntConsumer beforeStep) {
        return executePlanInternal(jdbcUrl, username, password, plan, bindings, openTrans, rowCount,
                dataSecurityParam, beforeStep, 0, ignored -> {
                });
    }

    /**
     * 在一个自主管理的事务中执行已授权的 PostgreSQL DML 计划；所有精确目标
     * 须在第一条语句执行前完成加锁和验证，此操作不会授予业务权限。
     */
    public JdbcSelectResult executeAuthorizedDmlPlan(String jdbcUrl, String username, String password,
            SqlScriptPlan plan, List<Object> bindings,
            List<cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget> targets,
            java.util.function.IntConsumer beforeStep) {
        return executeAuthorizedDmlPlan(jdbcUrl, username, password, plan, bindings, targets, beforeStep, 0);
    }

    /**
     * 同 {@link #executeAuthorizedDmlPlan(String, String, String, SqlScriptPlan, List, List, java.util.function.IntConsumer)}，
     * 计划中的加锁、校验与每条 DML 语句均受 JDBC 查询超时约束；超时视为执行失败并回滚。
     *
     * @param queryTimeoutSeconds 每条语句的超时秒数，小于 1 表示不限制
     */
    public JdbcSelectResult executeAuthorizedDmlPlan(String jdbcUrl, String username, String password,
            SqlScriptPlan plan, List<Object> bindings,
            List<cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget> targets,
            java.util.function.IntConsumer beforeStep, int queryTimeoutSeconds) {
        var frozenTargets = List.copyOf(targets);
        cn.iocoder.yudao.framework.dbswitch.core.service.PostgresDmlTargetGuard.validatePlan(plan, frozenTargets);
        return executePlanInternal(jdbcUrl, username, password, plan, bindings, 1, 1,
                null, beforeStep, queryTimeoutSeconds, connection -> {
                    try {
                        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    } catch (SQLException failure) {
                        throw new IllegalArgumentException("Unable to select exact DML transaction isolation");
                    }
                    cn.iocoder.yudao.framework.dbswitch.core.service.PostgresDmlTargetGuard
                            .lockAndValidate(connection, plan, frozenTargets);
                });
    }

    /**
     * 拒绝当前实现未支持的 {@code executePlanInternal} 操作。
     */
    private JdbcSelectResult executePlanInternal(String jdbcUrl, String username, String password, SqlScriptPlan plan,
            List<Object> bindings, Integer openTrans, int rowCount, DataSecurityParam dataSecurityParam,
            java.util.function.IntConsumer beforeStep, int queryTimeoutSeconds,
            java.util.function.Consumer<Connection> beforePlan) {
        if (plan.product() != this.dbType) {
            throw new IllegalArgumentException("SQL plan product does not match the connection product");
        }
        List<Object> frozenBindings = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(bindings));
        if (plan.steps().stream().mapToInt(SqlScriptPlan.Step::parameterCount).sum() != frozenBindings.size()) {
            throw new IllegalArgumentException("SQL plan parameter count does not match bindings");
        }
        boolean transaction = Integer.valueOf(1).equals(openTrans);
        if (transaction && (this.dbType == ProductTypeEnum.MYSQL || this.dbType == ProductTypeEnum.MARIADB
                || this.dbType == ProductTypeEnum.TIDB)
                && plan.steps().stream().anyMatch(step -> step.kind()
                == cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.DDL)) {
            throw new UnsupportedOperationException(
                    "MySQL, MariaDB and TiDB DDL cannot run in an owned script transaction");
        }
        if (rowCount < 1 || rowCount > 100_000) {
            throw new IllegalArgumentException("SQL result limit must be between 1 and 100000");
        }
        JdbcSelectResult result = null;
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            connection.setAutoCommit(!transaction);
            Connection bounded = cn.iocoder.yudao.framework.dbswitch.core.util.QueryTimeoutConnection
                    .wrap(connection, queryTimeoutSeconds);
            try {
                beforePlan.accept(bounded);
                result = database.executePlan(bounded, plan, frozenBindings, rowCount, dataSecurityParam,
                        beforeStep);
            } catch (RuntimeException executionFailure) {
                result = new JdbcSelectResult();
                result.setSuccess(false);
                result.setErrorMsg("SQL script execution failed");
            }
            if (transaction) {
                try {
                    if (Boolean.TRUE.equals(result.getSuccess())) {
                        connection.commit();
                        result.setTransactionOutcome(JdbcSelectResult.TransactionOutcome.COMMITTED);
                    } else {
                        connection.rollback();
                        result.setTransactionOutcome(JdbcSelectResult.TransactionOutcome.ROLLED_BACK);
                    }
                } catch (SQLException transactionFailure) {
                    result.setSuccess(false);
                    result.setTransactionOutcome(JdbcSelectResult.TransactionOutcome.UNKNOWN);
                    result.setErrorMsg("SQL transaction outcome is unknown; verify the database before retrying");
                }
            }
            return result;
        } catch (SQLException failure) {
            if (result != null) {
                // close 失败不能覆盖已确认的提交，也不能把 UNKNOWN 改写成已回滚。
                result.setSuccess(false);
                result.setErrorMsg("SQL connection cleanup failed; inspect the transaction outcome before retrying");
                return result;
            }
            throw new IllegalStateException("Unable to open, configure or close the JDBC script connection");
        }
    }

    /** 仅检查已验证脚本语法；不执行数据库 EXPLAIN，也不宣称执行计划已验证。 */
    @Override
    public List<SqlExplainResult> explain(String sql, String dbType) {
        try {
            SqlScriptPlan plan = SqlScriptPlan.parse(this.dbType, sql);
            return plan.steps().stream()
                    .map(step -> SqlExplainResult.parsed(step.ordinal(), step.kind().name(), step.sql()))
                    .toList();
        } catch (IllegalArgumentException | UnsupportedOperationException failure) {
            return List.of(SqlExplainResult.fail(sql,
                    "SQL syntax or database product is not supported by the verified parser"));
        }
    }

    /**
     * 校验查询 SQL 能否在库上实际执行（包装为低成本探测语句后执行）
     */
    @Override
    public void testQuerySQL(String jdbcUrl, String username, String password, String sql) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            database.testQuerySQL(connection, sql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 执行单条无参 SQL
     */
    @Override
    public void executeSql(String jdbcUrl, String username, String password, String sql) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            database.executeSql(connection, sql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 执行单条带占位符参数的 SQL
     */
    @Override
    public void executeSql(String jdbcUrl, String username, String password, String sql, List<Object> params) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            database.executeSql(connection, sql, params);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询指定表中某字段的最大值（增量迁移的水位值计算）
     */
    @Override
    public Object queryMaxVal(String jdbcUrl, String username, String password, String schemaName, String tableName,
            String columnName) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            return database.queryMaxVal(connection, schemaName, tableName, columnName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 查询自定义 SQL 结果集中某字段的最大值（SQL 抽取模式的水位值计算）
     */
    @Override
    public Object queryMaxVal(String jdbcUrl, String username, String password, String sql, String columnName) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            return database.queryMaxVal(connection, sql, columnName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 判断表是否存在：执行 SELECT 1 FROM 表 WHERE 1=0 探测，执行成功即认为存在
     */
    @Override
    public boolean tableExist(String jdbcUrl, String username, String password, String tableName) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            database.executeSql(connection, String.format("SELECT 1 FROM %s WHERE 1=0", tableName));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断表是否存在（带 schema 的重载，表名与 schema 按方言加引号）
     */
    @Override
    public boolean tableExist(String jdbcUrl, String username, String password, String schema, String tableName) {
        try (Connection connection = workloadProvider.open(jdbcUrl, username, password)) {
            database.executeSql(connection, String.format("SELECT 1 FROM %s%s%s.%s%s%s WHERE 1=0", dbType.getQuote(),
                    schema, dbType.getQuote(), dbType.getQuote(), tableName, dbType.getQuote()));
            return true;
        } catch (Exception e) {
            // e.printStackTrace();
            return false;
        }
    }

    /**
     * 生成指定数据库类型的建表 DDL（委托给 GenerateSqlUtils 做跨库类型映射与拼接）
     */
    @Override
    public String getDDLCreateTableSQL(ProductTypeEnum type, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, boolean autoIncr) {
        if (type != database.getDatabaseType()) {
            throw new IllegalArgumentException("DDL target does not match the configured product");
        }
        if (database.requiresDialectConnection()) {
            throw new IllegalStateException("DDL generation for this product requires the actual target connection");
        }
        return GenerateSqlUtils.getDDLCreateTableSQL(
                database, fieldNames, primaryKeys, schemaName, tableName, autoIncr);
    }

    /**
     * 根据表和列定义生成建表 SQL。
     */
    @Override
    public String getDDLCreateTableSQL(Connection connection, ProductTypeEnum type,
            List<ColumnDescription> fieldNames, List<String> primaryKeys,
            String schemaName, String tableName, boolean autoIncr) {
        if (type != database.getDatabaseType()) {
            throw new IllegalArgumentException("DDL target does not match the configured product");
        }
        java.util.Objects.requireNonNull(connection, "Target connection is required");
        try {
            database.prepareDialect(connection);
            return GenerateSqlUtils.getDDLCreateTableSQL(
                    database, fieldNames, primaryKeys, schemaName, tableName, autoIncr);
        } catch (SQLException unavailable) {
            throw new IllegalStateException("DDL target dialect could not be verified");
        }
    }

    /**
     * 生成带字段注释的 SELECT 查询模板（供前端 SQL 编辑器一键生成查询语句）；
     * MongoDB 生成 db.getCollection(...).find(...) 形式，其余数据库生成标准 SELECT，
     * 字段与表的注释以 " -- 注释" 形式追加在行尾（注释中的引号会被去除，避免破坏 SQL）
     */
    @Override
    public String getSqlSelect(List<ColumnDescription> columns, String schemaName, String tableName,
            String tableRemarks) {
        if (ProductTypeEnum.MONGODB.equals(dbType)) {
            return String.format("db.getCollection('%s').find({},{ %s })",
                    tableName, columns.stream().map(s -> String.format("'%s' : 1\n", s.getFieldName()))
                            .collect(Collectors.joining(",")));
        }
        if (ProductTypeEnum.ELASTICSEARCH.equals(dbType)) {
            return tableName;
        }
        StringBuilder sb = new StringBuilder("SELECT\n");
        for (int i = 0; i < columns.size(); i++) {
            sb.append("    ");
            if (i > 0) {
                sb.append(",");
            }
            String columnComment = columns.get(i).getRemarks();
            if (StringUtil.isNotBlank(columnComment)) {
                if (columnComment.contains("\'") | columnComment.contains("\"")) {
                    columnComment = columnComment.replaceAll("[\"']", "");
                }
                sb.append(columns.get(i).getFieldName()).append("  --  ").append(columnComment).append(" \n");
            } else {
                sb.append(columns.get(i).getFieldName()).append(" \n");

            }
        }
        if (StringUtil.isNotBlank(tableRemarks)) {
            sb.append(" FROM ").append(schemaName).append(".").append(tableName).append(";").append(" -- ")
                    .append(tableRemarks).append("\n");
        } else {
            sb.append(" FROM ").append(schemaName).append(".").append(tableName).append(";\n");
        }
        return sb.toString();
    }

    /**
     * 生成“按指定字段分组出现次数大于 1”的重复数据检查 SQL
     */
    @Override
    public String getCountMoreThanOneSql(String schemaName, String tableName, List<String> columns) {
        return database.getCountMoreThanOneSql(schemaName, tableName, columns);
    }

    /**
     * 生成“按指定字段分组仅出现 1 次”的查询 SQL
     */
    @Override
    public String getCountOneSql(String schemaName, String tableName, List<String> columns) {
        return database.getCountOneSql(schemaName, tableName, columns);
    }

    /**
     * 检查某字段值在表中是否重复出现（COUNT > 1 返回 true）
     */
    @Override
    public Boolean checkRepeat(String jdbcUrl, String userName, String password, String databaseSchema,
            String sourceTableName, String name, Object value) {
        try (Connection connection = workloadProvider.open(jdbcUrl, userName, password)) {
            return database.countBySql(connection,
                    String.format("SELECT COUNT(1) AS c FROM %s WHERE %s=?", sourceTableName, name), value) > 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 删除指定表（schema 与表名按方言加引号）
     */
    @Override
    public void dropTable(String jdbcUrl, String userName, String password, String schema, String table) {
        try (Connection connection = workloadProvider.open(jdbcUrl, userName, password)) {
            database.executeSql(connection,
                    String.format("DROP TABLE %s.%s ", dbType.quoteName(schema), dbType.quoteName(table)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
