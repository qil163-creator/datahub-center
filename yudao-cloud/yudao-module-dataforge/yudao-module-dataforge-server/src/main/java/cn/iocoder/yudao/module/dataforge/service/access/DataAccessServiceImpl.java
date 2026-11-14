package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.number.NumberUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.seatunnel.NativeSeatunnelConfig;
import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableMapping;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessIncreaseLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskDetailMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessEngineTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessModeEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessReleaseStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessTaskTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.database.DatabaseUsageEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据同步任务 Service 实现类
 *
 * 业务流程参考 srt-cloud 的数据接入（DataAccess），执行引擎复用 DB Switch 组件
 *
 * @author Li
 */
@Service
@Validated
public class DataAccessServiceImpl implements DataAccessService {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    /**
     * 入中台库模式下目标表的统一前缀
     */
    public static final String ODS_TABLE_PREFIX = "ods_";

    /**
     * 字段映射为空表示不同步该字段的占位标记
     */
    private static final String STRING_DELETE = "<!删除>";

    private static final Integer INCLUDE = 1;

    @Resource
    private DataAccessMapper dataAccessMapper;

    @Resource
    private DataAccessTaskMapper taskMapper;

    @Resource
    private DataAccessTaskDetailMapper taskDetailMapper;

    @Resource
    private DataAccessIncreaseLogMapper increaseLogMapper;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private DataAccessExecutor dataAccessExecutor;

    @Resource
    private AccessRunCoordinator runCoordinator;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport databaseQuerySupport;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService executionAccess;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建数据同步任务
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDataAccess(DataAccessSaveReqVO createReqVO) {
        DataAccessDO dataAccess = buildDataAccess(createReqVO);
        dataAccessMapper.insert(dataAccess);
        return dataAccess.getId();
    }

    /**
     * 更新数据同步任务（已发布的任务需先取消发布）
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateDataAccess(DataAccessSaveReqVO updateReqVO) {
        DataAccessDO old = validateDataAccessExists(updateReqVO.getId());
        validateNotReleasedAndNotRunning(old);
        DataAccessDO updateObj = buildDataAccess(updateReqVO);
        updateObj.setId(old.getId());
        runCoordinator.updateDraft(old, updateObj);
    }

    /**
     * 删除数据同步任务，级联删除执行记录、表级结果与增量日志
     *
     * @param id 记录编号
     */
    @Override
    public void deleteDataAccess(Long id) {
        runCoordinator.deleteDefinition(id);
    }

    /**
     * 获得数据同步任务（含同步配置回显）
     *
     * @param id 记录编号
     * @return 数据同步任务响应数据
     */
    @Override
    public DataAccessRespVO getDataAccess(Long id) {
        DataAccessDO dataAccess = validateDataAccessExists(id);
        return CollUtil.getFirst(convertToRespList(Collections.singletonList(dataAccess)));
    }

    /**
     * 获得数据同步任务分页
     *
     * @param pageReqVO 分页查询条件
     * @return 数据同步任务分页结果
     */
    @Override
    public PageResult<DataAccessRespVO> getDataAccessPage(DataAccessPageReqVO pageReqVO) {
        PageResult<DataAccessDO> pageResult = dataAccessMapper.selectPage(pageReqVO);
        return new PageResult<>(convertToRespList(pageResult.getList()), pageResult.getTotal());
    }

    // ==================== 名称映射预览 ====================

