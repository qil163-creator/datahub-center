package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.*;
import cn.iocoder.yudao.module.supply.dal.dataobject.apicategory.ApiCategoryDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;

/**
 * 数据服务-API 服务 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DataApiServiceImpl implements DataApiService {

    @Resource
    private DataApiMapper dataApiMapper;

    @Resource
    private ApiCategoryMapper apiCategoryMapper;

    @Resource
    private AppApiMapper appApiMapper;

    @Resource
    private SupplyDatabaseResolver databaseResolver;

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DataApiExecutor dataApiExecutor;

    @Resource
    private SupplyDataApiProperties dataApiProperties;

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    @Resource
    private DataApiReleaseMapper dataApiReleaseMapper;

    @Resource
    private DataApiEtlBatchService etlBatches;

    // ==================== CRUD ====================

    /**
     * 创建 API 服务
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDataApi(DataApiSaveReqVO createReqVO) {
        validatePathUnique(null, createReqVO.getPath(), createReqVO.getVersion());
        DataApiDO dataApi = convertToDO(createReqVO);
        // 上线必须走带发布权限与控制版本的发布入口。
        dataApi.setStatus(DataApiDO.STATUS_OFFLINE);
        if (dataApi.getProviderDeptId() == null) {
            dataApi.setProviderDeptId(SecurityFrameworkUtils.getLoginUserDeptId());
        }
        if (dataApi.getOwnerUserId() == null) {
            dataApi.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());
        }
        dataApi.setDraftRevision(0);
        dataApi.setRowVersion(0);
        dataApiMapper.insert(dataApi);
        return dataApi.getId();
    }

    /**
     * 更新 API 服务（上线状态不可修改）
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDataApi(DataApiSaveReqVO updateReqVO) {
        DataApiDO old = validateDataApiExistsForUpdate(updateReqVO.getId());
        if (old.getStatus() != null && old.getStatus() == DataApiDO.STATUS_ONLINE) {
            DataApiDO draft = convertToDO(updateReqVO);
            DataApiDO update = new DataApiDO();
            update.setId(old.getId());
            update.setDraftJson(JsonUtils.toJsonString(DataApiContract.snapshot(draft)));
            update.setDraftRevision((old.getDraftRevision() == null ? 0 : old.getDraftRevision()) + 1);
            dataApiMapper.updateById(update);
            return;
        }
        validatePathUnique(updateReqVO.getId(), updateReqVO.getPath(), updateReqVO.getVersion());
        DataApiDO update = convertToDO(updateReqVO);
        update.setStatus(null);
        if (update.getProviderDeptId() == null) {
            update.setProviderDeptId(SecurityFrameworkUtils.getLoginUserDeptId());
        }
        dataApiMapper.updateById(update);
    }

    /**
     * 上线/下线 API 服务
     *
     * @param id 记录编号
     * @param status 目标状态
     * @param expectedRowVersion 预期行版本
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDataApiStatus(Long id, Integer status, Integer expectedRowVersion) {
        var prepared = Integer.valueOf(DataApiDO.STATUS_ONLINE).equals(status) ? prepareEtl(id, null) : null;
        DataApiDO dataApi = validateDataApiExistsForUpdate(id);
        assertRowVersion(dataApi, expectedRowVersion);
        if (Integer.valueOf(DataApiDO.STATUS_ONLINE).equals(status)) {
            publishLocked(dataApi, prepared);
            return;
        }
        if (!Integer.valueOf(DataApiDO.STATUS_OFFLINE).equals(status)) {
            throw exception(DATA_API_CONFIG_INVALID, "只允许上线或下线");
        }
        DataApiDO update = new DataApiDO();
        update.setId(id);
        update.setStatus(status);
        update.setRowVersion((dataApi.getRowVersion() == null ? 0 : dataApi.getRowVersion()) + 1);
        dataApiMapper.updateById(update);
    }

    /**
     * 删除 API 服务（上线中或已被应用授权不可删除）
     *
     * @param id 记录编号
     */
    @Override
    public void deleteDataApi(Long id) {
        DataApiDO dataApi = validateDataApiExists(id);
        if (dataApi.getStatus() != null && dataApi.getStatus() == DataApiDO.STATUS_ONLINE) {
            throw exception(DATA_API_RELEASED_CANNOT_MODIFY);
        }
        // 退役检查同时覆盖旧协议聚合与 v2 来源账本；仍有未撤销来源的 API 不能删除。
        if (appApiMapper.countOpenGrantReferences(
                id) > 0) {
            throw exception(DATA_API_AUTHORIZED_CANNOT_DELETE);
        }
        dataApiMapper.deleteById(id);
    }

    /**
     * 获得 API 服务
     *
     * @param id 记录编号
     * @return 数据 API响应数据
     */
    @Override
    public DataApiRespVO getDataApi(Long id) {
        return convertToResp(validateDataApiExists(id));
    }

    /**
     * 获得 API 服务分页（类目过滤含子级）
     *
     * @param pageReqVO 分页查询条件
     * @return  API 服务分页（类目过滤含子级）结果
     */
    @Override
    public PageResult<DataApiRespVO> getDataApiPage(DataApiPageReqVO pageReqVO) {
        Collection<Long> categoryIds = resolveCategoryIds(pageReqVO.getCategoryId());
        PageResult<DataApiDO> pageResult = dataApiMapper.selectPage(pageReqVO, categoryIds);
        return new PageResult<>(convertList(pageResult.getList(), this::convertToResp),
                pageResult.getTotal());
    }

    /**
     * 获得 API 服务精简列表（接口测试/应用授权下拉用）
     *
     * @param status 目标状态
     * @param purpose 用途
     * @return  API 服务精简列表（接口测试/应用授权下拉用）结果集合
     */
    @Override
    public List<DataApiSimpleRespVO> getDataApiSimpleList(Integer status, String purpose) {
        String scene = StrUtil.blankToDefault(purpose, "query").toLowerCase(Locale.ROOT);
        Integer effectiveStatus = status;
        switch (scene) {
            case "grant" -> {
                if (!securityFrameworkService.hasAnyPermissions("supply:app:grant", "supply:app:update")) {
                    throw new org.springframework.security.access.AccessDeniedException("没有授权选择权限");
                }
                effectiveStatus = DataApiDO.STATUS_ONLINE;
            }
            case "test" -> {
                if (!securityFrameworkService.hasAnyPermissions("supply:data-api:test", "supply:data-api:invoke")) {
                    throw new org.springframework.security.access.AccessDeniedException("没有试调选择权限");
                }
            }
            default -> {
                if (!securityFrameworkService.hasPermission("supply:data-api:query")) {
                    throw new org.springframework.security.access.AccessDeniedException("没有查询权限");
                }
            }
        }
        return convertList(dataApiMapper.selectSimpleList(effectiveStatus), this::convertToSimple);
    }

    /**
     * 转换目标精简。
     *
     * @param dataApi 数据 API
     * @return 数据 API精简响应数据
     */
    private DataApiSimpleRespVO convertToSimple(DataApiDO dataApi) {
        DataApiSimpleRespVO vo = new DataApiSimpleRespVO();
        vo.setId(dataApi.getId());
        vo.setName(dataApi.getName());
        vo.setCategoryId(dataApi.getCategoryId());
        vo.setVersion(dataApi.getVersion());
        vo.setStatus(dataApi.getStatus());
        vo.setMethod(dataApi.getMethod());
        vo.setPath(dataApi.getPath());
        vo.setProviderDeptId(dataApi.getProviderDeptId());
        vo.setActiveReleaseId(dataApi.getActiveReleaseId());
        vo.setDraftRevision(dataApi.getDraftRevision());
        vo.setUnpublishedDraft(StrUtil.isNotBlank(dataApi.getDraftJson()));
        return vo;
    }

    /**
     * 读取 API 持久化记录；不存在时返回 {@code null}。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    @Override
    public DataApiDO getDataApiEntity(Long id) {
        return id == null ? null : dataApiMapper.selectById(id);
    }

    /**
     * 当前发布快照；不把未发布草稿作为授权证据。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    @Override
    public DataApiDO getPublishedDataApiEntity(Long id) {
        DataApiDO api = getDataApiEntity(id);
        return api == null ? null : publishedRuntime(api);
    }

    /**
     * 已上线、单表向导、查询类 API，供资产申请核验绑定。
     *
     * @param databaseId 数据源编号
     * @return 已上线、单表向导、查询类 API，供资产申请核验绑定结果集合
     */
    @Override
    public List<DataApiDO> listOnlineTableQueryApis(Long databaseId) {
        if (databaseId == null) {
            return List.of();
        }
        return dataApiMapper.selectOnlineTableQueryList(databaseId);
    }

    /**
     * 类目过滤：包含选中类目及其全部子孙
     */
    private Collection<Long> resolveCategoryIds(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        List<ApiCategoryDO> all = apiCategoryMapper.selectList();
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(categoryId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ApiCategoryDO category : all) {
                if (category.getParentId() != null && ids.contains(category.getParentId())
                        && ids.add(category.getId())) {
                    changed = true;
                }
            }
        }
        return ids;
    }

    /**
     * 转换目标。
     *
     * @param reqVO 请求参数
     * @return 数据 API
     */
    private DataApiDO convertToDO(DataApiSaveReqVO reqVO) {
        DataApiDO dataApi = BeanUtils.toBean(reqVO, DataApiDO.class);
        dataApi.setRequestParamsJson(JsonUtils.toJsonString(
                reqVO.getRequestParams() == null ? List.of() : reqVO.getRequestParams()));
        dataApi.setResponseFieldsJson(JsonUtils.toJsonString(
                reqVO.getResponseFields() == null ? List.of() : reqVO.getResponseFields()));
        dataApi.setApiType(StrUtil.blankToDefault(dataApi.getApiType(), "QUERY").toUpperCase(Locale.ROOT));
        dataApi.setAuthType(
                StrUtil.blankToDefault(dataApi.getAuthType(), DataApiDO.AUTH_TYPE_NONE).toUpperCase(Locale.ROOT));
        return dataApi;
    }

    /**
     * 转换目标。
     *
     * @param dataApi 数据 API
     * @return 数据 API响应数据
     */
    private DataApiRespVO convertToResp(DataApiDO dataApi) {
        DataApiDO view = overlayDraft(dataApi);
        DataApiRespVO vo = BeanUtils.toBean(view, DataApiRespVO.class);
        vo.setRequestParams(StrUtil.isBlank(view.getRequestParamsJson()) ? List.of()
                : JsonUtils.parseObject(view.getRequestParamsJson(), new TypeReference<>() {
        }));
        vo.setResponseFields(StrUtil.isBlank(view.getResponseFieldsJson()) ? List.of()
                : JsonUtils.parseObject(view.getResponseFieldsJson(), new TypeReference<>() {
        }));
        vo.setUnpublishedDraft(StrUtil.isNotBlank(dataApi.getDraftJson()));
        vo.setActiveReleaseId(dataApi.getActiveReleaseId());
        vo.setRowVersion(dataApi.getRowVersion() == null ? 0 : dataApi.getRowVersion());
        vo.setContractHash(dataApi.getContractHash());
        vo.setDraftRevision(dataApi.getDraftRevision());
        return vo;
    }

    /**
     * 获取数据 API的覆盖配置草稿。
     *
     * @param dataApi 数据 API
     * @return 数据 API
     */
    private DataApiDO overlayDraft(DataApiDO dataApi) {
        if (StrUtil.isBlank(dataApi.getDraftJson())) {
            return dataApi;
        }
        DataApiDO view = BeanUtils.toBean(dataApi, DataApiDO.class);
        DataApiContract.applySnapshot(view, parseSnapshot(dataApi.getDraftJson()));
        return view;
    }

    /**
     * 解析快照。
     *
     * @param json JSON 内容
     * @return 处理后的键值映射
     */
    private Map<String, Object> parseSnapshot(String json) {
        Map<String, Object> snapshot = JsonUtils.parseObjectQuietly(json, new TypeReference<>() {
        });
        if (snapshot == null) {
            throw exception(DATA_API_CONFIG_INVALID, "发布快照无法读取");
        }
        return snapshot;
    }

    /**
     * 校验数据 API存在性。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    private DataApiDO validateDataApiExists(Long id) {
        DataApiDO dataApi = dataApiMapper.selectById(id);
        if (dataApi == null) {
            throw exception(DATA_API_NOT_EXISTS);
        }
        return dataApi;
    }

    /**
     * 校验数据 API存在性对应更新。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    private DataApiDO validateDataApiExistsForUpdate(Long id) {
        DataApiDO dataApi = dataApiMapper.selectByIdForUpdate(id);
        if (dataApi == null) {
            throw exception(DATA_API_NOT_EXISTS);
        }
        return dataApi;
    }

    /**
     * 校验路径唯一性。
     *
     * @param id 记录编号
     * @param path 路径
     * @param version 版本
     */
    private void validatePathUnique(Long id, String path, String version) {
        DataApiDO exists = dataApiMapper.selectByPathAndVersion(path, version);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(DATA_API_PATH_DUPLICATE);
        }
    }

    /**
     * 校验配置完整。
     *
     * @param dataApi 数据 API
     */
    private void validateConfigComplete(DataApiDO dataApi) {
        String apiType = StrUtil.blankToDefault(dataApi.getApiType(), "QUERY").toUpperCase(Locale.ROOT);
        if (!Set.of("QUERY", "INSERT", "UPDATE", "DELETE", "MULTI").contains(apiType)) {
            throw exception(DATA_API_CONFIG_INVALID, "接口类型不合法");
        }
        String authType = StrUtil.blankToDefault(dataApi.getAuthType(), DataApiDO.AUTH_TYPE_NONE)
                .toUpperCase(Locale.ROOT);
        if (!Set.of(DataApiDO.AUTH_TYPE_NONE, DataApiDO.AUTH_TYPE_APPKEY, DataApiDO.AUTH_TYPE_SIGN)
                .contains(authType)) {
            throw exception(DATA_API_CONFIG_INVALID, "认证方式不合法");
        }
        // 写入型和转发型接口必须绑定应用身份，避免匿名请求绕过应用授权或形成匿名代理。
        if ((!DataApiDO.API_TYPE_QUERY.equals(apiType)
                || DataApiDO.CONFIG_TYPE_FORWARD.equalsIgnoreCase(dataApi.getConfigType()))
                && DataApiDO.AUTH_TYPE_NONE.equals(authType)) {
            throw exception(DATA_API_CONFIG_INVALID, "写入或转发接口不能使用 NONE 认证");
        }
        if (DataApiOperationService.requiresOperation(dataApi)
                && !DataApiDO.AUTH_TYPE_SIGN.equals(authType)) {
            throw exception(DATA_API_CONFIG_INVALID,
                    "写入及非GET转发必须使用 SIGN 认证；调用方须使用 v2 签名和稳定业务操作编号");
        }
        if (StrUtil.isNotBlank(dataApi.getRequestParamsJson())) {
            List<DataApiSaveReqVO.ParamItem> parameters = JsonUtils.parseObject(
                    dataApi.getRequestParamsJson(), new TypeReference<>() {
                    });
            if (parameters.stream().anyMatch(parameter -> DataApiOperationService.KEY_PARAM.equals(parameter.getName())
                    || DataApiOperationService.MODE_PARAM.equals(parameter.getName())
                    || "_consumeKey".equals(parameter.getName()) || "_consumeMode".equals(parameter.getName()))) {
                throw exception(DATA_API_CONFIG_INVALID,
                        "_operationKey/_operationMode/_consumeKey/_consumeMode 为网关保留参数，不能定义为SQL参数");
            }
        }
        switch (StrUtil.nullToEmpty(dataApi.getConfigType())) {
            case DataApiDO.CONFIG_TYPE_ETL_BATCH -> {
                DataApiEtlBatchService.validateMode(dataApi);
                if (dataApi.getEtlBatchHash() == null || !dataApi.getEtlBatchHash().matches("[0-9a-f]{64}")
                        || dataApi.getDatabaseId() == null || StrUtil.isBlank(
                        dataApi.getEtlSchemaName()) || StrUtil.isBlank(dataApi.getTableName())) {
                    throw exception(DATA_API_CONFIG_INVALID, "ETL 批次发布绑定不完整");
                }
            }
            case DataApiDO.CONFIG_TYPE_TABLE -> {
                if (dataApi.getDatabaseId() == null || StrUtil.isBlank(dataApi.getTableName())) {
                    throw exception(DATA_API_CONFIG_INVALID, "请先在参数配置中选择数据源与数据表");
                }
            }
            case DataApiDO.CONFIG_TYPE_SQL -> {
                if (dataApi.getDatabaseId() == null || StrUtil.isBlank(dataApi.getSqlText())) {
                    throw exception(DATA_API_CONFIG_INVALID, "请先在参数配置中配置数据源与 SQL 脚本");
                }
            }
            case DataApiDO.CONFIG_TYPE_FORWARD -> {
                if (StrUtil.isBlank(dataApi.getForwardUrl())) {
                    throw exception(DATA_API_CONFIG_INVALID, "请先在参数配置中配置转发地址");
                }
                DataApiExecutor.validateForwardUrl(dataApi.getForwardUrl(), dataApiProperties);
            }
            default -> throw exception(DATA_API_CONFIG_INVALID, "配置方式不合法");
        }
        if (StrUtil.isNotBlank(dataApi.getResponseFieldsJson())) {
            List<DataApiSaveReqVO.FieldItem> fields = JsonUtils.parseObject(
                    dataApi.getResponseFieldsJson(), new TypeReference<>() {
                    });
            DataApiExecutor.assertUniqueOutputNames(fields);
        }
    }

    // ==================== 数据源元数据 ====================

    /**
     * 数据源精简列表（向导下拉用，不含连接凭据）
     *
     * @return 数据源列表
     */
    @Override
    public List<SupplyDatabaseSimpleRespVO> getDatabaseList() {
        return databaseResolver.getDatabaseList();
    }

    /**
     * 对外网关基址；未配置时返回空，调用方不得猜测。
     *
     * @return 公开基础URL
     */
    @Override
    public String getPublicBaseUrl() {
        return StrUtil.nullToEmpty(dataApiProperties.getPublicBaseUrl()).strip();
    }

    /**
     * 数据源下的表名列表（向导下拉用）
     *
     * @param databaseId 数据源编号
     * @return 数据源下的表名列表（向导下拉用）结果集合
     */
    @Override
    public List<String> getTableNameList(Long databaseId) {
        return databaseResolver.getTableNameList(databaseId);
    }

    /**
     * 数据表列元数据（向导选择请求参数/返回字段用）
     *
     * @param databaseId 数据源编号
     * @param tableName 表名
     * @return 数据表列元数据（向导选择请求参数/返回字段用）结果集合
     */
    @Override
    public List<TableColumnRespVO> getTableColumns(Long databaseId, String tableName) {
        SupplyDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(databaseId);
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(
                conn.getProductType());
        List<ColumnDescription> descriptions = metaDataService.queryTableColumnMeta(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), tableName);
        List<String> pks = metaDataService.queryTablePrimaryKeys(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), tableName);
        List<TableColumnRespVO> result = new ArrayList<>();
        for (ColumnDescription description : descriptions) {
            ColumnMetaData meta = description.getMetaData();
            TableColumnRespVO vo = new TableColumnRespVO();
            vo.setName(description.getFieldName());
            vo.setComment(description.getRemarks());
            vo.setDataType(resolveTypeName(meta.getType()));
            vo.setLength(meta.getLength());
            vo.setScale(meta.getPrecision());
            boolean pk = pks != null && pks.contains(description.getFieldName());
            vo.setPk(pk ? 1 : 0);
            vo.setNullable(pk || !description.isNullable() ? 0 : 1);
            result.add(vo);
        }
        return result;
    }

    /**
     * JDBC 类型码转可读类型名
     */
    private String resolveTypeName(int jdbcType) {
        try {
            return java.sql.JDBCType.valueOf(jdbcType).getName();
        } catch (Exception ignored) {
            return String.valueOf(jdbcType);
        }
    }

    // ==================== 执行 ====================

    /**
     * 草稿只读预览（配置可未保存）
     *
     * @param testReqVO 测试请求参数
     * @param callerIp 调用方 IP
     * @return 数据 API执行响应数据
     */
    @Override
    public DataApiExecuteRespVO testDataApi(DataApiTestReqVO testReqVO, String callerIp) {
        DataApiDO dataApi = convertToDO(testReqVO.getApi());
        if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(dataApi.getConfigType())) {
            dataApi = DataApiEtlBatchService.applyPrepared(dataApi, etlBatches.prepare(dataApi, false));
        }
        // 草稿预览使用提交配置；客户端 API ID 不作为缓存或已发布对象身份。
        Long submittedId = dataApi.getId();
        dataApi.setId(null);
        DataApiExecuteRespVO result = dataApiExecutor.execute(dataApi, testReqVO.getParams(),
                testReqVO.getPageNo(), testReqVO.getPageSize(), callerIp, false, true,
                null, true, DataApiExecuteMode.DRAFT_READONLY_PREVIEW);
        dataApi.setId(submittedId);
        return result;
    }

    /**
     * 把当前草稿或在线配置发布为不可变快照并切换生效指针。
     *
     * @param id 记录编号
     * @param expectedRowVersion 预期行版本
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishDataApi(Long id, Integer expectedRowVersion) {
        var prepared = prepareEtl(id, null);
        DataApiDO locked = validateDataApiExistsForUpdate(id);
        assertRowVersion(locked, expectedRowVersion);
        return publishLocked(locked, prepared);
    }

    /**
     * 在当前合同范围内回滚到既有快照。
     *
     * @param id 记录编号
     * @param releaseId 发布编号
     * @param expectedRowVersion 预期行版本
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackDataApi(Long id, Long releaseId, Integer expectedRowVersion) {
        var prepared = prepareEtl(id, releaseId);
        DataApiDO locked = validateDataApiExistsForUpdate(id);
        assertRowVersion(locked, expectedRowVersion);
        DataApiReleaseDO release = dataApiReleaseMapper.selectById(releaseId);
        if (release == null || !id.equals(release.getApiId())) {
            throw exception(DATA_API_RELEASE_NOT_EXISTS);
        }
        if (StrUtil.isBlank(release.getConfigJson())) {
            throw exception(DATA_API_ROLLBACK_DENIED, "快照配置为空");
        }
        DataApiDO source = BeanUtils.toBean(locked, DataApiDO.class);
        DataApiContract.applySnapshot(source, parseSnapshot(release.getConfigJson()));
        source = DataApiEtlBatchService.applyPrepared(source, prepared);
        validateConfigComplete(source);
        applyPublishedRow(locked, source, release);
    }

    /**
     * 发布历史
     *
     * @param apiId API 编号
     * @return 发布历史结果集合
     */
    @Override
    public List<DataApiReleaseRespVO> listReleases(Long apiId) {
        validateDataApiExists(apiId);
        DataApiDO api = dataApiMapper.selectById(apiId);
        Long activeId = api == null ? null : api.getActiveReleaseId();
        return convertList(dataApiReleaseMapper.selectListByApiId(apiId), release -> {
            DataApiReleaseRespVO vo = BeanUtils.toBean(release, DataApiReleaseRespVO.class);
            vo.setActive(activeId != null && activeId.equals(release.getId()));
            return vo;
        });
    }

    /**
     * 发布已锁定。
     *
     * @param locked 已锁定
     * @param prepared 已准备的执行配置或记录
     * @return 生成或保存的记录编号
     */
    private Long publishLocked(DataApiDO locked, DataApiEtlBatchService.Prepared prepared) {
        DataApiDO source = DataApiEtlBatchService.applyPrepared(overlayDraft(locked), prepared);
        validateConfigComplete(source);
        return persistRelease(locked, source);
    }

    /**
     * 准备ETL。
     *
     * @param id 记录编号
     * @param releaseId 发布编号
     * @return 已准备
     */
    private DataApiEtlBatchService.Prepared prepareEtl(Long id, Long releaseId) {
        // 非锁定观察只用于新模式预检，锁内仍比较观察摘要和控制版本。
        var observed = dataApiMapper.selectById(id);
        if (observed == null) {
            return null;
        }
        var source = overlayDraft(observed);
        if (releaseId != null) {
            var release = dataApiReleaseMapper.selectById(releaseId);
            if (release == null || !Objects.equals(id, release.getApiId())) {
                throw exception(DATA_API_RELEASE_NOT_EXISTS);
            }
            source = BeanUtils.toBean(observed, DataApiDO.class);
            DataApiContract.applySnapshot(source, parseSnapshot(release.getConfigJson()));
        }
        return DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(source.getConfigType()) ? etlBatches.prepare(source,
                releaseId != null) : null;
    }

    /**
     * 持久化发布。
     *
     * @param locked 已锁定
     * @param source 来源数据
     * @return 记录编号
     */
    private Long persistRelease(DataApiDO locked, DataApiDO source) {
        DataApiReleaseDO release = new DataApiReleaseDO();
        release.setApiId(locked.getId());
        release.setRevision(dataApiReleaseMapper.selectMaxRevision(locked.getId()) + 1);
        release.setApiVersion(source.getVersion());
        release.setConfigJson(JsonUtils.toJsonString(DataApiContract.snapshot(source)));
        release.setConfigHash(DataApiContract.configHash(source));
        release.setContractHash(DataApiContract.contractHash(source));
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        release.setPublisher(userId == null ? null : String.valueOf(userId));
        release.setPublishTime(LocalDateTime.now());
        dataApiReleaseMapper.insert(release);
        applyPublishedRow(locked, source, release);
        return release.getId();
    }

    /**
     * 应用已发布行。
     *
     * @param locked 已锁定
     * @param source 来源数据
     * @param release 发布
     */
    private void applyPublishedRow(DataApiDO locked, DataApiDO source, DataApiReleaseDO release) {
        DataApiDO update = new DataApiDO();
        update.setId(locked.getId());
        DataApiContract.applySnapshot(update, DataApiContract.snapshot(source));
        update.setStatus(DataApiDO.STATUS_ONLINE);
        update.setActiveReleaseId(release.getId());
        update.setContractHash(release.getContractHash());
        update.setRowVersion((locked.getRowVersion() == null ? 0 : locked.getRowVersion()) + 1);
        if (locked.getProviderDeptId() == null) {
            update.setProviderDeptId(SecurityFrameworkUtils.getLoginUserDeptId());
        }
        if (locked.getOwnerUserId() == null) {
            update.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());
        }
        dataApiMapper.updateById(update);
        dataApiMapper.clearDraftJson(locked.getId());
    }

    /**
     * 确认行版本。
     *
     * @param locked 已锁定
     * @param expectedRowVersion 预期行版本
     */
    private void assertRowVersion(DataApiDO locked, Integer expectedRowVersion) {
        if (expectedRowVersion == null) {
            throw exception(DATA_API_PUBLISH_CONFLICT);
        }
        int current = locked.getRowVersion() == null ? 0 : locked.getRowVersion();
        if (!expectedRowVersion.equals(current)) {
            throw exception(DATA_API_PUBLISH_CONFLICT);
        }
    }

    /**
     * 记录已发布运行时。
     *
     * @param api 本次使用的 API 定义
     * @return 数据 API
     */
    DataApiDO publishedRuntime(DataApiDO api) {
        if (api.getActiveReleaseId() == null) {
            return api;
        }
        DataApiReleaseDO release = dataApiReleaseMapper.selectById(api.getActiveReleaseId());
        if (release == null || StrUtil.isBlank(release.getConfigJson())) {
            throw exception(DATA_API_RELEASE_NOT_EXISTS);
        }
        DataApiDO runtime = BeanUtils.toBean(api, DataApiDO.class);
        DataApiContract.applySnapshot(runtime, parseSnapshot(release.getConfigJson()));
        runtime.setId(api.getId());

        runtime.setStatus(api.getStatus());
        runtime.setCategoryId(api.getCategoryId());
        runtime.setActiveReleaseId(release.getId());
        runtime.setContractHash(release.getContractHash());
        return runtime;
    }

    /**
     * 在线签名。v1 保持旧协议；v2 使用 HMAC-SM3。
     *
     * @param reqVO 请求参数
     * @return 计算后的摘要或签名字符串
     */
    @Override
    public String sign(DataApiSignReqVO reqVO) {
        if (DataApiSignatureV2.VERSION.equalsIgnoreCase(reqVO.getSignVersion())) {
            byte[] body = reqVO.getBody() == null ? new byte[0]
                    : reqVO.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return DataApiSignatureV2.sign(reqVO.getAppSecret(), reqVO.getMethod(), reqVO.getApiPath(),
                    reqVO.getQueryString(), body, reqVO.getAppKey(), reqVO.getTimestamp(),
                    reqVO.getNonce(), reqVO.getContentType(), reqVO.getKeyVersion());
        }
        return DataApiGatewayService.sign(reqVO.getAppKey(), reqVO.getAppSecret(), reqVO.getMethod(),
                reqVO.getApiPath(), reqVO.getQueryString(), reqVO.getBody(),
                reqVO.getTimestamp(), reqVO.getNonce());
    }

}
