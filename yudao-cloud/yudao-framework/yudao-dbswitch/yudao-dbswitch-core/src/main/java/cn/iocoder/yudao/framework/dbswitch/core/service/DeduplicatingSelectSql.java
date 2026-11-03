package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.*;

/** 已授权单表 SELECT 的结构化去重投影；不开放任意窗口表达式，也不访问数据库。 */
public final class DeduplicatingSelectSql {
    private DeduplicatingSelectSql() { }

    public record Order(String field, boolean descending) { }
    public record Options(boolean allFields, List<String> keyFields, Set<String> ignoreCaseFields,
                          List<Order> orderBy, List<String> uniqueFields) { }

    /** 字段与原生类型由调用方从已授权结果元数据提供；当前仅验证 PostgreSQL 方言。 */
    public static String render(ProductTypeEnum product, String sourceSql, String schema,
                                Map<String, String> fields, Options options) {
        if (product != ProductTypeEnum.POSTGRESQL) throw new IllegalArgumentException("当前数据库尚未验证原生去重查询");
        if (fields == null || fields.isEmpty() || fields.size() > 1024 || options == null)
            throw new IllegalArgumentException("去重字段合同缺失或超过预算");
        String base = RestrictedSelectGuard.parseBound(product, sourceSql, schema).getNormalizedSql();
        var names = new ArrayList<>(fields.keySet());
        var keys = options.allFields() ? names : checked(options.keyFields(), names, "去重键");
        var insensitive = options.ignoreCaseFields() == null ? Set.<String>of() : options.ignoreCaseFields();
        if (!keys.containsAll(insensitive) || insensitive.stream().anyMatch(name -> !text(fields.get(name))))
            throw new IllegalArgumentException("忽略大小写字段必须是字符串去重键");
        String projection = String.join(", ", names.stream().map(DeduplicatingSelectSql::quote).toList());
        String distinct = "SELECT DISTINCT " + projection + " FROM (" + base + ") dh_distinct_source";
        if (options.allFields() && insensitive.isEmpty()) return distinct;
        var unique = checked(options.uniqueFields(), names, "唯一排序键");
        if (options.orderBy() == null || options.orderBy().isEmpty() || options.orderBy().size() > 1024)
            throw new IllegalArgumentException("按键去重必须声明确定排序");
        var orderNames = new HashSet<String>(); var orderSql = new ArrayList<String>();
        for (var order : options.orderBy()) {
            if (order == null || !fields.containsKey(order.field()) || !orderNames.add(order.field()))
                throw new IllegalArgumentException("去重排序字段不存在或重复");
            orderSql.add(quote(order.field()) + (order.descending() ? " DESC NULLS FIRST" : " ASC NULLS LAST"));
        }
        if (!orderNames.containsAll(unique)) throw new IllegalArgumentException("去重排序必须包含全部唯一字段");
        String count = fresh(names, "dh_dedup_unique_count"), rank = fresh(names, "dh_dedup_rank");
        String countQuoted = quote(count), rankQuoted = quote(rank);
        String checked = "SELECT " + projection + " FROM (SELECT dh_distinct.*, COUNT(*) OVER (PARTITION BY "
                + String.join(", ", unique.stream().map(DeduplicatingSelectSql::quote).toList()) + ") AS " + countQuoted
                + " FROM (" + distinct + ") dh_distinct) dh_unique WHERE CASE WHEN " + countQuoted
                + " = 1 THEN TRUE ELSE CAST('DataHub ordering key is not unique: ' || CAST(" + countQuoted + " AS TEXT) AS BIGINT) = 0 END";
        String partitions = String.join(", ", keys.stream().map(name -> insensitive.contains(name)
                ? "LOWER(" + quote(name) + ")" : quote(name)).toList());
        return "SELECT " + projection + " FROM (SELECT dh_checked.*, ROW_NUMBER() OVER (PARTITION BY " + partitions
                + " ORDER BY " + String.join(", ", orderSql) + ") AS " + rankQuoted + " FROM (" + checked
                + ") dh_checked) dh_ranked WHERE " + rankQuoted + " = 1";
    }

    private static List<String> checked(List<String> values, List<String> fields, String label) {
        if (values == null || values.isEmpty() || values.size() > fields.size()
                || new HashSet<>(values).size() != values.size() || !fields.containsAll(values))
            throw new IllegalArgumentException(label + "不存在、为空或重复");
        return List.copyOf(values);
    }
    private static boolean text(String type) {
        return type != null && type.toUpperCase(Locale.ROOT).matches("(?:STRING|TEXT|VARCHAR|CHAR|CHARACTER VARYING|CHARACTER)(?:\\([0-9]+\\))?");
    }
    private static String fresh(List<String> fields, String name) { while (fields.contains(name)) name += "_"; return name; }
    private static String quote(String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, name); }
}
