package cn.iocoder.yudao.module.governance.api.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderStatusRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 质量工单")
public interface QualityWorkOrderApi {

    String PREFIX = ApiConstants.PREFIX + "/quality-work-order";

    /**
     * 由数据集市校核发起质量工单。
     */
    @PostMapping(PREFIX + "/create-from-market")
    @Operation(summary = "由数据集市校核发起质量工单")
    CommonResult<QualityWorkOrderStatusRespDTO> createFromMarket(
            @Valid @RequestBody QualityWorkOrderCreateReqDTO reqDTO);

    /**
     * 查询质量工单状态投影。
     */
    @GetMapping(PREFIX + "/get-status")
    @Operation(summary = "查询质量工单状态投影")
    CommonResult<QualityWorkOrderStatusRespDTO> getStatus(@RequestParam("id") Long id);
}
