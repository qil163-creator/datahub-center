package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.Optional;

/**
 * Selects the configured namespace consumed by DB Switch metadata/table APIs.
 * A returned name is exact configuration, not a verified catalog identity or authorization.
 * This method neither opens connections nor derives defaults from URLs, users or search paths.
 */
public final class DatabaseNamespaceResolver {

    /**
     * 初始化 {@code DatabaseNamespaceResolver}。
     */
    private DatabaseNamespaceResolver() {
    }

    /**
     * An explicit schema always wins. Only products whose implemented table APIs use the
     * database/catalog as their namespace can fall back to {@code databaseName}. SQLite,
     * InfluxDB and Elasticsearch use the defaults already established by their implementations.
     * Empty means that the caller must request/resolve a schema before a table operation;
     * connection-only operations need not invent one. Significant whitespace and case survive.
     */
    public static Optional<String> resolve(ProductTypeEnum product, String databaseName, String schemaName) {
        if (present(schemaName)) {
            return Optional.of(schemaName);
        }
        if (product == null) {
            return Optional.empty();
        }
        String fixedNamespace = switch (product) {
            case SQLITE3 -> "main";
            case INFLUXDB3 -> "iox";
            case ELASTICSEARCH -> "es";
            default -> null;
        };
        if (fixedNamespace != null) {
            return Optional.of(fixedNamespace);
        }
        boolean databaseNamespace = switch (product) {
            // MySQL-compatible metadata uses TABLE_SCHEMA/catalog; OLAP tables use database.table.
            case MYSQL, MARIADB, TIDB, GBASE8A, DORIS, STARROCKS,
                 CLICKHOUSE, HIVE, MONGODB, TDENGINE -> true;
            // These products have a schema/owner separate from the connection database.
            case POSTGRESQL, GREENPLUM, KINGBASE, HIGHGO, OPENGAUSS, UXDB,
                 SQLSERVER, SQLSERVER2000, ORACLE, DM, DB2, OSCAR, SYBASE -> false;
            // OceanBase requires explicit configuration until its compatibility mode is observed.
            // Fixed-namespace products have already returned above.
            case OCEANBASE, UNKNOWN, SQLITE3, INFLUXDB3, ELASTICSEARCH -> false;
            // 仅测连产品不声明元数据命名空间。
            case GBASE8C, GAUSSDB, PHOENIX, SAPHANA, PRESTO, ODPS, NEO4J, MILVUS, INFLUXDB2 -> false;
        };
        return databaseNamespace && present(databaseName) ? Optional.of(databaseName) : Optional.empty();
    }

    /**
     * 返回当前存在性。
     */
    private static boolean present(String name) {
        if (name != null && name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A database namespace must not contain NUL");
        }
        return name != null && !name.isBlank();
    }
}
