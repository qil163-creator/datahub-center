package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.DataSecurityParam;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.PageSqlModel;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * OceanBase 元信息实现：按兼容模式委托 MySQL 或 Oracle 方言。
 */
public class DatabaseOceanbaseImpl extends AbstractDatabase implements IDatabaseInterface {

    /** 每次从本连接核对 OceanBase 模式，不能复用其他连接的模式缓存。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        if (diagnosticMysqlMode(connection, timeoutSeconds)) {
            mysqlDelegate.mysqlNamespace(connection, namespace, timeoutSeconds);
        } else {
            // Oracle 模式与 MySQL USE 的授权语义不同，使用基本表零行 SELECT 证据。
            oracleDelegate.probeNamespace(connection, namespace, timeoutSeconds);
        }
    }

    /** 模式确定后显式读取该模式的账号目录，不启用对应兼容产品的 CDC 或写计划。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        if (diagnosticMysqlMode(connection, timeoutSeconds)) {
            mysqlDelegate.mysqlAccountPrivileges(connection, namespace, timeoutSeconds);
        } else {
            oracleDelegate.probeAccountPrivileges(connection, namespace, timeoutSeconds);
        }
    }

    /** 两种模式分别选择固定原生目录，均在服务器和 JDBC 层设置行上限。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        if (!diagnosticMysqlMode(connection, timeoutSeconds)) return oracleDelegate.queryCatalogTableList(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<TableDescription>();
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("OceanBase 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 只复用已存在的两套注释目录；模式必须由本次借用连接证明，不套用 binlog/LogMiner。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        return diagnosticMysqlMode(connection,timeoutSeconds)
                ? mysqlDelegate.mysqlExtendedProperties(connection,namespace,table,timeoutSeconds)
                : oracleDelegate.probeExtendedProperties(connection,namespace,table,timeoutSeconds);
    }

    private static boolean diagnosticMysqlMode(Connection connection, int timeoutSeconds) throws SQLException {
        try (var statement = connection.prepareStatement("show global variables where variable_name = 'ob_compatibility_mode'")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLFeatureNotSupportedException("OceanBase 兼容模式证据缺失", "0A000");
                String mode = rows.getString(2);
                if (rows.next() || !("MYSQL".equalsIgnoreCase(mode) || "ORACLE".equalsIgnoreCase(mode))) throw new java.sql.SQLFeatureNotSupportedException("OceanBase 兼容模式不唯一或未知", "0A000");
                return "MYSQL".equalsIgnoreCase(mode);
            }
        }
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("OceanBase 诊断参数必须完整且有界");
    }

    /**
     * 探测数据库连接并返回连接诊断结果。
     */
    @Override
    public void probeConnection(Connection connection, int timeoutSeconds) throws SQLException {
        // 探活按本次物理连接确认模式，不复用其他连接的缓存。
        (OceanbaseUtils.isOceanBaseUseMysqlMode(connection) ? mysqlDelegate : oracleDelegate)
                .probeConnection(connection, timeoutSeconds);
    }

