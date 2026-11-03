package cn.iocoder.yudao.framework.dbswitch.common.config;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 由部署配置持有的不可变源代际固定信息。绝不从请求或 RPC 属性填充。 */
public final class ConfiguredSourceGenerations {

    /** 组件持有的部署输入契约，不依赖 Spring 属性绑定。 */
    public interface Specification {

        /**
         * 返回数据库产品。
         */
        ProductTypeEnum getProduct();

        /**
         * 判断当前源端恢复过程是否需要轮换代次标记。
         */
        boolean isRestoreRotationRequired();

        /**
         * 返回目标端指纹。
         */
        String getTargetFingerprint();

        /**
         * 返回模式。
         */
        String getSchema();

        /**
         * 返回实例编号。
         */
        String getInstanceId();

        /**
         * 返回代次。
         */
        String getGeneration();
    }

    private final Map<String, SourceGenerationPin> pins;

    /**
     * 校验构造参数并初始化 {@code ConfiguredSourceGenerations}。
     */
    public ConfiguredSourceGenerations(Map<String, ? extends Specification> configuration) {
        Map<String, SourceGenerationPin> copy = new HashMap<>();
        if (configuration == null) {
            throw new IllegalArgumentException("Source generation configuration is required");
        }
        for (var entry : configuration.entrySet()) {
            var value = entry.getValue();
            if (value == null || value.getProduct() == null || !Set.of(ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB,
                            ProductTypeEnum.TIDB, ProductTypeEnum.POSTGRESQL, ProductTypeEnum.SQLITE3, ProductTypeEnum.DM,
                            ProductTypeEnum.SQLSERVER, ProductTypeEnum.SQLSERVER2000, ProductTypeEnum.GBASE8A,
                            ProductTypeEnum.OSCAR, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.OCEANBASE,
                            ProductTypeEnum.MONGODB, ProductTypeEnum.GREENPLUM, ProductTypeEnum.ORACLE,
                            ProductTypeEnum.ELASTICSEARCH, ProductTypeEnum.KINGBASE, ProductTypeEnum.HIGHGO,
                            ProductTypeEnum.SYBASE, ProductTypeEnum.DB2, ProductTypeEnum.HIVE, ProductTypeEnum.UXDB)
                    .contains(value.getProduct())
                    || !value.isRestoreRotationRequired() || value.getTargetFingerprint() == null
                    || !value.getTargetFingerprint().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid managed source generation configuration");
            }
            var pin = new SourceGenerationPin(value.getSchema(), value.getInstanceId(), value.getGeneration());
            if (copy.putIfAbsent(value.getTargetFingerprint(), pin) != null) {
                throw new IllegalArgumentException("Duplicate managed source generation target");
            }
        }
        pins = Map.copyOf(copy);
    }

    /**
     * 根据给定参数创建 {@code ConfiguredSourceGenerations} 空实例。
     */
    public static ConfiguredSourceGenerations empty() {
        return new ConfiguredSourceGenerations(Map.of());
    }

    /**
     * 返回当前查找。
     */
    public Optional<SourceGenerationPin> find(ProductTypeEnum product, String jdbcUrl, String username) {
        if (pins.isEmpty() || product == null || !Set.of(ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB,
                ProductTypeEnum.TIDB, ProductTypeEnum.POSTGRESQL, ProductTypeEnum.SQLITE3, ProductTypeEnum.DM,
                ProductTypeEnum.SQLSERVER, ProductTypeEnum.SQLSERVER2000, ProductTypeEnum.GBASE8A,
                ProductTypeEnum.OSCAR, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.OCEANBASE, ProductTypeEnum.MONGODB,
                ProductTypeEnum.GREENPLUM, ProductTypeEnum.ORACLE, ProductTypeEnum.ELASTICSEARCH,
                ProductTypeEnum.KINGBASE, ProductTypeEnum.HIGHGO, ProductTypeEnum.SYBASE, ProductTypeEnum.DB2,
                ProductTypeEnum.HIVE, ProductTypeEnum.UXDB).contains(product)) {
            return Optional.empty();
        }
        SourceGenerationPin pin = pins.get(targetFingerprint(product, jdbcUrl, username));
        if (pin != null) {
            validateManagedEndpoint(product, jdbcUrl);
        }
        return Optional.ofNullable(pin);
    }

