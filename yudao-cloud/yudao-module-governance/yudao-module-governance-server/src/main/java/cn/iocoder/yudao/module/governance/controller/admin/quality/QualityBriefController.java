package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityReportLogDO;
import cn.iocoder.yudao.module.governance.service.quality.QualityBriefService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-质检简报 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-质检简报")
@RestController
@RequestMapping("/governance/quality-brief")
@Validated
public class QualityBriefController {

    @Resource
    private QualityBriefService briefService;

    /**
     * 生成简报：按模板段落渲染变量（数据来自机构评分），生成草稿简报并写报告记录
     *
     *  @return 简报编号
     */
    @PostMapping("/generate")
    @LogRecord(type = "数据治理-数据质量-质检简报", subType = "生成简报（按模板渲染变量与附表）",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "生成简报（按模板渲染变量与附表）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "生成简报（按模板渲染变量与附表）")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:create')")
    public CommonResult<Long> generateBrief(@Valid @RequestBody QualityBriefGenerateReqVO reqVO) {
        return success(briefService.generateBrief(reqVO));
    }

    /**
     * 发布简报
     */
    @PutMapping("/publish")
    @LogRecord(type = "数据治理-数据质量-质检简报", subType = "发布简报",
            bizNo = "{{(#id) ?: 0}}", success = "发布简报",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布简报")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:update')")
    public CommonResult<Boolean> publishBrief(@RequestParam("id") Long id) {
        briefService.publishBrief(id);
        return success(true);
    }

    /**
     * 删除简报。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-质检简报", subType = "删除简报",
            bizNo = "{{(#id) ?: 0}}", success = "删除简报",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除简报")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:delete')")
    public CommonResult<Boolean> deleteBrief(@RequestParam("id") Long id) {
        briefService.deleteBrief(id);
        return success(true);
    }

    /**
     * 获得简报详情（含渲染后内容，供预览）。
     */
    @GetMapping("/get")
    @Operation(summary = "获得简报详情（含渲染后内容，供预览）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public CommonResult<QualityBriefRespVO> getBrief(@RequestParam("id") Long id) {
        return success(briefService.getBrief(id));
    }

    /**
     * 获得简报分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得简报分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public CommonResult<PageResult<QualityBriefRespVO>> getBriefPage(@Valid QualityBriefPageReqVO pageReqVO) {
        return success(briefService.getBriefPage(pageReqVO));
    }

    /**
     * 获得报告生成记录分页。
     */
    @GetMapping("/report-log/page")
    @Operation(summary = "获得报告生成记录分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public CommonResult<PageResult<QualityReportLogRespVO>> getReportLogPage(
            @Valid QualityReportLogPageReqVO pageReqVO) {
        PageResult<QualityReportLogDO> pageResult = briefService.getReportLogPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, QualityReportLogRespVO.class));
    }

    /**
     * 导出简报：按渲染后的简报内容生成 Word（.doc）或 Excel（.xls）文件流
     *
     *  @param format WORD 或 EXCEL 格式
     */
    @GetMapping("/export")
    @Operation(summary = "导出简报（format=WORD 导出 .doc / format=EXCEL 导出 .xls）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public void exportBrief(@RequestParam("id") Long id,
            @RequestParam(value = "format", defaultValue = "WORD") String format,
            HttpServletResponse response) throws IOException {
        briefService.exportBrief(id, format, response);
    }

}
