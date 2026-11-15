package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DS_DATASOURCE_UNSUPPORTED;

/**
 * 开发任务的调度器数据源适配：平台数据库产品与连接地址 → DolphinScheduler 数据源创建参数。
 *
 * <p>只登记受管 SQL 校验已覆盖、且 DolphinScheduler 自带对应数据源插件的产品。两侧范围由守卫测试保持一致，
 * 新增产品必须同时接入受管校验与本适配。连接参数从平台实际使用的 JDBC 地址按产品严格解析；多主机、TNS 描述串等
 * 不能一一对应到调度器单主机参数的地址直接拒绝，不截断、不猜测，保证调度器连接的是平台校验过的同一目标。</p>
 */
public final class DevDsDatasourceAdapter {

    /** Oracle 以 SID 连接 */
    static final String ORACLE_SID = "ORACLE_SID";
    /** Oracle 以服务名连接 */
    static final String ORACLE_SERVICE_NAME = "ORACLE_SERVICE_NAME";

    /**
     * 调度器数据源连接目标，不含名称、备注与凭据。
     *
     * @param type        调度器数据源类型
     * @param host        主机
     * @param port        端口
     * @param database    数据库名；Oracle 为 SID 或服务名
     * @param connectType Oracle 连接方式，其他产品为空
     * @param other       连接地址中的附加参数
     */
    record Target(String type, String host, int port, String database, String connectType,
                  Map<String, String> other) {
    }

    private enum Protocol { MYSQL, POSTGRESQL, ORACLE, DAMENG }

    private record Spec(String dsType, Protocol protocol, int defaultPort, String label) {
    }

    private static final Map<ProductTypeEnum, Spec> SPECS;

    static {
        Map<ProductTypeEnum, Spec> specs = new EnumMap<>(ProductTypeEnum.class);
        specs.put(ProductTypeEnum.MYSQL, new Spec("MYSQL", Protocol.MYSQL, 3306, "MySQL"));
        specs.put(ProductTypeEnum.MARIADB, new Spec("MYSQL", Protocol.MYSQL, 3306, "MariaDB"));
        specs.put(ProductTypeEnum.TIDB, new Spec("MYSQL", Protocol.MYSQL, 4000, "TiDB"));
        specs.put(ProductTypeEnum.POSTGRESQL, new Spec("POSTGRESQL", Protocol.POSTGRESQL, 5432, "PostgreSQL"));
        // 调度器用自带的 PostgreSQL 驱动连接 openGauss，服务端须允许该驱动支持的认证方式（例如 md5）。
        specs.put(ProductTypeEnum.OPENGAUSS, new Spec("POSTGRESQL", Protocol.POSTGRESQL, 5432, "openGauss"));
        specs.put(ProductTypeEnum.ORACLE, new Spec("ORACLE", Protocol.ORACLE, 1521, "Oracle"));
        specs.put(ProductTypeEnum.DM, new Spec("DAMENG", Protocol.DAMENG, 5236, "达梦"));
        SPECS = Collections.unmodifiableMap(specs);
    }

    /** 单个主机名或 IPv4 地址；逗号分隔的多主机、方括号 IPv6 与 TNS 描述串都不匹配。 */
    private static final String HOST = "([A-Za-z0-9._-]+)";
    private static final String OPTIONAL_PORT = "(?::(\\d{1,5}))?";
    private static final String OPTIONAL_PATH = "(?:/([^/?#]*))?";
    private static final String OPTIONAL_QUERY = "(?:\\?(.*))?";
    private static final String ORACLE_NAME = "([A-Za-z0-9_$#.-]+)";

    private static final Pattern MYSQL_URL = Pattern.compile(
            "^jdbc:(?:mysql|mariadb)://" + HOST + OPTIONAL_PORT + OPTIONAL_PATH + OPTIONAL_QUERY + "$");
    private static final Pattern POSTGRESQL_URL = Pattern.compile(
            "^jdbc:(?:postgresql|opengauss)://" + HOST + OPTIONAL_PORT + OPTIONAL_PATH + OPTIONAL_QUERY + "$");
    private static final Pattern DAMENG_URL = Pattern.compile(
            "^jdbc:dm://" + HOST + OPTIONAL_PORT + OPTIONAL_PATH + OPTIONAL_QUERY + "$");
    /** jdbc:oracle:thin:@host:port:SID */
    private static final Pattern ORACLE_SID_URL = Pattern.compile(
            "^jdbc:oracle:thin:@" + HOST + ":(\\d{1,5}):" + ORACLE_NAME + OPTIONAL_QUERY + "$");
    /** jdbc:oracle:thin:@//host:port/service，以及省略 // 的 EZConnect 写法 */
    private static final Pattern ORACLE_SERVICE_URL = Pattern.compile(
            "^jdbc:oracle:thin:@(?://)?" + HOST + OPTIONAL_PORT + "/" + ORACLE_NAME + OPTIONAL_QUERY + "$");

    private DevDsDatasourceAdapter() {
    }

    /**
     * 判断数据库产品能否以 SQL 任务提交到调度器执行。
     *
     * @param product 数据库产品
     * @return 是否可调度
     */
    public static boolean supports(ProductTypeEnum product) {
        return product != null && SPECS.containsKey(product);
    }

