package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3AddColumn;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3CatalogColumns;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 支持SQLite数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseSqliteImpl extends AbstractDatabase implements IDatabaseInterface {

    /** 读取实际附加库的目录，缺失命名空间不能退回 main；不改变调用方忙等待策略。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (connection == null || namespace == null || namespace.isBlank() || timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("SQLite 诊断连接、命名空间和有界期限不能为空");
        }
        String quoted = quoteName(namespace);
        boolean attached = "temp".equalsIgnoreCase(namespace);
        // Xerial 的 setQueryTimeout 会修改 busy_timeout；外层独占诊断执行器负责硬期限。
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA database_list")) {
            while (rows.next()) {
                attached |= namespace.equalsIgnoreCase(rows.getString("name"));
            }
        }
        if (!attached) {
            throw new SQLException("SQLite 目标附加库不存在", "3F000");
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + quoted + ".sqlite_master WHERE 1=0")) {
            if (!rows.next() || rows.getInt(1) != 0) {
                throw new SQLException("SQLite 命名空间未返回有效目录响应");
            }
        }
    }

    /** SQLite 默认没有数据库账号、GRANT 或服务器角色，不能将文件可读伪装成账号授权。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds)
            throws java.sql.SQLFeatureNotSupportedException {
        throw new cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException(
                "SQLite 不提供服务器账号或 GRANT 权限目录；文件访问由宿主系统控制");
    }

    /** SQLite 不提供原生表列 COMMENT 目录；DDL 文本中的注释不等于可查询扩展属性。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds)
            throws java.sql.SQLFeatureNotSupportedException {
        throw new cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException(
                "SQLite 没有原生表列 COMMENT 扩展属性目录");
    }

    /** WAL 文件不是逻辑复制服务，不能据 journal_mode=WAL 声称具备 CDC 前置条件。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws java.sql.SQLFeatureNotSupportedException {
        throw new cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException(
                "SQLite 没有服务器复制账号或逻辑复制协议；WAL 不能证明 CDC 就绪");
    }

    /** 所有目标严格限定 temp，重复清理绝不会删除 main 或 ATTACH 中的同名业务对象。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) {
        if (randomName == null || !randomName.matches("dbswitch_diag_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("组件生成的诊断临时表名不合法");
        }
        String name = quoteName(randomName);
        String relation = "temp." + name;
        String renamed = quoteName(randomName + "_renamed");
        String renamedRelation = "temp." + renamed;
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan(
                "CREATE TEMPORARY TABLE " + relation + " (probe_id INTEGER PRIMARY KEY, probe_value VARCHAR(40))",
                relation, "ALTER TABLE " + relation + " ADD COLUMN probe_extra INTEGER",
                "DROP TABLE IF EXISTS " + relation,
                "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (4,'upserted') "
                        + "ON CONFLICT(probe_id) DO UPDATE SET probe_value=excluded.probe_value",
                null, Integer.MAX_VALUE, null, "ALTER TABLE " + relation + " RENAME TO " + renamed,
                renamedRelation, "DROP TABLE IF EXISTS " + renamedRelation);
    }

    /**
     * 借用当前连接读取当前数据库已用字节；不改变连接状态、不推断磁盘总量。
     */
    @Override
    public long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqliteNativeCatalog
                .allocatedDatabaseBytes(connection, timeoutSeconds);
    }

    /**
     * 初始化 {@code DatabaseSqliteImpl}及其父类状态。
     */
    public DatabaseSqliteImpl() {
        super(ProductTypeEnum.SQLITE3.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.SQLITE3;
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
    public void prepareDialect(Connection connection) throws SQLException {
        cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqliteNativeCatalog.verifyConnection(connection,
                10);
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement()) {
            // Xerial 3.31 的 setQueryTimeout 会修改连接 busy_timeout，因此保留调用方策略。
            try (var rows = statement.executeQuery("PRAGMA database_list")) {
                while (rows.next()) {
                    result.add(rows.getString("name"));
                }
            }
            return result;
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        String schema = schema(schemaName);
        List<TableDescription> result = new ArrayList<>();
        // Xerial 3.31 元数据不解析附加模式，绝不能隐式转而浏览 main。
        String sql = "SELECT name, type FROM " + quoteName(schema)
                + ".sqlite_master WHERE type IN ('table','view') AND substr(name,1,7) <> 'sqlite_' ORDER BY name";
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    var table = new TableDescription();
                    table.setSchemaName(schema);
                    table.setTableName(rows.getString(1));
                    table.setTableType(rows.getString(2).toUpperCase(Locale.ROOT));
                    result.add(table);
                }
            }
            return result;
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /** 诊断目录读取有明确行数上限，超限拒绝截断，不回退到 main。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schemaName,
            int maxTables, int timeoutSeconds) throws SQLException {
        if (maxTables < 1 || maxTables > 10_000 || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("SQLite 诊断目录必须设置有界行数与期限");
        }
        String scope = schema(schemaName);
        String sql = "SELECT name,type FROM " + quoteName(scope)
                + ".sqlite_master WHERE type IN ('table','view') AND substr(name,1,7) <> 'sqlite_' ORDER BY name LIMIT ?";
        List<TableDescription> tables = new ArrayList<>();
        // Xerial 的 setQueryTimeout 会改 busy_timeout，诊断总执行期限由外层独占执行器控制。
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxTables + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (tables.size() == maxTables) {
                        throw new IllegalArgumentException("SQLite 目录超过诊断上限，拒绝返回截断证据");
                    }
                    var table = new TableDescription();
                    table.setSchemaName(scope);
                    table.setTableName(rows.getString(1));
                    table.setTableType(rows.getString(2).toUpperCase(Locale.ROOT));
                    tables.add(table);
                }
            }
        }
        return List.copyOf(tables);
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        return nativeDdl(connection, schemaName, tableName, "table");
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return nativeDdl(connection, schemaName, tableName, "view");
    }

    /**
     * 即使查询值已绑定，也拒绝无效标识符。
     */
    private String nativeDdl(Connection connection, String schemaName, String tableName, String type) {
        quoteName(tableName); // 即使查询值已绑定，也拒绝无效标识符。
        String sql = "SELECT sql FROM " + quoteName(schema(schemaName))
                + ".sqlite_master WHERE type=? AND name=? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setMaxRows(2);
            ps.setString(1, type);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                String ddl = rs.getString(1);
                if (rs.next()) {
                    throw new SQLException("Ambiguous SQLite catalog object", "22000");
                }
                // 保留原生子句、引号和字面值分号，此处不提供完整索引或触发器导出。
                return ddl;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        quoteName(tableName);
        String schema = schema(schemaName);
        quoteName(schema);
        List<String> keys = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT name FROM pragma_table_xinfo(?, ?) WHERE pk > 0 ORDER BY pk")) {
            statement.setString(1, tableName);
            statement.setString(2, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
            }
            return keys;
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName) {
        return getSelectSqlColumnMeta(connection, getTableFieldsQuerySQL(schemaName, tableName));
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName) {
        // getColumns 把 BLOB 报成 VARCHAR、NUMERIC 报成 FLOAT。声明类型走 pragma_table_xinfo。
        try {
            return Sqlite3CatalogColumns.read(connection, schema(schemaName), tableName, 10).columns();
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return quoteName(schema(schemaName)) + "." + quoteName(tableName);
    }

    /**
     * 返回模式。
     */
    private static String schema(String schema) {
        return schema == null || schema.isBlank() ? "main" : schema;
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    @Override
    public String quoteIdentifier(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid SQLite identifier");
        }
        return super.quoteIdentifier(name);
    }

    /**
     * 引用名称。
     */
    private String quoteName(String name) {
        return quoteIdentifier(name);
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s LIMIT 0 ", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " WHERE 1=0";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain %s", sql.replace(";", ""));
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();

        String retval = " \"" + fieldname + "\"   ";

        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
            case ColumnMetaData.TYPE_DATE:
                // sqlite中没有时间数据类型
                retval += "DATETIME";
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += "CHAR(1)";
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    // 关键字 AUTOINCREMENT 只能⽤于整型（INTEGER）字段。
                    if (useAutoInc) {
                        retval += "INTEGER PRIMARY KEY AUTOINCREMENT";
                    } else {
                        retval += "BIGINT ";
                    }
                } else {
                    if (precision != 0 || length < 0 || length > 18) {
                        retval += "NUMERIC";
                    } else {
                        retval += "INTEGER";
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                // 与上游方言一致，未知长度和字符 LOB 保留文本亲和性。
                retval += "TEXT";
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval += "BLOB";
                break;
            default:
                retval += "TEXT";
                break;
        }

        if (addCr) {
            retval += Const.CR;
        }

        return retval;
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /**
     * 可空且无默认的加列。空 schema 按本类其他入口视为 main。
     * 标识符只用 quotePhysicalIdentifier(SQLITE3)。主键、NOT NULL、唯一约束、
     * 默认值、自增和生成列在这里拒绝，不发出 3.31.1 会拒绝或超出该范围的语句。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return Sqlite3AddColumn.sql(this, schema(targetSchemaName), targetTableName, targetColumn);
    }

    /**
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        return false;
    }

}
