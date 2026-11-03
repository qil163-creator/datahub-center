package cn.iocoder.yudao.module.governance.api.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlBatchRunReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityTaskResultRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderRecheckAttachReqDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 质量任务 RPC：供资产质量任务复用治理质量引擎。
 *
 * @author Li
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 质量任务")
public interface QualityTaskApi {

    String PREFIX = ApiConstants.PREFIX + "/quality-task";

    /**
     * 校验研发执行身份与质量任务。
     */
    @GetMapping(PREFIX + "/validate-for-run")
    @Operation(summary = "校验研发执行身份与质量任务")
    CommonResult<Boolean> validateForRun(@RequestParam("qualityTaskId") Long qualityTaskId,
            @RequestParam("executionUserId") Long executionUserId);

    /**
     * 按研发运行键幂等提交质量检查。
     */
    @PostMapping(PREFIX + "/execute-for-run")
    @Operation(summary = "按研发运行键幂等提交质量检查")
    CommonResult<Long> executeForRun(
            @Valid @RequestBody cn.iocoder.yudao.module.governance.api.quality.dto.QualityRunReqDTO request);

    /**
     * 对 DataForge 已提交批次按已有任务生成真实质量实例。
     */
    @PostMapping(PREFIX + "/execute-for-etl-batch")
    @Operation(summary = "对 DataForge 已提交批次按已有任务生成真实质量实例")
    CommonResult<Long> executeForEtlBatch(@Valid @RequestBody QualityEtlBatchRunReqDTO request);

    /**
     * 授权读取 ETL 工单源端整改复检所需的原始完整人口与目标清单。
     */
    @PostMapping(PREFIX + "/describe-etl-work-order-replay")
    @Operation(summary = "授权读取 ETL 工单源端整改复检所需的原始完整人口与目标清单")
    CommonResult<QualityEtlWorkOrderReplayDescribeRespDTO> describeEtlWorkOrderReplay(
            @Valid @RequestBody QualityEtlWorkOrderReplayDescribeReqDTO request);

    /**
     * 将已持久化的 ETL 目标批次真实质量实例关联至工单复检轮次。
     */
    @PostMapping(PREFIX + "/attach-etl-work-order-recheck")
    @Operation(summary = "将已持久化的 ETL 目标批次真实质量实例关联至工单复检轮次")
    CommonResult<Long> attachEtlWorkOrderRecheck(@Valid @RequestBody QualityEtlWorkOrderRecheckAttachReqDTO request);

    /**
     * 取得本次研发关联的质量实例结果。
     */
    @GetMapping(PREFIX + "/run-result")
    @Operation(summary = "取得本次研发关联的质量实例结果")
    CommonResult<QualityTaskResultRespDTO> getRunResult(@RequestParam("instanceId") Long instanceId,
            @RequestParam("executionUserId") Long executionUserId);


    /**
     * 资产质量任务同步为治理质量任务（编码 ASSET-{assetTaskId}）
     */
    @PostMapping(PREFIX + "/sync-from-asset")
    @Operation(summary = "按资产质量任务同步/更新治理质量任务")
    CommonResult<Long> syncFromAsset(@Valid @RequestBody QualityAssetSyncReqDTO reqDTO);

    /**
     * 手动执行质量任务。
     */
    @PostMapping(PREFIX + "/execute")
    @Operation(summary = "手动执行质量任务")
    CommonResult<Long> execute(@RequestParam("qualityTaskId") Long qualityTaskId);

    /**
     * 最近一次执行结果；无实例时返回 null
     */
    @GetMapping(PREFIX + "/latest-result")
    @Operation(summary = "获得质量任务最近一次执行结果")
    CommonResult<QualityTaskResultRespDTO> getLatestResult(
            @RequestParam("qualityTaskId") Long qualityTaskId);

    /**
     * 删除质量任务。
     */
    @PostMapping(PREFIX + "/delete")
    @Operation(summary = "删除质量任务")
    CommonResult<Boolean> delete(@RequestParam("qualityTaskId") Long qualityTaskId);

}
