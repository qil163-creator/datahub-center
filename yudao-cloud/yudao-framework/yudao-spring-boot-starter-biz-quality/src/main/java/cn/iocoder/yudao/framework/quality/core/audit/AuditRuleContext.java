package cn.iocoder.yudao.framework.quality.core.audit;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 稽查规则执行上下文：一条规则绑定到某数据库方言 + 表 + 字段后的完整描述
 *
 * @author Li
 */
@Data
public class AuditRuleContext {

    /** 从执行应用上下文借用的不可变产品注册信息。 */
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private final transient cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry products;

    /**
     * 校验构造参数并初始化 {@code AuditRuleContext}。
     */
    public AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry products) {
        this.products = java.util.Objects.requireNonNull(products, "products");
    }

    /**
     * 返回数据库产品集合。
     */
    public cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry products() {
        return products;
    }

    /**
     * 返回当前数据库。
     */
    public cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase database() {
        return products.metadata(productType);
    }

    /** 数据库产品类型（决定 SQL 方言），复用 dbswitch 的枚举 */
    private ProductTypeEnum productType = ProductTypeEnum.MYSQL;

    /** 表名 */
    private String tableName;

    /** 稽查字段（单字段规则）；多字段规则填 columns */
    private String column;

    /** 稽查字段列表（组合唯一、字段组完整性、时间顺序等多字段规则） */
    private List<String> columns;

    /** 附加过滤条件（拼接到 WHERE） */
    private String whereClause;

    /** 由后端构造的类型化谓词，追加到每条生成的计数或明细查询中。 */
    private List<ScopePredicate> scopePredicates = new ArrayList<>();

    /**
     * 添加文本相等性范围。
     */
    public void addTextEqualsScope(String column, String value) {
        scopePredicates.add(new ScopePredicate(column, ScopeOperator.EQUALS, ScopeValueType.TEXT, value));
    }

    /**
     * 添加文本输入范围。
     */
    public void addTextInScope(String column, List<String> values) {
        scopePredicates.add(new ScopePredicate(column, ScopeOperator.IN, ScopeValueType.TEXT, List.copyOf(values)));
    }

    public enum ScopeOperator {EQUALS, IN}

    public enum ScopeValueType {TEXT}

    public record ScopePredicate(String column, ScopeOperator operator, ScopeValueType valueType, Object value) {

        /**
         * 校验构造参数并初始化 {@code ScopePredicate}。
         */
        public ScopePredicate {
            if (column == null || column.isBlank() || operator == null || valueType == null || value == null) {
                throw new IllegalArgumentException("A complete typed scope predicate is required");
            }
            if (operator == ScopeOperator.EQUALS && !(value instanceof String)) {
                throw new IllegalArgumentException("Text equality scope requires a text value");
            }
            if (operator == ScopeOperator.IN && (!(value instanceof List<?> values)
                    || values.isEmpty() || values.stream().anyMatch(item -> !(item instanceof String)))) {
                throw new IllegalArgumentException("Text IN scope requires a non-empty list of text values");
            }
        }
    }

    /** 规则参数（各策略的配置项） */
    private Map<String, Object> config;

    /** 仅由可信元数据读取器注入，null 表示未读取；禁止从规则 JSON 构造。 */
    private List<ColumnSnapshot> columnSnapshots;

    public record ColumnSnapshot(String name, String type, Boolean nullable, Integer length,
                                 Integer precision, Integer scale, String comment) {

    }

    /**
     * 是否忽略空值：true 时 NULL 不计入错误
     */
    public boolean isIgnoreNullValue() {
        if (config == null) {
            return false;
        }
        Object value = config.get("ignoreNullValue");
        return value != null && StrUtil.equalsIgnoreCase(String.valueOf(value), "true");
    }

    /**
     * 获取参与稽查的全部字段（column + columns 合并去重）
     */
    public List<String> resolveColumns() {
        List<String> result = new ArrayList<>();
        if (StrUtil.isNotBlank(column)) {
            result.add(column.trim());
        }
        if (CollUtil.isNotEmpty(columns)) {
            for (String col : columns) {
                if (StrUtil.isNotBlank(col) && !result.contains(col.trim())) {
                    result.add(col.trim());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

}
