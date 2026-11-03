package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.IntStream;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 保存与实际投递共用的薄授权适配；脚本语法、参数与 schema 机制全部委托 DB Switch。 */
@Component
public class DevScriptAuthorizationSupport {

    @Resource
    private ExecutionAccessService executionAccessService;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    record AuthorizedStep(String sql, boolean query) {

    }

    record AuthorizedScript(ProductTypeEnum product, String configurationRevision, List<AuthorizedStep> steps) {

        /**
         * 创建已授权脚本实例。
         *
         * @param product 产品
         * @param configurationRevision 连接配置修订号
         * @param steps 步骤
         */
        AuthorizedScript {
            steps = List.copyOf(steps);
        }
    }

    /**
     * 校验开发脚本授权支持所需的访问权限。
     *
     * @param task 任务记录
     * @return 已授权脚本
     */
    AuthorizedScript authorize(DevTaskDO task) {
        if (DevTaskDO.TYPE_SHELL.equals(task.getTaskType())) {
            return new AuthorizedScript(null, null, List.of());
        }
        if (task.getExecutionUserId() == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        executionAccessService.requireDatabase(task.getDatabaseId());
        var database = databaseMapper.selectById(task.getDatabaseId());
        if (database == null) {
            throw exception(DATABASE_NOT_EXISTS);
        }
        var route = databaseResolver.resolve(database);
        if (route == null || route.getProductType() == null || StrUtil.isBlank(route.getConfigurationRevision())
                || !route.getProductType().name().equals(task.getDbType())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "数据源产品或配置修订不可核验，请重新保存数据源绑定");
        }
        try {
            var config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
            List<String> input = statements(task, route.getProductType(), config);
            if (input.isEmpty()) {
                throw exception(DEV_TASK_CONTENT_EMPTY);
            }
            var bindings = input.stream().map(sql -> compile(route.getProductType(), sql, config)).toList();
            var compiled = bindings.stream().map(NamedSqlBindings::sql).toList();
            boolean schema = ManagedSchemaScriptPlan.supports(route.getProductType())
                    && ManagedSchemaScriptPlan.requiresSchemaAuthorization(route.getProductType(), compiled);
            var steps = schema ? authorizeSchema(task, route, bindings) : authorizeOrdinary(task, route, bindings,
                    config);
            steps.forEach(
                    step -> validateSchedulerBindings(NamedSqlBindings.compile(route.getProductType(), step.sql())));
            validateShape(config, steps, schema);
            return new AuthorizedScript(route.getProductType(), route.getConfigurationRevision(), steps);
        } catch (IllegalArgumentException | UnsupportedOperationException |
                 com.alibaba.druid.sql.parser.ParserException invalid) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "SQL 语法、绑定或执行类型未通过受管执行校验");
        }
    }

    /**
     * 授权结构。
     *
     * @param task 任务记录
     * @param route 路由
     * @param bindings 绑定
     * @return 授权结构结果集合
     */
    private List<AuthorizedStep> authorizeSchema(DevTaskDO task, AccessDatabaseResolver.ConnectionInfo route,
            List<NamedSqlBindings> bindings) {
        requireWrite(task);
        var authorized = ManagedSchemaScriptPlan.parse(route.getProductType(), route.getSchema(),
                bindings.stream().map(NamedSqlBindings::sql).toList());
        var result = new ArrayList<AuthorizedStep>();
        for (int i = 0; i < bindings.size(); i++) {
            var step = authorized.steps().get(i);
            if (step == null || StrUtil.isBlank(step.sql()) || step.operation() == null
                    || !Set.of("QUERY", "DML", "DDL", "PROCEDURE", "BLOCK", "CALL").contains(step.operation())) {
                throw rejected();
            }
            String sql = bindings.get(i)
                    .restoreOrdered(route.getProductType(), step.sql(), step.parameterOrder());
            boolean query = "QUERY".equals(step.operation());
            if (query) {
                sql = summarize(route.getProductType(), sql);
            }
            result.add(new AuthorizedStep(sql, query));
        }
        if ("PROCEDURE".equals(DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson()).getSqlType())
                && authorized.steps().stream()
                .noneMatch(step -> "PROCEDURE".equals(step.operation()) || "CALL".equals(step.operation()))) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "存储过程配置必须包含受管过程定义或经核验的调用");
        }
        return result;
    }

    /**
     * 授权普通。
     *
     * @param task 任务记录
     * @param route 路由
     * @param bindings 绑定
     * @param config 处理配置
     * @return 授权普通结果集合
     */
    private List<AuthorizedStep> authorizeOrdinary(DevTaskDO task, AccessDatabaseResolver.ConnectionInfo route,
            List<NamedSqlBindings> bindings, DevExecutionConfig config) {
        var result = new ArrayList<AuthorizedStep>();
        for (var binding : bindings) {
            var classification = SqlStatementClassifier.classify(route.getProductType(), binding.sql());
            if (classification.readOnlyQuery()) {
                String sql = RestrictedSelectGuard.parseBound(route.getProductType(), binding.sql(),
                        route.getSchema()).getNormalizedSql();
                result.add(new AuthorizedStep(
                        summarize(route.getProductType(), binding.restore(route.getProductType(), sql)), true));
            } else if (classification.kind() == SqlStatementClassifier.Kind.DML && classification.statementCount() == 1) {
                requireWrite(task);
                var shape = RestrictedDmlAuthorization.inspect(route.getProductType(), binding.sql(),
                        route.getSchema());
                if (!Objects.equals(route.getSchema(), shape.schemaName())
                        || shape.parameterCount() != binding.parameterNames().size()) {
                    throw rejected();
                }
                var plan = RestrictedDmlGuard.bindAuthorizedTable(
                        dbSwitchRuntime.products().metadata(route.getProductType()), binding.sql(),
                        route.getSchema(), shape.tableName());
                result.add(new AuthorizedStep(binding.restoreOrdered(route.getProductType(), plan.getNormalizedSql(),
                        IntStream.range(0, binding.parameterNames().size()).boxed().toList()), false));
            } else {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "该语句需要受管模式脚本授权或产品尚未验证");
            }
        }
        return result;
    }

    /**
     * 获取开发脚本授权支持的汇总。
     *
     * @param product 产品
     * @param authorizedNamedSql 已授权命名SQL
     * @return 开发脚本授权支持的汇总文本
     */
    private String summarize(ProductTypeEnum product, String authorizedNamedSql) {
        var canonical = NamedSqlBindings.compile(product, authorizedNamedSql);
        var summary = SqlExecutionSummary.countQuery(product, canonical.sql());
        return canonical.restoreOrdered(product, summary.sql(), summary.parameterOrder());
    }

    /**
     * 校验结构。
     *
     * @param config 处理配置
     * @param steps 步骤
     * @param schema 结构
     */
    private void validateShape(DevExecutionConfig config, List<AuthorizedStep> steps, boolean schema) {
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i).query()) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "SELECT 只能作为脚本最后一步，其他查询请建立独立任务");
            }
        }
        boolean finalQuery = steps.getLast().query();
        if ("QUERY".equals(config.getSqlType()) && (steps.size() != 1 || !finalQuery)
                || "NON_QUERY".equals(config.getSqlType()) && finalQuery
                || "PROCEDURE".equals(config.getSqlType()) && !schema) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "执行类型与已授权脚本不一致");
        }
    }

    /**
     * 获取开发脚本授权支持的编译。
     *
     * @param product 产品
     * @param sql SQL
     * @param config 处理配置
     * @return 命名SQL绑定
     */
    private NamedSqlBindings compile(ProductTypeEnum product, String sql, DevExecutionConfig config) {
        var binding = NamedSqlBindings.compile(product, sql);
        validateSchedulerBindings(binding);
        var names = config.getLocalParams().stream().map(DevExecutionConfig.LocalParam::getProp)
                .collect(java.util.stream.Collectors.toSet());
        if (!names.containsAll(binding.parameterNames())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "SQL 引用了未配置的自定义参数");
        }
        return binding;
    }

    /** DS 3.2.2 的列表参数扩展按问号切分；不让其误认字面值中的问号为绑定位置。 */
    private void validateSchedulerBindings(NamedSqlBindings binding) {
        if (!binding.parameterNames().isEmpty()
                && binding.sql().chars().filter(character -> character == '?').count() != binding.parameterNames()
                .size()) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID,
                    "调度器暂不支持自定义参数与字面值问号同时出现，请拆分任务或改写字面值");
        }
    }

    /**
     * 获取开发脚本授权支持的语句。
     *
     * @param task 任务记录
     * @param product 产品
     * @param config 处理配置
     * @return 开发脚本授权支持的语句结果集合
     */
    private List<String> statements(DevTaskDO task, ProductTypeEnum product, DevExecutionConfig config) {
        return SqlScriptPlan.splitStatements(product, task.getContent(), config.getStatementSeparator());
    }

    /**
     * 校验写入。
     *
     * @param task 任务记录
     */
    private void requireWrite(DevTaskDO task) {
        executionAccessService.requireUserPermission(task.getExecutionUserId(),
                ExecutionAccessService.PERMISSION_DEV_TASK_WRITE);
    }

    /**
     * 获取开发脚本授权支持的已拒绝。
     *
     * @return 服务异常
     */
    private cn.iocoder.yudao.framework.common.exception.ServiceException rejected() {
        return exception(DEV_TASK_DEPENDENCY_INVALID, "本地脚本执行计划无法核验");
    }
}
