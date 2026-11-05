package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDomainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据域")
@RestController
@RequestMapping("/governance/master-domain")
@Validated
public class MasterDomainController {

    @Resource
    private MasterDomainService domainService;

    /**
     * 创建主数据域。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-主数据域", subType = "创建主数据域",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了主数据域【{{(#req.name) ?: '未设置'}}】，编码【{{(#req.code) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:create')")
    public CommonResult<Long> create(@Valid @RequestBody MasterDomainSaveReqVO req) {
        return success(domainService.createDomain(req));
    }

    /**
     * 修改主数据域。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-主数据域", subType = "修改主数据域",
            bizNo = "{{(#req.id) ?: 0}}", success = "修改了主数据域，原名称【{{(#auditObjectName) ?: '编号' + #req.id}}】，提交名称【{{(#req.name) ?: '未设置'}}】，提交编码【{{(#req.code) ?: '未设置'}}】，目标状态【{{(#req.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MasterDomainSaveReqVO req) {
        captureAuditContext(req.getId());
        domainService.updateDomain(req);
        return success(true);
    }

    /**
     * 更新主数据域状态。
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-主数据域", subType = "更新主数据域状态",
            bizNo = "{{(#req.id) ?: 0}}", success = "调整了主数据域【{{(#auditObjectName) ?: '编号' + #req.id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#req.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新主数据域状态")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody MasterDomainUpdateStatusReqVO req) {
        captureAuditContext(req.getId());
        domainService.updateStatus(req.getId(), req.getStatus());
        return success(true);
    }

    /**
     * 删除主数据域。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-主数据域", subType = "删除主数据域",
            bizNo = "{{(#id) ?: 0}}", success = "删除了主数据域【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        captureAuditContext(id);
        domainService.deleteDomain(id);
        return success(true);
    }

    /**
     * 批量删除主数据域。
     */
    @DeleteMapping("/delete-list")
    @LogRecord(type = "数据治理-主数据域", subType = "批量删除主数据域",
            bizNo = "{{(#ids) ?: 0}}", success = "提交批量删除主数据域，对象编号【{{(#ids) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam("ids") @NotEmpty @Size(max = 100) List<Long> ids) {
        domainService.deleteDomainList(ids);
        return success(true);
    }

    /**
     * 查询主数据域。
     */
    @GetMapping("/get")
    @Operation(summary = "查询主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:query')")
    public CommonResult<MasterDomainRespVO> get(@RequestParam("id") Long id) {
        return success(domainService.getDomain(id));
    }

    /**
     * 分页查询主数据域。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:query')")
    public CommonResult<PageResult<MasterDomainRespVO>> page(@Valid MasterDomainPageReqVO req) {
        return success(domainService.getDomainPage(req));
    }

    /**
     * 查询主数据域列表。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "查询主数据域列表")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:query') or "
            + "@ss.hasPermission('governance:master-model:query') or @ss.hasPermission('governance:master-data:query')")
    public CommonResult<List<MasterDomainRespVO>> simpleList() {
        return success(domainService.getSimpleList());
    }

    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = domainService.getDomain(id);
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
