package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlFieldRules;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.Map;

/** 字符串操作、字符串替换及字段赋值共享有界逐行执行生命周期。 */
public final class FieldRulesExecutor extends AbstractEtlNodeExecutor {
    public FieldRulesExecutor(EtlNodeDef node, EtlRunContext context) { super(node, context); }

    @Override
    protected void execute() throws Exception {
        var plan = EtlFieldRules.compile(node.getToolCode(), config());
        Map<String, Object> row;
        while ((row = readNextRow()) != null) pushRow(plan.apply(row, this::checkStop));
    }
}
