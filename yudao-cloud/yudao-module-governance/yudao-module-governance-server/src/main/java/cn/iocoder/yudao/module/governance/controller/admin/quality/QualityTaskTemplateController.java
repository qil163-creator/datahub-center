package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
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
    @LogRecord(type = "数据治理-数据质量-任务模板", subType = "创建任务模板",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了质量任务模板【{{(#createReqVO.templateName) ?: '未设置'}}】，编码【{{(#createReqVO.templateCode) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建任务模板")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:create')")
    public CommonResult<Long> createTemplate(@Valid @RequestBody QualityTaskTemplateSaveReqVO createReqVO) {
        return success(templateService.createTemplate(createReqVO));
    }

    /**
     * 更新任务模板。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-任务模板", subType = "更新任务模板",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了质量任务模板，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.templateName) ?: '未设置'}}】，提交编码【{{(#updateReqVO.templateCode) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新任务模板")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:update')")
    public CommonResult<Boolean> updateTemplate(@Valid @RequestBody QualityTaskTemplateSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        templateService.updateTemplate(updateReqVO);
        return success(true);
    }

    /**
     * 更新任务模板状态。
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-数据质量-任务模板", subType = "更新任务模板状态",
            bizNo = "{{(#id) ?: 0}}", success = "调整了质量任务模板【{{(#auditObjectName) ?: '编号' + #id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新任务模板状态")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:update')")
    public CommonResult<Boolean> updateTemplateStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        captureAuditContext(id);
        templateService.updateTemplateStatus(id, status);
        return success(true);
    }

    /**
     * 删除任务模板。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-任务模板", subType = "删除任务模板",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量任务模板【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除任务模板")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:delete')")
    public CommonResult<Boolean> deleteTemplate(@RequestParam("id") Long id) {
        captureAuditContext(id);
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
    @LogRecord(type = "数据治理-数据质量-任务模板", subType = "同步物化：按模板批量生成检测任务",
            bizNo = "{{(#id) ?: 0}}", success = "按质量任务模板【{{(#auditObjectName) ?: '编号' + #id}}】生成了【{{(#_ret.data) ?: '未设置'}}】个检测任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "同步物化：按模板批量生成检测任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task-template:update')")
    public CommonResult<Integer> materializeTemplate(@RequestParam("id") Long id) {
        captureAuditContext(id);
        return success(templateService.materializeTemplate(id));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = templateService.getTemplate(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getTemplateName() == null
                        ? "编号" + id : auditBefore.getTemplateName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
