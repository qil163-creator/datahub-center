package cn.iocoder.yudao.module.governance.controller.admin.rule;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.*;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleWorkbenchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 展示脱敏规则工作台")
@RestController
@RequestMapping("/governance/safety-rule/workbench")
@Validated
public class SafetyRuleWorkbenchController {

    @Resource
    private SafetyRuleWorkbenchService workbench;

    /**
     * 获得展示脱敏规则分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得展示脱敏规则分页")
    @PreAuthorize("@ss.hasPermission('governance:rule:query')")
    public CommonResult<PageResult<SafetyRuleWorkbenchRespVO>> page(@Valid SafetyRuleWorkbenchPageReqVO request) {
        return success(workbench.page(request));
    }

    /**
     * 获得展示脱敏规则详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得展示脱敏规则详情")
    @PreAuthorize("@ss.hasPermission('governance:rule:query')")
    public CommonResult<SafetyRuleWorkbenchRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(workbench.get(id));
    }

    /**
     * 获得规则工作台分类树及实际可选配置。
     */
    @GetMapping("/options")
    @Operation(summary = "获得规则工作台分类树及实际可选配置")
    @PreAuthorize("@ss.hasPermission('governance:rule:query')")
    public CommonResult<SafetyRuleWorkbenchOptionsRespVO> options() {
        return success(workbench.options());
    }

    /**
     * 获得明确绑定此规则的真实资产敏感记录。
     */
    @GetMapping("/sensitive-page")
    @Operation(summary = "获得明确绑定此规则的真实资产敏感记录")
    @PreAuthorize("@ss.hasPermission('governance:rule:query')")
    public CommonResult<PageResult<cn.iocoder.yudao.module.asset.api.sensitive.dto.AssetSensitiveFactRespDTO>> sensitivePage(
            @Valid cn.iocoder.yudao.module.asset.api.sensitive.dto.AssetRuleSensitivePageReqDTO request) {
        return success(workbench.sensitivePage(request));
    }

    /**
     * 新建展示脱敏规则并关联分类。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-展示脱敏规则工作台", subType = "新建展示脱敏规则并关联分类",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了展示脱敏规则【{{(#request.ruleName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "新建展示脱敏规则并关联分类")
    @PreAuthorize("@ss.hasPermission('governance:rule:create')")
    public CommonResult<Long> create(@Valid @RequestBody SafetyRuleWorkbenchSaveReqVO request) {
        return success(workbench.create(request));
    }

    /**
     * 修改展示脱敏规则。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-展示脱敏规则工作台", subType = "修改展示脱敏规则",
            bizNo = "{{(#request.id) ?: 0}}", success = "修改了展示脱敏规则，原名称【{{(#auditObjectName) ?: '编号' + #request.id}}】，提交名称【{{(#request.ruleName) ?: '未设置'}}】，目标状态【{{(#request.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改展示脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody SafetyRuleWorkbenchSaveReqVO request) {
        captureAuditContext(request.getId());
        workbench.update(request);
        return success(true);
    }

    /**
     * 修改展示脱敏规则状态。
     */
    @PutMapping("/change-status")
    @LogRecord(type = "数据治理-展示脱敏规则工作台", subType = "修改展示脱敏规则状态",
            bizNo = "{{(#request.id) ?: 0}}", success = "调整了展示脱敏规则【{{(#auditObjectName) ?: '编号' + #request.id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#request.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改展示脱敏规则状态")
    @PreAuthorize("@ss.hasPermission('governance:rule:update')")
    public CommonResult<Boolean> changeStatus(@Valid @RequestBody SafetyRuleWorkbenchStatusReqVO request) {
        captureAuditContext(request.getId());
        workbench.changeStatus(request);
        return success(true);
    }

    /**
     * 删除展示脱敏规则并解除自身分类关联。
     */
    @PostMapping("/delete")
    @LogRecord(type = "数据治理-展示脱敏规则工作台", subType = "删除展示脱敏规则并解除自身分类关联",
            bizNo = "{{(#request.id) ?: 0}}", success = "删除了展示脱敏规则【{{(#auditObjectName) ?: '编号' + #request.id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除展示脱敏规则并解除自身分类关联")
    @PreAuthorize("@ss.hasPermission('governance:rule:delete')")
    public CommonResult<Boolean> delete(@Valid @RequestBody SafetyRuleWorkbenchVersionReqVO request) {
        captureAuditContext(request.getId());
        workbench.delete(request);
        return success(true);
    }

    /**
     * 批量删除展示脱敏规则。
     */
    @PostMapping("/delete-list")
    @LogRecord(type = "数据治理-展示脱敏规则工作台", subType = "批量删除展示脱敏规则",
            bizNo = "{{(#request.items.![id]) ?: 0}}", success = "提交批量删除展示脱敏规则，对象编号【{{(#request.items.![id]) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除展示脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:delete')")
    public CommonResult<Boolean> deleteList(@Valid @RequestBody SafetyRuleWorkbenchBatchReqVO request) {
        workbench.deleteList(request);
        return success(true);
    }

    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = workbench.get(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getRuleName() == null
                        ? "编号" + id : auditBefore.getRuleName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getRuleVersionId() == null
                        ? "未设置" : String.valueOf(auditBefore.getRuleVersionId()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
