package cn.iocoder.yudao.module.asset.controller.admin.market;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEligibleApiRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketLifecycleReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryOperationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRecheckReqVO;
import cn.iocoder.yudao.module.asset.service.market.MarketApplyService;
import cn.iocoder.yudao.module.asset.service.market.MarketDeliveryEvidenceService;
import cn.iocoder.yudao.module.asset.service.market.MarketDeliveryReviewService;
import cn.iocoder.yudao.module.asset.service.market.MarketDeliveryDisposalService;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalBatchReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalBatchRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRedeliverReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewPageRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据集市资源申请")
@RestController
@RequestMapping("/asset/market/apply")
@Validated
public class MarketApplyController {

    @Resource
    private MarketApplyService marketApplyService;

    @Resource
    private MarketDeliveryEvidenceService deliveryEvidenceService;

    @Resource
    private MarketDeliveryReviewService deliveryReviewService;

    @Resource
    private MarketDeliveryDisposalService deliveryDisposalService;

    /**
     * 提交资源申请。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "提交资源申请", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了资源申请【{{#reqVO.title}}】，申请编号【{{#_ret.data}}】，资产【{{#reqVO.assetId}}】，应用【{{#reqVO.appId}}】，提交审核【{{#reqVO.submit}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "提交资源申请")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Long> createApply(@Valid @RequestBody MarketApplySaveReqVO reqVO) {
        return success(marketApplyService.createApply(reqVO));
    }

    /**
     * 修改草稿申请。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "修改草稿申请", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "修改了资源申请【{{#reqVO.title}}】（编号{{#reqVO.id}}），资产【{{#reqVO.assetId}}】，校验内容修订【{{#reqVO.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "修改草稿申请")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Boolean> updateApply(@Valid @RequestBody MarketApplySaveReqVO reqVO) {
        marketApplyService.updateApply(reqVO);
        return success(true);
    }

    /**
     * expectedRevision 为客户端所见内容版本；为空时与 {@link #submitApply(Long)} 相同。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "提交草稿进入待审，内容冻结", bizNo = "{{(#id) ?: 0}}",
            success = "提交审核并冻结了资源申请【编号{{#id}}】，校验内容修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/submit")
    @Operation(summary = "提交草稿进入待审，内容冻结")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Boolean> submitApply(@RequestParam("id") Long id,
            @RequestParam(value = "expectedRevision", required = false) Integer expectedRevision) {
        marketApplyService.submitApply(id, expectedRevision);
        return success(true);
    }

    /**
     * 删除草稿/驳回/已撤销申请。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "删除草稿/驳回/已撤销申请", bizNo = "{{(#id) ?: 0}}",
            success = "删除了草稿、驳回或已撤销的资源申请【编号{{#id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除草稿/驳回/已撤销申请")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Boolean> deleteApply(@RequestParam("id") Long id) {
        marketApplyService.deleteApply(id);
        return success(true);
    }

    /**
     * 撤销自己的申请。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "撤销自己的申请", bizNo = "{{(#id) ?: 0}}",
            success = "撤销了资源申请【编号{{#id}}】，校验内容修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/cancel")
    @Operation(summary = "撤销自己的申请")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Boolean> cancelApply(@RequestParam("id") Long id,
            @RequestParam(value = "expectedRevision", required = false) Integer expectedRevision) {
        marketApplyService.cancelApply(id, expectedRevision);
        return success(true);
    }

    /**
     * 审批申请。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "审批申请", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "审批了资源申请【编号{{#reqVO.id}}】，审批状态【{{#reqVO.status}}】，校验内容修订【{{#reqVO.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/check")
    @Operation(summary = "审批申请")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<Boolean> checkApply(@Valid @RequestBody MarketCheckReqVO reqVO) {
        marketApplyService.checkApply(reqVO);
        return success(true);
    }

    /**
     * expectedSourceRevision 为客户端所见交付来源版本；为空时与 {@link #authApply(Long, Integer)} 相同。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "开通或取消授权", bizNo = "{{(#id) ?: 0}}",
            success = "为资源申请【编号{{#id}}】执行了授权控制，授权标识【{{#auth}}】，校验来源修订【{{#expectedSourceRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/auth")
    @Operation(summary = "开通或取消授权")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<Boolean> authApply(@RequestParam("id") Long id, @RequestParam("auth") Integer auth,
            @RequestParam(value = "expectedSourceRevision", required = false) Long expectedSourceRevision) {
        marketApplyService.authApply(id, auth, expectedSourceRevision);
        return success(true);
    }

    /**
     * 收回已通过的授权。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "收回已通过的授权", bizNo = "{{(#id) ?: 0}}",
            success = "收回了资源申请【编号{{#id}}】的授权，校验来源修订【{{#expectedSourceRevision}}】，交付完成状态以回执为准", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/revoke")
    @Operation(summary = "收回已通过的授权")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<Boolean> revokeApply(@RequestParam("id") Long id,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "expectedSourceRevision", required = false) Long expectedSourceRevision) {
        marketApplyService.revokeApply(id, note, expectedSourceRevision);
        return success(true);
    }

    /**
     * 申请详情（含审批轨迹）。
     */
    @GetMapping("/get")
    @Operation(summary = "申请详情（含审批轨迹）")
    @Parameter(name = "id", required = true)
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<MarketApplyRespVO> getApply(@RequestParam("id") Long id) {
        return success(marketApplyService.getApply(id));
    }

