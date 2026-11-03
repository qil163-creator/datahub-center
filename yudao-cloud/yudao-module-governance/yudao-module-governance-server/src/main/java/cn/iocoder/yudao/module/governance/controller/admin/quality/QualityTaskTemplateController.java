package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateSaveReqVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityTaskTemplateService;
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
 * 数据治理-数据质量-任务模板 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-任务模板")
@RestController
@RequestMapping("/governance/quality-task-template")
@Validated
public class QualityTaskTemplateController {

    @Resource
    private QualityTaskTemplateService templateService;

    /**
     * 创建任务模板。
     */
    @PostMapping("/create")
    @Operation(summary = "创建任务模板")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:create')")
    public CommonResult<Long> createTemplate(@Valid @RequestBody QualityTaskTemplateSaveReqVO createReqVO) {
        return success(templateService.createTemplate(createReqVO));
    }

    /**
     * 更新任务模板。
     */
    @PutMapping("/update")
    @Operation(summary = "更新任务模板")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:update')")
    public CommonResult<Boolean> updateTemplate(@Valid @RequestBody QualityTaskTemplateSaveReqVO updateReqVO) {
        templateService.updateTemplate(updateReqVO);
        return success(true);
    }

    /**
     * 更新任务模板状态。
     */
    @PutMapping("/update-status")
    @Operation(summary = "更新任务模板状态")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:update')")
    public CommonResult<Boolean> updateTemplateStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        templateService.updateTemplateStatus(id, status);
        return success(true);
    }

    /**
     * 删除任务模板。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除任务模板")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:delete')")
    public CommonResult<Boolean> deleteTemplate(@RequestParam("id") Long id) {
        templateService.deleteTemplate(id);
        return success(true);
    }

    /**
     * 获得任务模板。
     */
    @GetMapping("/get")
    @Operation(summary = "获得任务模板")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:query')")
    public CommonResult<QualityTaskTemplateRespVO> getTemplate(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(templateService.getTemplate(id), QualityTaskTemplateRespVO.class));
    }

    /**
     * 获得任务模板分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得任务模板分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:query')")
    public CommonResult<PageResult<QualityTaskTemplateRespVO>> getTemplatePage(
            @Valid QualityTaskTemplatePageReqVO pageReqVO) {
        return success(templateService.getTemplatePage(pageReqVO));
    }

    /**
     * 同步物化：按模板的「规则 + 表范围」批量生成实体检测任务
     *
     *  @return 本次新生成的任务数
     */
    @PostMapping("/materialize")
    @Operation(summary = "同步物化：按模板批量生成检测任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:update')")
    public CommonResult<Integer> materializeTemplate(@RequestParam("id") Long id) {
        return success(templateService.materializeTemplate(id));
    }

}
