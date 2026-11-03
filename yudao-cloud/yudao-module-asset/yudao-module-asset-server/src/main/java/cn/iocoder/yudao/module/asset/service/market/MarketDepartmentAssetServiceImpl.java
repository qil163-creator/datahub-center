package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDepartmentAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDepartmentAssetRow;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 同部门申请的读模型；复制业务数据与本人的实时交付授权使用不同的既有领域出口。 */
@Service
public class MarketDepartmentAssetServiceImpl implements MarketDepartmentAssetService {

    @Resource
    private MarketDepartmentScope departmentScope;

    @Resource
    private MarketDepartmentAssetMapper departmentAssetMapper;

    @Resource
    private MarketCategoryService categoryService;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetCatalogService catalogService;

    @Resource
    private MarketMyAssetService myAssetService;

    @Resource
    private MarketObjectGuard objectGuard;

    /**
     * 按当前部门范围分页读取本地及迁入申请，汇总部门可见的申请记录。
     */
    @Override
    public PageResult<MarketDepartmentAssetRespVO> getPage(MarketDepartmentAssetPageReqVO req) {
        var scope = departmentScope.current();
        Collection<Long> categories = req.getMarketCategoryId() == null ? null
                : categoryService.descendants(req.getMarketCategoryId());
        if (categories != null && categories.isEmpty()) {
            return PageResult.empty();
        }
        long total = departmentAssetMapper.count(req, scope.deptId(), scope.userIds(), categories);
        if (total == 0) {
            return PageResult.empty();
        }
        long offset = (long) (req.getPageNo() - 1) * req.getPageSize();
        var rows = departmentAssetMapper.selectPage(req, scope.deptId(), scope.userIds(), categories, offset).stream()
                .map(row -> {
                    requireInScope(row, scope);
                    return present(row);
                }).toList();
        return new PageResult<>(rows, total);
    }

    /**
     * 按指定申请编号读取同部门资产详情，查看不产生消费授权。
     */
    @Override
    public MarketDepartmentAssetDetailRespVO get(Long id) {
        var scope = departmentScope.current();
        var row = selected(id, scope);
        var result = new MarketDepartmentAssetDetailRespVO();
        result.setAsset(present(row));
        if (row.getSourceWorkOrderId() != null) {
            // 迁入申请只展示与来源申请绑定的快照，避免把历史申请替换成当前资产内容。
            var source = sourceData(row);
            result.setResource(source.getResource());
            result.setColumns(source.getColumns() == null ? List.of() : source.getColumns());
            result.setPreview(source.getPreview());
            result.setApiDocument(source.getApiDocument());
            result.setCanPreview(source.getPreview() != null);
            result.setCanViewApiDocument(source.getApiDocument() != null);
        } else {
            var resource = new MarketCheckDetailRespVO.ResourceSummary();
            resource.setAssetName(row.getAssetName());
            resource.setMarketName(row.getMarketName());
            resource.setMarketCategoryName(row.getMarketCategoryName());
            resource.setAssetType(row.getAssetType());
            resource.setFrequency(row.getUpdateCycle());
            resource.setDeliveryTypes(deliveryTypes(row));
            result.setResource(resource);
            var asset = row.getAssetId() == null ? null : assetMapper.selectById(row.getAssetId());
            if (asset != null && asset.getPublishedCatalogVersionId() != null) {
                resource.setCreateTime(asset.getCreateTime());
                var published = catalogService.publishedAsset(asset);
                result.setColumns(
                        catalogService.listColumns(published.getId(), published.getPublishedSnapshotId()).stream()
                                .map(column -> BeanUtils.toBean(column, MarketCheckDetailRespVO.Column.class))
                                .toList());
            }
            if (Objects.equals(row.getApplyUserId(), scope.actorId())
                    && List.of(2, 5, 6).contains(Objects.requireNonNullElse(row.getStatus(), -1))
                    && objectGuard.hasPermission("asset:market:my-assets:query")) {
                var own = myAssetService.get(row.getId());
                result.setCanPreview(Boolean.TRUE.equals(own.getCanPreview()));
                result.setCanViewApiDocument(Boolean.TRUE.equals(own.getCanViewApiDocument()));
            }
            // 实时取数仍使用原本人已批准资产出口；本接口不借同部门其他人的申请调用实时预览或 API。
        }
        return result;
    }