    /**
     * 预览表名映射
     *
     * @param reqVO 请求参数
     * @return 表名映射预览结果集合
     */
    @Override
    public List<PreviewNameMapperRespVO> previewTableMap(DataAccessPreviewReqVO reqVO) {
        List<String> selectedTables = exactSourceTableNames(reqVO.getSourceSelectedTables());
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "执行主体");
        }
        executionAccess.requireUserPermission(userId, "dataforge:access:query");
        executionAccess.requireDatabase(reqVO.getSourceDatabaseId());
        boolean include = !Objects.equals(reqVO.getIncludeOrExclude(), 0);
        if (include && !selectedTables.isEmpty()) {
            var connection = resolvePreviewDatabase(reqVO.getSourceDatabaseId(), DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS,
                    "源端");
            databaseQuerySupport.requireExactTables(reqVO.getSourceDatabaseId(), connection.getProductType(),
                    connection.getSchema(), selectedTables);
        }
        var mapping = namePreviewMapping(reqVO);
        List<TableDescription> allTables = getAllTableNames(reqVO.getSourceDatabaseId());

        List<PreviewNameMapperRespVO> result = new ArrayList<>();
        if (CollUtil.isEmpty(selectedTables)) {
            // 未选择表时预览全部
            for (TableDescription table : allTables) {
                result.add(buildTablePreview(table.getTableName(), table.getRemarks(), mapping));
            }
        } else if (include) {
            for (String name : selectedTables) {
                TableDescription table = allTables.stream()
                        .filter(item -> name.equals(item.getTableName())).findFirst().orElse(null);
                result.add(buildTablePreview(name, table != null ? table.getRemarks() : null, mapping));
            }
        } else {
            for (TableDescription table : allTables) {
                if (!selectedTables.contains(table.getTableName())) {
                    result.add(buildTablePreview(table.getTableName(), table.getRemarks(), mapping));
                }
            }
        }
        return result;
    }

    /**
     * 构造表预览。
     *
     * @param originalName 原始名称
     * @param remarks 备注
     * @param mapping 映射
     * @return 预览名称数据访问响应数据
     */
    private PreviewNameMapperRespVO buildTablePreview(String originalName, String remarks,
            cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest mapping) {
        requireExactSourceTableName(originalName);
        final String targetName;
        try {
            targetName = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(
                    mapping.getSource().getFirst(), mapping.getTarget(), originalName);
            authorizePreviewTarget(mapping, targetName);
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "最终物理目标表名");
        }
        return PreviewNameMapperRespVO.builder()
                .originalName(originalName)
                .targetName(targetName)
                .remarks(StringUtils.isNotBlank(remarks) ? remarks : originalName)
                .build();
    }

    /** 新请求按受管理目标产品预览；旧名称请求未提供目标时只应用显式映射规则。 */
    private cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest namePreviewMapping(
            DataAccessPreviewReqVO reqVO) {
        if (!Objects.equals(reqVO.getSchemaVersion(), AccessTaskConfig.CURRENT_VERSION)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "配置结构版本");
        }
        validateTableOverrideSource(reqVO.getConfigMap(), reqVO.getSourceType());
        if (hasTableOverrides(reqVO.getConfigMap()) && reqVO.getTargetDatabaseId() == null) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "多表建表映射的目标数据库");
        }
        var config = new AccessTaskConfig();
        var source = new AccessTaskConfig.Source();
        source.setMapperType(StrUtil.blankToDefault(reqVO.getMapperType(), MapperType.ALL.name()));
        source.setSourcePrimaryKeys(reqVO.getSourcePrimaryKeys());
        source.setRegexTableMapper(reqVO.getTableNameMapper());
        source.setRegexColumnMapper(reqVO.getColumnNameMapper());
        source.setConfigMap(reqVO.getConfigMap());
        config.getSource().add(source);
        config.getTarget()
                .setTablePrefix(AccessModeEnum.ODS.getMode().equals(reqVO.getAccessMode()) ? ODS_TABLE_PREFIX : null);
        config.getTarget().setLowercase(reqVO.isTargetLowerCase());
        config.getTarget().setUppercase(reqVO.isTargetUpperCase());
        config.getTarget().setTableNameCase(reqVO.getTargetTableNameCase());
        config.getTarget().setColumnNameCase(reqVO.getTargetColumnNameCase());
        config.getTarget().setSyncDefaultVal(reqVO.isSyncDefaultVal());
        final cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest mapping;
        try {
            mapping = AccessTaskConfigMapper.toRuntime(config, reqVO.getTargetDatabaseId());
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_MAPPING_CONFIG_INVALID, "名称映射规则或建表选项");
        }
        if (reqVO.getTargetDatabaseId() != null) {
            executionAccess.requireDatabase(reqVO.getTargetDatabaseId());
            var targetConnection = resolvePreviewDatabase(reqVO.getTargetDatabaseId(),
                    DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS, "目标");
            if (AccessModeEnum.ODS.getMode().equals(reqVO.getAccessMode())
                    && !DatabaseUsageEnum.PLATFORM.getType().equals(targetConnection.getUsageType())) {
                throw exception(DATA_ACCESS_TARGET_NOT_PLATFORM);
            }
            mapping.getTarget().setTargetProductType(targetConnection.getProductType());
            mapping.getTarget().setTargetSchema(targetConnection.getSchema());
        }
        validateDorisTarget(reqVO.getConfigMap(), reqVO.getAccessMode(), mapping.getTarget().getTargetProductType());
        return mapping;
    }

    /**
     * 解析预览数据源。
     *
     * @param id 记录编号
     * @param notExists 未存在性
     * @param role 角色
     * @return 连接信息
     */
    private AccessDatabaseResolver.ConnectionInfo resolvePreviewDatabase(Long id, ErrorCode notExists, String role) {
        return resolvePreviewDatabase(id, notExists, role, true);
    }

    /**
     * 解析预览数据源。
     *
     * @param id 记录编号
     * @param notExists 未存在性
     * @param role 角色
     * @param namespaceRequired 命名空间必需
     * @return 连接信息
     */
    private AccessDatabaseResolver.ConnectionInfo resolvePreviewDatabase(Long id, ErrorCode notExists, String role,
            boolean namespaceRequired) {
        final AccessDatabaseResolver.ConnectionInfo connection;
        try {
            connection = databaseResolver.resolve(id, notExists);
        } catch (ServiceException invalid) {
            if (Objects.equals(invalid.getCode(), DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED.getCode())) {
                throw exception(DATA_ACCESS_DATABASE_PRODUCT_MISSING, role);
            }
            throw invalid;
        }
        requirePreviewDatabase(connection, role, namespaceRequired);
        return connection;
    }

    /**
     * 校验预览数据源。
     *
     * @param connection 数据库连接
     * @param role 角色
     */
    private static void requirePreviewDatabase(AccessDatabaseResolver.ConnectionInfo connection, String role) {
        requirePreviewDatabase(connection, role, true);
    }

    /**
     * 校验预览数据源。
     *
     * @param connection 数据库连接
     * @param role 角色
     * @param namespaceRequired 命名空间必需
     */
    private static void requirePreviewDatabase(AccessDatabaseResolver.ConnectionInfo connection, String role,
            boolean namespaceRequired) {
        if (connection.getProductType() == null || connection.getProductType() == ProductTypeEnum.UNKNOWN) {
            throw exception(DATA_ACCESS_DATABASE_PRODUCT_MISSING, role);
        }
        if (namespaceRequired && StringUtils.isBlank(connection.getSchema())) {
            throw exception(DATA_ACCESS_DATABASE_NAMESPACE_MISSING, role);
        }
    }

    /**
     * 获取数据同步任务的精确来源表名集合。
     *
     * @param names 名称
     * @return 数据同步任务的精确来源表名集合结果集合
     */
    private List<String> exactSourceTableNames(List<String> names) {
        try {
            return cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.requireExactNames(
                    names == null ? List.of() : names);
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "请选择互不重复、非空且不含 NUL 的精确物理表名");
        }
    }

    /**
     * 授权预览目标。
     *
     * @param mapping 映射
     * @param table 表
     */
    private void authorizePreviewTarget(cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest mapping,
            String table) {
        var target = mapping.getTarget();
        if (target.getDatabaseId() == null) {
            return; // 旧版仅提供名称的请求不声明物理目标数据库产品。
        }
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "执行主体");
        }
        cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                target.getTargetProductType(), target.getTargetSchema(), table);
    }

    /**
     * 校验精确来源表名。
     *
     * @param name 名称
     */
    private void requireExactSourceTableName(String name) {
        try {
            cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.requireExactName(name);
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "源表名称无法精确编码，请重新选择精确物理表名");
        }
    }

    /**
     * 校验SQL统一映射。
     *
     * @param mapperType 数据访问类型
     */
    private void requireSqlUnifiedMapping(String mapperType) {
        try {
            AccessTaskConfigCodec.requireSqlUnifiedMapping(StrUtil.blankToDefault(mapperType, MapperType.ALL.name()));
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "SQL 来源仅支持统一映射（ALL）");
        }
    }

    /**
     * 预览字段名映射
     *
     * @param reqVO 请求参数
     * @return 字段名映射预览结果集合
     */
    @Override
    public List<PreviewNameMapperRespVO> previewColumnMap(DataAccessPreviewReqVO reqVO) {
        boolean sqlSource = SourceType.SQL.getCode().equals(reqVO.getSourceType());
        if (sqlSource) {
            requireSqlUnifiedMapping(reqVO.getMapperType());
        }
        if (sqlSource && StringUtils.isBlank(reqVO.getSourceSql())) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "源端 SQL");
        }
        if (!sqlSource && StringUtils.isBlank(reqVO.getPreviewTableName())) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "预览表名");
        }
        if (!sqlSource) {
            requireExactSourceTableName(reqVO.getPreviewTableName());
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "执行主体");
        }
        executionAccess.requireUserPermission(userId, "dataforge:access:query");
        executionAccess.requireDatabase(reqVO.getSourceDatabaseId());
        // SQL 自带来源表定位；仅表来源需要用数据源 namespace 组装查询。
        AccessDatabaseResolver.ConnectionInfo conn =
                resolvePreviewDatabase(reqVO.getSourceDatabaseId(), DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS, "源端",
                        !sqlSource);
        String sourceSql = sqlSource
                ? databaseQuerySupport.normalizeSelect(reqVO.getSourceDatabaseId(), reqVO.getSourceSql()) : null;
        if (!sqlSource) {
            databaseQuerySupport.requireExactTables(reqVO.getSourceDatabaseId(), conn.getProductType(),
                    conn.getSchema(), List.of(reqVO.getPreviewTableName()));
        }
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        var mapping = namePreviewMapping(reqVO);
        if (!sqlSource) {
            try {
                authorizePreviewTarget(mapping,
                        cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(
                                mapping.getSource().getFirst(), mapping.getTarget(), reqVO.getPreviewTableName()));
                var observed = metaDataService.queryTableColumnsAndKeys(conn.getJdbcUrl(), conn.getUsername(),
                        conn.getPassword(),
                        conn.getSchema(), reqVO.getPreviewTableName());
                var columns = observed.columns();
                var mapped = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings.resolve(
                        mapping.getSource().getFirst(), mapping.getTarget(), reqVO.getPreviewTableName(), columns,
                        observed.primaryKeys());
                validateDorisColumns(reqVO.getConfigMap(), reqVO.getPreviewTableName(), mapped.retainedColumns());
                var result = new ArrayList<PreviewNameMapperRespVO>(columns.size());
                for (int i = 0; i < columns.size(); i++) {
                    var column = columns.get(i);
                    var targetName = mapped.columns().get(i).getFieldName();
                    result.add(PreviewNameMapperRespVO.builder().originalName(column.getFieldName())
                            .targetName(StringUtils.isNotBlank(targetName) ? targetName : STRING_DELETE)
                            .remarks(StringUtils.isNotBlank(
                                    column.getRemarks()) ? column.getRemarks() : column.getFieldName()).build());
                }
                return result;
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "受控表结构或列映射无效");
            }
        }
        List<ColumnDescription> columns = metaDataService.querySqlColumnMeta(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), sourceSql);

        final cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings.Plan mapped;
        try {
            mapped = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings.resolve(
                    mapping.getSource().getFirst(), mapping.getTarget(), reqVO.getPreviewTableName(), columns,
                    List.of());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "SQL 投影或列映射无效");
        }
        List<PreviewNameMapperRespVO> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDescription column = columns.get(i);
            String targetName = mapped.columns().get(i).getFieldName();
            if (sqlSource && StringUtils.isBlank(targetName)) {
                throw exception(DATA_ACCESS_SQL_COLUMN_MAPPER_BLANK);
            }
            result.add(PreviewNameMapperRespVO.builder()
                    .originalName(column.getFieldName())
                    .targetName(StringUtils.isNotBlank(targetName) ? targetName : STRING_DELETE)
                    .remarks(StringUtils.isNotBlank(column.getRemarks()) ? column.getRemarks() : column.getFieldName())
                    .build());
        }
        return result;
    }

    /**
     * 已授权表源的跨产品列映射；只读元数据，全部有损列默认不接受。
     *
     * @param reqVO 请求参数
     * @return 访问类型映射计划响应数据
     */
    @Override
    public AccessTypeMappingPlanRespVO previewTypeMap(AccessTypeMappingPreviewReqVO reqVO) {
        if (!Objects.equals(reqVO.getSchemaVersion(), AccessTaskConfig.CURRENT_VERSION)
                || !SourceType.TABLE.getCode().equals(reqVO.getSourceType())
                || StringUtils.isBlank(reqVO.getPreviewTableName()) || reqVO.getPreviewTableName().length() > 256
                || reqVO.getTargetDatabaseId() == null || reqVO.isTargetLowerCase() && reqVO.isTargetUpperCase()) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "版本 1 的表源类型映射参数");
        }
        requireExactSourceTableName(reqVO.getPreviewTableName());
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "执行主体");
        }
        executionAccess.requireUserPermission(userId, "dataforge:access:query");
        executionAccess.requireDatabase(reqVO.getSourceDatabaseId());
        executionAccess.requireDatabase(reqVO.getTargetDatabaseId());
        var sourceConnection = resolvePreviewDatabase(reqVO.getSourceDatabaseId(),
                DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS, "源端");
        var targetConnection = resolvePreviewDatabase(reqVO.getTargetDatabaseId(),
                DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS, "目标");
        if (!dbSwitchRuntime.products().typeMappings()
                .supports(sourceConnection.getProductType(), targetConnection.getProductType())) {
            throw exception(DATA_ACCESS_TYPE_MAPPING_UNSUPPORTED, sourceConnection.getProductType().name(),
                    targetConnection.getProductType().name());
        }
        boolean odsMode = AccessModeEnum.ODS.getMode().equals(reqVO.getAccessMode());
        if (odsMode && !DatabaseUsageEnum.PLATFORM.getType().equals(targetConnection.getUsageType())) {
            throw exception(DATA_ACCESS_TARGET_NOT_PLATFORM);
        }
        validateDorisTarget(reqVO.getConfigMap(), reqVO.getAccessMode(), targetConnection.getProductType());
        var config = new AccessTaskConfig();
        var source = config.getSource();
        var configuredSource = new AccessTaskConfig.Source();
        configuredSource.setMapperType(StrUtil.blankToDefault(reqVO.getMapperType(), MapperType.ALL.name()));
        configuredSource.setRegexTableMapper(reqVO.getTableNameMapper());
        configuredSource.setRegexColumnMapper(reqVO.getColumnNameMapper());
        configuredSource.setConfigMap(reqVO.getConfigMap());
        configuredSource.setSourcePrimaryKeys(reqVO.getSourcePrimaryKeys());
        source.add(configuredSource);
        config.getTarget().setLowercase(reqVO.isTargetLowerCase());
        config.getTarget().setUppercase(reqVO.isTargetUpperCase());
        config.getTarget().setTableNameCase(reqVO.getTargetTableNameCase());
        config.getTarget().setColumnNameCase(reqVO.getTargetColumnNameCase());
        config.getTarget().setTablePrefix(odsMode ? ODS_TABLE_PREFIX : null);
        config.getTarget().setSyncDefaultVal(reqVO.isSyncDefaultVal());
        final cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest request;
        try {
            request = AccessTaskConfigMapper.toRuntime(config, reqVO.getTargetDatabaseId());
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_MAPPING_CONFIG_INVALID, "列映射规则或建表选项");
        }
        var runtimeSource = request.getSource().getFirst();
        var runtimeTarget = request.getTarget();
        runtimeSource.setSourceProductType(sourceConnection.getProductType());
        runtimeTarget.setTargetProductType(targetConnection.getProductType());
        runtimeTarget.setTargetSchema(targetConnection.getSchema());
        final String targetTable;
        try {
            targetTable = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(
                    runtimeSource, runtimeTarget, reqVO.getPreviewTableName());
            cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                    targetConnection.getProductType(), targetConnection.getSchema(), targetTable);
            databaseQuerySupport.requireExactTables(reqVO.getSourceDatabaseId(), sourceConnection.getProductType(),
                    sourceConnection.getSchema(), List.of(reqVO.getPreviewTableName()));
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "最终物理目标表名");
        }
        try (var connection = dbSwitchRuntime.openConnection(sourceConnection.getProductType(), null,
                sourceConnection.getJdbcUrl(), sourceConnection.getUsername(), sourceConnection.getPassword());
             var deadline = new cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline(connection, 10_000)) {
            var observed = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(connection,
                    sourceConnection.getProductType(), sourceConnection.getSchema(), reqVO.getPreviewTableName(), 10);
            deadline.check();
            var mapped = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings.resolve(runtimeSource,
                    runtimeTarget,
                    reqVO.getPreviewTableName(), observed.columns(), observed.primaryKeys());
            validateDorisColumns(reqVO.getConfigMap(), reqVO.getPreviewTableName(), mapped.retainedColumns());
            var plan = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.planCrossProductColumns(
                    dbSwitchRuntime.products().metadata(targetConnection.getProductType()), mapped.retainedColumns(),
                    mapped.primaryKeys(),
                    cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions.of(
                            dbSwitchRuntime.products().typeMappings()));
            return AccessTypeMappingDiagnostics.response(
                    AccessTypeMappingDiagnostics.from(plan, sourceConnection.getSchema(),
                            reqVO.getPreviewTableName(), targetConnection.getSchema(), targetTable));
        } catch (java.sql.SQLException | IllegalArgumentException | IllegalStateException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "类型映射元数据不可用或列映射无效；请核查受托管配置");
        }
    }

    /**
     * 读取全部表名称。
     *
     * @param sourceDatabaseId 来源数据源编号
     * @return 全部表名称结果集合
     */
    private List<TableDescription> getAllTableNames(Long sourceDatabaseId) {
        AccessDatabaseResolver.ConnectionInfo conn =
                resolvePreviewDatabase(sourceDatabaseId, DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS, "源端");
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        Long userId = cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "执行主体");
        }
        var tables = metaDataService.queryTableList(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                        conn.getSchema())
                .stream().filter(table -> !table.isViewTable()).toList();
        databaseQuerySupport.requireExactTables(sourceDatabaseId, conn.getProductType(), conn.getSchema(),
                tables.stream().map(TableDescription::getTableName).toList());
        return tables;
    }

    // ==================== 发布 / 执行 ====================

    /**
     * 发布任务：周期任务计算下次执行时间等待调度，一次性任务立即执行一次
     *
     * @param id 记录编号
     */
    @Override
    public void releaseDataAccess(Long id) {
        runCoordinator.publish(id);
    }

    /**
     * 取消发布
     *
     * @param id 记录编号
     */
    @Override
    public void cancelDataAccess(Long id) {
        runCoordinator.unpublish(id);
    }

    /**
     * 手动执行一次
     *
     * @param id 记录编号
     */
    @Override
    public void runDataAccess(Long id) {
        DataAccessDO task = validateDataAccessExists(id);
        validateEngineSupported(task.getEngineType());
        if (dataAccessExecutor.isRunning(id)) {
            throw exception(DATA_ACCESS_RUNNING);
        }
        runCoordinator.enqueueManual(id, java.util.UUID.randomUUID().toString());
    }

    /**
     * 停止正在运行的任务
     *
     * @param id 记录编号
     */
    @Override
    public void stopDataAccess(Long id) {
        validateDataAccessExists(id);
        if (!dataAccessExecutor.stop(id)) {
            throw exception(DATA_ACCESS_NOT_RUNNING);
        }
    }

    // ==================== 执行记录 / 表级结果 ====================

    /**
     * 获得执行记录分页
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    @Override
    public PageResult<DataAccessTaskRespVO> getTaskPage(DataAccessTaskPageReqVO pageReqVO) {
        return BeanUtils.toBean(taskMapper.selectPage(pageReqVO), DataAccessTaskRespVO.class);
    }

    /**
     * 获得执行记录（含实时日志，可轮询）
     *
     * @param id 记录编号
     * @return 数据同步任务任务响应数据
     */
    @Override
    public DataAccessTaskRespVO getTask(Long id) {
        DataAccessTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(DATA_ACCESS_TASK_NOT_EXISTS);
        }
        return BeanUtils.toBean(dataAccessExecutor.reconcileSeatunnel(task), DataAccessTaskRespVO.class);
    }

    /**
     * 批量删除执行记录（级联删除表级结果）
     *
     * @param ids 记录编号集合
     */
    @Override
    public void deleteTaskList(List<Long> ids) {
        runCoordinator.deleteRuns(ids);
    }

    /**
     * 获得表级同步结果分页
     *
     * @param pageReqVO 分页查询条件
     * @return 表级同步结果分页结果
     */
    @Override
    public PageResult<DataAccessTaskDetailRespVO> getTaskDetailPage(DataAccessTaskDetailPageReqVO pageReqVO) {
        var page = taskDetailMapper.selectPage(pageReqVO);
        return new PageResult<>(page.getList().stream().map(AccessTypeMappingDiagnostics::detailResponse).toList(),
                page.getTotal());
    }

    // ==================== 内部方法 ====================

    /**
     * 校验数据同步任务存在性。
     *
     * @param id 记录编号
     * @return 数据同步任务
     */
    private DataAccessDO validateDataAccessExists(Long id) {
        DataAccessDO dataAccess = dataAccessMapper.selectById(id);
        if (dataAccess == null) {
            throw exception(DATA_ACCESS_NOT_EXISTS);
        }
        return dataAccess;
    }

    /**
     * 校验未已发布与未运行中。
     *
     * @param dataAccess 数据同步任务
     */
    private void validateNotReleasedAndNotRunning(DataAccessDO dataAccess) {
        if (AccessReleaseStatusEnum.RELEASED.getStatus().equals(dataAccess.getStatus())) {
            throw exception(DATA_ACCESS_RELEASED_CANNOT_MODIFY);
        }
        if (dataAccessExecutor.isRunning(dataAccess.getId())) {
            throw exception(DATA_ACCESS_RUNNING);
        }
    }

    /**
     * 校验引擎支持范围。
     *
     * @param engineType 引擎类型
     */
    private void validateEngineSupported(Integer engineType) {
        if (engineType != null
                && !AccessEngineTypeEnum.DBSWITCH.getType().equals(engineType)
                && !AccessEngineTypeEnum.SEATUNNEL.getType().equals(engineType)
                && !AccessEngineTypeEnum.API_ACCESS.getType().equals(engineType)) {
            throw exception(DATA_ACCESS_ENGINE_NOT_SUPPORTED);
        }
    }

    /**
     * 请求 VO → DO：校验业务规则，并按引擎组装同步配置
     * 离线同步保存平台数据源绑定；SeaTunnel 保存用户编写的原生作业配置。
     */
    private DataAccessDO buildDataAccess(DataAccessSaveReqVO reqVO) {
        // 1. 业务校验
        Integer engineType = reqVO.getEngineType() == null
                ? AccessEngineTypeEnum.DBSWITCH.getType() : reqVO.getEngineType();
        validateEngineSupported(engineType);
        if (hasTableOverrides(reqVO.getConfigMap()) && !AccessEngineTypeEnum.DBSWITCH.getType().equals(engineType)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "多表建表映射仅适用于离线同步");
        }
        validateTableOverrideSource(reqVO.getConfigMap(), reqVO.getSourceType());
        if (reqVO.getTargetDistributedKeys() == null
                || !AccessEngineTypeEnum.DBSWITCH.getType().equals(engineType)
                && !reqVO.getTargetDistributedKeys().isEmpty()) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "分布列仅适用于 DB Switch Greenplum 自动建表");
        }
        if (!AccessEngineTypeEnum.DBSWITCH.getType().equals(engineType)
                && java.util.stream.Stream.of(reqVO.getSourceBeforeSqlScripts(), reqVO.getSourceAfterSqlScripts(),
                reqVO.getTargetBeforeSqlScripts(), reqVO.getTargetAfterSqlScripts()).anyMatch(StrUtil::isNotBlank)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "同步生命周期脚本仅适用于 DB Switch");
        }
        if (!AccessEngineTypeEnum.DBSWITCH.getType().equals(engineType)
                && reqVO.getTypeMappingAcknowledgements() != null && !reqVO.getTypeMappingAcknowledgements()
                .isEmpty()) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "有损类型映射确认仅适用于 DB Switch 表源迁移");
        }
        boolean seatunnelEngine = AccessEngineTypeEnum.SEATUNNEL.getType().equals(engineType);
        if (AccessTaskTypeEnum.REAL_TIME_SYNC.getType().equals(reqVO.getTaskType()) && !seatunnelEngine) {
            throw exception(DATA_ACCESS_REALTIME_ONLY_SEATUNNEL);
        }
        boolean periodic = AccessTaskTypeEnum.ONE_TIME_FULL_PERIODIC_INCR_SYNC.getType().equals(reqVO.getTaskType());
        if (periodic && (StrUtil.isBlank(reqVO.getCron()) || !CronExpression.isValidExpression(reqVO.getCron()))) {
            throw exception(DATA_ACCESS_CRON_INVALID, reqVO.getCron());
        }
        // 2. API 接入：校验映射并保存 API 接入配置
        if (AccessEngineTypeEnum.API_ACCESS.getType().equals(engineType)) {
            return buildApiAccess(reqVO, engineType, periodic);
        }
        // 3. SeaTunnel 保留 JSON/HOCON 原文，连接器和连接参数由作业配置定义。
        if (seatunnelEngine) {
            String format;
            try {
                format = NativeSeatunnelConfig.normalizeFormat(reqVO.getConfigFormat());
                NativeSeatunnelConfig.validate(reqVO.getSeatunnelConfig(), format);
            } catch (IllegalArgumentException invalid) {
                throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, invalid.getMessage());
            }
            if (reqVO.getTaskType() == null || !List.of(1, 2, 3).contains(reqVO.getTaskType())) {
                throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "请选择实时同步、一次性全量或周期增量");
            }
            DataAccessDO definition = DataAccessDO.builder()
                    .id(reqVO.getId())
                    .taskName(reqVO.getTaskName())
                    .description(reqVO.getDescription())
                    .orgId(reqVO.getOrgId())
                    .engineType(engineType)
                    .taskType(reqVO.getTaskType())
                    .cron(periodic ? reqVO.getCron() : null)
                    .status(AccessReleaseStatusEnum.UNRELEASED.getStatus())
                    .seatunnelConfig(reqVO.getSeatunnelConfig())
                    .configFormat(format)
                    .remark(reqVO.getRemark())
                    .build();
            return definition;
        }
        // 4. 离线同步（DBSwitch）：校验源端/目的端并组装 dbswitch 属性
        if (reqVO.getSourceDatabaseId() == null) {
            throw exception(DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS);
        }
        if (reqVO.getTargetDatabaseId() == null) {
            throw exception(DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS);
        }
        boolean sqlSource = SourceType.SQL.getCode().equals(reqVO.getSourceType());
        if (sqlSource) {
            requireSqlUnifiedMapping(reqVO.getMapperType());
        }
        List<String> selectedTables = sqlSource ? reqVO.getSourceSelectedTables() : exactSourceTableNames(
                reqVO.getSourceSelectedTables());
        if (sqlSource) {
            if (StrUtil.isBlank(reqVO.getSourceSql())) {
                throw exception(DATA_ACCESS_SOURCE_SQL_BLANK);
            }
            if (StrUtil.isBlank(reqVO.getTargetTable())) {
                throw exception(DATA_ACCESS_TARGET_TABLE_BLANK);
            }
            if (CollUtil.isNotEmpty(reqVO.getColumnNameMapper())
                    && reqVO.getColumnNameMapper().stream().anyMatch(item -> StrUtil.isBlank(item.getToValue()))) {
                throw exception(DATA_ACCESS_SQL_COLUMN_MAPPER_BLANK);
            }
        }
        DatabaseDO sourceDatabase = databaseMapper.selectById(reqVO.getSourceDatabaseId());
        if (sourceDatabase == null) {
            throw exception(DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS);
        }
        DatabaseDO targetDatabase = databaseMapper.selectById(reqVO.getTargetDatabaseId());
        if (targetDatabase == null) {
            throw exception(DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS);
        }
        boolean odsMode = AccessModeEnum.ODS.getMode().equals(reqVO.getAccessMode());
        if (odsMode && !DatabaseUsageEnum.PLATFORM.getType().equals(targetDatabase.getUsageType())) {
            throw exception(DATA_ACCESS_TARGET_NOT_PLATFORM);
        }

        // 组装 dbswitch 同步配置（连接信息执行时从数据源实例现取，不落库）
        AccessTaskConfig.Source source = new AccessTaskConfig.Source();
        source.setSourceType(reqVO.getSourceType());
        source.setSourceSql(sqlSource ? reqVO.getSourceSql() : null);
        source.setBeforeSqlScripts(reqVO.getSourceBeforeSqlScripts());
        source.setAfterSqlScripts(reqVO.getSourceAfterSqlScripts());
        source.setSourcePrimaryKeys(reqVO.getSourcePrimaryKeys());
        source.setTimestampColumn(reqVO.getTimestampColumn());
        source.setCursorWatermarkContract(reqVO.getCursorWatermarkContract());
        source.setCursorBootstrapAllowed(reqVO.getCursorBootstrapAllowed());
        source.setIdentitySourceQuiesced(reqVO.isIdentitySourceQuiesced());
        source.setTableReadMode(reqVO.getTableReadMode());
        if (reqVO.getBatchSize() != null && reqVO.getBatchSize() > 0) {
            source.setFetchSize(reqVO.getBatchSize());
        }
        Integer includeOrExclude = reqVO.getIncludeOrExclude() == null ? INCLUDE : reqVO.getIncludeOrExclude();
        source.setIncludeOrExclude(includeOrExclude);
        if (!sqlSource) {
            source.setSourceTableNames(selectedTables);
        }
        source.setRegexTableMapper(reqVO.getTableNameMapper());
        source.setRegexColumnMapper(reqVO.getColumnNameMapper());
        source.setMapperType(StrUtil.blankToDefault(reqVO.getMapperType(), MapperType.ALL.name()));
        source.setIncreaseColumnName(reqVO.getIncreaseColumnName());
        source.setConfigMap(reqVO.getConfigMap());

        AccessTaskConfig.Target target = new AccessTaskConfig.Target();
        target.setBeforeSqlScripts(reqVO.getTargetBeforeSqlScripts());
        target.setAfterSqlScripts(reqVO.getTargetAfterSqlScripts());
        target.setTargetDrop(reqVO.isTargetDropTable());
        target.setTargetTruncateData(reqVO.isTargetTruncateData());
        target.setSyncExist(reqVO.isTargetSyncExit());
        target.setOnlyCreate(reqVO.isTargetOnlyCreate());
        target.setIndexCreate(reqVO.isTargetIndexCreate());
        target.setSyncDefaultVal(reqVO.isSyncDefaultVal());
        target.setLowercase(reqVO.isTargetLowerCase());
        target.setUppercase(reqVO.isTargetUpperCase());
        target.setTableNameCase(reqVO.getTargetTableNameCase());
        target.setColumnNameCase(reqVO.getTargetColumnNameCase());
        target.setDistributedKeys(reqVO.getTargetDistributedKeys());
        target.setCreateTableAutoIncrement(reqVO.isTargetAutoIncrement());
        target.setPreserveIdentityValues(reqVO.isPreserveIdentityValues());
        target.setExclusiveTargetOwnership(reqVO.isExclusiveTargetOwnership());
        target.setChangeDataSync(reqVO.isTargetDataSync());
        target.setChangeDataSyncType(StrUtil.blankToDefault(reqVO.getChangeDataSyncType(), ""));
        target.setTargetTable(sqlSource ? reqVO.getTargetTable() : null);
        target.setWriteMode(reqVO.getWriteMode());
        target.setFailOnFieldReadError(reqVO.isFailOnFieldReadError());
        target.setTargetSyncOption(reqVO.getTargetSyncOption());
        target.setTablePrefix(odsMode ? ODS_TABLE_PREFIX : null);

        AccessTaskConfig properties = new AccessTaskConfig();
        properties.setSchemaVersion(reqVO.getSchemaVersion());
        List<AccessTaskConfig.Source> sourceList = new ArrayList<>(1);
        sourceList.add(source);
        properties.setSource(sourceList);
        properties.setTarget(target);
        properties.setTypeMappingAcknowledgements(reqVO.getTypeMappingAcknowledgements());
        try {
            properties = AccessTaskConfigCodec.copy(properties);
        } catch (IllegalArgumentException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "配置版本或同步选项");
        }
        normalizeNewTableWriteModeForSave(properties, sourceDatabase, targetDatabase);
        if (!properties.getTarget().getDistributedKeys().isEmpty()
                && databaseResolver.resolveProductType(targetDatabase) != ProductTypeEnum.GREENPLUM) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "分布列仅适用于 Greenplum 全量自动建表");
        }
        if (hasDorisOptions(reqVO.getConfigMap())) {
            validateDorisTarget(reqVO.getConfigMap(), reqVO.getAccessMode(),
                    databaseResolver.resolveProductType(targetDatabase));
        }
        validateMappingDecisionScope(properties, sourceDatabase, targetDatabase);
        validateHookSyntax(sourceDatabase, source.getBeforeSqlScripts(), source.getAfterSqlScripts());
        validateHookSyntax(targetDatabase, target.getBeforeSqlScripts(), target.getAfterSqlScripts());

        // 组装 DO
        return DataAccessDO.builder()
                .id(reqVO.getId())
                .taskName(reqVO.getTaskName())
                .description(reqVO.getDescription())
                .orgId(reqVO.getOrgId())
                .engineType(engineType)
                .sourceType(reqVO.getSourceType())
                .sourceDatabaseId(reqVO.getSourceDatabaseId())
                .targetDatabaseId(reqVO.getTargetDatabaseId())
                .accessMode(reqVO.getAccessMode())
                .taskType(reqVO.getTaskType())
                .cron(periodic ? reqVO.getCron() : null)
                .status(AccessReleaseStatusEnum.UNRELEASED.getStatus())
                .dataAccessJson(properties)
                .remark(reqVO.getRemark())
                .build();
    }

    /** 用户明确关闭已有表同步的普通 PostgreSQL 源全量配置，可固定到已支持目标的新表约束。 */
    private void normalizeNewTableWriteModeForSave(AccessTaskConfig config, DatabaseDO sourceDatabase,
            DatabaseDO targetDatabase) {
        var source = config.getSource().getFirst();
        var target = config.getTarget();
        boolean dorisTarget = targetDatabase.getDatabaseType() != null
                && databaseResolver.resolveProductType(targetDatabase) == ProductTypeEnum.DORIS;
        if (target.getWriteMode() != null || !Integer.valueOf(1).equals(source.getSourceType())
                || target.isSyncExist() || target.isTargetDrop() || target.isTargetTruncateData()
                || target.isChangeDataSync() || target.isOnlyCreate() || target.isIndexCreate()
                || target.isSyncDefaultVal() || target.isCreateTableAutoIncrement()
                || target.isPreserveIdentityValues() || target.isExclusiveTargetOwnership()
                || Boolean.TRUE.equals(source.getIdentitySourceQuiesced())
                || StrUtil.isNotBlank(source.getBeforeSqlScripts()) || StrUtil.isNotBlank(source.getAfterSqlScripts())
                || StrUtil.isNotBlank(target.getBeforeSqlScripts()) || StrUtil.isNotBlank(target.getAfterSqlScripts())
                || StrUtil.isNotBlank(source.getTimestampColumn()) || StrUtil.isNotBlank(source.getIncreaseColumnName())
                || CollUtil.isNotEmpty(target.getDistributedKeys())
                || CollUtil.isNotEmpty(config.getTypeMappingAcknowledgements())
                || source.getConfigMap() != null && source.getConfigMap().values().stream().anyMatch(mapping ->
                !dorisTarget && StrUtil.isNotBlank(mapping.getTargetTableComment()) || StrUtil.isNotBlank(mapping.getTimestampColumn())
                        || StrUtil.isNotBlank(mapping.getIncreaseColumnName()) || mapping.getDorisOptions() != null
                        && (!dorisTarget || mapping.getDorisOptions().partition() != null))
                || sourceDatabase.getDatabaseType() == null || targetDatabase.getDatabaseType() == null) {
            return;
        }
        if (databaseResolver.resolveProductType(sourceDatabase) == ProductTypeEnum.POSTGRESQL
                && (dorisTarget || databaseResolver.resolveProductType(targetDatabase) == ProductTypeEnum.POSTGRESQL)) {
            target.setWriteMode("CREATE_NEW");
        }
    }

    /**
     * 判断是否存在表覆盖条件。
     *
     * @param mappings 映射
     * @return 判断结果
     */
    private static boolean hasTableOverrides(Map<String, AccessTableMapping> mappings) {
        return mappings != null && mappings.values().stream().filter(Objects::nonNull).anyMatch(mapping ->
                mapping.getTargetTableName() != null || mapping.getTargetTableComment() != null || mapping.getDorisOptions() != null);
    }

    /**
     * 判断是否存在Doris选项条件。
     *
     * @param mappings 映射
     * @return 判断结果
     */
    private static boolean hasDorisOptions(Map<String, AccessTableMapping> mappings) {
        return mappings != null && mappings.values().stream().filter(Objects::nonNull)
                .anyMatch(mapping -> mapping.getDorisOptions() != null);
    }

    /**
     * 校验表覆盖来源。
     *
     * @param mappings 映射
     * @param sourceType 来源类型
     */
    private static void validateTableOverrideSource(Map<String, AccessTableMapping> mappings, Integer sourceType) {
        if (hasTableOverrides(mappings) && SourceType.SQL.getCode().equals(sourceType)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "多表建表映射仅适用于表来源");
        }
    }

    /** Doris 建表选项是入中台库的领域配置；自定义接入沿用其目标产品默认行为。 */
    private static void validateDorisTarget(Map<String, AccessTableMapping> mappings, Integer accessMode,
            ProductTypeEnum product) {
        if (hasDorisOptions(mappings) && (!AccessModeEnum.ODS.getMode()
                .equals(accessMode) || product != ProductTypeEnum.DORIS)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "分区、分桶和副本配置仅适用于 Doris 中台库");
        }
    }

    /**
     * 校验Doris列集合。
     *
     * @param mappings 映射
     * @param table 表
     * @param columns 列集合
     */
    private static void validateDorisColumns(Map<String, AccessTableMapping> mappings, String table,
            List<ColumnDescription> columns) {
        var mapping = mappings == null ? null : mappings.get(table);
        if (mapping != null && mapping.getDorisOptions() != null) {
            AccessTaskConfigMapper.toRuntimeDorisOptions(mapping.getDorisOptions())
                    .validateColumns(columns.stream().map(ColumnDescription::getFieldName).toList());
        }
    }

    /** 保存仅检查组件脚本契约，不授予治理访问权。 */
    private void validateHookSyntax(DatabaseDO database,
            String before, String after) {
        if (StrUtil.isBlank(before) && StrUtil.isBlank(after)) {
            return;
        }
        try {
            var product = databaseResolver.resolveProductType(database);
            for (String script : java.util.Arrays.asList(before, after)) {
                if (StrUtil.isNotBlank(script)) {
                    cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parseMigrationHook(product, script);
                }
            }
        } catch (RuntimeException invalid) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "该产品的同步生命周期脚本或语法不受支持");
        }
    }

    /** 决策不授予访问权；保存时绑定受管理目标，执行仍重读元数据并复核当前权限。 */
    private void validateMappingDecisionScope(AccessTaskConfig config, DatabaseDO sourceDatabase,
            DatabaseDO targetDatabase) {
        if (config.getTypeMappingAcknowledgements().isEmpty()) {
            return;
        }
        var sourceProduct = databaseResolver.resolveProductType(sourceDatabase);
        var targetProduct = databaseResolver.resolveProductType(targetDatabase);
        if (!dbSwitchRuntime.products().typeMappings().supports(sourceProduct, targetProduct)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "跨产品类型映射决策范围");
        }
        var targetConnection = databaseResolver.resolve(targetDatabase);
        requirePreviewDatabase(targetConnection, "目标");
        var runtime = AccessTaskConfigMapper.toRuntime(config, targetDatabase.getId());
        var source = runtime.getSource().getFirst();
        var target = runtime.getTarget();
        target.setTargetProductType(targetProduct);
        var sourceNames = AccessExactTableSelection.selectedNames(source);
        var selected = Integer.valueOf(1).equals(source.getIncludeOrExclude()) && !sourceNames.isEmpty()
                ? sourceNames.stream().map(table ->
                        cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(source, target, table))
                .collect(java.util.stream.Collectors.toSet()) : null;
        for (var decision : config.getTypeMappingAcknowledgements()) {
            if (!decision.targetProduct().equals(targetProduct.name())
                    || !decision.targetSchema().equals(targetConnection.getSchema())
                    || selected != null && !selected.contains(decision.targetTable())) {
                throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "决策与最终物理目标表不一致，请重新预览");
            }
        }
    }

    /**
     * API 接入：校验目标库与映射配置，序列化保存到 apiAccessJson
     */
    private DataAccessDO buildApiAccess(DataAccessSaveReqVO reqVO, Integer engineType, boolean periodic) {
        DatabaseDO targetDatabase = databaseMapper.selectById(reqVO.getTargetDatabaseId());
        if (targetDatabase == null) {
            throw exception(DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS);
        }
        if (CollUtil.isEmpty(reqVO.getApiMappings())) {
            throw exception(DATA_API_ACCESS_MAPPING_EMPTY);
        }
        for (ApiAccessMappingVO mapping : reqVO.getApiMappings()) {
            if (mapping == null || mapping.getApiConfig() == null
                    || !mapping.getApiConfig().isRequestConfigurationValid()) {
                throw exception(DATA_API_ACCESS_MAPPING_INVALID, "存在未完成接口配置的映射");
            }
            var apiConfig = mapping.getApiConfig();
            mapping.setSourceApiName(apiConfig.getApiName());
            if (CollUtil.isEmpty(apiConfig.getDataColumns())) {
                throw exception(DATA_API_ACCESS_MAPPING_INVALID, "请先测试接口并配置返回字段");
            }
            if (reqVO.isTargetDataSync() && !mapping.hasCompletePrimaryKeyMapping()) {
                throw exception(DATA_API_ACCESS_MAPPING_INVALID, "增量写入必须声明至少一个主键并完整导入所有主键字段");
            }
            if (StrUtil.isBlank(mapping.getTargetTable())) {
                throw exception(DATA_API_ACCESS_MAPPING_INVALID,
                        String.format("API【%s】未指定目标表", mapping.getSourceApiName()));
            }
            boolean hasImport = CollUtil.isNotEmpty(mapping.getColumnMaps())
                    && mapping.getColumnMaps().stream().anyMatch(column ->
                    Integer.valueOf(1).equals(column.getImportFlg())
                            && StrUtil.isNotBlank(column.getTargetColumn()));
            if (!hasImport) {
                throw exception(DATA_API_ACCESS_MAPPING_INVALID,
                        String.format("API【%s】至少需要一个导入字段并指定目标字段", mapping.getSourceApiName()));
            }
        }
        cn.hutool.json.JSONObject config = new cn.hutool.json.JSONObject();
        config.set("syncExist", reqVO.isTargetSyncExit());
        config.set("targetDrop", reqVO.isTargetDropTable());
        config.set("changeDataSync", reqVO.isTargetDataSync());
        config.set("mappings", reqVO.getApiMappings());
        return DataAccessDO.builder()
                .id(reqVO.getId())
                .taskName(reqVO.getTaskName())
                .description(reqVO.getDescription())
                .orgId(reqVO.getOrgId())
                .engineType(engineType)
                .taskType(reqVO.getTaskType())
                .cron(periodic ? reqVO.getCron() : null)
                .status(AccessReleaseStatusEnum.UNRELEASED.getStatus())
                .targetDatabaseId(reqVO.getTargetDatabaseId())
                .apiAccessJson(config.toString())
                .remark(reqVO.getRemark())
                .build();
    }

    /**
     * DO → RespVO：回显同步配置，并补充数据源、单位、发布人和创建人名称
     */
    private List<DataAccessRespVO> convertToRespList(List<DataAccessDO> list) {
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Set<Long> databaseIds = convertSet(list, DataAccessDO::getSourceDatabaseId);
        databaseIds.addAll(convertSet(list, DataAccessDO::getTargetDatabaseId));
        databaseIds.remove(null);
        Map<Long, DatabaseDO> databaseMap = CollUtil.isEmpty(databaseIds)
                ? Collections.emptyMap()
                : convertMap(databaseMapper.selectByIds(databaseIds), DatabaseDO::getId);
        Set<Long> orgIds = convertSet(list, DataAccessDO::getOrgId);
        Map<Long, DeptRespDTO> deptMap = CollUtil.isEmpty(orgIds)
                ? Collections.emptyMap() : deptApi.getDeptMap(orgIds);
        Set<Long> userIds = convertSet(list, DataAccessDO::getReleaseUserId);
        Map<String, Long> creatorIds = new HashMap<>();
        for (DataAccessDO dataAccess : list) {
            try {
                Long creatorId = NumberUtils.parseLong(dataAccess.getCreator());
                if (creatorId != null) {
                    creatorIds.put(dataAccess.getCreator(), creatorId);
                    userIds.add(creatorId);
                }
            } catch (NumberFormatException ignored) {
                // 系统标记或无效的历史审计值不参与用户查询，避免阻断列表回显。
            }
        }
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap() : adminUserApi.getUserMap(userIds);

        return convertList(list, dataAccess -> {
            DataAccessRespVO vo = BeanUtils.toBean(dataAccess, DataAccessRespVO.class);
            if (!AccessEngineTypeEnum.SEATUNNEL.getType().equals(dataAccess.getEngineType())) {
                vo.setSeatunnelConfig(null);
                vo.setConfigFormat(null);
            }
            DatabaseDO sourceDatabase = databaseMap.get(dataAccess.getSourceDatabaseId());
            if (sourceDatabase != null) {
                vo.setSourceDatabaseName(sourceDatabase.getName());
            }
            DatabaseDO targetDatabase = databaseMap.get(dataAccess.getTargetDatabaseId());
            if (targetDatabase != null) {
                vo.setTargetDatabaseName(targetDatabase.getName());
            }
            DeptRespDTO dept = dataAccess.getOrgId() == null ? null : deptMap.get(dataAccess.getOrgId());
            if (dept != null) {
                vo.setOrgName(dept.getName());
            }
            AdminUserRespDTO releaseUser = dataAccess.getReleaseUserId() == null
                    ? null : userMap.get(dataAccess.getReleaseUserId());
            if (releaseUser != null) {
                vo.setReleaseUserName(releaseUser.getNickname());
            }
            Long creatorId = creatorIds.get(dataAccess.getCreator());
            AdminUserRespDTO creator = creatorId == null ? null : userMap.get(creatorId);
            if (creator != null) {
                vo.setCreatorName(creator.getNickname());
            }
            if (AccessEngineTypeEnum.API_ACCESS.getType().equals(dataAccess.getEngineType())) {
                fillApiAccessConfig(vo, dataAccess.getApiAccessJson());
            } else if (AccessEngineTypeEnum.DBSWITCH.getType().equals(dataAccess.getEngineType())) {
                fillConfig(vo, dataAccess.getDataAccessJson());
            }
            return vo;
        });
    }

    /**
     * API 接入配置回显：apiAccessJson → apiMappings 与写入选项
     */
    private void fillApiAccessConfig(DataAccessRespVO vo, String apiAccessJson) {
        if (StrUtil.isBlank(apiAccessJson)) {
            return;
        }
        cn.hutool.json.JSONObject config = cn.hutool.json.JSONUtil.parseObj(apiAccessJson);
        vo.setTargetSyncExit(config.getBool("syncExist", true));
        vo.setTargetDropTable(config.getBool("targetDrop", false));
        vo.setTargetDataSync(config.getBool("changeDataSync", false));
        cn.hutool.json.JSONArray mappings = config.getJSONArray("mappings");
        if (mappings != null) {
            vo.setApiMappings(cn.hutool.json.JSONUtil.toList(mappings, ApiAccessMappingVO.class));
        }
    }

    /**
     * 补充数据同步任务的配置。
     *
     * @param vo 展示或请求数据
     * @param properties 组件配置
     */
    private void fillConfig(DataAccessRespVO vo, AccessTaskConfig properties) {
        if (properties == null || CollUtil.isEmpty(properties.getSource())) {
            return;
        }
        AccessTaskConfig.Source source = properties.getSource().get(0);
        AccessTaskConfig.Target target = properties.getTarget();
        vo.setSchemaVersion(properties.getSchemaVersion());
        vo.setTypeMappingAcknowledgements(properties.getTypeMappingAcknowledgements());
        vo.setCursorWatermarkContract(source.getCursorWatermarkContract());
        vo.setCursorBootstrapAllowed(source.getCursorBootstrapAllowed());
        vo.setIdentitySourceQuiesced(Boolean.TRUE.equals(source.getIdentitySourceQuiesced()));
        vo.setTableReadMode(source.getTableReadMode());
        vo.setIncludeOrExclude(source.getIncludeOrExclude());
        vo.setSourceSelectedTables(SourceType.TABLE.getCode().equals(source.getSourceType())
                ? AccessExactTableSelection.selectedNames(source) : List.of());
        vo.setBatchSize(source.getFetchSize());
        vo.setMapperType(source.getMapperType());
        vo.setTableNameMapper(source.getRegexTableMapper());
        vo.setColumnNameMapper(source.getRegexColumnMapper());
        vo.setConfigMap(source.getConfigMap());
        vo.setSourceSql(source.getSourceSql());
        vo.setSourceBeforeSqlScripts(source.getBeforeSqlScripts());
        vo.setSourceAfterSqlScripts(source.getAfterSqlScripts());
        vo.setSourcePrimaryKeys(source.getSourcePrimaryKeys());
        vo.setIncreaseColumnName(source.getIncreaseColumnName());
        vo.setTimestampColumn(source.getTimestampColumn());
        if (target != null) {
            vo.setTargetBeforeSqlScripts(target.getBeforeSqlScripts());
            vo.setTargetAfterSqlScripts(target.getAfterSqlScripts());
            vo.setTargetTable(target.getTargetTable());
            vo.setWriteMode(target.getWriteMode());
            vo.setFailOnFieldReadError(target.isFailOnFieldReadError());
            vo.setTargetSyncOption(target.getTargetSyncOption());
            vo.setTargetOnlyCreate(target.isOnlyCreate());
            vo.setTargetSyncExit(target.isSyncExist());
            vo.setTargetDropTable(target.isTargetDrop());
            vo.setTargetTruncateData(target.isTargetTruncateData());
            vo.setPreserveIdentityValues(target.isPreserveIdentityValues());
            vo.setExclusiveTargetOwnership(target.isExclusiveTargetOwnership());
            vo.setTargetDataSync(target.isChangeDataSync());
            vo.setChangeDataSyncType(target.getChangeDataSyncType());
            vo.setTargetIndexCreate(target.isIndexCreate());
            vo.setSyncDefaultVal(target.isSyncDefaultVal());
            vo.setTargetLowerCase(target.isLowercase());
            vo.setTargetUpperCase(target.isUppercase());
            vo.setTargetTableNameCase(target.getTableNameCase());
            vo.setTargetColumnNameCase(target.getColumnNameCase());
            vo.setTargetDistributedKeys(List.copyOf(target.getDistributedKeys()));
            vo.setTargetAutoIncrement(target.isCreateTableAutoIncrement());
        }
    }

}
