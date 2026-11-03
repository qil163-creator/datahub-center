package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.ArrayList;

/**
 * 行级规则的有界键集合摘要。计数和摘要来自同一条分组查询，不拼接两次读取的结果。
 * 支持 PostgreSQL 整数、text/varchar、UUID，以及 MySQL/TiDB 已验证有符号整数/VARCHAR/TEXT 的单列或 2–4 列复合键；
 * MySQL/TiDB/openGauss 支持已验证的有界 VARCHAR/TEXT；ClickHouse/StarRocks 显式支持已验证的原生文本类型。
 * 文本单键/混合复合键/机构均按产品隔离编码；Greenplum 支持独立验证的原生 VARCHAR/TEXT。
 * Doris/MariaDB/OceanBase 仍仅整数路径。
 * 扫描能力不代表治理关系身份或所有规则、版本均已验证。
 * 不推断数据库主键约束。类型与统计来自同一结果集，不把转换后的文本/小数当成整数。
 * 不持有共享状态、不记录 SQL/键值、不接管调用方连接或事务；只关闭本次 Statement/ResultSet。
 * SQL/JDBC 行数双上限限制驱动缓冲；超限返回不可用统计，由调用方决定是否另行做普通统计。
 */
public record AuditKeySnapshot(String sql, long total, long fail, long keyCount,
                               Integer jdbcType, String keyHash, boolean complete, String reasonCode,
                               String encoding) {

    /** 保留旧调用和缺少 encoding 的历史 JSON；旧类型推导只适用于已有 PG/整数协议。 */
    public AuditKeySnapshot(String sql, long total, long fail, long keyCount,
            Integer jdbcType, String keyHash, boolean complete, String reasonCode) {
        this(sql, total, fail, keyCount, jdbcType, keyHash, complete, reasonCode, null);
    }

    public static final int MAX_KEYS = 100_000;

    public static final String ALGORITHM = "SHA256_SORTED_INT64_V1";

    public static final String TYPED_ALGORITHM = "SHA256_SORTED_PG_TYPED_BYTES_V1";

    public static final String COMPOSITE_ALGORITHM = "SHA256_SORTED_PG_TUPLE_V1";

    public static final String MYSQL_TEXT_ALGORITHM = "SHA256_SORTED_MYSQL_UTF8_BYTES_V1";

    public static final String MYSQL_COMPOSITE_ALGORITHM = "SHA256_SORTED_MYSQL_TUPLE_V1";

    public static final String DORIS_COMPOSITE_ALGORITHM = "SHA256_SORTED_DORIS_INT_TUPLE_V1";

    public static final String DORIS_INTEGER_GROUP_ENCODING = "DORIS_SIGNED_INT64_DECIMAL_V1";

    public static final String MARIADB_COMPOSITE_ALGORITHM = "SHA256_SORTED_MARIADB_INT_TUPLE_V1";

    public static final String MARIADB_INTEGER_GROUP_ENCODING = "MARIADB_SIGNED_INT64_DECIMAL_V1";

    public static final String CLICKHOUSE_COMPOSITE_ALGORITHM = "SHA256_SORTED_CLICKHOUSE_INT_TUPLE_V1";

    public static final String CLICKHOUSE_INTEGER_GROUP_ENCODING = "CLICKHOUSE_SIGNED_INT64_DECIMAL_V1";

    public static final String CLICKHOUSE_TEXT_ALGORITHM = "SHA256_SORTED_CLICKHOUSE_UTF8_BYTES_V1";

    public static final String CLICKHOUSE_TYPED_COMPOSITE_ALGORITHM = "SHA256_SORTED_CLICKHOUSE_TYPED_TUPLE_V1";

    public static final String CLICKHOUSE_TEXT_GROUP_ENCODING = "CLICKHOUSE_EXACT_UTF8_GROUP_V1";

    public static final String OPENGAUSS_COMPOSITE_ALGORITHM = "SHA256_SORTED_OPENGAUSS_INT_TUPLE_V1";

    public static final String OPENGAUSS_INTEGER_GROUP_ENCODING = "OPENGAUSS_SIGNED_INT64_DECIMAL_V1";

    public static final String OPENGAUSS_TEXT_ALGORITHM = "SHA256_SORTED_OPENGAUSS_UTF8_BYTES_V1";

    public static final String OPENGAUSS_TYPED_COMPOSITE_ALGORITHM = "SHA256_SORTED_OPENGAUSS_TYPED_TUPLE_V1";

    public static final String OPENGAUSS_TEXT_GROUP_ENCODING = "OPENGAUSS_EXACT_UTF8_GROUP_V1";

    public static final String STARROCKS_COMPOSITE_ALGORITHM = "SHA256_SORTED_STARROCKS_INT_TUPLE_V1";

    public static final String STARROCKS_INTEGER_GROUP_ENCODING = "STARROCKS_SIGNED_INT64_DECIMAL_V1";

    public static final String STARROCKS_TEXT_ALGORITHM = "SHA256_SORTED_STARROCKS_UTF8_BYTES_V1";

    public static final String STARROCKS_TYPED_COMPOSITE_ALGORITHM = "SHA256_SORTED_STARROCKS_TYPED_TUPLE_V1";

    public static final String STARROCKS_TEXT_GROUP_ENCODING = "STARROCKS_EXACT_UTF8_GROUP_V1";

    public static final String TIDB_COMPOSITE_ALGORITHM = "SHA256_SORTED_TIDB_INT_TUPLE_V1";

    public static final String TIDB_INTEGER_GROUP_ENCODING = "TIDB_SIGNED_INT64_DECIMAL_V1";

    public static final String TIDB_TEXT_ALGORITHM = "SHA256_SORTED_TIDB_UTF8_BYTES_V1";

    public static final String TIDB_TYPED_COMPOSITE_ALGORITHM = "SHA256_SORTED_TIDB_TYPED_TUPLE_V1";

    public static final String TIDB_TEXT_GROUP_ENCODING = "TIDB_EXACT_UTF8_GROUP_V1";

    public static final String OCEANBASE_COMPOSITE_ALGORITHM = "SHA256_SORTED_OCEANBASE_INT_TUPLE_V1";

    public static final String OCEANBASE_INTEGER_GROUP_ENCODING = "OCEANBASE_SIGNED_INT64_DECIMAL_V1";

    public static final String GREENPLUM_COMPOSITE_ALGORITHM = "SHA256_SORTED_GREENPLUM_INT_TUPLE_V1";

    public static final String GREENPLUM_INTEGER_GROUP_ENCODING = "GREENPLUM_SIGNED_INT64_DECIMAL_V1";

    public static final String GREENPLUM_TEXT_ALGORITHM = "SHA256_SORTED_GREENPLUM_UTF8_BYTES_V1";

    public static final String GREENPLUM_TYPED_COMPOSITE_ALGORITHM = "SHA256_SORTED_GREENPLUM_TYPED_TUPLE_V1";

    public static final String GREENPLUM_TEXT_GROUP_ENCODING = "GREENPLUM_EXACT_UTF8_GROUP_V1";

    public static final String HIGHGO_COMPOSITE_ALGORITHM = "SHA256_SORTED_HIGHGO_INT_TUPLE_V1";

    public static final String HIGHGO_INTEGER_GROUP_ENCODING = "HIGHGO_SIGNED_INT64_DECIMAL_V1";

    public static final String DM_COMPOSITE_ALGORITHM = "SHA256_SORTED_DM_INT_TUPLE_V1";

    public static final String DM_INTEGER_GROUP_ENCODING = "DM_SIGNED_INT64_DECIMAL_V1";

    public static final String KINGBASE_COMPOSITE_ALGORITHM = "SHA256_SORTED_KINGBASE_INT_TUPLE_V1";

    public static final String KINGBASE_INTEGER_GROUP_ENCODING = "KINGBASE_SIGNED_INT64_DECIMAL_V1";

    public static final String OSCAR_COMPOSITE_ALGORITHM = "SHA256_SORTED_OSCAR_INT_TUPLE_V1";

    public static final String OSCAR_INTEGER_GROUP_ENCODING = "OSCAR_SIGNED_INT64_DECIMAL_V1";

    public static final String UXDB_COMPOSITE_ALGORITHM = "SHA256_SORTED_UXDB_INT_TUPLE_V1";

    public static final String UXDB_INTEGER_GROUP_ENCODING = "UXDB_SIGNED_INT64_DECIMAL_V1";

    public static final String TDENGINE_COMPOSITE_ALGORITHM = "SHA256_SORTED_TDENGINE_INT_TUPLE_V1";

    public static final String TDENGINE_INTEGER_GROUP_ENCODING = "TDENGINE_SIGNED_INT64_DECIMAL_V1";

    /** 仅满足组件扫描协议：原生源代次尚未通过治理验收。 */
    public static final String INFLUXDB_COMPOSITE_ALGORITHM = "SHA256_SORTED_INFLUXDB3_INT_TUPLE_V1";

    public static final String INFLUXDB_INTEGER_GROUP_ENCODING = "INFLUXDB3_SIGNED_INT64_DECIMAL_V1";

    /** 在元数据存储、物理数据代次及原生访问范围通过验证前，仅限组件内部使用。 */
    public static final String HIVE_COMPOSITE_ALGORITHM = "SHA256_SORTED_HIVE_INT_TUPLE_V1";

    public static final String HIVE_INTEGER_GROUP_ENCODING = "HIVE_SIGNED_INT64_DECIMAL_V1";

    /** 仅用于扫描：此编码尚未验证 SQL Server 关系或恢复身份。 */
    public static final String SQLSERVER_COMPOSITE_ALGORITHM = "SHA256_SORTED_SQLSERVER_INT_TUPLE_V1";

    public static final String SQLSERVER_INTEGER_GROUP_ENCODING = "SQLSERVER_SIGNED_INT64_DECIMAL_V1";

    public static final String ORACLE_COMPOSITE_ALGORITHM = "SHA256_SORTED_ORACLE_NUMBER_INT64_TUPLE_V1";

    public static final String ORACLE_INTEGER_GROUP_ENCODING = "ORACLE_NUMBER_INT64_DECIMAL_V1";

    /** MySQL 原生有符号整数分组的精确十进制身份，不适用于文本 collation 分组。 */
    public static final String MYSQL_INTEGER_GROUP_ENCODING = "MYSQL_SIGNED_INT64_DECIMAL_V1";

    public static final String MYSQL_TEXT_GROUP_ENCODING = "MYSQL_EXACT_UTF8_GROUP_V1";

    public static final int MAX_KEY_COLUMNS = 4;

    /** 每个单列键/整个编码元组的字节上限，不截断。键载荷约 25.6 MB 上限，不含协议/驱动对象开销。 */
    public static final int MAX_KEY_BYTES = 256;

    public static final int MAX_GROUP_KEY_LENGTH = 64;

    /** 产品文本显式声明协议，不能仅凭 JDBC 类型冒用 PG 文本协议。 */
    public String algorithm() {
        if (encoding != null) {
            return encoding;
        }
        if (Integer.valueOf(Types.STRUCT).equals(jdbcType)) {
            return COMPOSITE_ALGORITHM;
        }
        return isTyped(jdbcType) ? TYPED_ALGORITHM : ALGORITHM;
    }

    /**
     * 判断是否支持字符编码。
     */
    public static boolean supportsEncoding(String algorithm, Integer type) {
        return ALGORITHM.equals(algorithm) && isInteger(type)
                || TYPED_ALGORITHM.equals(algorithm) && isTyped(type)
                || (MYSQL_TEXT_ALGORITHM.equals(algorithm) || TIDB_TEXT_ALGORITHM.equals(algorithm)
                || OPENGAUSS_TEXT_ALGORITHM.equals(algorithm) || GREENPLUM_TEXT_ALGORITHM.equals(algorithm)) && isText(
                type)
                || (CLICKHOUSE_TEXT_ALGORITHM.equals(algorithm) || STARROCKS_TEXT_ALGORITHM.equals(algorithm))
                && Integer.valueOf(Types.VARCHAR).equals(type)
                || isCompositeEncoding(algorithm) && Integer.valueOf(Types.STRUCT).equals(type);
    }

    /**
     * 判断复合编码。
     */
    public static boolean isCompositeEncoding(String algorithm) {
        return COMPOSITE_ALGORITHM.equals(algorithm) || MYSQL_COMPOSITE_ALGORITHM.equals(algorithm)
                || DORIS_COMPOSITE_ALGORITHM.equals(algorithm) || MARIADB_COMPOSITE_ALGORITHM.equals(algorithm)
                || CLICKHOUSE_COMPOSITE_ALGORITHM.equals(algorithm) || OPENGAUSS_COMPOSITE_ALGORITHM.equals(algorithm)
                || TIDB_COMPOSITE_ALGORITHM.equals(algorithm) || STARROCKS_COMPOSITE_ALGORITHM.equals(algorithm)
                || TIDB_TYPED_COMPOSITE_ALGORITHM.equals(algorithm) || CLICKHOUSE_TYPED_COMPOSITE_ALGORITHM.equals(
                algorithm)
                || OPENGAUSS_TYPED_COMPOSITE_ALGORITHM.equals(algorithm)
                || STARROCKS_TYPED_COMPOSITE_ALGORITHM.equals(algorithm)
                || GREENPLUM_TYPED_COMPOSITE_ALGORITHM.equals(algorithm)
                || OCEANBASE_COMPOSITE_ALGORITHM.equals(algorithm) || TDENGINE_COMPOSITE_ALGORITHM.equals(algorithm)
                || GREENPLUM_COMPOSITE_ALGORITHM.equals(algorithm)
                || HIGHGO_COMPOSITE_ALGORITHM.equals(algorithm)
                || DM_COMPOSITE_ALGORITHM.equals(algorithm)
                || KINGBASE_COMPOSITE_ALGORITHM.equals(algorithm)
                || OSCAR_COMPOSITE_ALGORITHM.equals(algorithm)
                || UXDB_COMPOSITE_ALGORITHM.equals(algorithm)
                || INFLUXDB_COMPOSITE_ALGORITHM.equals(algorithm) || HIVE_COMPOSITE_ALGORITHM.equals(algorithm)
                || SQLSERVER_COMPOSITE_ALGORITHM.equals(algorithm)
                || ORACLE_COMPOSITE_ALGORITHM.equals(algorithm);
    }

    /** 产品与编码必须共同成立；通用整数协议不意味着所有数据库已经验证。 */
    public static boolean supportsEncoding(String product, String algorithm, Integer type) {
        return ProductTypeEnum.POSTGRESQL.name().equals(product)
                && (ALGORITHM.equals(algorithm) || TYPED_ALGORITHM.equals(algorithm) || COMPOSITE_ALGORITHM.equals(
                algorithm))
                && supportsEncoding(algorithm, type)
                || ProductTypeEnum.MYSQL.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || MYSQL_TEXT_ALGORITHM.equals(algorithm) && isText(type)
                || MYSQL_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.DORIS.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || DORIS_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.MARIADB.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || MARIADB_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.CLICKHOUSE.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || CLICKHOUSE_TEXT_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.VARCHAR).equals(type)
                || (CLICKHOUSE_COMPOSITE_ALGORITHM.equals(algorithm) || CLICKHOUSE_TYPED_COMPOSITE_ALGORITHM.equals(
                algorithm))
                && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.OPENGAUSS.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || OPENGAUSS_TEXT_ALGORITHM.equals(algorithm) && isText(type)
                || (OPENGAUSS_COMPOSITE_ALGORITHM.equals(algorithm) || OPENGAUSS_TYPED_COMPOSITE_ALGORITHM.equals(
                algorithm))
                && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.STARROCKS.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || STARROCKS_TEXT_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.VARCHAR).equals(type)
                || (STARROCKS_COMPOSITE_ALGORITHM.equals(algorithm) || STARROCKS_TYPED_COMPOSITE_ALGORITHM.equals(
                algorithm))
                && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.TIDB.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || TIDB_TEXT_ALGORITHM.equals(algorithm) && isText(type)
                || (TIDB_COMPOSITE_ALGORITHM.equals(algorithm) || TIDB_TYPED_COMPOSITE_ALGORITHM.equals(algorithm))
                && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.OCEANBASE.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || OCEANBASE_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.TDENGINE.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || TDENGINE_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.GREENPLUM.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || GREENPLUM_TEXT_ALGORITHM.equals(algorithm) && isText(type)
                || (GREENPLUM_COMPOSITE_ALGORITHM.equals(algorithm) || GREENPLUM_TYPED_COMPOSITE_ALGORITHM.equals(
                algorithm))
                && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.HIGHGO.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || HIGHGO_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.DM.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || DM_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.KINGBASE.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || KINGBASE_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.OSCAR.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || OSCAR_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.UXDB.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || UXDB_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type))
                || ProductTypeEnum.ORACLE.name().equals(product)
                && (ALGORITHM.equals(algorithm) && isInteger(type)
                || ORACLE_COMPOSITE_ALGORITHM.equals(algorithm) && Integer.valueOf(Types.STRUCT).equals(type));
    }

    /** 限制为 2–4 个有序普通列，不接受表达式、重复（含 PG 大小写折叠）或隐式截断的标识符。 */
    public static boolean validCompositeColumns(List<?> columns) {
        if (columns == null || columns.size() < 2 || columns.size() > MAX_KEY_COLUMNS) {
            return false;
        }
        var names = new HashSet<String>();
        for (Object column : columns) {
            if (!(column instanceof String name) || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                    || !names.add(name.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取复合。
     */
    public static AuditKeySnapshot readComposite(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, List<String> columns, int maxKeys, int timeoutSeconds) throws SQLException {
        if (usesProjectedKeys(context)) {
            var result = ProjectedCompositeKeySnapshot.read(connection, generator, context, columns,
                    null, maxKeys, 1, timeoutSeconds);
            return result.complete() ? result.groups().getFirst().snapshot()
                    : new AuditKeySnapshot(result.sql(), -1, -1, -1, Types.STRUCT, null, false,
                    result.reasonCode(), projectedCompositeAlgorithm(context));
        }
        String sql = buildCompositeSql(generator, context, columns, null, maxKeys);
        return scan(connection, sql, maxKeys, timeoutSeconds, COMPOSITE_ALGORITHM);
    }

    /**
     * 读取分组复合。
     */
    public static Grouped readGroupedComposite(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, List<String> columns, String groupColumn, int maxKeys, int maxGroups,
            int timeoutSeconds) throws SQLException {
        validateGroup(groupColumn, maxGroups, timeoutSeconds);
        if (usesProjectedKeys(context)) {
            return ProjectedCompositeKeySnapshot.read(connection, generator, context, columns,
                    groupColumn, maxKeys, maxGroups, timeoutSeconds);
        }
        String sql = buildCompositeSql(generator, context, columns, groupColumn, maxKeys);
        return scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds, COMPOSITE_ALGORITHM);
    }

    /**
     * 判断整数。
     */
    private static boolean isInteger(Integer type) {
        return type != null && (type == Types.SMALLINT || type == Types.INTEGER || type == Types.BIGINT);
    }

    /**
     * 判断当前产品是否使用投影后的键值参与分组统计。
     */
    private static boolean usesProjectedKeys(AuditRuleContext context) {
        return context.getProductType() == ProductTypeEnum.MYSQL || context.getProductType() == ProductTypeEnum.DORIS
                || context.getProductType() == ProductTypeEnum.MARIADB || context.getProductType() == ProductTypeEnum.CLICKHOUSE
                || context.getProductType() == ProductTypeEnum.OPENGAUSS || context.getProductType() == ProductTypeEnum.STARROCKS
                || context.getProductType() == ProductTypeEnum.TIDB || context.getProductType() == ProductTypeEnum.OCEANBASE
                || context.getProductType() == ProductTypeEnum.TDENGINE || context.getProductType() == ProductTypeEnum.INFLUXDB3
                || context.getProductType() == ProductTypeEnum.HIVE || context.getProductType() == ProductTypeEnum.SQLSERVER
                || context.getProductType() == ProductTypeEnum.ORACLE || context.getProductType() == ProductTypeEnum.GREENPLUM
                || context.getProductType() == ProductTypeEnum.HIGHGO || context.getProductType() == ProductTypeEnum.DM
                || context.getProductType() == ProductTypeEnum.KINGBASE || context.getProductType() == ProductTypeEnum.OSCAR
                || context.getProductType() == ProductTypeEnum.UXDB;
    }

    /**
     * 返回当前产品的复合键投影算法标识。
     */
    static String projectedCompositeAlgorithm(AuditRuleContext context) {
        return switch (context.getProductType()) {
            case MYSQL -> MYSQL_COMPOSITE_ALGORITHM;
            case DORIS -> DORIS_COMPOSITE_ALGORITHM;
            case MARIADB -> MARIADB_COMPOSITE_ALGORITHM;
            case CLICKHOUSE -> CLICKHOUSE_COMPOSITE_ALGORITHM;
            case OPENGAUSS -> OPENGAUSS_COMPOSITE_ALGORITHM;
            case STARROCKS -> STARROCKS_COMPOSITE_ALGORITHM;
            case TIDB -> TIDB_COMPOSITE_ALGORITHM;
            case OCEANBASE -> OCEANBASE_COMPOSITE_ALGORITHM;
            case TDENGINE -> TDENGINE_COMPOSITE_ALGORITHM;
            case INFLUXDB3 -> INFLUXDB_COMPOSITE_ALGORITHM;
            case HIVE -> HIVE_COMPOSITE_ALGORITHM;
            case SQLSERVER -> SQLSERVER_COMPOSITE_ALGORITHM;
            case ORACLE -> ORACLE_COMPOSITE_ALGORITHM;
            case GREENPLUM -> GREENPLUM_COMPOSITE_ALGORITHM;
            case HIGHGO -> HIGHGO_COMPOSITE_ALGORITHM;
            case DM -> DM_COMPOSITE_ALGORITHM;
            case KINGBASE -> KINGBASE_COMPOSITE_ALGORITHM;
            case OSCAR -> OSCAR_COMPOSITE_ALGORITHM;
            case UXDB -> UXDB_COMPOSITE_ALGORITHM;
            default -> throw new UnsupportedOperationException("Unverified composite key product");
        };
    }

    /**
     * 返回当前产品的整数分组键编码标识。
     */
    static String projectedIntegerGroupEncoding(AuditRuleContext context) {
        return switch (context.getProductType()) {
            case MYSQL -> MYSQL_INTEGER_GROUP_ENCODING;
            case DORIS -> DORIS_INTEGER_GROUP_ENCODING;
            case MARIADB -> MARIADB_INTEGER_GROUP_ENCODING;
            case CLICKHOUSE -> CLICKHOUSE_INTEGER_GROUP_ENCODING;
            case OPENGAUSS -> OPENGAUSS_INTEGER_GROUP_ENCODING;
            case STARROCKS -> STARROCKS_INTEGER_GROUP_ENCODING;
            case TIDB -> TIDB_INTEGER_GROUP_ENCODING;
            case OCEANBASE -> OCEANBASE_INTEGER_GROUP_ENCODING;
            case TDENGINE -> TDENGINE_INTEGER_GROUP_ENCODING;
            case INFLUXDB3 -> INFLUXDB_INTEGER_GROUP_ENCODING;
            case HIVE -> HIVE_INTEGER_GROUP_ENCODING;
            case SQLSERVER -> SQLSERVER_INTEGER_GROUP_ENCODING;
            case ORACLE -> ORACLE_INTEGER_GROUP_ENCODING;
            case GREENPLUM -> GREENPLUM_INTEGER_GROUP_ENCODING;
            case HIGHGO -> HIGHGO_INTEGER_GROUP_ENCODING;
            case DM -> DM_INTEGER_GROUP_ENCODING;
            case KINGBASE -> KINGBASE_INTEGER_GROUP_ENCODING;
            case OSCAR -> OSCAR_INTEGER_GROUP_ENCODING;
            case UXDB -> UXDB_INTEGER_GROUP_ENCODING;
            default -> throw new UnsupportedOperationException("Unverified integer group product");
        };
    }

    /**
     * 判断带类型。
     */
    private static boolean isTyped(Integer type) {
        return type != null && (type == Types.LONGVARCHAR || type == Types.VARCHAR || type == Types.OTHER);
    }

    /**
     * 判断文本。
     */
    private static boolean isText(Integer type) {
        return type != null && (type == Types.LONGVARCHAR || type == Types.VARCHAR);
    }

    /**
     * 判断当前路径是否支持文本分组键编码。
     */
    public static boolean supportsTextGroupEncoding(String product, String encoding) {
        return ProductTypeEnum.MYSQL.name().equals(product) && MYSQL_TEXT_GROUP_ENCODING.equals(encoding)
                || ProductTypeEnum.TIDB.name().equals(product) && TIDB_TEXT_GROUP_ENCODING.equals(encoding)
                || ProductTypeEnum.CLICKHOUSE.name().equals(product) && CLICKHOUSE_TEXT_GROUP_ENCODING.equals(encoding)
                || ProductTypeEnum.OPENGAUSS.name().equals(product) && OPENGAUSS_TEXT_GROUP_ENCODING.equals(encoding)
                || ProductTypeEnum.GREENPLUM.name().equals(product) && GREENPLUM_TEXT_GROUP_ENCODING.equals(encoding)
                || ProductTypeEnum.STARROCKS.name().equals(product) && STARROCKS_TEXT_GROUP_ENCODING.equals(encoding);
    }

    /**
     * 判断当前路径是否支持文本分组键编码。
     */
    public static boolean supportsTextGroupEncoding(String product, String encoding, Integer jdbcType) {
        return supportsTextGroupEncoding(product, encoding) && (ProductTypeEnum.CLICKHOUSE.name().equals(product)
                || ProductTypeEnum.STARROCKS.name().equals(product)
                ? Integer.valueOf(Types.VARCHAR).equals(jdbcType) : isText(jdbcType));
    }

    /**
     * 判断当前产品是否使用文本键投影协议。
     */
    private static boolean usesProjectedText(AuditRuleContext context) {
        return context.getProductType() == ProductTypeEnum.MYSQL || context.getProductType() == ProductTypeEnum.TIDB
                || context.getProductType() == ProductTypeEnum.CLICKHOUSE || context.getProductType() == ProductTypeEnum.OPENGAUSS
                || context.getProductType() == ProductTypeEnum.STARROCKS || context.getProductType() == ProductTypeEnum.GREENPLUM;
    }

    /**
     * 返回当前产品的文本键投影算法标识。
     */
    private static String projectedTextAlgorithm(AuditRuleContext context) {
        return switch (context.getProductType()) {
            case MYSQL -> MYSQL_TEXT_ALGORITHM;
            case TIDB -> TIDB_TEXT_ALGORITHM;
            case CLICKHOUSE -> CLICKHOUSE_TEXT_ALGORITHM;
            case OPENGAUSS -> OPENGAUSS_TEXT_ALGORITHM;
            case STARROCKS -> STARROCKS_TEXT_ALGORITHM;
            case GREENPLUM -> GREENPLUM_TEXT_ALGORITHM;
            default -> throw new UnsupportedOperationException("Unverified text key product");
        };
    }

    /**
     * 返回当前产品的文本分组键编码标识。
     */
    static String projectedTextGroupEncoding(AuditRuleContext context) {
        return switch (context.getProductType()) {
            case MYSQL -> MYSQL_TEXT_GROUP_ENCODING;
            case TIDB -> TIDB_TEXT_GROUP_ENCODING;
            case CLICKHOUSE -> CLICKHOUSE_TEXT_GROUP_ENCODING;
            case OPENGAUSS -> OPENGAUSS_TEXT_GROUP_ENCODING;
            case STARROCKS -> STARROCKS_TEXT_GROUP_ENCODING;
            case GREENPLUM -> GREENPLUM_TEXT_GROUP_ENCODING;
            default -> throw new UnsupportedOperationException("Unverified text group product");
        };
    }

    /**
     * 为已完成的分组统计结果补充相应键编码说明。
     */
    private static Grouped withProjectedGroupEncoding(Grouped result, AuditRuleContext context, boolean text) {
        return result.complete() ? new Grouped(result.sql(), true, null, result.groups(),
                text ? projectedTextGroupEncoding(context) : projectedIntegerGroupEncoding(context),
                result.groupJdbcType()) : result;
    }

    /** complete 只代表扫描完整；每个分组的键资格由 snapshot.complete 独立表达。 */
    public record Grouped(String sql, boolean complete, String reasonCode, List<Group> groups,
                          String groupEncoding, Integer groupJdbcType) {

        /**
         * 使用给定参数初始化 {@code Grouped}。
         */
        public Grouped(String sql, boolean complete, String reasonCode, List<Group> groups) {
            this(sql, complete, reasonCode, groups, null, null);
        }
    }

    public record Group(String key, AuditKeySnapshot snapshot) {

    }

    /**
     * 按当前产品键投影协议扫描受限数据，生成质量规则的键集合摘要。
     */
    public static AuditKeySnapshot read(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, int maxKeys,
            int timeoutSeconds) throws SQLException {
        if (usesProjectedKeys(context)) {
            var result = readProjectedInteger(connection, generator, context, column, maxKeys, timeoutSeconds);
            return usesProjectedText(context) && "KEY_TYPE_UNSUPPORTED".equals(result.reasonCode())
                    ? readProjectedText(connection, generator, context, column, maxKeys, timeoutSeconds) : result;
        }
        var result = read(connection, generator, context, column, maxKeys, timeoutSeconds, false);
        // 兼容已有整数 SQL/摘要；只有明确不支持的完整扫描才尝试新编码，且丢弃第一遍计数。
        return "KEY_TYPE_UNSUPPORTED".equals(result.reasonCode())
                ? read(connection, generator, context, column, maxKeys, timeoutSeconds, true) : result;
    }

    /**
     * 按当前产品键投影协议扫描受限数据，生成质量规则的键集合摘要。
     */
    private static AuditKeySnapshot read(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, int maxKeys,
            int timeoutSeconds, boolean typed) throws SQLException {
        String sql = buildSql(generator, context, column, maxKeys, typed);
        return scan(connection, sql, maxKeys, timeoutSeconds, typed ? TYPED_ALGORITHM : ALGORITHM);
    }

    /**
     * 扫描当前查询结果并收集受限的质量键摘要。
     */
    private static AuditKeySnapshot scan(Connection connection, String sql, int maxKeys,
            int timeoutSeconds, String algorithm) throws SQLException {
        return scan(connection, sql, maxKeys, timeoutSeconds, algorithm, null);
    }

    /**
     * 读取投影整数。
     */
    private static AuditKeySnapshot readProjectedInteger(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, int maxKeys, int timeoutSeconds) throws SQLException {
        validateProjectedKey(column, maxKeys, timeoutSeconds);
        // 非行级策略仍由原生成器拒绝。SQL 方言和原生类型证明交给 DB Switch，
        // 本组件只负责质量分组、完整性和已有 int64 摘要，不新增业务侧产品分支。
        String key = RestrictedSelectGuard.quotePhysicalIdentifier(context.getProductType(), column);
        var database = context.database();
        String grouped = database.limitGeneratedSelect(generator.buildGroupCountSql(context, key) + " ORDER BY 1 ASC",
                maxKeys + 1);
        var projection = database.integerKeyProjection(connection, "k", "groupKey");
        String sql = "SELECT " + projection.valueSql() + " AS " + quote(context, "keyValue") + ", k."
                + quote(context, "totalCount") + ", k." + quote(context, "errorCount") + ", "
                + projection.typeSql() + " AS " + quote(context, "keyType") + " FROM (" + grouped
                + ") k ORDER BY k." + quote(context, "groupKey") + " ASC";
        if (projection.requiresExplicitOuterLimit()) {
            sql = database.limitGeneratedSelect(sql, maxKeys + 1);
        }
        return scan(connection, sql, maxKeys, timeoutSeconds, ALGORITHM, projection);
    }

    /**
     * 拒绝当前实现未支持的 {@code validateProjectedKey} 操作。
     */
    private static void validateProjectedKey(String column, int maxKeys, int timeoutSeconds) {
        if (maxKeys <= 0 || maxKeys > MAX_KEYS || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Invalid key scan bounds");
        }
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new UnsupportedOperationException("Unsupported key scope");
        }
    }

    /**
     * 读取投影文本。
     */
    private static AuditKeySnapshot readProjectedText(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, int maxKeys, int timeoutSeconds) throws SQLException {
        var database = context.database();
        var source = database.textKeyProjection(null, column, MAX_KEY_BYTES);
        // 精确字节身份在聚合之前加入分组，原列仅保留原生类型；不能先按 collation 或 LOB 前缀合并。
        String grouped = generator.buildCompositeKeyCountSql(context, List.of(
                quote(context, column)), source.groupSql())
                + " ORDER BY 1 ASC LIMIT " + (maxKeys + 1);
        var key = database.textKeyProjection("k", "key0", MAX_KEY_BYTES);
        String sql = "SELECT " + key.valueSql() + " AS " + quote(context, "keyValue")
                + ", k." + quote(context, "totalCount") + ", k." + quote(context, "errorCount") + ", "
                + key.typeSql() + " AS " + quote(context, "keyType") + " FROM (" + grouped
                + ") k ORDER BY k." + quote(context, "scopeKey") + " ASC"
                + (key.requiresExplicitOuterLimit() ? " LIMIT " + (maxKeys + 1) : "");
        return scan(connection, sql, maxKeys, timeoutSeconds, projectedTextAlgorithm(context), null, key);
    }

    /**
     * 扫描当前查询结果并收集受限的质量键摘要。
     */
    private static AuditKeySnapshot scan(Connection connection, String sql, int maxKeys,
            int timeoutSeconds, String algorithm, BoundedIntegerKeyProjection projection) throws SQLException {
        return scan(connection, sql, maxKeys, timeoutSeconds, algorithm, projection, null);
    }

    /**
     * 扫描当前查询结果并收集受限的质量键摘要。
     */
    private static AuditKeySnapshot scan(Connection connection, String sql, int maxKeys,
            int timeoutSeconds, String algorithm, BoundedIntegerKeyProjection projection,
            BoundedTextKeyProjection textKey) throws SQLException {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("A positive query timeout is required");
        }
        Accumulator counts = new Accumulator(algorithm);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxKeys + 1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                Integer metadataType = projection == null ? null : projection.jdbcType(rows.getMetaData(), 4);
                if (textKey != null) {
                    metadataType = textKey.jdbcType(rows.getMetaData(), 4);
                }
                while (rows.next()) {
                    if (counts.keys >= maxKeys) {
                        return new AuditKeySnapshot(sql, -1, -1, counts.keys + 1, counts.type,
                                null, false, "KEY_LIMIT_EXCEEDED", counts.explicitEncoding());
                    }
                    if (textKey != null && textKey.requiresRowTypeWitness()) {
                        metadataType = textKey.jdbcType(rows, 4);
                    }
                    if (projection != null && projection.requiresRowTypeWitness()) {
                        metadataType = projection.jdbcType(rows, 4);
                    }
                    counts.add(rows, metadataType);
                }
                return counts.snapshot(sql);
            }
        } catch (ArithmeticException ex) {
            throw new SQLException("Key group count overflow", "22003", ex);
        }
    }

    /** 所有分组共享 maxKeys 扫描预算，不是每个分组再扫描 maxKeys；超限不交付半份凭证。 */
    public static Grouped readGrouped(Connection connection, AuditSqlGenerator generator, AuditRuleContext context,
            String column, String groupColumn, int maxKeys, int maxGroups,
            int timeoutSeconds) throws SQLException {
        if (usesProjectedKeys(context)) {
            var result = readProjectedGroupedInteger(connection, generator, context, column, groupColumn,
                    maxKeys, maxGroups, timeoutSeconds);
            if (!usesProjectedText(context)) {
                return result;
            }
            // 丢弃不支持类型的整份结果；文本身份、计数和类型证明均取自第二次的同一查询。
            boolean textGroup = "GROUP_KEY_UNSUPPORTED".equals(result.reasonCode());
            if (textGroup) {
                result = readProjectedGroupedText(connection, generator, context, column, groupColumn,
                        maxKeys, maxGroups, timeoutSeconds);
            }
            // 每次回退都丢弃上一遍所有统计；机构和记录键的类型、身份、计数来自最终的同一结果。
            return result.complete() && result.groups().stream()
                    .anyMatch(group -> "KEY_TYPE_UNSUPPORTED".equals(group.snapshot().reasonCode()))
                    ? readProjectedGroupedTextKeys(connection, generator, context, column, groupColumn,
                    maxKeys, maxGroups, timeoutSeconds, textGroup) : result;
        }
        var result = readGrouped(connection, generator, context, column, groupColumn,
                maxKeys, maxGroups, timeoutSeconds, false);
        return result.complete() && result.groups().stream()
                .anyMatch(group -> "KEY_TYPE_UNSUPPORTED".equals(group.snapshot().reasonCode()))
                ? readGrouped(connection, generator, context, column, groupColumn,
                maxKeys, maxGroups, timeoutSeconds, true) : result;
    }

    /**
     * 读取投影分组整数。
     */
    private static Grouped readProjectedGroupedInteger(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, String groupColumn, int maxKeys, int maxGroups,
            int timeoutSeconds) throws SQLException {
        validateProjectedKey(column, maxKeys, timeoutSeconds);
        validateProjectedKey(groupColumn, maxKeys, timeoutSeconds);
        validateGroup(groupColumn, maxGroups, timeoutSeconds);
        var database = context.database();
        String grouped = database.limitGeneratedSelect(generator.buildGroupedKeyCountSql(context,
                RestrictedSelectGuard.quotePhysicalIdentifier(context.getProductType(), groupColumn),
                RestrictedSelectGuard.quotePhysicalIdentifier(context.getProductType(), column))
                + " ORDER BY 1 ASC, 2 ASC", maxKeys + 1);
        var key = database.integerKeyProjection(connection, "k", "groupKey");
        var group = database.integerKeyProjection(connection, "k", "scopeKey");
        // 两个身份均使用同一结果的原生类型见证；外层按原数值排序，不能按截取的字符串排序。
        String sql = "SELECT " + key.valueSql() + " AS " + quote(context, "keyValue") + ", k."
                + quote(context, "totalCount") + ", k." + quote(context, "errorCount") + ", "
                + key.typeSql() + " AS " + quote(context, "keyType") + ", " + group.valueSql()
                + " AS " + quote(context, "scopeValue") + ", " + group.typeSql() + " AS " + quote(context, "scopeType")
                + " FROM (" + grouped + ") k ORDER BY k." + quote(context, "scopeKey")
                + " ASC, k." + quote(context, "groupKey") + " ASC";
        if (key.requiresExplicitOuterLimit() || group.requiresExplicitOuterLimit()) {
            sql = database.limitGeneratedSelect(sql, maxKeys + 1);
        }
        var result = scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds, ALGORITHM, key, group);
        return result.complete()
                ? new Grouped(result.sql(), true, null, result.groups(), projectedIntegerGroupEncoding(context),
                result.groupJdbcType())
                : result;
    }

    /**
     * 读取投影分组文本。
     */
    private static Grouped readProjectedGroupedText(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, String groupColumn, int maxKeys, int maxGroups,
            int timeoutSeconds) throws SQLException {
        var database = context.database();
        var source = database.textKeyProjection(null, groupColumn, MAX_KEY_BYTES);
        // 同时按精确身份和源列分组，源列保留原生类型；不能先按 collation 合并再做精确编码。
        // 复用既有复合分组生成器，方言和原生类型证明由实际产品提供。
        String grouped = generator.buildCompositeKeyCountSql(context, List.of(
                quote(context, groupColumn), quote(context, column)), source.groupSql())
                + " ORDER BY 1 ASC, 3 ASC LIMIT " + (maxKeys + 1);
        var key = database.integerKeyProjection(connection, "k", "key1");
        var group = database.textKeyProjection("k", "key0", MAX_KEY_BYTES);
        String sql = "SELECT " + key.valueSql() + " AS " + quote(context, "keyValue")
                + ", k." + quote(context, "totalCount") + ", k." + quote(context, "errorCount") + ", "
                + key.typeSql() + " AS " + quote(context, "keyType") + ", " + group.valueSql()
                + " AS " + quote(context, "scopeValue") + ", " + group.typeSql() + " AS " + quote(context, "scopeType")
                + " FROM (" + grouped + ") k ORDER BY k." + quote(context, "scopeKey") + " ASC, k." + quote(context,
                "key1") + " ASC"
                + (group.requiresExplicitOuterLimit() ? " LIMIT " + (maxKeys + 1) : "");
        return withProjectedGroupEncoding(scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds,
                ALGORITHM, key, null, group), context, true);
    }

    /**
     * 读取分组。
     */
    private static Grouped readGrouped(Connection connection, AuditSqlGenerator generator, AuditRuleContext context,
            String column, String groupColumn, int maxKeys, int maxGroups,
            int timeoutSeconds, boolean typed) throws SQLException {
        validate(context, column, maxKeys);
        validateGroup(groupColumn, maxGroups, timeoutSeconds);
        String grouped = generator.buildGroupedKeyCountSql(context, groupColumn, column)
                + " ORDER BY 1 ASC, 2 ASC LIMIT " + (maxKeys + 1);
        String scopeType = "pg_catalog.pg_typeof(k.\"scopeKey\")::text";
        String sql = (typed ? typedKeyProjection() : keyProjection()) + ", CASE WHEN " + scopeType
                + " IN ('text','character varying','smallint','integer','bigint') "
                + "AND char_length(k.\"scopeKey\"::text) <= " + MAX_GROUP_KEY_LENGTH
                + " THEN k.\"scopeKey\"::text ELSE NULL END AS \"scopeValue\" FROM ("
                + grouped + ") k ORDER BY k.\"scopeKey\" ASC, 1 ASC";
        return scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds, typed ? TYPED_ALGORITHM : ALGORITHM);
    }

    /**
     * 读取投影分组文本键集合。
     */
    private static Grouped readProjectedGroupedTextKeys(Connection connection, AuditSqlGenerator generator,
            AuditRuleContext context, String column, String groupColumn, int maxKeys, int maxGroups,
            int timeoutSeconds, boolean textGroups) throws SQLException {
        var database = context.database();
        String scope = textGroups ? database.textKeyProjection(null, groupColumn, MAX_KEY_BYTES).groupSql()
                : quote(context, groupColumn);
        String grouped = generator.buildCompositeKeyCountSql(context, List.of(
                quote(context, groupColumn), quote(context, column),
                database.textKeyProjection(null, column, MAX_KEY_BYTES).groupSql()), scope)
                + " ORDER BY 1 ASC, 4 ASC LIMIT " + (maxKeys + 1);
        var key = database.textKeyProjection("k", "key1", MAX_KEY_BYTES);
        var textGroup = textGroups ? database.textKeyProjection("k", "key0", MAX_KEY_BYTES) : null;
        var integerGroup = textGroups ? null : database.integerKeyProjection(connection, "k", "key0");
        String sql = "SELECT " + key.valueSql() + " AS " + quote(context, "keyValue")
                + ", k." + quote(context, "totalCount") + ", k." + quote(context, "errorCount") + ", "
                + key.typeSql() + " AS " + quote(context, "keyType") + ", "
                + (textGroups ? textGroup.valueSql() : integerGroup.valueSql()) + " AS " + quote(context,
                "scopeValue") + ", "
                + (textGroups ? textGroup.typeSql() : integerGroup.typeSql()) + " AS " + quote(context, "scopeType")
                + " FROM (" + grouped + ") k ORDER BY k." + quote(context, "scopeKey") + " ASC, k." + quote(context,
                "key2") + " ASC"
                + (key.requiresExplicitOuterLimit() ? " LIMIT " + (maxKeys + 1) : "");
        return withProjectedGroupEncoding(scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds,
                projectedTextAlgorithm(context), null, integerGroup, textGroup, key), context, textGroups);
    }

    /**
     * 按分组键扫描数据并计算质量规则结果。
     */
    private static Grouped scanGrouped(Connection connection, String sql, int maxKeys, int maxGroups,
            int timeoutSeconds, String algorithm) throws SQLException {
        return scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds, algorithm, null, null);
    }

    /**
     * 按分组键扫描数据并计算质量规则结果。
     */
    private static Grouped scanGrouped(Connection connection, String sql, int maxKeys, int maxGroups,
            int timeoutSeconds, String algorithm, BoundedIntegerKeyProjection keyProjection,
            BoundedIntegerKeyProjection groupProjection) throws SQLException {
        return scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds, algorithm,
                keyProjection, groupProjection, null);
    }

    /**
     * 按分组键扫描数据并计算质量规则结果。
     */
    private static Grouped scanGrouped(Connection connection, String sql, int maxKeys, int maxGroups,
            int timeoutSeconds, String algorithm, BoundedIntegerKeyProjection keyProjection,
            BoundedIntegerKeyProjection groupProjection, BoundedTextKeyProjection textGroup) throws SQLException {
        return scanGrouped(connection, sql, maxKeys, maxGroups, timeoutSeconds, algorithm,
                keyProjection, groupProjection, textGroup, null);
    }

    /**
     * 按分组键扫描数据并计算质量规则结果。
     */
    private static Grouped scanGrouped(Connection connection, String sql, int maxKeys, int maxGroups,
            int timeoutSeconds, String algorithm, BoundedIntegerKeyProjection keyProjection,
            BoundedIntegerKeyProjection groupProjection, BoundedTextKeyProjection textGroup,
            BoundedTextKeyProjection textKey) throws SQLException {
        LinkedHashMap<String, Accumulator> groups = new LinkedHashMap<>();
        int scanned = 0;
        Integer groupType = null;
        Integer witnessedKeyType = null;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxKeys + 1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                Integer keyType = keyProjection == null ? null : keyProjection.jdbcType(rows.getMetaData(), 4);
                if (textKey != null) {
                    keyType = textKey.jdbcType(rows.getMetaData(), 4);
                }
                if (groupProjection != null && !groupProjection.requiresRowTypeWitness()) {
                    groupType = groupProjection.jdbcType(rows.getMetaData(), 6);
                    if (!isInteger(groupType)) {
                        return new Grouped(sql, false, "GROUP_KEY_UNSUPPORTED", List.of());
                    }
                }
                if (textGroup != null && !textGroup.requiresRowTypeWitness()) {
                    groupType = textGroup.jdbcType(rows.getMetaData(), 6);
                    if (groupType == 0) {
                        return new Grouped(sql, false, "GROUP_KEY_UNSUPPORTED", List.of());
                    }
                }
                while (rows.next()) {
                    if (++scanned > maxKeys) {
                        return new Grouped(sql, false, "KEY_LIMIT_EXCEEDED", List.of());
                    }
                    if (keyProjection != null && keyProjection.requiresRowTypeWitness()) {
                        keyType = keyProjection.jdbcType(rows, 4);
                        if (witnessedKeyType != null && !witnessedKeyType.equals(keyType)) {
                            throw new SQLException("Inconsistent native integer key types", "22000");
                        }
                        witnessedKeyType = keyType;
                    }
                    if (groupProjection != null && groupProjection.requiresRowTypeWitness()) {
                        int witnessed = groupProjection.jdbcType(rows, 6);
                        if (!isInteger(witnessed) || groupType != null && groupType != witnessed) {
                            return new Grouped(sql, false, "GROUP_KEY_UNSUPPORTED", List.of());
                        }
                        groupType = witnessed;
                    }
                    if (textKey != null && textKey.requiresRowTypeWitness()) {
                        keyType = textKey.jdbcType(rows, 4);
                        if (witnessedKeyType != null && !witnessedKeyType.equals(keyType)) {
                            throw new SQLException("Inconsistent native key types", "22000");
                        }
                        witnessedKeyType = keyType;
                    }
                    if (textGroup != null && textGroup.requiresRowTypeWitness()) {
                        int witnessed = textGroup.jdbcType(rows, 6);
                        if (witnessed == 0 || groupType != null && groupType != witnessed) {
                            return new Grouped(sql, false, "GROUP_KEY_UNSUPPORTED", List.of());
                        }
                        groupType = witnessed;
                    }
                    String group;
                    if (textGroup != null) {
                        byte[] bytes = rows.getBytes(5);
                        group = bytes == null || bytes.length > MAX_KEY_BYTES ? null : decodeUtf8(bytes);
                    } else if (groupProjection == null) {
                        group = rows.getString(5);
                    } else {
                        long value = rows.getLong(5);
                        group = rows.wasNull() ? null : Long.toString(value);
                    }
                    if (group == null || group.isBlank() || group.length() > MAX_GROUP_KEY_LENGTH
                            || textGroup != null && group.indexOf('\0') >= 0) {
                        return new Grouped(sql, false, "GROUP_KEY_UNSUPPORTED", List.of());
                    }
                    if (!groups.containsKey(group) && groups.size() >= maxGroups) {
                        return new Grouped(sql, false, "GROUP_LIMIT_EXCEEDED", List.of());
                    }
                    groups.computeIfAbsent(group, ignored -> new Accumulator(algorithm)).add(rows, keyType);
                }
            }
            return new Grouped(sql, true, null, groups.entrySet().stream()
                    .map(entry -> new Group(entry.getKey(), entry.getValue().snapshot(sql))).toList(),
                    textGroup != null ? MYSQL_TEXT_GROUP_ENCODING
                            : groupProjection == null ? null : MYSQL_INTEGER_GROUP_ENCODING, groupType);
        } catch (ArithmeticException ex) {
            throw new SQLException("Key group count overflow", "22003", ex);
        }
    }

    /**
     * 解码UTF-8。
     */
    private static String decodeUtf8(byte[] bytes) throws SQLException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new SQLException("Invalid UTF-8 group identity", "22021", ex);
        }
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String quote(AuditRuleContext context, String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(context.getProductType(), name);
    }

    /**
     * 拒绝当前实现未支持的 {@code validateGroup} 操作。
     */
    private static void validateGroup(String groupColumn, int maxGroups, int timeoutSeconds) {
        if (groupColumn == null || !groupColumn.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new UnsupportedOperationException("Unsupported group scope");
        }
        if (maxGroups <= 0 || maxGroups > MAX_KEYS || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Invalid grouped scan bounds");
        }
    }

    /**
     * 构建SQL 文本。
     */
    static String buildSql(AuditSqlGenerator generator, AuditRuleContext context, String column, int maxKeys) {
        return buildSql(generator, context, column, maxKeys, false);
    }

    /**
     * 构建SQL 文本。
     */
    private static String buildSql(AuditSqlGenerator generator, AuditRuleContext context, String column,
            int maxKeys, boolean typed) {
        validate(context, column, maxKeys);
        // 非行级策略（如唯一性）由原生成器明确拒绝；不改写其统计语义。
        String grouped = generator.buildGroupCountSql(context, column)
                + " ORDER BY 1 ASC LIMIT " + (maxKeys + 1);
        return (typed ? typedKeyProjection() : keyProjection()) + " FROM (" + grouped + ") k ORDER BY 1 ASC";
    }

    /**
     * 拒绝当前实现未支持的 {@code validate} 操作。
     */
    private static void validate(AuditRuleContext context, String column, int maxKeys) {
        if (maxKeys <= 0 || maxKeys > MAX_KEYS) {
            throw new IllegalArgumentException("Invalid key scan limit");
        }
        if (context.getProductType() != ProductTypeEnum.POSTGRESQL
                || column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new UnsupportedOperationException("Unsupported key scope");
        }
    }

    /**
     * 不向驱动传输不支持的任意长度文本/二进制键，防止行数有界但单行无限大。
     */
    private static String keyProjection() {
        // 不向驱动传输不支持的任意长度文本/二进制键，防止行数有界但单行无限大。
        String type = "pg_catalog.pg_typeof(k.\"groupKey\")::text";
        return "SELECT CASE WHEN " + type + " IN ('smallint','integer','bigint') "
                + "THEN k.\"groupKey\"::text::bigint ELSE NULL END AS \"keyValue\", "
                + "k.\"totalCount\", k.\"errorCount\", CASE " + type
                + " WHEN 'smallint' THEN 5 WHEN 'integer' THEN 4 WHEN 'bigint' THEN -5 ELSE 0 END AS \"keyType\"";
    }

    /**
     * 生成包含类型编码及计数信息的分组键查询投影。
     */
    private static String typedKeyProjection() {
        return "SELECT " + encodedValue("k.\"groupKey\"", false)
                + " AS \"keyValue\", k.\"totalCount\", k.\"errorCount\", "
                + encodedType("k.\"groupKey\"", false) + " AS \"keyType\"";
    }

    /**
     * 输出 bytea 并由外层按其排序，不依赖文本 collation，不做 trim/大小写/Unicode 归一化。
     */
    private static String encodedValue(String key, boolean integers) {
        String type = "pg_catalog.pg_typeof(" + key + ")::text";
        String bytes = "pg_catalog.convert_to(" + key + "::text, 'UTF8')";
        // 输出 bytea 并由外层按其排序，不依赖文本 collation，不做 trim/大小写/Unicode 归一化。
        // CASE 仅对已知 UUID 分支执行转换；不支持的类型及超长值不传输到驱动。
        return "CASE WHEN " + type + " IN ('text','character varying') THEN CASE WHEN "
                + "pg_catalog.octet_length(" + bytes + ") <= " + MAX_KEY_BYTES + " THEN " + bytes + " ELSE NULL END "
                + "WHEN " + type + " = 'uuid' THEN pg_catalog.uuid_send(" + key + "::text::uuid) "
                + (integers ? "WHEN " + type + " IN ('smallint','integer','bigint') THEN pg_catalog.int8send("
                + key + "::text::bigint) " : "") + "ELSE NULL END";
    }

    /**
     * 生成将数据库原生键类型映射为稳定编码的 CASE 表达式。
     */
    private static String encodedType(String key, boolean integers) {
        return "CASE pg_catalog.pg_typeof(" + key + ")::text"
                + " WHEN 'text' THEN -1 WHEN 'character varying' THEN 12 WHEN 'uuid' THEN 1111 "
                + (integers ? "WHEN 'smallint' THEN 5 WHEN 'integer' THEN 4 WHEN 'bigint' THEN -5 " : "") + "ELSE 0 END";
    }

    /**
     * 拒绝当前实现未支持的 {@code buildCompositeSql} 操作。
     */
    static String buildCompositeSql(AuditSqlGenerator generator, AuditRuleContext context,
            List<String> columns, String groupColumn, int maxKeys) {
        if (!validCompositeColumns(columns)) {
            throw new UnsupportedOperationException("Unsupported composite key columns");
        }
        validate(context, columns.getFirst(), maxKeys);
        String grouped = generator.buildCompositeKeyCountSql(context, columns, groupColumn) + " LIMIT " + (maxKeys + 1);
        List<String> selected = new ArrayList<>(), frames = new ArrayList<>(), supported = new ArrayList<>();
        frames.add("pg_catalog.int4send(" + columns.size() + ")");
        for (int i = 0; i < columns.size(); i++) {
            String key = "k.\"key" + i + "\"", part = "p.part" + i, type = "p.type" + i;
            selected.add(encodedValue(key, true) + " AS part" + i);
            selected.add(encodedType(key, true) + " AS type" + i);
            supported.add(type + " <> 0");
            frames.add("pg_catalog.int4send(" + type + ") || pg_catalog.int4send(pg_catalog.octet_length("
                    + part + ")) || " + part);
        }
        selected.add("k.\"totalCount\"");
        selected.add("k.\"errorCount\"");
        if (groupColumn != null) {
            selected.add("CASE WHEN pg_catalog.pg_typeof(k.\"scopeKey\")::text "
                    + "IN ('text','character varying','smallint','integer','bigint') "
                    + "AND char_length(k.\"scopeKey\"::text) <= " + MAX_GROUP_KEY_LENGTH
                    + " THEN k.\"scopeKey\"::text ELSE NULL END AS \"scopeValue\"");
        }
        String tuple = String.join(" || ", frames);
        // 整个元组（含分量数、类型、长度）仍受 256 字节预算约束，不能将多列上限相乘。
        return "SELECT CASE WHEN pg_catalog.octet_length(" + tuple + ") <= " + MAX_KEY_BYTES + " THEN " + tuple
                + " ELSE NULL END AS \"keyValue\", p.\"totalCount\", p.\"errorCount\", CASE WHEN "
                + String.join(" AND ", supported) + " THEN " + Types.STRUCT + " ELSE 0 END AS \"keyType\""
                + (groupColumn == null ? "" : ", p.\"scopeValue\"") + " FROM (SELECT "
                + String.join(", ", selected) + " FROM (" + grouped + ") k) p ORDER BY "
                + (groupColumn == null ? "" : "5 ASC, ") + "1 ASC";
    }

    record EncodedKey(byte[] bytes, long total, long fail, int type) {

    }

    /** 只用于有界、完整扫描的编码元组；复用原计数/类型/唯一性与摘要累加器。 */
    static AuditKeySnapshot sortedEncodedSnapshot(String sql, List<EncodedKey> rows,
            String algorithm) throws SQLException {
        rows.sort(java.util.Comparator.comparing(EncodedKey::bytes,
                java.util.Comparator.nullsFirst(Arrays::compareUnsigned)));
        Accumulator accumulator = new Accumulator(algorithm);
        for (EncodedKey row : rows) {
            accumulator.addEncoded(row);
        }
        return accumulator.snapshot(sql);
    }

    private static final class Accumulator {

        private final MessageDigest digest = digest();

        private final ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES);

        private final boolean typed;

        private final String algorithm;

        private long total, fail, keys;

        private Long previous;

        private byte[] previousBytes;

        private Integer type;

        private String reason;

        /**
         * 初始化 {@code Accumulator}。
         */
        private Accumulator(String algorithm) {
            this.algorithm = algorithm;
            this.typed = !ALGORITHM.equals(algorithm);
            digest.update(algorithm.getBytes(StandardCharsets.US_ASCII));
        }

        /**
         * 将当前值加入集合或累计统计。
         */
        private void add(ResultSet rows) throws SQLException {
            add(rows, null);
        }

        /**
         * 将当前值加入集合或累计统计。
         */
        private void add(ResultSet rows, Integer metadataType) throws SQLException {
            long rowTotal = rows.getLong(2);
            boolean invalid = rows.wasNull() || rowTotal <= 0;
            long rowFail = rows.getLong(3);
            invalid |= rows.wasNull() || rowFail < 0 || rowFail > rowTotal;
            addCounts(rowTotal, rowFail, invalid);
            int rowType = metadataType == null ? rows.getInt(4) : metadataType;
            boolean supported = acceptType(rowType);
            if (supported && typed) {
                addBytes(rowTotal, rows.getBytes(1));
            } else if (supported) {
                long key = rows.getLong(1);
                if (rows.wasNull() || rowTotal != 1 || (previous != null && key <= previous)) {
                    reason = "KEY_NOT_UNIQUE_OR_NULL";
                }
                previous = key;
                bytes.clear();
                bytes.putLong(key);
                digest.update(bytes.array()); // 固定宽度编码；不将失败标记混入键身份。
            }
        }

        /**
         * 校验并累计当前已编码分组键的统计和字节占用。
         */
        private void addEncoded(EncodedKey row) throws SQLException {
            addCounts(row.total(), row.fail(), false);
            if (acceptType(row.type())) {
                addBytes(row.total(), row.bytes());
            }
        }

        /**
         * 校验分组行数与失败数，并累计质量统计。
         */
        private void addCounts(long rowTotal, long rowFail, boolean invalid) throws SQLException {
            if (invalid || rowTotal <= 0 || rowFail < 0 || rowFail > rowTotal) {
                throw new SQLException("Invalid key group counts", "22000");
            }
            total = Math.addExact(total, rowTotal);
            fail = Math.addExact(fail, rowFail);
            keys++;
        }

        /**
         * 检查当前原生类型是否属于允许的值域。
         */
        private boolean acceptType(int rowType) throws SQLException {
            if (type != null && type != rowType) {
                throw new SQLException("Inconsistent key type", "22000");
            }
            if (typed && type == null) { // 类型也属于摘要域，text 与 varchar/uuid 不能相互借用。
                bytes.clear();
                bytes.putInt(rowType);
                digest.update(bytes.array(), 0, Integer.BYTES);
            }
            type = rowType;
            boolean supported = supportsEncoding(algorithm, type);
            if (!supported) {
                reason = "KEY_TYPE_UNSUPPORTED";
            }
            return supported;
        }

        /**
         * 添加字节数组。
         */
        private void addBytes(long rowTotal, byte[] key) throws SQLException {
            if (key == null || key.length > MAX_KEY_BYTES || type == Types.OTHER && key.length != 16) {
                reason = "KEY_NULL_OR_TOO_LONG";
            } else {
                if (isText(type)) {
                    decodeUtf8(key); // 绝不能将驱动替换字符的哈希作为原生身份。
                }
                if (rowTotal != 1 || previousBytes != null && Arrays.compareUnsigned(previousBytes, key) >= 0) {
                    reason = "KEY_NOT_UNIQUE_OR_NULL";
                }
                previousBytes = key;
                bytes.clear();
                bytes.putInt(key.length);
                digest.update(bytes.array(), 0, Integer.BYTES);
                digest.update(key);
            }
        }

        /**
         * 返回快照。
         */
        private AuditKeySnapshot snapshot(String sql) {
            return new AuditKeySnapshot(sql, total, fail, keys, type,
                    reason == null ? HexFormat.of().formatHex(digest.digest()) : null, reason == null, reason,
                    explicitEncoding());
        }

        /**
         * 判断当前分组键算法是否要求显式编码。
         */
        private String explicitEncoding() {
            return CLICKHOUSE_TEXT_ALGORITHM.equals(algorithm) || CLICKHOUSE_TYPED_COMPOSITE_ALGORITHM.equals(algorithm)
                    || OPENGAUSS_TEXT_ALGORITHM.equals(algorithm) || OPENGAUSS_TYPED_COMPOSITE_ALGORITHM.equals(
                    algorithm)
                    || STARROCKS_TEXT_ALGORITHM.equals(algorithm) || STARROCKS_TYPED_COMPOSITE_ALGORITHM.equals(
                    algorithm)
                    || GREENPLUM_TEXT_ALGORITHM.equals(algorithm) || GREENPLUM_TYPED_COMPOSITE_ALGORITHM.equals(
                    algorithm)
                    || TIDB_TEXT_ALGORITHM.equals(algorithm) || TIDB_TYPED_COMPOSITE_ALGORITHM.equals(algorithm)
                    || MYSQL_TEXT_ALGORITHM.equals(algorithm) || MYSQL_COMPOSITE_ALGORITHM.equals(algorithm)
                    || DORIS_COMPOSITE_ALGORITHM.equals(algorithm) || MARIADB_COMPOSITE_ALGORITHM.equals(algorithm)
                    || CLICKHOUSE_COMPOSITE_ALGORITHM.equals(algorithm) || OPENGAUSS_COMPOSITE_ALGORITHM.equals(
                    algorithm)
                    || TIDB_COMPOSITE_ALGORITHM.equals(algorithm) || STARROCKS_COMPOSITE_ALGORITHM.equals(algorithm)
                    || TDENGINE_COMPOSITE_ALGORITHM.equals(algorithm)
                    || INFLUXDB_COMPOSITE_ALGORITHM.equals(algorithm)
                    || HIVE_COMPOSITE_ALGORITHM.equals(algorithm)
                    || SQLSERVER_COMPOSITE_ALGORITHM.equals(algorithm)
                    || ORACLE_COMPOSITE_ALGORITHM.equals(algorithm)
                    || OCEANBASE_COMPOSITE_ALGORITHM.equals(algorithm)
                    || GREENPLUM_COMPOSITE_ALGORITHM.equals(algorithm)
                    || HIGHGO_COMPOSITE_ALGORITHM.equals(algorithm)
                    || DM_COMPOSITE_ALGORITHM.equals(algorithm)
                    || KINGBASE_COMPOSITE_ALGORITHM.equals(algorithm)
                    || OSCAR_COMPOSITE_ALGORITHM.equals(algorithm)
                    || UXDB_COMPOSITE_ALGORITHM.equals(algorithm) ? algorithm : null;
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
