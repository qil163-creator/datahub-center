package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.*;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.*;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class SafetyPermissionTestFixture {
    final DatabaseApi database = mock(DatabaseApi.class);
    final SafetyTablePermissionMapper tables = mock(SafetyTablePermissionMapper.class);
    final SafetyFieldPermissionMapper fields = mock(SafetyFieldPermissionMapper.class);
    final SafetyRuleService rules = mock(SafetyRuleService.class);
    final SafetyPermissionBindingResolver resolver = new SafetyPermissionBindingResolver();
    final SafetySqlPermissionService service = new SafetySqlPermissionService();

    SafetyPermissionTestFixture() {
        this(ProductTypeEnum.POSTGRESQL, "public");
    }

    SafetyPermissionTestFixture(ProductTypeEnum product, String schema) {
        TenantContextHolder.clear();
        ReflectionTestUtils.setField(resolver, "databaseApi", database);
        ReflectionTestUtils.setField(service, "tablePermissionMapper", tables);
        ReflectionTestUtils.setField(service, "fieldPermissionMapper", fields);
        when(database.getDatabaseConnection(10L)).thenReturn(CommonResult.success(route(product, schema, "r1")));
        when(tables.countActiveUnbound(10L)).thenReturn(0L);
        when(fields.countActiveUnbound(10L)).thenReturn(0L);
        when(database.parseRestrictedSelect(any())).thenAnswer(call -> {
            DatabaseQueryReqDTO req = call.getArgument(0);
            var plan = RestrictedSelectGuard.parseBound(product, req.getSql(), schema);
            var result = new RestrictedSelectRespDTO();
            result.setSchemaName(plan.getSchemaName());
            result.setTableName(plan.getTableName());
            result.setNormalizedSql(plan.getNormalizedSql());
            result.setWildcard(plan.isWildcard());
            result.setSourceColumns(List.copyOf(plan.getSourceColumns()));
            return CommonResult.success(result);
        });
        when(database.resolveQueryTable(eq(10L), anyString())).thenAnswer(call -> {
            var plan = RestrictedSelectGuard.parseTableReference(product, call.getArgument(1), null);
            var dto = new DatabaseQueryTableRespDTO();
            dto.setSchemaName(plan.getSchemaName());
            dto.setTableName(plan.getTableName());
            dto.setTableReference(RestrictedSelectGuard.qualifiedTableName(product,
                    plan.getSchemaName(), plan.getTableName()));
            dto.setNormalizedSql(plan.getNormalizedSql());
            return CommonResult.success(dto);
        });
        when(database.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of(
                new DatabaseColumnRespDTO("id", "int8"), new DatabaseColumnRespDTO("mobile", "varchar"))));
    }

    static DatabaseConnectionRespDTO route(String revision) {
        return route(ProductTypeEnum.POSTGRESQL, "public", revision);
    }

    static DatabaseConnectionRespDTO route(ProductTypeEnum product, String schema, String revision) {
        var result = new DatabaseConnectionRespDTO();
        result.setId(10L);
        result.setProductType(product.name());
        result.setSchema(schema);
        result.setConfigurationRevision(revision);
        return result;
    }
}
