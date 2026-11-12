package cn.iocoder.yudao.module.governance.api.metadata;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataFileRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaFileDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaFileMapper;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnUpdateReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataRuntimeMetricsReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableIngestReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsBatchReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableKeyDTO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaColumnUpdateBizReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaFinalizedDetailRespVO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableSnapshotDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableSnapshotMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaObjectService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 元数据 RPC 接口实现。
 */
@RestController
@Validated
public class MetadataApiImpl implements MetadataApi {

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.MetaCatalogLinkService catalogLinkService;

    /**
     * 幂等交付目录挂接来源版本。
     */
    @Override
    public CommonResult<cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO> applyCatalogLink(
            cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkCommandDTO command) {
        return success(catalogLinkService.apply(command));
    }

    /**
     * 查询目录挂接原始操作回执。
     */
    @Override
    public CommonResult<cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO> queryCatalogLinkOperation(
            String operationId) {
        return success(catalogLinkService.query(operationId));
    }

    @Resource
    private MetaFileMapper fileMapper;

    /**
     * 分页读取当前有效文件元数据，供资产注册使用。
     */
    @Override
    public CommonResult<PageResult<MetadataFileRespDTO>> getCurrentFilePage(Integer pageNo, Integer pageSize,
            String keyword) {
        PageParam page = new PageParam();
        page.setPageNo(Math.max(1, pageNo == null ? 1 : pageNo));
        page.setPageSize(Math.max(1, Math.min(200, pageSize == null ? 10 : pageSize)));
        var query = Wrappers.lambdaQuery(MetaFileDO.class)
                .eq(MetaFileDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_ACTIVE);
        if (StrUtil.isNotBlank(keyword)) {
            query.and(q -> q.like(MetaFileDO::getName, keyword).or().like(MetaFileDO::getBusinessName, keyword));
        }
        var rows = fileMapper.selectPage(page, query.orderByDesc(MetaFileDO::getId));
        return success(new PageResult<>(BeanUtils.toBean(rows.getList(), MetadataFileRespDTO.class), rows.getTotal()));
    }

    /**
     * 读取当前有效文件元数据的注册投影。
     */
    @Override
    public CommonResult<MetadataFileRespDTO> getCurrentFile(Long id) {
        var file = id == null ? null : fileMapper.selectById(id);
        return success(file == null || !Objects.equals(file.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                ? null : BeanUtils.toBean(file, MetadataFileRespDTO.class));
    }

    @Resource
    private MetaTableMapper tableMapper;

    @Resource
    private MetaDbMapper dbMapper;

    @Resource
    private MetaColumnMapper columnMapper;

    @Resource
    private MetaObjectService objectService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.MetaPhysicalTableIngestService ingestService;

    @Resource
    private MetaTableSnapshotMapper snapshotMapper;

    /**
     * 获得可编目的已发布表元数据分页。
     */
    @Override
    public CommonResult<PageResult<MetadataTableRespDTO>> getPublishedTablePage(
            Integer pageNo, Integer pageSize, String keyword, Boolean onlyUnlinked) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(Math.max(pageNo == null ? 1 : pageNo, 1));
        pageParam.setPageSize(Math.max(1, Math.min(pageSize == null ? 10 : pageSize, 200)));
        var wrapper = Wrappers.lambdaQuery(MetaTableDO.class)
                .eq(MetaTableDO::getPublishStatus, MetaTableDO.PUBLISH_STATUS_YES)
                .isNotNull(MetaTableDO::getPublishedSnapshotId)
                .isNotNull(MetaTableDO::getFinalizedSnapshot)
                .eq(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_ACTIVE);
        if (Boolean.TRUE.equals(onlyUnlinked)) {
            wrapper.and(query -> query.isNull(MetaTableDO::getCatalogLinked)
                    .or().eq(MetaTableDO::getCatalogLinked, 0));
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.apply("(finalized_snapshot->'table'->>'tableName' ILIKE {0} "
                    + "OR finalized_snapshot->'table'->>'businessName' ILIKE {0} "
                    + "OR finalized_snapshot->'table'->>'sourceComment' ILIKE {0} "
                    + "OR finalized_snapshot->'table'->>'customComment' ILIKE {0})", "%" + keyword + "%");
        }
        wrapper.orderByDesc(MetaTableDO::getPublishTime)
                .orderByDesc(MetaTableDO::getId);
        PageResult<MetaTableDO> page = tableMapper.selectPage(pageParam, wrapper);
        List<MetadataTableRespDTO> list = page.getList().stream().map(this::publishedTableView).toList();
        return success(new PageResult<>(list, page.getTotal()));
    }

