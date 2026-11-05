package cn.iocoder.yudao.module.governance.controller.admin.whitelist;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistApprovalDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistService;
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

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 脱敏白名单")
@RestController
@RequestMapping("/governance/safety-whitelist")
@Validated
public class SafetyWhitelistController {

    @Resource
    private SafetyWhitelistService whitelistService;

    /**
     * 创建脱敏白名单
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-脱敏白名单", subType = "创建脱敏白名单",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建脱敏白名单",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建脱敏白名单")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:create')")
    public CommonResult<Long> createWhitelist(@Valid @RequestBody SafetyWhitelistSaveReqVO createReqVO) {
        return success(whitelistService.createWhitelist(createReqVO));
    }

    /**
     * 更新脱敏白名单
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-脱敏白名单", subType = "更新脱敏白名单",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新脱敏白名单",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新脱敏白名单")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:update')")
    public CommonResult<Boolean> updateWhitelist(@Valid @RequestBody SafetyWhitelistSaveReqVO updateReqVO) {
        whitelistService.updateWhitelist(updateReqVO);
        return success(true);
    }

    /**
     * 删除脱敏白名单
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-脱敏白名单", subType = "删除脱敏白名单",
            bizNo = "{{(#id) ?: 0}}", success = "删除脱敏白名单",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除脱敏白名单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:whitelist:delete')")
    public CommonResult<Boolean> deleteWhitelist(@RequestParam("id") Long id) {
        whitelistService.deleteWhitelist(id);
        return success(true);
    }

    /**
     * 批量删除脱敏白名单。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据治理-脱敏白名单", subType = "批量删除脱敏白名单",
            bizNo = "{{(#ids) ?: 0}}", success = "批量删除脱敏白名单",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除脱敏白名单")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:delete')")
    public CommonResult<Boolean> deleteWhitelistList(@RequestParam("ids") List<Long> ids) {
        whitelistService.deleteWhitelistListByIds(ids);
        return success(true);
    }

    /**
     * 获得脱敏白名单
     *
     *  @param id 编号
     *  @return 脱敏白名单
     */
    @GetMapping("/get")
    @Operation(summary = "获得脱敏白名单")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<SafetyWhitelistRespVO> getWhitelist(@RequestParam("id") Long id) {
        SafetyWhitelistDO whitelist = whitelistService.getWhitelist(id);
        return success(BeanUtils.toBean(whitelist, SafetyWhitelistRespVO.class));
    }

    /**
     * 获得脱敏白名单分页
     *
     *  @param pageReqVO 分页查询
     *  @return 脱敏白名单分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得脱敏白名单分页")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<PageResult<SafetyWhitelistRespVO>> getWhitelistPage(@Valid SafetyWhitelistPageReqVO pageReqVO) {
        PageResult<SafetyWhitelistDO> pageResult = whitelistService.getWhitelistPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, SafetyWhitelistRespVO.class));
    }

    /**
     * 导出脱敏白名单 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出脱敏白名单 Excel")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportWhitelistExcel(@Valid SafetyWhitelistPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<SafetyWhitelistDO> list = whitelistService.getWhitelistPage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "脱敏白名单.xls", "数据", SafetyWhitelistRespVO.class,
                BeanUtils.toBean(list, SafetyWhitelistRespVO.class));
    }

    // ==================== 审批流 ====================

    /**
     * 提交审批：DRAFT / REJECTED → PENDING
     */
    @PutMapping("/submit")
    @LogRecord(type = "数据治理-脱敏白名单", subType = "提交审批（草稿/已驳回 → 审批中）",
            bizNo = "{{(#id) ?: 0}}", success = "提交审批（草稿/已驳回 → 审批中）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "提交审批（草稿/已驳回 → 审批中）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:whitelist:update')")
    public CommonResult<Boolean> submitWhitelist(@RequestParam("id") Long id) {
        whitelistService.submitWhitelist(id);
        return success(true);
    }

    /**
     * 审批通过：PENDING → APPROVED，并自动启用
     */
    @PutMapping("/approve")
    @LogRecord(type = "数据治理-脱敏白名单", subType = "审批通过（审批中 → 已通过，并自动启用）",
            bizNo = "{{(#id) ?: 0}}", success = "审批通过（审批中 → 已通过，并自动启用）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "审批通过（审批中 → 已通过，并自动启用）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:whitelist:approve')")
    public CommonResult<Boolean> approveWhitelist(@RequestParam("id") Long id,
            @RequestParam(value = "opinion", required = false) String opinion) {
        whitelistService.approveWhitelist(id, opinion);
        return success(true);
    }

    /**
     * 审批驳回：PENDING → REJECTED，并停用
     */
    @PutMapping("/reject")
    @LogRecord(type = "数据治理-脱敏白名单", subType = "审批驳回（审批中 → 已驳回，并停用）",
            bizNo = "{{(#id) ?: 0}}", success = "审批驳回（审批中 → 已驳回，并停用）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "审批驳回（审批中 → 已驳回，并停用）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:whitelist:approve')")
    public CommonResult<Boolean> rejectWhitelist(@RequestParam("id") Long id,
            @RequestParam(value = "opinion", required = false) String opinion) {
        whitelistService.rejectWhitelist(id, opinion);
        return success(true);
    }

    /**
     * 获得白名单审批记录列表
     */
    @GetMapping("/approval-list")
    @Operation(summary = "获得白名单审批记录列表")
    @Parameter(name = "whitelistId", description = "白名单编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<List<SafetyWhitelistApprovalDO>> getWhitelistApprovalList(
            @RequestParam("whitelistId") Long whitelistId) {
        return success(whitelistService.getWhitelistApprovalList(whitelistId));
    }

    // ==================== 子表（数据安全--脱敏白名单生效主体） ====================

    /**
     * 获得脱敏白名单生效主体列表
     *
     *  @param whitelistId 所属白名单编号
     *  @return 脱敏白名单生效主体列表
     */
    @GetMapping("/whitelist-subject/list-by-whitelist-id")
    @Operation(summary = "获得脱敏白名单生效主体列表")
    @Parameter(name = "whitelistId", description = "所属白名单编号")
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<List<SafetyWhitelistSubjectDO>> getWhitelistSubjectListByWhitelistId(
            @RequestParam("whitelistId") Long whitelistId) {
        return success(whitelistService.getWhitelistSubjectListByWhitelistId(whitelistId));
    }

}
