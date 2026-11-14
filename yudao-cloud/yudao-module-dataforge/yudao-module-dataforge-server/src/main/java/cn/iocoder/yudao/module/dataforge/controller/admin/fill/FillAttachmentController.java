package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillAttachmentRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-附件与图片字段
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报附件")
@RestController
@RequestMapping("/dataforge/fill-attachment")
@Validated
public class FillAttachmentController {

    @Resource
    private FillAttachmentService attachmentService;

    @PostMapping("/upload")
    @Operation(summary = "上传附件或图片", description = "图片 ≤5MB（PNG/JPEG/GIF/WEBP），附件 ≤20MB；返回的附件编号写入单元格")
    @Parameter(name = "workItemId", description = "组织实例编号", required = true)
    @Parameter(name = "fieldCode", description = "附件或图片字段码", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    @ApiAccessLog(requestEnable = false)
    public CommonResult<FillAttachmentRespVO> upload(@RequestParam("workItemId") Long workItemId,
                                                     @RequestParam("fieldCode") String fieldCode,
                                                     @RequestParam("file") MultipartFile file) {
        return success(attachmentService.upload(workItemId, fieldCode, file));
    }

    @GetMapping("/download")
    @Operation(summary = "下载附件或预览图片", description = "填报人、发起者、组织负责人，或所属提交的审批参与者可下载")
    @Parameter(name = "id", description = "附件编号", required = true)
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:fill-work-item:my', 'dataforge:fill-submission:preview', "
            + "'dataforge:fill-task:query', 'dataforge:fill-work-item:dept-view')")
    @ApiAccessLog(responseEnable = false)
    public void download(@RequestParam("id") Long id, HttpServletResponse response) throws IOException {
        attachmentService.download(id, response);
    }

}