    /**
     * 读取指定同部门申请的复制数据；原生实时预览仍校验本人真实批准。
     */
    @Override
    public MarketCheckDetailRespVO.Preview preview(Long id, Integer limit) {
        var scope = departmentScope.current();
        var row = selected(id, scope);
        if (limit == null || limit < 1 || limit > 100) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        if (row.getSourceWorkOrderId() != null) {
            // 快照预览只裁剪已有行；这里不因请求更大的条数而重新向来源系统取数。
            var preview = sourceData(row).getPreview();
            if (preview == null) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            if (preview.getRows() != null && preview.getRows().size() > limit) {
                preview.setRows(preview.getRows().subList(0, limit));
                preview.setTruncated(true);
            }
            return preview;
        }
        requireOwnLive(row, scope);
        var live = myAssetService.preview(id, limit);
        var preview = BeanUtils.toBean(live, MarketCheckDetailRespVO.Preview.class);
        if (Boolean.FALSE.equals(live.getTruncated()) && live.getRows() != null) {
            preview.setTotal(String.valueOf(live.getRows().size()));
        }
        return preview;
    }

    /**
     * 读取指定申请的 API 文档；不执行来源 API，不转授同部门其他人的合同。
     */
    @Override
    public MarketCheckDetailRespVO.ApiDocument apiDocument(Long id) {
        var scope = departmentScope.current();
        var row = selected(id, scope);
        if (row.getSourceWorkOrderId() != null) {
            var document = sourceData(row).getApiDocument();
            if (document == null) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            return document;
        }
        requireOwnLive(row, scope);
        var live = myAssetService.apiDocument(id);
        var document = new MarketCheckDetailRespVO.ApiDocument();
        document.setName(live.getName());
        document.setMethod(live.getMethod());
        document.setPath(live.getPath());
        document.setRequestParams(live.getRequestParams() == null ? List.of() : live.getRequestParams().stream()
                .map(item -> BeanUtils.toBean(item, MarketCheckDetailRespVO.Parameter.class)).toList());
        document.setResponseFields(live.getResponseFields() == null ? List.of() : live.getResponseFields().stream()
                .map(item -> BeanUtils.toBean(item, MarketCheckDetailRespVO.Parameter.class)).toList());
        return document;
    }

    /**
     * 获取当前同部门申请资产实际关联的集市类目。
     */
    @Override
    public List<MarketDepartmentAssetCategoryRespVO> categories() {
        var scope = departmentScope.current();
        return departmentAssetMapper.selectCategories(scope.deptId(), scope.userIds());
    }

    /**
     * 读取部门范围内的指定申请，并复核返回记录的部门归属。
     */
    private MarketDepartmentAssetRow selected(Long id, MarketDepartmentScope.Scope scope) {
        if (id == null || id <= 0) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        var row = departmentAssetMapper.selectInDepartment(id, scope.deptId(), scope.userIds());
        if (row == null) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        requireInScope(row, scope);
        return row;
    }

    /**
     * 校验申请属于当前用户且用户具有本人资产查询权限。
     */
    private void requireOwnLive(MarketDepartmentAssetRow row, MarketDepartmentScope.Scope scope) {
        if (!Objects.equals(row.getApplyUserId(), scope.actorId())
                || !objectGuard.hasPermission("asset:market:my-assets:query")) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
    }

    /**
     * 分别按本地申请成员和迁入申请部门核验可见范围。
     */
    private void requireInScope(MarketDepartmentAssetRow row, MarketDepartmentScope.Scope scope) {
        // 本地申请按当前部门成员收敛；迁入申请按冻结的申请部门及来源申请标识核验。
        boolean allowed = row.getSourceWorkOrderId() == null
                ? row.getApplyUserId() != null && scope.userIds().contains(row.getApplyUserId())
                : Objects.equals(row.getApplyDeptId(), scope.deptId()) && row.getSourceApplyId() != null;
        if (!allowed) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
    }

