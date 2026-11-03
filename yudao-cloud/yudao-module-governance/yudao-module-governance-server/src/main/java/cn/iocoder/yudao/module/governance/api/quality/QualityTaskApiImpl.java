package cn.iocoder.yudao.module.governance.api.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlBatchRunReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityTaskResultRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderRecheckAttachReqDTO;
import cn.iocoder.yudao.module.governance.service.quality.QualityTaskService;
import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 质量任务 RPC 实现
 *
 * @author Li
 */
@RestController
@Validated
public class QualityTaskApiImpl implements QualityTaskApi {

    @Resource
    private QualityTaskService qualityTaskService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.quality.QualityRunService runService;

    @Resource
    private QualityWorkOrderService workOrderService;

    /**
     * 校验研发执行身份与质量任务。
     */
    @Override
    public CommonResult<Boolean> validateForRun(Long qualityTaskId, Long executionUserId) {
        runService.validate(qualityTaskId, executionUserId);
        return success(true);
    }

    /**
     * 按研发运行键幂等提交质量检查。
     */
    @Override
    public CommonResult<Long> executeForRun(
            cn.iocoder.yudao.module.governance.api.quality.dto.QualityRunReqDTO request) {
        return success(runService.submit(request));
    }

    /**
     * 对 DataForge 已提交批次按已有任务生成真实质量实例。
     */
    @Override
    public CommonResult<Long> executeForEtlBatch(QualityEtlBatchRunReqDTO request) {
        return success(runService.submitEtlBatch(request));
    }

    /**
     * 授权读取 ETL 工单源端整改复检所需的原始完整人口与目标清单。
     */
    @Override
    public CommonResult<QualityEtlWorkOrderReplayDescribeRespDTO> describeEtlWorkOrderReplay(
            QualityEtlWorkOrderReplayDescribeReqDTO request) {
        return success(workOrderService.describeEtlWorkOrderReplay(request));
    }

    /**
     * 将已持久化的 ETL 目标批次真实质量实例关联至工单复检轮次。
     */
    @Override
    public CommonResult<Long> attachEtlWorkOrderRecheck(QualityEtlWorkOrderRecheckAttachReqDTO request) {
        return success(workOrderService.attachEtlWorkOrderRecheck(request));
    }

    /**
     * 取得本次研发关联的质量实例结果。
     */
    @Override
    public CommonResult<QualityTaskResultRespDTO> getRunResult(Long instanceId, Long executionUserId) {
        return success(runService.result(instanceId, executionUserId));
    }

    /**
     * 资产质量任务同步为治理质量任务（编码 ASSET-{assetTaskId}）
     */
    @Override
    public CommonResult<Long> syncFromAsset(QualityAssetSyncReqDTO reqDTO) {
        return success(qualityTaskService.syncFromAsset(reqDTO));
    }

    /**
     * 手动执行质量任务。
     */
    @Override
    public CommonResult<Long> execute(Long qualityTaskId) {
        return success(qualityTaskService.executeTask(qualityTaskId));
    }

    /**
     * 最近一次执行结果；无实例时返回 null
     */
    @Override
    public CommonResult<QualityTaskResultRespDTO> getLatestResult(Long qualityTaskId) {
        return success(qualityTaskService.getLatestResult(qualityTaskId));
    }

    /**
     * 删除质量任务。
     */
    @Override
    public CommonResult<Boolean> delete(Long qualityTaskId) {
        qualityTaskService.deleteTask(qualityTaskId);
        return success(true);
    }

}
