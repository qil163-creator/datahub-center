package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.hutool.core.date.DateUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.common.security.TablePermissionService;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlInjectionChecker;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.DbswitchStrUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.HivePrepareUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.StringUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypeConvertUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.DataSecurityParam;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcIndexMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.model.PageSqlModel;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库元信息抽象基类
 * <p>
 * 职责：作为各类数据库（MySQL、Oracle、PostgreSQL、SQLServer、Hive 等）元数据访问实现类的公共父类，
 * 基于 JDBC 的 DatabaseMetaData 与原生 SQL 提供通用能力：
 * <ul>
 * <li>元数据查询：schema 列表、表/视图列表、字段元数据、主键、索引、字段默认值与注释等；</li>
 * <li>SQL 执行：多语句脚本执行 {@link #executeBySql}、
 * 通用分页查询 {@link #pageDataBySql}、表数据预览 {@link #queryTableData} 等；</li>
 * <li>DDL 辅助：建表语句的字段定义、主键、索引拼接，目标表自动补列 {@link #addNoExistColumnsByTarget} 等。</li>
 * </ul>
 * 协作关系：由显式产品注册表按 {@link ProductTypeEnum} 数据库类型创建对应子类实例，
 * 供 MetaDataByJdbcServiceImpl 等元数据服务门面调用；数据库方言差异（字段查询 SQL、分页写法、引号风格等）由子类实现。
 *
 * @author jrl
 */
@Slf4j
public abstract class AbstractDatabase implements IDatabaseInterface {

    /** Product-specific structured options on a component-generated CREATE, before execution. */
    public String applyCreateTableOptions(String generatedSql, String schema, String table,
            Map<String, String> properties) {
        if (properties != null && properties.containsKey(
                cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY)) {
            throw new UnsupportedOperationException("Doris create options require a Doris dialect");
        }
        return generatedSql;
    }

    /** 读取 CLOB 大字段时的最大长度上限 */
    public static final int CLOB_LENGTH = 9999999;

    /** 缺省 JDBC 驱动类全限定名；不是当前 Connection 的驱动身份凭证。 */
    protected String driverClassName;

    /** JDBC catalog 名称，多数数据库为 null，MySQL 系子类会赋值 */
    protected String catalogName = null;

    /**
     * 元数据操作接受调用方已有的 Connection，不要求其隔离驱动也出现在应用 classpath。
     * 若缺省驱动可见，保留历史注册副作用（兼容旧 DriverManager 调用方）；缺失时由实际建连入口报错。
     * 仅允许缺类，不吞掉驱动初始化/链接错误；构造成功不表示该产品已可连接或已适配。
     *
     * @param driverClassName JDBC 驱动类全限定名
     */
    public AbstractDatabase(String driverClassName) {
        try {
            this.driverClassName = driverClassName;
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            log.debug(
                    "Default JDBC driver is not on application classpath; metadata requires a caller-owned connection");
        }
    }

    /**
     * 获取数据库的JDBC驱动类。
     */
    @Override
    public String getDriverClassName() {
        return this.driverClassName;
    }

    /** 生成 DDL 是否需要实际目标连接提供的兼容模式验证依据。 */
    public boolean requiresDialectConnection() {
        return false;
    }

    /**
     * 生成 SQL 前先准备与连接相关的方言验证依据。调用方仍持有
     * 连接及其事务。固定方言的产品无需额外准备。
     */
    public void prepareDialect(Connection connection) throws SQLException {
    }

    /** 连接本地的 JDBC catalog/schema 映射；子类不得修改共享的 catalogName。 */
    protected ResultSet openColumnMetadata(Connection connection, String schemaName,
            String tableName) throws SQLException {
        return connection.getMetaData().getColumns(this.catalogName, schemaName, tableName, null);
    }

    /**
     * 根据所选关系及读取模式规划实际读取的物理表。
     */
    public cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan planTableReads(Connection connection,
            java.util.Collection<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> selected,
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode mode) throws SQLException {
        if (mode != cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode.INCLUDE_DESCENDANTS) {
            throw new UnsupportedOperationException("ONLY relation reads are not verified for this product");
        }
        var reads = new java.util.LinkedHashSet<>(selected).stream().map(relation ->
                        new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read(relation,
                                getQuotedSchemaTableCombination(relation.schema(), relation.table()), List.of(relation)))
                .toList();
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan(mode, reads, Map.of());
    }

    /**
     * 查询数据库中所有 schema（模式）的名称列表
     * <p>
     * 通过 JDBC 元数据接口 getSchemas() 获取，使用 Set 去重后返回
     *
     * @param connection 数据库连接
     * @return schema 名称列表
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        Set<String> ret = new HashSet<>();
        try (ResultSet schemas = connection.getMetaData().getSchemas()) {
            while (schemas.next()) {
                ret.add(schemas.getString("TABLE_SCHEM"));
            }
            return new ArrayList<>(ret);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 查询指定 schema 下的所有表与视图
     * <p>
     * 通过 JDBC 元数据接口 getTables() 获取（类型限定为 TABLE 与 VIEW），
     * 按表名去重后封装为 {@link TableDescription}（含表名、注释、表类型）
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @return 表描述信息列表
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> ret = new ArrayList<>();
        Set<String> uniqueSet = new HashSet<>();
        String[] types = new String[]{"TABLE", "VIEW"};
        try (ResultSet tables = connection.getMetaData()
                .getTables(this.catalogName, schemaName, "%", types)) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (uniqueSet.contains(tableName)) {
                    continue;
                } else {
                    uniqueSet.add(tableName);
                }

                TableDescription td = new TableDescription();
                td.setSchemaName(schemaName);
                td.setTableName(tableName);
                td.setRemarks(tables.getString("REMARKS"));
                td.setTableType(tables.getString("TABLE_TYPE").toUpperCase());
                ret.add(td);
            }
            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 未点名同步使用的表。默认不删减；PostgreSQL 去掉声明式分区父表和子表。
     * 显式表名不能调用这里。
     */
    public List<TableDescription> defaultSyncTargets(List<TableDescription> tables) {
        if (tables == null) {
            throw new IllegalArgumentException("Table list is required");
        }
        return List.copyOf(tables);
    }

    /**
     * 查询单个表的元信息：在 {@link #queryTableList} 结果中按表名过滤
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 表描述信息，表不存在时返回 null
     */
    @Override
    public TableDescription queryTableMeta(Connection connection, String schemaName,
            String tableName) {
        return queryTableList(connection, schemaName).stream()
                .filter(one -> tableName.equals(one.getTableName()))
                .findAny().orElse(null);
    }

    /**
     * 查询指定表的全部字段名（仅名称，不含类型等详细信息）
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 按 JDBC 观察顺序去重的字段名列表；不重排或推断驱动未提供的物理序号
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName,
            String tableName) {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet rs = openColumnMetadata(connection, schemaName, tableName)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new ArrayList<>(columns);
    }

    /**
     * 为字段描述列表补充默认值与注释信息
     * <p>
     * 执行子类提供的默认值查询 SQL（{@link #getDefaultValueSql}），按字段名匹配后
     * 回填 defaultValue 与 remarks；子类返回 null 表示不支持该查询，直接跳过；
     * 查询出错仅记录日志，不中断整体流程
     *
     * @param connection         数据库连接
     * @param schemaName         schema 名称
     * @param tableName          表名
     * @param columnDescriptions 待补充的字段描述列表（原地修改）
     */
    @Override
    public void setColumnDefaultValue(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {

        String sql = this.getDefaultValueSql(schemaName, tableName);
        if (sql == null) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String columnName = rs.getString("column_name");
                    String columnDefault = rs.getString("column_default");
                    String columnComment = rs.getString("column_comment");
                    if (columnName != null) {
                        for (ColumnDescription cd : columnDescriptions) {
                            if (columnName.equals(cd.getFieldName())) {
                                cd.setDefaultValue(columnDefault);
                                cd.setRemarks(columnComment);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error(schemaName + "." + tableName + " setColumnDefaultValue error:" + e.getMessage());
            // throw new RuntimeException(e);
        }
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关），由子类实现
     * <p>
     * 结果集需包含 column_name、column_default、column_comment 三列；返回 null 表示该数据库不支持
     *
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 查询 SQL，或 null
     */
    protected abstract String getDefaultValueSql(String schemaName, String tableName);

    /**
     * 为字段描述列表补充索引信息
     * <p>
     * 通过 JDBC 元数据接口 getIndexInfo() 获取表上全部索引，交给 {@link #setIndex} 按字段名回填；
     * 查询出错仅记录日志，不中断整体流程
     *
     * @param connection         数据库连接
     * @param schemaName         schema 名称
     * @param tableName          表名
     * @param columnDescriptions 待补充的字段描述列表（原地修改）
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
        // 补充一下索引信息
        try (ResultSet indexInfo = connection.getMetaData().getIndexInfo(this.catalogName, schemaName, tableName, false,
                true)) {
            setIndex(columnDescriptions, indexInfo);
        } catch (SQLException e) {
            log.error(schemaName + "." + tableName + " setColumnIndexInfo error:" + e.getMessage());
            // throw new RuntimeException(schemaName + "." + tableName + "
            // setColumnIndexInfo error!!", e);
        }
    }

    /**
     * 读取表的索引定义。
     */
    @Override
    public List<IndexDescription> queryTableIndexes(Connection connection, String schemaName, String tableName) {
        try (ResultSet rows = openIndexMetadata(connection, schemaName, tableName)) {
            return JdbcIndexMetadata.collect(rows);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 用于索引元数据的连接本地 JDBC catalog/schema 映射，与 {@link #openColumnMetadata} 一致。 */
    protected ResultSet openIndexMetadata(Connection connection, String schemaName,
            String tableName) throws SQLException {
        return connection.getMetaData().getIndexInfo(this.catalogName, schemaName, tableName, false, true);
    }

    /** CREATE INDEX 是否需要在索引名前加 schema：Oracle 系不加时索引落在当前用户的 schema。 */
    public boolean qualifiesIndexName() {
        return false;
    }

    /**
     * 设置索引信息：遍历索引结果集，把索引名、是否唯一、索引类型、排序方向等属性
     * 按字段名回填到对应的字段描述对象中（同一字段可属于多个索引，索引名会累加到 indexes 列表）
     *
     * @param columnDescriptions 字段描述列表（原地修改）
     * @param indexInfo          JDBC getIndexInfo() 返回的索引结果集
     * @throws SQLException 读取结果集失败时抛出
     */
    public void setIndex(List<ColumnDescription> columnDescriptions, ResultSet indexInfo) throws SQLException {
        while (indexInfo.next()) {
            // 索引值是否可以不唯一
            boolean nonUnique = indexInfo.getBoolean("NON_UNIQUE");
            // 索引类别
            String indexQualifier = indexInfo.getString("INDEX_QUALIFIER");
            String indexName = indexInfo.getString("INDEX_NAME");
            /**
             * 索引类型：
             * tableIndexStatistic - 此标识与表的索引描述一起返回的表统计信息
             * tableIndexClustered - 此为集群索引
             * tableIndexHashed - 此为散列索引
             * tableIndexOther - 此为某种其他样式的索引
             */
            short type = indexInfo.getShort("TYPE");
            String columnName = indexInfo.getString("COLUMN_NAME");
            String ascOrDesc = indexInfo.getString("ASC_OR_DESC");
            if (columnName != null) {
                for (ColumnDescription cd : columnDescriptions) {
                    if (columnName.equals(cd.getFieldName())) {
                        cd.setNonIndexUnique(nonUnique);
                        cd.setIndexQualifier(indexQualifier);
                        cd.setIndexName(indexName);
                        cd.getIndexes().add(indexName);
                        cd.setIndexType(type);
                        cd.setAscOrDesc(ascOrDesc);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 查询表的字段元数据（并补充字段注释）
     * <p>
     * 主要流程：
     * 1. 用子类提供的字段查询 SQL（形如 SELECT * FROM t WHERE 1=2，只取元数据不取数据）
     *    经 querySelectSqlColumnMeta 解析出字段名、类型、精度等元数据；
     * 2. 再通过 JDBC 元数据接口 getColumns() 补充字段注释（REMARKS），失败只记日志不抛错
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 字段描述列表
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        String sql = this.getTableFieldsQuerySQL(schemaName, tableName);
        List<ColumnDescription> ret = this.querySelectSqlColumnMeta(connection, sql);
        // 补充一下注释信息，索引信息
        try (ResultSet columns = openColumnMetadata(connection, schemaName, tableName)) {
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String remarks = columns.getString("REMARKS");
                for (ColumnDescription cd : ret) {
                    if (columnName.equals(cd.getFieldName())) {
                        cd.setRemarks(remarks);
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            log.error(schemaName + "." + tableName + " queryTableColumnMeta error:" + e.getMessage());
            // throw new RuntimeException(schemaName + "." + tableName + "
            // queryTableColumnMeta error!!", e);
        }
        return ret;
    }

    /**
     * 仅查询表的字段元数据，不补充注释/索引等附加信息，比 {@link #queryTableColumnMeta} 更轻量
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 字段描述列表
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName) {
        String sql = this.getTableFieldsQuerySQL(schemaName, tableName);
        return this.querySelectSqlColumnMeta(connection, sql);
    }

    /**
     * 查询表的主键字段名（通过 JDBC 元数据接口 getPrimaryKeys() 获取）
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 按 JDBC KEY_SEQ 排序的主键字段名列表
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
            String tableName) {
        java.util.SortedMap<Integer, String> ordered = new java.util.TreeMap<>();
        Set<String> names = new HashSet<>();
        try (ResultSet primaryKeys = connection.getMetaData()
                .getPrimaryKeys(this.catalogName, schemaName, tableName)) {
            while (primaryKeys.next()) {
                String name = primaryKeys.getString("COLUMN_NAME");
                int sequence = primaryKeys.getInt("KEY_SEQ");
                if (primaryKeys.wasNull() || sequence <= 0 || name == null || name.isEmpty()
                        || !names.add(name) || ordered.putIfAbsent(sequence, name) != null) {
                    throw new SQLException("Invalid JDBC primary key metadata");
                }
            }
            // JDBC 行不一定按键顺序返回；缺少序号时不得静默改变复合键。
            int expected = 1;
            for (int sequence : ordered.keySet()) {
                if (sequence != expected++) {
                    throw new SQLException("Incomplete JDBC primary key metadata");
                }
            }
            return new ArrayList<>(ordered.values());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 预览表数据：SELECT * 查询指定表的前 rowCount 行
     * <p>
     * 主要流程：
     * 1. 拼接带引号的全限定表名并生成查询 SQL；
     * 2. setMaxRows 限制返回行数；MySQL 系用流式读取（fetchSize=Integer.MIN_VALUE）防止大表撑爆内存，
     *    其余数据库 fetchSize=100；
     * 3. Hive 需先执行会话准备语句；
     * 4. 交由 getSchemaTableData 读取列名与行数据
     *
     * @param connection 数据库连接
     * @param schemaName schema 名称
     * @param tableName  表名
     * @param rowCount   最多返回的行数
     * @return 含列名与行数据的结果对象
     */
    @Override
    public SchemaTableData queryTableData(Connection connection, String schemaName, String tableName,
            int rowCount) {
        String fullTableName = getQuotedSchemaTableCombination(schemaName, tableName);
        String querySQL = String.format("SELECT * FROM %s ", fullTableName);
        SchemaTableData data = new SchemaTableData();
        data.setSchemaName(schemaName);
        data.setTableName(tableName);
        data.setColumns(new ArrayList<>());
        data.setRows(new ArrayList<>());
        try (Statement st = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // 限制下最大数量
            st.setMaxRows(rowCount);
            if (getDatabaseType().isLikeMysql()) {
                st.setFetchSize(Integer.MIN_VALUE);
            } else {
                st.setFetchSize(100);
            }
            if (getDatabaseType() == ProductTypeEnum.HIVE) {
                HivePrepareUtils.prepare(connection, schemaName, tableName);
            }
            return getSchemaTableData(querySQL, rowCount, data, st);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 按自定义 SQL 预览数据（最多返回 rowCount 行），行数限制与流式读取逻辑同 {@link #queryTableData}
     *
     * @param connection 数据库连接
     * @param sql        查询 SQL
     * @param rowCount   最多返回的行数
     * @return 含列名与行数据的结果对象
     */
    @Override
    public SchemaTableData queryTableDataBySql(Connection connection, String sql, int rowCount) {
        SchemaTableData data = new SchemaTableData();
        data.setColumns(new ArrayList<>());
        data.setRows(new ArrayList<>());
        try (Statement st = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // 限制下最大数量
            st.setMaxRows(rowCount);
            if (getDatabaseType().isLikeMysql()) {
                st.setFetchSize(Integer.MIN_VALUE);
            } else {
                st.setFetchSize(100);
            }
            return getSchemaTableData(sql, rowCount, data, st);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 执行查询并把结果装入 SchemaTableData：先从结果集元数据取出全部列名，
     * 再逐行读取数据（最多 rowCount 行），单元格值经 getFiledVal 统一转为便于展示的类型
     */
    private SchemaTableData getSchemaTableData(String sql, int rowCount, SchemaTableData data, Statement st)
            throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            int count = m.getColumnCount();
            for (int i = 1; i <= count; i++) {
                data.getColumns().add(m.getColumnLabel(i));
            }

            int counter = 0;
            while (rs.next() && counter++ < rowCount) {
                List<Object> row = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    row.add(getFiledVal(rs.getObject(i)));
                }
                data.getRows().add(row);
            }
            data.setSql(sql);
            return data;
        }
    }

    /**
     * 校验查询 SQL 是否合法可执行：把 SQL 包装为方言相关的低成本探测语句（见 getTestQuerySQL）后执行，
     * 执行失败时抛出运行时异常
     *
     * @param connection 数据库连接
     * @param sql        待校验的查询 SQL
     */
    @Override
    public void testQuerySQL(Connection connection, String sql) {
        String wrapperSql = this.getTestQuerySQL(sql);
        try (Statement statement = connection.createStatement();) {
            statement.execute(wrapperSql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 返回带引号的 "schema"."table" 全限定表名（默认双引号风格，MySQL 等子类会覆盖为反引号）
     *
     * @param schemaName schema 名称
     * @param tableName  表名
     * @return 加引号后的全限定表名
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return String.format(" \"%s\".\"%s\" ", schemaName, tableName);
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。
     * 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    public String quoteIdentifier(String name) {
        if (name == null) {
            return "";
        }
        String quote = getDatabaseType().getQuote();
        if (quote == null || quote.isEmpty()) {
            quote = "`";
        }
        return quote + name.replace(quote, quote + quote) + quote;
    }

    /**
     * 将表达式转为可做字符串函数的文本类型
     */
    public String castToText(String expr) {
        ProductTypeEnum type = getDatabaseType();
        if (type.isLikeMysql()) {
            return "CAST(" + expr + " AS CHAR)";
        }
        if (type.isLikePostgres()) {
            return "CAST(" + expr + " AS TEXT)";
        }
        if (type.isLikeSqlServer()) {
            return "CAST(" + expr + " AS NVARCHAR(4000))";
        }
        if (type.isLikeOracle()) {
            return "TO_CHAR(" + expr + ")";
        }
        if (type == ProductTypeEnum.HIVE || type == ProductTypeEnum.CLICKHOUSE) {
            return "CAST(" + expr + " AS STRING)";
        }
        return "CAST(" + expr + " AS VARCHAR(4000))";
    }

    /**
     * 字符长度表达式
     */
    public String charLengthExpr(String expr) {
        ProductTypeEnum type = getDatabaseType();
        if (type.isLikeMysql()) {
            return "CHAR_LENGTH(" + expr + ")";
        }
        if (type.isLikeSqlServer()) {
            return "LEN(" + expr + ")";
        }
        return "LENGTH(" + castToText(expr) + ")";
    }

    /**
     * 绑定 {@link #getPageModel} 生成的两个分页占位符。
     *
     * @param paramOffset 已绑定业务参数个数，下一个占位符从 {@code paramOffset + 1} 开始
     */
    public void bindPageParameters(PreparedStatement stmt, int paramOffset, int page, int limit)
            throws SQLException {
        ProductTypeEnum type = getDatabaseType();
        if (type == ProductTypeEnum.ORACLE) {
            stmt.setObject(paramOffset + 1, page * limit);
            stmt.setObject(paramOffset + 2, (page - 1) * limit);
        } else if (type == ProductTypeEnum.SQLSERVER || type == ProductTypeEnum.SQLSERVER2000) {
            stmt.setObject(paramOffset + 1, (page - 1) * limit);
            stmt.setObject(paramOffset + 2, limit);
        } else {
            stmt.setObject(paramOffset + 1, limit);
            stmt.setObject(paramOffset + 2, (page - 1) * limit);
        }
    }

    /**
     * 生成建表语句中单个字段的定义片段（字段名、类型、长度、是否可空、默认值等），由子类按方言实现
     *
     * @param v           字段元数据
     * @param pks         主键字段名列表（用于判断该字段是否主键，主键通常强制 NOT NULL）
     * @param useAutoInc  是否使用自增
     * @param addCr       是否在结尾追加换行符
     * @param withRemarks 是否在定义中携带字段注释（部分数据库支持内联 COMMENT）
     * @return 字段定义 SQL 片段
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        throw new RuntimeException("AbstractDatabase Unimplemented!");
    }

    /**
     * 把主键字段名列表拼接为建表语句中的主键字段串，形如 "id" , "name"（双引号包裹）
     *
     * @param pks 主键字段名列表
     * @return 拼接后的字符串；无主键时返回空串
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        if (!pks.isEmpty()) {
            return "\"" +
                    StringUtils.join(pks, "\" , \"") +
                    "\"";
        }

        return "";
    }

    /**
     * 生成表注释与字段注释的 DDL 语句列表（如 COMMENT ON TABLE/COLUMN ...），
     * 由建表语句不支持内联注释的数据库子类实现
     *
     * @param td  表描述信息
     * @param cds 字段描述列表
     * @return 注释 DDL 语句列表
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        throw new RuntimeException("AbstractDatabase Unimplemented!");
    }

    /**************************************
     * 内部方法
     **************************************/

    /**
     * 获取查询表全部字段用的 SQL（通常形如 SELECT * FROM t WHERE 1=2，仅为获取元数据、不取数据），由子类按方言实现
     */
    protected abstract String getTableFieldsQuerySQL(String schemaName, String tableName);

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现
     */
    protected abstract String getTestQuerySQL(String sql);

    /**
     * 执行查询 SQL 并从结果集元数据（ResultSetMetaData）解析字段元数据，
     * 是各子类 querySelectSqlColumnMeta 的通用实现
     * <p>
     * 主要流程：
     * 1. Hive 需先设置会话参数，使结果集列名不带表名前缀；
     * 2. 执行 SQL 后逐列读取：列名/标签、JDBC 类型、类型名、Java 类名、显示宽度、精度、小数位、是否自增、是否可空；
     * 3. 字段类型为 0（如视图中 NULL as xx 的场景）时按 CHAR(1) 兜底处理；
     * 4. 部分驱动不支持 isSigned 方法，捕获异常并默认视为无符号
     *
     * @param connection 数据库连接
     * @param querySQL   查询 SQL（通常为 WHERE 1=2 的空结果查询）
     * @return 字段描述列表
     */
    protected List<ColumnDescription> getSelectSqlColumnMeta(Connection connection, String querySQL) {
        return getSelectSqlColumnMeta(connection, querySQL, 0);
    }

    /** 供精确元数据调用方使用的可选超时；零表示保留现有产品的驱动默认值。 */
    protected List<ColumnDescription> getSelectSqlColumnMeta(Connection connection, String querySQL,
            int timeoutSeconds) {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("Metadata timeout must not be negative");
        }
        List<ColumnDescription> ret = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (timeoutSeconds > 0) {
                st.setQueryTimeout(timeoutSeconds);
            }
            if (getDatabaseType() == ProductTypeEnum.HIVE) {
                HivePrepareUtils.setResultSetColumnNameNotUnique(connection);
            }

            try (ResultSet rs = st.executeQuery(querySQL)) {
                ResultSetMetaData m = rs.getMetaData();
                int columns = m.getColumnCount();
                for (int i = 1; i <= columns; i++) {
                    String name = m.getColumnLabel(i);
                    if (null == name) {
                        name = m.getColumnName(i);
                    }
                    ColumnDescription cd = new ColumnDescription();
                    cd.setFieldName(name);
                    cd.setLabelName(name);
                    cd.setFieldType(m.getColumnType(i));
                    if (0 != cd.getFieldType()) {
                        cd.setFieldTypeName(m.getColumnTypeName(i));
                        cd.setFiledTypeClassName(m.getColumnClassName(i));
                        cd.setDisplaySize(m.getColumnDisplaySize(i));
                        cd.setPrecisionSize(m.getPrecision(i));
                        cd.setScaleSize(m.getScale(i));
                        cd.setAutoIncrement(m.isAutoIncrement(i));
                        cd.setNullable(m.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    } else {
                        // 处理视图中NULL as fieldName的情况
                        cd.setFieldTypeName("CHAR");
                        cd.setFiledTypeClassName(String.class.getName());
                        cd.setDisplaySize(1);
                        cd.setPrecisionSize(1);
                        cd.setScaleSize(0);
                        cd.setAutoIncrement(false);
                        cd.setNullable(true);
                    }

                    boolean signed = false;
                    try {
                        signed = m.isSigned(i);
                    } catch (Exception ignored) {
                        // 此 JDBC 驱动不支持 isSigned 方法
                        // 捕获该异常后无法继续处理。
                    }
                    cd.setSigned(signed);
                    cd.setDbType(getDatabaseType());

                    ret.add(cd);
                }

                return ret;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 判断该字段类型能否用于创建索引（基类默认返回 false，由支持索引迁移的子类按字段类型覆盖）
     *
     * @param v 字段元数据
     * @return true 表示可以为该字段创建索引
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        return false;
    }

    /**
     * 目标表自动补列：对比目标表现有字段，为缺失字段执行 ALTER TABLE ... ADD COLUMN
     * <p>
     * 主要流程：
     * 1. 把调用方传入的已有字段名规范化（去引号、转小写）后放入集合，便于忽略大小写/引号差异地比较；
     * 2. 若调用方未传，退化为通过 JDBC 元数据接口查询目标表现有字段；
     * 3. 遍历期望字段列表，跳过已存在的字段，其余逐个执行加列 SQL；
     * 4. 加列时若报“字段已存在/重复列”类错误（并发建列等场景）则忽略并继续，其他异常向上抛出
     *
     * @param connection               目标库连接
     * @param targetSchemaName         目标 schema 名称
     * @param targetTableName          目标表名
     * @param allColumns               目标表当前已有字段名（可为空，为空时自动查询元数据）
     * @param targetColumnDescriptions 期望目标表拥有的字段描述列表
     */
    @Override
    public void addNoExistColumnsByTarget(Connection connection, String targetSchemaName, String targetTableName,
            List<String> allColumns, List<ColumnDescription> targetColumnDescriptions) {
        try (Statement statement = connection.createStatement()) {
            // 1. 收集目标表已有字段名（统一规范化后比较）
            Set<String> normalizedExistingColumns = new HashSet<>();
            if (allColumns != null) {
                for (String col : allColumns) {
                    String normalized = normalizeColumnName(col);
                    if (StringUtils.isNotBlank(normalized)) {
                        normalizedExistingColumns.add(normalized);
                    }
                }
            }
            // 2. 调用方没有提供已有字段时，从 JDBC 元数据中查询
            if (normalizedExistingColumns.isEmpty()) {
                try (ResultSet columns = openColumnMetadata(connection, targetSchemaName, targetTableName)) {
                    while (columns.next()) {
                        String columnName = columns.getString("COLUMN_NAME");
                        String normalized = normalizeColumnName(columnName);
                        if (StringUtils.isNotBlank(normalized)) {
                            normalizedExistingColumns.add(normalized);
                        }
                    }
                } catch (SQLException ignored) {
                }
            }
            // 3. 遍历期望字段，为目标表中不存在的字段逐个加列
            for (ColumnDescription targetColumn : targetColumnDescriptions) {
                String fieldName = targetColumn.getFieldName();
                if (StringUtils.isEmpty(fieldName)) {
                    continue;
                }
                String normalizedFieldName = normalizeColumnName(fieldName);
                if (StringUtils.isEmpty(normalizedFieldName) || normalizedExistingColumns.contains(
                        normalizedFieldName)) {
                    continue;
                }
                try {
                    // 添加字段
                    statement.execute(getAddColumnSql(targetSchemaName, targetTableName, targetColumn));
                    normalizedExistingColumns.add(normalizedFieldName);
                } catch (SQLException e) {
                    // 4. “字段已存在”类错误（并发加列等场景）忽略并继续，其余异常抛出
                    if (isDuplicateColumnException(e)) {
                        normalizedExistingColumns.add(normalizedFieldName);
                        continue;
                    }
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 规范化字段名用于比较：去掉首尾空白与包裹的引号（反引号/双引号/方括号）并统一转小写
     */
    private static String normalizeColumnName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '[' && last == ']')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed.toLowerCase();
    }

    /**
     * 判断 SQLException 是否属于“字段已存在/重复列”类错误：
     * MySQL 错误码 1060、SQLState 42S21，或错误消息包含 duplicate column / already exists
     */
    private static boolean isDuplicateColumnException(SQLException e) {
        if (e == null) {
            return false;
        }
        int errorCode = e.getErrorCode();
        String sqlState = e.getSQLState();
        if (errorCode == 1060) {
            return true;
        }
        if ("42S21".equalsIgnoreCase(sqlState)) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String msg = message.toLowerCase();
        return msg.contains("duplicate column") || msg.contains("already exists");
    }

    /**
     * 未覆盖此方法的方言不能把库名和表名原样拼进 ALTER。各产品必须使用自己的引用规则。
     */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        throw new SQLFeatureNotSupportedException(getDatabaseType() + " nullable add is unverified", "0A000");
    }

    /** 原生表模型的建表规划扩展；默认拒绝，调用方不得退回其他产品的方言。 */
    public cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization.Plan planCreateTableSynchronization(
            String schema, String table, String comment, List<ColumnDescription> columns,
            Map<String, String> properties) {
        throw new UnsupportedOperationException("Native table creation planning is unavailable");
    }

    /** 原生表模型的结构同步扩展；只借用连接读取目录，不执行或改变事务。 */
    public cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization.Plan planTableSynchronization(
            Connection connection, String schema, String table, String comment, List<ColumnDescription> columns,
            int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Native table synchronization planning is unavailable", "0A000");
    }

    /** 受控建模脚本的原生语法门禁；不扩大普通查询、DML 或任意脚本入口。 */
    public cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan planModelingSchemaScript(
            String schema, String table, List<String> statements) {
        throw new UnsupportedOperationException("Native modeling schema script is unavailable");
    }

    /** 有界等待原生异步结构变更；默认产品没有相应协议，必须显式实现。 */
    public void awaitSchemaChange(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("Native asynchronous schema confirmation is unavailable", "0A000");
    }

    /** 建模读取原生列事实；产品可以覆盖，避免兼容协议的目录丢失键模型等语义。 */
    public cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata readModelingSchema(
            Connection connection, String schema, String table, int timeoutSeconds) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(
                connection, getDatabaseType(), schema, table, timeoutSeconds);
    }

    /** 核验冻结建表定义中的键、布局和属性；不能从字段同步无差异推断建表已完成。 */
    public boolean matchesModelingCreate(Connection connection, String schema, String table, String createSql,
            int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Native create result comparison is unavailable", "0A000");
    }

    /**
     * 执行单条无参 SQL（Statement 直接执行），失败时抛出运行时异常
     */
    public void executeSql(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 执行单条带占位符参数的 SQL（PreparedStatement 按顺序绑定参数后执行），失败时抛出运行时异常
     *
     * @param connection 数据库连接
     * @param sql        含 ? 占位符的 SQL
     * @param params     按顺序绑定的参数值列表
     */
    public void executeSql(Connection connection, String sql, List<Object> params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 在调用方拥有的连接和事务中顺序执行已验证的脚本，失败即停止。
     * 本层不切换自动提交、不提交、不回滚、不关闭连接；结果成功不等于事务已提交。
     * 仅接受 SqlScriptPlan 已验证的产品与语法，解析失败不会降级执行原文。
     */
    public JdbcSelectResult executeBySql(Connection connection, String dbType, String sql,
            int rowCount, DataSecurityParam dataSecurityParam) {
        return executePlan(connection, SqlScriptPlan.parse(getDatabaseType(), sql), List.of(), rowCount,
                dataSecurityParam, ignored -> {
                });
    }

    /** 执行调用方已经授权的同一计划；不重新解析、改写 SQL 或接管连接事务。 */
    public JdbcSelectResult executePlan(Connection connection, SqlScriptPlan plan, List<Object> bindings,
            int rowCount, DataSecurityParam dataSecurityParam,
            java.util.function.IntConsumer beforeStep) {
        if (plan.product() != getDatabaseType()) {
            throw new IllegalArgumentException("SQL plan product does not match the executor");
        }
        if (plan.steps().stream().mapToInt(SqlScriptPlan.Step::parameterCount).sum() != bindings.size()) {
            throw new IllegalArgumentException("SQL plan parameter count does not match bindings");
        }
        if (rowCount < 1 || rowCount > 100_000) {
            throw new IllegalArgumentException("SQL result limit must be between 1 and 100000");
        }
        log.debug("Executing JDBC script product={}, statements={}", getDatabaseType(), plan.steps().size());
        JdbcSelectResult jobResult = new JdbcSelectResult();
        List<JdbcSelectResult> results = new ArrayList<>();
        jobResult.setSuccess(true);
        jobResult.setResults(results);

        // 2. 逐条执行解析出的语句
        int parameterOffset = 0;
        for (SqlScriptPlan.Step step : plan.steps()) {
            beforeStep.accept(step.ordinal());
            String item = step.sql();
            // 将查询数据存储到数据中
            List<Map<String, Object>> dataList = new ArrayList<>();
            // 存储列名的数组
            List<String> columnList = new LinkedList<>();
            // 新增、修改、删除受影响行数
            Integer updateCount = null;
            JdbcSelectResult result = new JdbcSelectResult();
            result.setSuccess(true);
            Statement stmt = null;
            ResultSet rs = null;
            long sqlStart = System.currentTimeMillis();
            try {
                // stmt = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                // ResultSet.CONCUR_READ_ONLY);
                if (step.parameterCount() == 0) {
                    stmt = connection.createStatement();
                } else {
                    PreparedStatement prepared = connection.prepareStatement(item);
                    stmt = prepared;
                    for (int parameter = 0; parameter < step.parameterCount(); parameter++) {
                        prepared.setObject(parameter + 1, bindings.get(parameterOffset + parameter));
                    }
                }
                parameterOffset += step.parameterCount();
                stmt.setQueryTimeout(30);
                // 限制查询返回行数（SQLServer 系列不设置 maxRows）
                if (!(getDatabaseType().equals(ProductTypeEnum.SQLSERVER)
                        || getDatabaseType().equals(ProductTypeEnum.SQLSERVER2000))) {
                    stmt.setMaxRows(rowCount);
                }
                // MySQL 系启用流式读取，避免大结果集占满内存
                if (getDatabaseType().isLikeMysql()) {
                    stmt.setFetchSize(Integer.MIN_VALUE);
                }
                // 数据权限校验：无权限时禁止执行非查询语句
                if (dataSecurityParam != null && !CollectionUtils.isEmpty(dataSecurityParam.getRoleIds())) {
                    TablePermissionService tablePermissionService = dataSecurityParam.getTablePermissionService();
                    tablePermissionService.validateNonSelectStatement(item, dataSecurityParam.getRoleIds(),
                            dataSecurityParam.getProjectId());
                }
                // 是否查询操作
                boolean execute = stmt instanceof PreparedStatement prepared ? prepared.execute() : stmt.execute(item);
                if (execute) {
                    result.setIfQuery(true);
                    int count = 0;
                    rs = stmt.getResultSet();
                    // 获取结果集的元数据信息
                    ResultSetMetaData rsmd = rs.getMetaData();
                    // 获取列字段的个数
                    int colunmCount = rsmd.getColumnCount();
                    for (int i = 1; i <= colunmCount; i++) {
                        // 获取所有的字段名称
                        columnList.add(rsmd.getColumnLabel(i));
                    }
                    while (rs.next()) {
                        Map<String, Object> map = new HashMap<>();
                        for (int i = 1; i <= colunmCount; i++) {
                            // 获取列名
                            String columnName = rsmd.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            val = getFiledVal(val);
                            map.put(columnName, val);
                        }
                        dataList.add(map);
                        count++;
                        if (count >= rowCount) {
                            break;
                        }
                    }
                    // 拦截，处理查询sql
                    if (dataSecurityParam != null && !CollectionUtils.isEmpty(dataSecurityParam.getRoleIds())) {
                        TablePermissionService tablePermissionService = dataSecurityParam.getTablePermissionService();
                        dataList = tablePermissionService.executeSecureSql(item,
                                dataSecurityParam.getRoleIds(), dataList, dataSecurityParam.getProjectId());
                    }
                } else {
                    result.setIfQuery(false);
                    // 执行新增、修改、删除受影响行数
                    updateCount = stmt.getUpdateCount();
                }
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMsg("SQL statement execution failed");
                log.debug("JDBC statement failed product={}, ordinal={}, failureType={}",
                        getDatabaseType(), step.ordinal(), e.getClass().getSimpleName());
                jobResult.setSuccess(false);
                jobResult.setErrorMsg(result.getErrorMsg());
            } finally {
                try {
                    if (rs != null) {
                        rs.close();
                    }
                    if (stmt != null) {
                        stmt.close();
                    }
                } catch (SQLException ignored) {
                }
            }
            long sqlEnd = System.currentTimeMillis();
            log.debug("JDBC statement completed ordinal={}, elapsedMs={}, success={}",
                    step.ordinal(), sqlEnd - sqlStart, result.getSuccess());
            result.setSql(item);
            result.setCount(updateCount);
            result.setColumns(columnList);
            result.setRowData(dataList);
            result.setTime(sqlEnd - sqlStart);
            results.add(result);
            // 如果执行失败了，终止执行
            if (!result.getSuccess()) {
                break;
            }
        }
        return jobResult;
    }

    /**
     * 通用分页查询：把任意查询 SQL 包装为分页 SQL 与 count SQL 后执行
     * <p>
     * 主要流程：
     * 1. {@link #getPageModel} 按数据库方言生成分页 SQL（LIMIT/OFFSET、ROWNUM、OFFSET FETCH 等）与 count SQL；
     * 2. 绑定业务参数后，按方言追加两个分页参数（不同数据库顺序与含义不同）；
     * 3. 执行分页 SQL，读取列名（去掉“表名.”前缀）与当前页数据；
     * 4. 有数据权限参数时对结果做行级权限/脱敏处理；
     * 5. 执行 count SQL 得到总记录数，组装分页结果（列名、数据、页码、页大小、总数）
     *
     * @param connection        数据库连接
     * @param sql               原始查询 SQL（不含分页子句）
     * @param params            SQL 中 ? 占位符对应的参数列表
     * @param page              页码（从 1 开始）
     * @param limit             每页条数
     * @param dataSecurityParam 数据权限参数（可为 null 表示不做权限控制）
     * @return 分页结果（含列名、当前页数据、页码、页大小、总记录数）
     */
    @SneakyThrows
    public JdbcSelectResult pageDataBySql(Connection connection, String sql, List<Object> params, Integer page,
            Integer limit, DataSecurityParam dataSecurityParam) {
        // 将查询数据存储到数据中
        List<Map<String, Object>> dataList = new ArrayList<>();
        // 存储列名的数组
        List<String> columnList = new LinkedList<>();
        JdbcSelectResult result = new JdbcSelectResult();
        result.setSuccess(true);
        PreparedStatement pageStmt = null;
        ResultSet pageRs = null;
        PreparedStatement countStmt = null;
        ResultSet countRs = null;
        PageSqlModel pageSqlModel = getPageModel(sql, page, limit);
        try {
            pageStmt = connection.prepareStatement(pageSqlModel.getPageSql());
            for (int i = 1; i <= params.size(); i++) {
                pageStmt.setObject(i, params.get(i - 1));
            }
            if (pageSqlModel.isBindPageParameters()) {
                bindPageParameters(pageStmt, params.size(), page, limit);
            }
            pageRs = pageStmt.executeQuery();
            ResultSetMetaData rsmd = pageRs.getMetaData();
            // 获取列字段的个数
            int colunmCount = rsmd.getColumnCount();
            for (int i = 1; i <= colunmCount; i++) {
                // 获取所有的字段名称
                columnList.add(getColumnLabel(rsmd, i));
            }
            // 分页数据
            while (pageRs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 1; i <= colunmCount; i++) {
                    // 获取列名
                    String columnName = getColumnLabel(rsmd, i);
                    Object val = pageRs.getObject(i);
                    val = getFiledVal(val);
                    map.put(columnName, val);
                }
                dataList.add(map);
            }
            // 拦截
            if (dataSecurityParam != null && !CollectionUtils.isEmpty(dataSecurityParam.getRoleIds())) {
                TablePermissionService tablePermissionService = dataSecurityParam.getTablePermissionService();
                dataList = tablePermissionService.executeSecureSql(pageSqlModel.getPageSql(),
                        dataSecurityParam.getRoleIds(), dataList, dataSecurityParam.getProjectId());
            }
            // 数量
            countStmt = connection.prepareStatement(pageSqlModel.getPageCountSql());
            for (int i = 1; i <= params.size(); i++) {
                countStmt.setObject(i, params.get(i - 1));
            }
            countRs = countStmt.executeQuery();
            countRs.next();
            Long total = countRs.getLong(1);
            JdbcSelectResult jdbcSelectResult = new JdbcSelectResult();
            jdbcSelectResult.setColumns(columnList);
            jdbcSelectResult.setList(dataList);
            jdbcSelectResult.setPage(page);
            jdbcSelectResult.setLimit(limit);
            jdbcSelectResult.setTotal(total);
            return jdbcSelectResult;
        } catch (Exception e) {
            // 保留原始异常作为 cause，调用方据此识别查询超时等驱动语义
            throw new RuntimeException("查询失败：" + e.getMessage(), e);
        } finally {
            if (pageRs != null) {
                pageRs.close();
            }
            if (pageStmt != null) {
                pageStmt.close();
            }
            if (countRs != null) {
                countRs.close();
            }
            if (countStmt != null) {
                countStmt.close();
            }
        }
    }

    /**
     * 获取列展示名：部分数据库返回“表名.列名”形式，这里只保留列名部分
     */
    private String getColumnLabel(ResultSetMetaData rsmd, int i) throws SQLException {
        String columnLabel = rsmd.getColumnLabel(i);
        String[] columnLabelArr = columnLabel.split("\\.");
        return columnLabelArr.length > 1 ? columnLabelArr[1] : columnLabelArr[0];
    }

    /**
     * 把 JDBC 返回的原始值转换为便于展示/序列化的值：
     * byte[] 与 Blob 转十六进制字符串、Clob 转字符串、Date/LocalDateTime 统一格式化为日期时间串，其余类型 toString
     */
    private Object getFiledVal(Object val) {
        if (val instanceof byte[]) {
            val = DbswitchStrUtils.toHexString((byte[]) val);
        } else if (val instanceof java.sql.Clob) {
            val = TypeConvertUtils.castToString(val);
        } else if (val instanceof java.sql.Blob) {
            byte[] bytes = TypeConvertUtils.castToByteArray(val);
            val = DbswitchStrUtils.toHexString(bytes);
        } else if (val instanceof Date) {
            val = DateUtil.formatDateTime((Date) val);
        } else if (val instanceof LocalDateTime) {
            val = DateUtil.formatLocalDateTime((LocalDateTime) val);
        } else {
            val = null == val ? null : val.toString();
        }
        return val;
    }

    /** 分页连接属性的产品扩展点；默认保留调用方的 JDBC URL。 */
    public String preparePageQueryJdbcUrl(String jdbcUrl) {
        return jdbcUrl;
    }

    /** 默认保留原有分页占位符模型；特定产品可校验边界后生成数字分页。 */
    public PageSqlModel getPageModel(String sql, Integer page, Integer limit) {
        return getPageModel(sql);
    }

    /**
     * 按数据库方言生成分页 SQL 与 count SQL 模板（各含两个 ? 分页占位符）：
     * Oracle 用 ROWNUM 双层嵌套子查询、SQLServer 用 OFFSET ... FETCH NEXT、
     * MongoDB 用 skip().limit()，其余默认 LIMIT ? OFFSET ?
     *
     * @param sql 原始查询 SQL
     * @return 分页 SQL 模型（含分页 SQL 与 count SQL）
     */
    public PageSqlModel getPageModel(String sql) {
        PageSqlModel pageSqlModel = new PageSqlModel();
        if (getDatabaseType().equals(ProductTypeEnum.ORACLE)) {
            pageSqlModel.setPageCountSql(String.format("SELECT COUNT(1) FROM (%s) t", sql));
            pageSqlModel.setPageSql(
                    String.format("SELECT * FROM (SELECT T.*,rownum FROM (%s) T WHERE rownum<=?) WHERE rownum>?", sql));
        } else if (getDatabaseType().equals(ProductTypeEnum.SQLSERVER)
                || getDatabaseType().equals(ProductTypeEnum.SQLSERVER2000)) {
            pageSqlModel.setPageCountSql(String.format("SELECT COUNT(1) FROM (%s) t", sql));
            pageSqlModel.setPageSql(String.format("%s offset ? rows fetch next ? rows only", sql));
        } else if (getDatabaseType().isElasticSearch()) {
            pageSqlModel.setPageCountSql(sql);
            pageSqlModel.setPageSql(sql);
        } else if (getDatabaseType().equals(ProductTypeEnum.MONGODB)) {
            pageSqlModel.setPageCountSql(String.format("%s.count()", sql));
            pageSqlModel.setPageSql(String.format("%s.skip(?).limit(?)", sql));
        } else {
            pageSqlModel.setPageCountSql(String.format("SELECT COUNT(1) FROM (%s) t", sql));
            pageSqlModel.setPageSql(String.format("%s LIMIT ? OFFSET ?", sql));
        }
        return pageSqlModel;
    }

    /**
     * 生成“按指定字段分组后出现次数大于 1”的查询 SQL（用于检测按这些字段判重时的重复数据）
     */
    public String getCountMoreThanOneSql(String schemaName, String tableName, List<String> columns) {
        String columnStr = "\"" + String.join("\",\"", columns) + "\"";
        return String.format("SELECT %s FROM \"%s\".\"%s\" GROUP BY %s HAVING count(*)>1", columnStr, schemaName,
                tableName, columnStr);
    }

    /**
     * 生成“按指定字段分组后仅出现 1 次”的查询 SQL
     */
    public String getCountOneSql(String schemaName, String tableName, List<String> columns) {
        String columnStr = "\"" + String.join("\",\"", columns) + "\"";
        return String.format("SELECT %s FROM \"%s\".\"%s\" GROUP BY %s HAVING count(*)=1", columnStr, schemaName,
                tableName, columnStr);
    }

    /**
     * 查询指定表中某字段的最大值（增量迁移场景下用于计算增量字段的水位值）
     */
    public Object queryMaxVal(Connection connection, String schemaName, String tableName, String columnName) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement
                     .executeQuery(String.format("SELECT MAX(%s) FROM %s.%s", columnName, schemaName, tableName))) {
            if (resultSet.next()) {
                return resultSet.getObject(1);
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 查询自定义 SQL 结果集中某字段的最大值（自定义 SQL 抽取模式下计算增量水位值）
     */
    public Object queryMaxVal(Connection connection, String sql, String columnName) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement
                     .executeQuery(String.format("SELECT MAX(%s) FROM (%s) t", columnName, sql))) {
            if (resultSet.next()) {
                return resultSet.getObject(1);
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 执行带单个绑定参数的计数 SQL（结果集需含别名为 c 的计数列），返回计数值，无结果时返回 0
     */
    public Integer countBySql(Connection connection, String sql, Object value) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("c");
                }
                return 0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 建表语句前置钩子：在 CREATE TABLE 之前追加方言相关内容，默认无操作，由需要的子类覆盖
     */
    @Override
    public void preAppendCreateTableSql(StringBuilder builder) {
        // 此处不执行操作，请由子类重写！
    }

    /**
     * 在建表语句中追加主键定义片段 ", PRIMARY KEY (...)"；主键列表为空时不追加
     */
    @Override
    public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
        // 不支持主键的数据库类型(例如：hive)，需要覆盖掉该方法
        if (!CollectionUtils.isEmpty(primaryKeys)) {
            String primaryKeyAsString = getPrimaryKeyAsString(primaryKeys);
            builder.append(", PRIMARY KEY (").append(primaryKeyAsString).append(")");
        }
    }

    /**
     * 建表语句后置钩子：在字段定义之后追加方言相关内容（表注释、存储引擎/属性、分布键等），
     * 默认无操作，由需要的子类覆盖
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        // 此处不执行操作，请由子类重写！
    }

}
