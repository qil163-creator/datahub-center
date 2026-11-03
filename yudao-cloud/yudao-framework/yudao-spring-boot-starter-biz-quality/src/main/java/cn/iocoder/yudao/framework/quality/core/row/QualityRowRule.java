package cn.iocoder.yudao.framework.quality.core.row;

import java.util.Map;

/** 与业务无关的输入。执行前先编译；编译后的计划冻结已验证值。 */
public record QualityRowRule(String code, String strategy, String field,
                             Map<String, Object> config, String severity) {

}
