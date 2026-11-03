package cn.iocoder.yudao.module.governance.api.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderStatusRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
public class QualityWorkOrderApiImpl implements QualityWorkOrderApi {

    @Resource
    private QualityWorkOrderService qualityWorkOrderService;

    /**
     * 由数据集市校核发起质量工单。
     */
    @Override
    public CommonResult<QualityWorkOrderStatusRespDTO> createFromMarket(QualityWorkOrderCreateReqDTO reqDTO) {
        QualityWorkOrderSaveReqVO create = new QualityWorkOrderSaveReqVO();
        create.setOrderName(reqDTO.getOrderName());
        create.setDescription(reqDTO.getDescription());
        create.setProblemType(reqDTO.getProblemType());
        create.setSourceType(reqDTO.getSourceType() == null ? "MARKET_CHECK" : reqDTO.getSourceType());
        Long id = qualityWorkOrderService.createWorkOrder(create);
        return getStatus(id);
    }

    /**
     * 查询质量工单状态投影。
     */
    @Override
    public CommonResult<QualityWorkOrderStatusRespDTO> getStatus(Long id) {
        QualityWorkOrderStatusRespDTO dto = new QualityWorkOrderStatusRespDTO();
        var vo = qualityWorkOrderService.getWorkOrder(id);
        if (vo == null) {
            return success(null);
        }
        dto.setId(vo.getId());
        dto.setOrderCode(vo.getOrderCode());
        dto.setOrderStatus(vo.getOrderStatus());
        dto.setCloseType(vo.getCloseType());
        dto.setOwnerUserId(vo.getOwnerUserId());
        return success(dto);
    }
}
