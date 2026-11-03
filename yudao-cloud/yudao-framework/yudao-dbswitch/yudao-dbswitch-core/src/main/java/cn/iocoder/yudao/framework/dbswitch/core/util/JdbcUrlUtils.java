package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.DatabaseDescription;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC的URL相关工具类
 *
 * @author jrl
 */
public class JdbcUrlUtils {

    private static Pattern patternWithParam = Pattern.compile(
            "(?<protocol>^.+):(?<dbtype>.+)://(?<addresss>.+):(?<port>.+)/(?<schema>.+)\\?(?<path>.+)");

    private static Pattern patternSimple = Pattern.compile(
            "(?<protocol>^.+):(?<dbtype>.+)://(?<addresss>.+):(?<port>.+)/(?<schema>.+)");

    /**
     * Oracle数据库Jdbc连接模式
     *
     * @author tang
     */
    protected enum OracleJdbcConnectionMode {
        /**
         * SID
         */
        SID(1),

        /**
         * SerivceName
         */
        SERVICENAME(2),

        /**
         * TNSName
         */
        TNSNAME(3);

        private int index;

        /**
         * 初始化 {@code OracleJdbcConnectionMode}。
         */
        OracleJdbcConnectionMode(int idx) {
            this.index = idx;
        }

        /**
         * 返回索引。
         */
        public int getIndex() {
            return index;
        }
    }

