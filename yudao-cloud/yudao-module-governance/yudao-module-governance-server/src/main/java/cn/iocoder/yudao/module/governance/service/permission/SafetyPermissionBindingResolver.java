package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.*;
import cn.iocoder.yudao.module.governance.api.permission.dto.SafetyTableBindingDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PERMISSION_SQL_PARSE_FAIL;

/**
 * 治理领域的精确对象适配。解析、引用生成与实时列元数据全部委托公开接口，
 * 不在业务侧猜测 schema、去引号或折叠大小写。
 */
@Component
public class SafetyPermissionBindingResolver {

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 读取数据安全权限绑定关联表信息。
     */
    public DatabaseQueryTableRespDTO table(Long databaseId, String reference, String schemaName, String tableName) {
        return table(databaseId, reference, schemaName, tableName, route(databaseId));
    }

    /**
     * 读取数据安全权限绑定关联表信息。
     */
    public DatabaseQueryTableRespDTO table(Long databaseId, String reference, String schemaName, String tableName,
            Route route) {
        requireDatabase(databaseId);
        if (blank(reference)) {
            throw rejected("必须选择精确表引用");
        }
        try {
            RestrictedSelectGuard.parseTableReference(route.product(), reference, null);
        } catch (IllegalArgumentException ex) {
            throw rejected("必须提供已限定 schema 的精确引用，不能回退裸表名");
        }
        DatabaseQueryTableRespDTO result = databaseApi.resolveQueryTable(databaseId, reference).getCheckedData();
        requireTable(result);
        expect(result.getSchemaName(), result.getTableName(), schemaName, tableName);
        assertCurrent(databaseId, route);
        return result;
    }

    /**
     * 解析数据安全权限绑定查询对应的精确表及路由。
     */
    public RestrictedSelectRespDTO query(Long databaseId, String sql, String schemaName, String tableName) {
        return query(databaseId, sql, schemaName, tableName, route(databaseId));
    }

    /**
     * 解析数据安全权限绑定查询对应的精确表及路由。
     */
    public RestrictedSelectRespDTO query(Long databaseId, String sql, String schemaName, String tableName,
            Route route) {
        requireDatabase(databaseId);
        DatabaseQueryReqDTO request = new DatabaseQueryReqDTO();
        request.setDatabaseId(databaseId);
        request.setSql(sql);
        RestrictedSelectRespDTO result = databaseApi.parseRestrictedSelect(request).getCheckedData();
        if (result == null || blank(result.getSchemaName()) || blank(result.getTableName())
                || blank(result.getNormalizedSql())) {
            throw rejected("精确查询解析未返回完整绑定");
        }
        expect(result.getSchemaName(), result.getTableName(), schemaName, tableName);
        assertCurrent(databaseId, route);
        return result;
    }

    /**
     * 读取数据安全权限绑定目标数据库产品类型。
     */
    public ProductTypeEnum product(Long databaseId) {
        return route(databaseId).product();
    }

    /**
     * 解析并核验精确数据源路由及配置修订。
     */
    public Route route(Long databaseId) {
        requireDatabase(databaseId);
        // 精简清单没有产品/修订字段；使用既有按 ID 契约。只保留无密路由快照，不记录返回 DTO。
        DatabaseConnectionRespDTO connection = databaseApi.getDatabaseConnection(databaseId).getCheckedData();
        if (connection == null || !Objects.equals(databaseId, connection.getId())
                || blank(connection.getProductType()) || blank(connection.getConfigurationRevision())
                || blank(connection.getSchema())) {
            throw rejected("数据源精确产品/schema/revision 不可用");
        }
        ProductTypeEnum product;
        try {
            product = ProductTypeEnum.valueOf(connection.getProductType());
        } catch (IllegalArgumentException ex) {
            throw rejected("数据源产品未开放精确治理权限");
        }
        if (!RestrictedSelectGuard.supportsExactBinding(product)) {
            throw rejected("该产品尚未开放精确治理权限");
        }
        return new Route(databaseId, product, connection.getSchema(), connection.getConfigurationRevision());
    }

    /**
     * 校验数据安全权限绑定的当前，不满足约束时拒绝当前操作。
     */
    public void assertCurrent(Long databaseId, Route expected) {
        if (expected == null || !Objects.equals(expected, route(databaseId))) {
            throw rejected("数据源产品、schema 或配置修订在核验期间变化，请重新加载");
        }
    }

