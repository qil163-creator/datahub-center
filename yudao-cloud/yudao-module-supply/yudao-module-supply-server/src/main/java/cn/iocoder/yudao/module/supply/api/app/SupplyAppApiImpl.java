package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppApiGrantReqDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantOpRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.service.app.SupplyAppService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
public class SupplyAppApiImpl implements SupplyAppApi {

    @Resource
    private SupplyAppService supplyAppService;

    /**
     * 为应用授权订阅 API。
     *
     * @param reqDTO 请求参数
     * @return 供应应用
     */
    @Override
    public CommonResult<Long> grantAppApi(SupplyAppApiGrantReqDTO reqDTO) {
        AppApiSaveReqVO saveReqVO = new AppApiSaveReqVO();
        saveReqVO.setAppId(reqDTO.getAppId());
        saveReqVO.setApiId(reqDTO.getApiId());
        saveReqVO.setForeverFlag(reqDTO.getForeverFlag() == null ? 1 : reqDTO.getForeverFlag());
        saveReqVO.setStartDate(reqDTO.getStartDate());
        saveReqVO.setEndDate(reqDTO.getEndDate());
        saveReqVO.setDescription(reqDTO.getDescription());
        saveReqVO.setPermissionUserId(reqDTO.getPermissionUserId());
        saveReqVO.setOperationId(reqDTO.getOperationId());
        saveReqVO.setSourceSystem(reqDTO.getSourceSystem());
        saveReqVO.setSourceDecisionId(reqDTO.getSourceDecisionId());
        saveReqVO.setSourceType(reqDTO.getSourceType());
        saveReqVO.setPurpose(reqDTO.getPurpose());
        saveReqVO.setValidFrom(reqDTO.getValidFrom());
        saveReqVO.setValidTo(reqDTO.getValidTo());
        saveReqVO.setGrantRevision(reqDTO.getGrantRevision());
        saveReqVO.setApprovedContractHash(reqDTO.getApprovedContractHash());
        saveReqVO.setApiVersion(reqDTO.getApiVersion());
        return success(supplyAppService.grantAppApi(saveReqVO));
    }

    /**
     * 取消应用对 API 的授权。
     *
     * @param reqDTO 请求参数
     * @return 供应应用
     */
    @Override
    public CommonResult<Boolean> revokeAppApi(SupplyAppApiGrantReqDTO reqDTO) {
        AppApiSaveReqVO saveReqVO = new AppApiSaveReqVO();
        saveReqVO.setAppId(reqDTO.getAppId());
        saveReqVO.setApiId(reqDTO.getApiId());
        saveReqVO.setOperationId(reqDTO.getOperationId());
        saveReqVO.setSourceSystem(reqDTO.getSourceSystem());
        saveReqVO.setSourceDecisionId(reqDTO.getSourceDecisionId());
        saveReqVO.setGrantRevision(reqDTO.getGrantRevision());
        supplyAppService.revokeAppApi(saveReqVO);
        return success(true);
    }

    /**
     * 查询授权交付结果。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权操作响应数据
     */
    @Override
    public CommonResult<SupplyGrantOpRespDTO> getGrantOperation(String sourceSystem, String operationId) {
        return success(supplyAppService.getGrantOperation(sourceSystem, operationId));
    }

    /**
     * 按创建人列出应用。
     *
     * @param userId 用户编号
     * @return 按创建人列出应用结果集合
     */
    @Override
    public CommonResult<List<SupplyAppRespDTO>> listAppsByCreator(Long userId) {
        List<SupplyAppRespDTO> list = BeanUtils.toBean(supplyAppService.listAppsByCreator(userId),
                SupplyAppRespDTO.class);
        list.forEach(item -> item.setSecret(null));
        return success(list);
    }

}
