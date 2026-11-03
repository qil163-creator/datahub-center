package cn.iocoder.yudao.framework.dbswitch.driver;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

/** 已验证 Mongo 桥接器的连接、服务器选择及单次套接字读取均采用有限等待。
 * 它们不是套接字绝对存活时限：慢速对端仍可持续产生部分进展。
 * 独立的调用方截止时间保证按时返回；未完成工作仍处于隔离并占用额度，
 * 直到物理关闭和实际工作线程退出均被观测到。 */
final class MongoNetworkPolicy {

    private static final Map<String, Integer> LIMITS = Map.of("sockettimeoutms", 30_000,
            "serverselectiontimeoutms", 30_000, "connecttimeoutms", 10_000);

    private static final Map<String, String> NAMES = Map.of("sockettimeoutms", "socketTimeoutMS",
            "serverselectiontimeoutms", "serverSelectionTimeoutMS", "connecttimeoutms", "connectTimeoutMS",
            "retryreads", "retryReads", "retrywrites", "retryWrites");

    record Prepared(String url, Properties properties) {

        /**
         * 初始化 {@code Prepared}。
         */
        Prepared {
            properties = copy(properties);
        }

        /**
         * 返回属性配置。
         */
        @Override
        public Properties properties() {
            return copy(properties);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "MongoNetworkPolicy.Prepared[bounded=true]";
        }
    }

    /**
     * 初始化 {@code MongoNetworkPolicy}。
     */
    private MongoNetworkPolicy() {
    }

    /**
     * 检查 MongoDB JDBC 地址并设置有界连接、服务器选择和套接字等待参数。
     */
    static Prepared prepare(String url, Properties input) throws SQLException {
        try {
            if (url == null || !url.startsWith("jdbc:mongodb://") || url.indexOf('#') >= 0 || url.length() > 65536) {
                throw new IllegalArgumentException();
            }
            int queryStart = url.indexOf('?');
            String base = queryStart < 0 ? url : url.substring(0, queryStart);
            List<String> retained = new ArrayList<>();
            Map<String, Integer> effective = new HashMap<>(LIMITS);
            if (queryStart >= 0) {
                for (String item : url.substring(queryStart + 1).split("&", -1)) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    int separator = item.indexOf('=');
                    if (separator < 1) {
                        throw new IllegalArgumentException();
                    }
                    String key = decoded(item.substring(0, separator));
                    String value = decoded(item.substring(separator + 1));
                    requireUnambiguous(key, value);
                    String normalized = key.toLowerCase(Locale.ROOT);
                    if (NAMES.containsKey(normalized)) {
                        apply(effective, normalized, value);
                    } else {
                        retained.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value,
                                StandardCharsets.UTF_8));
                    }
                }
            }
            Properties properties = new Properties();
            for (String key : input.stringPropertyNames()) {
                String normalized = decoded(key).toLowerCase(Locale.ROOT);
                if (NAMES.containsKey(normalized)) {
                    apply(effective, normalized, decoded(input.getProperty(key)));
                } else {
                    properties.setProperty(key, input.getProperty(key));
                }
            }
            for (String key : List.of("connecttimeoutms", "sockettimeoutms", "serverselectiontimeoutms")) {
                String value = Integer.toString(effective.get(key));
                retained.add(NAMES.get(key) + "=" + value);
                properties.setProperty(NAMES.get(key), value);
            }
            // Mongo SDK 4.10 的 runCommand 已禁用重试。此处还固定读取器重试策略。
            retained.add("retryReads=false");
            retained.add("retryWrites=false");
            properties.setProperty("retryReads", "false");
            properties.setProperty("retryWrites", "false");
            return new Prepared(base + "?" + String.join("&", retained), properties);
        } catch (RuntimeException invalid) {
            throw new SQLException("MongoDB network parameters are invalid or ambiguous");
        }
    }

    /**
     * 重新解析连接信息并检查其与已校验的网络边界一致。
     */
    static void verifyParsed(Prepared prepared, ClassLoader loader) throws SQLException {
        try {
            Class<?> type = Class.forName("com.mongodb.ConnectionString", false, loader);
            // 桥接器在添加凭据时调用一次 URI.getQuery。prepare 拒绝解码后的
            // 分隔符，因此这次额外解码不能注入第二个超时或重试参数。
            Object parsed = type.getConstructor(String.class).newInstance(prepared.url().substring(5));
            for (String[] pair : new String[][]{{"getSocketTimeout", "socketTimeoutMS"},
                    {"getConnectTimeout", "connectTimeoutMS"}, {"getServerSelectionTimeout", "serverSelectionTimeoutMS"}}) {
                Object actual = type.getMethod(pair[0]).invoke(parsed);
                if (!(actual instanceof Number number) || number.intValue() != Integer.parseInt(
                        prepared.properties.getProperty(pair[1]))) {
                    throw new ReflectiveOperationException();
                }
            }
            if (!Boolean.FALSE.equals(type.getMethod("getRetryReads").invoke(parsed))
                    || !Boolean.FALSE.equals(type.getMethod("getRetryWritesValue").invoke(parsed))) {
                throw new ReflectiveOperationException();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new SQLException("MongoDB effective network limits could not be verified");
        }
    }

    /**
     * 校验并归并当前 MongoDB 网络超时参数。
     */
    private static void apply(Map<String, Integer> effective, String name, String value) {
        if (LIMITS.containsKey(name)) {
            if (!value.matches("[0-9]{1,18}")) {
                throw new IllegalArgumentException();
            }
            long requested = Long.parseLong(value);
            if (requested > 0) {
                effective.compute(name, (ignored, previous) -> (int) Math.min(previous, requested));
            }
        } else if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * 返回当前值解码后的内容。
     */
    private static String decoded(String value) {
        return URLDecoder.decode(Objects.requireNonNull(value), StandardCharsets.UTF_8);
    }

    /**
     * 要求当前配置或对象解析结果不存在歧义。
     */
    private static void requireUnambiguous(String key, String value) {
        if (!key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || value.chars()
                .anyMatch(c -> c == '%' || c == '&' || c == ';' || c == '=' || Character.isISOControl(c))) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * 返回当前COPY。
     */
    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        source.stringPropertyNames().forEach(key -> copy.setProperty(key, source.getProperty(key)));
        return copy;
    }
}
