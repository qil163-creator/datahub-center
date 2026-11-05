package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo.*;
import cn.iocoder.yudao.module.dataforge.service.fullsync.FullSyncWizardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 整库同步四步向导。
 */
@Tag(name = "管理后台 - 整库同步")
@RestController
@RequestMapping("/dataforge/full-sync/wizard")
@Validated
public class FullSyncWizardController {

    @Resource
    private FullSyncWizardService wizardService;

    @GetMapping("/page")
    @Operation(summary = "查询平台范围内的整库同步配置分页")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:query')")
    public CommonResult<PageResult<FullSyncWizardRespVO>> page(@Valid FullSyncWizardPageReqVO req) {
        return success(wizardService.page(req));
    }

    @DeleteMapping("/{id}")
    @LogRecord(type = "数据开发-整库同步", subType = "删除未提交执行的整库同步配置", bizNo = "{{(#id) ?: 0}}",
            success = "删除了尚未提交执行的整库同步草稿【{{(#operateName) ?: '未设置'}}】（向导编号【{{(#id) ?: '未设置'}}】），要求修订【{{(#expectedRevision) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除未提交执行的整库同步配置")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:delete')")
    public CommonResult<Boolean> delete(@PathVariable("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision) {
        String operateName = "编号" + id;
        try {
            var operateObject = wizardService.get(id).getConfig();
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getTaskName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        wizardService.delete(id, expectedRevision);
        return success(true);
    }

    @PostMapping
    @LogRecord(type = "数据开发-整库同步", subType = "创建整库同步向导草稿", bizNo = "{{(#_ret.data.id) ?: 0}}",
            success = "创建了整库同步草稿【{{(#_ret.data.config?.taskName) ?: '未设置'}}】，向导编号【{{(#_ret.data.id) ?: '未设置'}}】，当前步骤【{{(#_ret.data.currentStep) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建整库同步向导草稿")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:create')")
    public CommonResult<FullSyncWizardRespVO> create(@Valid @RequestBody FullSyncWizardSaveReqVO req) {
        return success(wizardService.create(req));
    }

    @PutMapping("/{id}/step")
    @LogRecord(type = "数据开发-整库同步", subType = "保存整库同步向导步骤", bizNo = "{{(#id) ?: 0}}",
            success = "保存了整库同步向导【{{(#id) ?: '未设置'}}】的第【{{(#req.step) ?: '未设置'}}】步，任务名称【{{(#_ret.data.config?.taskName) ?: '未设置'}}】，保存后修订【{{(#_ret.data.revision) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存整库同步向导步骤")
    @Parameter(name = "id", description = "向导编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:update')")
    public CommonResult<FullSyncWizardRespVO> saveStep(@PathVariable("id") Long id,
            @Valid @RequestBody FullSyncWizardSaveReqVO req) {
        return success(wizardService.saveStep(id, req));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询整库同步向导")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:query')")
    public CommonResult<FullSyncWizardRespVO> get(@PathVariable("id") Long id) {
        return success(wizardService.get(id));
    }

    @PostMapping("/{id}/validate")
    @Operation(summary = "校验整库同步向导四步配置")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:query')")
    public CommonResult<FullSyncWizardValidateRespVO> validate(@PathVariable("id") Long id) {
        return success(wizardService.validate(id));
    }

    @PostMapping("/{id}/submit")
    @LogRecord(type = "数据开发-整库同步", subType = "发布并提交整库同步", bizNo = "{{(#id) ?: 0}}",
            success = "发起了整库同步向导【{{(#id) ?: '未设置'}}】的发布和执行提交，任务名称【{{(#_ret.data.config?.taskName) ?: '未设置'}}】，返回状态【{{(#_ret.data.status) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布并提交整库同步")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:release')")
    public CommonResult<FullSyncWizardRespVO> submit(@PathVariable("id") Long id,
            @Valid @RequestBody(required = false) FullSyncWizardSubmitReqVO req) {
        return success(wizardService.submit(id, req));
    }
}

