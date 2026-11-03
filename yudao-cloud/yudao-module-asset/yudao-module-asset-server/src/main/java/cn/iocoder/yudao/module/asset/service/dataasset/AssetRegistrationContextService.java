package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetRegistrationNamePreviewReqVO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.modeling.api.registration.RegistrationContextApi;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 资产仅代理合法命名上下文；建模规则不在资产模块重新实现。 */
@Service
public class AssetRegistrationContextService {

    @Resource
    private RegistrationContextApi contextApi;

    @Resource
    private MetadataApi metadataApi;

    @Resource
    private AssetCatalogService catalog;

    /**
     * 读取已启用的真实建模选项，板块与市场分类独立。
     */
    public RegistrationContextRespDTO context(Long dataDomainId, String tableType, Long subjectDomainId) {
        catalog.prepareMaintenanceAuthorization();
        return contextApi.context(dataDomainId, tableType, subjectDomainId).getCheckedData();
    }

    /**
     * 按真实候选与持久建模规则生成推荐名称，不修改物理表。
     */
    public RegistrationNameRespDTO preview(AssetRegistrationNamePreviewReqVO input) {
        catalog.prepareMaintenanceAuthorization();
        var table = metadataApi.getTable(input.getCandidateId()).getCheckedData();
        if (table == null || !Objects.equals(table.getId(), input.getCandidateId())) {
            throw exception(DATA_ASSET_META_NOT_EXISTS);
        }
        if (!Objects.equals(table.getPublishStatus(), 1)) {
            throw exception(DATA_ASSET_META_NOT_PUBLISHED);
        }
        var request = BeanUtils.toBean(input, RegistrationNameReqDTO.class);
        request.setSourceTableName(table.getTableName());
        request.setSourceTableComment(table.getSourceComment());
        return contextApi.preview(request).getCheckedData();
    }
}