    /** 部署固定信息和显式管理员工具共用的准入检查，绝不打开连接。 */
    public static void validateManagedEndpoint(ProductTypeEnum product, String jdbcUrl) {
        if (product == null) {
            throw new IllegalArgumentException("Managed source product is required");
        }
        switch (product) {
            case SQLITE3 -> validateSqliteEndpoint(jdbcUrl);
            case POSTGRESQL, GREENPLUM -> validatePostgresEndpoint(jdbcUrl);
            case TIDB -> validateMysqlEndpoint(jdbcUrl, true);
            case MYSQL -> validateMysqlEndpoint(jdbcUrl);
            case MARIADB -> validateManagedEndpoint(jdbcUrl);
            case DM -> validateDmEndpoint(jdbcUrl);
            case SQLSERVER, SQLSERVER2000 -> validateSqlServerEndpoint(jdbcUrl);
            case OSCAR -> validateOscarEndpoint(jdbcUrl);
            case GBASE8A -> validateGbase8aEndpoint(jdbcUrl);
            case OPENGAUSS -> validateOpengaussEndpoint(jdbcUrl);
            case KINGBASE -> validateKingbaseEndpoint(jdbcUrl);
            case HIGHGO -> validateHighgoEndpoint(jdbcUrl);
            case UXDB -> validateUxdbEndpoint(jdbcUrl);
            case SYBASE -> validateSybaseEndpoint(jdbcUrl);
            case HIVE -> validateHiveEndpoint(jdbcUrl);
            case DB2 -> validateDb2Endpoint(jdbcUrl);
            case OCEANBASE -> validateOceanbaseEndpoint(jdbcUrl);
            case MONGODB -> validateMongodbEndpoint(jdbcUrl);
            case ORACLE -> validateOracleEndpoint(jdbcUrl);
            case ELASTICSEARCH -> validateElasticsearchEndpoint(jdbcUrl);
            default -> throw new IllegalArgumentException("Managed source product is not implemented");
        }
    }

