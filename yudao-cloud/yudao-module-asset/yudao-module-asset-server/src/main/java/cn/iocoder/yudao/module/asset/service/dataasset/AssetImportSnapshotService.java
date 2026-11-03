package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 资产迁入快照的领域维护；查询仅作用于本平台快照，不执行外部数据库语句。 */
@Service
public class AssetImportSnapshotService {

    @Resource
    private AssetImportDatasetMapper datasetMapper;

    @Resource
    private AssetImportRowMapper rowMapper;

    @Resource
    private AssetImportRowLogMapper logMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketAuditRecorder audit;

    @Resource
    private AssetUserNameResolver userNames;

    @Resource
    private cn.iocoder.yudao.module.system.api.user.AdminUserApi userApi;

    /**
     * 判断资产外部编码是否属于当前迁入快照支持的来源。
     */
    public static boolean supports(DataAssetDO asset) {
        return asset != null && asset.getExternalCode() != null
                && (asset.getExternalCode().startsWith("qdata:market:")
                || asset.getExternalCode().startsWith("qdata:asset:"));
    }

    /**
     * 组装迁入资产快照的描述信息。
     */
    public AssetImportSnapshotRespVO describe(Long assetId) {
        AssetImportDatasetDO dataset = datasetMapper.selectById(assetId);
        if (dataset == null) {
            return null;
        }
        AssetImportSnapshotRespVO view = BeanUtils.toBean(dataset, AssetImportSnapshotRespVO.class);
        view.setFileInfo(object(dataset.getFileInfoJson()));
        view.setQuality(object(dataset.getQualityJson()));
        view.setLineage(object(dataset.getLineageJson()));
        return view;
    }

    /**
     * 补充迁入资产快照的展示。
     */
    public void fillDisplay(DataAssetRespVO view) {
        AssetImportSnapshotRespVO snapshot = describe(view.getId());
        if (snapshot == null) {
            return;
        }
        view.setSourceRecordId(snapshot.getSourceAssetId());
        view.setDatasourceName(snapshot.getSourceName());
        view.setDatasourceType(snapshot.getSourceType());
        view.setFileName(Objects.toString(snapshot.getFileInfo().get("fileName"), null));
        view.setFileType(Objects.toString(snapshot.getFileInfo().get("fileType"), null));
    }

    /**
     * 读取迁入数据集的字段快照，并将数据集修订号关联到字段。
     */
    public List<MetadataColumnRespDTO> columns(Long assetId) {
        AssetImportDatasetDO dataset = datasetMapper.selectById(assetId);
        return dataset == null ? List.of() : columns(dataset);
    }

    /**
     * 读取迁入数据集的字段快照，并将数据集修订号关联到字段。
     */
    private List<MetadataColumnRespDTO> columns(AssetImportDatasetDO dataset) {
        List<MetadataColumnRespDTO> result = JsonUtils.parseObject(
                Objects.requireNonNullElse(dataset.getColumnsJson(), "[]"),
                new TypeReference<List<MetadataColumnRespDTO>>() {
                });
        result.forEach(column -> column.setRevision(dataset.getRevision()));
        return result;
    }

    /**
     * 按字段编号读取迁入资产快照字段详情。
     */
    public DataAssetColumnRespVO column(Long assetId, Long columnId) {
        return BeanUtils.toBean(columns(assetId).stream().filter(item -> Objects.equals(item.getId(), columnId))
                .findFirst().orElseThrow(() -> exception(ASSET_QUALITY_COLUMN_INVALID)), DataAssetColumnRespVO.class);
    }

