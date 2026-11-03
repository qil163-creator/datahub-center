package cn.iocoder.yudao.module.governance.api.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.etl.dto.*;
import cn.iocoder.yudao.module.governance.service.etl.GovernanceEtlService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
public class GovernanceEtlApiImpl implements GovernanceEtlApi {

    @Resource
    private GovernanceEtlService governanceEtlService;

    /**
     * 读取数据行质量计划。
     */
    @Override
    public CommonResult<RowQualityPlanRespDTO> getRowQualityPlan(@Valid RowQualityPlanReqDTO request) {
        return success(governanceEtlService.getRowQualityPlan(request));
    }

    /**
     * 使用持久化函数参数冻结已启用的清洗规则；客户端仅提供字段绑定。
     */
    @Override
    public CommonResult<CleanRulePlanRespDTO> snapshotCleanRulePlan(@Valid CleanRulePlanReqDTO request) {
        return success(governanceEtlService.snapshotCleanRulePlan(request));
    }

    /**
     * 受理内联质量结果。
     */
    @Override
    public CommonResult<InlineQualityResultRespDTO> acceptInlineQualityResult(
            @Valid InlineQualityResultReqDTO request) {
        return success(governanceEtlService.acceptInlineQualityResult(request));
    }

    /**
     * 按 ETL 内联质量报告关联标识读取质量报告。
     */
    @Override
    public CommonResult<InlineQualityReportDTO> queryInlineQualityReport(
            @Valid InlineQualityReportQueryReqDTO request) {
        return success(governanceEtlService.queryInlineQualityReport(request));
    }

    /**
     * 创建内联质量工作工单。
     */
    @Override
    public CommonResult<Long> createInlineQualityWorkOrder(@Valid CreateInlineQualityWorkOrderReqDTO request) {
        return success(governanceEtlService.createInlineQualityWorkOrder(request));
    }

    /**
     * 按保守原则进行有数量上限的归档授权；任何歧义或可见性校验失败都拒绝整个批次。
     */
    @Override
    public CommonResult<Boolean> canArchiveReports(@Valid QualityEtlArchiveAuthorizeReqDTO request) {
        return success(governanceEtlService.canArchiveReports(request));
    }
}
