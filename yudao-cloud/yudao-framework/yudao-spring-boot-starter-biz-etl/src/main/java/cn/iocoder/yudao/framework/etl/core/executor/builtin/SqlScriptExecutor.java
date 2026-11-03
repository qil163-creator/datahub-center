package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.List;

/**
 * SQL 脚本节点（toolCode: sql-script）
 *
 * 配置：databaseId 数据源；sql 多条语句以分号分隔，按顺序执行。
 * 仅控制执行顺序，不向下游传递数据（常用于写入后的加工/统计语句）
 *
 * @author Li
 */
public class SqlScriptExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "sql-script";

    /**
     * 初始化 {@code SqlScriptExecutor}及其父类状态。
     */
    public SqlScriptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() {
        Long databaseId = config().getLong("databaseId");
        if (databaseId == null) {
            throw new IllegalStateException(String.format("节点【%s】缺少配置：数据源", node.getName()));
        }
        String sqlText = requireConfig("sql", "SQL 语句");
        EtlConnectionInfo conn = context.getConnectionResolver().resolve(databaseId);
        logLine(String.format("连接数据源【%s】", conn.getName()));
        MetaDataByJdbcServiceImpl metaDataService = context.getDatabaseRuntime()
                .jdbcMetadata(conn.getProductType(), conn.getDriverProfileId());
        var preflight = context.getLimits() == null ? null : context.getLimits().getSqlScriptPlans();
        SqlScriptPlan plan;
        if (preflight != null) {
            var binding = preflight.get(node.getNo());
            if (binding == null || !databaseId.equals(binding.databaseId())
                    || binding.plan().product() != conn.getProductType()) {
                throw new IllegalStateException("SQL 脚本节点缺少匹配的预检计划");
            }
            plan = binding.plan();
        } else {
            plan = SqlScriptPlan.parse(conn.getProductType(), sqlText);
        }
        var result = metaDataService.executePlan(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                plan, List.of(), 0, 1, null, index -> {
                    checkStop();
                    logLine(String.format("执行第 %s/%s 条语句", index, plan.steps().size()));
                });
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException("SQL 脚本执行失败，已完成语句的外部写入需核查");
        }
        logLine(String.format("SQL 脚本执行完成，共 %s 条语句", plan.steps().size()));
    }

}