    /**
     * 校验来源申请关联信息并解析详情快照，拒绝缺失或不匹配的快照。
     */
    private MarketCheckDetailRespVO sourceData(MarketDepartmentAssetRow row) {
        // 来源系统、市场编号和外部资产编码必须互相对应，不能仅凭可解析的 JSON 信任快照。
        if (!"QDATA".equals(row.getSourceSystem())
                || StrUtil.isBlank(row.getSourceMarketId()) || StrUtil.isBlank(row.getSourceApplyId())
                || !Objects.equals(row.getSourceAssetExternalCode(), "qdata:market:" + row.getSourceMarketId())) {
            throw exception(MARKET_CHECK_SNAPSHOT_INVALID);
        }
        try {
            var data = JsonUtils.getObjectMapper()
                    .readValue(row.getSourceDetailSnapshot(), MarketCheckDetailRespVO.class);
            if (data == null || data.getResource() == null
                    || !Objects.equals(row.getSourceMarketId(), data.getResource().getSourceRecordId())) {
                throw exception(MARKET_CHECK_SNAPSHOT_INVALID);
            }
            return data;
        } catch (JacksonException | IllegalArgumentException invalid) {
            // 不记录来源数据原文，解析失败也不回退其他申请或资产的快照。
            throw exception(MARKET_CHECK_SNAPSHOT_INVALID);
        }
    }

    /**
     * 组装部门资产申请展示信息及状态文案，迁入记录使用来源快照的资源属性。
     */
    private MarketDepartmentAssetRespVO present(MarketDepartmentAssetRow row) {
        var result = new MarketDepartmentAssetRespVO();
        result.setId(row.getId());
        result.setDisplayId(StrUtil.blankToDefault(row.getSourceApplyId(), String.valueOf(row.getId())));
        result.setAssetId(row.getAssetId());
        result.setSourceApplyId(row.getSourceApplyId());
        result.setSourceMarketId(row.getSourceMarketId());
        result.setMarketName(row.getMarketName());
        result.setMarketCategoryId(row.getMarketCategoryId());
        result.setMarketCategoryName(row.getMarketCategoryName());
        result.setFrequency(row.getUpdateCycle());
        result.setDeliveryTypes(deliveryTypes(row));
        result.setApplyType(row.getApplyStartTime() == null && row.getApplyEndTime() == null ? 1 : 2);
        result.setApplyTime(row.getCreateTime());
        result.setStartTime(row.getApplyStartTime());
        result.setEndTime(row.getApplyEndTime());
        result.setApplyReason(StrUtil.blankToDefault(row.getApplyNote(), row.getPurpose()));
        result.setStatus(row.getStatus());
        result.setStatusLabel(switch (row.getStatus() == null ? -1 : row.getStatus()) {
            case 0 -> "草稿";
            case 1 -> "待授权";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            case 4 -> "已撤销";
            case 5 -> "已过期";
            case 6 -> "已收回";
            case 8 -> "待补正";
            default -> "未知状态";
        });
        result.setStatusTone(switch (row.getStatus() == null ? -1 : row.getStatus()) {
            case 2 -> "success";
            case 3 -> "error";
            case 0, 1, 8 -> "warning";
            default -> "default";
        });
        if (row.getSourceWorkOrderId() != null) {
            var resource = sourceData(row).getResource();
            result.setMarketCategoryName(resource.getMarketCategoryName());
            result.setFrequency(resource.getFrequency());
            result.setDeliveryTypes(resource.getDeliveryTypes() == null ? List.of() : resource.getDeliveryTypes());
        }
        return result;
    }

    /**
     * 解析声明的交付方式并去重，声明为空时读取单一交付方式。
     */
    private List<Integer> deliveryTypes(MarketDepartmentAssetRow row) {
        if (StrUtil.isBlank(row.getDeclaredDeliveryTypes())) {
            return row.getDeliveryType() == null ? List.of() : List.of(row.getDeliveryType());
        }
        return Arrays.stream(row.getDeclaredDeliveryTypes().split(","))
                .map(String::trim).filter(value -> value.matches("[123]"))
                .map(Integer::valueOf).distinct().toList();
    }
}
