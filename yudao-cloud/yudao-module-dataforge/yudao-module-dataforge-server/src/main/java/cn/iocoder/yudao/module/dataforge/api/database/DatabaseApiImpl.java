package cn.iocoder.yudao.module.dataforge.api.database;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectPlan;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableRespVO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class DatabaseApiImpl implements DatabaseApi {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DatabaseService databaseService;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.database.DatabaseDynamicMaskSupport dynamicMaskSupport;

    /**
     * 获得数据源精简列表（不含密码）。
     *
     * @param usageType 用途类型
     * @return 数据源精简列表（不含密码）结果集合
     */
    @Override
    public CommonResult<List<DatabaseConnectionRespDTO>> getDatabaseList(Integer usageType) {
        return success(convertList(databaseService.getSimpleDatabaseList(usageType), item -> {
            DatabaseConnectionRespDTO dto = new DatabaseConnectionRespDTO();
            dto.setId(item.getId());
            dto.setName(item.getName());
            dto.setDatabaseType(item.getDatabaseType());
            dto.setDatabaseTypeName(item.getDatabaseTypeName());
            dto.setCategoryCode(item.getCategoryCode());
            dto.setUsageType(item.getUsageType());
            dto.setIconPath(item.getIconPath());
            dto.setSchema(item.getDatabaseSchema());
            // productType 是 RPC 产品编码；可编辑的展示名称不能替代组件解析出的产品身份。
            dto.setProductType(item.getProductId());
            dto.setHost(item.getHost());
            return dto;
        }));
    }

    /**
     * 获得数据源连接信息（含解密密码）。
     *
     * @param id 记录编号
     * @return 数据源连接响应数据
     */
    @Override
    public CommonResult<DatabaseConnectionRespDTO> getDatabaseConnection(Long id) {
        AccessDatabaseResolver.ConnectionInfo info = databaseResolver.resolve(id, DATABASE_NOT_EXISTS);
        DatabaseConnectionRespDTO dto = new DatabaseConnectionRespDTO();
        dto.setId(info.getDatabaseId());
        dto.setName(info.getName());
        dto.setProductType(info.getProductType().name());
        dto.setJdbcUrl(info.getJdbcUrl());
        dto.setUsername(info.getUsername());
        dto.setPassword(info.getPassword());
        dto.setSchema(info.getSchema());
        dto.setConfigurationRevision(info.getConfigurationRevision());
        dto.setUsageType(info.getUsageType());
        return success(dto);
    }

    /**
     * 获得数据源下的表名列表。
     *
     * @param id 记录编号
     * @return 数据源下的表名列表结果集合
     */
    @Override
    public CommonResult<List<String>> getTableNameList(Long id) {
        return success(convertList(databaseService.getTableList(id), DatabaseTableRespVO::getTableName));
    }

    /**
     * 列出配置 schema 内的精确查询表身份（最多 2000）。
     *
     * @param id 记录编号
     * @return 配置 schema 内的精确查询表身份（最多 2000）结果集合
     */
    @Override
    public CommonResult<List<DatabaseQueryTableRespDTO>> getQueryTableList(Long id) {
        var info = databaseResolver.resolve(id, DATABASE_NOT_EXISTS);
        try {
            var tables = dbSwitchRuntime.jdbcMetadata(info.getProductType()).queryExactTableList(
                    info.getJdbcUrl(), info.getUsername(), info.getPassword(), info.getSchema(), 2000, 15_000);
            assertCatalogConnectionCurrent(info);
            return success(convertList(tables, table -> {
                var result = toQueryTable(info, table.getSchemaName(), table.getTableName());
                result.setRelationObjectId(table.getCatalogObjectId());
                return result;
            }));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw exception(DATABASE_QUERY_SQL_INVALID, ex.getMessage());
        } catch (RuntimeException ex) {
            throw exception(DATABASE_QUERY_FAILED, "精确表列表读取失败");
        }
    }

    /**
     * 解析单独的 table/schema.table 引用，不接受 SQL 片段。
     *
     * @param id 记录编号
     * @param tableReference 表引用
     * @return 数据源精确查询表响应数据
     */
    @Override
    public CommonResult<DatabaseQueryTableRespDTO> resolveQueryTable(Long id, String tableReference) {
        var info = databaseResolver.resolve(id, DATABASE_NOT_EXISTS);
        try {
            if (info.getProductType() == ProductTypeEnum.TIDB || info.getProductType() == ProductTypeEnum.DORIS) {
                var syntax = info.getProductType() == ProductTypeEnum.TIDB
                        ? RestrictedSelectGuard.parseCatalogRelationReference(tableReference, info.getSchema())
                        : RestrictedSelectGuard.parseRelationReference(info.getProductType(), tableReference, info.getSchema());
                var relation = catalogRelation(info, syntax.getSchemaName(), syntax.getTableName());
                var result = toQueryTable(info, relation.schemaName(), relation.tableName());
                result.setRelationObjectId(relation.objectId());
                return success(result);
            }
            var plan = RestrictedSelectGuard.parseTableReference(info.getProductType(), tableReference,
                    info.getSchema());
            return success(toQueryTable(info, plan.getSchemaName(), plan.getTableName()));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw exception(DATABASE_QUERY_SQL_INVALID, ex.getMessage());
        }
    }

    /**
     * 按物理 schema/table 和列投影读取零行元数据（不回退默认 schema）。
     *
     * @param request 请求参数
     * @return 按物理 schema/table 和列投影读取零行元数据（不回退默认 schema）结果集合
     */
    @Override
    public CommonResult<List<DatabaseColumnRespDTO>> getQueryTableColumns(DatabaseQueryColumnsReqDTO request) {
        var info = databaseResolver.resolve(request.getDatabaseId(), DATABASE_NOT_EXISTS);
        try {
            var metadata = dbSwitchRuntime.jdbcMetadata(info.getProductType());
            var columns = request.getExpectedObjectId() == null
                    ? metadata.queryExactTableColumns(info.getJdbcUrl(), info.getUsername(), info.getPassword(),
                    request.getSchemaName(), request.getTableName(), request.getColumns(), 15_000)
                    : metadata.queryExactTableColumns(info.getJdbcUrl(), info.getUsername(), info.getPassword(),
                    new CatalogRelationIdentity(info.getProductType(), request.getSchemaName(),
                            request.getTableName(), request.getExpectedObjectId()), request.getColumns(), 15_000);
            assertCatalogConnectionCurrent(info);
            return success(convertList(columns,
                    column -> new DatabaseColumnRespDTO(column.getFieldName(), column.getFieldTypeName())));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw exception(DATABASE_QUERY_SQL_INVALID, ex.getMessage());
        } catch (RuntimeException ex) {
            throw exception(DATABASE_QUERY_FAILED, "精确列元数据读取失败");
        }
    }

    /**
     * 转换为精确查询表。
     *
     * @param info 信息
     * @param schema 结构
     * @param table 表
     * @return 数据源精确查询表响应数据
     */
    private DatabaseQueryTableRespDTO toQueryTable(AccessDatabaseResolver.ConnectionInfo info, String schema,
            String table) {
        var result = new DatabaseQueryTableRespDTO();
        result.setSchemaName(schema);
        result.setTableName(table);
        result.setTableReference(RestrictedSelectGuard.qualifiedTableName(info.getProductType(), schema, table));
        result.setNormalizedSql("SELECT * FROM " + result.getTableReference());
        return result;
    }

    /**
     * 只读预览数据源表样例数据（最多 100 行）。
     *
     * @param reqDTO 请求参数
     * @return 数据源预览响应数据
     */
    @Override
    public CommonResult<DatabasePreviewRespDTO> previewTable(DatabasePreviewReqDTO reqDTO) {
        AccessDatabaseResolver.ConnectionInfo info =
                databaseResolver.resolve(reqDTO.getDatabaseId(), DATABASE_NOT_EXISTS);
        boolean tableExists = info.getProductType() == ProductTypeEnum.DORIS
                || databaseService.getTableList(reqDTO.getDatabaseId()).stream()
                .map(DatabaseTableRespVO::getTableName)
                .filter(java.util.Objects::nonNull)
                .anyMatch(name -> name.equalsIgnoreCase(reqDTO.getTableName()));
        if (!tableExists) {
            throw exception(DATABASE_TABLE_NOT_EXISTS);
        }
        int limit = Math.max(1, Math.min(reqDTO.getLimit() == null ? 20 : reqDTO.getLimit(), 100));
        String schemaName = cn.hutool.core.util.StrUtil.blankToDefault(reqDTO.getSchemaName(), info.getSchema());
        DatabasePreviewRespDTO preview;
        try {
            try (Connection connection = dbSwitchRuntime.openConnection(info.getProductType(), null,
                    preparedQueryJdbcUrl(info), info.getUsername(), info.getPassword());
                 var deadline = info.getProductType() == ProductTypeEnum.DORIS
                         ? new JdbcReadDeadline(connection, 30_000) : null) {
                try {
                    connection.setReadOnly(true);
                } catch (Exception ignored) {
                    // 部分数据库驱动不支持切换只读模式，固定 SELECT 仍限制写操作
                }
                CatalogRelationIdentity relation = info.getProductType() == ProductTypeEnum.DORIS
                        ? resolveDorisRelation(connection, info, schemaName, reqDTO.getTableName(), null) : null;
                DatabaseMetaData databaseMetaData = connection.getMetaData();
                String qualifiedTable = quoteIdentifier(databaseMetaData, reqDTO.getTableName());
                if (cn.hutool.core.util.StrUtil.isNotBlank(schemaName)) {
                    qualifiedTable = quoteIdentifier(databaseMetaData, schemaName) + "." + qualifiedTable;
                }
                Map<String, Integer> columnTypes = new LinkedHashMap<>();
                Map<String, String> columnNames = new LinkedHashMap<>();
                loadPreviewColumns(connection, qualifiedTable, columnTypes, columnNames);
                String sql = buildPreviewSelectSql(databaseMetaData, qualifiedTable,
                        reqDTO.getColumns(), columnNames);
                List<Object> params = new ArrayList<>();
                if (cn.hutool.core.collection.CollUtil.isNotEmpty(reqDTO.getFilters())) {
                    sql += buildWhereClause(databaseMetaData, columnTypes, columnNames,
                            reqDTO.getFilters(), params);
                }
                preview = executePreview(connection, sql, params, limit);
                if (relation != null) {
                    assertDorisRelationCurrent(connection, info, relation);
                    deadline.check();
                }
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATABASE_PREVIEW_FAILED,
                    cn.hutool.core.util.StrUtil.maxLength(ex.getMessage(), 300));
        }
        // 连接关闭后再调用 governance 动态脱敏，按调用者身份处理；不可用时失败关闭。
        return success(dynamicMaskSupport.maskTable(reqDTO.getDatabaseId(), schemaName, reqDTO.getTableName(),
                reqDTO.getMaskScene(), preview, DATABASE_PREVIEW_FAILED));
    }

    /**
     * 获得数据源下指定表的字段列表。
     *
     * @param id 记录编号
     * @param tableName 表名
     * @return 数据源下指定表的字段列表结果集合
     */
    @Override
    public CommonResult<List<DatabaseColumnRespDTO>> getTableColumnList(Long id, String tableName) {
        AccessDatabaseResolver.ConnectionInfo info = databaseResolver.resolve(id, DATABASE_NOT_EXISTS);
        if (info.getProductType() == ProductTypeEnum.DORIS) {
            // 兼容调用方也通过原生目录读取，避免 MySQL 协议的 catalog/schema 差异及临时遮蔽。
            var request = new DatabaseQueryColumnsReqDTO();
            request.setDatabaseId(id);
            request.setSchemaName(info.getSchema());
            request.setTableName(tableName);
            var result = getQueryTableColumns(request);
            assertCatalogConnectionCurrent(info);
            return result;
        }
        boolean tableExists = databaseService.getTableList(id).stream()
                .map(DatabaseTableRespVO::getTableName)
                .filter(java.util.Objects::nonNull)
                .anyMatch(name -> name.equalsIgnoreCase(tableName));
        if (!tableExists) {
            throw exception(DATABASE_TABLE_NOT_EXISTS);
        }
        try {
            try (Connection connection = dbSwitchRuntime.openConnection(info.getProductType(), null,
                    info.getJdbcUrl(), info.getUsername(), info.getPassword())) {
                DatabaseMetaData databaseMetaData = connection.getMetaData();
                String qualifiedTable = quoteIdentifier(databaseMetaData, tableName);
                if (cn.hutool.core.util.StrUtil.isNotBlank(info.getSchema())) {
                    qualifiedTable = quoteIdentifier(databaseMetaData, info.getSchema()) + "." + qualifiedTable;
                }
                List<DatabaseColumnRespDTO> columns = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM " + qualifiedTable + " WHERE 1 = 0");
                     ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        columns.add(new DatabaseColumnRespDTO(
                                metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
                    }
                }
                return success(columns);
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATABASE_QUERY_FAILED,
                    cn.hutool.core.util.StrUtil.maxLength(ex.getMessage(), 300));
        }
    }

    /**
     * 解析并固定受限单表 SELECT 的物理 schema/table。
     *
     * @param reqDTO 请求参数
     * @return 受限 SELECT响应数据
     */
    @Override
    public CommonResult<RestrictedSelectRespDTO> parseRestrictedSelect(DatabaseQueryReqDTO reqDTO) {
        AccessDatabaseResolver.ConnectionInfo info =
                databaseResolver.resolve(reqDTO.getDatabaseId(), DATABASE_NOT_EXISTS);
        try {
            if (info.getProductType() == ProductTypeEnum.TIDB || info.getProductType() == ProductTypeEnum.DORIS) {
                var syntax = RestrictedSelectGuard.parse(info.getProductType(), reqDTO.getSql());
                String schema = syntax.getSchemaName() == null ? info.getSchema() : syntax.getSchemaName();
                var relation = catalogRelation(info, schema, syntax.getTableName());
                var result = toRestrictedSelect(
                        RestrictedSelectGuard.bindToCatalogRelation(reqDTO.getSql(), schema, relation));
                result.setRelationObjectId(relation.objectId());
                return success(result);
            }
            return success(toRestrictedSelect(RestrictedSelectGuard.parseBound(
                    info.getProductType(), reqDTO.getSql(), info.getSchema())));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw exception(DATABASE_QUERY_SQL_INVALID, ex.getMessage());
        }
    }

    /**
     * 只读执行单条 SELECT 查询（服务端只读校验 + 最大行数限制）。
     *
     * @param reqDTO 请求参数
     * @return 数据源预览响应数据
     */
    @Override
    public CommonResult<DatabasePreviewRespDTO> executeQuery(DatabaseQueryReqDTO reqDTO) {
        AccessDatabaseResolver.ConnectionInfo info =
                databaseResolver.resolve(reqDTO.getDatabaseId(), DATABASE_NOT_EXISTS);
        String sql = restrictToAllowedColumns(info, reqDTO);
        if (info.getProductType() == ProductTypeEnum.DORIS
                && (reqDTO.getExpectedObjectId() == null
                || !reqDTO.getExpectedObjectId().matches("[0-9a-f]{64}"))) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "Doris 查询必须携带解析阶段的原生关系身份");
        }
        int limit = Math.max(1, Math.min(reqDTO.getLimit() == null ? 100 : reqDTO.getLimit(), 1000));
        DatabasePreviewRespDTO result;
        try {
            try (Connection connection = dbSwitchRuntime.openConnection(info.getProductType(), null,
                    preparedQueryJdbcUrl(info), info.getUsername(), info.getPassword());
                 var deadline = info.getProductType() == ProductTypeEnum.DORIS
                         ? new JdbcReadDeadline(connection, 30_000) : null) {
                try {
                    connection.setReadOnly(true);
                } catch (Exception ignored) {
                    // 部分驱动不支持只读模式，SQL 校验仍保证只执行 SELECT
                }
                CatalogRelationIdentity relation = null;
                if (info.getProductType() == ProductTypeEnum.DORIS) {
                    var syntax = parseUserSelect(info, sql);
                    String schema = StrUtil.blankToDefault(syntax.getSchemaName(), info.getSchema());
                    relation = resolveDorisRelation(connection, info, schema, syntax.getTableName(),
                            reqDTO.getExpectedObjectId());
                    sql = RestrictedSelectGuard.bindToCatalogRelation(sql, schema, relation).getNormalizedSql();
                }
                result = executePreview(connection, sql, List.of(), limit);
                if (relation != null) {
                    assertDorisRelationCurrent(connection, info, relation);
                    deadline.check();
                }
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATABASE_QUERY_FAILED,
                    cn.hutool.core.util.StrUtil.maxLength(ex.getMessage(), 300));
        }
        // 按实际执行的 SQL 追溯输出列来源后脱敏；连接已关闭。
        return success(dynamicMaskSupport.maskQuery(reqDTO.getDatabaseId(), info.getProductType(), info.getSchema(),
                sql, reqDTO.getExpectedSchema(), reqDTO.getExpectedTable(), reqDTO.getMaskScene(), result,
                DATABASE_QUERY_FAILED));
    }

    /** 复用产品参数传输契约；Doris 在不同 sql_mode 下仍须使用服务端绑定。 */
    private String preparedQueryJdbcUrl(AccessDatabaseResolver.ConnectionInfo info) {
        return info.getProductType() == ProductTypeEnum.DORIS
                ? dbSwitchRuntime.products().metadata(info.getProductType()).preparePageQueryJdbcUrl(info.getJdbcUrl())
                : info.getJdbcUrl();
    }

    /** 执行连接本身必须仍指向解析阶段确认的 internal 永久 OLAP 关系。 */
    private CatalogRelationIdentity resolveDorisRelation(Connection connection,
            AccessDatabaseResolver.ConnectionInfo info, String schema, String table, String expectedObjectId)
            throws java.sql.SQLException {
        assertCatalogConnectionCurrent(info);
        var relation = dbSwitchRuntime.products().metadata(ProductTypeEnum.DORIS)
                .resolveCatalogRelation(connection, schema, table, 5);
        if (relation == null || relation.product() != ProductTypeEnum.DORIS
                || !java.util.Objects.equals(schema, relation.schemaName())
                || !java.util.Objects.equals(table, relation.tableName())
                || !relation.objectId().matches("[0-9a-f]{64}")
                || expectedObjectId != null && !expectedObjectId.equals(relation.objectId())) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "Doris 原生关系身份变化或超出已验证范围");
        }
        return relation;
    }

    /** 数据先保存在本地，确认对象代次与数据源配置未变化后才允许交给调用方。 */
    private void assertDorisRelationCurrent(Connection connection,
            AccessDatabaseResolver.ConnectionInfo info, CatalogRelationIdentity expected) throws java.sql.SQLException {
        resolveDorisRelation(connection, info, expected.schemaName(), expected.tableName(), expected.objectId());
        assertCatalogConnectionCurrent(info);
    }

    /**
     * 获取数据源的目录关系。
     *
     * @param info 信息
     * @param schema 结构
     * @param table 表
     * @return 目录关系身份
     */
    private CatalogRelationIdentity catalogRelation(AccessDatabaseResolver.ConnectionInfo info, String schema,
            String table) {
        assertCatalogConnectionCurrent(info);
        try {
            var relation = dbSwitchRuntime.jdbcMetadata(info.getProductType()).resolveCatalogRelation(
                    info.getJdbcUrl(), info.getUsername(), info.getPassword(), schema, table, 15_000);
            assertCatalogConnectionCurrent(info);
            return relation;
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException failure) {
            throw failure;
        } catch (IllegalArgumentException | UnsupportedOperationException invalid) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "原生 catalog 关系超出已验证范围");
        } catch (RuntimeException unavailable) {
            throw exception(DATABASE_QUERY_FAILED, "原生 catalog 关系身份读取失败");
        }
    }

    /**
     * 确认任务实例数据库连接当前。
     *
     * @param info 信息
     */
    private void assertCatalogConnectionCurrent(AccessDatabaseResolver.ConnectionInfo info) {
        if (info.getProductType() != ProductTypeEnum.TIDB && info.getProductType() != ProductTypeEnum.DORIS) {
            return;
        }
        var current = databaseResolver.resolve(info.getDatabaseId(), DATABASE_NOT_EXISTS);
        if (cn.hutool.core.util.StrUtil.isBlank(info.getConfigurationRevision())
                || current == null || !java.util.Objects.equals(info.getDatabaseId(), current.getDatabaseId())
                || info.getProductType() != current.getProductType()
                || !java.util.Objects.equals(info.getSchema(), current.getSchema())
                || !java.util.Objects.equals(info.getConfigurationRevision(), current.getConfigurationRevision())) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "数据源产品、schema 或配置修订在核验期间变化");
        }
    }

    /** SQL 注释（用于只读校验前剥离，避免注释里的关键字影响判断） */
    private RestrictedSelectPlan parseUserSelect(AccessDatabaseResolver.ConnectionInfo info, String sql) {
        try {
            return RestrictedSelectGuard.parse(info.getProductType(), sql);
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw exception(DATABASE_QUERY_SQL_INVALID, ex.getMessage());
        }
    }

    /**
     * 调用方声明字段投影时，在数据库执行前约束 SELECT、过滤和排序，通配符按指定列展开。
     * 三个约束字段必须同时出现；缺失或对不上目标对象时拒绝执行，不退回无限制查询。
     */
    private String restrictToAllowedColumns(AccessDatabaseResolver.ConnectionInfo info, DatabaseQueryReqDTO reqDTO) {
        String boundSql = parseUserSelect(info, reqDTO.getSql()).getNormalizedSql();
        boolean scoped = cn.hutool.core.util.StrUtil.isNotBlank(reqDTO.getExpectedTable())
                || (reqDTO.getAllowedColumns() != null && !reqDTO.getAllowedColumns().isEmpty())
                || cn.hutool.core.util.StrUtil.isNotBlank(reqDTO.getExpectedSchema());
        if (!scoped) {
            return boundSql;
        }
        if (cn.hutool.core.util.StrUtil.isBlank(reqDTO.getExpectedTable())
                || reqDTO.getAllowedColumns() == null || reqDTO.getAllowedColumns().isEmpty()) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "字段投影必须同时给出目标表与列集合");
        }
        String schema = cn.hutool.core.util.StrUtil.blankToDefault(reqDTO.getExpectedSchema(), info.getSchema());
        if (cn.hutool.core.util.StrUtil.isBlank(schema)) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "无法确定受限查询的 schema");
        }
        try {
            return RestrictedSelectGuard.restrictProjection(info.getProductType(), boundSql,
                    schema, reqDTO.getExpectedTable(), List.copyOf(reqDTO.getAllowedColumns()),
                    Set.copyOf(reqDTO.getAllowedColumns())).sql();
        } catch (IllegalArgumentException | UnsupportedOperationException rejected) {
            throw exception(DATABASE_QUERY_SQL_INVALID, rejected.getMessage());
        }
    }

    /**
     * 转换为受限 SELECT。
     *
     * @param plan 计划
     * @return 受限 SELECT响应数据
     */
    private RestrictedSelectRespDTO toRestrictedSelect(RestrictedSelectPlan plan) {
        RestrictedSelectRespDTO dto = new RestrictedSelectRespDTO();
        dto.setSchemaName(plan.getSchemaName());
        dto.setTableName(plan.getTableName());
        dto.setNormalizedSql(plan.getNormalizedSql());
        dto.setWildcard(plan.isWildcard());
        dto.setSourceColumns(new java.util.ArrayList<>(plan.getSourceColumns()));
        return dto;
    }

    private static final Set<String> COMPARE_OPERATORS = Set.of(
            DatabasePreviewReqDTO.Filter.OP_EQ, DatabasePreviewReqDTO.Filter.OP_NE,
            DatabasePreviewReqDTO.Filter.OP_GT, DatabasePreviewReqDTO.Filter.OP_GE,
            DatabasePreviewReqDTO.Filter.OP_LT, DatabasePreviewReqDTO.Filter.OP_LE);

    private static final Set<Integer> CHARACTER_TYPES = Set.of(
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
            Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB);

    /**
     * 过滤条件转 WHERE 片段：字段名必须存在于表结构中（以空结果集元数据为准），
     * 操作符白名单，值按字段 JDBC 类型转换后走参数绑定。
     */
    private void loadPreviewColumns(Connection connection, String qualifiedTable,
            Map<String, Integer> columnTypes,
            Map<String, String> columnNames) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + qualifiedTable + " WHERE 1 = 0");
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String label = metadata.getColumnLabel(index);
                columnTypes.put(label.toLowerCase(Locale.ROOT), metadata.getColumnType(index));
                columnNames.put(label.toLowerCase(Locale.ROOT), label);
            }
        }
    }

    /**
     * 构造预览SELECTSQL。
     *
     * @param databaseMetaData 数据源元数据数据
     * @param qualifiedTable 完整限定表
     * @param requestedColumns 已请求列集合
     * @param columnNames 列名集合
     * @return 构造预览SELECTSQL文本
     */
    private String buildPreviewSelectSql(DatabaseMetaData databaseMetaData, String qualifiedTable,
            List<String> requestedColumns,
            Map<String, String> columnNames) throws Exception {
        if (cn.hutool.core.collection.CollUtil.isEmpty(requestedColumns)) {
            return "SELECT * FROM " + qualifiedTable;
        }
        String missing = PreviewSelectColumns.firstMissing(requestedColumns, columnNames);
        if (missing != null) {
            throw exception(DATABASE_PREVIEW_FAILED, "预览字段不存在：" + missing);
        }
        List<String> selected = PreviewSelectColumns.resolve(requestedColumns, columnNames);
        if (selected.isEmpty()) {
            throw exception(DATABASE_PREVIEW_FAILED, "预览字段范围无匹配列");
        }
        String selectList = selected.stream()
                .map(name -> {
                    try {
                        return quoteIdentifier(databaseMetaData, name);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .collect(Collectors.joining(", "));
        return "SELECT " + selectList + " FROM " + qualifiedTable;
    }

    /**
     * 构造筛选条件子句。
     *
     * @param databaseMetaData 数据源元数据数据
     * @param columnTypes 列类型
     * @param columnNames 列名集合
     * @param filters 筛选
     * @param params 业务参数映射
     * @return 构造筛选条件子句文本
     */
    private String buildWhereClause(DatabaseMetaData databaseMetaData,
            Map<String, Integer> columnTypes,
            Map<String, String> columnNames,
            List<DatabasePreviewReqDTO.Filter> filters,
            List<Object> params) throws Exception {
        StringBuilder where = new StringBuilder();
        for (DatabasePreviewReqDTO.Filter filter : filters) {
            String key = filter.getColumn().trim().toLowerCase(Locale.ROOT);
            if (!columnTypes.containsKey(key)) {
                throw exception(DATABASE_PREVIEW_FAILED, "过滤字段不存在：" + filter.getColumn());
            }
            String column = quoteIdentifier(databaseMetaData, columnNames.get(key));
            String operator = filter.getOperator();
            where.append(where.isEmpty() ? " WHERE " : " AND ");
            if (DatabasePreviewReqDTO.Filter.OP_NULL.equals(operator)) {
                where.append(column).append(" IS NULL");
            } else if (DatabasePreviewReqDTO.Filter.OP_NOT_NULL.equals(operator)) {
                where.append(column).append(" IS NOT NULL");
            } else if (DatabasePreviewReqDTO.Filter.OP_LIKE.equals(operator)) {
                if (!CHARACTER_TYPES.contains(columnTypes.get(key))) {
                    throw exception(DATABASE_PREVIEW_FAILED,
                            "模糊匹配仅支持字符类型字段：" + filter.getColumn());
                }
                where.append(column).append(" LIKE ?");
                params.add("%" + cn.hutool.core.util.StrUtil.nullToEmpty(filter.getValue()) + "%");
            } else if (COMPARE_OPERATORS.contains(operator)) {
                where.append(column).append(' ').append(sqlOperator(operator)).append(" ?");
                params.add(convertFilterValue(filter.getValue(), columnTypes.get(key)));
            } else {
                throw exception(DATABASE_PREVIEW_FAILED, "不支持的过滤操作符：" + operator);
            }
        }
        return where.toString();
    }

    /**
     * 获取数据源的SQL操作人。
     *
     * @param operator 操作人
     * @return 数据源的SQL操作人文本
     */
    private String sqlOperator(String operator) {
        return switch (operator) {
            case DatabasePreviewReqDTO.Filter.OP_NE -> "<>";
            case DatabasePreviewReqDTO.Filter.OP_GT -> ">";
            case DatabasePreviewReqDTO.Filter.OP_GE -> ">=";
            case DatabasePreviewReqDTO.Filter.OP_LT -> "<";
            case DatabasePreviewReqDTO.Filter.OP_LE -> "<=";
            default -> "=";
        };
    }

    /**
     * 按字段 JDBC 类型把字符串过滤值转换成绑定参数，避免 PostgreSQL 等严格类型库比较报错
     */
    private Object convertFilterValue(String value, Integer jdbcType) {
        if (value == null) {
            throw exception(DATABASE_PREVIEW_FAILED, "比较类操作符必须提供过滤值");
        }
        String trimmed = value.trim();
        int type = jdbcType == null ? Types.VARCHAR : jdbcType;
        try {
            return switch (type) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Long.valueOf(trimmed);
                case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE -> new BigDecimal(trimmed);
                case Types.BOOLEAN, Types.BIT -> switch (trimmed.toLowerCase(Locale.ROOT)) {
                    case "true", "1" -> true;
                    case "false", "0" -> false;
                    default -> throw new IllegalArgumentException("布尔过滤值无效");
                };
                case Types.DATE -> java.sql.Date.valueOf(trimmed);
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                        java.sql.Timestamp.valueOf(trimmed.length() == 10 ? trimmed + " 00:00:00"
                                : trimmed.replace('T', ' '));
                default -> value;
            };
        } catch (RuntimeException ex) {
            throw exception(DATABASE_PREVIEW_FAILED, "过滤值与字段类型不匹配：" + value);
        }
    }

    /**
     * 执行预览。
     *
     * @param connection 数据库连接
     * @param sql SQL
     * @param params 业务参数映射
     * @param limit 读取或处理上限
     * @return 数据源预览响应数据
     */
    private DatabasePreviewRespDTO executePreview(Connection connection, String sql,
            List<Object> params, int limit) throws Exception {
        DatabasePreviewRespDTO response = new DatabasePreviewRespDTO();
        List<DatabasePreviewRespDTO.Column> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try {
                statement.setQueryTimeout(15);
            } catch (Exception ignored) {
                // 驱动不支持查询超时时，仍使用最大返回行数限制
            }
            statement.setMaxRows(limit + 1);
            for (int index = 0; index < params.size(); index++) {
                statement.setObject(index + 1, params.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<String> keys = buildColumns(metadata, columns);
                while (resultSet.next()) {
                    if (rows.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(keys.get(index - 1), normalizeValue(resultSet.getObject(index)));
                    }
                    rows.add(row);
                }
            }
        }
        response.setColumns(columns);
        response.setRows(rows);
        response.setTruncated(truncated);
        return response;
    }

    /**
     * 构造数据源的列集合。
     *
     * @param metadata 元数据
     * @param columns 列集合
     * @return 构造数据源的列集合结果集合
     */
    private List<String> buildColumns(ResultSetMetaData metadata,
            List<DatabasePreviewRespDTO.Column> columns) throws Exception {
        List<String> keys = new ArrayList<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            String normalized = label.toLowerCase(Locale.ROOT);
            int occurrence = occurrences.merge(normalized, 1, Integer::sum);
            String key = occurrence == 1 ? label : label + "_" + occurrence;
            keys.add(key);
            columns.add(new DatabasePreviewRespDTO.Column(
                    key, label, metadata.getColumnTypeName(index)));
        }
        return keys;
    }

    /**
     * 规范化值。
     *
     * @param value 待处理的值
     * @return 数据源
     */
    private Object normalizeValue(Object value) throws Exception {
        // 精确数值必须在 JSON 编码前变成十进制字符串，避免浏览器先解析为有损 double。
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof java.math.BigInteger integer) {
            return integer.toString();
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "[BINARY " + bytes.length + " bytes]";
        }
        if (value instanceof Blob blob) {
            return "[BINARY " + blob.length() + " bytes]";
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, (int) Math.min(clob.length(), 2000));
        }
        if (value instanceof TemporalAccessor || value instanceof java.util.Date) {
            return value.toString();
        }
        return cn.hutool.core.util.StrUtil.maxLength(String.valueOf(value), 2000);
    }

    /**
     * 按目标数据库方言引用标识符。
     *
     * @param metadata 元数据
     * @param identifier 标识符
     * @return 按目标数据库方言引用标识符文本
     */
    private String quoteIdentifier(DatabaseMetaData metadata, String identifier) throws Exception {
        if (identifier == null || identifier.indexOf('\0') >= 0 || identifier.length() > 256) {
            throw exception(DATABASE_PREVIEW_FAILED, "非法标识符");
        }
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            if (!identifier.matches("[A-Za-z0-9_$#]+")) {
                throw exception(DATABASE_PREVIEW_FAILED, "当前数据库不支持安全引用该标识符");
            }
            return identifier;
        }
        quote = quote.trim();
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

}
