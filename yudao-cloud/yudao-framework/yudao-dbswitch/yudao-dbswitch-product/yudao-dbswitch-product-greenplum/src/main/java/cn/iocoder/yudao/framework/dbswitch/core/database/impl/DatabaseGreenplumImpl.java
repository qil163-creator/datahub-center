package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.database.constant.PostgresqlConst;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.GreenplumRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresUtils;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumAddColumn;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支持Greenplum数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseGreenplumImpl extends AbstractDatabase implements IDatabaseInterface {

    /** Greenplum 原生关系目录；仅采用本产品已有 obj/col_description 机制。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT CASE WHEN pg_catalog.obj_description(c.oid,'pg_class') IS NULL THEN 0 ELSE 1 END,"
                + "COUNT(a.attnum),COUNT(pg_catalog.col_description(c.oid,a.attnum)) "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "WHERE n.nspname=? AND c.relname=? AND c.relkind='r' GROUP BY c.oid";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Greenplum 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("Greenplum 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** Greenplum 独立使用其原生 schema USAGE 检查，不从 PostgreSQL 产品继承能力。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
            statement.setString(1, namespace);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Greenplum 命名空间不存在", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new SQLException("Greenplum 命名空间 USAGE 被拒绝", "42501");
            }
        }
    }

    /** 只读取账号角色目录，不把超级用户或角色成员关系当成业务表写权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT rolname,rolsuper FROM pg_catalog.pg_roles WHERE rolname=current_user")) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("Greenplum 当前账号目录证据缺失");
            }
        }
    }

    /** 使用原生基本表目录和双重行上限，不读取无界 JDBC getTables 结果。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<TableDescription>();
        String sql = "SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind='r' ORDER BY c.relname LIMIT ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setInt(2, maxTables + 1);
            statement.setMaxRows(maxTables + 1);
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("Greenplum 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription();
                    table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE");
                    result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("Greenplum 诊断连接、物理命名空间和有界参数不能为空");
        }
    }

    private static Set<String> systemSchemas = new HashSet<>();

    private static final String SHOW_CREATE_VIEW_SQL =
            "select pg_get_viewdef('\"%s\".\"%s\"', true)";

    static {
        systemSchemas.add("gp_toolkit");
        systemSchemas.add("pg_aoseg");
        systemSchemas.add("information_schema");
        systemSchemas.add("pg_catalog");
        systemSchemas.add("pg_bitmapindex");
    }

    /**
     * 初始化 {@code DatabaseGreenplumImpl}及其父类状态。
     */
    public DatabaseGreenplumImpl() {
        super(ProductTypeEnum.GREENPLUM.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.GREENPLUM;
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.GREENPLUM) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return GreenplumRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.GREENPLUM) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.GREENPLUM) {
            return super.keyComponentProjection(alias, column, maxBytes);
        }
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid key byte bound");
        }
        return integerProjection(alias, column, Math.min(maxBytes, 21));
    }

    /**
     * 生成整数值的查询投影。
     */
    private static IntegerProjection integerProjection(String alias, String column, int maxBytes) {
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, column);
        String text = "CAST(" + field + " AS pg_catalog.text)";
        return new IntegerProjection("CASE WHEN pg_catalog.octet_length(" + text + ") <= " + maxBytes
                + " THEN " + text + " ELSE NULL END", "NULLIF(" + field + ", " + field + ")");
    }

    /**
     * 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。
     */
    @Override
    public BoundedTextKeyProjection textKeyProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.GREENPLUM) {
            return super.textKeyProjection(alias, column, maxBytes);
        }
        return PgCatalogKeyProjections.text(ProductTypeEnum.GREENPLUM, alias, column, maxBytes);
    }

    /**
     * 生成文本或整数键分量的查询投影。
     */
    @Override
    public BoundedKeyComponentProjection textAndIntegerKeyComponentProjection(Connection connection,
            String alias, String column, int maxBytes) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.GREENPLUM) {
            return super.textAndIntegerKeyComponentProjection(connection, alias, column, maxBytes);
        }
        return PgCatalogKeyProjections.mixed(ProductTypeEnum.GREENPLUM, alias, column, maxBytes);
    }

    private record IntegerProjection(String valueSql, String typeSql)
            implements BoundedIntegerKeyProjection, BoundedKeyComponentProjection {

        /**
         * 整数的十进制表示也是精确且有界的元组组件。
         */
        @Override
        public String groupSql() {
            return valueSql;
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            if (!metadata.isSigned(column)) {
                return 0;
            }
            String nativeType = metadata.getColumnTypeName(column);
            int jdbc = metadata.getColumnType(column);
            if (nativeType == null) {
                return 0;
            }
            return switch (nativeType) {
                case "int2" -> jdbc == Types.SMALLINT ? jdbc : 0;
                case "int4" -> jdbc == Types.INTEGER ? jdbc : 0;
                case "int8" -> jdbc == Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
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
        return cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables.ddl(connection, schemaName,
                tableName, 30);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_VIEW_SQL, schemaName, tableName);
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null && rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    /**
     * 建表语句尾部追加分布键（移植自 dbswitch 2.0.2 的 GreenplumMetadataQueryProvider）：
     * 有主键时按主键分布，避免 Greenplum 把第一列当默认分布键；无主键时不追加，保持服务器默认策略。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            java.util.Map<String, String> tblProperties, String hashFiled) {
        List<String> keys = primaryKeys == null ? List.of() : primaryKeys;
        if (tblProperties != null && tblProperties.containsKey("greenplum.distributedKeys")) {
            var values = cn.hutool.json.JSONUtil.parseArray(tblProperties.get("greenplum.distributedKeys"));
            if (values.isEmpty() || values.size() > 1024 || values.stream()
                    .anyMatch(value -> !(value instanceof String name)
                            || name.isBlank() || name.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("Greenplum distribution requires physical column names");
            }
            keys = values.stream().map(String.class::cast).toList();
            if (new HashSet<>(
                    keys).size() != keys.size() || primaryKeys != null && !primaryKeys.isEmpty() && !primaryKeys.containsAll(
                    keys)) {
                throw new IllegalArgumentException(
                        "Distribution keys must be distinct and a subset of the primary key when one is present");
            }
        }
        if (!keys.isEmpty()) {
            builder.append("\n DISTRIBUTED BY (").append(keys.stream()
                            .map(PostgreSqlUtils::quoteIdentifier).collect(java.util.stream.Collectors.joining(", ")))
                    .append(")");
            // 生成的 CREATE 必须将每个请求的物理分布键绑定到真实的 AST 列。
            cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parseMigrationHook(ProductTypeEnum.GREENPLUM,
                    builder.toString());
        }
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        return this.getSelectSqlColumnMeta(connection, "SELECT * FROM (" + sql + ") dbswitch_metadata WHERE 1=2");
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables.table(
                schemaName, tableName) + " WHERE 1=2";
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
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
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
                    if (null != pks && pks.contains(fieldname)) {
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
                retval += " TEXT";
                canHaveDefaultValue = false;
                break;
        }

        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                if (v.getDefaultValue().startsWith("'")) {
                    retval += " DEFAULT " + v.getDefaultValue();
                } else {
                    retval += " DEFAULT '" + v.getDefaultValue() + "'";
                }
            } /*else {
				retval += " DEFAULT DEFAULT SYSDATE";
			}*/
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
     * 获取添加字段的sql。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.GREENPLUM) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return GreenplumAddColumn.dialectSql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        String relation = PostgreSqlUtils.getFullyQualifiedTableName(td.getSchemaName(), td.getTableName(), true);
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add("COMMENT ON TABLE " + relation + " IS " + PostgreSqlUtils.quoteLiteral(td.getRemarks()) + " ");
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add("COMMENT ON COLUMN " + relation + "." + PostgreSqlUtils.quoteIdentifier(cd.getFieldName())
                        + " IS " + PostgreSqlUtils.quoteLiteral(cd.getRemarks()) + " ");
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
