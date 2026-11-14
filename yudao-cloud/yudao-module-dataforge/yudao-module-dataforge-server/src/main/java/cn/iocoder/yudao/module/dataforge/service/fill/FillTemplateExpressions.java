package cn.iocoder.yudao.module.dataforge.service.fill;

import java.util.Collection;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_EXPRESSION_INVALID;

/**
 * 在线填报-模板表达式（公式、校验规则）的定义期校验
 *
 * @author Li
 */
public final class FillTemplateExpressions {

    private FillTemplateExpressions() {
    }

    /**
     * 编译表达式并检查引用的字段都存在；不合法时抛出带定位说明的业务异常。
     *
     * @param text 表达式
     * @param scope 使用范围
     * @param fieldCodes 本表全部字段码
     * @param label 提示中的对象名称，如“字段【金额】的公式”
     * @return 已编译表达式
     */
    public static FillExpression compile(String text, FillExpression.Scope scope, Collection<String> fieldCodes,
                                         String label) {
        FillExpression expression;
        try {
            expression = FillExpression.compile(text, scope);
        } catch (FillExpression.DefinitionException ex) {
            throw exception(FILL_TABLE_EXPRESSION_INVALID, label, ex.getMessage());
        }
        List<String> unknown = expression.fieldRefs().stream().filter(ref -> !fieldCodes.contains(ref)).toList();
        if (!unknown.isEmpty()) {
            throw exception(FILL_TABLE_EXPRESSION_INVALID, label, "引用了不存在的字段 " + String.join("、", unknown));
        }
        return expression;
    }

}
