package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** 共享原生创建、更新及删除协议，保留逐项确认和资源依据。 */
public final class ElasticsearchBulkExecutor {

    /**
     * 初始化 {@code ElasticsearchBulkExecutor}。
     */
    private ElasticsearchBulkExecutor() {
    }

    public enum Operation {
        CREATE("create"), UPDATE("update"), DELETE("delete"), UPSERT("index");

        private final String action;

        /**
         * 初始化 {@code Operation}。
         */
        Operation(String action) {
            this.action = action;
        }
    }

    /**
     * 按操作类型编码批量请求，并保留 Elasticsearch 逐项写入确认。
     */
    public static long execute(DataSource source, String tableName, List<String> fields,
            Map<String, ElasticsearchMapping.Field> mapping, List<Object[]> values, Operation operation,
            BatchLimits batchLimits, DbSwitchOptions runtimeOptions) {
        if (operation != Operation.CREATE && !fields.contains("_id")) {
            throw new IllegalArgumentException("Elasticsearch update/delete/upsert require document identity");
        }
        List<Object[]> rows = BatchValueConverter.convert(values, fields.size(), batchLimits);
        if (rows.isEmpty()) {
            return 0;
        }
        StringBuilder payload = new StringBuilder();
        Set<String> identities = new HashSet<>();
        List<String> expectedIds = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> document = new LinkedHashMap<>();
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("_index", tableName);
            String documentId = null;
            for (int i = 0; i < fields.size(); i++) {
                String name = fields.get(i);
                if (operation == Operation.DELETE && !name.equals("_id")) {
                    continue;
                }
                Object value = normalize(mapping.get(name), row[i]);
                if (name.equals("_id")) {
                    if (!(value instanceof String id) || id.isEmpty() || id.getBytes(
                            StandardCharsets.UTF_8).length > 512 || !identities.add(id)) {
                        throw new IllegalArgumentException(
                                "Elasticsearch batch requires distinct nonempty document identities");
                    }
                    identity.put("_id", id);
                    documentId = id;
                } else {
                    document.put(name, value);
                }
            }
            expectedIds.add(documentId);
            // INSERT 不得静默覆盖已有文档标识；UPSERT 才是显式幂等 index 操作。
            payload.append(ElasticsearchNativeSession.json(Map.of(operation.action, identity))).append('\n');
            if (operation != Operation.DELETE) {
                payload.append(ElasticsearchNativeSession.json(
                                operation == Operation.UPDATE ? Map.of("doc", document, "doc_as_upsert", false) : document))
                        .append('\n');
            }
        }
        String encoded = payload.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > batchLimits.maxBatchBytes()) {
            throw new IllegalArgumentException("Encoded Elasticsearch bulk exceeds its byte limit before submission");
        }
        boolean attempted = false;
        long acknowledgedRows = -1, rejectedRows = -1;
        try (Connection connection = source.getConnection()) {
            var session = ElasticsearchNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                    runtimeOptions.queryTimeoutSeconds());
            ElasticsearchMapping.requireUnroutedDocuments(session, tableName);
            attempted = true;
            JsonNode result = session.request("POST",
                            "/_bulk?refresh=wait_for&timeout=" + runtimeOptions.queryTimeoutSeconds() + "s", encoded)
                    .requireSuccess();
            JsonNode items = result.path("items");
            if (!result.path("errors").isBoolean() || !items.isArray() || items.size() != rows.size()) {
                throw new UnconfirmedWriteException();
            }
            long acknowledged = 0, rejected = 0;
            int ordinal = 0;
            boolean serverItemError = false;
            for (JsonNode item : items) {
                JsonNode receipt = item.path(operation.action);
                String expectedId = expectedIds.get(ordinal++);
                if (item.size() != 1 || !receipt.path("status").isIntegralNumber() || !receipt.path("status")
                        .canConvertToInt()
                        || !tableName.equals(receipt.path("_index").asText()) || !receipt.path("_id").isTextual()
                        || receipt.path("_id").asText().isEmpty()
                        || expectedId != null && !expectedId.equals(receipt.path("_id").textValue())) {
                    throw new UnconfirmedWriteException();
                }
                int status = receipt.get("status").intValue();
                serverItemError |= receipt.has("error");
                if (status >= 200 && status < 300 && !receipt.has("error")) {
                    acknowledged++;
                } else if (status >= 400 && status <= 599 && (receipt.has(
                        "error") || operation == Operation.DELETE && status == 404 && receipt.path("result").asText()
                        .equals("not_found"))) {
                    rejected++;
                } else {
                    throw new UnconfirmedWriteException();
                }
            }
            if (result.get("errors").booleanValue() != serverItemError) {
                throw new UnconfirmedWriteException();
            }
            acknowledgedRows = acknowledged;
            rejectedRows = rejected;
        } catch (SQLException | RuntimeException failure) {
            if (acknowledgedRows >= 0) {
                throw new PartialBatchWriteException(acknowledgedRows, rejectedRows, 0, false);
            }
            if (attempted) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("Elasticsearch batch was not submitted");
        }
        if (rejectedRows > 0) {
            throw new PartialBatchWriteException(acknowledgedRows, rejectedRows, 0, true);
        }
        return acknowledgedRows;
    }

    /**
     * 将字段整理为当前协议使用的规范形式。
     */
    private static Object normalize(ElasticsearchMapping.Field field, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return switch (field.jdbcType()) {
                case java.sql.Types.VARCHAR -> {
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
                case java.sql.Types.BOOLEAN -> {
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
                case java.sql.Types.INTEGER, java.sql.Types.BIGINT, java.sql.Types.NUMERIC -> {
                    BigInteger number;
                    if (value instanceof BigInteger big) {
                        number = big;
                    } else if (value instanceof BigDecimal decimal) {
                        number = decimal.toBigIntegerExact();
                    } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                        number = BigInteger.valueOf(((Number) value).longValue());
                    } else {
                        throw new IllegalArgumentException();
                    }
                    switch (field.type()) {
                        case "byte" -> number.byteValueExact();
                        case "short" -> number.shortValueExact();
                        case "integer" -> number.intValueExact();
                        case "long" -> number.longValueExact();
                        case "unsigned_long" -> {
                            if (number.signum() < 0 || number.bitLength() > 64) {
                                throw new IllegalArgumentException();
                            }
                        }
                        default -> throw new IllegalArgumentException();
                    }
                    yield number;
                }
                case java.sql.Types.DOUBLE -> {
                    if (!(value instanceof Double || value instanceof Float) || !Double.isFinite(
                            ((Number) value).doubleValue())) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Elasticsearch value requires an explicit lossless conversion for its target mapping");
        }
    }
}
