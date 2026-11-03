package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo.StandardAssociationPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo.StandardAssociationRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.governance.api.standard.StandardAssociationApi;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardAssociationColumnRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 由治理提供当前落标事实，由资产领域拥有者执行维护范围过滤和分页。 */
@Service
@RequiredArgsConstructor
public class StandardAssociationService {

    private final StandardAssociationApi standardApi;

    private final DataAssetMapper assets;

    private final AssetTechLinkMapper links;

    private final AssetCatalogService catalog;

    private final AssetUserNameResolver users;

    private final SecurityFrameworkService security;

    /**
     * 按筛选条件分页查询标准关联。
     */
    public PageResult<StandardAssociationRespVO> getPage(StandardAssociationPageReqVO request) {
        validateRequest(request);
        String permission = "element".equals(
                request.getKind()) ? "governance:standard-element:query" : "governance:standard-code-set:query";
        if (SecurityFrameworkUtils.getLoginUserId() == null || !security.hasPermission(permission)) {
            throw exception(FORBIDDEN);
        }
        // 提供方同时校验当前目录权限、目录对象存在性和有效落标状态。
        List<StandardAssociationColumnRespDTO> fields = standardApi.getColumns(request.getKind(), request.getId())
                .getCheckedData();
        AssetMaintenanceAuthorization authorization = catalog.prepareMaintenanceAuthorization();
        if (authorization == null || authorization.getUserId() == null || authorization.getUserId() <= 0
                || !Objects.equals(authorization.getUserId(), SecurityFrameworkUtils.getLoginUserId())
                || !Objects.equals(authorization.getDeptId(), SecurityFrameworkUtils.getLoginUserDeptId())) {
            throw exception(FORBIDDEN);
        }
        if (fields.isEmpty()) {
            return PageResult.empty();
        }
        Set<Long> tableIds = fields.stream().map(StandardAssociationColumnRespDTO::getTableId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (tableIds.isEmpty()) {
            return PageResult.empty();
        }
        var matchingLinks = links.selectList(
                new LambdaQueryWrapperX<AssetTechLinkDO>().in(AssetTechLinkDO::getMetaTableId, tableIds));
        Set<Long> linkedIds = matchingLinks.stream().map(AssetTechLinkDO::getAssetId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var assetQuery = new LambdaQueryWrapperX<DataAssetDO>().eq(DataAssetDO::getAssetType,
                        DataAssetDO.TYPE_DATABASE_TABLE)
                .and(query -> {
                    query.in(DataAssetDO::getMetaTableId, tableIds);
                    if (!linkedIds.isEmpty()) {
                        query.or().in(DataAssetDO::getId, linkedIds);
                    }
                });
        List<DataAssetDO> candidates = assets.selectList(assetQuery).stream().filter(authorization::canMaintain)
                .toList();
        if (candidates.isEmpty()) {
            return PageResult.empty();
        }
        // 存在技术挂接时，以完整挂接集合为准；旧的主表投影不能恢复已解绑关系。
        Map<Long, List<AssetTechLinkDO>> allLinks = links.selectList(new LambdaQueryWrapperX<AssetTechLinkDO>()
                        .in(AssetTechLinkDO::getAssetId, candidates.stream().map(DataAssetDO::getId).toList())).stream()
                .collect(Collectors.groupingBy(AssetTechLinkDO::getAssetId));
        Map<Long, DataAssetDO> selected = new HashMap<>();
        List<StandardAssociationRespVO> result = new ArrayList<>();
        for (var asset : candidates) {
            var assetLinks = allLinks.getOrDefault(asset.getId(), List.of());
            Set<Long> effectiveTables = assetLinks.isEmpty()
                    ? (asset.getMetaTableId() == null ? Set.of() : Set.of(asset.getMetaTableId()))
                    : assetLinks.stream().map(AssetTechLinkDO::getMetaTableId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<StandardAssociationColumnRespDTO> related = fields.stream()
                    .filter(field -> effectiveTables.contains(field.getTableId()))
                    .sorted(Comparator.comparing(StandardAssociationColumnRespDTO::getTableId)
                            .thenComparing(StandardAssociationColumnRespDTO::getColumnId)).toList();
            if (related.isEmpty()) {
                continue;
            }
            var row = new StandardAssociationRespVO();
            row.setId(asset.getId());
            row.setAssetName(asset.getAssetName());
            row.setDescription(asset.getDescription());
            row.setTableName(
                    related.stream().map(StandardAssociationColumnRespDTO::getTableName).filter(Objects::nonNull)
                            .distinct().collect(Collectors.joining("、")));
            boolean multiTable = related.stream().map(StandardAssociationColumnRespDTO::getTableId).distinct()
                    .count() > 1;
            row.setColumnNames(related.stream()
                    .map(field -> multiTable ? field.getTableName() + "." + field.getColumnName() : field.getColumnName())
                    .distinct().toList());
            row.setCreateTime(asset.getCreateTime());
            row.setUpdateTime(asset.getUpdateTime());
            result.add(row);
            selected.put(asset.getId(), asset);
        }
        result.sort(Comparator.comparing(StandardAssociationRespVO::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StandardAssociationRespVO::getId, Comparator.reverseOrder()));
        int start = (int) Math.min((long) (request.getPageNo() - 1) * request.getPageSize(), result.size());
        int end = Math.min(start + request.getPageSize(), result.size());
        var page = result.subList(start, end);
        Map<String, String> names = users.resolveByTexts(
                page.stream().map(row -> selected.get(row.getId()).getCreator()).toList());
        page.forEach(row -> row.setCreatorName(users.nameOf(names, selected.get(row.getId()).getCreator())));
        return new PageResult<>(List.copyOf(page), (long) result.size());
    }

    /**
     * 校验标准关联的请求，不满足约束时拒绝当前操作。
     */
    private void validateRequest(StandardAssociationPageReqVO request) {
        if (request == null || request.getKind() == null || !Set.of("element", "codeset").contains(request.getKind())
                || request.getId() == null || request.getId() <= 0 || request.getPageNo() == null || request.getPageNo() < 1
                || request.getPageSize() == null || request.getPageSize() < 1 || request.getPageSize() > 200) {
            throw exception(BAD_REQUEST);
        }
    }
}
