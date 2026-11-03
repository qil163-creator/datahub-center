package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlAuthorization;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.module.governance.api.permission.dto.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.permission.SafetyTablePermissionDO;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyTablePermissionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.module.governance.service.permission.SafetyPermissionBindingResolver.*;

/** 负责显式写入策略；SQL 语法、标识符、投影及范围注入由 DB Switch 负责。 */
@Service
public class SafetyWritePermissionService {

    @Resource
    private SafetyPermissionBindingResolver bindingResolver;

    @Resource
    private SafetySqlPermissionService sqlPermissionService;

    @Resource
    private SafetyTablePermissionMapper tablePermissionMapper;

    /** 管理员预检不执行操作，只评估选定的持久化角色，绝不将该角色授予操作人员。 */
    public SafetyWritePermissionRespDTO preflight(Long databaseId, String sql, List<String> parameters, Long roleId) {
        if (roleId == null || roleId <= 0) {
            throw rejected("请选择核验角色");
        }
        return authorizeRoles(databaseId, sql, bindingResolver.route(databaseId).revision(), parameters,
                List.of(roleId));
    }

    /**
     * 校验授权角色。
     */
    SafetyWritePermissionRespDTO authorizeRoles(Long databaseId, String sql, String revision,
            List<String> parameters, List<Long> roles) {
        sqlPermissionService.requireBoundPolicies(databaseId);
        if (roles == null || roles.isEmpty() || roles.size() > 200
                || roles.stream().anyMatch(role -> role == null || role <= 0)) {
            throw rejected("写授权角色上下文无效");
        }
        var route = bindingResolver.route(databaseId);
        if (blank(revision) || !Objects.equals(revision, route.revision())) {
            throw rejected("消费方与治理的数据源配置修订不一致");
        }
        try {
            var shape = RestrictedDmlAuthorization.inspect(route.product(), sql, route.configuredSchema());
            String reference = RestrictedSelectGuard.qualifiedTableName(route.product(), shape.schemaName(),
                    shape.tableName());
            bindingResolver.table(databaseId, reference, shape.schemaName(), shape.tableName(), route);
            Set<String> physical = new LinkedHashSet<>(
                    bindingResolver.columns(databaseId, shape.schemaName(), shape.tableName(), route)
                            .stream().map(column -> column.getColumnName()).toList());
            List<SafetyTablePermissionDO> policies = tablePermissionMapper.selectListByTableRole(
                    databaseId, shape.schemaName(), shape.tableName(), roles);
            if (policies == null) {
                throw rejected("写策略查询没有结果契约");
            }
            List<RestrictedDmlAuthorization.Grant> grants = new ArrayList<>();
            for (var policy : policies) {
                if (policy == null || !Objects.equals(databaseId, policy.getDatabaseId())
                        || !Objects.equals(shape.schemaName(), policy.getSchemaName())
                        || !Objects.equals(shape.tableName(), policy.getTableName()) || !roles.contains(
                        policy.getRoleId())
                        || !Objects.equals(0, policy.getStatus()) || policy.getPermissionType() == null
                        || policy.getPermissionType() < 0 || policy.getPermissionType() > 2) {
                    throw rejected("写策略不属于当前精确对象或有效角色");
                }
                if (Objects.equals(policy.getPermissionType(), SafetyTablePermissionDO.TYPE_DENY)) {
                    throw rejected("存在明确禁止访问的角色策略");
                }
                if (!blank(policy.getWritePolicy())) {
                    grants.add(grant(parsePolicy(policy.getWritePolicy()), physical));
                }
            }
            var authorized = RestrictedDmlAuthorization.authorize(route.product(), sql, route.configuredSchema(),
                    shape.schemaName(), shape.tableName(), physical, parameters, grants);
            bindingResolver.assertCurrent(databaseId, route);
            SafetyWritePermissionRespDTO response = new SafetyWritePermissionRespDTO();
            response.setSql(authorized.sql());
            response.setOperation(shape.operation());
            response.setSchemaName(shape.schemaName());
            response.setTableName(shape.tableName());
            response.setConfigurationRevision(route.revision());
            response.setParameterCount(shape.parameterCount());
            return response;
        } catch (IllegalArgumentException ex) {
            throw rejected(ex.getMessage());
        }
    }

    /**
     * 解析写入权限的策略。
     */
    static SafetyWritePolicyDTO parsePolicy(String json) {
        try {
            if (json == null || json.length() > 100000) {
                throw new IllegalArgumentException();
            }
            return JsonUtils.parseObject(json, SafetyWritePolicyDTO.class);
        } catch (RuntimeException ex) {
            throw rejected("写策略 JSON 无效，必须重新核验配置");
        }
    }

    /**
     * 解析写入权限单条授权规则。
     */
    static RestrictedDmlAuthorization.Grant grant(SafetyWritePolicyDTO policy, Set<String> physical) {
        if (policy == null || policy.getOperations() == null || policy.getWriteColumns() == null
                || policy.getFilterColumns() == null || policy.getRowScope() == null || policy.getUnrestrictedRows() == null) {
            throw rejected("写策略缺少操作、列或明确行范围");
        }
        var grant = new RestrictedDmlAuthorization.Grant(unique(policy.getOperations()),
                unique(policy.getWriteColumns()),
                unique(policy.getFilterColumns()), policy.getRowScope(), policy.getUnrestrictedRows());
        try {
            RestrictedDmlAuthorization.validateGrant(grant, physical);
        } catch (IllegalArgumentException ex) {
            throw rejected(ex.getMessage());
        }
        return grant;
    }

    /**
     * 校验写入权限集合中不存在重复标识。
     */
    private static Set<String> unique(List<String> values) {
        if (values.size() > 2000 || values.stream().anyMatch(SafetyPermissionBindingResolver::blank)
                || new HashSet<>(values).size() != values.size()) {
            throw rejected("写策略列或操作有空值、重复或超限");
        }
        return new LinkedHashSet<>(values);
    }
}
