package cn.iocoder.yudao.module.governance.controller.admin.statictask;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import cn.iocoder.yudao.module.governance.service.statictask.SafetyStaticTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 静态脱敏任务")
@RestController
@RequestMapping("/governance/safety-static-task")
@Validated
public class SafetyStaticTaskController {

    @Resource
    private SafetyStaticTaskService taskService;

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 创建静态脱敏任务
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @Operation(summary = "创建静态脱敏任务")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:create')")
    public CommonResult<Long> createTask(@Valid @RequestBody SafetyStaticTaskSaveReqVO createReqVO) {
        return success(taskService.createTask(createReqVO));
    }

    /**
     * 更新静态脱敏任务
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @Operation(summary = "更新静态脱敏任务")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:update')")
    public CommonResult<Boolean> updateTask(@Valid @RequestBody SafetyStaticTaskSaveReqVO updateReqVO) {
        taskService.updateTask(updateReqVO);
        return success(true);
    }

    /**
     * 删除静态脱敏任务
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除静态脱敏任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        taskService.deleteTask(id);
        return success(true);
    }

    /**
     * 批量删除静态脱敏任务。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除静态脱敏任务")
    @Parameter(name = "ids", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:delete')")
    public CommonResult<Boolean> deleteTaskList(@RequestParam("ids") List<Long> ids) {
        taskService.deleteTaskListByIds(ids);
        return success(true);
    }

    /**
     * 获得静态脱敏任务
     *
     *  @param id 编号
     *  @return 任务
     */
    @GetMapping("/get")
    @Operation(summary = "获得静态脱敏任务")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:query')")
    public CommonResult<SafetyStaticTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(convertRespVO(taskService.getTask(id)));
    }

    /**
     * 获得静态脱敏任务分页
     *
     *  @param pageReqVO 分页查询
     *  @return 任务分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得静态脱敏任务分页")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:query')")
    public CommonResult<PageResult<SafetyStaticTaskRespVO>> getTaskPage(@Valid SafetyStaticTaskPageReqVO pageReqVO) {
        PageResult<SafetyStaticTaskDO> pageResult = taskService.getTaskPage(pageReqVO);
        return success(new PageResult<>(
                pageResult.getList().stream().map(this::convertRespVO).toList(),
                pageResult.getTotal()));
    }

    /**
     * 更新任务上下线状态
     *
     *  @param id     编号
     *  @param status 状态：0-上线、1-下线
     */
    @PutMapping("/update-status")
    @Operation(summary = "更新静态脱敏任务上下线状态")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:update')")
    public CommonResult<Boolean> updateTaskStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        taskService.updateTaskStatus(id, status);
        return success(true);
    }

    /**
     * 手动执行任务（异步）
     *
     *  @param id 任务编号
     */
    @PostMapping("/execute")
    @Operation(summary = "手动执行静态脱敏任务（异步）")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:execute')")
    public CommonResult<Boolean> executeTask(@RequestParam("id") Long id) {
        taskService.executeTask(id);
        return success(true);
    }

    /**
     * 获得源表字段列表（用于配置脱敏字段）。
     */
    @GetMapping("/column-list")
    @Operation(summary = "获得源表字段列表（用于配置脱敏字段）")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:query')")
    public CommonResult<List<DatabaseColumnRespDTO>> getColumnList(
            @RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName) {
        return success(databaseApi.getTableColumnList(databaseId, tableName).getCheckedData());
    }

    // ==================== 执行记录 ====================

    /**
     * 获得任务执行记录分页
     *
     *  @param pageReqVO 分页查询
     *  @return 执行记录分页
     */
    @GetMapping("/log/page")
    @Operation(summary = "获得静态脱敏任务执行记录分页")
    @PreAuthorize("@ss.hasPermission('governance:safety-static-task:query')")
    public CommonResult<PageResult<SafetyStaticTaskLogRespVO>> getTaskLogPage(
            @Valid SafetyStaticTaskLogPageReqVO pageReqVO) {
        PageResult<SafetyStaticTaskLogDO> pageResult = taskService.getTaskLogPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, SafetyStaticTaskLogRespVO.class));
    }

    /**
     * 转换响应VO。
     */
    private SafetyStaticTaskRespVO convertRespVO(SafetyStaticTaskDO task) {
        if (task == null) {
            return null;
        }
        SafetyStaticTaskRespVO respVO = BeanUtils.toBean(task, SafetyStaticTaskRespVO.class);
        if (StrUtil.isNotBlank(task.getFieldRulesJson())) {
            respVO.setFieldRules(JsonUtils.parseObject(task.getFieldRulesJson(),
                    new TypeReference<List<SafetyStaticTaskFieldRuleVO>>() {
                    }));
        }
        return respVO;
    }

}
