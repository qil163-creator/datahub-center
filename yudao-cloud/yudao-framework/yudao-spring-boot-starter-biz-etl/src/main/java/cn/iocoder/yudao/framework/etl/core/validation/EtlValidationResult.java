package cn.iocoder.yudao.framework.etl.core.validation;

import java.util.List;

public record EtlValidationResult(boolean valid, List<Issue> issues) {

    public record Issue(String nodeId, String field, String severity, String code, String message) {

    }

    /**
     * 根据给定参数创建 {@code EtlValidationResult} 实例。
     */
    public static EtlValidationResult of(List<Issue> issues) {
        return new EtlValidationResult(issues.stream().noneMatch(i -> "ERROR".equals(i.severity())),
                List.copyOf(issues));
    }
}
