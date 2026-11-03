package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcUrlUtils;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFilePaths;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 平台连接记录到组件配置的领域映射；JSON 只允许非秘密的协议参数。 */
public record ConnectionSettings(String code, Map<String, String> properties) {

    private static final Set<String> JDBC_ONLY = Set.of("GBASE8C", "GAUSSDB", "PHOENIX", "SAPHANA", "PRESTO");

    private static final Set<String> NATIVE = Set.of("ODPS", "NEO4J", "MILVUS", "INFLUXDB2");

    private static final Set<String> EXTERNAL = Set.of("OSS", "MINIO", "REDIS", "KAFKA", "RABBITMQ", "ROCKETMQ",
            "SFTP");

    private static final Set<String> KEYS = Set.of("schemaVersion", "endpoint", "basePath", "hostKeyFingerprint",
            "region", "pathStyle", "securityProtocol", "saslMechanism", "virtualHost", "servers", "topic", "tls",
            "authenticationMode", "timeoutSeconds");

    private static final Set<String> BOOLEAN_KEYS = Set.of("pathStyle", "tls");

    /**
     * 创建连接设置实例。
     *
     * @param code 编码
     * @param properties 组件配置
     */
    public ConnectionSettings {
        properties = Map.copyOf(properties);
    }

    /**
     * 读取连接设置编码。
     *
     * @param type 类型标识
     * @return 连接设置编码文本
     */
    public static String code(DatabaseTypeDO type) {
        String code = type == null || type.getTypeCode() == null ? "" : type.getTypeCode().trim()
                .toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return "ES".equals(code) ? "ELASTICSEARCH" : code;
    }

    /**
     * 判断受管条件是否成立。
     *
     * @param type 类型标识
     * @return 处理是否成功
     */
    public static boolean managed(DatabaseTypeDO type) {
        String code = code(type);
        return JDBC_ONLY.contains(code) || NATIVE.contains(code) || EXTERNAL.contains(code);
    }

    /**
     * 判断外部条件是否成立。
     *
     * @param type 类型标识
     * @return 处理是否成功
     */
    public static boolean external(DatabaseTypeDO type) {
        return EXTERNAL.contains(code(type));
    }

    /**
     * 判断JDBC条件是否成立。
     *
     * @param type 类型标识
     * @return 处理是否成功
     */
    public static boolean jdbc(DatabaseTypeDO type) {
        return JDBC_ONLY.contains(code(type));
    }

