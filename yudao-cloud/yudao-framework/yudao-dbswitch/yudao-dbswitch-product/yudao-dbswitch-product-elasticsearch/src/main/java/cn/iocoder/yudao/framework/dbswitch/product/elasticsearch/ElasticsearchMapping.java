package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 受支持平面标量传输契约的映射依据，包括文档标识。 */
public final class ElasticsearchMapping {

    /**
     * 初始化 {@code ElasticsearchMapping}。
     */
    private ElasticsearchMapping() {
    }

    public record Field(String name, String type, int jdbcType, Class<?> javaType) {

    }

    /**
     * 读取当前输入中的数据并按约定结构返回。
     */
    public static Map<String, Field> read(ElasticsearchNativeSession session, String index) throws SQLException {
        JsonNode response = session.request("GET", "/" + ElasticsearchNativeSession.index(index) + "/_mapping", null)
                .requireSuccess();
        if (response.size() != 1 || !response.has(index)) {
            throw new SQLException("Elasticsearch mapping requires one physical concrete index");
        }
        return parse(response.path(index).path("mappings"));
    }

    /** 精确解析同一次物理索引观测所包含的映射。 */
    static Map<String, Field> parse(JsonNode mapping) throws SQLException {
        if (!mapping.isObject() || mapping.path("_source").path("enabled").isBoolean()
                && !mapping.path("_source").path("enabled").booleanValue()) {
            throw new SQLException("Elasticsearch mapping requires enabled document source");
        }
        if (mapping.has("runtime")) {
            throw new SQLException("Runtime fields require an explicit transfer mapping");
        }
        if (mapping.path("_routing").path("required").asBoolean(false)) {
            throw new SQLException("Required document routing is not carried by the flat transfer frame");
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("_id", new Field("_id", "keyword", Types.VARCHAR, String.class));
        JsonNode properties = mapping.path("properties");
        if (!properties.isMissingNode() && !properties.isObject()) {
            throw new SQLException("Elasticsearch properties are invalid");
        }
        var iterator = properties.fields();
        while (iterator.hasNext()) {
            var item = iterator.next();
            String name = item.getKey();
            JsonNode definition = item.getValue();
            if (!name.matches("[A-Za-z][A-Za-z0-9_]*") || definition.has("properties")) {
                throw new SQLException(
                        "Nested or nonordinary Elasticsearch fields require an explicit transfer mapping");
            }
            String type = definition.path("type").asText();
            Field field = switch (type) {
                case "keyword", "text", "wildcard", "constant_keyword", "ip", "date", "date_nanos" ->
                        new Field(name, type, Types.VARCHAR, String.class);
                case "byte", "short", "integer" -> new Field(name, type, Types.INTEGER, Integer.class);
                case "long" -> new Field(name, type, Types.BIGINT, Long.class);
                case "unsigned_long" -> new Field(name, type, Types.NUMERIC, java.math.BigInteger.class);
                case "boolean" -> new Field(name, type, Types.BOOLEAN, Boolean.class);
                case "float", "half_float", "double" -> new Field(name, type, Types.DOUBLE, Double.class);
                default -> throw new SQLException("Elasticsearch field type needs an explicit transfer conversion");
            };
            fields.put(name, field);
        }
        return java.util.Collections.unmodifiableMap(fields);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static List<ColumnDescription> columns(Map<String, Field> fields) {
        List<ColumnDescription> columns = new ArrayList<>();
        fields.values().forEach(field -> {
            var column = new ColumnDescription();
            column.setFieldName(field.name());
            column.setLabelName(field.name());
            column.setFieldType(field.jdbcType());
            column.setFieldTypeName(field.type());
            column.setFiledTypeClassName(field.javaType().getName());
            column.setDbType(ProductTypeEnum.ELASTICSEARCH);
            column.setNullable(!field.name().equals("_id"));
            column.setPk(field.name().equals("_id"));
            column.setSigned(!field.type().equals("unsigned_long"));
            columns.add(column);
        });
        return columns;
    }

    /**
     * 返回值。
     */
    public static Object value(Field field, JsonNode value) throws SQLException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isContainerNode()) {
            throw new SQLException("Multivalued Elasticsearch field needs explicit conversion");
        }
        try {
            return switch (field.jdbcType()) {
                case Types.VARCHAR -> {
                    if (!value.isTextual()) {
                        throw new IllegalArgumentException();
                    }
                    yield value.textValue();
                }
                case Types.INTEGER -> {
                    if (!value.isIntegralNumber()) {
                        throw new IllegalArgumentException();
                    }
                    yield value.bigIntegerValue().intValueExact();
                }
                case Types.BIGINT -> {
                    if (!value.isIntegralNumber()) {
                        throw new IllegalArgumentException();
                    }
                    yield value.bigIntegerValue().longValueExact();
                }
                case Types.NUMERIC -> {
                    if (!value.isIntegralNumber()) {
                        throw new IllegalArgumentException();
                    }
                    var number = value.bigIntegerValue();
                    if (number.signum() < 0 || number.bitLength() > 64) {
                        throw new IllegalArgumentException();
                    }
                    yield number;
                }
                case Types.BOOLEAN -> {
                    if (!value.isBoolean()) {
                        throw new IllegalArgumentException();
                    }
                    yield value.booleanValue();
                }
                case Types.DOUBLE -> {
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                        throw new IllegalArgumentException();
                    }
                    yield value.doubleValue();
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new SQLException("Elasticsearch source value disagrees with its declared mapping");
        }
    }

    /**
     * 校验并取得无路由查询命中。
     */
    public static void requireUnroutedHit(JsonNode hit) throws SQLException {
        if (hit.has("_routing") || hit.path("fields").has("_routing")) {
            throw new SQLException("Custom document routing cannot be reduced to the _id transfer key");
        }
    }

    /**
     * 校验并取得无路由文档集合。
     */
    public static void requireUnroutedDocuments(ElasticsearchNativeSession session, String index) throws SQLException {
        // 写入口的可见性屏障；_count 是 NRT，单独返回 0 不能证明尚未 refresh 的路由文档不存在。
        JsonNode refreshed = session.request("POST", "/" + index + "/_refresh", null).requireSuccess().path("_shards");
        if (!refreshed.path("failed").isIntegralNumber() || refreshed.path("failed").longValue() != 0
                || !refreshed.path("successful").isIntegralNumber() || refreshed.path("successful").longValue() < 1) {
            throw new SQLException("Target refresh could not be confirmed before routing inspection");
        }
        if (!ElasticsearchStructureCatalog.routingNone(session, index)) {
            throw new SQLException("Custom routing exists or could not be checked before _id-only writes");
        }
    }
}