    /**
     * 根据数据库种类拼接jdbc的url信息 参考地址：https://www.cnblogs.com/chenglc/p/8421573.html
     * <p>
     * 说明：
     * <p>
     * （1）SQLServer数据库驱动问题
     * <p>
     * 在SQL Server 2000 中加载驱动和URL路径的语句是
     * <p>
     * String driverName = "com.microsoft.jdbc.sqlserver.SQLServerDriver";
     * <p>
     * String dbURL = "jdbc:microsoft:sqlserver://localhost:1433;DatabaseName=sample";
     * <p>
     * 而SQL Server2005和SQL Server 2008 中加载驱动和URL的语句则为
     * <p>
     * String driverName = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
     * <p>
     * String dbURL = "jdbc:sqlserver://localhost:1433; DatabaseName=sample";
     * <p>
     * （2）Oracle数据库驱动连接问题
     * <p>
     * JDBC的URL三种方式：https://blog.csdn.net/gnail_oug/article/details/80075263
     *
     * @param db             数据库连接描述信息
     * @param connectTimeout 连接超时时间(单位：秒）
     * @return 对应数据库的JDBC的URL字符串
     */
    public static String getJdbcUrl(DatabaseDescription db, int connectTimeout) {
        switch (db.getType()) {
            case MYSQL:
            case STARROCKS:
                String charset = db.getCharset();
                if (Objects.isNull(charset) || charset.isEmpty()) {
                    charset = "utf-8";
                }
                return String.format(
                        "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=%s&nullCatalogMeansCurrent=true&connectTimeout=%d",
                        db.getHost(), db.getPort(), db.getDbname(), charset, connectTimeout * 1000);
            case ORACLE:
                OracleJdbcConnectionMode type;
                String mode = db.getMode();
                if (Objects.isNull(mode)) {
                    type = OracleJdbcConnectionMode.SID;
                } else {
                    type = OracleJdbcConnectionMode.valueOf(mode.trim().toUpperCase());
                }

                // 处理Oracle的oracle.sql.TIMESTAMP类型到java.sql.Timestamp的兼容问题
                // 参考地址：https://blog.csdn.net/alanwei04/article/details/77507807
                System.setProperty("oracle.jdbc.J2EE13Compliant", "true");
                // Oracle设置连接超时时间
                System.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(1000 * connectTimeout));
                if (OracleJdbcConnectionMode.SID == type) {
                    return String.format("jdbc:oracle:thin:@%s:%d:%s",
                            db.getHost(), db.getPort(), db.getDbname());
                } else if (OracleJdbcConnectionMode.SERVICENAME == type) {
                    return String.format("jdbc:oracle:thin:@//%s:%d/%s",
                            db.getHost(), db.getPort(), db.getDbname());
                } else if (OracleJdbcConnectionMode.TNSNAME == type) {
                    //
                    // return String.format(
                    // "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=%s)(PORT=%d)))
                    // "
                    // + "(CONNECT_DATA=(SERVICE_NAME=%s)))",
                    /// db.getHost(), db.getPort(), db.getDbname());
                    //
                    // (DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=172.17.20.58)(PORT=1521)))(CONNECT_DATA=(SID=orcl)))
                    //
                    // 或
                    //
                    // (DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=172.17.20.58)(PORT=1521)))(CONNECT_DATA=(SERVICE_NAME=orcl.test.com.cn)))
                    return String.format("jdbc:oracle:thin:@%s", db.getDbname());
                } else {
                    return String.format("jdbc:oracle:thin:@%s:%d:%s",
                            db.getHost(), db.getPort(), db.getDbname());
                }
            case SQLSERVER2000:
                return String.format("jdbc:microsoft:sqlserver://%s:%d;DatabaseName=%s",
                        db.getHost(), db.getPort(),
                        db.getDbname());
            case SQLSERVER:
                return String.format("jdbc:sqlserver://%s:%d;DatabaseName=%s",
                        db.getHost(), db.getPort(), db.getDbname());
            case POSTGRESQL:
                return String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=%d",
                        db.getHost(), db.getPort(),
                        db.getDbname(), connectTimeout);
            case GREENPLUM:
                return String.format("jdbc:pivotal:greenplum://%s:%d;DatabaseName=%s",
                        db.getHost(), db.getPort(),
                        db.getDbname());
            case HIGHGO:
                return String.format("jdbc:highgo://%s:%d/%s?connectTimeout=%d",
                        db.getHost(), db.getPort(), db.getDbname(), connectTimeout);
            case OPENGAUSS:
                return String.format("jdbc:opengauss://%s:%d/%s?connectTimeout=%d",
                        db.getHost(), db.getPort(), db.getDbname(), connectTimeout);
            case ELASTICSEARCH:
                return String.format("jdbc:jest://%s:%d", db.getHost(), db.getPort());
            case OCEANBASE:
                return String.format(
                        "jdbc:oceanbase://%s:%d/%s?pool=false&useUnicode=true&characterEncoding=utf-8&useSSL=false&connectTimeout=%d",
                        db.getHost(), db.getPort(), db.getDbname(), connectTimeout * 1000);
            case UXDB:
                return String.format("jdbc:uxdb://%s:%d/%s?connectTimeout=%d",
                        db.getHost(), db.getPort(), db.getDbname(), connectTimeout);
            case MARIADB:
                String charsets = db.getCharset();
                if (Objects.isNull(charsets) || charsets.isEmpty()) {
                    charsets = "utf-8";
                }
                return String.format(
                        "jdbc:mariadb://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=%s&nullCatalogMeansCurrent=true&connectTimeout=%d",
                        db.getHost(), db.getPort(), db.getDbname(), charsets, connectTimeout * 1000);
            case DB2:
                System.setProperty("db2.jcc.charsetdecoderencoder", "3");
                return String.format("jdbc:db2://%s:%d/%s", db.getHost(), db.getPort(), db.getDbname());
            default:
                throw new RuntimeException(
                        String.format("Unknown database type (%s)", db.getType().name()));
        }
    }

    /**
     * 按产品协议追加普通连接参数。Phoenix/Hive 使用分号，DB2 使用冒号后的分号列表，
     * SQL Server 使用其花括号转义；HANA 和其他已有 URL 参数产品使用查询串。
     * 本方法只负责语法，调用方仍须执行参数白名单与凭据策略。
     */
    public static String appendParams(ProductTypeEnum product, String jdbcUrl, Map<String, ?> params) {
        Objects.requireNonNull(product, "product");
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("缺少合法 JDBC 地址");
        }
        if (params == null || params.isEmpty()) {
            return jdbcUrl;
        }
        if (product.getDriveClassName() == null) {
            throw new UnsupportedOperationException("原生协议不接受 JDBC URL 参数");
        }
        var normalized = new java.util.TreeMap<String, String>();
        params.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.:-]*")) {
                throw new IllegalArgumentException("连接参数名称不合法");
            }
            if (secretParam(key)) {
                throw new IllegalArgumentException("凭据不能通过扩展 URL 参数传入");
            }
            if (value == null) {
                return;
            }
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("连接参数必须为字符串、数字或布尔值");
            }
            String text = value.toString();
            if (text.indexOf('\0') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("连接参数包含非法控制字符");
            }
            normalized.put(key, text);
        });
        var result = new StringBuilder(jdbcUrl);
        boolean semicolon = product == ProductTypeEnum.PHOENIX || product == ProductTypeEnum.HIVE
                || product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000;
        for (var entry : normalized.entrySet()) {
            String value = entry.getValue();
            if (semicolon || product == ProductTypeEnum.DB2) {
                if (product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000) {
                    if (value.indexOf(';') >= 0 || value.indexOf('{') >= 0 || value.indexOf('}') >= 0) {
                        value = "{" + value.replace("}", "}}") + "}";
                    }
                } else if (value.indexOf(';') >= 0) {
                    throw new IllegalArgumentException("该产品的连接参数不能包含分号");
                }
                if (product == ProductTypeEnum.DB2 && result.substring(result.lastIndexOf("/") + 1).indexOf(':') < 0) {
                    result.append(':');
                } else if (result.charAt(result.length() - 1) != ';'
                        && !(product == ProductTypeEnum.PHOENIX && result.toString().endsWith("thin:"))) {
                    result.append(';');
                }
                result.append(entry.getKey()).append('=').append(value);
                if (product == ProductTypeEnum.DB2) {
                    result.append(';');
                }
            } else {
                char last = result.charAt(result.length() - 1);
                if (last != '?' && last != '&') {
                    result.append(result.indexOf("?") >= 0 ? '&' : '?');
                }
                result.append(queryParam(entry.getKey())).append('=').append(queryParam(value));
            }
        }
        return result.toString();
    }

    /** 覆盖 MySQL 协议查询参数的全部同名项，其他参数保持原始编码，不修改凭据。 */
    public static String overrideMysqlQueryParams(String jdbcUrl, Map<String, ?> required) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.indexOf('#') >= 0) {
            throw new IllegalArgumentException("需要不含片段的 MySQL 协议 JDBC URL");
        }
        Objects.requireNonNull(required, "required");
        if (required.isEmpty()) return jdbcUrl;
        if (required.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("覆盖连接参数不能为空");
        }
        // 先复用已有参数验证，禁止凭据、非法键及结构化值进入覆盖契约。
        appendParams(ProductTypeEnum.MYSQL, "jdbc:mysql://localhost/", required);
        var keys = new java.util.HashSet<String>();
        required.keySet().forEach(key -> {
            if (!keys.add(key.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("覆盖连接参数存在重复名称");
            }
        });
        int question = jdbcUrl.indexOf('?');
        String base = question < 0 ? jdbcUrl : jdbcUrl.substring(0, question);
        var retained = new java.util.ArrayList<String>();
        if (question >= 0) {
            for (String field : jdbcUrl.substring(question + 1).split("&", -1)) {
                if (field.isEmpty()) continue;
                String key;
                try {
                    key = java.net.URLDecoder.decode(field.split("=", 2)[0],
                            java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("JDBC URL 参数名称包含不合法编码");
                }
                if (!keys.contains(key)) retained.add(field);
            }
        }
        String preserved = retained.isEmpty() ? base : base + '?' + String.join("&", retained);
        return appendParams(ProductTypeEnum.MYSQL, preserved, required);
    }

    /** 接受表单既有的查询串或 Phoenix 等产品的分号属性，不接受凭据和重复键。 */
    public static String appendParams(ProductTypeEnum product, String jdbcUrl, String rawParams) {
        if (rawParams == null || rawParams.isBlank()) {
            return appendParams(product, jdbcUrl, Map.of());
        }
        String input = rawParams.trim();
        while (!input.isEmpty() && (input.charAt(0) == '?' || input.charAt(0) == '&' || input.charAt(0) == ';')) {
            input = input.substring(1);
        }
        boolean semicolon = product == ProductTypeEnum.PHOENIX || product == ProductTypeEnum.HIVE
                || product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000 || product == ProductTypeEnum.DB2;
        String separator = semicolon && input.indexOf(';') >= 0 ? ";" : "&";
        var parsed = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (String field : input.split(separator, -1)) {
            if (field.isBlank()) {
                continue;
            }
            int equals = field.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("扩展连接参数必须采用 key=value 格式");
            }
            String key;
            String value;
            try {
                key = java.net.URLDecoder.decode(field.substring(0, equals).trim(),
                        java.nio.charset.StandardCharsets.UTF_8);
                value = java.net.URLDecoder.decode(field.substring(equals + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("扩展连接参数包含不合法的百分号编码");
            }
            if (parsed.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("扩展连接参数存在重复名称");
            }
        }
        return appendParams(product, jdbcUrl, parsed);
    }

    /**
     * 判断并处理当前请求中的敏感参数。
     */
    private static boolean secretParam(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("user") || normalized.equals("username") || normalized.equals("userid")
                || normalized.equals("pwd") || normalized.equals("passwd") || normalized.startsWith("avatica")
                || normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("credential") || normalized.contains("accesskey") || normalized.contains(
                "apikey")
                || normalized.contains("keytab") || normalized.contains("jaas");
    }

    /**
     * 读取当前查询参数并按协议处理其值。
     */
    private static String queryParam(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 从MySQL数据库的JDBC的URL中提取数据库连接相关参数
     *
     * @param jdbcUrl JDBC连接的URL字符串
     * @return Map 参数列表
     */
    public static Map<String, String> findParamsByMySqlJdbcUrl(String jdbcUrl) {
        Pattern pattern = null;
        if (jdbcUrl.indexOf('?') > 0) {
            pattern = patternWithParam;
        } else {
            pattern = patternSimple;
        }

        Matcher m = pattern.matcher(jdbcUrl);
        if (m.find()) {
            Map<String, String> ret = new HashMap<String, String>();
            ret.put("protocol", m.group("protocol"));
            ret.put("dbtype", m.group("dbtype"));
            ret.put("addresss", m.group("addresss"));
            ret.put("port", m.group("port"));
            ret.put("schema", m.group("schema"));

            if (m.groupCount() > 5) {
                ret.put("path", m.group("path"));
            }

            return ret;
        } else {
            return null;
        }
    }

}
