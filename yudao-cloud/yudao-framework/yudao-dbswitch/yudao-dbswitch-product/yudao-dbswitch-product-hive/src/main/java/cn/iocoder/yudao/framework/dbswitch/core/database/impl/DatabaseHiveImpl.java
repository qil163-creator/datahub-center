package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import org.springframework.util.CollectionUtils;
import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.UuidUtils;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveAddColumn;
import org.apache.commons.lang3.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.stream.Collectors;

public class DatabaseHiveImpl extends AbstractDatabase implements IDatabaseInterface {

    /** Hive 3.1.3 TGetTablesReq 无目录数量或执行期限参数，不能以客户端停止读取冒充有界目录。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schema, int maxTables,
            int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.HIVE) {
            return super.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
        }
        if (schema == null || schema.isBlank() || maxTables < 1 || maxTables > 10000
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("目录读取必须指定 schema、数量及超时上限");
        }
        throw new java.sql.SQLFeatureNotSupportedException(
                "Hive 3.1.3 GetTables 无枚举数量或执行期限参数；FetchResults.maxRows 仅限制接收批次，"
                        + "不能限制服务端目录物化；完整有界目录不可由现有协议保证。接收采样须使用显式不完整结果与传输期限的新契约", "0A000");
    }

    /** 在独占诊断连接上实际进入数据库，不依赖驱动可能忽略的 setSchema。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("目标数据库和有效超时不能为空");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("USE `" + namespace.replace("`", "``") + "`");
        }
    }

    /** 读取当前主体的授权目录；空目录不代表角色授权不存在，不据此推断任意表权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticTimeout(timeoutSeconds);
        probeNamespace(connection, namespace, timeoutSeconds);
        String principal;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery("SELECT current_user()")) {
                if (!rows.next() || (principal = rows.getString(1)) == null || principal.isBlank()
                        || principal.length() > 256 || principal.indexOf('\0') >= 0) {
                    throw new SQLException("Hive 未返回有效当前主体", "HY000");
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setFetchSize(128);
            try (var rows = statement.executeQuery("SHOW GRANT USER " + hiveIdentifier(principal))) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 4096) { throw new SQLException("当前主体授权目录超过诊断接收上限", "54000"); }
                }
            }
        }
    }

    /** 精确对象的 FORMATTED 描述只读取目录；接收上限不是服务端枚举上限。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticTimeout(timeoutSeconds);
        String reference = hiveIdentifier(namespace) + "." + hiveIdentifier(table);
        int columns = 0, comments = 0, received = 0;
        boolean inColumns = true, inParameters = false, tableComment = false, parametersSeen = false;
        var names = new java.util.HashSet<String>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setFetchSize(128);
            try (var rows = statement.executeQuery("DESCRIBE FORMATTED " + reference)) {
                while (rows.next()) {
                    if (++received > 8192) { throw new SQLException("对象描述超过诊断接收上限", "54000"); }
                    String name = rows.getString(1), type = rows.getString(2), comment = rows.getString(3);
                    name = name == null ? "" : name.trim();
                    type = type == null ? "" : type.trim();
                    if (name.startsWith("#")) {
                        if (!name.startsWith("# col_name")) { inColumns = false; }
                        continue;
                    }
                    if ("Table Parameters:".equals(name)) {
                        inColumns = false; inParameters = true; parametersSeen = true; continue;
                    }
                    if (inColumns && !name.isEmpty() && !type.isEmpty()) {
                        if (!names.add(name) || ++columns > 4096) {
                            throw new SQLException("列描述不唯一或超过诊断上限", "54000");
                        }
                        if (comment != null && !comment.isBlank()) { comments++; }
                    }
                    if (inParameters && !name.isEmpty()) { inParameters = false; }
                    if (inParameters && "comment".equals(type) && comment != null && !comment.isBlank()) {
                        tableComment = true;
                    }
                }
            }
        }
        if (columns == 0 || !parametersSeen) { throw new SQLException("Hive 对象描述缺少完整列或表属性证据", "HY000"); }
        return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns, tableComment, comments);
    }

    private static String hiveIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Hive 诊断需要有效的物理标识符");
        }
        return "`" + value.replace("`", "``") + "`";
    }

    private static void diagnosticTimeout(int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 60) { throw new IllegalArgumentException("诊断超时必须介于 1 到 60 秒"); }
    }

    private static final boolean HIVE_USE_CTAS = false;

    /**
     * 初始化 {@code DatabaseHiveImpl}及其父类状态。
     */
    public DatabaseHiveImpl() {
        super(ProductTypeEnum.HIVE.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.HIVE;
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        return integerProjection(alias, column, 21, false);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 256) {
            throw new IllegalArgumentException("Invalid Hive integer byte budget");
        }
        return integerProjection(alias, column, Math.min(maxBytes, 21), true);
    }

