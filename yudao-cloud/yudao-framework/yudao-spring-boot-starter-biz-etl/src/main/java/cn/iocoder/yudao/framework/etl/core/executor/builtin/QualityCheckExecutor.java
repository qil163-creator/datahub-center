package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 行级质量检查：数据违规分流，规则执行异常保持技术失败语义。 */
public class QualityCheckExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "quality-check";

    /**
     * 初始化 {@code QualityCheckExecutor}及其父类状态。
     */
    public QualityCheckExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        EtlRowPolicyPort port = context.getRowPolicyPort();
        if (port == null) {
            throw new IllegalStateException("未配置受权质量计划");
        }
        String disposition = config().getStr("disposition", "ROUTE");
        String statusField = config().getStr("statusField", "_quality_status");
        if (!Set.of("ROUTE", "KEEP_TAGGED").contains(disposition) || statusField.isBlank()) {
            throw new IllegalArgumentException("无效的质量处置策略或状态字段");
        }
        long input = 0, passed = 0, warned = 0, rejected = 0, violations = 0;
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            if (row.containsKey(statusField)) {
                throw new IllegalArgumentException("质量状态字段已存在：" + statusField);
            }
            input++;
            var issues = port.evaluate(node.getNo(), row);
            if (issues == null) {
                throw new IllegalStateException("质量计划未返回检测结果");
            }
            for (var issue : issues) {
                if (!Set.of("INFO", "WARN", "ERROR").contains(issue.severity())) {
                    throw new IllegalStateException("质量计划返回无效严重级别");
                }
            }
            boolean failed = issues.stream().anyMatch(i -> "ERROR".equals(i.severity()));
            String status = failed ? "REJECT" : issues.isEmpty() ? "PASS" : "WARN";
            if (failed) {
                rejected++;
            } else if (issues.isEmpty()) {
                passed++;
            } else {
                warned++;
            }
            violations += issues.size();
            var origin = currentRowOrigin;
            if (origin != null && !context.getDag().getDataUpstreamList(node.getNo())
                    .equals(java.util.List.of(origin.sourceNode()))) {
                origin = null;
            }
            if (!issues.isEmpty()) {
                port.recordIssue(node.getNo(), input, new LinkedHashMap<>(row), issues, origin);
            }
            Map<String, Object> output = new LinkedHashMap<>(row);
            output.put(statusField, status);
            if (context.getRowObserver() != null) {
                context.getRowObserver().onQualityResult(node.getNo(), java.util.List.copyOf(issues));
            }
            pushRow(output, "KEEP_TAGGED".equals(disposition) ? "main" : status.toLowerCase(java.util.Locale.ROOT));
        }
        port.complete(node.getNo(), new EtlRowPolicyPort.Counts(input, passed, warned, rejected, violations));
    }
}