    /**
     * 获得表元数据。
     */
    @Override
    public CommonResult<MetadataTableRespDTO> getTable(Long id) {
        MetaTableDO table = tableMapper.selectById(id);
        if (table == null || Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            return success(null);
        }
        MetadataTableRespDTO result;
        if (Objects.equals(table.getPublishStatus(), MetaTableDO.PUBLISH_STATUS_YES)
                && table.getPublishedSnapshotId() != null) {
            result = publishedTableView(table);
        } else {
            result = BeanUtils.toBean(table, MetadataTableRespDTO.class);
            fillDatabaseInfo(List.of(result));
        }
        return success(result);
    }

    /**
     * 获得表字段元数据。
     */
    @Override
    public CommonResult<List<MetadataColumnRespDTO>> getColumnList(Long tableId) {
        List<MetaColumnDO> columns = columnMapper.selectListByTableId(tableId).stream()
                .filter(column -> column.getObjectStatus() == null
                        || column.getObjectStatus() != MetaDbDO.OBJECT_STATUS_DELETED)
                .toList();
        return success(BeanUtils.toBean(columns, MetadataColumnRespDTO.class));
    }

    @Resource
    private MetaTableKeyResolver tableKeyResolver;

    /**
     * 按 (数据源, schema, 表名) 批量读取当前有效字段名；响应与请求一一对应、顺序一致，未唯一命中时 found=false。
     */
    @Override
    public CommonResult<List<MetadataTableColumnsRespDTO>> getColumnNamesBatch(MetadataTableColumnsBatchReqDTO req) {
        List<MetadataTableKeyDTO> tables = req == null || req.getTables() == null ? List.of() : req.getTables();
        if (tables.size() > MetadataTableColumnsBatchReqDTO.MAX_TABLES) {
            throw exception(GlobalErrorCodeConstants.BAD_REQUEST);
        }
        List<MetaTableKeyResolver.TableKey> keys = tables.stream()
                .map(table -> table == null ? null : new MetaTableKeyResolver.TableKey(
                        table.getDatasourceId(), table.getSchemaName(), table.getTableName()))
                .toList();
        Map<MetaTableKeyResolver.TableKey, MetaTableKeyResolver.TableMatch> matches =
                tableKeyResolver.resolve(keys.stream().filter(Objects::nonNull).toList());
        Map<Long, List<String>> columns = tableKeyResolver.loadColumnNames(
                matches.values().stream().map(MetaTableKeyResolver.TableMatch::tableId).toList());
        List<MetadataTableColumnsRespDTO> result = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            MetadataTableKeyDTO table = tables.get(i);
            MetadataTableColumnsRespDTO row = new MetadataTableColumnsRespDTO();
            MetaTableKeyResolver.TableMatch match = keys.get(i) == null ? null : matches.get(keys.get(i));
            if (table != null) {
                row.setDatasourceId(table.getDatasourceId());
                row.setSchemaName(table.getSchemaName());
                row.setTableName(table.getTableName());
            }
            row.setFound(match != null);
            row.setColumnNames(match == null ? new ArrayList<>()
                    : new ArrayList<>(columns.getOrDefault(match.tableId(), List.of())));
            result.add(row);
        }
        return success(result);
    }

    /**
     * 按编号读取当前字段治理详情，供消费方完成对象授权后维护。
     */
    @Override
    public CommonResult<MetadataColumnRespDTO> getColumn(Long id) {
        return success(BeanUtils.toBean(objectService.getColumnDetail(id), MetadataColumnRespDTO.class));
    }

    /**
     * 获得同一发布快照的字段集合；无可信快照时返回空。
     */
    @Override
    public CommonResult<List<MetadataColumnRespDTO>> getPublishedColumnList(Long tableId) {
        MetaTableDO table = tableId == null ? null : tableMapper.selectById(tableId);
        if (table == null || Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_TABLE_NOT_EXISTS);
        }
        if (!Objects.equals(table.getPublishStatus(), MetaTableDO.PUBLISH_STATUS_YES)) {
            throw exception(META_TABLE_NOT_PUBLISHED);
        }
        if (table.getPublishedSnapshotId() == null) {
            throw exception(META_TABLE_NOT_FINALIZED);
        }
        return success(parseSnapshotColumns(table.getPublishedSnapshotId(), tableId));
    }

    /**
     * 按指定已发布技术快照读取完整字段。
     */
    @Override
    public CommonResult<List<MetadataColumnRespDTO>> getColumnListBySnapshot(Long snapshotId) {
        MetaTableSnapshotDO snapshot = snapshotId == null ? null : snapshotMapper.selectById(snapshotId);
        Long tableId = snapshot == null ? null : snapshot.getTableId();
        return success(parseSnapshotColumns(snapshotId, tableId));
    }

    /**
     * 解析元数据的快照字段。
     */
    @SuppressWarnings("unchecked")
    private List<MetadataColumnRespDTO> parseSnapshotColumns(Long snapshotId, Long tableId) {
        MetaTableSnapshotDO snapshot = snapshotId == null ? null : snapshotMapper.selectById(snapshotId);
        if (snapshot == null || StrUtil.isBlank(snapshot.getSnapshot())) {
            throw exception(META_SNAPSHOT_NOT_EXISTS);
        }
        if (!Objects.equals(snapshot.getTableId(), tableId)) {
            throw exception(META_SNAPSHOT_TABLE_MISMATCH);
        }
        Map<String, Object> body = JsonUtils.parseObject(snapshot.getSnapshot(), Map.class);
        Object columnsNode = body == null ? null : body.get("columns");
        if (!(columnsNode instanceof List<?> columnNodes)) {
            throw exception(META_SNAPSHOT_INVALID);
        }
        List<MetadataColumnRespDTO> columns = new ArrayList<>();
        for (Object node : columnNodes) {
            if (!(node instanceof Map<?, ?> raw)) {
                continue;
            }
            MetadataColumnRespDTO column = new MetadataColumnRespDTO();
            if (raw.get("id") != null) {
                column.setId(Long.valueOf(stringVal(raw.get("id"))));
            }
            column.setResourceUrn(stringVal(raw.get("resourceUrn")));
            column.setTableId(tableId);
            column.setColumnName(stringVal(raw.get("columnName")));
            Object ordinal = raw.get("ordinal");
            if (ordinal instanceof Number number) {
                column.setOrdinalPosition(number.intValue());
            }
            column.setSourceComment(stringVal(raw.get("comment")));
            column.setDataType(stringVal(raw.get("dataType")));
            Object length = raw.get("length");
            if (length instanceof Number number) {
                column.setDataLength(number.longValue());
            }
            Object precision = raw.get("precision");
            if (precision instanceof Number number) {
                column.setDataPrecision(number.intValue());
            }
            Object scale = raw.get("scale");
            if (scale instanceof Number number) {
                column.setDataScale(number.intValue());
            }
            Object nullable = raw.get("nullable");
            if (nullable instanceof Number number) {
                column.setNullableFlag(number.intValue());
            }
            column.setDefaultValue(stringVal(raw.get("defaultValue")));
            Object pk = raw.get("pk");
            if (pk instanceof Number number) {
                column.setPkFlag(number.intValue());
            }
            columns.add(column);
        }
        return columns;
    }

    /**
     * 将可选值转换为字符串，缺失值保持为空。
     */
    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 维护字段业务属性。
     */
    @Override
    public CommonResult<Boolean> updateColumnBiz(MetadataColumnUpdateReqDTO reqDTO) {
        objectService.updateColumnBiz(
                BeanUtils.toBean(reqDTO, MetaColumnUpdateBizReqVO.class));
        return success(true);
    }

    /**
     * 更新元数据的资产目录挂接状态。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class, timeout = 10)
    public CommonResult<Boolean> updateCatalogLink(
            Long tableId, Boolean linked, String catalogItemUrn) {
        if (catalogLinkService.managed(tableId)) {
            return success(false);
        }
        MetaTableDO table = tableMapper.selectById(tableId);
        if (table == null || StrUtil.isBlank(catalogItemUrn)) {
            return success(false);
        }
        if (Boolean.TRUE.equals(linked)
                && Integer.valueOf(1).equals(table.getCatalogLinked())
                && !StrUtil.equals(table.getCatalogItemUrn(), catalogItemUrn)) {
            return success(false);
        }
        var update = Wrappers.lambdaUpdate(MetaTableDO.class)
                .eq(MetaTableDO::getId, tableId);
        if (!Boolean.TRUE.equals(linked)) {
            update.eq(MetaTableDO::getCatalogItemUrn, catalogItemUrn);
        } else {
            update.and(condition -> condition.isNull(MetaTableDO::getCatalogLinked)
                    .or().ne(MetaTableDO::getCatalogLinked, 1)
                    .or().eq(MetaTableDO::getCatalogItemUrn, catalogItemUrn));
        }
        update.set(MetaTableDO::getCatalogLinked, Boolean.TRUE.equals(linked) ? 1 : 0)
                .set(MetaTableDO::getCatalogItemUrn,
                        Boolean.TRUE.equals(linked) ? catalogItemUrn : null);
        return success(tableMapper.update(null, update) > 0);
    }

    /**
     * 回写表的行数/存储量等运行指标。
     */
    @Override
    public CommonResult<Integer> updateRuntimeMetrics(MetadataRuntimeMetricsReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getDatasourceId() == null || reqDTO.getTables() == null) {
            return success(0);
        }
        int updated = 0;
        LocalDateTime now = LocalDateTime.now();
        for (MetadataRuntimeMetricsReqDTO.TableMetric metric : reqDTO.getTables()) {
            if (metric == null || StrUtil.isBlank(metric.getTableName())) {
                continue;
            }
            MetaTableDO table = tableMapper.selectByDatasourceIdAndName(
                    reqDTO.getDatasourceId(), metric.getTableName());
            if (table == null) {
                continue;
            }
            MetaTableDO update = new MetaTableDO();
            update.setId(table.getId());
            if (metric.getRowCount() != null) {
                update.setRowCount(metric.getRowCount());
            }
            if (metric.getStorageSize() != null) {
                update.setStorageSize(metric.getStorageSize());
            }
            update.setDataUpdateTime(now);
            tableMapper.updateById(update);
            updated++;
        }
        return success(updated);
    }

    /**
     * 物理表回写入元数据（建模部署 / 主数据发布）。
     */
    @Override
    public CommonResult<Long> ingestPhysicalTable(MetadataTableIngestReqDTO reqDTO) {
        return success(ingestService.ingest(reqDTO));
    }

    /** 对外发布候选使用同一冻结表头，避免历史字段和当前表业务属性混合。 */
    private MetadataTableRespDTO publishedTableView(MetaTableDO table) {
        if (StrUtil.isBlank(table.getFinalizedSnapshot())) {
            throw exception(META_TABLE_NOT_FINALIZED);
        }
        MetaFinalizedDetailRespVO frozen;
        try {
            frozen = JsonUtils.parseObject(table.getFinalizedSnapshot(), MetaFinalizedDetailRespVO.class);
        } catch (RuntimeException invalid) {
            throw exception(META_SNAPSHOT_INVALID);
        }
        if (frozen == null || frozen.getTable() == null
                || !Objects.equals(frozen.getTable().getId(), table.getId())) {
            throw exception(META_SNAPSHOT_INVALID);
        }
        MetadataTableRespDTO result = BeanUtils.toBean(frozen.getTable(), MetadataTableRespDTO.class);
        // 仅实时发布/挂接状态覆盖镜像，业务及结构信息保持确认时状态。
        result.setPublishStatus(table.getPublishStatus());
        result.setObjectStatus(table.getObjectStatus());
        result.setPublishTime(table.getPublishTime());
        result.setPublishedSnapshotId(table.getPublishedSnapshotId());
        result.setPublishedVersion(table.getPublishedVersion());
        result.setCatalogLinked(table.getCatalogLinked());
        result.setCatalogItemUrn(table.getCatalogItemUrn());
        return result;
    }

    /**
     * 补充元数据的数据库信息。
     */
    private void fillDatabaseInfo(List<MetadataTableRespDTO> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Long> dbIds = list.stream().map(MetadataTableRespDTO::getDbId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, MetaDbDO> dbMap = dbIds.isEmpty() ? Collections.emptyMap()
                : dbMapper.selectBatchIds(dbIds).stream()
                .collect(Collectors.toMap(MetaDbDO::getId, Function.identity()));
        list.forEach(item -> {
            MetaDbDO db = dbMap.get(item.getDbId());
            if (db != null) {
                item.setDbName(db.getDbName());
                item.setSchemaName(db.getSchemaName());
                item.setDbType(db.getDbType());
                item.setSourceSystemName(db.getSourceSystemName());
            }
        });
    }

}