    public record Route(Long databaseId, ProductTypeEnum product, String configuredSchema, String revision) {

    }

    /**
     * 读取数据安全权限绑定的字段定义。
     */
    public List<DatabaseColumnRespDTO> columns(Long databaseId, String schemaName, String tableName) {
        return columns(databaseId, schemaName, tableName, route(databaseId));
    }

    /**
     * 读取数据安全权限绑定的字段定义。
     */
    public List<DatabaseColumnRespDTO> columns(Long databaseId, String schemaName, String tableName, Route route) {
        if (blank(schemaName) || blank(tableName)) {
            throw rejected("物理模式和表名不能为空");
        }
        DatabaseQueryColumnsReqDTO request = new DatabaseQueryColumnsReqDTO();
        request.setDatabaseId(databaseId);
        request.setSchemaName(schemaName);
        request.setTableName(tableName);
        List<DatabaseColumnRespDTO> columns = databaseApi.getQueryTableColumns(request).getCheckedData();
        if (columns == null || columns.isEmpty() || columns.size() > 2000) {
            throw rejected("精确列元数据不可用或超过上限，不能回退历史元数据");
        }
        HashSet<String> names = new HashSet<>();
        for (DatabaseColumnRespDTO column : columns) {
            if (column == null || blank(column.getColumnName()) || !names.add(column.getColumnName())) {
                throw rejected("精确列元数据无效或重复");
            }
        }
        assertCurrent(databaseId, route);
        return columns;
    }

    /**
     * 核验精确数据源、模式和物理表绑定后返回目标表。
     */
    public VerifiedTable verifiedTable(Long databaseId, String reference, String schemaName, String tableName) {
        Route route = route(databaseId);
        DatabaseQueryTableRespDTO table = table(databaseId, reference, schemaName, tableName, route);
        List<DatabaseColumnRespDTO> columns = columns(databaseId, table.getSchemaName(), table.getTableName(), route);
        assertCurrent(databaseId, route);
        return new VerifiedTable(route, table, columns);
    }

    public record VerifiedTable(Route route, DatabaseQueryTableRespDTO table, List<DatabaseColumnRespDTO> columns) {

    }

    /**
     * 解析并核验数据安全权限绑定对象的精确绑定。
     */
    public SafetyTableBindingDTO binding(Long databaseId, String schemaName, String tableName) {
        SafetyTableBindingDTO binding = new SafetyTableBindingDTO();
        binding.setSchemaName(schemaName);
        binding.setTableName(tableName);
        binding.setTableReference(RestrictedSelectGuard.qualifiedTableName(product(databaseId), schemaName, tableName));
        return binding;
    }

    /**
     * 校验数据安全权限绑定的表，不满足约束时拒绝当前操作。
     */
    static void requireTable(DatabaseQueryTableRespDTO table) {
        if (table == null || blank(table.getSchemaName()) || blank(table.getTableName())
                || blank(table.getTableReference()) || blank(table.getNormalizedSql())) {
            throw rejected("精确表接口未返回完整绑定");
        }
    }

    /**
     * 核验数据安全权限绑定实际对象与请求预期一致。
     */
    static void expect(String actualSchema, String actualTable, String expectedSchema, String expectedTable) {
        if (expectedSchema == null && expectedTable == null) {
            return;
        }
        if (blank(expectedSchema) || blank(expectedTable)
                || !Objects.equals(actualSchema, expectedSchema) || !Objects.equals(actualTable, expectedTable)) {
            throw rejected("SQL/表引用与期望的物理模式和表不一致");
        }
    }

    /**
     * 校验数据安全权限绑定的数据库，不满足约束时拒绝当前操作。
     */
    static void requireDatabase(Long databaseId) {
        if (databaseId == null || databaseId <= 0) {
            throw rejected("数据源编号无效");
        }
    }

    /**
     * 判断输入是否为空或仅含空白。
     */
    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 构造数据安全权限绑定请求被拒绝时的业务异常。
     */
    static cn.iocoder.yudao.framework.common.exception.ServiceException rejected(String reason) {
        return exception(PERMISSION_SQL_PARSE_FAIL, reason);
    }
}
