package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetRegistrationSourceDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
public class AssetRegistrationService {

    @Resource
    private DataAssetService assetService;

    @Resource
    private MetadataApi metadataApi;

    @Resource
    private SupplyDataApiApi supplyApi;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetRegistrationWriter writer;

    @Resource
    private AssetRegistrationConfig config;

    /**
     * 读取真实元数据/已发布 API 注册候选，平台注册人员可见，不代表技术交付授权。
     */
    public PageResult<AssetRegistrationCandidateRespVO> candidates(AssetRegistrationCandidatePageReqVO req) {
        // 与地图维护使用同一登录身份核验。候选为平台注册人员可读的元数据，不是数据访问授权。
        assetService.getAssetFacets();
        PageResult<AssetRegistrationCandidateRespVO> page;
        if (Objects.equals(req.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            var source = metadataApi.getPublishedTablePage(req.getPageNo(), req.getPageSize(), req.getKeyword(), false)
                    .getCheckedData();
            page = new PageResult<>(BeanUtils.toBean(source.getList(), AssetRegistrationCandidateRespVO.class),
                    source.getTotal());
        } else if (Objects.equals(req.getAssetType(), DataAssetDO.TYPE_FILE)) {
            var source = metadataApi.getCurrentFilePage(req.getPageNo(), req.getPageSize(), req.getKeyword())
                    .getCheckedData();
            var rows = source.getList().stream().map(file -> {
                var view = BeanUtils.toBean(file, AssetRegistrationCandidateRespVO.class);
                view.setFileName(file.getName());
                view.setStorageSize(file.getSize());
                return view;
            }).toList();
            page = new PageResult<>(rows, source.getTotal());
        } else if (Objects.equals(req.getAssetType(), DataAssetDO.TYPE_API)) {
            var source = supplyApi.getPublishedCandidatePage(req.getPageNo(), req.getPageSize(), req.getKeyword())
                    .getCheckedData();
            page = new PageResult<>(BeanUtils.toBean(source.getList(), AssetRegistrationCandidateRespVO.class),
                    source.getTotal());
        } else {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "资产类型无效");
        }
        var candidateIds = page.getList().stream().map(AssetRegistrationCandidateRespVO::getId).toList();
        var urns = candidateIds.stream().map(id -> resourceUrn(req.getAssetType(), id)).toList();
        Set<String> registered = Set.of();
        if (!candidateIds.isEmpty()) {
            var query = Wrappers.<DataAssetDO>lambdaQuery();
            if (req.getAssetType() == DataAssetDO.TYPE_DATABASE_TABLE) {
                query.in(DataAssetDO::getMetaTableId, candidateIds);
            } else {
                query.in(DataAssetDO::getResourceUrn, urns);
            }
            registered = assetMapper.selectList(query).stream().map(asset -> req.getAssetType() == 1
                    ? String.valueOf(asset.getMetaTableId()) : asset.getResourceUrn()).collect(Collectors.toSet());
        }
        for (var row : page.getList()) {
            row.setAssetType(req.getAssetType());
            String key = req.getAssetType() == 1 ? String.valueOf(row.getId()) : resourceUrn(req.getAssetType(),
                    row.getId());
            row.setRegistered(Objects.equals(row.getCatalogLinked(), 1) || registered.contains(key));
        }
        return page;
    }

