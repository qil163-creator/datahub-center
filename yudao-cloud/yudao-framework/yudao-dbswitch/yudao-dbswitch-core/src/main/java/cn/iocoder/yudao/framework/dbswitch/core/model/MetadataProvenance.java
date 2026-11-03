package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.time.Instant;

/** 观测来源信息，不是连接凭据、授权或结构锁。 */
public record MetadataProvenance(ProductTypeEnum product, String serverProduct, String serverVersion,
                                 String method, Instant observedAt) {

    /**
     * 校验构造参数并初始化 {@code MetadataProvenance}。
     */
    public MetadataProvenance {
        java.util.Objects.requireNonNull(product);
        java.util.Objects.requireNonNull(serverProduct);
        java.util.Objects.requireNonNull(serverVersion);
        java.util.Objects.requireNonNull(method);
        java.util.Objects.requireNonNull(observedAt);
    }

    /**
     * 返回当前未知。
     */
    public static MetadataProvenance unknown() {
        return new MetadataProvenance(ProductTypeEnum.UNKNOWN, "", "", "UNKNOWN", Instant.EPOCH);
    }

    /**
     * 仅由 MARIADB_CATALOG_V1 读取器通过 MariaDB Connector/J 3.x 观测到的 MariaDB 11.4+。
     */
    public boolean nativeProduct(ProductTypeEnum target) {
        return product == target && !serverVersion.isBlank()
                && ((target == ProductTypeEnum.POSTGRESQL && "PostgreSQL".equals(serverProduct))
                || (target == ProductTypeEnum.MYSQL && "MySQL".equals(serverProduct)
                && !serverVersion.toLowerCase(java.util.Locale.ROOT).contains("tidb")
                && !serverVersion.toLowerCase(java.util.Locale.ROOT).contains("mariadb"))
                || (target == ProductTypeEnum.SQLSERVER && "Microsoft SQL Server".equals(serverProduct)
                && serverVersion.matches("16[.].*") && "SQLSERVER16_CATALOG_V1".equals(method))
                || (target == ProductTypeEnum.TIDB && "TiDB".equals(serverProduct)
                && serverVersion.matches("v8\\.[0-9]+\\.[0-9]+(?:[-+].*)?"))
                // 仅由 MARIADB_CATALOG_V1 读取器通过 MariaDB Connector/J 3.x 观测到的 MariaDB 11.4+。
                || (target == ProductTypeEnum.MARIADB && "MariaDB".equals(serverProduct)
                && "MARIADB_CATALOG_V1".equals(method)
                && serverVersion.matches("11\\.(?:[4-9]|[1-9][0-9])\\.[0-9]+(?:-.*)?"))
                // 仅通过 PG12 形态目录读取器观测的 HighGo 6.0.4.2；version() 返回 HighGo 版本标识。
                || (target == ProductTypeEnum.HIGHGO && serverProduct.startsWith("HighGo Database 6 Release 6.0.4.2")
                && "HIGHGO_CATALOG_V1".equals(method) && serverVersion.equals("12.7"))
                // 仅通过 PG10 形态 ux_catalog 读取器观测的 UXsinoDB 2.1.0.3；version() 返回 UXDB 版本标识。
                || (target == ProductTypeEnum.UXDB && serverProduct.startsWith("UXsinoDB 2.1.0.3")
                && "UXDB_CATALOG_V1".equals(method) && serverVersion.equals("10.0"))
                // 仅通过固定目录读取器观测的 Greenplum 7.1.0；使用版本标识区分，而非 server_version。
                || (target == ProductTypeEnum.GREENPLUM && serverProduct.startsWith(
                "PostgreSQL 12.12 (Greenplum Database 7.1.0")
                && "GREENPLUM_CATALOG_V1".equals(method) && serverVersion.equals("12.12"))
                // 通过固定的 VERSION() 观测和 GBASE8A_CATALOG_V1 读取器识别的 GBase 8a 8.6.2.43。
                || (target == ProductTypeEnum.GBASE8A && "GBase".equals(serverProduct)
                && "GBASE8A_CATALOG_V1".equals(method) && serverVersion.matches("8\\.6\\.2\\.43-R7-free(?:\\..*)?"))
                // 通过固定 version_comment 观测识别的 Doris 4.x；JDBC 产品与版本显示的是 MySQL 兼容层。
                || (target == ProductTypeEnum.DORIS && "Doris".equals(serverProduct)
                && "DORIS_CATALOG_V1".equals(method) && serverVersion.matches("doris-[0-9]+\\.[0-9]+\\.[0-9]+(?:-.*)?"))
                // 通过固定 ORACLE_CATALOG_V1 读取器（v$version 版本标识）识别的 Oracle 23ai/26ai Free。
                || (target == ProductTypeEnum.ORACLE && "Oracle".equals(serverProduct)
                && "ORACLE_CATALOG_V1".equals(method) && serverVersion.startsWith("Oracle"))
                // 通过固定 version() 观测识别的 KingbaseES V008R006C009B0014 oracle 模式。
                || (target == ProductTypeEnum.KINGBASE && "KingbaseES".equals(serverProduct)
                && "KINGBASE_CATALOG_V1".equals(method) && serverVersion.startsWith("KingbaseES V008R006C009B0014"))
                // 通过固定 DM_CATALOG_V1 读取器识别的 DM8 8.1.5.60；JDBC 产品与版本来自 DM 服务器。
                || (target == ProductTypeEnum.DM && "DM DBMS".equals(serverProduct)
                && "DM_CATALOG_V1".equals(method) && serverVersion.matches("8\\.1\\.5\\.60.*"))
                // 通过固定 @@version 版本标识观测识别的 Sybase ASE 16.0 SP02 PL02。
                || (target == ProductTypeEnum.SYBASE && "Adaptive Server Enterprise".equals(serverProduct)
                && "SYBASE16_CATALOG_V1".equals(method)
                && serverVersion.startsWith("Adaptive Server Enterprise/16.0 SP02 PL02"))
                // 通过固定 SYSIBMADM.ENV_INST_INFO service_level 观测识别的 DB2 LUW 12.1.5.0。
                || (target == ProductTypeEnum.DB2 && "DB2/LINUXX8664".equals(serverProduct)
                && "DB2_1215_CATALOG_V1".equals(method) && "DB2 v12.1.5.0".equals(serverVersion))
                // 仅限 StarRocks 3.5.21-85fccf8；JDBC 产品与版本显示的是 MySQL 兼容层（8.0.33）。
                || (target == ProductTypeEnum.STARROCKS && "StarRocks".equals(serverProduct)
                && "STARROCKS_CATALOG_V1".equals(method) && serverVersion.matches("3\\.5\\.21-85fccf8"))
                // 通过固定 version() 观测识别的 ClickHouse 25.8.x。
                || (target == ProductTypeEnum.CLICKHOUSE && "ClickHouse".equals(serverProduct)
                && "CLICKHOUSE_CATALOG_V1".equals(method)
                && serverVersion.matches("25\\.8\\.[0-9]+(?:\\.[0-9]+)?"))
                // 通过 SELECT SERVER_VERSION() 识别的 TDengine 3.3.8.x；JDBC 主版本为 3，不能用于固定服务器版本。
                || (target == ProductTypeEnum.TDENGINE && "TDengine".equals(serverProduct)
                && "TDENGINE_CATALOG_V1".equals(method) && serverVersion.matches("3\\.3\\.8\\.[0-9]+"))
                // 仅限 InfluxDB 3 Core 3.11.5。JDBC 报告 InfluxDB IOx / 2，不能据此满足本条件。
                || (target == ProductTypeEnum.INFLUXDB3 && "InfluxDB 3 Core".equals(serverProduct)
                && "INFLUXDB3_CATALOG_V1".equals(method) && "3.11.5".equals(serverVersion))
                // 使用文件数据库验证的 SQLite 目录版本；读取器还会检查 sqlite_version()。
                || (target == ProductTypeEnum.SQLITE3 && "SQLite".equals(serverProduct)
                && "SQLITE_CATALOG_V1".equals(method)
                && ("3.31.1".equals(serverVersion) || "3.49.1".equals(serverVersion)))
                // openGauss 7.0.0-RC3，server_version 9.2.4，sql_compatibility A。其他模式仍未验证。
                || (target == ProductTypeEnum.OPENGAUSS
                && "(openGauss 7.0.0-RC3 build 01b7e318) compiled at 2026-03-25 18:12:24 commit 0 last mr 9114".equals(
                serverProduct)
                && "OPENGAUSS_CATALOG_V1".equals(method) && "9.2.4".equals(serverVersion))
                // OceanBase CE 4.3.5.6 MySQL 模式。JDBC 产品版本 4.3.5.6 和 Oracle 模式不符合此固定条件。
                || (target == ProductTypeEnum.OCEANBASE && "OceanBase".equals(serverProduct)
                && "OCEANBASE_CATALOG_V1".equals(method) && "5.7.25-OceanBase_CE-v4.3.5.6".equals(serverVersion))
                // Oscar JDBC 7.20 对应已探测的 251217 版本标识。JDBC 主版本 7 不能用于固定服务器版本。
                || (target == ProductTypeEnum.OSCAR && "OSCAR".equals(serverProduct)
                && ("OSCAR_JDBC_V1".equals(method) || "OSCAR_CATALOG_V1".equals(method))
                && cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog.verifiedVersion(
                serverVersion))
                // SQL Server 2000 8.00.760。不得复用 SQL Server 2022 16.* 的目录方法。
                || (target == ProductTypeEnum.SQLSERVER2000 && "Microsoft SQL Server".equals(serverProduct)
                && "SQLSERVER2000_CATALOG_V1".equals(method) && "8.00.760".equals(serverVersion))
                // Hive 4.1.0 的完整 version() 返回行。仅有 JDBC 4.1.0 不满足此固定条件。
                || (target == ProductTypeEnum.HIVE && "Apache Hive".equals(serverProduct)
                && "HIVE_CATALOG_V1".equals(method)
                && "4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb".equals(serverVersion)));
    }
}
