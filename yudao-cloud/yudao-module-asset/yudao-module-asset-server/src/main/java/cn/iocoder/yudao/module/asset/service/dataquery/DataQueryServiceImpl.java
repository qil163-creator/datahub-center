package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryColumnRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryDatasourceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.asset.service.market.MarketQuerySnapshot;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_SQL_REJECTED;

@Service
@Validated
@Slf4j
public class DataQueryServiceImpl implements DataQueryService {

    @Resource
    private cn.iocoder.yudao.module.asset.service.market.MarketConsumptionService consumptionService;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private SafetySecurityApi safetySecurityApi;

    @Resource
    private AssetAccessGuard assetAccessGuard;

    @Resource
    private MarketAccessService marketAccessService;

    /**
     * 获得可查询的数据源列表
     *
     *  @return 数据源列表
     */
    @Override
    public List<DataQueryDatasourceRespVO> getDatasourceList() {
        return convertList(databaseApi.getDatabaseList(null).getCheckedData(),
                item -> BeanUtils.toBean(item, DataQueryDatasourceRespVO.class));
    }

    /**
     * 获得数据源下的表名列表
     *
     *  @param datasourceId 数据源编号
     *  @return 表名列表
     */
    @Override
    public List<String> getTableList(Long datasourceId) {
        Long userId = requireUserId();
        List<DatabaseQueryTableRespDTO> tables = databaseApi.getQueryTableList(datasourceId).getCheckedData();
        if (tables == null || tables.size() > 2000) {
            throw exception(MARKET_QUERY_SQL_REJECTED);
        }
        tables.forEach(this::validateTableIdentity);
        List<VisibleTable> visible = new ArrayList<>();
        for (var table : tables) {
            if (assetAccessGuard.canReadPublishedTable(
                    datasourceId, table.getSchemaName(), table.getTableName(), userId)) {
                visible.add(new VisibleTable(table, authorizeTable(datasourceId, table, userId)));
            }
        }
        // 列表也是消费出口；不能只凭第一轮单申请 existence 放出失效或不兼容来源。
        for (var table : visible) {
            revalidateTable(datasourceId, table.table(), userId, table.authorization());
        }
        return visible.stream().map(table -> table.table().getTableReference()).distinct().toList();
    }

    /**
     * 获得数据源下指定表的字段列表
     *
     *  @param datasourceId 数据源编号
     *  @param tableName    表名
     *  @return 字段列表
     */
    @Override
    public List<DataQueryColumnRespVO> getColumnList(Long datasourceId, String tableName) {
        Long userId = requireUserId();
        var table = databaseApi.resolveQueryTable(datasourceId, tableName).getCheckedData();
        validateTableIdentity(table);
        MarketQuerySnapshot authorization = authorizeTable(datasourceId, table, userId);
        var request = new DatabaseQueryColumnsReqDTO();
        request.setDatabaseId(datasourceId);
        request.setSchemaName(table.getSchemaName());
        request.setTableName(table.getTableName());
        request.setExpectedObjectId(table.getRelationObjectId());
        if (authorization != null) {
            request.setColumns(List.copyOf(authorization.fieldScope().getColumns()));
        }
        List<DataQueryColumnRespVO> columns = convertList(
                databaseApi.getQueryTableColumns(request).getCheckedData(),
                item -> BeanUtils.toBean(item, DataQueryColumnRespVO.class));
        if (authorization != null) {
            columns = marketAccessService.filterPreview(columns, authorization.fieldScope());
        }
        revalidateTable(datasourceId, table, userId, authorization);
        return columns;
    }

    /**
     * 校验授权表。
     */
    private MarketQuerySnapshot authorizeTable(Long datasourceId, DatabaseQueryTableRespDTO table, Long userId) {
        assetAccessGuard.assertPublishedTableReadable(datasourceId, table.getSchemaName(), table.getTableName(), userId,
                true);
        var apply = marketAccessService.findEffectiveApplyForTable(userId, datasourceId, table.getSchemaName(),
                table.getTableName());
        if (apply == null) {
            assetAccessGuard.assertPublishedTableReadable(datasourceId, table.getSchemaName(), table.getTableName(),
                    userId, false);
            return null;
        }
        var snapshot = marketAccessService.requireQuerySnapshot(userId, apply.getId());
        if (!Objects.equals(datasourceId, snapshot.databaseId())
                || !Objects.equals(table.getSchemaName(), snapshot.schemaName())
                || !Objects.equals(table.getTableName(), snapshot.tableName())) {
            throw exception(cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_AUTHORIZATION_CHANGED);
        }
        return snapshot;
    }

