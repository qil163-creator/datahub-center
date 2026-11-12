package cn.iocoder.yudao.module.asset.controller.admin.dataexport;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo.DataExportRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo.DataExportSubmitReqVO;
import cn.iocoder.yudao.module.asset.service.dataexport.DataExportService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据查询结果导出（审批制）。
 * <p>
 * 数据范围：申请人只能查看、撤回、下载本人的申请；导出内容沿用数据查询的访问校验并按导出场景脱敏。
 */
@Tag(name = "管理后台 - 数据导出")
@RestController
@RequestMapping("/asset/data-export")
@Validated
public class DataExportController {

    @Resource
    private DataExportService exportService;

    /**
     * 提交导出申请。
     */
    @PostMapping("/submit")
    @Operation(summary = "提交数据导出申请")
    @PreAuthorize("@ss.hasPermission('asset:data-export:create')")
    @LogRecord(type = "数据资产-数据导出", subType = "提交导出申请", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "提交了数据源【{{#reqVO.datasourceId}}】的导出申请，行数上限【{{#reqVO.limit}}】，原因【{{#reqVO.reason}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    public CommonResult<Long> submit(@Valid @RequestBody DataExportSubmitReqVO reqVO) {
        return success(exportService.submit(reqVO));
    }

    /**
     * 本人的导出申请。
     */
    @GetMapping("/my-page")
    @Operation(summary = "获得本人的数据导出申请分页")
    @PreAuthorize("@ss.hasPermission('asset:data-export:query')")
    public CommonResult<PageResult<DataExportRespVO>> myPage(@Valid PageParam pageParam) {
        return success(exportService.myPage(pageParam));
    }

    /**
     * 撤回审批中的申请。
     */
    @PutMapping("/cancel")
    @Operation(summary = "撤回数据导出申请")
    @PreAuthorize("@ss.hasPermission('asset:data-export:create')")
    @LogRecord(type = "数据资产-数据导出", subType = "撤回导出申请", bizNo = "{{#id}}",
            success = "撤回了导出申请【{{#id}}】", extra = "BUSINESS_OPERATION")
    public CommonResult<Boolean> cancel(@RequestParam("id") @Positive Long id) {
        exportService.cancel(id);
        return success(true);
    }

    /**
     * 下载已批准的导出（一次性）。
     */
    @GetMapping("/download")
    @Operation(summary = "下载已批准的数据导出（一次性）")
    @PreAuthorize("@ss.hasPermission('asset:data-export:query')")
    @ApiAccessLog(operateType = EXPORT)
    @LogRecord(type = "数据资产-数据导出", subType = "下载导出文件", bizNo = "{{#id}}",
            success = "下载了导出申请【{{#id}}】的文件", extra = "BUSINESS_OPERATION")
    public void download(@RequestParam("id") @Positive Long id, HttpServletResponse response) throws IOException {
        DataExportService.ExportFile file = exportService.download(id);
        response.addHeader("Content-Disposition", "attachment;filename=" + file.encodedFilename());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setContentLength(file.content().length);
        response.getOutputStream().write(file.content());
    }
}