    /**
     * 获得可调度执行 SQL 任务的数据库产品标识，与数据源的 productId 取值一致。
     *
     * @return 按名称排序的产品标识
     */
    public static List<String> schedulableProducts() {
        return SPECS.keySet().stream().map(Enum::name).sorted().toList();
    }

    /**
     * 要求数据库产品可调度，否则给出明确原因。
     *
     * @param product 数据库产品
     */
    static void requireSchedulable(ProductTypeEnum product) {
        spec(product);
    }

    /**
     * 获得调度器数据源类型。
     *
     * @param product 数据库产品
     * @return 调度器数据源类型
     */
    static String dsType(ProductTypeEnum product) {
        return spec(product).dsType();
    }

    /**
     * 按平台实际使用的连接信息构造调度器连接目标。
     *
     * @param conn 已解析的连接信息
     * @return 调度器连接目标
     */
    static Target target(AccessDatabaseResolver.ConnectionInfo conn) {
        return target(conn.getProductType(), conn.getJdbcUrl(), conn.getDatabaseName(), conn.getSchema());
    }

    /**
     * 按产品严格解析 JDBC 地址，构造调度器连接目标。
     *
     * @param product      数据库产品
     * @param jdbcUrl      平台实际使用的 JDBC 地址
     * @param databaseName 数据源配置的数据库名
     * @param schema       数据源解析出的表命名空间
     * @return 调度器连接目标
     */
    static Target target(ProductTypeEnum product, String jdbcUrl, String databaseName, String schema) {
        Spec spec = spec(product);
        String url = StrUtil.trimToEmpty(jdbcUrl);
        return switch (spec.protocol()) {
            // MySQL 系的表命名空间就是数据库
            case MYSQL -> standard(spec, MYSQL_URL.matcher(url), schema);
            case POSTGRESQL -> postgresql(spec, url, databaseName, schema);
            // 达梦地址模板不带库名，沿用表命名空间
            case DAMENG -> standard(spec, DAMENG_URL.matcher(url), schema);
            case ORACLE -> oracle(spec, url);
        };
    }

    private static Spec spec(ProductTypeEnum product) {
        if (product == null) {
            throw exception(DEV_DS_DATASOURCE_UNSUPPORTED, "数据源产品未识别");
        }
        Spec spec = SPECS.get(product);
        if (spec == null) {
            throw exception(DEV_DS_DATASOURCE_UNSUPPORTED,
                    "数据库产品 " + product.name() + " 尚未接入受管 SQL 校验与调度器数据源适配");
        }
        return spec;
    }

    private static Target standard(Spec spec, Matcher matcher, String fallbackDatabase) {
        if (!matcher.matches()) {
            throw unparseable(spec);
        }
        return new Target(spec.dsType(), matcher.group(1), port(spec, matcher.group(2)),
                requireDatabase(spec, StrUtil.blankToDefault(matcher.group(3), fallbackDatabase)), null,
                query(spec, matcher.group(4)));
    }

    private static Target postgresql(Spec spec, String url, String databaseName, String schema) {
        Matcher matcher = POSTGRESQL_URL.matcher(url);
        if (!matcher.matches()) {
            throw unparseable(spec);
        }
        // PostgreSQL 系的表命名空间不是数据库，地址未带库名时只能取数据源配置的数据库名
        String database = requireDatabase(spec, StrUtil.blankToDefault(matcher.group(3), databaseName));
        Map<String, String> other = query(spec, matcher.group(4));
        if (StrUtil.isNotBlank(schema)) {
            other.putIfAbsent("currentSchema", schema);
        }
        return new Target(spec.dsType(), matcher.group(1), port(spec, matcher.group(2)), database, null, other);
    }

    private static Target oracle(Spec spec, String url) {
        Matcher sid = ORACLE_SID_URL.matcher(url);
        if (sid.matches()) {
            return new Target(spec.dsType(), sid.group(1), port(spec, sid.group(2)), sid.group(3), ORACLE_SID,
                    query(spec, sid.group(4)));
        }
        Matcher service = ORACLE_SERVICE_URL.matcher(url);
        if (service.matches()) {
            return new Target(spec.dsType(), service.group(1), port(spec, service.group(2)), service.group(3),
                    ORACLE_SERVICE_NAME, query(spec, service.group(4)));
        }
        throw unparseable(spec);
    }

    private static int port(Spec spec, String raw) {
        if (raw == null) {
            return spec.defaultPort();
        }
        int port = Integer.parseInt(raw);
        if (port < 1 || port > 65535) {
            throw unparseable(spec);
        }
        return port;
    }

    private static String requireDatabase(Spec spec, String database) {
        if (StrUtil.isBlank(database)) {
            throw exception(DEV_DS_DATASOURCE_UNSUPPORTED, "无法从 " + spec.label() + " 连接配置确定数据库名");
        }
        return database;
    }

    private static Map<String, String> query(Spec spec, String query) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (StrUtil.isBlank(query)) {
            return properties;
        }
        try {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    properties.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
        } catch (IllegalArgumentException malformed) {
            throw unparseable(spec);
        }
        return properties;
    }

    /** 错误信息不回显连接地址，避免地址中的参数或凭据进入日志与界面。 */
    private static RuntimeException unparseable(Spec spec) {
        return exception(DEV_DS_DATASOURCE_UNSUPPORTED, "连接地址不是调度器可一一对应的单主机 " + spec.label()
                + " 地址，多主机、故障转移或 TNS 描述串等形式请改为单主机地址");
    }

}
