package cn.iocoder.yudao.module.dataforge.controller.admin.dev;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillRespVO;
import cn.iocoder.yudao.module.dataforge.service.dev.DevBackfillService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据开发补数据（历史业务日期区间重跑）。
 */
@Tag(name = "管理后台 - 数据开发补数据")
@RestController
@RequestMapping("/dataforge/dev-backfill")
@Validated
public class DevBackfillController {

    @Resource
    private DevBackfillService backfillService;

    /**
     * 发起补数据批次。
     *
     * @param reqVO 请求参数
     * @return 批次编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-补数据", subType = "发起补数据", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "为开发任务【{{#reqVO.taskId}}】发起补数据，业务日期【{{#reqVO.startDate}}】至【{{#reqVO.endDate}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "发起补数据批次")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Long> createBackfill(@Valid @RequestBody DevBackfillCreateReqVO reqVO) {
        return success(backfillService.createBackfill(reqVO));
    }

    /**
     * 取消补数据批次。
     *
     * @param id 批次编号
     * @return 是否成功
     */
    @PutMapping("/cancel")
    @LogRecord(type = "数据开发-补数据", subType = "取消补数据", bizNo = "{{#id}}",
            success = "取消了补数据批次【{{#id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "取消补数据批次")
    @Parameter(name = "id", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Boolean> cancelBackfill(@RequestParam("id") Long id) {
        backfillService.cancelBackfill(id);
        return success(true);
    }

    /**
     * 获得补数据批次详情（含日期明细）。
     *
     * @param id 批次编号
     * @return 批次详情
     */
    @GetMapping("/get")
    @Operation(summary = "获得补数据批次详情")
    @Parameter(name = "id", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<DevBackfillRespVO> getBackfill(@RequestParam("id") Long id) {
        return success(backfillService.getBackfill(id));
    }

    /**
     * 获得补数据批次分页。
     *
     * @param reqVO 分页参数
     * @return 分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得补数据批次分页")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<PageResult<DevBackfillRespVO>> getBackfillPage(@Valid DevBackfillPageReqVO reqVO) {
        return success(backfillService.getBackfillPage(reqVO));
    }

}
