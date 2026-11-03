package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlBufferBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多输入笛卡尔积节点（toolCode: cartesian-product）。字段冲突时默认加上上游编号前缀。 */
public class CartesianProductExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "cartesian-product";

    /**
     * 初始化 {@code CartesianProductExecutor}及其父类状态。
     */
    public CartesianProductExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        Map<String, List<Map<String, Object>>> upstreams = readAllUpstreams();
        List<Map<String, Object>> products = new ArrayList<>();
        products.add(new LinkedHashMap<>());
        boolean prefix = config().getBool("prefixFields", true);
        int maxOutputRows = Math.max(config().getInt("maxOutputRows", 1_000_000), 1);
        EtlBufferBudget currentBudget = new EtlBufferBudget(context, maxOutputRows);
        try {
            for (Map.Entry<String, List<Map<String, Object>>> upstream : upstreams.entrySet()) {
                checkStop();
                List<Map<String, Object>> next = new ArrayList<>();
                EtlBufferBudget nextBudget = new EtlBufferBudget(context, maxOutputRows);
                boolean retained = false;
                try {
                    for (Map<String, Object> base : products) {
                        for (Map<String, Object> row : upstream.getValue()) {
                            checkStop();
                            Map<String, Object> merged = new LinkedHashMap<>(base);
                            row.forEach((field, value) -> merged.put(prefix
                                    ? StrUtil.format("{}.{}", upstream.getKey(), field) : field, value));
                            nextBudget.retainRow(merged);
                            next.add(merged);
                        }
                    }
                    products = next;
                    currentBudget.close();
                    currentBudget = nextBudget;
                    retained = true;
                } finally {
                    if (!retained) {
                        nextBudget.close();
                    }
                }
            }
            for (Map<String, Object> row : products) {
                checkStop();
                pushRow(row);
            }
            logLine(String.format("笛卡尔积完成，共输出 %s 行", writeCount.get()));
        } finally {
            currentBudget.close();
        }
    }
}
