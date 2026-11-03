package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.AUDIT_RULE_CONFIG_INVALID;

/** 质量任务与标准关联规则共用的表单参数映射。 */
public final class QualityRuleConfigAdapter {

    private static final cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry STRATEGIES =
            new cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry();

    /**
     * 限制质量规则配置适配器工具类的直接实例化。
     */
    private QualityRuleConfigAdapter() {
    }

    /**
     * 按策略类型规范化质量规则配置并校验字段值。
     */
    public static Map<String, Object> normalize(String strategy, Map<String, Object> parameters) {
        Map<String, Object> config = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        if ("CHARACTER_VALIDATION".equals(strategy) && !Boolean.TRUE.equals(config.get("useRegex"))) {
            if (!(config.get("allowedTypes") instanceof List<?> values) || values.isEmpty()) {
                throw invalid("至少选择一种允许字符类型");
            }
            StringBuilder chars = new StringBuilder();
            for (Object value : values) {
                chars.append(switch (String.valueOf(value)) {
                    case "digit" -> "0-9";
                    case "letter" -> "A-Za-z";
                    case "space" -> " ";
                    case "special" -> "\\x21-\\x2F\\x3A-\\x40\\x5B-\\x60\\x7B-\\x7E";
                    default -> throw invalid("不支持的字符类型");
                });
            }
            config.put("regex", "^[" + chars + "]+$");
        }
        config.remove("allowedTypes");
        config.remove("useRegex");
        config.remove("referenceObjectKey");
        config.remove("codeSetId");
        config.remove("valueLabels");
        if ("DECIMAL_PRECISION_VALIDATION".equals(strategy)) {
            config.putIfAbsent("precision", 38);
        }
        if (config.containsKey("actualColumns") || config.containsKey("columnSnapshots")) {
            throw invalid("实际表结构仅允许由服务端读取");
        }
        var generator = STRATEGIES.getGenerator(strategy);
        return generator == null ? config : generator.normalizeConfig(config);
    }

    /** 将已授权的领域字段绑定到组件上下文，标识符统一委托 DB Switch。 */
    public static AuditRuleContext context(DbSwitchProductRegistry products, ProductTypeEnum product, String table,
            String strategy, List<String> columnNames, Map<String, Object> parameters) {
        AuditRuleContext context = new AuditRuleContext(products);
        context.setProductType(product);
        var relation = RestrictedSelectGuard.parseTableReference(product, table, "public");
        context.setTableName(relation.getSchemaName() == null
                ? RestrictedSelectGuard.quotePhysicalIdentifier(product, relation.getTableName())
                : RestrictedSelectGuard.qualifiedTableName(product, relation.getSchemaName(), relation.getTableName()));
        List<String> names = columnNames.stream()
                .map(value -> RestrictedSelectGuard.quotePhysicalIdentifier(product, value)).toList();
        context.setColumns(names);
        context.setColumn(names.isEmpty() ? null : names.getFirst());
        Map<String, Object> config = normalize(strategy, parameters);
        if (config.get("conditions") instanceof List<?> conditions) {
            config.put("conditions", conditions.stream().map(item -> {
                if (!(item instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("时间条件不合法");
                }
                String left = String.valueOf(values.get("leftField")), right = String.valueOf(values.get("rightField"));
                if (!columnNames.contains(left) || !columnNames.contains(right)) {
                    throw new IllegalArgumentException("时间字段不在检查字段内");
                }
                return Map.of("leftField", RestrictedSelectGuard.quotePhysicalIdentifier(product, left),
                        "rightField", RestrictedSelectGuard.quotePhysicalIdentifier(product, right), "operator",
                        String.valueOf(values.get("operator")));
            }).toList());
        }
        if (config.get("refColumn") != null) {
            config.put("refColumn",
                    RestrictedSelectGuard.quotePhysicalIdentifier(product, String.valueOf(config.get("refColumn"))));
        }
        for (String key : List.of("conditionColumn", "leftColumn")) {
            if (config.get(key) != null) {
                config.put(key, checkedColumn(product, columnNames, config.get(key)));
            }
        }
        if (config.get("requiredColumns") instanceof List<?> required) {
            config.put("requiredColumns",
                    required.stream().map(value -> checkedColumn(product, columnNames, value)).toList());
        }
        if (config.get("assertions") instanceof List<?> assertions) {
            config.put("assertions", assertions.stream().map(value -> {
                if (!(value instanceof Map<?, ?> entry)) {
                    throw new IllegalArgumentException("状态断言必须为对象");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                entry.forEach((key, item) -> result.put(String.valueOf(key), item));
                result.put("column", checkedColumn(product, columnNames, entry.get("column")));
                return result;
            }).toList());
        }
        if (config.get("comparisons") instanceof List<?> comparisons) {
            config.put("comparisons", comparisons.stream().map(value -> {
                if (!(value instanceof Map<?, ?> entry) || !(entry.get("rightColumn") instanceof String right)) {
                    throw new IllegalArgumentException("跨表比较必须配置两侧字段");
                }
                return Map.of("leftColumn", checkedColumn(product, columnNames, entry.get("leftColumn")),
                        "rightColumn", RestrictedSelectGuard.quotePhysicalIdentifier(product, right));
            }).toList());
        }
        if (config.get("refTable") != null) {
            var reference = RestrictedSelectGuard.parseTableReference(product, String.valueOf(config.get("refTable")),
                    relation.getSchemaName());
            config.put("refTable", reference.getSchemaName() == null
                    ? RestrictedSelectGuard.quotePhysicalIdentifier(product, reference.getTableName())
                    : RestrictedSelectGuard.qualifiedTableName(product, reference.getSchemaName(),
                    reference.getTableName()));
        }
        context.setConfig(config);
        return context;
    }

    /**
     * 校验并返回质量规则引用的字段标识。
     */
    private static String checkedColumn(ProductTypeEnum product, List<String> columnNames, Object value) {
        if (!(value instanceof String column) || !columnNames.contains(column)) {
            throw new IllegalArgumentException("条件或关联字段不在检查字段内");
        }
        return RestrictedSelectGuard.quotePhysicalIdentifier(product, column);
    }

    /**
     * 构造质量规则配置适配器输入校验失败时的业务异常。
     */
    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid(String reason) {
        return exception(AUDIT_RULE_CONFIG_INVALID, reason);
    }
}