    /** 仅普通物理名称可用于诊断；按本次借用连接的模式选择已有引用机制，不读取方言缓存。 */
    @Override
    public String diagnosticTableReference(Connection connection, String namespace, String table,
            int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String mysqlReference = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.OCEANBASE, namespace, table);
        return diagnosticMysqlMode(connection, timeoutSeconds) ? mysqlReference
                : oracleDelegate.getQuotedSchemaTableCombination(namespace, table);
    }

    private final DatabaseMysqlImpl mysqlDelegate;

    private final DatabaseOracleImpl oracleDelegate;

    private volatile Boolean mysqlMode;

    /** 由 {@link #prepareDialect} 读取的兼容模式。目标连接验证前为 null。 */
    public Boolean observedMysqlMode() {
        return mysqlMode;
    }

    /**
     * 初始化 {@code DatabaseOceanbaseImpl}及其父类状态。
     */
    public DatabaseOceanbaseImpl() {
        super(ProductTypeEnum.OCEANBASE.getDriveClassName());
        String driver = ProductTypeEnum.OCEANBASE.getDriveClassName();
        this.mysqlDelegate = new OceanbaseMysqlDialect(driver);
        this.oracleDelegate = new DatabaseOracleImpl(driver);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.OCEANBASE;
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.OCEANBASE) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return OceanbaseRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 多兼容模式产品在实际连接上验证方言；默认委托旧 SPI，保留已有产品/扩展的行为。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(Connection connection, String alias,
            String column) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.OCEANBASE) {
            return super.integerKeyProjection(connection, alias, column);
        }
        OceanbaseRelationIdentity.requireVerifiedMysqlMode(connection);
        return mysqlDelegate.mysqlSignedIntegerProjection(alias, column, true);
    }

    /**
     * 连接感知的复合键投影；不得为了检测模式改变连接状态。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(Connection connection, String alias, String column,
            int maxBytes) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.OCEANBASE) {
            return super.keyComponentProjection(connection, alias, column, maxBytes);
        }
        OceanbaseRelationIdentity.requireVerifiedMysqlMode(connection);
        return mysqlDelegate.mysqlSignedIntegerComponent(
                mysqlDelegate.mysqlSignedIntegerProjection(alias, column, true), maxBytes);
    }

    /** 仅限 MySQL 模式的模板；执行前必须先校验实际连接模式。 */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.OCEANBASE) {
            return super.textColumnExpression(column);
        }
        return "CAST(" + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OCEANBASE, column) + " AS CHAR)";
    }

    /**
     * 生成 DDL 是否需要实际目标连接提供的兼容模式验证依据。
     */
    @Override
    public boolean requiresDialectConnection() {
        return true;
    }

    /**
     * 核对实际连接产品和兼容模式，准备后续方言操作。
     */
    @Override
    public synchronized void prepareDialect(Connection connection) {
        boolean observed = OceanbaseUtils.isOceanBaseUseMysqlMode(java.util.Objects.requireNonNull(connection,
                "OceanBase dialect requires a target connection"));
        if (mysqlMode != null && mysqlMode.booleanValue() != observed) {
            throw new IllegalStateException("OceanBase compatibility mode changed for this metadata instance");
        }
        mysqlMode = observed;
    }

    /**
     * 判断MySQL模式。
     */
    private boolean isMysqlMode(Connection connection) {
        prepareDialect(connection);
        return mysqlMode;
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private AbstractDatabase dialect(Connection connection) {
        return isMysqlMode(connection) ? mysqlDelegate : oracleDelegate;
    }

    /**
     * 读取已经探测的 OceanBase 兼容模式，未探测时拒绝生成 DDL。
     */
    private AbstractDatabase cachedDialect() {
        Boolean mode = mysqlMode;
        if (mode == null) {
            throw new IllegalStateException(
                    "OceanBase DDL requires compatibility mode verified from the target connection");
        }
        return mode ? mysqlDelegate : oracleDelegate;
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        return dialect(connection).querySchemaList(connection);
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        return dialect(connection).queryTableList(connection, schemaName);
    }

    /**
     * 精确获取表或视图的元数据。
     */
    @Override
    public TableDescription queryTableMeta(Connection connection, String schemaName, String tableName) {
        return dialect(connection).queryTableMeta(connection, schemaName, tableName);
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        return dialect(connection).getTableDDL(connection, schemaName, tableName);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return dialect(connection).getViewDDL(connection, schemaName, tableName);
    }

    /**
     * 获取指定模式表的字段列表。
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName, String tableName) {
        return dialect(connection).queryTableColumnName(connection, schemaName, tableName);
    }

    /**
     * 为字段描述列表补充默认值与注释信息。
     */
    @Override
    public void setColumnDefaultValue(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
        dialect(connection).setColumnDefaultValue(connection, schemaName, tableName, columnDescriptions);
    }

    /**
     * 为字段描述列表补充索引信息。
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
        dialect(connection).setColumnIndexInfo(connection, schemaName, tableName, columnDescriptions);
    }

    /**
     * 读取表的索引定义。
     */
    @Override
    public List<IndexDescription> queryTableIndexes(Connection connection, String schemaName, String tableName) {
        return dialect(connection).queryTableIndexes(connection, schemaName, tableName);
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName) {
        List<ColumnDescription> columns = dialect(connection).queryTableColumnMeta(connection, schemaName, tableName);
        return Boolean.TRUE.equals(mysqlMode) ? temporalColumns(connection, schemaName, tableName, columns) : columns;
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        return dialect(connection).querySelectSqlColumnMeta(connection, sql);
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName) {
        List<ColumnDescription> columns = dialect(connection).queryTableColumnMetaOnly(connection, schemaName,
                tableName);
        return Boolean.TRUE.equals(mysqlMode) ? temporalColumns(connection, schemaName, tableName, columns) : columns;
    }

    /** 2.4.3 驱动将 DATETIME(n) 的 scale 报告为零；应使用原生目录，绝不根据值推断。 */
    private List<ColumnDescription> temporalColumns(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        var observed = cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseTemporalColumns.read(connection,
                schema, table, 30);
        if (!new java.util.ArrayList<>(observed.keySet()).equals(
                columns.stream().map(ColumnDescription::getFieldName).toList())) {
            throw new IllegalStateException("OceanBase column catalog changed during metadata collection");
        }
        for (var column : columns) {
            column.setDbType(ProductTypeEnum.OCEANBASE);
            Integer scale = observed.get(column.getFieldName()).scale();
            if (scale == null) {
                continue;
            }
            column.setScaleSize(scale);
            if (column.getFieldType() == java.sql.Types.TIME) {
                column.setValType(ColumnMetaData.TYPE_TIME);
                column.setLength(scale);
                column.setPrecision(0);
            } else if (column.getFieldType() != java.sql.Types.TIMESTAMP) {
                throw new IllegalStateException("OceanBase temporal catalog and JDBC type disagree");
            }
        }
        return columns;
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        return dialect(connection).queryTablePrimaryKeys(connection, schemaName, tableName);
    }

    /**
     * 获取指定模式表内的数据。
     */
    @Override
    public SchemaTableData queryTableData(Connection connection, String schemaName, String tableName, int rowCount) {
        return dialect(connection).queryTableData(connection, schemaName, tableName, rowCount);
    }

    /**
     * 按自定义 SQL 预览数据（最多返回 rowCount 行），行数限制与流式读取逻辑同 {@link #queryTableData}。
     */
    @Override
    public SchemaTableData queryTableDataBySql(Connection connection, String sql, int rowCount) {
        return dialect(connection).queryTableDataBySql(connection, sql, rowCount);
    }

    /**
     * 测试查询SQL语句的有效性。
     */
    @Override
    public void testQuerySQL(Connection connection, String sql) {
        dialect(connection).testQuerySQL(connection, sql);
    }

    /**
     * 根据要同步的字段添加目标表中不存在的字段。
     */
    @Override
    public void addNoExistColumnsByTarget(Connection connection, String targetSchemaName, String targetTableName,
            List<String> allColumns, List<ColumnDescription> targetColumnDescriptions) {
        dialect(connection).addNoExistColumnsByTarget(connection, targetSchemaName, targetTableName,
                allColumns, targetColumnDescriptions);
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return cachedDialect().getQuotedSchemaTableCombination(schemaName, tableName);
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
            boolean withRemarks) {
        return cachedDialect().getFieldDefinition(v, pks, useAutoInc, addCr, withRemarks);
    }

    private static final class OceanbaseMysqlDialect extends DatabaseMysqlImpl {

        /**
         * 初始化 {@code OceanbaseMysqlDialect}及其父类状态。
         */
        private OceanbaseMysqlDialect(String driver) {
            super(driver);
        }

        /**
         * 原生目录属于其他产品时，兼容委托必须禁用此能力。
         */
        @Override
        protected boolean supportsNativeTemporalMetadata() {
            return false;
        }

        /**
         * 生成 MySQL 建表语句中的字段定义片段。
         */
        @Override
        public String getFieldDefinition(ColumnMetaData column, List<String> keys, boolean autoIncrement,
                boolean newline, boolean remarks) {
            int type = column.getType();
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                return super.getFieldDefinition(column, keys, autoIncrement, newline, remarks);
            }
            StringBuilder sql = new StringBuilder(" ").append(quoteIdentifier(column.getName())).append(' ')
                    .append(type == ColumnMetaData.TYPE_DATE ? "DATE" : temporalType(column));
            if (!column.isNullable()) {
                sql.append(" NOT NULL");
            }
            if (column.getDefaultValue() != null) {
                sql.append(" DEFAULT ").append(temporalDefault(column));
            }
            if (remarks && column.getRemarks() != null && !column.getRemarks().isBlank()) {
                if (column.getRemarks().contains("\\")) {
                    throw new UnsupportedOperationException(
                            "OceanBase escaped temporal comments require a native SQL mode plan");
                }
                sql.append(" COMMENT '").append(column.getRemarks().replace("'", "''")).append('\'');
            }
            if (newline) {
                sql.append(cn.iocoder.yudao.framework.dbswitch.common.constant.Const.CR);
            }
            return sql.toString();
        }

        /**
         * 拒绝当前实现未支持的 {@code temporalDefault} 操作。
         */
        private String temporalDefault(ColumnMetaData column) {
            String value = column.getDefaultValue();
            if (value.equalsIgnoreCase("NULL")) {
                return "NULL";
            }
            if (column.getType() == ColumnMetaData.TYPE_TIMESTAMP && value.matches(
                    "(?i)CURRENT_TIMESTAMP(?:\\([0-6]\\))?")) {
                return value;
            }
            String literal = value.length() >= 2 && value.startsWith("'") && value.endsWith("'")
                    ? value.substring(1, value.length() - 1) : value;
            if (column.getType() == ColumnMetaData.TYPE_TIME) {
                cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.timeDurationMicros(literal);
            } else if (column.getType() == ColumnMetaData.TYPE_DATE) {
                if (!literal.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw new UnsupportedOperationException("OceanBase DATE default requires a native literal");
                }
            } else if (!literal.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?")) {
                throw new UnsupportedOperationException("OceanBase DATETIME default requires a native literal");
            }
            return "'" + literal + "'";
        }

        /**
         * 产品委托可保留已验证的时间精度，无需改写生成 SQL。
         */
        @Override
        protected String temporalType(ColumnMetaData column) {
            if (column.getSqlType() == java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                    || column.getSqlType() == java.sql.Types.TIME_WITH_TIMEZONE) {
                throw new UnsupportedOperationException("OceanBase MySQL mode cannot preserve temporal time zones");
            }
            if (column.getLength() < 0 || column.getLength() > 6) {
                throw new IllegalArgumentException("OceanBase MySQL temporal precision must be between 0 and 6");
            }
            return (column.getType() == ColumnMetaData.TYPE_TIMESTAMP ? "DATETIME" : "TIME")
                    + "(" + column.getLength() + ")";
        }
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        return cachedDialect().getPrimaryKeyAsString(pks);
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return cachedDialect().getTableColumnCommentDefinition(td, cds);
    }

    /**
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        return cachedDialect().canCreateIndex(v);
    }

    /**
     * CREATE INDEX 是否需要在索引名前加 schema：Oracle 系不加时索引落在当前用户的 schema。
     */
    @Override
    public boolean qualifiesIndexName() {
        return cachedDialect().qualifiesIndexName();
    }

    /** 仅限 MySQL 模式。允许空值、无默认值、非键、非标识生成列且非生成列。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseAddColumn.sql(
                this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 建表语句前置钩子：在 CREATE TABLE 之前追加方言相关内容，默认无操作，由需要的子类覆盖。
     */
    @Override
    public void preAppendCreateTableSql(StringBuilder builder) {
        cachedDialect().preAppendCreateTableSql(builder);
    }

    /**
     * 在建表语句中追加主键定义片段 ", PRIMARY KEY (...)"；主键列表为空时不追加。
     */
    @Override
    public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
        cachedDialect().appendPrimaryKeyForCreateTableSql(builder, primaryKeys);
    }

    /**
     * 建表语句后置钩子：在字段定义之后追加方言相关内容（表注释、存储引擎/属性、分布键等）， 默认无操作，由需要的子类覆盖。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        cachedDialect().postAppendCreateTableSql(builder, tblComment, primaryKeys, tblProperties, hashFiled);
    }

    /**
     * 执行给定 SQL 并返回当前操作结果。
     */
    @Override
    public JdbcSelectResult executeBySql(Connection connection, String dbType, String sql,
            int rowCount, DataSecurityParam dataSecurityParam) {
        return dialect(connection).executeBySql(connection, dbType, sql, rowCount, dataSecurityParam);
    }

    /**
     * 通用分页查询：把任意查询 SQL 包装为分页 SQL 与 count SQL 后执行。
     */
    @Override
    public JdbcSelectResult pageDataBySql(Connection connection, String sql, List<Object> params, Integer page,
            Integer limit, DataSecurityParam dataSecurityParam) {
        return dialect(connection).pageDataBySql(connection, sql, params, page, limit, dataSecurityParam);
    }

    /**
     * 按数据库方言生成分页 SQL 与 count SQL 模板（各含两个 ? 分页占位符）： Oracle 用 ROWNUM 双层嵌套子查询、SQLServer 用 OFFSET ... FETCH NEXT、 MongoDB 用 skip().limit()，其余默认 LIMIT ? OFFSET ?。
     */
    @Override
    public PageSqlModel getPageModel(String sql) {
        return cachedDialect().getPageModel(sql);
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    @Override
    public String quoteIdentifier(String name) {
        return cachedDialect().quoteIdentifier(name);
    }

    /**
     * 将表达式转为可做字符串函数的文本类型。
     */
    @Override
    public String castToText(String expr) {
        return cachedDialect().castToText(expr);
    }

    /**
     * 字符长度表达式。
     */
    @Override
    public String charLengthExpr(String expr) {
        return cachedDialect().charLengthExpr(expr);
    }

    /**
     * 绑定 {@link #getPageModel} 生成的两个分页占位符。
     */
    @Override
    public void bindPageParameters(PreparedStatement stmt, int paramOffset, int page, int limit)
            throws SQLException {
        cachedDialect().bindPageParameters(stmt, paramOffset, page, limit);
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        cachedDialect();
        return Boolean.FALSE.equals(mysqlMode)
                ? oracleDelegate.dialectDefaultValueSql(schemaName, tableName)
                : mysqlDelegate.dialectDefaultValueSql(schemaName, tableName);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        cachedDialect();
        return Boolean.FALSE.equals(mysqlMode)
                ? oracleDelegate.dialectTableFieldsQuerySQL(schemaName, tableName)
                : mysqlDelegate.dialectTableFieldsQuerySQL(schemaName, tableName);
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        cachedDialect();
        return Boolean.FALSE.equals(mysqlMode)
                ? oracleDelegate.dialectTestQuerySQL(sql)
                : mysqlDelegate.dialectTestQuerySQL(sql);
    }
}
