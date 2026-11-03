package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 只解析可证明没有路由覆盖的单目标 URL，不使用表单主机冒充驱动实际连接目标。 */
record DiagnosticEndpoint(String host, int port) {

    private static final Set<String> PARAMETERS = Set.of(
            "ssl", "sslmode", "connecttimeout", "sockettimeout", "logintimeout", "currentschema",
            "applicationname", "tcpkeepalive", "readonly", "preparethreshold", "usessl", "servertimezone",
            "characterencoding", "useunicode", "allowpublickeyretrieval", "zerodatetimebehavior",
            "useinformationschema", "remarksreporting", "tinyint1isbit", "rewritebatchedstatements",
            "useserverprepstmts", "prepstmtcachesqllimit", "defaultfetchsize", "schema", "authsource",
            "useencryption", "database", "databasename", "pool", "encrypt", "trustservercertificate",
            "serialization", "user", "password");

    static Optional<DiagnosticEndpoint> parse(ProductTypeEnum product, String jdbcUrl) {
        if (product == null || jdbcUrl == null || jdbcUrl.length() > 16_384
                || jdbcUrl.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        try {
            if (product == ProductTypeEnum.ORACLE) {
                return oracle(jdbcUrl);
            }
            String prefix = prefix(product);
            if (prefix == null || !jdbcUrl.startsWith(prefix)) {
                return Optional.empty();
            }
            String target = jdbcUrl.substring(prefix.length());
            if (product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000
                    || product == ProductTypeEnum.SAPHANA || product == ProductTypeEnum.PHOENIX) {
                int separator = target.indexOf(';');
                if (separator >= 0) {
                    if (!safeParameters(target.substring(separator + 1), ";")) {
                        return Optional.empty();
                    }
                    target = target.substring(0, separator);
                }
            }
            URI uri = URI.create("tcp://" + target);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getRawAuthority().contains(",") || uri.getRawAuthority().contains("%")
                    || target.contains(";") || target.contains("\\")
                    || uri.getRawQuery() != null && !safeParameters(uri.getRawQuery(), "&")) {
                return Optional.empty();
            }
            // ClickHouse 的 SSL 等传输配置可能改变默认端口，缺少显式端口时不推断。
            // Arrow Flight JDBC 要求显式端口，不能使用产品界面默认值代替驱动契约。
            if (uri.getPort() == -1 && (product == ProductTypeEnum.INFLUXDB3
                    || product == ProductTypeEnum.CLICKHOUSE && uri.getRawQuery() != null)) {
                return Optional.empty();
            }
            return endpoint(uri.getHost(), uri.getPort() == -1 ? defaultPort(product) : uri.getPort());
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static boolean safeParameters(String query, String separator) {
        for (String parameter : query.split(separator, -1)) {
            String key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            // 自定义 socketFactory、代理、故障转移和服务发现等参数不允许推断独立网络目标。
            if (!PARAMETERS.contains(key)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<DiagnosticEndpoint> oracle(String url) {
        String prefix = "jdbc:oracle:thin:@";
        if (!url.startsWith(prefix)) {
            return Optional.empty();
        }
        String target = url.substring(prefix.length());
        if (target.startsWith("//")) {
            URI uri = URI.create("tcp:" + target);
            return uri.getHost() != null && uri.getRawQuery() == null && uri.getFragment() == null
                    && uri.getUserInfo() == null ? endpoint(uri.getHost(), uri.getPort() == -1 ? 1521 : uri.getPort())
                    : Optional.empty();
        }
        // 不猜测 TNS 别名、DESCRIPTION 地址列表、钱包或连接管理器的路由。
        var match = java.util.regex.Pattern.compile("^([A-Za-z0-9_.-]+):([0-9]{1,5}):[A-Za-z0-9_.-]+$")
                .matcher(target);
        return match.matches() ? endpoint(match.group(1), Integer.parseInt(match.group(2))) : Optional.empty();
    }

    private static Optional<DiagnosticEndpoint> endpoint(String host, int port) {
        return port > 0 && port <= 65535 ? Optional.of(new DiagnosticEndpoint(host, port)) : Optional.empty();
    }

    private static String prefix(ProductTypeEnum product) {
        return switch (product) {
            case MYSQL, TIDB, DORIS, STARROCKS -> "jdbc:mysql://";
            case POSTGRESQL, GREENPLUM, GAUSSDB -> "jdbc:postgresql://";
            case MARIADB -> "jdbc:mariadb://";
            case SQLSERVER, SQLSERVER2000 -> "jdbc:sqlserver://";
            case DB2 -> "jdbc:db2://";
            case DM -> "jdbc:dm://";
            case KINGBASE -> "jdbc:kingbase8://";
            case OSCAR -> "jdbc:oscar://";
            case GBASE8A -> "jdbc:gbase://";
            case GBASE8C -> "jdbc:gbase8c://";
            case HIVE -> "jdbc:hive2://";
            case SYBASE -> "jdbc:sybase:Tds:";
            case CLICKHOUSE -> "jdbc:clickhouse://";
            case MONGODB -> "jdbc:mongodb://";
            case TDENGINE -> "jdbc:TAOS-WS://";
            case INFLUXDB3 -> "jdbc:arrow-flight-sql://";
            case HIGHGO -> "jdbc:highgo://";
            case OPENGAUSS -> "jdbc:opengauss://";
            case ELASTICSEARCH -> "jdbc:jest://";
            case OCEANBASE -> "jdbc:oceanbase://";
            case UXDB -> "jdbc:uxdb://";
            case PHOENIX -> "jdbc:phoenix:thin:url=http://";
            case SAPHANA -> "jdbc:sap://";
            case PRESTO -> "jdbc:presto://";
            default -> null;
        };
    }

    private static int defaultPort(ProductTypeEnum product) {
        return switch (product) {
            case MYSQL, MARIADB, TIDB, OCEANBASE, DORIS, STARROCKS -> 3306;
            case POSTGRESQL, GREENPLUM, GAUSSDB, OPENGAUSS -> 5432;
            case SQLSERVER, SQLSERVER2000 -> 1433;
            case DB2 -> 50000;
            case DM -> 5236;
            case KINGBASE -> 54321;
            case OSCAR -> 2003;
            case GBASE8A -> 5258;
            case HIVE -> 10000;
            case SYBASE -> 5000;
            case CLICKHOUSE -> 8123;
            case MONGODB -> 27017;
            case TDENGINE -> 6041;
            case ELASTICSEARCH -> 9200;
            case PRESTO -> 8080;
            // 无法从驱动契约证明默认端口的产品，必须由 URL 显式提供端口。
            default -> -1;
        };
    }
}