    /**
     * 再次核验表。
     */
    private void revalidateTable(Long datasourceId, DatabaseQueryTableRespDTO table, Long userId,
            MarketQuerySnapshot snapshot) {
        if (snapshot != null) {
            marketAccessService.assertStillCurrent(snapshot);
        } else {
            assetAccessGuard.assertPublishedTableReadable(datasourceId, table.getSchemaName(), table.getTableName(),
                    userId, false);
        }
    }

    /**
     * 校验数据查询的表身份，不满足约束时拒绝当前操作。
     */
    private void validateTableIdentity(DatabaseQueryTableRespDTO table) {
        if (table == null || StrUtil.isBlank(table.getSchemaName()) || StrUtil.isBlank(table.getTableName())
                || StrUtil.isBlank(table.getTableReference()) || StrUtil.isBlank(table.getNormalizedSql())) {
            throw exception(MARKET_QUERY_SQL_REJECTED);
        }
    }

    /**
     * 读取并校验当前登录用户编号。
     */
    private Long requireUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_APPLY_REQUIRED);
        }
        return userId;
    }

    private record VisibleTable(DatabaseQueryTableRespDTO table, MarketQuerySnapshot authorization) {

    }

    private record TableIdentity(String schemaName, String tableName) {

    }

    /**
     * 只读执行单条 SELECT 查询
     *
     *  @param reqVO 查询请求
     *  @return 查询结果
     */
    @Override
    public DataQueryResultRespVO executeQuery(DataQueryExecuteReqVO reqVO) {
        return execute(reqVO, "DATA_QUERY");
    }

    /**
     * 为导出重新执行查询：沿用数据查询的全部访问校验与批准列约束，结果按 DATA_EXPORT 场景（导出豁免口径）脱敏。
     */
    @Override
    public DataQueryResultRespVO executeForExport(DataQueryExecuteReqVO reqVO) {
        return execute(reqVO, "DATA_EXPORT");
    }

    /**
     * 只做导出申请前的访问校验，不读取数据；返回规范化后的表身份。
     */
    @Override
    public String[] checkExportable(DataQueryExecuteReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        RestrictedSelectRespDTO plan = parseRestrictedSelect(reqVO);
        if (StrUtil.isBlank(plan.getTableName()) || StrUtil.isBlank(plan.getSchemaName())
                || StrUtil.isBlank(plan.getNormalizedSql())) {
            throw exception(MARKET_QUERY_SQL_REJECTED);
        }
        assetAccessGuard.assertPublishedTableReadable(reqVO.getDatasourceId(), plan.getSchemaName(),
                plan.getTableName(), userId, true);
        MarketResourceApplyDO apply = marketAccessService.findEffectiveApplyForTable(
                userId, reqVO.getDatasourceId(), plan.getSchemaName(), plan.getTableName());
        if (apply != null) {
            MarketQuerySnapshot authorization = marketAccessService.requireQuerySnapshot(userId, apply.getId());
            marketAccessService.assertQueryColumnsAllowed(authorization.fieldScope(),
                    Boolean.TRUE.equals(plan.getWildcard()), plan.getSourceColumns());
        } else {
            assetAccessGuard.assertPublishedTableReadable(reqVO.getDatasourceId(), plan.getSchemaName(),
                    plan.getTableName(), userId, false);
        }
        return new String[]{plan.getSchemaName(), plan.getTableName()};
    }

    /**
     * 执行受限查询。
     */
    private DataQueryResultRespVO execute(DataQueryExecuteReqVO reqVO, String maskScene) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        RestrictedSelectRespDTO plan = parseRestrictedSelect(reqVO);
        String tableName = plan.getTableName();
        String schemaName = plan.getSchemaName();
        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(schemaName) || StrUtil.isBlank(plan.getNormalizedSql())) {
            throw exception(MARKET_QUERY_SQL_REJECTED);
        }
        assetAccessGuard.assertPublishedTableReadable(reqVO.getDatasourceId(), schemaName, tableName, userId, true);
        MarketResourceApplyDO apply = marketAccessService.findEffectiveApplyForTable(
                userId, reqVO.getDatasourceId(), schemaName, tableName);
        MarketQuerySnapshot authorization = apply == null ? null
                : marketAccessService.requireQuerySnapshot(userId, apply.getId());
        if (apply != null) {
            marketAccessService.assertQueryColumnsAllowed(authorization.fieldScope(),
                    Boolean.TRUE.equals(plan.getWildcard()), plan.getSourceColumns());
        } else {
            assetAccessGuard.assertPublishedTableReadable(reqVO.getDatasourceId(), schemaName, tableName, userId,
                    false);
        }
        DatabaseQueryReqDTO request = new DatabaseQueryReqDTO();
        request.setDatabaseId(reqVO.getDatasourceId());
        request.setSql(plan.getNormalizedSql());
        request.setExpectedObjectId(plan.getRelationObjectId());
        request.setLimit(reqVO.getLimit());
        request.setMaskScene(maskScene);
        if (apply != null) {
            // 批准列在数据库执行前生效；返回后再过滤只作为防御，不作为通过依据。
            if (!java.util.Objects.equals(request.getDatabaseId(), authorization.databaseId())
                    || !java.util.Objects.equals(schemaName, authorization.schemaName())
                    || !java.util.Objects.equals(tableName, authorization.tableName())) {
                throw exception(
                        cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_AUTHORIZATION_CHANGED);
            }
            request.setExpectedSchema(authorization.schemaName());
            request.setExpectedTable(authorization.tableName());
            request.setAllowedColumns(java.util.List.copyOf(authorization.fieldScope().getColumns()));
        }
        return cn.iocoder.yudao.module.asset.service.market.MarketConsumptionService.read(
                consumptionService, authorization, request, request.getLimit() == null ? 100 : request.getLimit(),
                allowedLimit -> {
                    request.setLimit(allowedLimit);
                    long startTime = System.currentTimeMillis();
                    DatabasePreviewRespDTO preview = databaseApi.executeQuery(request).getCheckedData();
                    DataQueryResultRespVO result = BeanUtils.toBean(preview, DataQueryResultRespVO.class);
                    if (apply != null) {
                        marketAccessService.filterPreview(result, authorization.fieldScope());
                    }
                    result.setElapsedMs(System.currentTimeMillis() - startTime);
                    recordQueryAccess(reqVO, tableName);
                    if (authorization != null) {
                        marketAccessService.assertStillCurrent(authorization);
                    } else {
                        assetAccessGuard.assertPublishedTableReadable(reqVO.getDatasourceId(), schemaName, tableName,
                                userId, false);
                    }
                    return result;
                }, result -> result.getRows() == null ? 0 : result.getRows().size());
    }

    /**
     * 解析数据查询的受限查询。
     */
    private RestrictedSelectRespDTO parseRestrictedSelect(DataQueryExecuteReqVO reqVO) {
        DatabaseQueryReqDTO request = new DatabaseQueryReqDTO();
        request.setDatabaseId(reqVO.getDatasourceId());
        request.setSql(reqVO.getSql());
        try {
            return databaseApi.parseRestrictedSelect(request).getCheckedData();
        } catch (RuntimeException ex) {
            throw exception(MARKET_QUERY_SQL_REJECTED);
        }
    }

    /**
     * 记录数据查询的查询访问。
     */
    private void recordQueryAccess(DataQueryExecuteReqVO reqVO, String tableName) {
        try {
            SafetyAccessLogCreateReqDTO logReq = new SafetyAccessLogCreateReqDTO();
            logReq.setUserId(SecurityFrameworkUtils.getLoginUserId());
            logReq.setAccessType(SafetyAccessLogCreateReqDTO.ACCESS_TYPE_QUERY);
            logReq.setResourceName("数据查询工作台");
            logReq.setDatabaseId(reqVO.getDatasourceId());
            logReq.setTableName(tableName);
            logReq.setAccessTime(LocalDateTime.now());
            safetySecurityApi.recordAccessLog(logReq);
        } catch (Exception ex) {
            log.warn("[recordQueryAccess][查询访问日志写入失败：{}]", ex.getMessage());
        }
    }

}
