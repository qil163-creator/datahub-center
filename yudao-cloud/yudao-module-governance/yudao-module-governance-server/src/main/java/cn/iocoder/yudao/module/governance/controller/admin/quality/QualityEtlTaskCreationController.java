package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import cn.iocoder.yudao.module.governance.service.quality.QualityEtlTaskCreationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
@RequestMapping("/governance/quality-task")
@Tag(name = "管理后台 - ETL 目标检测任务生成")
public class QualityEtlTaskCreationController {

    @Resource
    private QualityEtlTaskCreationService service;

    /**
     * 创建目标检查对象和 SQL 任务并保留幂等回执；不启动检测。
     */
    @PostMapping("/create-for-etl")
    @BusinessOperateLog(type = "数据治理-ETL 目标检测任务生成", action = "提交创建质量检测任务（含幂等回执）", bizId = "#result.taskId")
    @Operation(summary = "创建目标检查对象和 SQL 任务并保留幂等回执；不启动检测")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:create') && @ss.hasPermission('governance:quality-task:query') && @ss.hasPermission('governance:quality-check-object:create') && @ss.hasPermission('governance:quality-rule:query')")
    public CommonResult<QualityEtlTaskCreateRespVO> create(@Valid @RequestBody QualityEtlTaskCreateReqVO request) {
        return success(service.create(request));
    }

    /**
     * 查询本人创建检测任务的原始回执，用于请求结果未知时核对。
     */
    @GetMapping("/etl-create-result")
    @Operation(summary = "查询本人创建检测任务的原始回执，用于请求结果未知时核对")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:query')")
    public CommonResult<QualityEtlTaskCreateRespVO> result(@RequestParam("requestId")
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId) {
        return success(service.result(requestId));
    }
}
