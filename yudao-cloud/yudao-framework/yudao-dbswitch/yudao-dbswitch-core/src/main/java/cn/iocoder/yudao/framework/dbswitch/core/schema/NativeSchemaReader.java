package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.ClickHouseNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.InfluxDb3CatalogSql;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqliteNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.TdengineNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 获取新的目录观测；绝不提交或持有连接。可选探测使用各自的保存点。 */
public final class NativeSchemaReader {

    /**
     * 初始化 {@code NativeSchemaReader}。
     */
    private NativeSchemaReader() {
    }

    /**
     * 读取SQL服务端标识表。
     */
    public static SqlServerIdentityTable readSqlServerIdentityTable(Connection connection, String schema, String table,
            int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Invalid metadata timeout");
        }
        return SqlServerNativeSchemaReader.readIdentityTable(connection, schema, table, timeoutSeconds);
    }

    /**
     * 拒绝当前实现未支持的 {@code read} 操作。
     */
    public static TableSchemaMetadata read(Connection connection, ProductTypeEnum product,
            String schema, String table, int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Invalid metadata timeout");
        }
        if (product == ProductTypeEnum.SQLSERVER) {
            return SqlServerNativeSchemaReader.read(connection, schema, table, timeoutSeconds);
        }
        if (product == ProductTypeEnum.SQLSERVER2000) {
            return cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqlServer2000NativeCatalog
                    .read(connection, schema, table, timeoutSeconds);
        }
        if (product == ProductTypeEnum.SQLITE3) {
            return SqliteNativeCatalog.read(connection, schema, table, timeoutSeconds);
        }
        if (product == ProductTypeEnum.OSCAR) {
            return cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog
                    .read(connection, schema, table, timeoutSeconds);
        }
        DatabaseMetaData jdbc = connection.getMetaData();
        MetadataProvenance source = new MetadataProvenance(product, jdbc.getDatabaseProductName(),
                jdbc.getDatabaseProductVersion(), "NATIVE_CATALOG_V1", Instant.now());
        if (product == ProductTypeEnum.TIDB) {
            // Connector/J 显示的是 MySQL 兼容版本。使用 TiDB 的目录事实生成 DDL 前，
            // 必须由 TiDB 自身的观测确定实际产品与版本。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT tidb_version()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("TiDB native version unavailable", "0A000");
                    }
                    var version = java.util.regex.Pattern.compile(
                                    "(?m)^Release Version: (v8\\.[0-9]+\\.[0-9]+(?:[-+][^\\r\\n]*)?)\\r?$")
                            .matcher(rows.getString(1));
                    if (!version.find() || rows.next()) {
                        throw new SQLFeatureNotSupportedException("TiDB native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "TiDB", version.group(1), "TIDB_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.MARIADB) {
            // 目录方法已固定：跨产品类型映射只接受此读取器提供的 MariaDB 事实。
            source = new MetadataProvenance(product, jdbc.getDatabaseProductName(), jdbc.getDatabaseProductVersion(),
                    "MARIADB_CATALOG_V1", Instant.now());
        }
        if (product == ProductTypeEnum.GBASE8A) {
            // VERSION() 固定 GBase 8a 服务器版本，避免将 MySQL 目录事实用于 DDL。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT VERSION()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("GBase 8a native version unavailable", "0A000");
                    }
                    String version = rows.getString(1);
                    if (rows.next() || version == null || version.isBlank() || version.length() > 64) {
                        throw new SQLFeatureNotSupportedException("GBase 8a native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "GBase", version, "GBASE8A_CATALOG_V1", Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.DORIS) {
            // Connector/J 显示 MySQL 兼容层（5.7.99）；version_comment 固定实际 Doris 服务器版本。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("Doris native version unavailable", "0A000");
                    }
                    var version = java.util.regex.Pattern.compile("^doris version (doris-[0-9][^\\s]*)$")
                            .matcher(rows.getString(2));
                    if (!version.matches() || rows.next()) {
                        throw new SQLFeatureNotSupportedException("Doris native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "Doris", version.group(1), "DORIS_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.ORACLE) {
            // JDBC 产品名为 Oracle；v$version 版本标识固定服务器版本，避免将其他引擎的事实
            // 用于 DDL。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT banner FROM v$version WHERE banner LIKE 'Oracle%'")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("Oracle native version unavailable", "0A000");
                    }
                    String banner = rows.getString(1);
                    if (rows.next() || banner == null || banner.isBlank() || banner.length() > 160) {
                        throw new SQLFeatureNotSupportedException("Oracle native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "Oracle", banner, "ORACLE_CATALOG_V1", Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.KINGBASE) {
            // JDBC 产品名为 KingbaseES；version() 固定 oracle 模式的服务器版本。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT version()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("Kingbase native version unavailable", "0A000");
                    }
                    String version = rows.getString(1);
                    if (rows.next() || version == null || version.isBlank() || version.length() > 160) {
                        throw new SQLFeatureNotSupportedException("Kingbase native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "KingbaseES", version, "KINGBASE_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.DM) {
            // JDBC 产品名为 DM DBMS；此方法固定目录读取器。
            source = new MetadataProvenance(product, "DM DBMS", jdbc.getDatabaseProductVersion(), "DM_CATALOG_V1",
                    Instant.now());
        }
        if (product == ProductTypeEnum.SYBASE) {
            // @@version 版本标识固定 ASE 服务器版本，避免将其他引擎的事实用于 DDL。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT @@version")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("Sybase native version unavailable", "0A000");
                    }
                    String banner = rows.getString(1);
                    if (rows.next() || banner == null || banner.isBlank() || banner.length() > 300) {
                        throw new SQLFeatureNotSupportedException("Sybase native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "Adaptive Server Enterprise", banner,
                            "SYBASE16_CATALOG_V1", Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.DB2) {
            // service_level 固定 LUW 服务器版本，避免将其他引擎的事实用于 DDL。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT service_level FROM SYSIBMADM.ENV_INST_INFO")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("DB2 native service level unavailable", "0A000");
                    }
                    String level = rows.getString(1);
                    if (rows.next() || level == null || level.isBlank() || level.length() > 64) {
                        throw new SQLFeatureNotSupportedException("DB2 native service level is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "DB2/LINUXX8664", level, "DB2_1215_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.HIGHGO || product == ProductTypeEnum.UXDB || product == ProductTypeEnum.GREENPLUM) {
            // JDBC 产品名为 PostgreSQL（HighGo、Greenplum）或 Uxdb；version() 版本标识与 server_version
            // 共同固定产品，避免将其他引擎的目录事实用于 DDL。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery(product == ProductTypeEnum.UXDB
                        ? "SELECT ux_catalog.version(), ux_catalog.current_setting('server_version')"
                        : "SELECT pg_catalog.version(), pg_catalog.current_setting('server_version')")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException(product + " native version unavailable", "0A000");
                    }
                    String banner = rows.getString(1), version = rows.getString(2);
                    if (banner != null && banner.contains(" on ")) {
                        banner = banner.substring(0, banner.indexOf(" on "));
                    }
                    if (rows.next() || banner == null || banner.isBlank() || banner.length() > 160
                            || version == null || version.isBlank() || version.length() > 64) {
                        throw new SQLFeatureNotSupportedException(product + " native version is unverified", "0A000");
                    }
                    source = switch (product) {
                        case HIGHGO ->
                                new MetadataProvenance(product, banner, version, "HIGHGO_CATALOG_V1", Instant.now());
                        case UXDB -> new MetadataProvenance(product, banner, version, "UXDB_CATALOG_V1", Instant.now());
                        default ->
                                new MetadataProvenance(product, banner, version, "GREENPLUM_CATALOG_V1", Instant.now());
                    };
                }
            }
        }
        if (product == ProductTypeEnum.STARROCKS) {
            // Connector/J 显示 MySQL 兼容层（8.0.33）；current_version() 仅固定此 StarRocks 版本。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT current_version()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("StarRocks native version unavailable", "0A000");
                    }
                    String version = rows.getString(1);
                    if (rows.next() || version == null || !version.matches("3\\.5\\.21-85fccf8")) {
                        throw new SQLFeatureNotSupportedException("StarRocks native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "StarRocks", version, "STARROCKS_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.CLICKHOUSE) {
            // version() 固定 ClickHouse 25.8。MYSQL_COLUMNS 不适用：此处 GENERATION_EXPRESSION 未知。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT version()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("ClickHouse native version unavailable", "0A000");
                    }
                    String version = rows.getString(1);
                    if (rows.next() || version == null || !version.matches("25\\.8\\.[0-9]+(?:\\.[0-9]+)?")) {
                        throw new SQLFeatureNotSupportedException("ClickHouse native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "ClickHouse", version, "CLICKHOUSE_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.TDENGINE) {
            // SERVER_VERSION() 固定 3.3.8.x。JDBC 主版本为 3，不能用于下方 major < 8 检查。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT SERVER_VERSION()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("TDengine native version unavailable", "0A000");
                    }
                    String version = rows.getString(1);
                    if (rows.next() || version == null || !version.matches("3\\.3\\.8\\.[0-9]+")) {
                        throw new SQLFeatureNotSupportedException("TDengine native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "TDengine", version, "TDENGINE_CATALOG_V1", Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.INFLUXDB3) {
            // Flight JDBC 报告 InfluxDB IOx / 2。SELECT version() 返回 DataFusion 版本标识，而非 3.11.5。
            if (!"InfluxDB IOx".equals(jdbc.getDatabaseProductName()) || !"2".equals(
                    jdbc.getDatabaseProductVersion())) {
                throw new SQLFeatureNotSupportedException("InfluxDB 3 JDBC facade is unverified", "0A000");
            }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT version()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("InfluxDB 3 native version unavailable", "0A000");
                    }
                    String banner = rows.getString(1);
                    if (rows.next() || !"Apache DataFusion 51.0.0, x86_64 on linux".equals(banner)) {
                        throw new SQLFeatureNotSupportedException("InfluxDB 3 native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "InfluxDB 3 Core", "3.11.5", "INFLUXDB3_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.OPENGAUSS) {
            // A 模式会重写日期和空字符串。其他版本标识、server_version 或兼容模式均未验证。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery(
                        "SELECT pg_catalog.version(), pg_catalog.current_setting('server_version'), pg_catalog.current_setting('sql_compatibility')")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("openGauss native version unavailable", "0A000");
                    }
                    String banner = rows.getString(1), version = rows.getString(2), compatibility = rows.getString(3);
                    if (banner != null && banner.contains(" on ")) {
                        banner = banner.substring(0, banner.indexOf(" on ")).stripTrailing();
                    }
                    if (rows.next() || banner == null || version == null || compatibility == null
                            || !"(openGauss 7.0.0-RC3 build 01b7e318) compiled at 2026-03-25 18:12:24 commit 0 last mr 9114".equals(
                            banner)
                            || !"9.2.4".equals(version) || !"A".equals(compatibility)) {
                        throw new SQLFeatureNotSupportedException("openGauss native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, banner, version, "OPENGAUSS_CATALOG_V1", Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.OCEANBASE) {
            // 仅支持 MySQL 模式。Oracle 模式和 JDBC 产品版本 4.3.5.6 不符合此固定条件。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT @@ob_compatibility_mode, VERSION()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("OceanBase native version unavailable", "0A000");
                    }
                    String mode = rows.getString(1), version = rows.getString(2);
                    if (rows.next() || !"MYSQL".equals(mode) || !"5.7.25-OceanBase_CE-v4.3.5.6".equals(version)) {
                        throw new SQLFeatureNotSupportedException("OceanBase native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "OceanBase", version, "OCEANBASE_CATALOG_V1",
                            Instant.now());
                }
            }
        }
        if (product == ProductTypeEnum.HIVE) {
            // JDBC 主版本为 4。完整的 version() 返回行才是固定依据；Hive 不得使用 MYSQL_COLUMNS。
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rows = statement.executeQuery("SELECT version()")) {
                    if (!rows.next()) {
                        throw new SQLFeatureNotSupportedException("Hive native version unavailable", "0A000");
                    }
                    String version = rows.getString(1);
                    if (rows.next() || !"4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb".equals(version)) {
                        throw new SQLFeatureNotSupportedException("Hive native version is unverified", "0A000");
                    }
                    source = new MetadataProvenance(product, "Apache Hive", version, "HIVE_CATALOG_V1", Instant.now());
                }
            }
        }
        if (!source.nativeProduct(product)) {
            throw new SQLFeatureNotSupportedException("Native schema product is unverified", "0A000");
        }
        // TiDB、Doris 和 StarRocks 报告的是 MySQL 兼容层。TDengine、Hive、OceanBase 和 Oscar
        // 报告的 JDBC 主版本低于 8；应以上方各产品自身的版本固定信息检查服务器。
        if (product != ProductTypeEnum.TIDB && product != ProductTypeEnum.DORIS && product != ProductTypeEnum.TDENGINE
                && product != ProductTypeEnum.HIVE
                && product != ProductTypeEnum.OCEANBASE && product != ProductTypeEnum.OSCAR
                && jdbc.getDatabaseMajorVersion() < (product == ProductTypeEnum.POSTGRESQL ? 12 : 8)) {
            throw new SQLFeatureNotSupportedException("Native schema server version is unverified", "0A000");
        }
        if (product == ProductTypeEnum.CLICKHOUSE) {
            return readClickHouse(connection, schema, table, timeoutSeconds, source);
        }
        if (product == ProductTypeEnum.TDENGINE) {
            return TdengineNativeCatalog.read(connection, schema, table, timeoutSeconds);
        }
        if (product == ProductTypeEnum.HIVE) {
            return readHive(connection, schema, table, timeoutSeconds);
        }
        if (product == ProductTypeEnum.INFLUXDB3 && !"iox".equals(schema)) {
            throw new SQLFeatureNotSupportedException("InfluxDB 3 catalog schema must be iox", "0A000");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(product, schema, table);
        List<ColumnDescription> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + relation + " WHERE 1=0")) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                if (metadata.getColumnCount() > 2000) {
                    throw new SQLException("Column metadata limit exceeded");
                }
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    ColumnDescription column = new ColumnDescription();
                    column.setDbType(product);
                    column.setFieldName(metadata.getColumnName(i));
                    column.setFieldType(metadata.getColumnType(i));
                    column.setFieldTypeName(metadata.getColumnTypeName(i));
                    column.setFiledTypeClassName(metadata.getColumnClassName(i));
                    column.setDisplaySize(metadata.getColumnDisplaySize(i));
                    column.setPrecisionSize(metadata.getPrecision(i));
                    column.setScaleSize(metadata.getScale(i));
                    column.setNullable(metadata.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    column.setAutoIncrement(metadata.isAutoIncrement(i));
                    column.setSigned(metadata.isSigned(i));
                    columns.add(column);
                }
            }
        }
        Map<String, ColumnDescription> byName = new LinkedHashMap<>();
        columns.forEach(column -> byName.put(column.getFieldName(), column));
        String catalogSql = product == ProductTypeEnum.POSTGRESQL ? POSTGRES_SIGNED_NUMERIC_COLUMNS
                : postgresFamily(product) || product == ProductTypeEnum.KINGBASE ? POSTGRES_COLUMNS
                : product == ProductTypeEnum.UXDB ? UXDB_COLUMNS
                : product == ProductTypeEnum.GBASE8A ? GBASE8A_COLUMNS
                : product == ProductTypeEnum.ORACLE ? ORACLE_COLUMNS
                : product == ProductTypeEnum.DM ? DM_COLUMNS
                : product == ProductTypeEnum.SYBASE ? SYBASE_COLUMNS
                : product == ProductTypeEnum.DB2 ? DB2_COLUMNS
                : product == ProductTypeEnum.INFLUXDB3 ? InfluxDb3CatalogSql.columns(schema, table)
                : product == ProductTypeEnum.OPENGAUSS ? OPENGAUSS_COLUMNS : MYSQL_COLUMNS;
        try (PreparedStatement statement = connection.prepareStatement(catalogSql)) {
            statement.setQueryTimeout(timeoutSeconds);
            if (product == ProductTypeEnum.SYBASE) {
                statement.setString(1, schema + "." + table);
            } else if (product != ProductTypeEnum.INFLUXDB3) {
                statement.setString(1, schema);
                statement.setString(2, table);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ColumnDescription column = byName.get(rows.getString("column_name"));
                    if (column == null) {
                        throw new SQLException("Schema changed during metadata observation");
                    }
                    String nativeType = rows.getString("native_type");
                    String generated = rows.getString("generated_expression");
                    String identity = rows.getString("identity_clause");
                    boolean numericVisible = rows.getBoolean("parameters_visible");
                    MetadataValue<Integer> precision = numericVisible ? optionalInteger(rows,
                            "numeric_precision") : MetadataValue.unknown();
                    MetadataValue<Integer> scale = numericVisible ? optionalInteger(rows,
                            "numeric_scale") : MetadataValue.unknown();
                    long characterLength = rows.getLong("character_length");
                    Long length = rows.wasNull() ? null : characterLength;
                    column.setNullable(rows.getBoolean("is_nullable"));
                    column.setRemarks(rows.getString("column_comment"));
                    String charset = rows.getString("character_set");
                    String collation = rows.getString("collation_name");
                    // Oracle data_default 是 LONG：先读取 DEFAULT ON NULL 标记，最后读取值，每行只读一次。
                    int defaultOnNull = product == ProductTypeEnum.ORACLE ? rows.getInt("default_on_null") : 0;
                    String defaultExpression = rows.getString("default_expression");
                    MetadataValue<String> defaultFact = optional(defaultExpression);
                    if ((product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.TIDB || product == ProductTypeEnum.GBASE8A)
                            && defaultExpression != null) {
                        String extra = rows.getString("extra");
                        // 目录不会为字面量添加引号。只有已验证的数值或时间表达式才能作为 SQL。
                        if ((extra != null && extra.contains("DEFAULT_GENERATED"))
                                || nativeType.matches("(?i)(timestamp|datetime)(\\(\\d+\\))?")
                                && defaultExpression.matches("(?i)current_timestamp(\\(\\d*\\))?")) {
                            defaultFact = MetadataValue.value(defaultExpression);
                        } else {
                            defaultFact = MetadataValue.value("'" + defaultExpression.replace("'", "''") + "'");
                        }
                        if (defaultExpression.indexOf('\\') >= 0) {
                            defaultFact = MetadataValue.unsupported();
                        }
                    }
                    if (product == ProductTypeEnum.MARIADB && defaultExpression != null) {
                        // MariaDB 10.2.7+ 将 COLUMN_DEFAULT 报告为 SQL 文本，隐式或显式 NULL 默认值报告为 'NULL'。
                        boolean nullDefault = defaultExpression.equals("NULL");
                        defaultFact = nullDefault ? MetadataValue.absent()
                                : defaultExpression.indexOf(
                                '\\') >= 0 ? MetadataValue.unsupported() : MetadataValue.value(defaultExpression);
                        if (nullDefault) {
                            defaultExpression = null;
                        }
                    }
                    if (product == ProductTypeEnum.ORACLE) {
                        // DEFAULT ON NULL 引入依赖语义，暂无跨产品映射；
                        // 字面量文本 'NULL' 表示不存在默认值。
                        if (defaultOnNull != 0) {
                            defaultFact = MetadataValue.unsupported();
                        } else {
                            defaultFact = defaultExpression == null || defaultExpression.trim().equals("NULL")
                                    ? MetadataValue.absent() : MetadataValue.value(defaultExpression.trim());
                        }
                    }
                    column.setDefaultValue(product == ProductTypeEnum.ORACLE ? null : defaultExpression);
                    column.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value(nativeType),
                            defaultFact, MetadataValue.value(column.isNullable()), precision, scale,
                            numericVisible ? optional(length) : MetadataValue.unknown(),
                            product == ProductTypeEnum.OPENGAUSS ? MetadataValue.unknown() : optional(identity),
                            product == ProductTypeEnum.OPENGAUSS ? MetadataValue.unknown() : optional(generated),
                            optional(charset), optional(collation)));
                }
            }
        }
        // 缺少目录行时（包括因权限过滤的元数据），状态仍为 UNKNOWN。
        if (product == ProductTypeEnum.POSTGRESQL) {
            for (var column : columns) {
                var facts = column.getMetadataEvidence();
                column.setMetadataEvidence(
                        new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                                facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                                facts.characterLength(), facts.identity(),
                                facts.generatedExpression(), facts.charset(), facts.collation(),
                                readPostgresSequence(connection, schema, table, column.getFieldName(),
                                        timeoutSeconds)));
            }
        }
        List<String> primaryKeys = new ArrayList<>();
        if (product == ProductTypeEnum.INFLUXDB3 || product == ProductTypeEnum.OPENGAUSS) {
            // 两个主键查询均未执行。空列表不能证明没有主键。
        } else if (product == ProductTypeEnum.SYBASE || product == ProductTypeEnum.DB2) {
            // jconn4 和 db2jcc4 的 getPrimaryKeys 已在这些端点验证；Sybase tempdb 禁止在多语句事务中使用 sp_* 辅助方法，
            // 所以 JDBC 元数据路径是两个读取器唯一共用的路径。
            var ordered = new java.util.TreeMap<Short, String>();
            try (var keys = connection.getMetaData()
                    .getPrimaryKeys(null, product == ProductTypeEnum.DB2 ? schema : null, table)) {
                while (keys.next()) {
                    ordered.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
                }
            }
            ordered.forEach((seq, name) -> primaryKeys.add(name));
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    postgresFamily(product) || product == ProductTypeEnum.KINGBASE ? POSTGRES_PK
                            : product == ProductTypeEnum.UXDB ? UXDB_PK
                            : product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM ? ORACLE_PK : MYSQL_PK)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setString(1, schema);
                statement.setString(2, table);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        primaryKeys.add(rows.getString(1));
                    }
                }
            }
        }
        columns.forEach(column -> column.setPk(primaryKeys.contains(column.getFieldName())));
        MetadataValue<List<String>> additionalFact;
        if (product == ProductTypeEnum.INFLUXDB3 || product == ProductTypeEnum.OPENGAUSS) {
            additionalFact = MetadataValue.unknown();
        } else {
            List<String> additional = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    product == ProductTypeEnum.POSTGRESQL ? POSTGRES_ADDITIONAL_WITH_POLICIES
                            : postgresFamily(product) || product == ProductTypeEnum.KINGBASE ? POSTGRES_ADDITIONAL
                            : product == ProductTypeEnum.UXDB ? UXDB_ADDITIONAL
                            : product == ProductTypeEnum.GBASE8A ? GBASE8A_ADDITIONAL
                            : product == ProductTypeEnum.ORACLE ? ORACLE_ADDITIONAL
                            : product == ProductTypeEnum.DM ? DM_ADDITIONAL
                            : product == ProductTypeEnum.SYBASE ? SYBASE_ADDITIONAL
                            : product == ProductTypeEnum.DB2 ? DB2_ADDITIONAL : MYSQL_ADDITIONAL)) {
                statement.setQueryTimeout(timeoutSeconds);
                if (product == ProductTypeEnum.SYBASE) {
                    for (int i = 1; i <= 2; i++) {
                        statement.setString(i, schema + "." + table);
                    }
                } else if (product == ProductTypeEnum.DB2) {
                    for (int i = 1; i <= 6; i++) {
                        statement.setString(i, i % 2 == 1 ? schema : table);
                    }
                } else if (product == ProductTypeEnum.GBASE8A) {
                    // 5 个联合查询，每个包含 2 个占位符；GBase 驱动会报告首个未设置的参数。
                    for (int i = 1; i <= 10; i++) {
                        statement.setString(i, i % 2 == 1 ? schema : table);
                    }
                } else if (product == ProductTypeEnum.ORACLE) {
                    // 4 个联合查询，每个包含 2 个占位符。
                    for (int i = 1; i <= 8; i++) {
                        statement.setString(i, i % 2 == 1 ? schema : table);
                    }
                } else if (product == ProductTypeEnum.DM) {
                    // 2 个联合查询，每个包含 2 个占位符。
                    for (int i = 1; i <= 4; i++) {
                        statement.setString(i, i % 2 == 1 ? schema : table);
                    }
                } else {
                    statement.setString(1, schema);
                    statement.setString(2, table);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (additional.size() >= 2000) {
                            throw new SQLException("Additional schema object limit exceeded");
                        }
                        additional.add(rows.getString(1));
                    }
                }
            }
            additionalFact = additional.isEmpty()
                    // 缺少 TRIGGER 权限时，MySQL 和 GBase 8a 会隐藏触发器行。空结果不能证明触发器不存在。
                    ? product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.TIDB || product == ProductTypeEnum.MARIADB
                    || product == ProductTypeEnum.GBASE8A
                    ? MetadataValue.unknown() : MetadataValue.absent()
                    : MetadataValue.value(additional);
        }
        return new TableSchemaMetadata(columns, primaryKeys, source, MetadataValue.value(true), additionalFact);
    }

    /**
     * ClickHouse 25.8 的列列表来自 {@code system.columns}，覆盖到 {@code SELECT *} 的空壳元数据上。
     * {@code MATERIALIZED}、{@code ALIAS} 和 {@code EPHEMERAL} 不在该投影中；仍通过
     * {@link ClickHouseNativeCatalog#apply} 处理，所以这些默认类型保持 UNSUPPORTED。主键和其他对象的
     * 未选择这些列。空键列表不等于 {@link MetadataValue.State#ABSENT}。
     */
    private static TableSchemaMetadata readClickHouse(Connection connection, String schema, String table,
            int timeoutSeconds, MetadataProvenance source) throws SQLException {
        if (!source.nativeProduct(ProductTypeEnum.CLICKHOUSE)) {
            throw new SQLFeatureNotSupportedException("ClickHouse catalog provenance is unverified", "0A000");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.CLICKHOUSE, schema, table);
        Map<String, ColumnDescription> jdbc = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + relation + " WHERE 1=0")) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                if (metadata.getColumnCount() > 2000) {
                    throw new SQLException("Column metadata limit exceeded");
                }
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    ColumnDescription column = new ColumnDescription();
                    column.setDbType(ProductTypeEnum.CLICKHOUSE);
                    column.setFieldName(metadata.getColumnName(i));
                    column.setFieldType(metadata.getColumnType(i));
                    column.setFieldTypeName(metadata.getColumnTypeName(i));
                    column.setFiledTypeClassName(metadata.getColumnClassName(i));
                    column.setDisplaySize(metadata.getColumnDisplaySize(i));
                    column.setPrecisionSize(metadata.getPrecision(i));
                    column.setScaleSize(metadata.getScale(i));
                    column.setNullable(metadata.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    column.setAutoIncrement(metadata.isAutoIncrement(i));
                    column.setSigned(metadata.isSigned(i));
                    if (column.getFieldName() == null || column.getFieldName().isBlank()
                            || jdbc.put(column.getFieldName(), column) != null) {
                        throw new SQLException("Schema changed during metadata observation");
                    }
                }
            }
        }
        List<ColumnDescription> columns = new ArrayList<>();
        var seen = new HashSet<String>();
        try (PreparedStatement statement = connection.prepareStatement(ClickHouseNativeCatalog.SQL)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= 2000) {
                        throw new SQLException("Column metadata limit exceeded");
                    }
                    String name = rows.getString("column_name");
                    if (name == null || name.isBlank() || !seen.add(name)) {
                        throw new SQLException("ClickHouse column catalog row is incomplete");
                    }
                    ColumnDescription column = jdbc.remove(name);
                    if (column == null) {
                        // SELECT * 会省略这些类型。普通列或 DEFAULT 列未出现在投影中，不代表它属于这些类型。
                        String kind = rows.getString("default_kind");
                        if (kind == null || kind.isEmpty() || "DEFAULT".equals(kind)) {
                            throw new SQLException("Schema changed during metadata observation");
                        }
                        column = new ColumnDescription();
                        column.setDbType(ProductTypeEnum.CLICKHOUSE);
                        column.setFieldName(name);
                    }
                    ClickHouseNativeCatalog.apply(column, rows, source);
                    columns.add(column);
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("ClickHouse column catalog row is incomplete");
        }
        if (!jdbc.isEmpty()) {
            throw new SQLException("Schema changed during metadata observation");
        }
        return new TableSchemaMetadata(columns, List.of(), source, MetadataValue.value(true), MetadataValue.unknown());
    }

    /** Hive 列事实仅来自 {@link HiveNativeCatalog}。该读取器不查询键或其他对象。 */
    private static TableSchemaMetadata readHive(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        List<ColumnDescription> columns = HiveNativeCatalog.read(connection, schema, table, timeoutSeconds);
        MetadataProvenance source = columns.get(0).getMetadataEvidence().provenance();
        // DESCRIBE 未选择主键或额外对象。空键列表不等于 ABSENT。
        return new TableSchemaMetadata(columns, List.of(), source, MetadataValue.value(true), MetadataValue.unknown());
    }

    /**
     * 返回当前可选。
     */
    private static <T> MetadataValue<T> optional(T value) {
        return value == null || value instanceof String text && text.isEmpty() ? MetadataValue.absent() : MetadataValue.value(
                value);
    }

    /**
     * 判断当前数据库是否采用 PostgreSQL 家族语法或协议。
     */
    private static boolean postgresFamily(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.HIGHGO || product == ProductTypeEnum.GREENPLUM;
    }

    /** openGauss 7.0.0-RC3 A 模式。没有 attgenerated、attidentity 或 to_regclass。标识生成和生成列状态仍为 UNKNOWN。 */
    private static final String OPENGAUSS_COLUMNS = """
            SELECT a.attname AS column_name, pg_catalog.format_type(a.atttypid, a.atttypmod) AS native_type,
              pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS default_expression,
              NULL::text AS generated_expression,
              NULL::text AS identity_clause,
              NOT a.attnotnull AS is_nullable, i.numeric_precision, i.numeric_scale,
              i.character_maximum_length AS character_length, i.column_name IS NOT NULL AS parameters_visible,
              NULL::text AS character_set,
              CASE WHEN a.attcollation<>t.typcollation THEN quote_ident(cn.nspname)||'.'||quote_ident(co.collname) END AS collation_name,
              pg_catalog.col_description(c.oid, a.attnum) AS column_comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
            JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            LEFT JOIN information_schema.columns i
              ON i.table_schema=n.nspname AND i.table_name=c.relname AND i.column_name=a.attname
            LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation
            LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid=co.collnamespace
            WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','f') ORDER BY a.attnum
            """;

    /** UXsinoDB 2.1.0.3 目录：PG10 形态（无 attgenerated）、ux_ 对象、ux_get_expr、schema ux_catalog。 */
    private static final String UXDB_COLUMNS = """
            SELECT a.attname AS column_name, ux_catalog.format_type(a.atttypid,a.atttypmod) AS native_type,
              ux_catalog.ux_get_expr(d.adbin,d.adrelid) AS default_expression,
              NULL::text AS generated_expression,
              CASE WHEN a.attidentity<>'' THEN 'GENERATED ' || CASE a.attidentity WHEN 'a' THEN 'ALWAYS' ELSE 'BY DEFAULT' END
                || ' AS IDENTITY' END AS identity_clause,
              NOT a.attnotnull AS is_nullable, i.numeric_precision, i.numeric_scale,
              i.character_maximum_length AS character_length, i.column_name IS NOT NULL AS parameters_visible,
              NULL::text AS character_set,
              CASE WHEN a.attcollation<>t.typcollation THEN quote_ident(cn.nspname)||'.'||quote_ident(co.collname) END AS collation_name,
              ux_catalog.col_description(c.oid,a.attnum) AS column_comment
            FROM ux_catalog.ux_class c JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace
            JOIN ux_catalog.ux_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
            JOIN ux_catalog.ux_type t ON t.oid=a.atttypid
            LEFT JOIN ux_catalog.ux_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            LEFT JOIN information_schema.columns i ON i.table_schema=n.nspname AND i.table_name=c.relname AND i.column_name=a.attname
            LEFT JOIN ux_catalog.ux_collation co ON co.oid=a.attcollation
            LEFT JOIN ux_catalog.ux_namespace cn ON cn.oid=co.collnamespace
            WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','f') ORDER BY a.attnum
            """;

    private static final String UXDB_PK = """
            SELECT a.attname FROM ux_catalog.ux_constraint p JOIN ux_catalog.ux_class c ON c.oid=p.conrelid
            JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace
            CROSS JOIN LATERAL unnest(p.conkey) WITH ORDINALITY k(attnum,position)
            JOIN ux_catalog.ux_attribute a ON a.attrelid=c.oid AND a.attnum=k.attnum
            WHERE n.nspname=? AND c.relname=? AND p.contype='p' ORDER BY k.position
            """;

    private static final String UXDB_ADDITIONAL = """
            WITH relation AS (SELECT c.oid, c.relkind, c.relrowsecurity, c.relispartition, c.reloptions, c.relpersistence
              FROM ux_catalog.ux_class c JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname=? AND c.relname=?)
            SELECT 'constraint:'||p.conname FROM ux_catalog.ux_constraint p JOIN relation c ON c.oid=p.conrelid WHERE p.contype NOT IN ('p','n')
            UNION ALL SELECT 'index:'||CAST(i.indexrelid AS ux_catalog.regclass)::ux_catalog.text FROM ux_catalog.ux_index i JOIN relation c ON c.oid=i.indrelid WHERE NOT i.indisprimary
            UNION ALL SELECT 'trigger:'||t.tgname FROM ux_catalog.ux_trigger t JOIN relation c ON c.oid=t.tgrelid WHERE NOT t.tgisinternal
            UNION ALL SELECT 'table-options' FROM relation WHERE relkind<>'r' OR relrowsecurity OR relispartition
              OR reloptions IS NOT NULL OR relpersistence<>'p'
              OR EXISTS (SELECT 1 FROM ux_catalog.ux_inherits i WHERE i.inhrelid=relation.oid OR i.inhparent=relation.oid)
            """;

    /** 序列位置是非 MVCC 观测，不是源表快照或恢复代际。 */
    public static MetadataValue<ColumnSequenceMetadata> readPostgresSequence(Connection connection,
            String schema, String table, String column, int timeout) {
        Savepoint probe = null;
        try {
            if (!connection.getAutoCommit()) {
                probe = connection.setSavepoint();
            }
            return readSequence(connection, schema, table, column, timeout);
        } catch (SQLException unavailable) {
            if (probe != null) {
                try {
                    connection.rollback(probe);
                } catch (SQLException rollbackFailure) {
                    throw new IllegalStateException("Sequence observation transaction recovery failed");
                }
            }
            return MetadataValue.unknown();
        } finally {
            if (probe != null) {
                try {
                    connection.releaseSavepoint(probe);
                } catch (SQLException releaseFailure) {
                    throw new IllegalStateException("Sequence observation savepoint release failed");
                }
            }
        }
    }

    /**
     * 读取序列。
     */
    private static MetadataValue<ColumnSequenceMetadata> readSequence(Connection connection,
            String schema, String table, String column, int timeout) throws SQLException {
        String sql = "SELECT sn.nspname,s.relname,pg_catalog.format_type(q.seqtypid,NULL),"
                + "q.seqstart,q.seqincrement,q.seqmin,q.seqmax,q.seqcache,q.seqcycle,a.attidentity,"
                + "pg_catalog.pg_get_expr(d.adbin,d.adrelid),EXISTS(SELECT 1 FROM pg_catalog.pg_depend dep "
                + "WHERE dep.classid='pg_catalog.pg_attrdef'::regclass AND dep.objid=d.oid "
                + "AND dep.refclassid='pg_catalog.pg_class'::regclass AND dep.refobjid=s.oid),pg_catalog.has_sequence_privilege(s.oid,'SELECT'),"
                + "EXISTS(SELECT 1 FROM pg_catalog.pg_depend other WHERE other.classid='pg_catalog.pg_attrdef'::regclass "
                + "AND other.refclassid='pg_catalog.pg_class'::regclass AND other.refobjid=s.oid "
                + "AND (d.oid IS NULL OR other.objid<>d.oid)) "
                + "FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=t.oid AND d.adnum=a.attnum "
                + "JOIN pg_catalog.pg_class s ON s.oid=pg_catalog.pg_get_serial_sequence(quote_ident(n.nspname)||'.'||quote_ident(t.relname),a.attname)::regclass "
                + "JOIN pg_catalog.pg_namespace sn ON sn.oid=s.relnamespace JOIN pg_catalog.pg_sequence q ON q.seqrelid=s.oid "
                + "WHERE n.nspname=? AND t.relname=? AND a.attname=? AND t.relkind='r' AND t.relpersistence='p'";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return MetadataValue.absent();
                }
                String sequenceSchema = rows.getString(1), sequenceName = rows.getString(2), type = rows.getString(
                        3), identity = rows.getString(10);
                long start = rows.getLong(4), increment = rows.getLong(5), min = rows.getLong(6), max = rows.getLong(
                        7), cache = rows.getLong(8);
                boolean cycle = rows.getBoolean(9);
                String expression = rows.getString(11);
                // OWNED BY 不代表专用。单表计划不能拆分共享默认值。
                if (rows.getBoolean(14)) {
                    return MetadataValue.unsupported();
                }
                ColumnSequenceMetadata.Kind kind;
                if ("a".equals(identity)) {
                    kind = ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS;
                } else if ("d".equals(identity)) {
                    kind = ColumnSequenceMetadata.Kind.IDENTITY_BY_DEFAULT;
                } else if (rows.getBoolean(12) && expression != null && expression.matches(
                        "nextval\\('(?:[^']|'')+'::regclass\\)")) {
                    kind = ColumnSequenceMetadata.Kind.OWNED_DEFAULT;
                } else {
                    return MetadataValue.unsupported();
                }
                boolean stateVisible = rows.getBoolean(13);
                if (rows.next()) {
                    return MetadataValue.unknown();
                }
                MetadataValue<ColumnSequenceMetadata.Position> position = MetadataValue.unknown();
                if (stateVisible) {
                    try (var state = connection.createStatement()) {
                        state.setQueryTimeout(timeout);
                        try (var value = state.executeQuery(
                                "SELECT last_value,is_called FROM " + RestrictedSelectGuard.qualifiedTableName(
                                        ProductTypeEnum.POSTGRESQL, sequenceSchema, sequenceName))) {
                            if (value.next()) {
                                position = MetadataValue.value(
                                        new ColumnSequenceMetadata.Position(value.getLong(1), value.getBoolean(2)));
                            }
                            if (value.next()) {
                                position = MetadataValue.unknown();
                            }
                        }
                    }
                }
                return MetadataValue.value(
                        new ColumnSequenceMetadata(kind, sequenceSchema, sequenceName, type, start, increment, min, max,
                                cache, cycle, position));
            }
        } catch (IllegalArgumentException unavailable) {
            return MetadataValue.unknown();
        }
    }

    /**
     * GBase 8a 驱动未实现带类型的 getObject(label, Class) 重载；此处所有产品。
     */
    private static MetadataValue<Integer> optionalInteger(ResultSet rows, String name) throws SQLException {
        // GBase 8a 驱动未实现带类型的 getObject(label, Class) 重载；此处所有产品
        // 都将 NUMERIC_PRECISION/SCALE 报告为整数，因此读取时不使用带类型转换。
        Object value = rows.getObject(name);
        if (value == null) {
            return MetadataValue.absent();
        }
        if (value instanceof Integer integer) {
            return MetadataValue.value(integer);
        }
        if (value instanceof Number number) {
            return MetadataValue.value(number.intValue());
        }
        throw new SQLException("Unexpected catalog numeric value " + value.getClass().getName());
    }

    private static final String POSTGRES_COLUMNS = """
            SELECT a.attname AS column_name, pg_catalog.format_type(a.atttypid,a.atttypmod) AS native_type,
              CASE WHEN a.attgenerated='' THEN pg_catalog.pg_get_expr(d.adbin,d.adrelid) END AS default_expression,
              CASE WHEN a.attgenerated<>'' THEN pg_catalog.pg_get_expr(d.adbin,d.adrelid) END AS generated_expression,
              CASE WHEN a.attidentity<>'' THEN 'GENERATED ' || CASE a.attidentity WHEN 'a' THEN 'ALWAYS' ELSE 'BY DEFAULT' END
                || ' AS IDENTITY (START WITH ' || seq.seqstart || ' INCREMENT BY ' || seq.seqincrement
                || ' MINVALUE ' || seq.seqmin || ' MAXVALUE ' || seq.seqmax || ' CACHE ' || seq.seqcache
                || CASE WHEN seq.seqcycle THEN ' CYCLE)' ELSE ' NO CYCLE)' END END AS identity_clause,
              NOT a.attnotnull AS is_nullable, i.numeric_precision, i.numeric_scale,
              i.character_maximum_length AS character_length, i.column_name IS NOT NULL AS parameters_visible,
              NULL::text AS character_set,
              CASE WHEN a.attcollation<>t.typcollation THEN quote_ident(cn.nspname)||'.'||quote_ident(co.collname) END AS collation_name,
              pg_catalog.col_description(c.oid,a.attnum) AS column_comment
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
            JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            LEFT JOIN information_schema.columns i ON i.table_schema=n.nspname AND i.table_name=c.relname AND i.column_name=a.attname
            LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation
            LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid=co.collnamespace
            LEFT JOIN pg_catalog.pg_sequence seq ON a.attidentity<>'' AND seq.seqrelid=
              pg_catalog.pg_get_serial_sequence(quote_ident(n.nspname)||'.'||quote_ident(c.relname),a.attname)::regclass
            WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','f') ORDER BY a.attnum
            """;

    // PG 15+ 支持负 numeric scale，但 PG 16 information_schema 可能暴露
    // 其无符号 typmod 位（例如将 -2 表示为 2046）。应匹配 numeric_typmod_scale 的 11 位有符号
    // 解码方式。已带符号的目录值及合法正 scale 保持不变。
    // 仅限 PostgreSQL；其他 PG 系产品尚未验证此编码。
    private static final String POSTGRES_SIGNED_NUMERIC_COLUMNS = POSTGRES_COLUMNS.replace(
                    "i.numeric_scale,", "CASE WHEN i.numeric_scale > 1000 THEN "
                            + "((i.numeric_scale & 2047) # 1024) - 1024 ELSE i.numeric_scale END AS numeric_scale,")
            // 域的 NOT NULL 位于 pg_type，而不是 pg_attribute。
            .replace("NOT a.attnotnull AS is_nullable", "NOT (a.attnotnull OR t.typnotnull) AS is_nullable");

    private static final String MYSQL_COLUMNS = """
            SELECT COLUMN_NAME AS column_name, COLUMN_TYPE AS native_type, COLUMN_DEFAULT AS default_expression,
              NULLIF(GENERATION_EXPRESSION,'') AS generated_expression,
              CASE WHEN EXTRA LIKE '%auto_increment%' THEN 'AUTO_INCREMENT' END AS identity_clause,
              IS_NULLABLE='YES' AS is_nullable, NUMERIC_PRECISION AS numeric_precision, NUMERIC_SCALE AS numeric_scale,
              CHARACTER_MAXIMUM_LENGTH AS character_length, TRUE AS parameters_visible,
              CHARACTER_SET_NAME AS character_set, COLLATION_NAME AS collation_name,
              COLUMN_COMMENT AS column_comment, EXTRA AS extra
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION
            """;

    /** Oracle 23ai/26ai all_tab_cols：data_default 为 LONG，必须是最后选择和读取的列。 */
    private static final String ORACLE_COLUMNS = """
            SELECT c.column_name AS column_name,
              CASE
                WHEN c.data_type='VARCHAR2' THEN 'VARCHAR2(' || CASE WHEN c.char_used='C' THEN c.char_length ELSE c.data_length END || ' ' || c.char_used || ')'
                WHEN c.data_type='CHAR' THEN 'CHAR(' || CASE WHEN c.char_used='C' THEN c.char_length ELSE c.data_length END || ' ' || c.char_used || ')'
                WHEN c.data_type='NVARCHAR2' THEN 'NVARCHAR2(' || c.char_length || ')'
                WHEN c.data_type='NCHAR' THEN 'NCHAR(' || c.char_length || ')'
                WHEN c.data_type='RAW' THEN 'RAW(' || c.data_length || ')'
                WHEN c.data_type='NUMBER' THEN 'NUMBER' || CASE WHEN c.data_precision IS NOT NULL THEN '(' || c.data_precision || ',' || NVL(TO_CHAR(c.data_scale),'0') || ')'
                  WHEN c.data_scale IS NOT NULL THEN '(*,' || c.data_scale || ')' END
                WHEN c.data_type='FLOAT' THEN 'FLOAT(' || c.data_precision || ')'
                ELSE c.data_type
              END AS native_type,
              CASE WHEN c.virtual_column='YES' THEN 'VIRTUAL' END AS generated_expression,
              CASE WHEN c.identity_column='YES' THEN 'GENERATED ' || idc.generation_type || ' AS IDENTITY' END AS identity_clause,
              CASE WHEN c.nullable='Y' THEN 1 ELSE 0 END AS is_nullable,
              c.data_precision AS numeric_precision, c.data_scale AS numeric_scale,
              CASE WHEN c.data_type='RAW' THEN c.data_length ELSE c.char_length END AS character_length,
              CASE WHEN c.data_type IN ('BLOB','CLOB','NCLOB','BOOLEAN','DATE','JSON','XMLTYPE','LONG','ROWID') THEN 0 ELSE 1 END AS parameters_visible,
              NULL AS character_set,
              CASE WHEN c.collation IS NULL OR c.collation='USING_NLS_COMP' THEN NULL ELSE c.collation END AS collation_name,
              NULL AS column_comment,
              CASE WHEN c.default_on_null='YES' THEN 1 ELSE 0 END AS default_on_null,
              c.data_default AS default_expression
            FROM sys.all_tab_cols c
            LEFT JOIN sys.all_tab_identity_cols idc ON idc.owner=c.owner AND idc.table_name=c.table_name AND idc.column_name=c.column_name
            WHERE c.owner=? AND c.table_name=? AND c.hidden_column='NO' ORDER BY c.column_id
            """;

    /** DM8 8.1.5.60 all_tab_columns：没有 identity/virtual/collation 列；标识生成由 SYSCOLUMNS.INFO2=1 表示。 */
    private static final String DM_COLUMNS = """
            SELECT c.COLUMN_NAME AS column_name,
              CASE
                WHEN c.DATA_TYPE='VARCHAR' OR c.DATA_TYPE='VARCHAR2' THEN c.DATA_TYPE || '(' || c.CHAR_LENGTH || (CASE WHEN c.CHAR_USED='C' THEN ' CHAR' ELSE ' BYTE' END) || ')'
                WHEN c.DATA_TYPE='CHAR' THEN 'CHAR(' || c.CHAR_LENGTH || (CASE WHEN c.CHAR_USED='C' THEN ' CHAR' ELSE ' BYTE' END) || ')'
                WHEN c.DATA_TYPE='BINARY' OR c.DATA_TYPE='VARBINARY' THEN c.DATA_TYPE || '(' || c.DATA_LENGTH || ')'
                WHEN c.DATA_TYPE='NUMBER' OR c.DATA_TYPE='DECIMAL' THEN c.DATA_TYPE || CASE WHEN c.DATA_PRECISION IS NOT NULL THEN '(' || c.DATA_PRECISION || ',' || c.DATA_SCALE || ')' END
                WHEN c.DATA_TYPE='TIME' OR c.DATA_TYPE='TIMESTAMP' OR c.DATA_TYPE='DATETIME' THEN c.DATA_TYPE || '(' || NVL(TO_CHAR(c.DATA_SCALE),'0') || ')'
                WHEN c.DATA_TYPE='JSONB' THEN 'JSON'
                ELSE c.DATA_TYPE
              END AS native_type,
              c.DATA_DEFAULT AS default_expression,
              NULL AS generated_expression,
              CASE WHEN sc.INFO2=1 THEN 'IDENTITY' END AS identity_clause,
              CASE WHEN c.NULLABLE='Y' THEN 1 ELSE 0 END AS is_nullable,
              c.DATA_PRECISION AS numeric_precision, c.DATA_SCALE AS numeric_scale,
              CASE WHEN c.DATA_TYPE IN ('BINARY','VARBINARY') THEN c.DATA_LENGTH ELSE c.CHAR_LENGTH END AS character_length,
              CASE WHEN c.DATA_TYPE IN ('NUMBER','DECIMAL','VARCHAR','VARCHAR2','CHAR','BINARY','VARBINARY','TIME','TIMESTAMP','DATETIME') THEN 1 ELSE 0 END AS parameters_visible,
              NULL AS character_set, NULL AS collation_name, NULL AS column_comment
            FROM SYS.ALL_TAB_COLUMNS c
            LEFT JOIN SYS.SYSCOLUMNS sc ON sc.ID=(SELECT o.ID FROM SYS.SYSOBJECTS o WHERE o.NAME=c.TABLE_NAME) AND sc.COLID=c.COLUMN_ID-1
            WHERE c.OWNER=? AND c.TABLE_NAME=? ORDER BY c.COLUMN_ID
            """;

    /** Oracle 和 DM 共用 Oracle 形态的主键目录查询。 */
    private static final String ORACLE_PK = """
            SELECT cc.column_name FROM sys.all_constraints co JOIN sys.all_cons_columns cc
            ON cc.owner=co.owner AND cc.constraint_name=co.constraint_name
            WHERE co.owner=? AND co.table_name=? AND co.constraint_type='P' ORDER BY cc.position
            """;

    private static final String ORACLE_ADDITIONAL = """
            SELECT 'constraint:'||constraint_name FROM sys.all_constraints c WHERE c.owner=? AND c.table_name=?
              AND c.constraint_type<>'P' AND NOT (c.constraint_type='C' AND c.generated='GENERATED NAME')
            UNION ALL SELECT 'index:'||index_name FROM sys.all_indexes i WHERE i.owner=? AND i.table_name=?
              AND NOT EXISTS (SELECT 1 FROM sys.all_constraints p WHERE p.owner=i.owner AND p.index_name=i.index_name AND p.constraint_type='P')
            UNION ALL SELECT 'trigger:'||trigger_name FROM sys.all_triggers tr WHERE tr.owner=? AND tr.table_name=?
            UNION ALL SELECT 'table-options' FROM sys.all_tables b WHERE b.owner=? AND b.table_name=?
              AND (b.temporary<>'N' OR b.partitioned<>'NO' OR b.iot_type IS NOT NULL OR b.nested<>'NO')
            """;
    /** DM8 额外对象：主键之外的约束和触发器。 */
    /** Sybase ASE 16 目录：syscolumns/systypes 提供状态位事实；标识生成使用 status 128，任何
     *  status2 位都表示尚未验证的默认值或计算语义（作为生成表达式事实报告）。 */
    private static final String SYBASE_COLUMNS = """
            SELECT c.name AS column_name,
              CASE
                WHEN t.name IN ('decimal','numeric') THEN t.name + '(' + convert(varchar(4),c.prec) + ',' + convert(varchar(4),c.scale) + ')'
                WHEN t.name IN ('char','varchar','binary','varbinary') THEN t.name + '(' + convert(varchar(6),c.length) + ')'
                WHEN t.name IN ('unichar','univarchar') THEN t.name + '(' + convert(varchar(6),c.length/2) + ')'
                ELSE t.name
              END AS native_type,
              NULL AS default_expression,
              CASE WHEN c.status2 != 0 THEN 'UNVERIFIED' END AS generated_expression,
              CASE WHEN (c.status & 128) != 0 THEN 'IDENTITY' END AS identity_clause,
              CASE WHEN (c.status & 8) != 0 THEN 1 ELSE 0 END AS is_nullable,
              CASE WHEN t.name='money' THEN 19 WHEN t.name='smallmoney' THEN 10 ELSE c.prec END AS numeric_precision,
              CASE WHEN t.name IN ('money','smallmoney') THEN 4 ELSE c.scale END AS numeric_scale,
              CASE WHEN t.name IN ('unichar','univarchar') THEN c.length/2
                   WHEN t.name IN ('char','varchar','binary','varbinary') THEN c.length END AS character_length,
              CASE WHEN t.name IN ('decimal','numeric','money','smallmoney','char','varchar','unichar','univarchar','binary','varbinary') THEN 1 ELSE 0 END AS parameters_visible,
              NULL AS character_set, NULL AS collation_name, NULL AS column_comment
            FROM syscolumns c JOIN systypes t ON t.usertype=c.usertype
            WHERE c.id=OBJECT_ID(?) ORDER BY c.colid
            """;

    /** Sybase 额外对象：非主键约束和触发器（主键由 JDBC 元数据路径读取）。 */
    private static final String SYBASE_ADDITIONAL = """
            SELECT 'constraint:'||o.name FROM sysconstraints c JOIN sysobjects o ON o.id=c.constrid
              WHERE c.tableid=OBJECT_ID(?) AND o.type<>'P'
            UNION ALL SELECT 'trigger:'||o.name FROM sysobjects o WHERE o.type='TR' AND o.deltrig=OBJECT_ID(?)
            """;

    /** DB2 LUW 12.1 目录：SYSCAT.COLUMNS 提供逐列代码页；VARCHAR 按字节计数，GRAPHIC 按
     *  双字节字符计数，FOR BIT DATA 列的代码页为 0。标识生成方式为 A/D。 */
    private static final String DB2_COLUMNS = """
            SELECT COLNAME AS column_name,
              CASE
                WHEN TYPENAME IN ('DECIMAL','NUMERIC') THEN 'decimal(' || LENGTH || ',' || SCALE || ')'
                WHEN TYPENAME='CHARACTER' THEN 'character(' || LENGTH || ')' || CASE WHEN CODEPAGE=0 THEN ' for bit data' ELSE '' END
                WHEN TYPENAME='VARCHAR' THEN 'varchar(' || LENGTH || ')' || CASE WHEN CODEPAGE=0 THEN ' for bit data' ELSE '' END
                WHEN TYPENAME IN ('GRAPHIC','VARGRAPHIC') THEN LOWER(TYPENAME) || '(' || LENGTH || ')'
                WHEN TYPENAME='TIMESTAMP' THEN 'timestamp(' || SCALE || ')'
                WHEN TYPENAME IN ('CLOB','BLOB','DBCLOB') THEN LOWER(TYPENAME) || '(' || LENGTH || ')'
                ELSE LOWER(TYPENAME)
              END AS native_type,
              DEFAULT AS default_expression,
              CASE WHEN GENERATED='A' AND IDENTITY='N' THEN TEXT END AS generated_expression,
              CASE WHEN IDENTITY='Y' THEN CASE GENERATED WHEN 'A' THEN 'GENERATED ALWAYS AS IDENTITY' ELSE 'GENERATED BY DEFAULT AS IDENTITY' END END AS identity_clause,
              CASE WHEN NULLS='Y' THEN 1 ELSE 0 END AS is_nullable,
              CASE WHEN TYPENAME IN ('DECIMAL','NUMERIC') THEN LENGTH ELSE 0 END AS numeric_precision,
              CASE WHEN TYPENAME IN ('DECIMAL','NUMERIC') THEN SCALE ELSE 0 END AS numeric_scale,
              CASE WHEN TYPENAME IN ('CHARACTER','VARCHAR','GRAPHIC','VARGRAPHIC','LONG VARCHAR') THEN LENGTH END AS character_length,
              CASE WHEN TYPENAME IN ('DECIMAL','NUMERIC','CHARACTER','VARCHAR','GRAPHIC','VARGRAPHIC') THEN 1 ELSE 0 END AS parameters_visible,
              CASE WHEN CODEPAGE>0 THEN CAST(CODEPAGE AS VARCHAR(10)) END AS character_set,
              NULL AS collation_name, NULL AS column_comment
            FROM SYSCAT.COLUMNS WHERE TABSCHEMA=? AND TABNAME=? ORDER BY COLNO
            """;

    /** DB2 额外对象：非主键约束、触发器和非主键索引。 */
    private static final String DB2_ADDITIONAL = """
            SELECT 'constraint:'||CONSTNAME FROM SYSCAT.TABCONST WHERE TABSCHEMA=? AND TABNAME=? AND TYPE<>'P'
            UNION ALL SELECT 'trigger:'||TRIGNAME FROM SYSCAT.TRIGGERS WHERE TABSCHEMA=? AND TABNAME=?
            UNION ALL SELECT 'index:'||INDNAME FROM SYSCAT.INDEXES WHERE TABSCHEMA=? AND TABNAME=? AND UNIQUERULE<>'P'
            """;

    private static final String DM_ADDITIONAL = """
            SELECT 'constraint:'||CONSTRAINT_NAME FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_TYPE<>'P'
            UNION ALL SELECT 'trigger:'||TRIGGER_NAME FROM SYS.ALL_TRIGGERS tr WHERE tr.TABLE_OWNER=? AND tr.TABLE_NAME=?
            """;

    /** GBase 8a 8.6.2.43 information_schema.COLUMNS：没有 GENERATION_EXPRESSION 列（不存在生成表达式）。 */
    private static final String GBASE8A_COLUMNS = """
            SELECT COLUMN_NAME AS column_name, COLUMN_TYPE AS native_type, COLUMN_DEFAULT AS default_expression,
              NULL AS generated_expression,
              CASE WHEN EXTRA LIKE '%auto_increment%' THEN 'AUTO_INCREMENT' END AS identity_clause,
              IS_NULLABLE='YES' AS is_nullable, NUMERIC_PRECISION AS numeric_precision, NUMERIC_SCALE AS numeric_scale,
              CHARACTER_MAXIMUM_LENGTH AS character_length, TRUE AS parameters_visible,
              CHARACTER_SET_NAME AS character_set, COLLATION_NAME AS collation_name,
              COLUMN_COMMENT AS column_comment, EXTRA AS extra
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION
            """;

    private static final String POSTGRES_PK = """
            SELECT a.attname FROM pg_catalog.pg_constraint p JOIN pg_catalog.pg_class c ON c.oid=p.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            CROSS JOIN LATERAL unnest(p.conkey) WITH ORDINALITY k(attnum,position)
            JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum=k.attnum
            WHERE n.nspname=? AND c.relname=? AND p.contype='p' ORDER BY k.position
            """;

    private static final String MYSQL_PK = """
            SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION
            """;

    private static final String POSTGRES_ADDITIONAL = """
            WITH relation AS (SELECT c.* FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname=? AND c.relname=?)
            SELECT 'constraint:'||p.conname FROM pg_catalog.pg_constraint p JOIN relation c ON c.oid=p.conrelid WHERE p.contype NOT IN ('p','n')
            UNION ALL SELECT 'index:'||i.indexrelid::regclass::text FROM pg_catalog.pg_index i JOIN relation c ON c.oid=i.indrelid WHERE NOT i.indisprimary
            UNION ALL SELECT 'trigger:'||t.tgname FROM pg_catalog.pg_trigger t JOIN relation c ON c.oid=t.tgrelid WHERE NOT t.tgisinternal
            UNION ALL SELECT 'table-options' FROM relation WHERE relkind<>'r' OR relrowsecurity OR relispartition
              OR reloptions IS NOT NULL OR relpersistence<>'p'
              OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=relation.oid OR i.inhparent=relation.oid)
            """;

    /** PostgreSQL 独立证明策略和重写规则存在；RLS 未启用也不能将已有策略报告为不存在。 */
    private static final String POSTGRES_ADDITIONAL_WITH_POLICIES = POSTGRES_ADDITIONAL + """
            UNION ALL SELECT 'policy:'||p.polname FROM pg_catalog.pg_policy p JOIN relation c ON c.oid=p.polrelid
            UNION ALL SELECT 'rule:'||r.rulename FROM pg_catalog.pg_rewrite r JOIN relation c ON c.oid=r.ev_class
            """;

    private static final String MYSQL_ADDITIONAL = """
            WITH target AS (SELECT ? AS schema_name, ? AS table_name)
            SELECT CONCAT('constraint:',CONSTRAINT_NAME) FROM information_schema.TABLE_CONSTRAINTS c JOIN target t
              ON c.TABLE_SCHEMA=t.schema_name AND c.TABLE_NAME=t.table_name WHERE CONSTRAINT_TYPE<>'PRIMARY KEY'
            UNION ALL SELECT DISTINCT CONCAT('index:',INDEX_NAME) FROM information_schema.STATISTICS i JOIN target t
              ON i.TABLE_SCHEMA=t.schema_name AND i.TABLE_NAME=t.table_name WHERE INDEX_NAME<>'PRIMARY'
            UNION ALL SELECT CONCAT('trigger:',TRIGGER_NAME) FROM information_schema.TRIGGERS g JOIN target t
              ON g.EVENT_OBJECT_SCHEMA=t.schema_name AND g.EVENT_OBJECT_TABLE=t.table_name
            UNION ALL SELECT DISTINCT 'partition' FROM information_schema.PARTITIONS p JOIN target t
              ON p.TABLE_SCHEMA=t.schema_name AND p.TABLE_NAME=t.table_name WHERE PARTITION_NAME IS NOT NULL
            UNION ALL SELECT 'table-options' FROM information_schema.TABLES b JOIN target t
              ON b.TABLE_SCHEMA=t.schema_name AND b.TABLE_NAME=t.table_name
              WHERE TABLE_TYPE<>'BASE TABLE' OR ENGINE<>'InnoDB' OR COALESCE(CREATE_OPTIONS,'')<>''
            """;

    /** GBase 8a 8.6.2.43 没有 CTE；MySQL 系额外对象的联合查询通过重复条件重写。 */
    private static final String GBASE8A_ADDITIONAL = """
            SELECT CONCAT('constraint:',CONSTRAINT_NAME) FROM information_schema.TABLE_CONSTRAINTS c
              WHERE c.TABLE_SCHEMA=? AND c.TABLE_NAME=? AND CONSTRAINT_TYPE<>'PRIMARY KEY'
            UNION ALL SELECT DISTINCT CONCAT('index:',INDEX_NAME) FROM information_schema.STATISTICS i
              WHERE i.TABLE_SCHEMA=? AND i.TABLE_NAME=? AND INDEX_NAME<>'PRIMARY'
            UNION ALL SELECT CONCAT('trigger:',TRIGGER_NAME) FROM information_schema.TRIGGERS g
              WHERE g.EVENT_OBJECT_SCHEMA=? AND g.EVENT_OBJECT_TABLE=?
            UNION ALL SELECT DISTINCT 'partition' FROM information_schema.PARTITIONS p
              WHERE p.TABLE_SCHEMA=? AND p.TABLE_NAME=? AND PARTITION_NAME IS NOT NULL
            UNION ALL SELECT 'table-options' FROM information_schema.TABLES b
              WHERE b.TABLE_SCHEMA=? AND b.TABLE_NAME=?
                AND (TABLE_TYPE<>'BASE TABLE' OR ENGINE<>'InnoDB' OR COALESCE(CREATE_OPTIONS,'')<>'')
            """;
}
