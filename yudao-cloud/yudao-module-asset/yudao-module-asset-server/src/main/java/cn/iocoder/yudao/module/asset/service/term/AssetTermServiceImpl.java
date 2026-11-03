package cn.iocoder.yudao.module.asset.service.term;

import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.term.AssetTermColumnRelDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.term.AssetTermColumnRelMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApi;
import cn.iocoder.yudao.module.governance.api.standardterm.dto.StandardTermRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 资产仅拥有字段关联；术语定义、启停与删除均由治理模块维护。 */
@Service
@Validated
@Slf4j
public class AssetTermServiceImpl implements AssetTermService {

    @Resource
    private StandardTermApi standardTermApi;

    @Resource
    private AssetTermColumnRelMapper termColumnRelMapper;

    @Resource
    private DataAssetMapper dataAssetMapper;

    @Resource
    private AssetCatalogService catalogService;

    @Resource
    private MetadataApi metadataApi;

    @Resource
    private AssetTermBindingWriter bindingWriter;

    /**
     * 治理模块启用中的业务术语，供资产字段选择。
     */
    @Override
    public List<AssetTermRespVO> getSimpleTermList(String keyword) {
        return standardTermApi.getEnabledTermList(keyword).getCheckedData().stream()
                .map(this::toResponse).toList();
    }

    /**
     * termId 是治理业务术语编号；为空表示解绑。
     */
    @Override
    public void bindColumn(AssetTermBindReqVO reqVO) {
        var asset = dataAssetMapper.selectById(reqVO.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalogService.assertCanMaintain(asset);
        // 解绑不依赖治理或元数据服务，已删除术语/字段也可以清理关联。
        MetadataColumnRespDTO column = null;
        if (reqVO.getTermId() != null) {
            var term = standardTermApi.getTermList(List.of(reqVO.getTermId())).getCheckedData().stream()
                    .filter(item -> Objects.equals(item.getId(), reqVO.getTermId()))
                    .findFirst().orElseThrow(() -> exception(ASSET_TERM_NOT_EXISTS));
            if (!Objects.equals(term.getStatus(), (short) 0)) {
                throw exception(ASSET_TERM_DISABLED);
            }
            if (asset.getMetaTableId() == null
                    && !cn.iocoder.yudao.module.asset.service.dataasset.AssetImportSnapshotService.supports(asset)) {
                throw exception(ASSET_TERM_COLUMN_INVALID);
            }
            var columns = cn.iocoder.yudao.module.asset.service.dataasset.AssetImportSnapshotService.supports(asset)
                    ? catalogService.listColumns(asset.getId(), null)
                    : metadataApi.getColumnList(asset.getMetaTableId()).getCheckedData();
            column = columns.stream()
                    .filter(item -> Objects.equals(item.getId(), reqVO.getMetaColumnId()))
                    .findFirst().orElseThrow(() -> exception(ASSET_TERM_COLUMN_INVALID));
        }
        // 远程读取和对象授权在持锁事务之前完成。治理后续启停/删除不抹掉关联事实。
        bindingWriter.bind(asset, reqVO, column == null ? null : column.getColumnName());
    }

    /**
     * 由资产详情既有读取权限保护的字段关联：metaColumnId -> 治理术语。
     */
    @Override
    public Map<Long, AssetTermRespVO> getColumnTermMap(Long assetId) {
        List<AssetTermColumnRelDO> relations = termColumnRelMapper.selectListByAssetId(assetId);
        List<Long> ids = relations.stream().map(AssetTermColumnRelDO::getStandardTermId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AssetTermRespVO> terms = new HashMap<>();
        Set<Long> unavailable = new HashSet<>();
        for (int start = 0; start < ids.size(); start += 200) {
            List<Long> batch = ids.subList(start, Math.min(start + 200, ids.size()));
            try {
                standardTermApi.getTermList(batch).getCheckedData()
                        .forEach(term -> terms.put(term.getId(), toResponse(term)));
            } catch (RuntimeException ex) {
                // 术语解释是可选展示，不让治理故障阻断整个资产详情；写操作仍失败关闭。
                unavailable.addAll(batch);
                log.warn("[getColumnTermMap][治理术语暂不可用: assetId={}, count={}, errorType={}]",
                        assetId, batch.size(), ex.getClass().getSimpleName());
            }
        }
        Map<Long, AssetTermRespVO> result = new HashMap<>();
        for (AssetTermColumnRelDO relation : relations) {
            Long id = relation.getStandardTermId();
            if (id == null) {
                continue;
            }
            AssetTermRespVO term = terms.get(id);
            if (term == null) {
                // 保留失效引用的编号供用户解除/更换，不能误显示为从未绑定。
                term = new AssetTermRespVO();
                term.setId(id);
                term.setName((unavailable.contains(id) ? "术语暂不可用（" : "术语已删除或不可见（") + id + "）");
                term.setStatus(unavailable.contains(id) ? null : 1);
            }
            result.put(relation.getMetaColumnId(), term);
        }
        return result;
    }

    /**
     * 按资产编号删除资产术语记录。
     */
    @Override
    public void deleteByAssetId(Long assetId) {
        termColumnRelMapper.deleteByAssetId(assetId);
    }

    /**
     * 将资产术语业务记录转换为接口响应。
     */
    private AssetTermRespVO toResponse(StandardTermRespDTO term) {
        AssetTermRespVO result = new AssetTermRespVO();
        result.setId(term.getId());
        result.setName(term.getName());
        result.setDescription(term.getDefinition());
        result.setStatus(term.getStatus() == null ? null : term.getStatus().intValue());
        return result;
    }
}
