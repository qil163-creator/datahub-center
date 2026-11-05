package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmissionPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-提交快照 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报提交快照")
@RestController
@RequestMapping("/dataforge/fill-submission")
@Validated
public class FillSubmissionController {

    @Resource
    private FillSubmissionService submissionService;

    /**
     * 提交快照只读预览。
     *
     * @param id 记录编号
     * @return 填报提交预览响应数据
     */
    @GetMapping("/preview")
    @Operation(summary = "提交快照只读预览",
            description = "审批人从 BPM 业务表单（formCustomViewPath）打开；含模板 schema 与全部不可变快照行")
    @Parameter(name = "id", description = "提交快照编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-submission:preview')")
    public CommonResult<FillSubmissionPreviewRespVO> getSubmissionPreview(@RequestParam("id") Long id) {
        return success(submissionService.getSubmissionPreview(id));
    }

    /**
     * 撤回审批中的提交（仅实际提交人）。
     *
     * @param id 记录编号
     * @param reason 操作原因
     * @return 填报提交
     */
    @PutMapping("/withdraw")
    @BusinessOperateLog(type = "数据开发-在线填报提交快照", bizId = "#id")
    @Operation(summary = "撤回审批中的提交（仅实际提交人）",
            description = "先经 BPM 成功终止流程实例；保留原快照，实例转入整改中")
    @Parameter(name = "id", description = "提交快照编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:withdraw')")
    public CommonResult<Boolean> withdraw(@RequestParam("id") Long id,
            @RequestParam(value = "reason", required = false) String reason) {
        submissionService.withdraw(id, reason);
        return success(true);
    }

}
