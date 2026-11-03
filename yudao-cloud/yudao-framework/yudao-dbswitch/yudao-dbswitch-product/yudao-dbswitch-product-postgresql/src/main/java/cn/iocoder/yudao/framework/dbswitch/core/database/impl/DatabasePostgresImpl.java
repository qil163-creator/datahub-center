package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.database.constant.PostgresqlConst;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.DDLFormatterUtils;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresUtils;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresReadSnapshot;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresAddColumn;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支持PostgreSQL数据库的元信息实现
 *
 * @author jrl
 */
public class DatabasePostgresImpl extends AbstractDatabase implements IDatabaseInterface {

    private static Set<String> systemSchemas = new HashSet<>();

    private static final String SHOW_CREATE_VIEW_SQL_1 =
            "SELECT pg_get_viewdef((select pg_class.relfilenode from pg_catalog.pg_class \n"
                    + "join pg_catalog.pg_namespace on pg_class.relnamespace = pg_namespace.oid \n"
                    + "where pg_namespace.nspname='%s' and pg_class.relname ='%s'),true) ";

    private static final String SHOW_CREATE_VIEW_SQL_2 =
            "select pg_get_viewdef('\"%s\".\"%s\"', true)";

    static {
        systemSchemas.add("pg_aoseg");
        systemSchemas.add("information_schema");
        systemSchemas.add("pg_catalog");
        systemSchemas.add("pg_bitmapindex");
    }

    /**
     * 初始化 {@code DatabasePostgresImpl}及其父类状态。
     */
    public DatabasePostgresImpl() {
        super(ProductTypeEnum.POSTGRESQL.getDriveClassName());
    }

    /**
     * 初始化 {@code DatabasePostgresImpl}及其父类状态。
     */
    public DatabasePostgresImpl(String driverClassName) {
        super(driverClassName);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.POSTGRESQL;
    }