    /**
     * 申请分页。
     */
    @GetMapping("/page")
    @Operation(summary = "申请分页")
    @PreAuthorize("@ss.hasAnyPermissions('asset:market:query', 'asset:market:check')")
    public CommonResult<PageResult<MarketApplyRespVO>> getApplyPage(@Valid MarketApplyPageReqVO reqVO) {
        return success(marketApplyService.getApplyPage(reqVO));
    }

    /**
     * 当前人员可代表的应用。
     */
    @GetMapping("/available-apps")
    @Operation(summary = "当前人员可代表的应用")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<List<SupplyAppRespDTO>> availableApps() {
        return success(marketApplyService.listAvailableApps());
    }

    /**
     * 当前资源已绑定、可申请的 API。
     */
    @GetMapping("/available-apis")
    @Operation(summary = "当前资源已绑定、可申请的 API")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<List<MarketEndpointRespVO>> availableApis(@RequestParam("assetId") Long assetId) {
        return success(marketApplyService.listAvailableApis(assetId));
    }

    /**
     * 要求一次性补正材料，不转为驳回。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "要求一次性补正材料，不转为驳回", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "要求资源申请【编号{{#reqVO.id}}】补正材料，校验内容修订【{{#reqVO.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/supplement")
    @Operation(summary = "要求一次性补正材料，不转为驳回")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<Boolean> requestSupplement(@Valid @RequestBody MarketLifecycleReqVO reqVO) {
        marketApplyService.requestSupplement(reqVO.getId(), reqVO.getNote(), reqVO.getExpectedRevision());
        return success(true);
    }

    /**
     * 补正后重新提交，生成新修订快照。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "补正后重新提交，生成新修订快照", bizNo = "{{(#id) ?: 0}}",
            success = "补正后重新提交了资源申请【编号{{#id}}】，校验内容修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/resubmit")
    @Operation(summary = "补正后重新提交，生成新修订快照")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Boolean> resubmitApply(@RequestParam("id") Long id,
            @RequestParam(value = "expectedRevision", required = false) Integer expectedRevision) {
        marketApplyService.resubmitApply(id, expectedRevision);
        return success(true);
    }

    /**
     * 续期已通过申请，生成新修订并重新交付。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "续期已通过申请，生成新修订并重新交付", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "续期了资源申请【编号{{#reqVO.id}}】，新截止时间【{{#reqVO.applyEndTime}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/renew")
    @Operation(summary = "续期已通过申请，生成新修订并重新交付")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Boolean> renewApply(@Valid @RequestBody MarketLifecycleReqVO reqVO) {
        marketApplyService.renewApply(reqVO.getId(), reqVO.getApplyEndTime());
        return success(true);
    }

    /**
     * 创建续期申请：关联原申请的新申请，复制原批准证据并重新走审批。
     *  同一 operationId 的重复提交返回同一条续期申请；原申请的期限与来源保持不变。
     *  独立 READ_COMMITTED 事务提交，不参与调用方外层事务；必须通过 Spring 代理调用。
     *  幂等比较首次请求截止时间（微秒精度），缺少历史证据或复用身份时明确拒绝。
     *
     *  @return 新申请编号
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "提交续期申请（新申请，需重新审批）", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "基于资源申请【编号{{#reqVO.id}}】创建了续期申请【{{#_ret.data}}】，申请截止时间【{{#reqVO.applyEndTime}}】，需重新审批", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/renewal-request")
    @Operation(summary = "提交续期申请（新申请，需重新审批）")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<Long> createRenewal(@Valid @RequestBody MarketRenewalReqVO reqVO) {
        return success(marketApplyService.createRenewal(reqVO));
    }

    /**
     * 暂停有效授权。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "暂停有效授权", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "暂停了资源申请【编号{{#reqVO.id}}】的授权，校验来源修订【{{#reqVO.expectedSourceRevision}}】，交付完成状态以回执为准", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/pause")
    @Operation(summary = "暂停有效授权")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<Boolean> pauseApply(@Valid @RequestBody MarketLifecycleReqVO reqVO) {
        marketApplyService.pauseApply(reqVO.getId(), reqVO.getNote(), reqVO.getExpectedSourceRevision());
        return success(true);
    }

    /**
     * 恢复已暂停授权。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "恢复已暂停授权", bizNo = "{{(#id) ?: 0}}",
            success = "恢复了资源申请【编号{{#id}}】的授权请求，校验来源修订【{{#expectedSourceRevision}}】，完成状态以交付回执为准", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/resume")
    @Operation(summary = "恢复已暂停授权")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<Boolean> resumeApply(@RequestParam("id") Long id,
            @RequestParam(value = "expectedSourceRevision", required = false) Long expectedSourceRevision) {
        marketApplyService.resumeApply(id, expectedSourceRevision);
        return success(true);
    }

    /**
     * 重试待执行或失败的交付操作。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "发起待执行或失败交付重试", bizNo = "0",
            success = "发起了待执行或失败交付的重试，本次受理【{{#_ret.data}}】项，最终结果以各交付回执为准", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/retry-delivery")
    @Operation(summary = "重试待执行或失败的交付操作")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<Integer> retryDeliveries() {
        return success(marketApplyService.retryDeliveries());
    }

    /**
     * 查看原交付操作状态（仅授权提供方）。
     */
    @GetMapping("/delivery-operation")
    @Operation(summary = "查看原交付操作状态（仅授权提供方）")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryOperationRespVO> getDeliveryOperation(
            @RequestParam("operationId") @NotBlank @Size(max = 128) String operationId) {
        return success(marketApplyService.getDeliveryOperation(operationId));
    }

