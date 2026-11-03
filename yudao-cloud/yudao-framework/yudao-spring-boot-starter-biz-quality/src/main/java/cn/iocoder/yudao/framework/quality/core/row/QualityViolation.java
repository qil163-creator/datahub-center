package cn.iocoder.yudao.framework.quality.core.row;

/** 确定性的规则失败。刻意不包含原始值；调用方负责证据访问。 */
public record QualityViolation(String ruleCode, String strategy, String field, String severity,
                               String errorCode, String message) {

}
