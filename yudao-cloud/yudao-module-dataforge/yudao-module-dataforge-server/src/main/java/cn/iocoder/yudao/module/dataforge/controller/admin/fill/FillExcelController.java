package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillExcelImportConfirmReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillExcelImportPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillExcelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-Excel 导入导出 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报 Excel")
@RestController
@RequestMapping("/dataforge/fill-work-item")
@Validated
public class FillExcelController {

    @Resource
    private FillExcelService excelService;

    /**
     * 下载 Excel 填报模板。
     *
     * @param id 记录编号
     * @param response 响应对象
     */
    @GetMapping("/{id}/excel/template")
    @Operation(summary = "下载 Excel 填报模板", description = "按工作项绑定的模板版本生成，含必填标记与字典下拉，仅输入列")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public void downloadTemplate(@PathVariable("id") Long id, HttpServletResponse response) {
        excelService.downloadTemplate(id, response);
    }

    /**
     * Excel 导入预检。
     *
     * @param id 记录编号
     * @param file 文件
     * @return 填报Excel导入预览响应数据
     */
    @PostMapping("/{id}/excel/import/preview")
    @Operation(summary = "Excel 导入预检", description = "解析并逐行逐字段校验，不写草稿；返回数据行供确认时回传")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<FillExcelImportPreviewRespVO> previewImport(
            @PathVariable("id") Long id, @RequestParam("file") MultipartFile file) {
        return success(excelService.previewImport(id, file));
    }

    /**
     * Excel 导入确认。
     *
     * @param id 记录编号
     * @param reqVO 请求参数
     * @return 填报操作应用响应数据
     */
    @PostMapping("/{id}/excel/import/confirm")
    @LogRecord(type = "数据开发-在线填报 Excel", subType = "Excel 导入确认", bizNo = "{{(#id) ?: 0}}",
            success = "Excel 导入确认", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "Excel 导入确认", description = "预检数据行经草稿操作通道原子写入（追加或整体替换），并广播协同更新")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<FillOperationApplyRespVO> confirmImport(
            @PathVariable("id") Long id, @Valid @RequestBody FillExcelImportConfirmReqVO reqVO) {
        return success(excelService.confirmImport(id, reqVO));
    }

    /**
     * 导出当前草稿数据。
     *
     * @param id 记录编号
     * @param response 响应对象
     */
    @GetMapping("/{id}/excel/export")
    @Operation(summary = "导出当前草稿数据")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public void exportDraft(@PathVariable("id") Long id, HttpServletResponse response) {
        excelService.exportDraft(id, response);
    }

}
