package cn.iocoder.yudao.module.dataforge.api.migration;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryDTO;
import cn.iocoder.yudao.module.dataforge.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = ApiConstants.NAME, contextId = "dataforge-sync-batch-evidence-v1",
        configuration = SyncBatchEvidenceClientConfiguration.class)
@Tag(name = "RPC 服务 - 受管同步批次诊断")
public interface SyncBatchEvidenceApi {

    String PREFIX = ApiConstants.PREFIX + "/sync-batch-evidence/v1";

    /**
     * 按执行身份观察一个目标表的有界批次事实，不构成到数证明。
     *
     * @param request 请求参数
     * @return 同步批次观测
     */
    @PostMapping(PREFIX + "/observe")
    @Operation(summary = "按执行身份观察一个目标表的有界批次事实，不构成到数证明")
    CommonResult<SyncBatchObservationDTO> observe(@Valid @RequestBody SyncBatchQueryReqDTO request);

    /**
     * 登记或对账已提交整改的稳定顺序边界；超时只能用同键重试。
     *
     * @param request 请求参数
     * @return 同步边界
     */
    @PostMapping(PREFIX + "/boundary")
    @Operation(summary = "登记或对账已提交整改的稳定顺序边界；超时只能用同键重试")
    CommonResult<SyncBoundaryDTO> ensureBoundary(@Valid @RequestBody SyncBoundaryReqDTO request);
}
