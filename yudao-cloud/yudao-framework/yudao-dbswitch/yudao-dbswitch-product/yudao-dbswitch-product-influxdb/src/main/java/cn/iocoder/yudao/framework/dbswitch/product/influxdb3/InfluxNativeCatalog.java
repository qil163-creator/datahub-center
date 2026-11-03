package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.*;

/** 原生角色证据。UTF8 字段和字典编码标签是不同的列。 */
public final class InfluxNativeCatalog {

    /**
     * 初始化 {@code InfluxNativeCatalog}。
     */
    private InfluxNativeCatalog() {
    }

    public enum Kind {TAG, TIME, INT64, UINT64, FLOAT64, STRING, BOOLEAN}

    public record Column(String name, Kind kind) {

        /**
         * 校验构造参数并初始化 {@code Column}。
         */
        public Column {
            InfluxNativeSession.identifier(name);
            Objects.requireNonNull(kind);
        }
    }

    public record Table(String name, List<Column> columns) {

        /**
         * 初始化 {@code Table}。
         */
        public Table {
            InfluxNativeSession.identifier(name);
            columns = List.copyOf(columns);
        }

        /**
         * 返回列。
         */
        public Column column(String name) {
            return columns.stream().filter(c -> c.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Influx native column is absent"));
        }

        /**
         * 返回字段集合。
         */
        public List<String> fields() {
            return columns.stream().map(Column::name).toList();
        }

        /**
         * 返回键集合。
         */
        public List<String> keys() {
            var keys = new ArrayList<String>();
            columns.stream().filter(c -> c.kind() == Kind.TAG).forEach(c -> keys.add(c.name()));
            keys.add("time");
            return List.copyOf(keys);
        }
    }

    /**
     * 返回模式。
     */
    public static void schema(String schema) {
        if (!"iox".equals(schema)) {
            throw new IllegalArgumentException("Influx physical tables belong to the iox schema");
        }
    }

    /**
     * 返回当前查找。
     */
    public static Table find(InfluxNativeSession session, String table) throws SQLException {
        InfluxNativeSession.identifier(table);
        JsonNode names = session.query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='iox' AND table_name=" + InfluxNativeSession.literal(
                        table) + " LIMIT 2");
        if (!names.isArray() || names.size() > 1) {
            throw new SQLException("Influx native relation identity is ambiguous");
        }
        if (names.isEmpty()) {
            return null;
        }
        JsonNode evidence = session.query(
                "SELECT key,data_type FROM system.influxdb_schema WHERE measurement=" + InfluxNativeSession.literal(
                        table) + " ORDER BY key LIMIT 501");
        if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > 500) {
            throw new SQLException("Influx native schema exceeds its column budget");
        }
        var columns = new ArrayList<Column>();
        var seen = new HashSet<String>();
        for (var item : evidence) {
            String name = text(item, "key"), type = text(item, "data_type");
            Kind kind = switch (type) {
                case "tag" -> Kind.TAG;
                case "time" -> Kind.TIME;
                case "integer" -> Kind.INT64;
                case "uinteger" -> Kind.UINT64;
                case "float" -> Kind.FLOAT64;
                case "string" -> Kind.STRING;
                case "boolean" -> Kind.BOOLEAN;
                default -> throw new SQLException("Influx native type needs explicit adaptation");
            };
            if (!seen.add(name) || kind == Kind.TIME && !name.equals("time")) {
                throw new SQLException("Influx native column evidence is inconsistent");
            }
            columns.add(new Column(name, kind));
        }
        if (columns.stream().filter(c -> c.kind() == Kind.TIME).count() != 1) {
            throw new SQLException("Influx native timestamp evidence is absent");
        }
        return new Table(table, columns);
    }

    /**
     * 检查会话、表是否满足当前操作的约定。
     */
    public static Table require(InfluxNativeSession session, String table) throws SQLException {
        Table result = find(session, table);
        if (result == null) {
            throw new SQLException("Influx native target must already exist");
        }
        return result;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    static String text(JsonNode node, String key) throws SQLException {
        if (!node.path(key).isTextual()) {
            throw new SQLException("Influx native catalog evidence is incomplete");
        }
        return node.path(key).textValue();
    }
}