    /**
     * 更新迁入资产快照的字段。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateColumn(DataAssetColumnUpdateReqVO req, AssetMaintenanceAuthorization authorization) {
        lockAuthorized(req.getAssetId(), authorization);
        AssetImportDatasetDO dataset = requireDataset(req.getAssetId());
        List<MetadataColumnRespDTO> columns = columns(dataset);
        MetadataColumnRespDTO column = columns.stream().filter(item -> Objects.equals(item.getId(), req.getId()))
                .findFirst().orElseThrow(() -> exception(ASSET_QUALITY_COLUMN_INVALID));
        column.setCustomComment(req.getCustomComment());
        column.setBusinessName(req.getBusinessName());
        column.setBusinessDefinition(req.getBusinessDefinition());
        column.setMeasuringUnit(req.getMeasuringUnit());
        column.setStandardElementId(req.getStandardElementId());
        column.setSafetyLevelId(req.getSafetyLevelId());
        column.setSensitiveFlag(req.getSensitiveFlag());
        column.setRemark(req.getRemark());
        if (datasetMapper.updateColumns(req.getAssetId(), req.getRevision(), JsonUtils.toJsonString(columns),
                String.valueOf(authorization.getUserId())) != 1) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        audit.record("IMPORT_COLUMN_UPDATE", "ASSET", req.getAssetId(), null, null,
                "维护迁入字段业务属性，字段编号=" + req.getId(), "OK");
    }

    /**
     * 按条件分页读取本地迁入数据行，返回行编号与修订号。
     */
    public AssetImportPreviewRespVO preview(DataAssetDO asset, DataAssetPreviewReqVO req) {
        AssetImportDatasetDO dataset = requireDataset(asset.getId());
        List<MetadataColumnRespDTO> columns = columns(dataset);
        int size = Math.max(1, Math.min(100, Optional.ofNullable(req.getPageSize())
                .orElse(Optional.ofNullable(req.getLimit()).orElse(20))));
        int page = Math.max(1, Math.min(1000000, Optional.ofNullable(req.getPageNo()).orElse(1)));
        QueryWrapper<AssetImportRowDO> query = rowQuery(asset.getId(), columns, req.getFilters());
        long count = rowMapper.selectCount(query);
        List<AssetImportRowDO> rows = rowMapper.selectList(query.orderByAsc("ordinal")
                .last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size)));
        AssetImportPreviewRespVO response = new AssetImportPreviewRespVO();
        response.setTotal(count);
        response.setColumns(columns.stream().map(column -> new DatabasePreviewRespDTO.Column(
                column.getColumnName(), label(column), column.getDataType())).toList());
        response.setRows(rows.stream().map(row -> new LinkedHashMap<String, Object>(values(row.getRowJson())))
                .map(row -> (Map<String, Object>) row).toList());
        response.setRowIds(rows.stream().map(row -> String.valueOf(row.getId())).toList());
        response.setRowRevisions(rows.stream().map(AssetImportRowDO::getRevision).toList());
        response.setTruncated((long) page * size < count);
        return response;
    }

    /**
     * 按快照字段和固定运算符构造本地 JSONB 行筛选条件。
     */
    QueryWrapper<AssetImportRowDO> rowQuery(Long assetId, List<MetadataColumnRespDTO> columns,
            List<DataAssetPreviewReqVO.Filter> filters) {
        QueryWrapper<AssetImportRowDO> query = new QueryWrapper<AssetImportRowDO>().eq("asset_id", assetId);
        if (filters == null) {
            return query;
        }
        for (DataAssetPreviewReqVO.Filter filter : filters) {
            // 字段先匹配快照元数据，运算符只取固定映射；字段名和值通过参数传入 JSONB 表达式。
            MetadataColumnRespDTO column = columns.stream()
                    .filter(item -> Objects.equals(item.getColumnName(), filter.getColumn()))
                    .findFirst().orElseThrow(() -> exception(DATA_ASSET_IMPORT_INPUT_INVALID));
            String operator = filter.getOperator();
            if ("isNull".equals(operator) || "notNull".equals(operator)) {
                query.apply("(row_json->>{0}) IS " + ("notNull".equals(operator) ? "NOT NULL" : "NULL"),
                        column.getColumnName());
                continue;
            }
            String sqlOperator = switch (operator) {
                case "eq" -> "=";
                case "ne" -> "<>";
                case "gt" -> ">";
                case "ge" -> ">=";
                case "lt" -> "<";
                case "le" -> "<=";
                case "like" -> "LIKE";
                default -> throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            };
            if (filter.getValue() == null) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            boolean numeric = numeric(column) && !"like".equals(operator);
            String expression = numeric ? "CAST((row_json->>{0}) AS numeric)" : "(row_json->>{0})";
            Object value = numeric ? decimal(filter.getValue()) : filter.getValue();
            if ("like".equals(operator)) {
                value = "%" + value + "%";
            }
            query.apply(expression + " " + sqlOperator + (numeric ? " CAST({1} AS numeric)" : " {1}"),
                    column.getColumnName(), value);
        }
        return query;
    }

    /**
     * 校验本地快照行修改并按修订号更新，同时保存变更日志。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRow(AssetImportRowUpdateReqVO req, AssetMaintenanceAuthorization authorization) {
        lockAuthorized(req.getId(), authorization);
        List<MetadataColumnRespDTO> columns = columns(requireDataset(req.getId()));
        if (req.getValues().values().stream().filter(Objects::nonNull).mapToLong(String::length).sum() > 1048576) {
            throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
        }
        AssetImportRowDO row = rowMapper.selectById(req.getRowId());
        if (row == null || !Objects.equals(row.getAssetId(), req.getId())) {
            throw exception(DATA_ASSET_IMPORT_ROW_NOT_EXISTS);
        }
        Map<String, String> before = values(row.getRowJson());
        Map<String, String> after = new LinkedHashMap<>(before);
        for (Map.Entry<String, String> entry : req.getValues().entrySet()) {
            MetadataColumnRespDTO column = columns.stream()
                    .filter(item -> Objects.equals(item.getColumnName(), entry.getKey()))
                    .findFirst().orElseThrow(() -> exception(DATA_ASSET_IMPORT_INPUT_INVALID));
            String value = entry.getValue();
            if (Objects.equals(column.getPkFlag(), 1) && !Objects.equals(before.get(entry.getKey()), value)) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            if (value == null && Objects.equals(column.getNullableFlag(), 0)) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            if (value != null && column.getDataLength() != null && !numeric(column)
                    && value.codePointCount(0, value.length()) > column.getDataLength()) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            if (value != null && numeric(column)) {
                BigDecimal number = decimal(value);
                if (column.getDataScale() != null && number.scale() > column.getDataScale()
                        || column.getDataPrecision() != null && number.precision() > column.getDataPrecision()) {
                    throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
                }
            }
            after.put(entry.getKey(), value);
        }
        // 修订号更新失败即终止；行数据和变更日志在同一事务内提交，避免覆盖并发编辑。
        if (rowMapper.updateRow(req.getRowId(), req.getId(), req.getRevision(), JsonUtils.toJsonString(after),
                String.valueOf(authorization.getUserId())) != 1) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        AssetImportRowLogDO log = new AssetImportRowLogDO();
        log.setAssetId(req.getId());
        log.setRowId(req.getRowId());
        log.setRevision(req.getRevision() + 1);
        log.setBeforeJson(JsonUtils.toJsonString(before));
        log.setAfterJson(JsonUtils.toJsonString(after));
        log.setCreator(String.valueOf(authorization.getUserId()));
        if (logMapper.insertLog(log) != 1) {
            throw exception(MARKET_OPERATION_NOT_PERSISTED);
        }
        audit.record("IMPORT_ROW_UPDATE", "ASSET", req.getId(), null, null,
                "维护本地迁入数据，行编号=" + req.getRowId() + "，修订=" + (req.getRevision() + 1), "OK");
    }

    /**
     * 分页读取迁入行的变更日志，补充操作人名称及字段前后值。
     */
    public PageResult<AssetImportRowLogRespVO> logs(Long assetId, Long rowId, int page, int size) {
        return logs(assetId, rowId, page, size, null, null);
    }

    /**
     * 分页读取迁入行的变更日志，补充操作人名称及字段前后值。
     */
    public PageResult<AssetImportRowLogRespVO> logs(Long assetId, Long rowId, int page, int size,
            List<Long> createTime, String creatorName) {
        Map<String, MetadataColumnRespDTO> columns = new LinkedHashMap<>();
        columns(assetId).forEach(column -> columns.put(column.getColumnName(), column));
        QueryWrapper<AssetImportRowLogDO> query = new QueryWrapper<AssetImportRowLogDO>()
                .eq("asset_id", assetId).eq(rowId != null, "row_id", rowId);
        if (createTime != null) {
            if (createTime.size() != 2 || createTime.getFirst() > createTime.getLast()) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            query.ge("create_time", java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(
                            createTime.getFirst()), java.time.ZoneId.systemDefault()))
                    .le("create_time", java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(
                            createTime.getLast()), java.time.ZoneId.systemDefault()));
        }
        if (creatorName != null && !creatorName.isBlank()) {
            List<String> actors = userApi.getUserListByNickname(creatorName.trim()).getCheckedData().stream()
                    .map(user -> String.valueOf(user.getId())).toList();
            if (actors.isEmpty()) {
                return PageResult.empty();
            }
            query.in("creator", actors);
        }
        long count = logMapper.selectCount(query);
        List<AssetImportRowLogDO> records = logMapper.selectList(query.orderByDesc("id")
                .last("LIMIT " + Math.max(1, Math.min(200, size)) + " OFFSET " + ((long) (Math.max(1,
                        page) - 1) * Math.max(1, Math.min(200, size)))));
        Map<String, String> actors = userNames.resolveByTexts(
                records.stream().map(AssetImportRowLogDO::getCreator).toList());
        List<AssetImportRowLogRespVO> result = records.stream().map(record -> {
            AssetImportRowLogRespVO view = BeanUtils.toBean(record, AssetImportRowLogRespVO.class);
            view.setId(String.valueOf(record.getId()));
            view.setRowId(String.valueOf(record.getRowId()));
            view.setActorName(userNames.nameOf(actors, record.getCreator()));
            Map<String, String> before = values(record.getBeforeJson()), after = values(record.getAfterJson());
            List<AssetImportRowLogRespVO.Change> changes = new ArrayList<>();
            after.forEach((key, value) -> {
                if (Objects.equals(value, before.get(key))) {
                    return;
                }
                AssetImportRowLogRespVO.Change change = new AssetImportRowLogRespVO.Change();
                change.setColumnName(key);
                change.setColumnLabel(columns.containsKey(key) ? label(columns.get(key)) : key);
                change.setOldValue(before.get(key));
                change.setNewValue(value);
                changes.add(change);
            });
            view.setChanges(changes);
            return view;
        }).toList();
        return new PageResult<>(result, count);
    }

    /**
     * 锁定资产并复核维护授权与当前登录身份，限制为支持的迁入资产。
     */
    private void lockAuthorized(Long assetId, AssetMaintenanceAuthorization authorization) {
        if (authorization == null || !Objects.equals(authorization.getUserId(), SecurityFrameworkUtils.getLoginUserId())
                || !Objects.equals(authorization.getDeptId(), SecurityFrameworkUtils.getLoginUserDeptId())) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        DataAssetDO current = assetMapper.selectForCatalogUpdate(assetId);
        if (!authorization.canMaintain(current)) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        if (!supports(current)) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
    }

    /**
     * 校验迁入资产快照的数据集，不满足约束时拒绝当前操作。
     */
    private AssetImportDatasetDO requireDataset(Long assetId) {
        AssetImportDatasetDO dataset = datasetMapper.selectById(assetId);
        if (dataset == null) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        return dataset;
    }

    /**
     * 解析迁入资产快照的优先显示名称。
     */
    private static String label(MetadataColumnRespDTO column) {
        if (column.getBusinessName() != null && !column.getBusinessName().isBlank()) {
            return column.getBusinessName();
        }
        if (column.getCustomComment() != null && !column.getCustomComment().isBlank()) {
            return column.getCustomComment();
        }
        return column.getSourceComment() == null ? column.getColumnName() : column.getSourceComment();
    }

    /**
     * 判断字段的数据库类型是否属于数值类型。
     */
    private static boolean numeric(MetadataColumnRespDTO column) {
        return Objects.requireNonNullElse(column.getDataType(), "").toLowerCase(Locale.ROOT)
                .matches(
                        "^(tinyint|smallint|int|integer|bigint|decimal|numeric|number|float|double|double precision|real)(\\(.*\\))?$");
    }

    /**
     * 解析有长度和标度上限的十进制输入，拒绝非法或过大的数值。
     */
    private static BigDecimal decimal(String value) {
        if (value.length() > 1024) {
            throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
        }
        try {
            BigDecimal number = new BigDecimal(value);
            if (Math.abs((long) number.scale()) > 1000) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            return number;
        } catch (NumberFormatException ex) {
            throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
        }
    }

    /**
     * 解析迁入资产快照中的字段值映射。
     */
    private static Map<String, String> values(String json) {
        return JsonUtils.parseObject(json, new TypeReference<LinkedHashMap<String, String>>() {
        });
    }

    /**
     * 解析迁入资产快照中的 JSON 对象。
     */
    private static Map<String, Object> object(String json) {
        return json == null ? Map.of() : JsonUtils.parseObject(json,
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
    }
}
