package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlAddColumn;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.StringUtil;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支持MySQL数据库的元信息实现
 * <p>
 * 职责：{@link AbstractDatabase} 的 MySQL 方言实现，是各数据库实现类的典型代表。
 * 主要差异点：
 * <ul>
 * <li>标识符使用反引号（`）包裹；</li>
 * <li>表清单、主键、字段默认值等元数据直接查 information_schema，而非 JDBC 元数据接口；</li>
 * <li>建表 DDL 通过 SHOW CREATE TABLE/VIEW 获取；</li>
 * <li>SQL 合法性探测使用 explain；字段元数据探测在 SQL 尾部追加 LIMIT 0,1；</li>
 * <li>getFieldDefinition 按通用类型映射为 MySQL 字段类型（DATETIME/DECIMAL/VARCHAR/TEXT 等）。</li>
 * </ul>
 * 由 MySQL 产品描述符创建；MariaDB/TiDB/GBase8a 等兼容库复用本实现的子类
 *
 * @author jrl
 */
@Slf4j
public class DatabaseMysqlImpl extends AbstractDatabase implements IDatabaseInterface {

    private static final String TABLE_LIST_SQL = "SELECT `TABLE_SCHEMA`,`TABLE_NAME`,`TABLE_TYPE`,`TABLE_COMMENT` "
            + "FROM `information_schema`.`TABLES` WHERE `TABLE_SCHEMA` = ?";

    /**
     * 初始化 {@code DatabaseMysqlImpl}及其父类状态。
     */
    public DatabaseMysqlImpl() {
        super(ProductTypeEnum.MYSQL.getDriveClassName());
    }

    /**
     * 初始化 {@code DatabaseMysqlImpl}及其父类状态。
     */
    public DatabaseMysqlImpl(String driverClassName) {
        super(driverClassName);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.MYSQL;
    }

    /** 读取当前账号授权目录，并由服务器实际验证目标数据库的进入权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            super.probeAccountPrivileges(connection, namespace, timeoutSeconds);
            return;
        }
        mysqlAccountPrivileges(connection, namespace, timeoutSeconds);
    }

    /** 各产品必须显式声明采用此机制；单测及真实产品验证状态分别记录，不能因继承自动启用。 */
    protected final void mysqlAccountPrivileges(Connection connection, String namespace, int timeoutSeconds)
            throws SQLException {
        mysqlNamespace(connection, namespace, timeoutSeconds);
        mysqlCurrentGrants(connection, timeoutSeconds);
    }

    protected final List<String> mysqlCurrentGrants(Connection connection, int timeoutSeconds) throws SQLException {
        diagnosticArguments(connection, timeoutSeconds);
        List<String> grants = new ArrayList<>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(1001);
            try (var rows = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER")) {
                while (rows.next()) {
                    String grant = rows.getString(1);
                    if (grant == null || grant.isBlank() || grants.size() == 1000) {
                        throw new SQLException("账号授权目录不完整或超过诊断上限");
                    }
                    grants.add(grant);
                }
            }
        }
        if (grants.isEmpty()) {
            throw new SQLException("账号授权目录未返回服务器证据");
        }
        return grants;
    }

    /** 精确读取基本表的注释数量，不返回注释内容，也不读取业务行。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.probeExtendedProperties(connection, namespace, table, timeoutSeconds);
        }
        return mysqlExtendedProperties(connection, namespace, table, timeoutSeconds);
    }

    /** 共用目录 SQL 由产品显式采用，不从协议兼容性推断运行验证已经通过。 */
    protected final cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic mysqlExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticArguments(connection, timeoutSeconds);
        RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, namespace);
        RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, table);
        String sql = "SELECT t.TABLE_COMMENT,COUNT(c.COLUMN_NAME),"
                + "COALESCE(SUM(CASE WHEN c.COLUMN_COMMENT<>'' THEN 1 ELSE 0 END),0) "
                + "FROM information_schema.TABLES t LEFT JOIN information_schema.COLUMNS c "
                + "ON BINARY c.TABLE_SCHEMA=BINARY t.TABLE_SCHEMA AND BINARY c.TABLE_NAME=BINARY t.TABLE_NAME "
                + "WHERE BINARY t.TABLE_SCHEMA=BINARY ? AND BINARY t.TABLE_NAME=BINARY ? "
                + "AND t.TABLE_TYPE='BASE TABLE' GROUP BY t.TABLE_SCHEMA,t.TABLE_NAME,t.TABLE_COMMENT";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, namespace);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("目标物理表不存在或不可见", "42S02");
                }
                String comment = rows.getString(1);
                int columns = rows.getInt(2);
                int comments = rows.getInt(3);
                if (rows.wasNull() || comment == null || columns < 1 || comments < 0 || comments > columns || rows.next()) {
                    throw new SQLException("表列注释目录证据不完整或不唯一");
                }
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(
                        columns, !comment.isEmpty(), comments);
            }
        }
    }

    /** 仅证明 binlog 配置和服务器返回的全局复制授权，不创建流或修改服务器配置。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.probeCdcReadiness(connection, timeoutSeconds);
        }
        boolean logsReady = mysqlBinlogConfigurationReady(connection, timeoutSeconds);
        Set<String> privileges = mysqlObservedGlobalPrivileges(connection, timeoutSeconds);
        boolean replication = privileges.contains("ALL PRIVILEGES")
                || privileges.containsAll(List.of("REPLICATION SLAVE", "REPLICATION CLIENT"));
        if (!replication && connection.getMetaData().getDatabaseMajorVersion() >= 8) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT CURRENT_ROLE()")) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new SQLException("活动角色证据缺失");
                    }
                    if (!"NONE".equals(rows.getString(1))) {
                        throw new java.sql.SQLFeatureNotSupportedException(
                                "MySQL 活动角色的递归复制授权尚未验证，不能据直接授权判定未授权", "0A000");
                    }
                }
            }
        }
        return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(logsReady, replication);
    }

    /** 仅采信服务器生成的全局授权行，包含服务器已展开的角色；不自行推断角色继承或动态权限等价性。 */
    protected final Set<String> mysqlObservedGlobalPrivileges(Connection connection, int timeoutSeconds) throws SQLException {
        Set<String> privileges = new LinkedHashSet<>();
        for (String grant : mysqlCurrentGrants(connection, timeoutSeconds)) {
            int boundary = grant.indexOf(" ON *.* TO ");
            if (grant.startsWith("GRANT ") && boundary > 6) {
                Collections.addAll(privileges, grant.substring(6, boundary).split(", "));
            }
        }
        return privileges;
    }

    /** 已在 MySQL 和 MariaDB 原生引擎验证的同名 binlog 变量。 */
    protected final boolean mysqlBinlogConfigurationReady(Connection connection, int timeoutSeconds) throws SQLException {
        diagnosticArguments(connection, timeoutSeconds);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery(
                    "SELECT @@GLOBAL.log_bin,@@GLOBAL.binlog_format,@@GLOBAL.binlog_row_image")) {
                if (!rows.next()) {
                    throw new SQLException("binlog 配置未返回服务器证据");
                }
                boolean enabled = rows.getBoolean(1);
                if (rows.wasNull()) {
                    throw new SQLException("binlog 开关证据缺失");
                }
                String format = rows.getString(2);
                String image = rows.getString(3);
                if (format == null || image == null) {
                    throw new SQLException("binlog 配置证据缺失");
                }
                return enabled && "ROW".equals(format) && "FULL".equals(image);
            }
        }
    }

    /** 临时表与普通业务表隔离；只能删除 TEMPORARY 对象，事务验证固定使用 InnoDB。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.temporaryTableDiagnosticPlan(randomName);
        }
        return mysqlTemporaryDiagnosticPlan(randomName);
    }

    /** 原生验证后由兼容产品显式调用，不自动继承启用。 */
    protected final cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan mysqlTemporaryDiagnosticPlan(
            String randomName) {
        if (randomName == null || !randomName.matches("dbswitch_diag_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("组件生成的诊断临时表名不合法");
        }
        String relation = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, randomName);
        String renamed = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, randomName + "_renamed");
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan(
                "CREATE TEMPORARY TABLE " + relation + " (probe_id INTEGER PRIMARY KEY, probe_value VARCHAR(40)) ENGINE=InnoDB",
                relation, "ALTER TABLE " + relation + " ADD COLUMN probe_extra INTEGER",
                "DROP TEMPORARY TABLE IF EXISTS " + relation,
                "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (4,'upserted') ON DUPLICATE KEY UPDATE probe_value='upserted'",
                null, Integer.MAX_VALUE, "TRUNCATE TABLE " + relation,
                "ALTER TABLE " + relation + " RENAME TO " + renamed, renamed,
                "DROP TEMPORARY TABLE IF EXISTS " + renamed);
    }

    private static void diagnosticArguments(Connection connection, int timeoutSeconds) {
        if (connection == null || timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("诊断连接和有界执行期限不能为空");
        }
    }

    /**
     * 借用当前连接读取当前数据库已用字节；不改变连接状态、不推断磁盘总量。
     */
    @Override
    public long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.queryDatabaseUsedBytes(connection, timeoutSeconds);
        }
        return queryVisibleTableStorageBytes(connection, timeoutSeconds);
    }

    /** MySQL/MariaDB 共用的目录统计机制；各产品必须显式启用并验证。 */
    protected final long queryVisibleTableStorageBytes(Connection connection, int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive query timeout is required");
        }
        if (connection.getCatalog() == null || connection.getCatalog().isBlank()) {
            throw new SQLException("A selected database is required for capacity collection");
        }
        try (var statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?")) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, connection.getCatalog());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Database capacity result is missing");
                }
                var bytes = rows.getBigDecimal(1);
                if (bytes == null || bytes.signum() < 0) {
                    throw new SQLException("Database capacity result is invalid");
                }
                return bytes.longValueExact();
            }
        }
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String relationReference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.queryRelationFingerprint(connection, relationReference);
        }
        return MysqlRelationIdentity.fingerprint(connection, relationReference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String tableAlias, String columnName) {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.integerKeyProjection(tableAlias, columnName);
        }
        return mysqlSignedIntegerProjection(tableAlias, columnName);
    }

    /** 可复用 SQL 机制；产品必须在原生驱动验证后显式启用。 */
    protected final BoundedIntegerKeyProjection mysqlSignedIntegerProjection(String tableAlias, String columnName) {
        return mysqlSignedIntegerProjection(tableAlias, columnName, false);
    }

    /** OceanBase 会扩宽 CASE 整数凭据类型；NULLIF 保留原生源类型。 */
    protected final BoundedIntegerKeyProjection mysqlSignedIntegerProjection(String tableAlias, String columnName,
            boolean useNullIfWitness) {
        String column = (tableAlias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL,
                tableAlias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, columnName);
        return new MysqlIntegerKeyProjection("LEFT(CAST(" + column + " AS CHAR CHARACTER SET ascii), 21)",
                useNullIfWitness ? "NULLIF(" + column + ", " + column + ")" : "CASE WHEN FALSE THEN " + column + " ELSE NULL END");
    }

    /**
     * 创建 MySQL 有符号整数键分量的比较投影。
     */
    protected final BoundedKeyComponentProjection mysqlSignedIntegerComponent(String tableAlias, String columnName,
            int maxBytes) {
        return mysqlSignedIntegerComponent(mysqlSignedIntegerProjection(tableAlias, columnName), maxBytes);
    }

    /**
     * 创建 MySQL 有符号整数键分量的比较投影。
     */
    protected final BoundedKeyComponentProjection mysqlSignedIntegerComponent(BoundedIntegerKeyProjection integer,
            int maxBytes) {
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid key byte limit");
        }
        return new MysqlIntegerKeyProjection("CASE WHEN OCTET_LENGTH(" + integer.valueSql() + ") <= " + maxBytes
                + " THEN " + integer.valueSql() + " ELSE NULL END", integer.typeSql());
    }

    private record MysqlIntegerKeyProjection(String valueSql, String typeSql)
            implements BoundedIntegerKeyProjection, BoundedKeyComponentProjection {

        /**
         * 整数的十进制表示也是精确且有界的元组组件。
         */
        @Override
        public String groupSql() {
            return "CAST(" + valueSql + " AS BINARY)";
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            return signedIntegerType(metadata, column);
        }
    }

    /**
     * 根据位宽构建有符号整数类型定义。
     */
    private static int signedIntegerType(ResultSetMetaData metadata, int column) throws SQLException {
        // CASE/NULLIF 保留原生整数类型。不得从 CAST 的文本结果反推整数，
        // 或把 BIGINT UNSIGNED、DECIMAL、BIT、布尔/文本键有损压入有符号 int64 协议。
        if (!metadata.isSigned(column)) {
            return 0;
        }
        int type = metadata.getColumnType(column);
        String nativeType = metadata.getColumnTypeName(column);
        return switch (type) {
            case Types.SMALLINT -> "SMALLINT".equalsIgnoreCase(nativeType) ? type : 0;
            case Types.INTEGER ->
                    "INT".equalsIgnoreCase(nativeType) || "INTEGER".equalsIgnoreCase(nativeType) ? type : 0;
            case Types.BIGINT -> "BIGINT".equalsIgnoreCase(nativeType) ? type : 0;
            default -> 0;
        };
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String tableAlias, String columnName, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.keyComponentProjection(tableAlias, columnName, maxBytes);
        }
        var text = textKeyProjection(tableAlias, columnName, maxBytes);
        return new MysqlKeyComponentProjection(text.valueSql(), text.typeSql(), text.groupSql(), text);
    }

    private record MysqlKeyComponentProjection(String valueSql, String typeSql, String groupSql,
                                               BoundedTextKeyProjection text) implements BoundedKeyComponentProjection {

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            int integer = signedIntegerType(metadata, column);
            return integer != 0 ? integer : text.jdbcType(metadata, column);
        }
    }

    /** 为独立且显式启用的产品能力复用原生类型校验。 */
    protected final BoundedKeyComponentProjection mysqlTextAndIntegerComponent(BoundedTextKeyProjection text) {
        return new MysqlKeyComponentProjection(text.valueSql(), text.typeSql(), text.groupSql(), text);
    }

    /**
     * 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。
     */
    @Override
    public BoundedTextKeyProjection textKeyProjection(String tableAlias, String columnName, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            return super.textKeyProjection(tableAlias, columnName, maxBytes);
        }
        return mysqlExactTextProjection(tableAlias, columnName, maxBytes);
    }

    /** 仅由显式验证的产品共享 SQL 与类型契约。 */
    protected final BoundedTextKeyProjection mysqlExactTextProjection(String tableAlias, String columnName,
            int maxBytes) {
        return mysqlExactTextProjection(tableAlias, columnName, maxBytes, false);
    }

    /** TiDB NULLIF 将 ENUM/SET 转为 VARCHAR；空标量子查询保留原生元数据。 */
    protected final BoundedTextKeyProjection mysqlExactTextProjection(String tableAlias, String columnName,
            int maxBytes,
            boolean scalarTypeWitness) {
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid text key byte limit");
        }
        String column = (tableAlias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(
                ProductTypeEnum.MYSQL, tableAlias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, columnName);
        String identity = "CAST(CONVERT(" + column + " USING utf8mb4) AS BINARY)";
        // NULLIF 返回首参数的原生类型；CASE 会把 CHAR/ENUM 等聚合成 VARCHAR，不能用于证明。
        String group = "(CASE WHEN OCTET_LENGTH(" + identity + ") <= " + maxBytes
                + " THEN CAST(HEX(" + identity + ") AS CHAR(" + (maxBytes * 2)
                + ") CHARACTER SET ascii) ELSE NULL END) COLLATE ascii_bin";
        return new MysqlTextKeyProjection(identity, group,
                "CASE WHEN OCTET_LENGTH(" + identity + ") <= " + maxBytes + " THEN " + identity + " ELSE NULL END",
                scalarTypeWitness ? "(SELECT " + column + " WHERE FALSE)" : "NULLIF(" + column + ", " + column + ")",
                maxBytes);
    }

    private record MysqlTextKeyProjection(String identitySql, String groupSql, String valueSql, String typeSql,
                                          int maxBytes)
            implements BoundedTextKeyProjection {

        /**
         * 0 表示类型未经验证；不可由转成文本的结果反推源字段类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            int type = metadata.getColumnType(column);
            String nativeType = metadata.getColumnTypeName(column);
            if (type == Types.VARCHAR && "VARCHAR".equalsIgnoreCase(nativeType)) {
                return type;
            }
            if (type == Types.LONGVARCHAR && Set.of("TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT")
                    .contains(nativeType.toUpperCase(java.util.Locale.ROOT))) {
                return type;
            }
            return 0;
        }

        /**
         * 有界的精确值过滤表达式，返回方言安全常量，不依赖会话反斜杠转义或 collation。
         */
        @Override
        public String equalitySql(String value) {
            if (value == null) {
                throw new IllegalArgumentException("A non-null text key is required");
            }
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > maxBytes || !value.equals(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Invalid or oversized UTF-8 text key");
            }
            return identitySql + " = X'" + java.util.HexFormat.of().formatHex(bytes) + "'";
        }
    }

    /**
     * MySQL 的 schema 是 JDBC catalog。返回当前账号可见的数据库，保留驱动观察顺序；
     * JDBC URL 的默认数据库不是服务器上的完整目录列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        Set<String> schemas = new LinkedHashSet<>();
        try (ResultSet catalogs = connection.getMetaData().getCatalogs()) {
            while (catalogs.next()) {
                String schema = catalogs.getString(1);
                if (schema != null) {
                    schemas.add(schema);
                }
            }
            return new ArrayList<>(schemas);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 目录名是精确值，只有 JDBC 表名模式需要转义。 */
    @Override
    protected ResultSet openColumnMetadata(Connection connection, String schemaName,
            String tableName) throws SQLException {
        var metadata = connection.getMetaData();
        String pattern = tableName;
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            if (tableName.contains("%") || tableName.contains("_")) {
                throw new java.sql.SQLFeatureNotSupportedException(
                        "MySQL-family JDBC metadata pattern escaping is unavailable", "0A000");
            }
        } else {
            pattern = tableName.replace(escape, escape + escape)
                    .replace("%", escape + "%").replace("_", escape + "_");
        }
        return metadata.getColumns(schemaName, null, pattern, null);
    }

    /** 原生目录属于其他产品时，兼容委托必须禁用此能力。 */
    protected boolean supportsNativeTemporalMetadata() {
        return getDatabaseType() == ProductTypeEnum.MYSQL || getDatabaseType() == ProductTypeEnum.MARIADB;
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName) {
        return enrichTimestampPrecision(connection, schemaName, tableName,
                super.queryTableColumnMeta(connection, schemaName, tableName));
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName) {
        return enrichTimestampPrecision(connection, schemaName, tableName,
                super.queryTableColumnMetaOnly(connection, schemaName, tableName));
    }

    /**
     * Connector/J 对 DATETIME(p) 报告零小数位数；复用游标读取已共享的。
     */
    private List<ColumnDescription> enrichTimestampPrecision(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columns) {
        if (!supportsNativeTemporalMetadata() || columns.stream()
                .noneMatch(column -> column.getFieldType() == Types.TIMESTAMP)) {
            return columns;
        }
        try {
            // Connector/J 对 DATETIME(p) 报告零小数位数；复用游标读取已共享的
            // 有数量上限的产品目录读取器，缺失或隐藏的目录事实绝不视为零小数位数。
            var catalog = MysqlCursorMetadata.columns(connection, schemaName, tableName, 30);
            var names = columns.stream().map(ColumnDescription::getFieldName).toList();
            if (!names.equals(new ArrayList<>(catalog.keySet())) || new LinkedHashSet<>(names).size() != names.size()) {
                throw new SQLException("MySQL/MariaDB column list changed or catalog visibility is incomplete");
            }
            for (var column : columns) {
                if (column.getFieldType() != Types.TIMESTAMP) {
                    continue;
                }
                var nativeColumn = catalog.get(column.getFieldName());
                Integer precision = nativeColumn.temporalPrecision();
                if (!("datetime".equalsIgnoreCase(nativeColumn.dataType()) || "timestamp".equalsIgnoreCase(
                        nativeColumn.dataType()))
                        || precision == null || precision < 0 || precision > 6) {
                    throw new SQLException("MySQL/MariaDB native timestamp precision is unavailable or inconsistent");
                }
            }
            for (var column : columns) {
                if (column.getFieldType() == Types.TIMESTAMP) {
                    column.setScaleSize(catalog.get(column.getFieldName()).temporalPrecision());
                }
            }
            // 此处修正历史 ColumnMetaData 输入，不凭空构造模式快照凭据。
            return columns;
        } catch (SQLException unavailable) {
            throw new IllegalStateException("MySQL/MariaDB native timestamp precision could not be read", unavailable);
        }
    }

    /**
     * 查询表清单：直接查 information_schema.TABLES（可拿到表注释 TABLE_COMMENT），
     * 表类型统一归为 TABLE / VIEW 两类
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> ret = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(TABLE_LIST_SQL)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ret.add(tableDescription(rs));
                }
                return ret;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 用于两个独立选择的 MySQL/MariaDB 门面路由的原生选择器。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schemaName,
            int maxTables, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL && getDatabaseType() != ProductTypeEnum.MARIADB) {
            return super.queryCatalogTableList(connection, schemaName, maxTables, timeoutSeconds);
        }
        RestrictedSelectGuard.quotePhysicalIdentifier(getDatabaseType(), schemaName);
        if (maxTables < 1 || maxTables > 10_000 || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("Bounded MySQL/MariaDB catalog table listing required");
        }
        Map<String, TableDescription> tables = new java.util.TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(TABLE_LIST_SQL
                + " AND `TABLE_TYPE` IN ('BASE TABLE','VIEW')")) {
            statement.setString(1, schemaName);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxTables + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    TableDescription table = tableDescription(rows);
                    // 信息模式相等比较可能不区分大小写，输入别名不能作为策略键。
                    if (!schemaName.equals(table.getSchemaName()) || table.getTableName() == null
                            || table.getTableName().isBlank() || tables.putIfAbsent(table.getTableName(),
                            table) != null) {
                        throw new java.sql.SQLFeatureNotSupportedException(
                                "MySQL/MariaDB catalog returned an ambiguous physical name", "0A000");
                    }
                    if (tables.size() > maxTables) {
                        throw new IllegalArgumentException(
                                "Catalog table list exceeds its bound; refusing a truncated result");
                    }
                }
            }
        }
        return List.copyOf(tables.values());
    }

    /**
     * 在独占诊断会话验证目标命名空间的进入/使用权限；目录可见性不等于可访问。
     */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.MYSQL) {
            super.probeNamespace(connection, namespace, timeoutSeconds);
            return;
        }
        mysqlNamespace(connection, namespace, timeoutSeconds);
    }

    /** 只在调用方提供的独占诊断会话执行 USE，实际进入权限由服务器判定。 */
    protected final void mysqlNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticArguments(connection, timeoutSeconds);
        RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, namespace);
        // 独占诊断会话执行 USE，由服务器检查权限；不修改调用方或业务池中的连接。
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("USE " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, namespace));
        }
    }

    /**
     * 将目录查询行转换为数据库表描述。
     */
    private static TableDescription tableDescription(ResultSet row) throws SQLException {
        TableDescription table = new TableDescription();
        table.setSchemaName(row.getString("TABLE_SCHEMA"));
        table.setTableName(row.getString("TABLE_NAME"));
        table.setRemarks(row.getString("TABLE_COMMENT"));
        table.setTableType("VIEW".equalsIgnoreCase(row.getString("TABLE_TYPE")) ? "VIEW" : "TABLE");
        return table;
    }

    /**
     * 字段默认值/注释查询 SQL：查 information_schema.columns 的
     * column_default 与 column_comment 两列
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return "select column_name,column_default,column_comment AS column_comment from information_schema.columns where table_schema = '" + schemaName + "' and table_name = '" + tableName + "'";
    }

    /**
     * 补充索引信息：MySQL 的 catalog 即数据库名，因此这里 catalog 与 schema 都传 schemaName；
     * 与基类不同，查询失败时直接抛出异常
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
        // 补充一下索引信息
        try (ResultSet indexInfo = connection.getMetaData()
                .getIndexInfo(schemaName, schemaName, tableName, false, true)) {
            setIndex(columnDescriptions, indexInfo);
        } catch (SQLException e) {
            log.error(schemaName + "." + tableName + " setColumnIndexInfo error:" + e.getMessage());
            throw new RuntimeException(schemaName + "." + tableName + " setColumnIndexInfo error!!", e);
        }
    }

    /** MySQL 的 catalog 即数据库名，与 {@link #setColumnIndexInfo} 使用相同的 catalog/schema 映射。 */
    @Override
    protected ResultSet openIndexMetadata(Connection connection, String schemaName,
            String tableName) throws SQLException {
        return connection.getMetaData().getIndexInfo(schemaName, schemaName, tableName, false, true);
    }

    /**
     * 查询主键字段：按约束内的键顺序返回，不能用表字段顺序或无序集合替代。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
            String tableName) {
        List<String> ret = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' "
                + "ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ret.add(rs.getString("COLUMN_NAME"));
                }
            }
            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 获取建表 DDL：执行 SHOW CREATE TABLE，取结果集第 2 列（建表语句）
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
                            String value = rs.getString(2);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result.stream().findAny().orElse(null);
    }

    /**
     * 获取视图定义 DDL：执行 SHOW CREATE VIEW，取结果集第 2 列（视图定义语句）
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        String sql = "SHOW CREATE VIEW " + getQuotedSchemaTableCombination(schemaName, tableName);
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            String value = rs.getString(2);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result.stream().findAny().orElse(null);
    }

    /**
     * 解析查询 SQL 的字段元数据：在 SQL 尾部追加 LIMIT 0,1 只取一行，
     * 再通过结果集元数据解析字段信息
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s LIMIT 0,1", stripTerminalDelimiter(sql));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 表字段查询 SQL：WHERE 1=2 不取数据只取元信息，标识符用反引号
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * SQL 合法性探测语句：使用 explain 让 MySQL 只做执行计划分析、不实际执行
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain %s", stripTerminalDelimiter(sql));
    }

    /**
     * 移除 SQL 末尾的语句分隔符。
     */
    private static String stripTerminalDelimiter(String sql) {
        String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 全限定表名：MySQL 使用反引号包裹标识符
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return RestrictedSelectGuard.qualifiedTableName(getDatabaseType(), schemaName, tableName);
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    @Override
    public String quoteIdentifier(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(getDatabaseType(), name);
    }

    /**
     * 生成 MySQL 建表语句中的字段定义片段
     * <p>
     * 类型映射规则：
     * 时间戳→DATETIME、时间→TIME、日期→DATE、布尔→TINYINT；
     * 数值类：主键强制 BIGINT（可选 AUTO_INCREMENT）；整数按位数选 INT/BIGINT/DECIMAL(n)；
     * 有小数位的用 DECIMAL(长度,精度)（缺省 DECIMAL(20,10)）；
     * 字符串按长度选 CHAR(1)/VARCHAR(n)/TEXT/MEDIUMTEXT/LONGTEXT
     * （主键字符串限制为 VARCHAR(254) BINARY，兼容 MySQL5.6 主键长度上限）；
     * 二进制→LONGBLOB；未识别类型兜底 LONGTEXT。
     * 其他规则：TEXT/BLOB 系列不能带默认值；日期时间类字段不加 NOT NULL
     * （避免源端 0000-00-00 之类的零值导致插入失败），其默认值统一改为 CURRENT_TIMESTAMP；
     * withRemarks=true 时追加 COMMENT
     *
     * @param v           字段元数据
     * @param pks         主键字段名列表
     * @param useAutoInc  主键是否使用自增
     * @param addCr       是否在结尾追加换行
     * @param withRemarks 是否内联字段注释
     * @return 字段定义 SQL 片段
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();

        String retval = " " + quoteIdentifier(fieldname) + "  ";
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
                retval += temporalType(v);
                break;
            case ColumnMetaData.TYPE_DATE:
                retval += "DATE";
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += "TINYINT";
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                // 显式定点数保留声明，不按小数位为零或主键标志转换成整数。
                if (v.getSqlType() == java.sql.Types.DOUBLE || v.getSqlType() == java.sql.Types.FLOAT) {
                    // JDBC 近似数不得被小数位推导误改为定点数。
                    retval += "DOUBLE";
                } else if (v.getSqlType() == java.sql.Types.REAL) {
                    retval += "FLOAT";
                } else if (v.getSqlType() == java.sql.Types.DECIMAL && length > 0 && precision >= 0) {
                    retval += "DECIMAL(" + length + ", " + precision + ")";
                } else if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += "BIGINT AUTO_INCREMENT";
                    } else {
                        retval += "BIGINT";
                    }
                } else {
                    // 整数值……
                    // 按有效位数选容量：<=9 用 INT，10~18 用 BIGINT，>=19 用 DECIMAL(n)。
                    // INT 的显示宽度 11 含符号，不能代表可容纳 11 位整数。
                    if (precision == 0) {
                        if (length > 9) {
                            if (length < 19) {
                                // 可容纳 -9223372036854775808 至 9223372036854775807 之间的有符号值。
                                // 18 位有效数字。
                                retval += "BIGINT";
                            } else {
                                retval += "DECIMAL(" + length + ")";
                            }
                        } else {
                            retval += "INT";
                        }
                    } else {
                        // 带小数位的数值：用 DECIMAL(长度,精度)，长度/精度缺失时兜底 20/10
                        if (length > 0) {
                            retval += "DECIMAL(" + length;
                        } else {
                            retval += "DECIMAL(" + 20;
                        }
                        if (precision > 0) {
                            retval += ", " + precision;
                        } else {
                            retval += ", " + 10;
                        }
                        retval += ")";
						/*// 浮点值……
						if (length > 15) {

						} else {
							// 双精度浮点数约有 15 位
							// 十进制精度。
							// http://mysql.mirrors-r-us.net/doc/refman/5.1/en/numeric-type-overview.html
							retval += "DOUBLE";
						}*/
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                // 字符串：按长度依次选择 CHAR(1) / VARCHAR(n) / TEXT / MEDIUMTEXT / LONGTEXT
                if (length > 0) {
                    if (length == 1) {
                        retval += "CHAR(1)";
                    } else if (length < 1025) {
                        retval += "VARCHAR(" + length + ")";
                    } else if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                        /*
                         * MySQL5.6中varchar字段为主键时最大长度为254,例如如下的建表语句在MySQL5.7下能通过，但在MySQL5.6下无法通过：
                         *	create table `t_test`(
                         *	`key` varchar(1024) binary,
                         *	`val` varchar(1024) binary,
                         *	primary key(`key`)
                         * );
                         */
                        retval += "VARCHAR(254) BINARY";
                    } else if (length < 65536) {
                        retval += "TEXT";
                        canHaveDefaultValue = false;
                    } else if (length < 16777216) {
                        retval += "MEDIUMTEXT";
                        canHaveDefaultValue = false;
                    } else {
                        retval += "LONGTEXT";
                        canHaveDefaultValue = false;
                    }
                } else if (length < 0) {
                    retval += "TEXT";
                } else {
                    retval += "TINYTEXT";
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval += "LONGBLOB";
                canHaveDefaultValue = false;
                break;
            default:
                retval += " LONGTEXT";
                canHaveDefaultValue = false;
                break;
        }

        //源端字段值如果是0000-00-00 00:00:00 会导致查出结果为 NLLL，插入的时候报错
        if (!v.isNullable() && type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
            retval += " NOT NULL";
        }

        // 追加默认值：普通类型原样带引号追加；日期时间类的默认值统一替换为 CURRENT_TIMESTAMP
        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                if (v.getDefaultValue().startsWith("'")) {
                    retval += " DEFAULT " + v.getDefaultValue();
                } else {
                    retval += " DEFAULT '" + v.getDefaultValue() + "'";
                }
            } else {
                retval += " DEFAULT CURRENT_TIMESTAMP";
                if (type == ColumnMetaData.TYPE_TIMESTAMP && length > 0) {
                    retval += "(" + length + ")";
                }
            }
        }

        // 追加字段注释（单引号转义防止破坏语句）
        if (withRemarks && StringUtils.isNotBlank(v.getRemarks())) {
            retval += " COMMENT " + GenerateSqlUtils.quoteMysqlComment(v.getRemarks(), 1024) + " ";
        }

        if (addCr) {
            retval += Const.CR;
        }

        return retval;
    }

    /** 产品委托可保留已验证的时间精度，无需改写生成 SQL。 */
    protected String temporalType(ColumnMetaData column) {
        if (column.getType() != ColumnMetaData.TYPE_TIMESTAMP) {
            return "TIME";
        }
        int fractionalDigits = column.getLength();
        if (fractionalDigits > 6) {
            throw new UnsupportedOperationException("MySQL DATETIME cannot preserve more than six fractional digits");
        }
        return fractionalDigits > 0 ? "DATETIME(" + fractionalDigits + ")" : "DATETIME";
    }

    /**
     * 判断字段类型能否建索引：数值/日期/布尔类可以；
     * 字符串类长度必须在 1~1024 之间（长文本 TEXT 系列不建索引）；其余类型不建
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
                if (length > 0) {
                    if (length >= 1025) {
                        canCreateIndex = false;
                    }
                } else {
                    canCreateIndex = false;
                }
                break;
            default:
                canCreateIndex = false;
                break;
        }
        return canCreateIndex;
    }

    /**
     * 主键字段串：MySQL 用反引号包裹，形如 `id` , `name`
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        if (null != pks && !pks.isEmpty()) {
            return pks.stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(" , "));
        }

        return "";
    }

    /**
     * 表/字段注释 DDL：MySQL 的注释在建表语句中内联（COMMENT 子句），无需独立语句，返回空列表
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /**
     * 生成“按指定字段分组后出现次数大于 1”的查询 SQL（用于检测按这些字段判重时的重复数据）。
     */
    @Override
    public String getCountMoreThanOneSql(String schemaName, String tableName, List<String> columns) {
        String columnStr = getPrimaryKeyAsString(columns);
        return "SELECT " + columnStr + " FROM " + getQuotedSchemaTableCombination(schemaName,
                tableName) + " GROUP BY " + columnStr + " HAVING count(*)>1";
    }

    /**
     * 生成判断分组数量为一的查询 SQL。
     */
    @Override
    public String getCountOneSql(String schemaName, String tableName, List<String> columns) {
        String columnStr = getPrimaryKeyAsString(columns);
        return "SELECT " + columnStr + " FROM " + getQuotedSchemaTableCombination(schemaName,
                tableName) + " GROUP BY " + columnStr + " HAVING count(*)=1";
    }


    /**
     * 建表语句后置内容：固定追加 InnoDB 引擎与 utf8mb4 字符集，有表注释时内联 COMMENT
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        builder.append("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        if (StringUtils.isNotBlank(tblComment)) {
            builder.append(" COMMENT=").append(GenerateSqlUtils.quoteMysqlComment(tblComment, 2048)).append(' ');
        }
    }

    /**
     * 按当前方言生成列默认值 SQL。
     */
    String dialectDefaultValueSql(String schemaName, String tableName) {
        return getDefaultValueSql(schemaName, tableName);
    }

    /**
     * 按当前方言生成读取表字段的查询 SQL。
     */
    String dialectTableFieldsQuerySQL(String schemaName, String tableName) {
        return getTableFieldsQuerySQL(schemaName, tableName);
    }

    /**
     * 返回当前方言使用的连接探测 SQL。
     */
    String dialectTestQuerySQL(String sql) {
        return getTestQuerySQL(sql);
    }

    /** 仅允许物理名称；列可空、非键列、无默认值、无标识生成且不是生成列。 */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getClass() != DatabaseMysqlImpl.class) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return MysqlAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }
}