    /**
     * 生成整数值的查询投影。
     */
    private static IntegerProjection integerProjection(String alias, String column, int maxBytes, boolean binary) {
        String field = (alias == null ? "" : quoteKey(alias) + ".") + quoteKey(column);
        String text = "CAST(" + field + " AS STRING)";
        String bounded = "CASE WHEN LENGTH(" + text + ") <= " + maxBytes + " THEN " + text + " ELSE NULL END";
        // STRING 载荷供 JDBC getLong 使用；BINARY 载荷供驱动原生 byte[] 元组读取器使用。
        // 读取任何载荷前，必须先检查未经类型转换的原生 NULLIF 验证信息。
        return new IntegerProjection(binary ? "CAST(" + bounded + " AS BINARY)" : bounded,
                "NULLIF(" + field + ", " + field + ")", bounded);
    }

    private record IntegerProjection(String valueSql, String typeSql,
                                     String groupSql) implements BoundedIntegerKeyProjection {

        /**
         * 说明当前查询路径是否要求显式添加外层数量限制。
         */
        @Override
        public boolean requiresExplicitOuterLimit() {
            return true;
        }

        /**
         * 读取字节数组。
         */
        @Override
        public byte[] readBytes(ResultSet rows, int column) throws SQLException {
            // Hive 4.1 即使对 BINARY 也未实现 getBytes。不要经 getString 往返转换。
            if (rows.getMetaData().getColumnType(column) != Types.BINARY) {
                throw new SQLException("Hive component payload must be native BINARY", "22000");
            }
            Object value = rows.getObject(column);
            if (value == null) {
                return null;
            }
            if (!(value instanceof byte[] bytes) || bytes.length > 21) {
                throw new SQLException("Invalid bounded Hive integer payload", "22000");
            }
            return bytes;
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            String nativeType = metadata.getColumnTypeName(column);
            int jdbc = metadata.getColumnType(column);
            if (nativeType == null || !metadata.isSigned(column)) {
                return 0;
            }
            return switch (nativeType.toLowerCase(Locale.ROOT)) {
                case "smallint" -> jdbc == Types.SMALLINT ? jdbc : 0;
                case "int" -> jdbc == Types.INTEGER ? jdbc : 0;
                case "bigint" -> jdbc == Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        return "CAST(" + quoteKey(column) + " AS STRING)";
    }

    /**
     * 引用键。
     */
    private static String quoteKey(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, name);
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String sql = "SHOW CREATE TABLE " + getQuotedSchemaTableCombination(schemaName, tableName);
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            String value = rs.getString(1);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return String.join("\n", result);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return getTableDDL(connection, schemaName, tableName);
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        getQuotedSchemaTableCombination(schemaName, tableName); // 访问元数据前先校验。
        // 物理元数据使用 metastore，不接管调用方的事务管理器。
        // 严格 DDL 重建仍使用 HiveNativeCatalog.read 及其仅限 TEXTFILE 的配置。
        try {
            return HiveNativeCatalog.readMetadata(connection, schemaName, tableName, 30);
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
        return queryTableColumnMeta(connection, schemaName, tableName);
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
            String tableName) {
        return Collections.emptyList();
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s LIMIT 1", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain %s", sql.replace(";", ""));
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    @Override
    public String quoteIdentifier(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Hive identifier must be nonempty and contain no NUL");
        }
        return super.quoteIdentifier(name);
    }

    /** 仅支持普通外部 TEXTFILE。此调用不查找实际表。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.HIVE) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return HiveAddColumn.dialectSql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData column, List<String> keys, boolean autoIncrement, boolean addCr,
            boolean withRemarks) {
        if (autoIncrement) {
            throw new UnsupportedOperationException("Hive does not implement generated auto-increment columns");
        }
        String type = switch (column.getSqlType()) {
            case Types.TINYINT -> "SMALLINT";
            case Types.SMALLINT -> "INT";
            case Types.INTEGER -> "BIGINT";
            case Types.BIGINT -> column.getType() == ColumnMetaData.TYPE_BIGNUMBER ? "DECIMAL(20,0)" : "BIGINT";
            case Types.NUMERIC, Types.DECIMAL -> {
                if (column.getLength() < 1 || column.getLength() > 38 || column.getPrecision() < 0 || column.getPrecision() > column.getLength()) {
                    throw new UnsupportedOperationException(
                            "Hive decimal requires explicit precision up to 38 and a valid scale");
                }
                yield "DECIMAL(" + column.getLength() + "," + column.getPrecision() + ")";
            }
            case Types.REAL -> "FLOAT";
            case Types.FLOAT, Types.DOUBLE -> "DOUBLE";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.DATE -> "DATE";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> "STRING";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BINARY";
            default -> throw new UnsupportedOperationException("Hive column requires an explicit lossless mapping");
        };
        String result = quoteIdentifier(column.getName()) + " " + type;
        if (withRemarks && StringUtils.isNotBlank(column.getRemarks())) {
            result += " COMMENT '" + column.getRemarks().replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        return result + (addCr ? Const.CR : "");
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
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        return false;
    }

    /**
     * 为字段描述列表补充索引信息。
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
    }

    /**
     * 读取表的索引定义。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schemaName, String tableName) {
        return List.of();
    }

    /**
     * 为hive定制的获取联邦建表导数SQL列表。
     */
    @Override
    public List<String> getCreateTableSqlList(List<ColumnDescription> fieldNames, List<String> primaryKeys,
            String schemaName, String tableName, String tableRemarks, boolean autoIncr,
            Map<String, String> tblProperties) {
        if (cn.iocoder.yudao.framework.dbswitch.product.hive.HiveMaterializedTableDdl.hasHiveEvidence(fieldNames)) {
            return List.of(cn.iocoder.yudao.framework.dbswitch.product.hive.HiveMaterializedTableDdl.create(
                    this, fieldNames, schemaName, tableName, tableRemarks, autoIncr));
        }
        List<String> sqlLists = new ArrayList<>();
        //String tmpTableName = "tmp_" + UuidUtils.generateUuid();
        String createTableSql = GenerateSqlUtils.getDDLCreateTableSQL(this, fieldNames, primaryKeys, schemaName,
                tableName, true, tableRemarks, autoIncr, tblProperties);
        sqlLists.add(createTableSql);
		/*if (HIVE_USE_CTAS) {
			String createAsTableSql = String.format("CREATE TABLE `%s`.`%s` STORED AS ORC AS (SELECT * FROM `%s`.`%s`)",
					schemaName, tableName, schemaName, tmpTableName);
			sqlLists.add(createAsTableSql);
		} else {
			String createAsTableSql = GenerateSqlUtils.getDDLCreateTableSQL(this, fieldNames, primaryKeys, schemaName,
					tableName, true, tableRemarks, autoIncr, null);
			sqlLists.add(createAsTableSql);
			String selectColumns = fieldNames.stream()
					.map(s -> String.format("`%s`", s.getFieldName()))
					.collect(Collectors.joining(","));
			String insertIntoSql = String.format("INSERT INTO `%s`.`%s` SELECT %s FROM `%s`.`%s`",
					schemaName, tableName, selectColumns, schemaName, tmpTableName);
			sqlLists.add(insertIntoSql);
		}
		String dropTmpTableSql = String.format("DROP TABLE IF EXISTS `%s`.`%s`", schemaName, tmpTableName);
		sqlLists.add(dropTmpTableSql);*/
        return sqlLists;
    }

    /**
     * 在建表语句中追加主键定义片段 ", PRIMARY KEY (...)"；主键列表为空时不追加。
     */
    @Override
    public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
        // HIVE表没有主键
    }

    /**
     * 建表语句后置钩子：在字段定义之后追加方言相关内容（表注释、存储引擎/属性、分布键等）， 默认无操作，由需要的子类覆盖。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
		/*if (!CollectionUtils.isEmpty(tblProperties)) {
			builder.append("\r\n");
			builder.append("STORED BY 'org.apache.hive.storage.jdbc.JdbcStorageHandler'");
			builder.append("\r\n");
			builder.append("TBLPROPERTIES (");
			builder.append(
					tblProperties.entrySet().stream()
							.map(entry -> String.format("\t\t'%s' = '%s'", entry.getKey(), entry.getValue()))
							.collect(Collectors.joining(",\n")));
			builder.append(")");
		} else {*/
        builder.append("\r\n");
        builder.append("STORED AS ORC");
        /*}*/
    }
}
