package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;

import java.util.List;
import java.util.Map;

/** 规则参数中的主键声明；非法声明仍是“要求覆盖”，不能退化为未配置主键。 */
record QualityKeyDefinition(boolean configured, boolean valid, String column, List<String> columns) {

    /**
     * 解析质量检查配置中的行键定义。
     */
    static QualityKeyDefinition from(Map<?, ?> parameters) {
        Object single = parameters.get("pkColumn");
        boolean hasSingle = single != null && (!(single instanceof String text) || !text.isBlank());
        if (parameters.containsKey("pkColumns")) {
            Object multiple = parameters.get("pkColumns");
            if (hasSingle || !(multiple instanceof List<?> values) || !AuditKeySnapshot.validCompositeColumns(values)) {
                return new QualityKeyDefinition(true, false, null, null);
            }
            return new QualityKeyDefinition(true, true, null, values.stream().map(String.class::cast).toList());
        }
        if (!hasSingle) {
            return new QualityKeyDefinition(false, true, null, null);
        }
        if (!(single instanceof String text) || !text.trim().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return new QualityKeyDefinition(true, false, null, null);
        }
        return new QualityKeyDefinition(true, true, text.trim(), null);
    }

    /**
     * 判断质量检查是否使用多个字段组成复合行键。
     */
    boolean composite() {
        return columns != null;
    }

    /**
     * 比对质量规则及对象的行键定义是否一致。
     */
    boolean matches(QualityReadEvidence.KeyScope scope) {
        if (!configured || !valid || scope == null) {
            return false;
        }
        return composite()
                ? scope.getColumn() == null && columns.equals(scope.getColumns())
                && AuditKeySnapshot.isCompositeEncoding(scope.getAlgorithm())
                : column.equals(scope.getColumn()) && scope.getColumns() == null
                && !AuditKeySnapshot.isCompositeEncoding(scope.getAlgorithm());
    }
}