    /**
     * 从真实提供方注册 API 或文件资产，尚未验证的交付保持不可用。
     */
    public List<Long> register(AssetGenericRegisterReqVO req) {
        var options = assetService.getAssetFacets();
        if (!Objects.equals(req.getAssetType(), DataAssetDO.TYPE_FILE) && !Objects.equals(req.getAssetType(),
                DataAssetDO.TYPE_API)) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "仅支持 API 或文件注册");
        }
        List<AssetRegistrationWriter.Prepared> prepared = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (var item : req.getItems()) {
            if (!ids.add(item.getCandidateId())) {
                throw exception(DATA_ASSET_META_DUPLICATE);
            }
            if (item.getCategoryId() != null && options.getCategories().stream()
                    .noneMatch(f -> f.getKey().equals(String.valueOf(item.getCategoryId())))) {
                throw exception(CATEGORY_DATA_ASSET_NOT_EXISTS);
            }
            var resolvedConfig = item.getRegistrationConfig() == null ? new AssetRegistrationConfig.Resolved(null, null)
                    : config.resolve(item.getRegistrationConfig(), item.getSubjectDomainId());
            String subjectName = resolvedConfig.subjectDomainName();
            if ((item.getRegistrationConfig() == null || item.getRegistrationConfig()
                    .getDataDomainId() == null) && item.getSubjectDomainId() != null) {
                subjectName = options.getSubjectDomains().stream()
                        .filter(f -> f.getKey().equals(String.valueOf(item.getSubjectDomainId())))
                        .map(DataAssetFacetRespVO.Facet::getLabel)
                        .findFirst()
                        .orElseThrow(() -> exception(DATA_ASSET_CATALOG_NOT_READY, "主题域不存在或当前不可见"));
            }
            String urn = resourceUrn(req.getAssetType(), item.getCandidateId());
            if (assetMapper.selectCount(Wrappers.<DataAssetDO>lambdaQuery().eq(DataAssetDO::getResourceUrn, urn)) > 0) {
                throw exception(DATA_ASSET_META_DUPLICATE);
            }
            var source = AssetRegistrationSourceDO.builder().sourceKind(req.getAssetType())
                    .sourceId(item.getCandidateId()).build();
            Long datasourceId = null;
            String system = null;
            if (Objects.equals(req.getAssetType(), DataAssetDO.TYPE_FILE)) {
                var file = metadataApi.getCurrentFile(item.getCandidateId()).getCheckedData();
                if (file == null || !Objects.equals(file.getId(), item.getCandidateId())) {
                    throw exception(DATA_ASSET_META_NOT_EXISTS);
                }
                source.setSourceName(file.getName());
                source.setFileType(file.getFileType());
                source.setSourcePath(file.getPath());
                source.setStorageSize(file.getSize());
                source.setSourceVersion(Objects.toString(file.getBizRevision(), null));
                datasourceId = file.getDatasourceId();
                system = file.getSourceSystemName();
            } else {
                var api = supplyApi.getPublishedApiDocument(item.getCandidateId()).getCheckedData();
                if (api == null || !Objects.equals(api.getId(),
                        item.getCandidateId()) || api.getActiveReleaseId() == null || StrUtil.isBlank(
                        api.getContractHash())) {
                    throw exception(DATA_ASSET_META_NOT_PUBLISHED);
                }
                source.setSourceName(api.getName());
                source.setSourcePath(api.getPath());
                source.setSourceVersion(String.valueOf(api.getActiveReleaseId()));
            }
            var asset = DataAssetDO.builder()
                    .assetCode((req.getAssetType() == 3 ? "FILE-" : "API-") + item.getCandidateId())
                    .catalogItemUrn(
                            "urn:datahub:asset:" + (req.getAssetType() == 3 ? "file:" : "api:") + item.getCandidateId())
                    .assetName(item.getAssetName().trim()).assetType(req.getAssetType())
                    .categoryId(item.getCategoryId())
                    .subjectDomainId(item.getSubjectDomainId()).subjectDomainName(subjectName)
                    .resourceUrn(urn).datasourceId(datasourceId).sourceSystemName(system)
                    .description(item.getDescription()).registrationConfigJson(resolvedConfig.json())
                    .storageSize(source.getStorageSize()).status(DataAssetDO.STATUS_UNPUBLISHED)
                    .ownerDeptId(SecurityFrameworkUtils.getLoginUserDeptId())
                    .providerDeptId(SecurityFrameworkUtils.getLoginUserDeptId())
                    .techLeaderUserId(SecurityFrameworkUtils.getLoginUserId()).viewCount(0L)
                    .shareAttribute(DataAssetDO.SHARE_PENDING).shareAttr(DataAssetDO.SHARE_PENDING)
                    .techAvailable(0).catalogRevision(0L).build();
            prepared.add(new AssetRegistrationWriter.Prepared(asset, source));
        }
        try {
            return writer.persist(prepared);
        } catch (DuplicateKeyException error) {
            throw exception(DATA_ASSET_META_DUPLICATE);
        }
    }

    /**
     * 生成登记资源的稳定 URN 标识。
     */
    static String resourceUrn(int type, Long id) {
        if (type == DataAssetDO.TYPE_FILE) {
            return "urn:datahub:governance:file:" + id;
        }
        if (type == DataAssetDO.TYPE_API) {
            return "urn:datahub:supply:api:" + id;
        }
        return "urn:datahub:governance:table:" + id;
    }
}
