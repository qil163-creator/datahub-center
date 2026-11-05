package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
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
    @BusinessOperateLog(type = "数据开发-整库同步", bizId = "#id")
    @Operation(summary = "删除未提交执行的整库同步配置")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:delete')")
    public CommonResult<Boolean> delete(@PathVariable("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision) {
        wizardService.delete(id, expectedRevision);
        return success(true);
    }

    @PostMapping
    @BusinessOperateLog(type = "数据开发-整库同步", bizId = "#result.id")
    @Operation(summary = "创建整库同步向导草稿")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:create')")
    public CommonResult<FullSyncWizardRespVO> create(@Valid @RequestBody FullSyncWizardSaveReqVO req) {
        return success(wizardService.create(req));
    }

    @PutMapping("/{id}/step")
    @BusinessOperateLog(type = "数据开发-整库同步", bizId = "#id")
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
    @BusinessOperateLog(type = "数据开发-整库同步", bizId = "#id")
    @Operation(summary = "发布并提交整库同步")
    @PreAuthorize("@ss.hasPermission('dataforge:full-sync:release')")
    public CommonResult<FullSyncWizardRespVO> submit(@PathVariable("id") Long id,
            @Valid @RequestBody(required = false) FullSyncWizardSubmitReqVO req) {
        return success(wizardService.submit(id, req));
    }
}

