package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import java.util.ArrayList;
import java.util.List;

/** 单个映射方向的结果。保真度由问题决定，所以含损失的结果不能声称 EXACT。 */
public record TypeMappingResult<T>(T value, List<TypeMappingIssue> issues) {

    /**
     * 校验构造参数并初始化 {@code TypeMappingResult}。
     */
    public TypeMappingResult {
        issues = List.copyOf(issues);
        if (value == null && fidelityOf(issues) != TypeFidelity.UNSUPPORTED) {
            throw new IllegalArgumentException("A mapping without a value must be UNSUPPORTED");
        }
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    public static <T> TypeMappingResult<T> exact(T value) {
        return new TypeMappingResult<>(value, List.of());
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    public static <T> TypeMappingResult<T> unsupported(String code, String detail) {
        return new TypeMappingResult<>(null, List.of(TypeMappingIssue.blocker(code, detail)));
    }

    /**
     * 返回当前保真度。
     */
    public TypeFidelity fidelity() {
        return fidelityOf(issues);
    }

    /**
     * 返回当前带。
     */
    public TypeMappingResult<T> with(TypeMappingIssue issue) {
        var all = new ArrayList<>(issues);
        all.add(issue);
        return new TypeMappingResult<>(value, all);
    }

    /**
     * 在当前类型映射结果中追加一项值域损失说明。
     */
    public TypeMappingResult<T> loss(String code, String detail) {
        return with(TypeMappingIssue.loss(code, detail));
    }

    /**
     * 返回当前注释。
     */
    public TypeMappingResult<T> note(String code, String detail) {
        return with(TypeMappingIssue.note(code, detail));
    }

    /**
     * 读取当前映射对应的保真度。
     */
    static TypeFidelity fidelityOf(List<TypeMappingIssue> issues) {
        TypeFidelity result = TypeFidelity.EXACT;
        for (var issue : issues) {
            if (issue.severity() == TypeMappingIssue.Severity.BLOCKER) {
                return TypeFidelity.UNSUPPORTED;
            }
            if (issue.severity() == TypeMappingIssue.Severity.LOSS) {
                result = TypeFidelity.LOSSY;
            }
        }
        return result;
    }
}
