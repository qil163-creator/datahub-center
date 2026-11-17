package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.framework.dbswitch.core.util.QueryTimeoutConnection;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.framework.security.PublishedQueryRpcProperties;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.PostgresDmlTargetGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlAuthorization;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.module.supply.api.dataapi.PublishedWriteClaimApi;
import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

@RestController
@Validated
@RequiredArgsConstructor
@EnableConfigurationProperties(PublishedQueryRpcProperties.class)
public class PublishedQueryApiImpl implements PublishedQueryApi {

    private final HttpServletRequest servletRequest;
    private final PublishedQueryRpcProperties properties;
    private final AccessDatabaseResolver databases;
    private final DbSwitchRuntime dbSwitchRuntime;
    private final PublishedWriteClaimApi writeClaims;
    @Value("${yudao.tenant.enable:true}")
    private boolean tenantEnabled;
    private final VerifiedServicePeerResolver peerResolver = new VerifiedServicePeerResolver();

    private void requireSingleTenantSupplyPeer() {
        if (tenantEnabled || peerResolver.resolve(servletRequest, properties.peers()).filter("supply"::equals).isEmpty()) {
            throw exception(UNAUTHORIZED);
        }
    }

    @Override
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Profile> writeProfile(Source source) {
        requireSingleTenantSupplyPeer();
        var conn = databases.resolveEnabled(source.databaseId(), DATABASE_NOT_EXISTS);
        if (conn.getProductType() != ProductTypeEnum.POSTGRESQL || conn.getSchema() == null
                || conn.getSchema().isBlank() || conn.getConfigurationRevision() == null || conn.getConfigurationRevision().isBlank()) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "写入需要明确的 PostgreSQL 模式和连接修订");
        }
        return CommonResult.success(new Profile(conn.getProductType().name(), conn.getSchema(), conn.getConfigurationRevision()));
    }

    @Override
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<List<Integer>> executeClaim(Ticket ticket) {
        requireSingleTenantSupplyPeer();
        var claim = writeClaims.redeem(new PublishedWriteClaimApi.Ticket(ticket.value())).getCheckedData();
        if (claim == null
                || claim.apiId() <= 0
                || claim.releaseId() <= 0
                || claim.appId() <= 0
                || claim.operationId() <= 0
                || claim.databaseId() <= 0
                || claim.revision() == null
                || claim.revision().isBlank()
                || claim.timeoutSeconds() < 1
                || claim.timeoutSeconds() > 300
                || claim.steps() == null
                || claim.steps().isEmpty()
                || claim.steps().size() > 1000
                || claim.bindings() == null
                || !Set.of("INSERT", "UPDATE", "DELETE", "MULTI").contains(claim.apiType())
                || !"MULTI".equals(claim.apiType()) && claim.steps().size() != 1) {
            throw exception(UNAUTHORIZED);
        }
        var conn = databases.resolveEnabled(claim.databaseId(), DATABASE_NOT_EXISTS);
        if (conn.getProductType() != ProductTypeEnum.POSTGRESQL
                || !Objects.equals(conn.getConfigurationRevision(), claim.revision())
                || conn.getSchema() == null
                || conn.getSchema().isBlank()) {
            throw exception(UNAUTHORIZED);
        }
        var sql = new ArrayList<String>();
        var targets = new ArrayList<RestrictedDmlTarget>();
        for (var step : claim.steps()) {
            if (step == null || !conn.getSchema().equals(step.schema())) {
                throw exception(UNAUTHORIZED);
            }
            var parsed = RestrictedDmlAuthorization.inspect(conn.getProductType(), step.sql(), conn.getSchema());
            if (!Objects.equals(parsed.schemaName(), step.schema())
                    || !Objects.equals(parsed.tableName(), step.table())
                    || !"MULTI".equals(claim.apiType())
                            && !claim.apiType().equals(parsed.operation())) {
                throw exception(UNAUTHORIZED);
            }
            var bound = RestrictedDmlGuard.bindAuthorizedTable(dbSwitchRuntime.products().metadata(conn.getProductType()),
                    step.sql(), step.schema(), step.table());
            sql.add(bound.getNormalizedSql());
            targets.add(new RestrictedDmlTarget(step.ordinal(), step.schema(), step.table()));
        }
        var plan = SqlScriptPlan.parse(conn.getProductType(), String.join(";\n", sql));
        PostgresDmlTargetGuard.validatePlan(plan, targets);
        if (plan.steps().stream().mapToInt(SqlScriptPlan.Step::parameterCount).sum() != claim.bindings().size()) {
            throw exception(UNAUTHORIZED);
        }
        var binds = claim.bindings().stream().map(PublishedWriteClaimApi.Bind::jdbcValue).toList();
        var result = dbSwitchRuntime.jdbcMetadata(conn.getProductType()).executeAuthorizedDmlPlan(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), plan, binds, targets, ignored -> { }, claim.timeoutSeconds());
        if (result == null
                || !Boolean.TRUE.equals(result.getSuccess())
                || result.getResults() == null
                || result.getResults().size() != targets.size()) {
            throw exception(DATABASE_QUERY_FAILED, "写入事务未确认");
        }
        return CommonResult.success(result.getResults().stream().map(JdbcSelectResult::getCount).toList());
    }

    @Override
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<PageResult<Map<String, Object>>> query(Query query) {
        requireSingleTenantSupplyPeer();
        var connection = databases.resolveEnabled(query.databaseId(), DATABASE_NOT_EXISTS);
        try {
            SqlStatementClassifier.assertReadOnlyQuery(connection.getProductType(), query.sql());
        } catch (UnsupportedOperationException | IllegalArgumentException invalid) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "仅允许支持的只读查询");
        }
        String sql = query.sql();
        if (query.projection() != null) {
            var scope = query.projection();
            try {
                sql = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.restrictProjection(
                        connection.getProductType(), sql, scope.schema(), scope.table(), scope.approvedColumns(),
                        Set.copyOf(scope.approvedColumns())).sql();
            } catch (UnsupportedOperationException | IllegalArgumentException invalid) {
                throw exception(DATABASE_PUBLISHED_SCOPE_INVALID);
            }
        }
        JdbcSelectResult result;
        try {
            result = dbSwitchRuntime.jdbcMetadata(connection.getProductType()).pageDataBySql(
                    connection.getJdbcUrl(), connection.getUsername(), connection.getPassword(),
                    sql, query.bindings().stream().map(Bind::jdbcValue).toList(),
                    query.page(), query.limit(), null, query.timeoutSeconds());
        } catch (RuntimeException failure) {
            if (QueryTimeoutConnection.isQueryTimeout(failure)) {
                throw exception(DATABASE_PUBLISHED_QUERY_TIMEOUT);
            }
            throw exception(DATABASE_QUERY_FAILED, "数据源查询未完成");
        }
        if (result == null || Boolean.FALSE.equals(result.getSuccess())) {
            throw exception(DATABASE_QUERY_FAILED, "数据源查询未完成");
        }
        List<Map<String, Object>> rows = result.getList() != null ? result.getList() : result.getRowData();
        if (rows != null) {
            rows =
                    rows.stream()
                            .map(
                                    row -> {
                                        if (row == null) {
                                            return null;
                                        }
                                        Map<String, Object> copy = new LinkedHashMap<>(row);
                                        copy.replaceAll(
                                                (key, value) ->
                                                        value instanceof BigDecimal n
                                                                ? n.toPlainString()
                                                                : value);
                                        return copy;
                                    })
                            .toList();
        }
        return CommonResult.success(new PageResult<>(rows, result.getTotal()));
    }
}
