package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.RowSetMetaDataImpl;

/** 仅保留一页，持有一个连接；耗尽、关闭、取消或失败时清理 scroll。 */
public final class ElasticsearchScroll {

    /**
     * 初始化 {@code ElasticsearchScroll}。
     */
    private ElasticsearchScroll() {
    }

    /**
     * 打开语句结果集合并准备后续访问。
     */
    public static StatementResultSet open(Connection connection, ElasticsearchNativeSession session, String index,
            List<String> requestedFields, List<String> orders, int fetchSize, int timeoutSeconds) throws SQLException {
        Map<String, ElasticsearchMapping.Field> mapping = ElasticsearchMapping.read(session, index);
        List<String> fields = requestedFields == null || requestedFields.isEmpty()
                ? List.copyOf(mapping.keySet()) : List.copyOf(requestedFields);
        if (new HashSet<>(fields).size() != fields.size() || !mapping.keySet().containsAll(fields)) {
            throw new IllegalArgumentException("Elasticsearch projection must contain distinct mapped fields");
        }
        List<Object> sort = new ArrayList<>();
        if (orders != null) {
            for (String order : orders) {
                var field = mapping.get(order);
                if (field == null || order.equals("_id") || field.type().equals("text")) {
                    throw new IllegalArgumentException("Elasticsearch order requires a mapped sortable field");
                }
                sort.add(Map.of(order, "asc"));
            }
        }
        if (sort.isEmpty()) {
            sort.add("_doc");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("size", fetchSize);
        request.put("sort", sort);
        request.put("query", Map.of("match_all", Map.of()));
        request.put("_source", fields.stream().filter(name -> !name.equals("_id")).toList());
        request.put("stored_fields", List.of("_routing"));
        request.put("timeout", timeoutSeconds + "s");
        var cursor = new Cursor(connection, session, fields, mapping, fetchSize);
        try {
            cursor.page(session.request("POST", "/" + index + "/_search?scroll=2m",
                    ElasticsearchNativeSession.json(request)).requireSuccess());
            return cursor.result();
        } catch (SQLException | RuntimeException failure) {
            try {
                cursor.clear();
            } catch (SQLException cleanup) {
                failure.addSuppressed(new SQLException("Elasticsearch scroll cleanup failed"));
            }
            throw failure;
        }
    }

    private static final class Cursor {

        private final Connection connection;

        private final ElasticsearchNativeSession session;

        private final List<String> fields;

        private final Map<String, ElasticsearchMapping.Field> mapping;

        private final int pageSize;

        private JsonNode hits;

        private int position = -1;

        private int row;

        private String scrollId;

        private boolean closed;

        private boolean exhausted;

        private boolean wasNull;

        private ResultSet rs;

        private Statement statement;

        private final RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();

        /**
         * 初始化 {@code Cursor}。
         */
        Cursor(Connection connection, ElasticsearchNativeSession session, List<String> fields,
                Map<String, ElasticsearchMapping.Field> mapping, int pageSize) throws SQLException {
            this.connection = connection;
            this.session = session;
            this.fields = fields;
            this.mapping = mapping;
            this.pageSize = pageSize;
            metadata.setColumnCount(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                var field = mapping.get(fields.get(i));
                metadata.setColumnName(i + 1, field.name());
                metadata.setColumnLabel(i + 1, field.name());
                metadata.setColumnType(i + 1, field.jdbcType());
                metadata.setColumnTypeName(i + 1, field.type());
                metadata.setNullable(i + 1, field.name()
                        .equals("_id") ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable);
            }
        }

        /**
         * 返回分页。
         */
        void page(JsonNode response) throws SQLException {
            if (response.path("_scroll_id").isTextual()) {
                scrollId = response.get("_scroll_id").textValue();
            }
            if (!response.path("timed_out").isBoolean() || response.path("timed_out").booleanValue()
                    || !response.path("_shards").path("failed").isIntegralNumber()
                    || response.path("_shards").path("failed").longValue() != 0) {
                throw new SQLException("Elasticsearch search did not complete on all shards");
            }
            hits = response.path("hits").path("hits");
            if (!hits.isArray() || hits.size() > pageSize || scrollId == null || scrollId.isBlank()) {
                throw new SQLException("Elasticsearch scroll response is incomplete");
            }
            for (JsonNode hit : hits) {
                ElasticsearchMapping.requireUnroutedHit(hit);
            }
            position = -1;
        }

        /**
         * 推进游标并返回下一条数据或后续读取状态。
         */
        boolean next() throws SQLException {
            if (closed) {
                throw new SQLException("Elasticsearch result is closed");
            }
            if (exhausted) {
                return false;
            }
            try {
                if (++position < hits.size()) {
                    row++;
                    return true;
                }
                if (hits.size() > 0) {
                    page(session.request("POST", "/_search/scroll", ElasticsearchNativeSession.json(
                            Map.of("scroll", "2m", "scroll_id", scrollId))).requireSuccess());
                    if (++position < hits.size()) {
                        row++;
                        return true;
                    }
                }
                exhausted = true;
                clear();
                return false;
            } catch (SQLException | RuntimeException failure) {
                closed = true;
                try {
                    clear();
                } catch (SQLException cleanup) {
                    failure.addSuppressed(new SQLException("Elasticsearch scroll cleanup failed"));
                }
                throw failure;
            }
        }

        /**
         * 清理当前远端滚动查询或本地缓存状态。
         */
        void clear() throws SQLException {
            if (scrollId == null) {
                return;
            }
            String id = scrollId;
            scrollId = null;
            JsonNode result = session.request("DELETE", "/_search/scroll",
                    ElasticsearchNativeSession.json(Map.of("scroll_id", List.of(id)))).requireSuccess();
            if (!result.path("succeeded").asBoolean(false)) {
                throw new SQLException("Elasticsearch scroll cleanup was not acknowledged");
            }
        }

        /**
         * 返回值。
         */
        Object value(Object selector) throws SQLException {
            if (closed || exhausted || position < 0 || position >= hits.size()) {
                throw new SQLException("No current Elasticsearch row");
            }
            int ordinal = selector instanceof Integer number ? number : fields.indexOf(selector) + 1;
            if (ordinal < 1 || ordinal > fields.size()) {
                throw new SQLException("Unknown Elasticsearch result column");
            }
            String name = fields.get(ordinal - 1);
            JsonNode hit = hits.get(position);
            JsonNode raw = name.equals("_id") ? hit.get("_id") : hit.path("_source").get(name);
            Object value = ElasticsearchMapping.value(mapping.get(name), raw);
            if (name.equals("_id") && value == null) {
                throw new SQLException("Elasticsearch hit has no document identity");
            }
            wasNull = value == null;
            return value;
        }

        /**
         * 返回结果。
         */
        StatementResultSet result() throws SQLException {
            statement = (Statement) Proxy.newProxyInstance(ElasticsearchScroll.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "close", "cancel" -> {
                                closed = true;
                                clear();
                                yield null;
                            }
                            case "isClosed" -> closed;
                            case "getConnection" -> connection;
                            case "getResultSet" -> rs;
                            case "getFetchSize" -> pageSize;
                            case "toString" -> "Elasticsearch scroll statement";
                            default -> throw new SQLFeatureNotSupportedException(
                                    "Elasticsearch scroll statement method is unavailable");
                        };
                    });
            rs = (ResultSet) Proxy.newProxyInstance(ElasticsearchScroll.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "next" -> next();
                            case "close" -> {
                                closed = true;
                                clear();
                                yield null;
                            }
                            case "isClosed" -> closed;
                            case "getObject" -> {
                                Object value = value(args[0]);
                                if (args.length == 2 && args[1] instanceof Class<?> type && value != null && !type.isInstance(
                                        value)) {
                                    throw new SQLException("Requested type is not the mapped Elasticsearch value type");
                                }
                                yield value;
                            }
                            case "getString" -> {
                                Object value = value(args[0]);
                                yield value == null ? null : value.toString();
                            }
                            case "getLong" -> {
                                Object value = value(args[0]);
                                yield value == null ? 0L : new java.math.BigDecimal(value.toString()).longValueExact();
                            }
                            case "getInt" -> {
                                Object value = value(args[0]);
                                yield value == null ? 0 : new java.math.BigDecimal(value.toString()).intValueExact();
                            }
                            case "getBigDecimal" -> {
                                Object value = value(args[0]);
                                yield value == null ? null : new java.math.BigDecimal(value.toString());
                            }
                            case "wasNull" -> wasNull;
                            case "findColumn" -> {
                                int ordinal = fields.indexOf(args[0]) + 1;
                                if (ordinal == 0) {
                                    throw new SQLException("Unknown result column");
                                }
                                yield ordinal;
                            }
                            case "getMetaData" -> metadata;
                            case "getStatement" -> statement;
                            case "getFetchSize" -> pageSize;
                            case "getRow" -> row;
                            case "getType" -> ResultSet.TYPE_FORWARD_ONLY;
                            case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;
                            case "toString" -> "Elasticsearch scroll result";
                            default -> throw new SQLFeatureNotSupportedException(
                                    "Elasticsearch result method is unavailable");
                        };
                    });
            var result = new StatementResultSet();
            result.setConnection(connection);
            result.setAutoCommit(connection.getAutoCommit());
            result.setStatement(statement);
            result.setResultset(rs);
            return result;
        }
    }
}
