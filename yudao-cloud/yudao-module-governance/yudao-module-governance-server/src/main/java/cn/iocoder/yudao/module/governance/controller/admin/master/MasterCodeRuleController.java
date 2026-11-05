package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterCodeRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据编码规则")
@RestController
@RequestMapping("/governance/master-code-rule")
@Validated
public class MasterCodeRuleController {

    @Resource
    private MasterCodeRuleService service;

    /**
     * 创建编码规则。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-主数据编码规则", subType = "创建编码规则",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了主数据编码规则【{{(#req.name) ?: '未设置'}}】，编码【{{(#req.code) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:create')")
    public CommonResult<Long> create(@Valid @RequestBody MasterCodeRuleSaveReqVO req) {
        return success(service.create(req));
    }

    /**
     * 修改编码规则。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-主数据编码规则", subType = "修改编码规则",
            bizNo = "{{(#req.id) ?: 0}}", success = "修改了主数据编码规则，原名称【{{(#auditObjectName) ?: '编号' + #req.id}}】，提交名称【{{(#req.name) ?: '未设置'}}】，提交编码【{{(#req.code) ?: '未设置'}}】，目标状态【{{(#req.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MasterCodeRuleSaveReqVO req) {
        captureAuditContext(req.getId());
        service.update(req);
        return success(true);
    }

    /**
     * 启停编码规则。
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-主数据编码规则", subType = "启停编码规则",
            bizNo = "{{(#req.id) ?: 0}}", success = "调整了主数据编码规则【{{(#auditObjectName) ?: '编号' + #req.id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#req.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "启停编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody MasterCodeRuleStatusReqVO req) {
        captureAuditContext(req.getId());
        service.updateStatus(req);
        return success(true);
    }

    /**
     * 删除编码规则。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-主数据编码规则", subType = "删除编码规则",
            bizNo = "{{(#id) ?: 0}}", success = "删除了主数据编码规则【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:delete')")
    public CommonResult<Boolean> delete(@RequestParam Long id) {
        captureAuditContext(id);
        service.deleteList(List.of(id));
        return success(true);
    }

    /**
     * 批量删除编码规则。
     */
    @DeleteMapping("/delete-list")
    @LogRecord(type = "数据治理-主数据编码规则", subType = "批量删除编码规则",
            bizNo = "{{(#ids) ?: 0}}", success = "提交批量删除主数据编码规则，对象编号【{{(#ids) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam List<Long> ids) {
        service.deleteList(ids);
        return success(true);
    }

    /**
     * 查询编码规则。
     */
    @GetMapping("/get")
    @Operation(summary = "查询编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:query')")
    public CommonResult<MasterCodeRuleRespVO> get(@RequestParam Long id) {
        return success(service.get(id));
    }

    /**
     * 分页查询编码规则。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:query')")
    public CommonResult<PageResult<MasterCodeRuleRespVO>> page(@Valid MasterCodeRulePageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 预览编码，不发放流水号。
     */
    @PostMapping("/preview")
    @Operation(summary = "预览编码，不发放流水号")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:query') or @ss.hasPermission('governance:master-code-rule:create') or @ss.hasPermission('governance:master-code-rule:update')")
    public CommonResult<String> preview(@Valid @RequestBody MasterCodeRuleSaveReqVO req) {
        return success(service.preview(req));
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
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
