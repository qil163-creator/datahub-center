package cn.iocoder.yudao.module.supply.controller.admin.apilog;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogOverviewRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogPageReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogStatisticsRespVO;
import cn.iocoder.yudao.module.supply.service.apilog.ApiLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据服务-调用记录")
@RestController
@RequestMapping("/supply/api-log")
@Validated
public class ApiLogController {

    @Resource
    private ApiLogService apiLogService;

    /**
     * 获得调用记录分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 调用记录分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得调用记录分页")
    @PreAuthorize("@ss.hasPermission('supply:api-log:query')")
    public CommonResult<PageResult<ApiLogRespVO>> getApiLogPage(@Valid ApiLogPageReqVO pageReqVO) {
        return success(BeanUtils.toBean(apiLogService.getApiLogPage(pageReqVO), ApiLogRespVO.class));
    }

    /**
     * 获得调用记录详情。
     *
     * @param id 记录编号
     * @return API日志响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得调用记录详情")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('supply:api-log:query')")
    public CommonResult<ApiLogRespVO> getApiLog(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(apiLogService.getApiLog(id), ApiLogRespVO.class));
    }

    /**
     * 删除调用记录。
     *
     * @param id 记录编号
     * @return API日志
     */
    @LogRecord(type = "数据服务-调用记录", subType = "删除调用记录", bizNo = "{{(#id) ?: 0}}",
            success = "删除了API调用记录【编号{{#id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除调用记录")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:api-log:delete')")
    public CommonResult<Boolean> deleteApiLog(@RequestParam("id") Long id) {
        apiLogService.deleteApiLog(id);
        return success(true);
    }

    /**
     * 数据概览：今日/本月调用量、API 数、近 30 天趋势、调用排行。
     *
     * @return 概览
     */
    @GetMapping("/overview")
    @Operation(summary = "数据概览：今日/本月调用量、API 数、近 30 天趋势、调用排行")
    @PreAuthorize("@ss.hasPermission('supply:api-log:query')")
    public CommonResult<ApiLogOverviewRespVO> getOverview() {
        return success(apiLogService.getOverview());
    }

    /**
     * 监控统计：调用量/成功率/耗时/趋势/排行。
     *
     * @param days 天数
     * @return API日志统计响应数据
     */
    @GetMapping("/statistics")
    @Operation(summary = "监控统计：调用量/成功率/耗时/趋势/排行")
    @Parameter(name = "days", description = "统计天数（默认 7，最大 90）", example = "7")
    @PreAuthorize("@ss.hasPermission('supply:api-log:query')")
    public CommonResult<ApiLogStatisticsRespVO> getStatistics(
            @RequestParam(value = "days", required = false) Integer days) {
        return success(apiLogService.getStatistics(days));
    }

}
