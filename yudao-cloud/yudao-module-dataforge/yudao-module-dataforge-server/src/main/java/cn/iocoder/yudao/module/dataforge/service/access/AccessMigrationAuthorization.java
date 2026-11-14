package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 同步领域的授权适配；SQL 规划、参数顺序和实际搬数交由 DB Switch。 */
@Component
public class AccessMigrationAuthorization {

    @Resource
    private ExecutionAccessService executionAccess;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    /** 复核实际执行人的同步运行权限。 */
    public void requireTargetPermission(Long actor) {
        executionAccess.requireUserPermission(actor, "dataforge:access:run");
    }

    /** DB Switch 表线程不借用请求身份；复核持久执行人并在校验后恢复线程原上下文。 */
    public ManagedSchemaScriptPlan authorizeTable(AccessDatabaseResolver.ConnectionInfo source,
            AccessDatabaseResolver.ConnectionInfo target, Long actor, String sourceTable, String targetTable,
            List<String> columns, ManagedSchemaScriptPlan plan) {
        requireRoute(source, actor);
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var current = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        current.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(new cn.iocoder.yudao.framework.security.core.LoginUser().setId(actor)
                        .setUserType(cn.iocoder.yudao.framework.common.enums.UserTypeEnum.ADMIN.getValue()),
                        null, List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(current);
        try {
            requireTargetPermission(actor);
            requireSourceBinding(source, actor, sourceTable);
            return authorizeTarget(target, actor, targetTable, columns, plan);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    /** 仅检查源数据源与物理对象绑定，不执行行列权限或脱敏策略。 */
    public void requireSourceBinding(AccessDatabaseResolver.ConnectionInfo route, Long actor, String table) {
        requireRoute(route, actor);
        if (route.getProductType() != ProductTypeEnum.POSTGRESQL) throw rejected();
        requireCurrentRoute(route);
        RestrictedSelectGuard.qualifiedTableName(route.getProductType(), route.getSchema(), table);
    }

    /** 校验真实 CREATE 与参数化 INSERT 计划，实际执行仍由受限新表路径负责。 */
    public ManagedSchemaScriptPlan authorizeTarget(AccessDatabaseResolver.ConnectionInfo route, Long actor,
            String table, List<String> columns, ManagedSchemaScriptPlan plan) {
        requireRoute(route, actor);
        if (plan == null || plan.product() != route.getProductType()
                || !Objects.equals(plan.schemaName(), route.getSchema()) || plan.steps().size() != 2
                || columns == null || columns.isEmpty() || new LinkedHashSet<>(columns).size() != columns.size()) {
            throw rejected();
        }
        var expected = route.getProductType() == ProductTypeEnum.DORIS
                ? dbSwitchRuntime.products().metadata(route.getProductType()).planAuthorizedCreateNew(
                route.getSchema(), table, columns, plan.steps().getFirst().sql(), plan.steps().getLast().sql())
                : ManagedSchemaScriptPlan.parse(route.getProductType(), route.getSchema(),
                plan.steps().stream().map(ManagedSchemaScriptPlan.Step::sql).toList());
        if (!plan.equals(expected) || !"DDL".equals(expected.steps().getFirst().operation())
                || !"DML".equals(expected.steps().getLast().operation())
                || !expected.steps().getFirst().parameterOrder().isEmpty()
                || expected.steps().getLast().parameterOrder().size() != columns.size()
                || expected.steps().stream().anyMatch(step -> !step.objectNames().equals(Set.of(table)))) {
            throw rejected();
        }
        requireCurrentRoute(route);
        return plan;
    }

    private void requireRoute(AccessDatabaseResolver.ConnectionInfo route, Long actor) {
        if (route == null || actor == null || actor <= 0 || route.getDatabaseId() == null
                || route.getProductType() != ProductTypeEnum.POSTGRESQL && route.getProductType() != ProductTypeEnum.DORIS
                || route.getConfigurationRevision() == null || route.getConfigurationRevision().isBlank()) {
            throw rejected();
        }
    }

    private void requireCurrentRoute(AccessDatabaseResolver.ConnectionInfo route) {
        var current = databaseResolver.resolve(route.getDatabaseId(),
                cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NOT_EXISTS);
        if (!Objects.equals(route.getConfigurationRevision(), current.getConfigurationRevision())
                || route.getProductType() != current.getProductType()
                || !Objects.equals(route.getSchema(), current.getSchema())) {
            throw new IllegalStateException("同步数据源修订已变化，禁止沿用旧连接执行");
        }
    }

    private IllegalStateException rejected() {
        return new IllegalStateException("同步目标写计划不可核验；当前仅支持普通 PostgreSQL 源至 PostgreSQL/Doris 新表全量同步");
    }
}
