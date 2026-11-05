package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataRespVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityIssueDataService;
import cn.iocoder.yudao.module.governance.service.quality.QualityIssueRowService;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueRowQueryReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueRowActionReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueRowPageRespVO;
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
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-问题数据 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-问题数据")
@RestController
@RequestMapping("/governance/quality-issue-data")
@Validated
public class QualityIssueDataController {

    @Resource
    private QualityIssueDataService issueDataService;

    @Resource
    private QualityIssueRowService issueRowService;

    /**
     * 查询当前授权范围内的问题行。
     */
    @PostMapping("/rows")
    @Operation(summary = "查询当前授权范围内的问题行")
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:query')")
    public CommonResult<QualityIssueRowPageRespVO> rows(@Valid @RequestBody QualityIssueRowQueryReqVO request) {
        return success(issueRowService.page(request));
    }

    /**
     * 忽略问题行或添加备注。
     */
    @PostMapping("/row-action")
    @LogRecord(type = "数据治理-数据质量-问题数据", subType = "忽略问题行或添加备注",
            bizNo = "{{(#request.auditLogId) ?: 0}}", success = "忽略问题行或添加备注",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "忽略问题行或添加备注")
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:update')")
    public CommonResult<Boolean> rowAction(@Valid @RequestBody QualityIssueRowActionReqVO request) {
        issueRowService.act(request);
        return success(true);
    }

    /**
     * 获得问题数据。
     */
    @GetMapping("/get")
    @Operation(summary = "获得问题数据")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:query')")
    public CommonResult<QualityIssueDataRespVO> getIssueData(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(issueDataService.getIssueData(id), QualityIssueDataRespVO.class));
    }

    /**
     * 获得问题数据分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得问题数据分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:query')")
    public CommonResult<PageResult<QualityIssueDataRespVO>> getIssueDataPage(
            @Valid QualityIssueDataPageReqVO pageReqVO) {
        return success(issueDataService.getIssueDataPage(pageReqVO));
    }

    /**
     * 查看问题明细：按定位 SQL 实时回源查询（源库已修复的行不会再出现）
     *
     *  @return 问题行明细（每行为「列名 → 值」）
     */
    @GetMapping("/detail")
    @Operation(summary = "查看问题明细（实时回源查询）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:query')")
    public CommonResult<List<Map<String, Object>>> getIssueDetail(
            @RequestParam("id") Long id,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return success(issueDataService.getIssueDetail(id, limit));
    }

    /**
     * 更新处理状态
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-数据质量-问题数据", subType = "更新问题数据处理状态",
            bizNo = "{{(#id) ?: 0}}", success = "更新问题数据处理状态",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新问题数据处理状态")
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:update')")
    public CommonResult<Boolean> updateIssueStatus(@RequestParam("id") Long id,
            @RequestParam("issueStatus") String issueStatus) {
        issueDataService.updateIssueStatus(id, issueStatus);
        return success(true);
    }

    /**
     * 删除问题数据。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-问题数据", subType = "删除问题数据",
            bizNo = "{{(#id) ?: 0}}", success = "删除问题数据",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除问题数据")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:delete')")
    public CommonResult<Boolean> deleteIssueData(@RequestParam("id") Long id) {
        issueDataService.deleteIssueData(id);
        return success(true);
    }

    /**
     * 导出问题数据清单 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出问题数据清单 Excel")
    @PreAuthorize("@ss.hasPermission('governance:quality-issue-data:query')")
    public void exportIssueData(@Valid QualityIssueDataPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<QualityIssueDataRespVO> list = issueDataService.getIssueDataPage(pageReqVO).getList();
        ExcelUtils.write(response, "问题数据清单.xls", "问题数据", QualityIssueDataRespVO.class, list);
    }

}
