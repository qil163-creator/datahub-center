package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlTargetBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - ETL 目标批次")
@RestController
@RequestMapping("/dataforge/etl/target-batch")
@Validated
public class EtlTargetBatchController {

    @Resource
    private EtlTargetBatchService service;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlReplayService replays;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlArchiveService archives;

    /**
     * 查询运行归档与证据保留期限。
     *
     * @param recordId 记录编号
     * @return ETL归档响应数据
     */
    @GetMapping("/archive")
    @Operation(summary = "查询运行归档与证据保留期限")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlArchiveRespVO> archive(@RequestParam @Positive Long recordId) {
        return success(archives.get(recordId));
    }

    /**
     * 归档已完成运行。
     *
     * @param request 请求参数
     * @return ETL 目标批次
     */
    @PostMapping("/archive")
    @Operation(summary = "归档已完成运行", description = "未关闭工单和未确认提交阻断归档；保留全部审计，不物理删除目标与证据")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> archive(@Valid @RequestBody EtlArchiveReqVO request) {
        archives.archive(request);
        return success(true);
    }

    /**
     * 在证据保留期内恢复归档运行。
     *
     * @param request 请求参数
     * @return ETL 目标批次
     */
    @PostMapping("/restore-archive")
    @Operation(summary = "在证据保留期内恢复归档运行")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> restoreArchive(@Valid @RequestBody EtlReplayRetryReqVO request) {
        archives.restore(request);
        return success(true);
    }

    /**
     * 按治理源端整改提交发起完整批次回放。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/replay")
    @Operation(summary = "按治理源端整改提交发起完整批次回放", description = "冻结所选版本、复核全部原目标，保留原检测证据；不接受任意目标或自定义 SQL")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> replay(
            @Valid @RequestBody EtlReplayReqVO request) {
        return success(replays.create(request));
    }

    /**
     * 查询回放与治理复检证据关联状态。
     *
     * @param recordId 记录编号
     * @return ETL 重放状态响应数据
     */
    @GetMapping("/replay-status")
    @Operation(summary = "查询回放与治理复检证据关联状态")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlReplayStatusRespVO> replayStatus(@RequestParam @Positive Long recordId) {
        return success(replays.status(recordId));
    }

    /**
     * 恢复回放复检证据投递。
     *
     * @param request 请求参数
     * @return ETL 目标批次
     */
    @PostMapping("/retry-recheck")
    @Operation(summary = "恢复回放复检证据投递", description = "只重报现有回放结果，不重复执行回放")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<Boolean> retryRecheck(@Valid @RequestBody EtlReplayRetryReqVO request) {
        replays.retry(request);
        return success(true);
    }

    /**
     * 查询运行的提交与质量状态。
     *
     * @param recordId 记录编号
     * @return 运行的提交与质量状态结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "查询运行的提交与质量状态", description = "平台内 ETL 查询范围，不返回持久化原始载荷")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<EtlTargetBatchRespVO>> list(@RequestParam @Positive Long recordId) {
        return success(service.list(recordId));
    }

    /**
     * 读取通过准入的目标批次。
     *
     * @param batchId 批次编号
     * @param afterRow 变更后行
     * @param limit 读取或处理上限
     * @return ETL目标行响应数据
     */
    @GetMapping("/rows")
    @Operation(summary = "读取通过准入的目标批次", description = "全部目标提交且质量通过后开放；同时应用当前用户的行权限和脱敏")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlTargetRowsRespVO> rows(@RequestParam @Positive Long batchId,
            @RequestParam(defaultValue = "0") @Min(0) long afterRow,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return success(service.rows(batchId, afterRow, limit));
    }

    /**
     * 人工恢复目标回执或质量登记对账。
     *
     * @param request 请求参数
     * @return ETL 目标批次
     */
    @PostMapping("/retry")
    @Operation(summary = "人工恢复目标回执或质量登记对账", description = "复用原批次与原实例，不把检测失败改为通过；操作原因持久审计")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<Boolean> retry(@Valid @RequestBody EtlTargetRetryReqVO request) {
        service.retry(request);
        return success(true);
    }
}
