package cn.iocoder.yudao.module.governance.controller.admin.master;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDataChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据审批")
@RestController
@RequestMapping("/governance/master-approval")
@Validated
public class MasterApprovalController {

    @Resource
    private MasterDataChangeService service;

    /**
     * 分页查询审批与未知变更。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询审批与未知变更")
    @PreAuthorize("@ss.hasPermission('governance:master-approval:query')")
    public CommonResult<PageResult<MasterApprovalRespVO>> page(@Valid MasterApprovalPageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 获得审批快照详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得审批快照详情")
    @PreAuthorize("@ss.hasPermission('governance:master-approval:query')")
    public CommonResult<MasterApprovalRespVO> get(@RequestParam Long id) {
        return success(service.get(id));
    }

    /**
     * 通过人工审批并应用数据变更。
     */
    @PutMapping("/approve")
    @BusinessOperateLog(type = "数据治理-主数据审批", bizId = "#req.id")
    @Operation(summary = "通过人工审批并应用数据变更")
    @PreAuthorize("@ss.hasPermission('governance:master-approval:approve')")
    public CommonResult<MasterApprovalRespVO> approve(@Valid @RequestBody MasterApprovalReviewReqVO req) {
        return success(service.approve(req));
    }

    /**
     * 驳回人工审批，正式数据保持原状态。
     */
    @PutMapping("/reject")
    @BusinessOperateLog(type = "数据治理-主数据审批", bizId = "#req.id")
    @Operation(summary = "驳回人工审批，正式数据保持原状态")
    @PreAuthorize("@ss.hasPermission('governance:master-approval:reject')")
    public CommonResult<MasterApprovalRespVO> reject(@Valid @RequestBody MasterApprovalReviewReqVO req) {
        return success(service.reject(req));
    }

    /**
     * 同步权威流程状态或标记超时执行待核查。
     */
    @PutMapping("/reconcile")
    @BusinessOperateLog(type = "数据治理-主数据审批", bizId = "#id")
    @Operation(summary = "同步权威流程状态或标记超时执行待核查")
    @PreAuthorize("@ss.hasPermission('governance:master-approval:reconcile')")
    public CommonResult<MasterApprovalRespVO> reconcile(@RequestParam Long id) {
        return success(service.reconcile(id));
    }

    /**
     * 核查未知结果并记录依据，不重新写入外部数据。
     */
    @PutMapping("/resolve")
    @BusinessOperateLog(type = "数据治理-主数据审批", bizId = "#req.id")
    @Operation(summary = "核查未知结果并记录依据，不重新写入外部数据")
    @PreAuthorize("@ss.hasPermission('governance:master-approval:resolve')")
    public CommonResult<MasterApprovalRespVO> resolve(@Valid @RequestBody MasterApprovalResolveReqVO req) {
        return success(service.resolve(req));
    }
}
