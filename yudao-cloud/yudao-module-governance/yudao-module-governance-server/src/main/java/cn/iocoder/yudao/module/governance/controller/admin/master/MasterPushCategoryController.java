package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterPushCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据推送类目")
@RestController
@RequestMapping("/governance/master-push-category")
@Validated
@RequiredArgsConstructor
public class MasterPushCategoryController {
    private final MasterPushCategoryService service;

    @GetMapping("/tree") @Operation(summary = "读取推送类目目录")
    @PreAuthorize("@ss.hasAnyPermissions('governance:master-distribute:query', 'governance:master-distribute:create', 'governance:master-distribute:update', 'governance:master-push-category:query')")
    public CommonResult<List<MasterPushCategoryRespVO>> tree() { return success(service.tree()); }

    @GetMapping("/list") @Operation(summary = "读取推送类目列表")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:query')")
    public CommonResult<List<MasterPushCategoryRespVO>> list() { return success(service.list()); }

    @GetMapping("/get") @Operation(summary = "读取推送类目详情")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:query')")
    public CommonResult<MasterPushCategoryRespVO> get(@RequestParam @Positive Long id) { return success(service.get(id)); }

    @PostMapping("/create")
    @LogRecord(type = "数据治理-主数据推送类目", subType = "创建推送类目",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了主数据推送类目【{{(#request.name) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:create')")
    public CommonResult<Long> create(@Valid @RequestBody MasterPushCategorySaveReqVO request) { return success(service.create(request)); }

    @PutMapping("/update")
    @LogRecord(type = "数据治理-主数据推送类目", subType = "修改推送类目",
            bizNo = "{{(#request.id) ?: 0}}", success = "修改了主数据推送类目，原名称【{{(#auditObjectName) ?: '编号' + #request.id}}】，提交名称【{{(#request.name) ?: '未设置'}}】，目标状态【{{(#request.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MasterPushCategorySaveReqVO request) {
        captureAuditContext(request.getId()); service.update(request); return success(true); }

    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-主数据推送类目", subType = "更新推送类目状态",
            bizNo = "{{(#request.id) ?: 0}}", success = "调整了主数据推送类目【{{(#auditObjectName) ?: '编号' + #request.id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#request.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新推送类目状态")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody MasterPushCategoryStatusReqVO request) {
        captureAuditContext(request.getId()); service.updateStatus(request); return success(true); }

    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-主数据推送类目", subType = "删除推送类目",
            bizNo = "{{(#id) ?: 0}}", success = "删除了主数据推送类目【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id) {
        captureAuditContext(id); service.delete(List.of(id)); return success(true); }

    @DeleteMapping("/delete-list")
    @LogRecord(type = "数据治理-主数据推送类目", subType = "批量删除推送类目",
            bizNo = "{{(#ids) ?: 0}}", success = "提交批量删除主数据推送类目，对象编号【{{(#ids) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam @NotEmpty @Size(max = 200) List<@Positive Long> ids) { service.delete(ids); return success(true); }

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