    /**
     * 判断免密条件是否成立。
     *
     * @param database 数据源
     * @param type 类型标识
     * @return 处理是否成功
     */
    static boolean passwordless(DatabaseDO database, DatabaseTypeDO type) {
        if ("HDFS".equals(code(type))) {
            return true;
        }
        if (!Set.of("MILVUS", "ROCKETMQ").contains(code(type))) {
            return false;
        }
        try {
            return "NONE".equals(read(database, type).get("authenticationMode",
                    "ROCKETMQ".equals(code(type)) ? "NONE" : "PASSWORD"));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** ES 只分流诊断，不改变受管创建、编辑及原密码复用规则。 */
    static ConnectionSettings readForDiagnosis(DatabaseDO database, DatabaseTypeDO type) {
        if (!"ELASTICSEARCH".equals(code(type))) {
            return read(database, type);
        }
        String raw = blank(database.getExtendConfig()) ? "" : database.getExtendConfig().trim();
        Map<String, String> values = new LinkedHashMap<>();
        if (raw.startsWith("{")) {
            try {
                JsonNode root = JsonUtils.getObjectMapper().readTree(raw);
                require(root.isObject() && root.path("schemaVersion").isIntegralNumber()
                        && root.path("schemaVersion").canConvertToInt()
                        && root.path("schemaVersion").asInt(-1) == 1, "连接配置版本无效");
                for (var entry : root.properties()) {
                    String key = entry.getKey();
                    JsonNode value = entry.getValue();
                    require(Set.of("schemaVersion", "index", "timeoutSeconds", "jdbcParams").contains(key),
                            "诊断配置含不支持的字段或秘密字段");
                    if ("schemaVersion".equals(key)) {
                        continue;
                    }
                    if ("timeoutSeconds".equals(key)) {
                        require(value.isIntegralNumber() && value.canConvertToInt()
                                && value.asInt() >= 1 && value.asInt() <= 30, "连接超时无效");
                        values.put(key, Integer.toString(value.asInt()));
                    } else {
                        require(value.isString(), "诊断配置字段类型无效");
                        values.put(key, value.asString());
                    }
                }
            } catch (IllegalArgumentException invalid) {
                throw invalid;
            } catch (Exception invalid) {
                throw new IllegalArgumentException("连接配置必须是合法 JSON");
            }
        } else if (!raw.isEmpty()) {
            values.put("jdbcParams", raw);
        }
        if (values.containsKey("index")) {
            require(values.get("index").matches("[a-z0-9][a-z0-9._-]{0,254}"),
                    "物理索引必须是单个精确名称，不能包含通配符、路径或多索引表达式");
        }
        String params = values.getOrDefault("jdbcParams", "");
        for (String field : params.split("&", -1)) {
            String key = field.split("=", 2)[0].replaceFirst("^[?&;]+", "");
            key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
            require(!"index".equalsIgnoreCase(key.trim()), "物理索引必须通过 JSON index 字段传入");
        }
        return new ConnectionSettings("ELASTICSEARCH", values);
    }

    /**
     * 读取连接设置。
     *
     * @param database 数据源
     * @param type 类型标识
     * @return 连接设置
     */
    public static ConnectionSettings read(DatabaseDO database, DatabaseTypeDO type) {
        String code = code(type);
        if (!managed(type)) {
            throw new IllegalArgumentException("该连接器尚未接入连接测试");
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (!JDBC_ONLY.contains(code)) {
            try {
                JsonNode root = JsonUtils.getObjectMapper()
                        .readTree(blank(database.getExtendConfig()) ? "{}" : database.getExtendConfig());
                if (!root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                        || !root.path("schemaVersion").canConvertToInt()
                        || root.path("schemaVersion").asInt(-1) != 1) {
                    throw new IllegalArgumentException("连接配置版本无效");
                }
                for (var entry : root.properties()) {
                    String key = entry.getKey();
                    JsonNode value = entry.getValue();
                    if (!KEYS.contains(key)) {
                        throw new IllegalArgumentException("连接配置含不支持的字段或秘密字段");
                    }
                    if ("schemaVersion".equals(key)) {
                        continue;
                    }
                    if ("timeoutSeconds".equals(key)) {
                        if (!value.isIntegralNumber() || !value.canConvertToInt()
                                || value.asInt() < 1 || value.asInt() > 30) {
                            throw new IllegalArgumentException("连接超时必须为 1 到 30 秒的整数");
                        }
                        values.put(key, Integer.toString(value.asInt()));
                    } else if ("servers".equals(key) && value.isArray()) {
                        if (value.size() > 32) {
                            throw new IllegalArgumentException("服务器地址不能超过32个");
                        }
                        var servers = new java.util.ArrayList<String>();
                        for (JsonNode server : value) {
                            if (!server.isString() || blank(server.asString())) {
                                throw new IllegalArgumentException("服务器地址无效");
                            }
                            servers.add(server.asString().trim());
                        }
                        values.put(key, String.join(",", servers));
                    } else if (BOOLEAN_KEYS.contains(key)) {
                        if (!value.isBoolean()) {
                            throw new IllegalArgumentException("连接开关类型无效");
                        }
                        values.put(key, Boolean.toString(value.asBoolean()));
                    } else {
                        if (!value.isString()) {
                            throw new IllegalArgumentException("连接字段类型无效");
                        }
                        String text = value.asString().trim();
                        if (text.length() > 4096 || text.chars().anyMatch(c -> c < 32 || c == 127)) {
                            throw new IllegalArgumentException("连接字段格式无效");
                        }
                        values.put(key, text);
                    }
                }
            } catch (IllegalArgumentException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalArgumentException("连接配置必须是合法 JSON");
            }
        }
        ConnectionSettings settings = new ConnectionSettings(code, values);
        settings.validate(database);
        return settings;
    }

    /**
     * 读取连接设置。
     *
     * @param name 名称
     * @param fallback 回退
     * @return 连接设置文本
     */
    public String get(String name, String fallback) {
        String value = properties.get(name);
        return blank(value) ? fallback : value;
    }

    /**
     * 判断标记条件是否成立。
     *
     * @param name 名称
     * @param fallback 回退
     * @return 处理是否成功
     */
    public boolean flag(String name, boolean fallback) {
        return Boolean.parseBoolean(get(name, Boolean.toString(fallback)));
    }

    /**
     * 获取连接设置的超时。
     *
     * @return 耗时
     */
    public Duration timeout() {
        return Duration.ofSeconds(Integer.parseInt(get("timeoutSeconds", "10")));
    }

    /** 只改变探测时长或补写等价默认值时，可以继续使用同一目标的已存凭据。 */
    Map<String, String> credentialProperties() {
        Map<String, String> effective = new LinkedHashMap<>(properties);
        effective.remove("timeoutSeconds");
        effective.entrySet().removeIf(entry -> blank(entry.getValue()));
        switch (code) {
            case "OSS", "MINIO" ->
                    effective.put("pathStyle", Boolean.toString(flag("pathStyle", "MINIO".equals(code))));
            case "SFTP" -> effective.put("basePath", get("basePath", "/"));
            case "REDIS" -> effective.put("tls", Boolean.toString(flag("tls", false)));
            case "RABBITMQ" -> {
                effective.put("tls", Boolean.toString(flag("tls", false)));
                effective.put("virtualHost", get("virtualHost", "/"));
            }
            case "KAFKA" -> {
                effective.put("securityProtocol", get("securityProtocol", "PLAINTEXT"));
                effective.put("saslMechanism", get("saslMechanism", "PLAIN"));
                effective.put("tls", Boolean.toString(flag("tls", false)));
            }
            case "MILVUS" -> effective.put("authenticationMode", get("authenticationMode", "PASSWORD"));
            case "ROCKETMQ" -> effective.put("authenticationMode", get("authenticationMode", "NONE"));
            default -> {
            }
        }
        if (Set.of("KAFKA", "ROCKETMQ").contains(code)) {
            effective.put("servers", java.util.Arrays.stream(get("servers", "").split("[,;]", -1))
                    .map(String::trim).collect(java.util.stream.Collectors.joining(",")));
        }
        return effective;
    }

    /** 在已解析旧密文或本次输入后验证认证方式，避免显式认证意外退化为匿名。 */
    void validateCredentials(DatabaseDO database, String password) {
        if (Set.of("NEO4J", "OSS", "MINIO", "ODPS", "SFTP", "RABBITMQ").contains(code)) {
            require(!blank(database.getUserName()) && !blank(password), "该协议需要有效账号和密码或访问密钥");
        }
        if ("INFLUXDB2".equals(code)) {
            require(!blank(password), "InfluxDB 2 需要访问令牌");
        }
        if ("REDIS".equals(code) && !blank(database.getUserName())) {
            require(!blank(password), "Redis ACL 用户认证需要密码");
        }
        if ("KAFKA".equals(code) && get("securityProtocol", "PLAINTEXT").startsWith("SASL_")) {
            require(!blank(database.getUserName()) && !blank(password), "Kafka SASL 认证需要用户名和密码");
        }
        if ("MILVUS".equals(code)) {
            String mode = get("authenticationMode", "PASSWORD");
            if ("PASSWORD".equals(mode)) {
                require(!blank(database.getUserName()) && !blank(password), "Milvus 密码认证需要用户名和密码");
            }
            if ("TOKEN".equals(mode)) {
                require(!blank(password), "Milvus Token 认证需要令牌");
            }
        }
        if ("ROCKETMQ".equals(code) && "ACL".equals(get("authenticationMode", "NONE"))) {
            require(!blank(database.getUserName()) && !blank(password), "RocketMQ ACL 认证需要访问密钥和秘密密钥");
        }
    }

    /**
     * 校验连接设置输入及状态。
     *
     * @param db 数据库
     */
    private void validate(DatabaseDO db) {
        if (JDBC_ONLY.contains(code)) {
            require(!blank(db.getJdbcUrl()) && db.getJdbcUrl().startsWith("jdbc:"), "JDBC 连接地址不能为空");
            JdbcUrlUtils.appendParams(ProductTypeEnum.valueOf(code), db.getJdbcUrl(), db.getExtendConfig());
            return;
        }
        if (Set.of("OSS", "MINIO", "ODPS", "NEO4J", "MILVUS", "INFLUXDB2").contains(code)) {
            require(!blank(get("endpoint", "")), "连接服务地址不能为空");
            URI uri;
            try {
                uri = URI.create(get("endpoint", ""));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("连接服务地址无效");
            }
            Set<String> schemes = "NEO4J".equals(code)
                    ? Set.of("bolt", "bolt+s", "neo4j", "neo4j+s") : Set.of("http", "https");
            require(schemes.contains(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                            && uri.getRawQuery() == null && uri.getRawFragment() == null
                            && !uri.getRawAuthority().endsWith(":")
                            && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535),
                    "服务地址必须使用受支持协议且不能包含凭据或查询参数");
            if ("NEO4J".equals(code)) {
                require(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()),
                        "Neo4j 地址不包含数据库路径，请在数据库名称字段填写目标库");
                require(!blank(db.getUserName()), "Neo4j 用户名不能为空");
            }
        }
        if (Set.of("OSS", "MINIO", "ODPS", "INFLUXDB2").contains(code)) {
            require(!blank(db.getDatabaseName()), "目标桶或项目名称不能为空");
            require(!blank(db.getUserName()), "账号或组织名称不能为空");
        }
        if (Set.of("NEO4J", "MILVUS").contains(code)) {
            require(!blank(db.getDatabaseName()), "目标数据库不能为空");
        }
        if (Set.of("SFTP", "REDIS", "RABBITMQ").contains(code)) {
            address(db);
        }
        if ("SFTP".equals(code)) {
            require(!blank(db.getUserName()), "SFTP 用户名不能为空");
            require(get("hostKeyFingerprint", "").matches("SHA256:[A-Za-z0-9+/]{43}"), "SFTP 需要可信的服务器公钥指纹");
            RemoteFilePaths.normalize(get("basePath", "/"));
        }
        if (Set.of("KAFKA", "ROCKETMQ").contains(code)) {
            require(!blank(get("servers", "")), "服务器地址不能为空");
            String[] servers = get("servers", "").split("[,;]", -1);
            require(servers.length <= ("ROCKETMQ".equals(code) ? 8 : 32), "服务器地址数量超过协议限制");
            for (String server : servers) {
                try {
                    URI uri = URI.create("tcp://" + server.trim());
                    require(uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                            && uri.getRawUserInfo() == null && uri.getPath().isEmpty()
                            && uri.getQuery() == null && uri.getFragment() == null, "服务器地址必须为主机和端口");
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("服务器地址必须为主机和端口");
                }
            }
        }
        if ("MILVUS".equals(code)) {
            require(Set.of("NONE", "PASSWORD", "TOKEN").contains(get("authenticationMode", "PASSWORD")),
                    "Milvus 认证模式不受支持");
        }
        if ("ROCKETMQ".equals(code)) {
            require(Set.of("NONE", "ACL").contains(get("authenticationMode", "NONE")), "RocketMQ 认证模式不受支持");
        }
        if ("KAFKA".equals(code)) {
            require(Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")
                    .contains(get("securityProtocol", "PLAINTEXT")), "Kafka 安全协议不受支持");
            require(Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512").contains(get("saslMechanism", "PLAIN")),
                    "Kafka 认证机制不受支持");
        }
        if ("REDIS".equals(code)) {
            try {
                require(Integer.parseInt(blank(db.getDatabaseName()) ? "0" : db.getDatabaseName()) >= 0,
                        "Redis 数据库编号无效");
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Redis 数据库编号无效");
            }
        }
    }

    /**
     * 校验连接主机及端口，并拒绝夹带用户信息、路径或查询片段的地址。
     *
     * @param db 数据库
     */
    static void address(DatabaseDO db) {
        require(!blank(db.getDatabaseIp()), "主机地址不能为空");
        try {
            int port = Integer.parseInt(db.getDatabasePort());
            URI uri = URI.create("tcp://" + db.getDatabaseIp() + ":" + port);
            require(port > 0 && port <= 65535 && uri.getHost() != null && uri.getRawUserInfo() == null
                            && uri.getPath().isEmpty() && uri.getQuery() == null && uri.getFragment() == null,
                    "连接主机或端口无效");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("连接主机或端口无效");
        }
    }

    /**
     * 校验连接设置所需条件，不满足时抛出配置错误。
     *
     * @param valid 有效
     * @param message 消息
     */
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 判断文本是否为空或只包含空白字符。
     *
     * @param text 待处理文本
     * @return 处理是否成功
     */
    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
