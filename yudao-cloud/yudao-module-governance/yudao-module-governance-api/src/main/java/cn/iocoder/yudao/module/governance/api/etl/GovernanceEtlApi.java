package cn.iocoder.yudao.module.governance.api.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityResultReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityResultRespDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportQueryReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.CreateInlineQualityWorkOrderReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.QualityEtlArchiveAuthorizeReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.RowQualityPlanReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.RowQualityPlanRespDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 供 ETL 执行服务使用的最小 Governance 契约。 */
@FeignClient(name = ApiConstants.NAME)
public interface GovernanceEtlApi {

    String PREFIX = ApiConstants.PREFIX + "/etl";

    /**
     * 读取数据行质量计划。
     */
    @PostMapping(PREFIX + "/row-quality-plan")
    CommonResult<RowQualityPlanRespDTO> getRowQualityPlan(@Valid @RequestBody RowQualityPlanReqDTO request);

    /**
     * 使用持久化函数参数冻结已启用的清洗规则；客户端仅提供字段绑定。
     */
    @PostMapping(PREFIX + "/clean-rule-plan")
    CommonResult<CleanRulePlanRespDTO> snapshotCleanRulePlan(@Valid @RequestBody CleanRulePlanReqDTO request);

    /**
     * 受理内联质量结果。
     */
    @PostMapping(PREFIX + "/inline-quality-result")
    CommonResult<InlineQualityResultRespDTO> acceptInlineQualityResult(
            @Valid @RequestBody InlineQualityResultReqDTO request);

    /**
     * 按 ETL 内联质量报告关联标识读取质量报告。
     */
    @PostMapping(PREFIX + "/inline-quality-report")
    CommonResult<InlineQualityReportDTO> queryInlineQualityReport(
            @Valid @RequestBody InlineQualityReportQueryReqDTO request);

    /**
     * 创建内联质量工作工单。
     */
    @PostMapping(PREFIX + "/inline-quality-work-order")
    CommonResult<Long> createInlineQualityWorkOrder(@Valid @RequestBody CreateInlineQualityWorkOrderReqDTO request);

    /**
     * 按保守原则进行有数量上限的归档授权；任何歧义或可见性校验失败都拒绝整个批次。
     */
    @PostMapping(PREFIX + "/can-archive-reports")
    CommonResult<Boolean> canArchiveReports(@Valid @RequestBody QualityEtlArchiveAuthorizeReqDTO request);
}