    /**
     * 只读核对原始交付证据；不批准、不迁移、不重发、不证明当前可访问。
     */
    @GetMapping("/delivery-evidence")
    @Operation(summary = "只读核对原始交付证据；不批准、不迁移、不重发、不证明当前可访问")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryEvidenceRespVO> getDeliveryEvidence(
            @RequestParam("operationId") @NotBlank @Size(max = 128) String operationId) {
        return success(deliveryEvidenceService.inspect(operationId));
    }

    /**
     * 人工按原操作身份核查未知结果；不重发交付命令。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "人工按原操作身份核查未知结果；不重发交付命令", bizNo = "{{(#_ret.data.applyId) ?: 0}}",
            success = "核查了交付操作【{{#reqVO.operationId}}】，关联申请【{{#_ret.data.applyId}}】，当前状态【{{#_ret.data.status}}】，未重发命令", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/recheck-delivery")
    @Operation(summary = "人工按原操作身份核查未知结果；不重发交付命令")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryOperationRespVO> recheckDelivery(
            @Valid @RequestBody MarketDeliveryRecheckReqVO reqVO) {
        return success(marketApplyService.recheckDelivery(reqVO));
    }

    /**
     * 受控重投：仅限冻结命令完整匹配且动作是撤销/暂停的 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次；
     *  必须绑定核查决定，GRANT 一律拒绝。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "受控重投历史交付", bizNo = "{{(#_ret.data.applyId) ?: 0}}",
            success = "受控重投了交付操作【{{#reqVO.operationId}}】，关联申请【{{#_ret.data.applyId}}】，当前状态【{{#_ret.data.status}}】，最终结果以回执为准", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/redeliver-delivery")
    @Operation(summary = "交付受控重投：仅限冻结命令完整匹配的撤销/暂停 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次；GRANT 拒绝")
    @PreAuthorize("@ss.hasPermission('asset:market:redeliver')")
    public CommonResult<MarketDeliveryOperationRespVO> redeliverDelivery(
            @Valid @RequestBody MarketDeliveryRedeliverReqVO reqVO) {
        return success(marketApplyService.redeliverDelivery(reqVO));
    }

    /**
     * 记录待核查结论与证据审计；不恢复、撤销或重新投递授权。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "记录待核查结论与证据审计；不恢复、撤销或重新投递授权", bizNo = "{{(#_ret.data.applyId) ?: 0}}",
            success = "记录了交付操作【{{#reqVO.operationId}}】的核查结论【{{#reqVO.decision}}】，关联申请【{{#_ret.data.applyId}}】，核查记录【{{#_ret.data.id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/delivery-review")
    @Operation(summary = "记录待核查结论与证据审计；不恢复、撤销或重新投递授权")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryReviewRespVO> recordDeliveryReview(
            @Valid @RequestBody MarketDeliveryReviewReqVO reqVO) {
        return success(deliveryReviewService.record(reqVO));
    }

    /**
     * 历史交付实际处置：收回授权，或在原回执与正式批准均匹配时确认原批准。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "历史交付实际处置：收回授权，或在原回执与正式批准均匹配时确认原批准", bizNo = "{{(#_ret.data.applyId) ?: 0}}",
            success = "处置了交付操作【{{#reqVO.operationId}}】，动作【{{#reqVO.action}}】，关联申请【{{#_ret.data.applyId}}】，处置记录【{{#_ret.data.id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/delivery-disposal")
    @Operation(summary = "历史交付实际处置：收回授权，或在原回执与正式批准均匹配时确认原批准")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryReviewRespVO> disposeDelivery(
            @Valid @RequestBody MarketDeliveryDisposalReqVO reqVO) {
        return success(deliveryDisposalService.dispose(reqVO));
    }

    /**
     * 历史交付批量处置：逐条独立执行并逐条返回结果（最多二十条，部分成功不整体回滚）。
     */
    @LogRecord(type = "数据资产-数据集市资源申请", subType = "批量处置历史交付", bizNo = "{{(#_ret.data.items.?[success].![result.applyId]) ?: 0}}",
            success = "批量处置了历史交付，成功申请编号集合【{{#_ret.data.items.?[success].![result.applyId]}}】，成功【{{#_ret.data.items.?[success].size()}}】项；失败项不记作成功", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/delivery-disposal-batch")
    @Operation(summary = "历史交付批量处置：逐条独立执行并逐条返回结果（最多二十条，部分成功不整体回滚）")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryDisposalBatchRespVO> disposeDeliveryBatch(
            @Valid @RequestBody MarketDeliveryDisposalBatchReqVO reqVO) {
        return success(deliveryDisposalService.disposeBatch(reqVO));
    }

    /**
     * 查看原交付核查结论历史（每页最多五十项）。
     */
    @GetMapping("/delivery-reviews")
    @Operation(summary = "查看原交付核查结论历史（每页最多五十项）")
    @PreAuthorize("@ss.hasPermission('asset:market:auth')")
    public CommonResult<MarketDeliveryReviewPageRespVO> getDeliveryReviews(
            @RequestParam("operationId") @NotBlank @Size(max = 128) String operationId,
            @RequestParam(value = "afterId", defaultValue = "0") @Min(0) long afterId) {
        return success(deliveryReviewService.history(operationId, afterId));
    }

    /**
     * 当前资产可证明关联的已绑定 API。
     */
    @GetMapping("/eligible-apis")
    @Operation(summary = "当前资产可证明关联的已绑定 API")
    @PreAuthorize("@ss.hasPermission('asset:market:apply')")
    public CommonResult<List<MarketEligibleApiRespVO>> listEligibleApis(@RequestParam("assetId") Long assetId) {
        return success(marketApplyService.listEligibleApis(assetId));
    }

}
