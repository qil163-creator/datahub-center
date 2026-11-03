package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import java.util.Objects;

/**
 * 附加到映射上的单个原因。NOTE 不降低保真度（例如目标驱动会明确拒绝的值），
 * LOSS 将列标记为 LOSSY，BLOCKER 将其标记为 UNSUPPORTED。详情只描述类型，绝不包含数据值。
 */
public record TypeMappingIssue(Severity severity, String code, String detail) {

    public enum Severity {NOTE, LOSS, BLOCKER}

    /**
     * 校验构造参数并初始化 {@code TypeMappingIssue}。
     */
    public TypeMappingIssue {
        Objects.requireNonNull(severity);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,95}")) {
            throw new IllegalArgumentException("Invalid mapping reason code");
        }
        detail = detail == null ? "" : detail;
    }

    /**
     * 返回当前注释。
     */
    public static TypeMappingIssue note(String code, String detail) {
        return new TypeMappingIssue(Severity.NOTE, code, detail);
    }

    /**
     * 在当前类型映射结果中追加一项值域损失说明。
     */
    public static TypeMappingIssue loss(String code, String detail) {
        return new TypeMappingIssue(Severity.LOSS, code, detail);
    }

    /**
     * 根据当前输入构建并返回 {@code TypeMappingIssue}。
     */
    public static TypeMappingIssue blocker(String code, String detail) {
        return new TypeMappingIssue(Severity.BLOCKER, code, detail);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return severity + ":" + code + (detail.isEmpty() ? "" : "(" + detail + ")");
    }
}
