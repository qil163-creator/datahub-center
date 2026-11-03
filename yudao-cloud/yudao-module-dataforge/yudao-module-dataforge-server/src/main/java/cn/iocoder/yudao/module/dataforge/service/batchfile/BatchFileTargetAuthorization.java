package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.springframework.stereotype.Component;

import java.util.*;

/** MySQL 目标计划校验适配；复用组件的模式、操作和有序参数检查。 */
@Component
public class BatchFileTargetAuthorization {

    public record Authorized(String sql, List<Integer> parameterOrder) {

        /**
         * 绑定已授权。
         *
         * @param source 来源数据
         * @return 绑定已授权结果集合
         */
        public List<Object> bind(List<Object> source) {
            if (source.size() != parameterOrder.size()) {
                throw new IllegalArgumentException("写参数数量不一致");
            }
            return parameterOrder.stream().map(source::get).toList();
        }
    }

    /**
     * 校验批次文件目标授权所需的访问权限。
     *
     * @param route 路由
     * @param actor 操作人编号
     * @param table 表
     * @param sql SQL
     * @param operation 操作
     * @param parameterCount 参数数量
     * @return 已授权
     */
    public Authorized authorize(AccessDatabaseResolver.ConnectionInfo route, Long actor, String table,
            String sql, String operation, int parameterCount) {
        if (actor == null || route.getConfigurationRevision() == null || route.getConfigurationRevision().isBlank()) {
            throw rejected();
        }
        var expected = ("DML".equals(operation)
                ? ManagedSchemaScriptPlan.parseInsertOnly(route.getProductType(), route.getSchema(), List.of(sql))
                : ManagedSchemaScriptPlan.parse(route.getProductType(), route.getSchema(), List.of(sql))).steps()
                .getFirst();
        if (!operation.equals(expected.operation()) || !expected.objectNames().equals(Set.of(table))
                || expected.parameterOrder().size() != parameterCount) {
            throw rejected();
        }
        // 实际执行仅消费组件规范化的 SQL 及参数顺序。
        return new Authorized(expected.sql(), List.copyOf(expected.parameterOrder()));
    }

    /**
     * 获取批次文件目标授权的已拒绝。
     *
     * @return 非法状态异常
     */
    private IllegalStateException rejected() {
        return new IllegalStateException("目标模式写计划不可核验");
    }
}
