package cn.iocoder.yudao.module.dataforge.controller.admin.database;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseMonitorVO.*;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据库运行监控与告警")
@RestController
@RequestMapping("/dataforge/database-monitor")
@Validated
@RequiredArgsConstructor
public class DatabaseMonitorController {

    private final DatabaseMonitorService service;

    /**
     * 获取数据库监控概览。
     *
     * @param databaseId 数据源编号
     * @return 概览
     */
    @GetMapping("/overview")
    @Operation(summary = "获取数据库监控概览")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<Overview> overview(@RequestParam @Positive Long databaseId) {
        return success(service.overview(databaseId));
    }

    /**
     * 获取近 7、15、30 天数据库监控趋势。
     *
     * @param databaseId 数据源编号
     * @param days 天数
     * @return 趋势
     */
    @GetMapping("/trend")
    @Operation(summary = "获取近 7、15、30 天数据库监控趋势")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<Trend> trend(@RequestParam @Positive Long databaseId,
            @RequestParam(defaultValue = "7") int days) {
        return success(service.trend(databaseId, days));
    }

    /**
     * 主动采集数据库连接状态与响应时间，每分钟幂等。
     *
     * @param databaseId 数据源编号
     * @return 概览
     */
    @PostMapping("/collect")
    @Operation(summary = "主动采集数据库连接状态与响应时间，每分钟幂等")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<Overview> collect(@RequestParam @Positive Long databaseId) {
        return success(service.collect(databaseId));
    }

    /**
     * 获取数据库关联任务读写日志，不包含无冻结绑定证据的旧同步记录。
     *
     * @param req 请求参数
     * @return 数据库关联任务读写日志，不包含无冻结绑定证据的旧同步记录结果
     */
    @GetMapping("/log/page")
    @Operation(summary = "获取数据库关联任务读写日志，不包含无冻结绑定证据的旧同步记录")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<PageResult<ReadWriteLog>> logs(@Valid PageReq req) {
        return success(service.logs(req));
    }

    /**
     * 获取数据库告警规则分页。
     *
     * @param req 请求参数
     * @return 数据库告警规则分页结果
     */
    @GetMapping("/rule/page")
    @Operation(summary = "获取数据库告警规则分页")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<PageResult<Rule>> rules(@Valid PageReq req) {
        return success(service.rules(req));
    }

    /**
     * 获取数据库告警规则详情。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 规则
     */
    @GetMapping("/rule/get")
    @Operation(summary = "获取数据库告警规则详情")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<Rule> rule(@RequestParam @Positive Long databaseId, @RequestParam @Positive Long id) {
        return success(service.rule(databaseId, id));
    }

    /**
     * 创建数据库告警规则。
     *
     * @param req 请求参数
     * @return 数据源监控
     */
    @PostMapping("/rule/create")
    @Operation(summary = "创建数据库告警规则")
    @PreAuthorize("@ss.hasPermission('dataforge:database:update')")
    public CommonResult<Long> createRule(@Valid @RequestBody RuleSave req) {
        return success(service.createRule(req));
    }

    /**
     * 更新数据库告警规则。
     *
     * @param req 请求参数
     * @return 数据源监控
     */
    @PutMapping("/rule/update")
    @Operation(summary = "更新数据库告警规则")
    @PreAuthorize("@ss.hasPermission('dataforge:database:update')")
    public CommonResult<Boolean> updateRule(@Valid @RequestBody RuleSave req) {
        service.updateRule(req);
        return success(true);
    }

    /**
     * 启停数据库告警规则。
     *
     * @param req 请求参数
     * @return 数据源监控
     */
    @PutMapping("/rule/status")
    @Operation(summary = "启停数据库告警规则")
    @PreAuthorize("@ss.hasPermission('dataforge:database:update')")
    public CommonResult<Boolean> statusRule(@Valid @RequestBody RuleStatus req) {
        service.statusRule(req);
        return success(true);
    }

    /**
     * 删除数据库告警规则，保留历史告警审计记录。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 数据源监控
     */
    @DeleteMapping("/rule/delete")
    @Operation(summary = "删除数据库告警规则，保留历史告警审计记录")
    @PreAuthorize("@ss.hasPermission('dataforge:database:update')")
    public CommonResult<Boolean> deleteRule(@RequestParam @Positive Long databaseId, @RequestParam @Positive Long id) {
        service.deleteRule(databaseId, id);
        return success(true);
    }

    /**
     * 获取数据库告警分页。
     *
     * @param req 请求参数
     * @return 数据库告警分页结果
     */
    @GetMapping("/alarm/page")
    @Operation(summary = "获取数据库告警分页")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<PageResult<Alarm>> alarms(@Valid PageReq req) {
        return success(service.alarms(req));
    }

    /**
     * 获取数据库告警详情。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 告警
     */
    @GetMapping("/alarm/get")
    @Operation(summary = "获取数据库告警详情")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<Alarm> alarm(@RequestParam @Positive Long databaseId, @RequestParam @Positive Long id) {
        return success(service.alarm(databaseId, id));
    }

    /**
     * 处理或忽略数据库告警，保留操作人和原因。
     *
     * @param req 请求参数
     * @return 数据源监控
     */
    @PutMapping("/alarm/handle")
    @Operation(summary = "处理或忽略数据库告警，保留操作人和原因")
    @PreAuthorize("@ss.hasPermission('dataforge:database:update')")
    public CommonResult<Boolean> handle(@Valid @RequestBody AlarmHandle req) {
        service.handleAlarm(req);
        return success(true);
    }
}