    /**
     * 读取账号角色并验证当前数据库连接和目标命名空间使用权限，不表示任意对象的写权限。
     */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace,
            int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            super.probeAccountPrivileges(connection, namespace, timeoutSeconds);
            return;
        }
        try (var statement = connection.prepareStatement("SELECT r.rolsuper,r.rolreplication,"
                + "pg_catalog.has_database_privilege(current_user,current_database(),'CONNECT'),"
                + "pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') "
                + "FROM pg_catalog.pg_roles r CROSS JOIN pg_catalog.pg_namespace n "
                + "WHERE r.rolname=current_user AND n.nspname=?")) {
            statement.setString(1, namespace);
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Account or namespace is not visible", "3F000");
                }
                for (int column = 1; column <= 4; column++) {
                    boolean granted = rows.getBoolean(column);
                    if (rows.wasNull()) {
                        throw new SQLException("Privilege metadata is incomplete");
                    }
                    if (column >= 3 && !granted) {
                        throw new SQLException("Required resource privilege is absent", "42501");
                    }
                }
            }
        }
    }

    /**
     * 精确关系的表/列注释目录读取；注释不存在也是有效观察，不读取业务行。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.probeExtendedProperties(connection, namespace, table, timeoutSeconds);
        }
        String sql = "SELECT pg_catalog.obj_description(c.oid,'pg_class') IS NOT NULL,"
                + "count(a.attnum),count(pg_catalog.col_description(c.oid,a.attnum)) "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p') GROUP BY c.oid";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, table);
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Sample relation is no longer visible", "42P01");
                }
                boolean tableComment = rows.getBoolean(1);
                int columns = rows.getInt(2);
                int comments = rows.getInt(3);
                if (columns < 1 || comments < 0 || comments > columns) {
                    throw new SQLException("Extended-property metadata is incomplete");
                }
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,
                        tableComment, comments);
            }
        }
    }

    /**
     * 只读检查 CDC 日志配置和账号复制角色，不创建复制槽、不连接复制流。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.probeCdcReadiness(connection, timeoutSeconds);
        }
        try (var statement = connection.prepareStatement("SELECT current_setting('wal_level'),rolsuper,rolreplication,"
                + "current_setting('max_replication_slots')::int,current_setting('max_wal_senders')::int "
                + "FROM pg_catalog.pg_roles WHERE rolname=current_user")) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Current role metadata is unavailable");
                }
                String wal = rows.getString(1);
                boolean administrator = rows.getBoolean(2);
                if (rows.wasNull()) {
                    throw new SQLException("Current role metadata is incomplete");
                }
                boolean replication = rows.getBoolean(3);
                if (rows.wasNull() || wal == null) {
                    throw new SQLException("CDC metadata is incomplete");
                }
                int slots = rows.getInt(4);
                if (rows.wasNull() || slots < 0) {
                    throw new SQLException("复制槽容量配置证据缺失");
                }
                int senders = rows.getInt(5);
                if (rows.wasNull() || senders < 0) {
                    throw new SQLException("复制发送进程容量配置证据缺失");
                }
                // 仅验证配置允许逻辑复制；不占用复制槽，也不把剩余容量作为瞬时保证。
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(
                        "logical".equals(wal) && slots > 0 && senders > 0, administrator || replication);
            }
        }
    }

    /**
     * 在独占诊断会话验证目标命名空间的进入/使用权限；目录可见性不等于可访问。
     */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            super.probeNamespace(connection, namespace, timeoutSeconds);
            return;
        }
        if (namespace == null || timeoutSeconds < 1) {
            throw new IllegalArgumentException("命名空间和期限不能为空");
        }
        try (var statement = connection.prepareStatement(
                "SELECT pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') "
                        + "FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
            statement.setString(1, namespace);
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Namespace is absent", "3F000");
                }
                if (!rows.getBoolean(1) || rows.wasNull()) {
                    throw new SQLException("Namespace access denied", "42501");
                }
            }
        }
    }

    /**
     * 只允许组件生成的随机表名；未用真实产品验证的临时写入能力默认不启用。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.temporaryTableDiagnosticPlan(randomName);
        }
        if (randomName == null || !randomName.matches("dbswitch_diag_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("组件生成的诊断临时表名不合法");
        }
        String name = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, randomName);
        String relation = "pg_temp." + name;
        String renamed = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL,
                randomName + "_renamed");
        String renamedRelation = "pg_temp." + renamed;
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan(
                "CREATE TEMPORARY TABLE " + name + " (probe_id INTEGER PRIMARY KEY, probe_value VARCHAR(40)) ON COMMIT PRESERVE ROWS",
                relation, "ALTER TABLE " + relation + " ADD COLUMN probe_extra INTEGER",
                "DROP TABLE IF EXISTS " + relation,
                "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (4,'upserted') ON CONFLICT (probe_id) DO UPDATE SET probe_value=EXCLUDED.probe_value",
                "MERGE INTO " + relation + " AS target USING (VALUES (5,'merged'),(6,'inserted')) AS source(probe_id,probe_value) "
                        + "ON target.probe_id=source.probe_id WHEN MATCHED THEN UPDATE SET probe_value=source.probe_value "
                        + "WHEN NOT MATCHED THEN INSERT (probe_id,probe_value) VALUES(source.probe_id,source.probe_value)",
                15, "TRUNCATE TABLE " + relation, "ALTER TABLE " + relation + " RENAME TO " + renamed,
                renamedRelation, "DROP TABLE IF EXISTS " + renamedRelation);
    }

    /**
     * 查询系统目录表列表。
     */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schemaName,
            int maxTables, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.queryCatalogTableList(connection, schemaName, maxTables, timeoutSeconds);
        }
        RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, schemaName);
        if (maxTables < 1 || maxTables > 10_000 || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("原生表目录读取必须有界");
        }
        // 排除视图、外部表和系统对象，诊断零行读取不执行视图内的用户函数。
        String sql = "SELECT n.nspname, c.relname FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relkind IN ('r','p') ORDER BY c.relname LIMIT ?";
        List<TableDescription> tables = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setInt(2, maxTables + 1);
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (tables.size() == maxTables) {
                        throw new IllegalArgumentException("目录结果超过读取上限");
                    }
                    var table = new TableDescription();
                    table.setSchemaName(rows.getString(1));
                    table.setTableName(rows.getString(2));
                    table.setTableType("TABLE");
                    tables.add(table);
                }
            }
        }
        return List.copyOf(tables);
    }

    /**
     * 借用当前连接读取当前数据库已用字节；不改变连接状态、不推断磁盘总量。
     */
    @Override
    public long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.queryDatabaseUsedBytes(connection, timeoutSeconds);
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive query timeout is required");
        }
        try (var statement = connection.prepareStatement("SELECT pg_database_size(current_database())")) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Database capacity result is missing");
                }
                long bytes = rows.getLong(1);
                if (rows.wasNull() || bytes < 0) {
                    throw new SQLException("Database capacity result is invalid");
                }
                return bytes;
            }
        }
    }

    /**
     * 打开读取快照。
     */
    @Override
    public ReadSnapshot openReadSnapshot(
            Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.openReadSnapshot(connection, timeoutSeconds);
        }
        return PostgresReadSnapshot.open(connection, timeoutSeconds);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String relationReference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.queryRelationFingerprint(connection, relationReference);
        }
        return PostgresRelationIdentity.fingerprint(connection, relationReference);
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        List<String> schemas = super.querySchemaList(connection);
        return schemas.stream()
                .filter(s -> !systemSchemas.contains(s))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.queryTableList(connection, schemaName);
        }
        try {
            return new ArrayList<>(PostgresPartitionTables.read(connection, schemaName));
        } catch (SQLException failure) {
            throw new IllegalStateException("PostgreSQL table metadata could not be read", failure);
        }
    }

    /**
     * 未点名同步调用 {@link PostgresPartitionTables#defaultSyncTargets}。
     * 视图不是分区普通表，留给 tableType 过滤；显式表名不走这里。
     */
    @Override
    public List<TableDescription> defaultSyncTargets(List<TableDescription> tables) {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.defaultSyncTargets(tables);
        }
        if (tables == null) {
            throw new IllegalArgumentException("PostgreSQL table list is required");
        }
        var physical = new ArrayList<PostgresPartitionTables.Listed>();
        for (var table : tables) {
            if (table instanceof PostgresPartitionTables.Listed item && !item.isViewTable()) {
                physical.add(item);
            }
        }
        var allowed = new HashSet<TableDescription>(PostgresPartitionTables.defaultSyncTargets(physical));
        var ordered = new ArrayList<TableDescription>(tables.size());
        for (var table : tables) {
            if (table == null) {
                continue;
            }
            if (table instanceof PostgresPartitionTables.Listed item && !item.isViewTable()) {
                if (allowed.contains(item)) {
                    ordered.add(item);
                }
            } else {
                ordered.add(table);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 根据所选关系及读取模式规划实际读取的物理表。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan planTableReads(Connection connection,
            java.util.Collection<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> selected,
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode mode) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.planTableReads(connection, selected, mode);
        }
        return cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTableReads.plan(connection, selected, mode);
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        // 此操作由 setColumnDefaultValue 中预先构造的目录查询完成。
        return null;
    }

    /**
     * 为字段描述列表补充默认值与注释信息。
     */
    @Override
    public void setColumnDefaultValue(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        String sql = "SELECT a.attname, pg_catalog.pg_get_expr(d.adbin,d.adrelid), x.description "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid "
                + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum "
                + "LEFT JOIN pg_catalog.pg_description x ON x.objoid=c.oid AND x.objsubid=a.attnum "
                + "AND x.classoid='pg_catalog.pg_class'::regclass "
                + "WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    for (ColumnDescription column : columns) {
                        if (column.getFieldName().equals(rows.getString(1))) {
                            column.setDefaultValue(rows.getString(2));
                            column.setRemarks(rows.getString(3));
                            break;
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("PostgreSQL-family defaults and comments could not be read");
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String sql = PostgresqlConst.CREATE_TABLE_SQL_TPL
                .replace(PostgresqlConst.TPL_KEY_SCHEMA, schemaName)
                .replace(PostgresqlConst.TPL_KEY_TABLE, tableName);
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null && rs.next()) {
                        return DDLFormatterUtils.format(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            //throw new RuntimeException(e);
        }

        // 低版本的PostgreSQL的表的DDL获取方法
        return PostgresUtils.getTableDDL(connection, schemaName, tableName);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_VIEW_SQL_1, schemaName, tableName);
        try (Statement st = connection.createStatement()) {
            try {
                if (st.execute(sql)) {
                    try (ResultSet rs = st.getResultSet()) {
                        if (rs != null && rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
            } catch (SQLException se) {
                sql = String.format(SHOW_CREATE_VIEW_SQL_2, schemaName, tableName);
                if (st.execute(sql)) {
                    try (ResultSet rs = st.getResultSet()) {
                        if (rs != null && rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s LIMIT 0 ", stripTerminalDelimiter(sql));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " ";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain %s", stripTerminalDelimiter(sql));
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schema, String table) {
        return PostgreSqlUtils.getFullyQualifiedTableName(schema, table, true);
    }

    /**
     * 只生成可空、无默认值的 ADD COLUMN。其他产品类型走父类拒绝，避免把未引用的名字拼进语句。
     */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return PostgresAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> keys) {
        return keys.stream().map(PostgreSqlUtils::quoteIdentifier).collect(Collectors.joining(", "));
    }

    /**
     * 移除 SQL 末尾的语句分隔符。
     */
    private static String stripTerminalDelimiter(String sql) {
        String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
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

        String retval = " " + PostgreSqlUtils.quoteIdentifier(fieldname) + "   ";
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                retval += "TIMESTAMP";
                break;
            case ColumnMetaData.TYPE_TIME:
                retval += "TIME";
                break;
            case ColumnMetaData.TYPE_DATE:
                retval += "DATE";
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += "BOOLEAN";
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if ((v.getSqlType() == java.sql.Types.DECIMAL || v.getSqlType() == java.sql.Types.NUMERIC) && length <= 0) {
                    // 无界 NUMERIC 保持精确数值语义，绝不表示请求 DOUBLE PRECISION。
                    retval += "NUMERIC";
                } else if ((v.getSqlType() == java.sql.Types.DECIMAL || v.getSqlType() == java.sql.Types.NUMERIC) && length > 0 && precision >= 0) {
                    // 显式定点数保留总精度和小数位，零小数位及主键也不能降级成整数。
                    retval += "NUMERIC(" + length + ", " + precision + ")";
                } else if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += "BIGSERIAL";
                    } else {
                        retval += "BIGINT";
                    }
                } else {
                    if (length > 0) {
                        if (precision > 0 || length > 18) {
                            if ((length + precision) > 0 && precision > 0) {
                                // Numeric(Precision, Scale)：Precision 为总位数，Scale 为小数位数
                                retval += "NUMERIC(" + (length + precision) + ", " + precision + ")";
                            } else {
                                retval += "DOUBLE PRECISION";
                            }
                        } else {
                            if (length > 9) {
                                retval += "BIGINT";
                            } else {
                                if (length < 5) {
                                    retval += "SMALLINT";
                                } else {
                                    retval += "INTEGER";
                                }
                            }
                        }

                    } else {
                        retval += "DOUBLE PRECISION";
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length < 1 || length >= AbstractDatabase.CLOB_LENGTH) {
                    retval += "TEXT";
                    canHaveDefaultValue = false;
                } else if (length <= 2000) {
                    retval += "VARCHAR(" + length + ")";
                } else {
                    if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                        retval += "VARCHAR(" + length + ")";
                    } else {
                        retval += "TEXT";
                        canHaveDefaultValue = false;
                    }
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval += "BYTEA";
                canHaveDefaultValue = false;
                break;
            default:
                retval += "TEXT";
                canHaveDefaultValue = false;
                break;
        }

        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            retval += " DEFAULT " + renderDefault(v);
        }

        if (!v.isNullable()) {
            retval += " NOT NULL";
        }

        if (addCr) {
            retval += Const.CR;
        }

        return retval;
    }

    /**
     * 渲染默认。
     */
    protected String renderDefault(ColumnMetaData column) {
        int type = column.getType();
        if (type == ColumnMetaData.TYPE_TIMESTAMP || type == ColumnMetaData.TYPE_TIME || type == ColumnMetaData.TYPE_DATE) {
            return "CURRENT_TIMESTAMP";
        }
        String value = column.getDefaultValue();
        return value.startsWith("'") ? value : "'" + value + "'";
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add("COMMENT ON TABLE " + getQuotedSchemaTableCombination(td.getSchemaName(), td.getTableName())
                    + " IS " + PostgreSqlUtils.quoteLiteral(td.getRemarks()) + " ");
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add(
                        "COMMENT ON COLUMN " + getQuotedSchemaTableCombination(td.getSchemaName(), td.getTableName())
                                + "." + PostgreSqlUtils.quoteIdentifier(cd.getFieldName()) + " IS "
                                + PostgreSqlUtils.quoteLiteral(cd.getRemarks()) + " ");
            }
        }

        return results;
    }

    /**
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        int length = v.getLength();
        int type = v.getType();
        boolean canCreateIndex = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
            case ColumnMetaData.TYPE_DATE:
            case ColumnMetaData.TYPE_BOOLEAN:
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length < 1 || length >= AbstractDatabase.CLOB_LENGTH) {
                    canCreateIndex = false;
                } else if (length > 2000) {
                    canCreateIndex = false;
                }
                break;
            default:
                canCreateIndex = false;
                break;
        }


        return canCreateIndex;
    }

}
