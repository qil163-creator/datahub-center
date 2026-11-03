package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;

import java.util.Map;

/** 编译后的边界在不改变值的前提下校验声明的行契约。定义不能独立运行。 */
public final class SubflowBoundaryExecutor extends AbstractEtlNodeExecutor {

    /**
     * 初始化 {@code SubflowBoundaryExecutor}及其父类状态。
     */
    public SubflowBoundaryExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        if (!"boundary-bridge".equals(node.getToolCode())) {
            throw new IllegalStateException("子流程定义需要由父流程绑定输入后执行");
        }
        var fields = config().getJSONArray("fields");
        var contracts = EtlFieldContract.parseFields(fields);
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            for (var contract : contracts) {
                contract.validate(row);
            }
            pushRow(row);
        }
    }
}
