package cn.iocoder.yudao.module.governance.controller.admin.whitelist;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.*;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistWorkbenchService;
import cn.iocoder.yudao.module.system.api.user.dto.*;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 分类脱敏白名单工作台")
@RestController
@Validated
@RequestMapping("/governance/safety-whitelist/workbench")
public class SafetyWhitelistWorkbenchController {

    @Resource
    private SafetyWhitelistWorkbenchService service;

    /**
     * 分类白名单分页。
     */
    @GetMapping("/page")
    @Operation(summary = "分类白名单分页")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<PageResult<SafetyWhitelistWorkbenchRespVO>> page(@Valid SafetyWhitelistWorkbenchPageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 分类白名单详情。
     */
    @GetMapping("/get")
    @Operation(summary = "分类白名单详情")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<SafetyWhitelistWorkbenchRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 分类白名单目录及分类候选。
     */
    @GetMapping("/options")
    @Operation(summary = "分类白名单目录及分类候选")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<SafetyWhitelistWorkbenchOptionsRespVO> options() {
        return success(service.options());
    }

    /**
     * 真实本地启用账号分页。
     */
    @GetMapping("/user-options")
    @Operation(summary = "真实本地启用账号分页")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<PageResult<AdminUserOptionRespDTO>> userOptions(@Valid AdminUserOptionPageReqDTO req) {
        return success(service.userOptions(req));
    }

    /**
     * 配置分类范围白名单并记录本地授权依据。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-分类脱敏白名单工作台", subType = "配置分类范围白名单并记录本地授权依据",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了分类范围白名单【{{(#req.name) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "配置分类范围白名单并记录本地授权依据")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:create')")
    public CommonResult<Long> create(@Valid @RequestBody SafetyWhitelistWorkbenchSaveReqVO req) {
        return success(service.create(req));
    }

    /**
     * 修改分类范围白名单。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-分类脱敏白名单工作台", subType = "修改分类范围白名单",
            bizNo = "{{(#req.id) ?: 0}}", success = "修改了分类范围白名单，原名称【{{(#auditObjectName) ?: '编号' + #req.id}}】，提交名称【{{(#req.name) ?: '未设置'}}】，目标状态【{{(#req.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改分类范围白名单")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody SafetyWhitelistWorkbenchSaveReqVO req) {
        captureAuditContext(req.getId());
        service.update(req);
        return success(true);
    }

    /**
     * 修改分类范围白名单状态。
     */
    @PutMapping("/change-status")
    @LogRecord(type = "数据治理-分类脱敏白名单工作台", subType = "修改分类范围白名单状态",
            bizNo = "{{(#req.id) ?: 0}}", success = "调整了分类范围白名单【{{(#auditObjectName) ?: '编号' + #req.id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#req.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改分类范围白名单状态")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody SafetyWhitelistWorkbenchStatusReqVO req) {
        captureAuditContext(req.getId());
        service.changeStatus(req);
        return success(true);
    }

    /**
     * 删除停用分类白名单。
     */
    @PostMapping("/delete")
    @LogRecord(type = "数据治理-分类脱敏白名单工作台", subType = "删除停用分类白名单",
            bizNo = "{{(#req.id) ?: 0}}", success = "删除了分类范围白名单【{{(#auditObjectName) ?: '编号' + #req.id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除停用分类白名单")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:delete')")
    public CommonResult<Boolean> delete(@Valid @RequestBody SafetyWhitelistWorkbenchVersionReqVO req) {
        captureAuditContext(req.getId());
        service.delete(req);
        return success(true);
    }

    /**
     * 原子批量删除停用分类白名单。
     */
    @PostMapping("/delete-list")
    @LogRecord(type = "数据治理-分类脱敏白名单工作台", subType = "原子批量删除停用分类白名单",
            bizNo = "{{(#req.items.![id]) ?: 0}}", success = "提交批量删除分类范围白名单，对象编号【{{(#req.items.![id]) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "原子批量删除停用分类白名单")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:delete')")
    public CommonResult<Boolean> deleteList(@Valid @RequestBody SafetyWhitelistWorkbenchBatchReqVO req) {
        service.deleteList(req);
        return success(true);
    }

    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = service.get(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getName() == null
                        ? "编号" + id : auditBefore.getName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getRevision() == null
                        ? "未设置" : String.valueOf(auditBefore.getRevision()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