    /** GBase 8a managed markers require one explicit direct endpoint without driver overrides. */
    private static void validateGbase8aEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:gbase://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed GBase 8a generation requires one direct host, explicit port and database without overrides");
        }
    }

    /** Oscar 原厂直连 URL；禁止嵌入凭据、多节点和驱动/会话覆盖。 */
    private static void validateOscarEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:oscar://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed Oscar generation requires one direct host, explicit port and database without overrides");
        }
    }

    /** 单个直连 OceanBase 端点：不允许负载均衡、故障转移、多主机列表、内嵌凭据或驱动及会话选项。 */
    private static void validateOceanbaseEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:oceanbase://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("(/[A-Za-z0-9_]*)?")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed OceanBase generation requires one direct host, explicit port and optional database without driver overrides");
        }
    }

    /** 单个直连 MongoDB 端点：单主机、显式端口、admin authSource，不允许内嵌凭据或驱动选项。 */
    private static void validateMongodbEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mongodb://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || !"/admin".equals(uri.getRawPath()) || !"authSource=admin".equals(uri.getRawQuery())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed MongoDB generation requires one direct host, explicit port and admin authSource without credential or driver overrides");
        }
    }

    /** 单个直连 Oracle 端点：单主机、显式端口和服务名，不允许内嵌凭据或驱动及会话选项。 */
    private static void validateOracleEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:oracle:thin:@//")) {
                throw new IllegalArgumentException();
            }
            String rest = jdbcUrl.substring("jdbc:oracle:thin:@//".length());
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash != rest.lastIndexOf('/')) {
                throw new IllegalArgumentException();
            }
            String[] hostPort = rest.substring(0, slash).split(":", -1);
            if (hostPort.length != 2 || !hostPort[0].matches("[A-Za-z0-9._-]+|\\[[0-9a-fA-F:]+\\]")
                    || !hostPort[1].matches("[1-9][0-9]{0,4}")) {
                throw new IllegalArgumentException();
            }
            if (!rest.substring(slash + 1).matches("[A-Za-z0-9_.]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed Oracle generation requires one direct host, explicit port and service name without driver overrides");
        }
    }

    /** 通过已验证的 jest 桥接器连接的单个 Elasticsearch 直连端点：单主机、显式端口，不允许内嵌凭据或驱动选项。 */
    private static void validateElasticsearchEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:jest://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().isEmpty()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed Elasticsearch generation requires one direct host and explicit port without credential or driver overrides");
        }
    }

    /** 单个直连 KingbaseES 端点：单主机、显式端口和数据库，不允许内嵌凭据或驱动选项。 */
    private static void validateKingbaseEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:kingbase8://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed KingbaseES generation requires a direct host, explicit port and database without driver overrides");
        }
    }

    /** 单个直连 HighGo 端点：单主机、显式端口和数据库，不允许内嵌凭据或驱动选项。 */
    private static void validateHighgoEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:highgo://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed HighGo generation requires a direct host, explicit port and database without driver overrides");
        }
    }

    /** 单个直连 UXDB 端点：单主机、显式端口和数据库，不允许内嵌凭据或驱动选项。 */
    private static void validateUxdbEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:uxdb://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed UXDB generation requires a direct host, explicit port and database without driver overrides");
        }
    }

    /** 单个直连 Sybase ASE 端点：单主机、显式端口和数据库，不允许内嵌凭据或驱动选项。 */
    private static void validateSybaseEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sybase:Tds:")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create("//" + jdbcUrl.substring("jdbc:sybase:Tds:".length()));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed Sybase generation requires one direct host, explicit port and database without driver overrides");
        }
    }

    /** 单个直连 DB2 LUW 端点：单主机、显式端口和数据库名，不允许内嵌凭据或驱动属性。 */
    private static void validateDb2Endpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:db2://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring("jdbc:db2:".length()));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed DB2 generation requires one direct host, explicit port and database without driver overrides");
        }
    }

    /** 单个直连 HiveServer2 端点：单主机、显式端口和数据库，不允许内嵌凭据或会话属性。 */
    private static void validateHiveEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:hive2://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring("jdbc:hive2:".length()));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed Hive generation requires one direct HiveServer2 host, explicit port and database without driver overrides");
        }
    }

    /** 原生物理标识与外部恢复代际；不允许覆盖重连、路由或会话配置。 */
    private static void validateOpengaussEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:opengauss://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed openGauss generation requires a direct host, explicit port and database without driver overrides");
        }
    }

    /** 仅允许直连端点：不允许实例发现、故障转移、内嵌凭据或覆盖会话及路由配置。 */
    private static void validateSqlServerEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlserver://")) {
                throw new IllegalArgumentException();
            }
            String[] parts = jdbcUrl.substring(5).split(";", -1);
            var endpoint = java.net.URI.create(parts[0]);
            if (endpoint.getHost() == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getFragment() != null || !Set.of("", "/")
                    .contains(endpoint.getRawPath())) {
                throw new IllegalArgumentException();
            }
            Set<String> seen = new java.util.HashSet<>();
            boolean database = false;
            for (int i = 1; i < parts.length; i++) {
                String[] pair = parts[i].split("=", -1);
                if (pair.length != 2 || !seen.add(pair[0])) {
                    throw new IllegalArgumentException();
                }
                switch (pair[0]) {
                    case "databaseName" -> {
                        if (!pair[1].matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                            throw new IllegalArgumentException();
                        }
                        database = true;
                    }
                    case "encrypt" -> {
                        if (!Set.of("true", "false", "strict").contains(pair[1])) {
                            throw new IllegalArgumentException();
                        }
                    }
                    case "trustServerCertificate" -> {
                        if (!Set.of("true", "false").contains(pair[1])) {
                            throw new IllegalArgumentException();
                        }
                    }
                    case "loginTimeout", "socketTimeout" -> {
                        if (!pair[1].matches("[1-9][0-9]{0,5}")) {
                            throw new IllegalArgumentException();
                        }
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            if (!database) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Managed SQL Server generation requires one direct host, explicit port and database without routing or credential overrides");
        }
    }

    /** 单个直连 DM 主机，不允许集群别名、故障转移、会话 SQL 或覆盖驱动 URL。 */
    private static void validateDmEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:dm://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || !Set.of("", "/").contains(uri.getRawPath())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Managed DM generation requires an explicit direct host and port without routing overrides");
        }
    }

    /**
     * 检查 SQLite 数据库地址是否满足受支持的文件端点约定。
     */
    private static void validateSqliteEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:") || jdbcUrl.indexOf('?') >= 0 || jdbcUrl.indexOf(
                    '\0') >= 0) {
                throw new IllegalArgumentException();
            }
            String file = jdbcUrl.substring("jdbc:sqlite:".length());
            if (file.startsWith("file:") || !java.nio.file.Path.of(file).isAbsolute()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Managed SQLite generation requires an explicit absolute file endpoint");
        }
    }

    /** 部署管理的 PG 游标源：单个直连主机，不允许多主机、重放或会话 SQL 配置。 */
    private static void validatePostgresEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
            Set<String> allowed = Set.of("ssl", "sslmode", "connectTimeout", "socketTimeout", "tcpKeepAlive",
                    "ApplicationName");
            Set<String> seen = new java.util.HashSet<>();
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&", -1)) {
                    String[] pair = parameter.split("=", -1);
                    if (pair.length != 2 || !allowed.contains(pair[0]) || !seen.add(pair[0])
                            || !pair[1].matches("[A-Za-z0-9_.-]+")) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Managed cursor generation requires a direct PostgreSQL endpoint");
        }
    }

    /** 首个已验证配置：仅支持单主机 TCP 直连，不允许重放、路由或会话初始化选项。 */
    private static void validateMysqlEndpoint(String jdbcUrl) {
        validateMysqlEndpoint(jdbcUrl, false);
    }

    /**
     * 校验 MySQL JDBC 地址是否符合受支持端点约定。
     */
    private static void validateMysqlEndpoint(String jdbcUrl, boolean allowEmptyCatalog) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1
                    || uri.getRawPath() == null || !uri.getRawPath()
                    .matches(allowEmptyCatalog ? "/[A-Za-z0-9_]*" : "/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
            Set<String> allowed = Set.of("useSSL", "sslMode", "serverTimezone", "connectionTimeZone", "useUnicode",
                    "characterEncoding",
                    "nullCatalogMeansCurrent", "connectTimeout", "socketTimeout", "allowPublicKeyRetrieval",
                    "tinyInt1isBit");
            Set<String> seen = new java.util.HashSet<>();
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&", -1)) {
                    String[] pair = parameter.split("=", -1);
                    if (pair.length != 2 || !allowed.contains(pair[0]) || !seen.add(pair[0])
                            || !pair[1].matches("[A-Za-z0-9_./:+-]+")
                            || "tinyInt1isBit".equals(pair[0]) && !"false".equals(pair[1])) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Managed cursor generation requires a direct MySQL endpoint without replay or session overrides");
        }
    }

    /** 受管理的 MariaDB 关系标识配置仍遵循独立的驱动验证契约。 */
    public static void validateManagedEndpoint(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mariadb://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]*")) {
                throw new IllegalArgumentException();
            }
            Set<String> allowed = Set.of("useSSL", "sslMode", "serverTimezone", "useUnicode", "characterEncoding",
                    "nullCatalogMeansCurrent", "connectTimeout", "socketTimeout", "allowPublicKeyRetrieval",
                    "permitRedirect");
            Set<String> seen = new java.util.HashSet<>();
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&", -1)) {
                    String[] pair = parameter.split("=", -1);
                    if (pair.length != 2 || !allowed.contains(pair[0]) || !seen.add(pair[0])
                            || !pair[1].matches("[A-Za-z0-9_./:+-]+")
                            || "permitRedirect".equals(pair[0]) && !"false".equals(pair[1])) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Managed source identity requires a verified direct MariaDB connection profile");
        }
    }

    /** 连接池构造时显式固定驱动默认值；输入端点仍作为部署绑定键。 */
    public static String managedJdbcUrl(String jdbcUrl) {
        validateManagedEndpoint(jdbcUrl);
        return jdbcUrl.contains("permitRedirect=") ? jdbcUrl
                : jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "permitRedirect=false";
    }

    /**
     * 校验驱动实际生效的配置，包括 URL 之外的 Properties。JDBC getURL()
     * 可能包含密码，因此不解析或记录。仅使用公开且已验证的驱动 API；
     * 先解包连接池元数据，再解析其类加载器，以支持隔离的独立驱动。
     */
    public static void validateManagedConnection(java.sql.Connection connection) throws java.sql.SQLException {
        var metadata = connection.getMetaData();
        if (!"MariaDB Connector/J".equals(metadata.getDriverName()) || !"3.5.10".equals(metadata.getDriverVersion())) {
            throw new java.sql.SQLFeatureNotSupportedException("Managed source connection driver is not verified",
                    "0A000");
        }
        try {
            ClassLoader loader = metadata.unwrap(java.sql.DatabaseMetaData.class).getClass().getClassLoader();
            Class<?> nativeType = Class.forName("org.mariadb.jdbc.Connection", false, loader);
            Object nativeConnection = connection.unwrap(nativeType);
            Object context = nativeType.getMethod("getContext").invoke(nativeConnection);
            Object configuration = Class.forName("org.mariadb.jdbc.client.Context", false, loader)
                    .getMethod("getConf").invoke(context);
            Class<?> configType = Class.forName("org.mariadb.jdbc.Configuration", false, loader);
            Object mode = configType.getMethod("haMode").invoke(configuration);
            Object addresses = configType.getMethod("addresses").invoke(configuration);
            if (!(mode instanceof Enum<?> value) || !"NONE".equals(value.name())
                    || !(addresses instanceof java.util.List<?> hosts) || hosts.size() != 1
                    || !Boolean.FALSE.equals(configType.getMethod("transactionReplay").invoke(configuration))
                    || !Boolean.FALSE.equals(configType.getMethod("permitRedirect").invoke(configuration))
                    || configType.getMethod("socketFactory").invoke(configuration) != null
                    || configType.getMethod("pipe").invoke(configuration) != null
                    || configType.getMethod("localSocket").invoke(configuration) != null
                    || configType.getMethod("initSql").invoke(configuration) != null
                    || configType.getMethod("sessionVariables").invoke(configuration) != null) {
                throw new java.sql.SQLFeatureNotSupportedException("Managed source connection profile is not verified",
                        "0A000");
            }
        } catch (ReflectiveOperationException | LinkageError invalid) {
            throw new java.sql.SQLFeatureNotSupportedException("Managed source driver configuration cannot be verified",
                    "0A000");
        }
    }

    /** 仅执行本地激活检查；旋转源代际前，部署端必须隔离并排空所有旧节点。 */
    public boolean isCurrent(String targetFingerprint, String generationFingerprint) {
        if (targetFingerprint == null || generationFingerprint == null) {
            return false;
        }
        SourceGenerationPin pin = pins.get(targetFingerprint);
        return pin != null && pin.fingerprint().equals(generationFingerprint);
    }

    /** 端点和主体精确绑定；不规范化 URL，不包含密码、平台 ID 或租户策略。 */
    public static String targetFingerprint(ProductTypeEnum product, String jdbcUrl, String username) {
        if (product == null || jdbcUrl == null || jdbcUrl.isBlank() || product != ProductTypeEnum.SQLITE3 && (username == null || username.isBlank())) {
            throw new IllegalArgumentException("Product, JDBC endpoint and principal are required");
        }
        if (username == null) {
            username = "";
        }
        return DigestUtil.sha256Hex("DBSWITCH_SOURCE_TARGET_V1\0" + product.name() + "\0"
                + jdbcUrl.length() + ":" + jdbcUrl + username.length() + ":" + username);
    }
}
