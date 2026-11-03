package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDraftSnapshotRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmissionRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemAssigneeUpdateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemTransferReqVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillDraftService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillSubmissionService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillWorkItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-组织填报实例（工作项）Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报工作项")
@RestController
@RequestMapping("/dataforge/fill-work-item")
@Validated
public class FillWorkItemController {

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillDraftService draftService;

    @Resource
    private FillSubmissionService submissionService;

    /**
     * 我的填报：当前用户作为有效填报人的工作项。
     *
     * @return 当前用户工作项
     */
    @GetMapping("/my")
    @Operation(summary = "我的填报：当前用户作为有效填报人的工作项")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public CommonResult<List<FillWorkItemRespVO>> getMyWorkItems() {
        return success(workItemService.getMyWorkItems());
    }

    /**
     * 获得工作项详情（有效填报人/发起者/部门负责人可见）。
     *
     * @param id 记录编号
     * @return 填报工作项响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得工作项详情（有效填报人/发起者/部门负责人可见）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public CommonResult<FillWorkItemRespVO> getWorkItem(@RequestParam("id") Long id) {
        return success(workItemService.getWorkItem(id));
    }

    /**
     * 任务下的全部工作项（发起者进度视角）。
     *
     * @param taskId 任务编号
     * @return 任务下的全部工作项（发起者进度视角）结果集合
     */
    @GetMapping("/list-by-task")
    @Operation(summary = "任务下的全部工作项（发起者进度视角）")
    @Parameter(name = "taskId", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:query')")
    public CommonResult<List<FillWorkItemRespVO>> getWorkItemListByTask(@RequestParam("taskId") Long taskId) {
        return success(workItemService.getWorkItemListByTask(taskId));
    }

    /**
     * 部门负责人只读查看本部门工作项。
     *
     * @param deptId 部门编号
     * @return 部门负责人只读查看本部门工作项结果集合
     */
    @GetMapping("/dept-view")
    @Operation(summary = "部门负责人只读查看本部门工作项")
    @Parameter(name = "deptId", description = "部门编号（缺省为当前用户所在部门）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:dept-view')")
    public CommonResult<List<FillWorkItemRespVO>> getDeptWorkItems(
            @RequestParam(value = "deptId", required = false) Long deptId) {
        return success(workItemService.getDeptWorkItems(deptId));
    }

    /**
     * 调整填报人（发起者/授权管理员：新增或移除）。
     *
     * @param reqVO 请求参数
     * @return 填报工作项
     */
    @PutMapping("/assignees")
    @Operation(summary = "调整填报人（发起者/授权管理员：新增或移除）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:assignee')")
    public CommonResult<Boolean> updateAssignees(@Valid @RequestBody FillWorkItemAssigneeUpdateReqVO reqVO) {
        workItemService.updateAssignees(reqVO);
        return success(true);
    }

    /**
     * 填报职责转派（填报人本人发起，限同组织）。
     *
     * @param reqVO 请求参数
     * @return 填报工作项
     */
    @PostMapping("/transfer")
    @Operation(summary = "填报职责转派（填报人本人发起，限同组织）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:transfer')")
    public CommonResult<Boolean> transfer(@Valid @RequestBody FillWorkItemTransferReqVO reqVO) {
        workItemService.transfer(reqVO);
        return success(true);
    }

    /**
     * 催办单个组织实例。
     *
     * @param id 记录编号
     * @return 填报工作项
     */
    @PostMapping("/urge")
    @Operation(summary = "催办单个组织实例", description = "通知当前有效填报人；1 小时内重复催办被拒绝")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:urge')")
    public CommonResult<Boolean> urgeWorkItem(@RequestParam("id") Long id) {
        workItemService.urgeWorkItem(id);
        return success(true);
    }

    // ==================== 草稿（多人共享） ====================

    /**
     * 草稿全量快照（进入工作台/断线重连）。
     *
     * @param id 记录编号
     * @return 填报草稿快照响应数据
     */
    @GetMapping("/{id}/snapshot")
    @Operation(summary = "草稿全量快照（进入工作台/断线重连）")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public CommonResult<FillDraftSnapshotRespVO> getDraftSnapshot(@PathVariable("id") Long id) {
        return success(draftService.getSnapshot(id));
    }

    /**
     * 增量拉取草稿操作（协同补齐与重放）。
     *
     * @param id 记录编号
     * @param afterVersion 变更后版本
     * @param limit 读取或处理上限
     * @return 增量拉取草稿操作（协同补齐与重放）结果集合
     */
    @GetMapping("/{id}/operations")
    @Operation(summary = "增量拉取草稿操作（协同补齐与重放）")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public CommonResult<List<FillOperationRespVO>> getDraftOperations(
            @PathVariable("id") Long id,
            @RequestParam(value = "afterVersion", required = false) Long afterVersion,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return success(draftService.getOperations(id, afterVersion, limit));
    }

    /**
     * 应用一批草稿操作（SET_CELL/ADD_ROW/REMOVE_ROW，单事务原子 + clientOpId 幂等）。
     *
     * @param id 记录编号
     * @param reqVO 请求参数
     * @return 填报操作应用响应数据
     */
    @PatchMapping("/{id}/operations")
    @Operation(summary = "应用一批草稿操作（SET_CELL/ADD_ROW/REMOVE_ROW，单事务原子 + clientOpId 幂等）")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<FillOperationApplyRespVO> applyDraftOperations(
            @PathVariable("id") Long id, @Valid @RequestBody FillOperationApplyReqVO reqVO) {
        return success(draftService.applyOperations(id, reqVO));
    }

    // ==================== 提交与审批 ====================

    /**
     * 提交填报数据。
     *
     * @param id 记录编号
     * @param reqVO 请求参数
     * @return 填报提交响应数据
     */
    @PostMapping("/{id}/submit")
    @Operation(summary = "提交填报数据",
            description = "冻结草稿（状态+版本 CAS）→ 服务端强校验 → 不可变快照 → 可靠启动 BPM；校验失败返回问题清单")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:submit')")
    public CommonResult<FillSubmitRespVO> submit(@PathVariable("id") Long id,
            @Valid @RequestBody FillSubmitReqVO reqVO) {
        return success(submissionService.submit(id, reqVO));
    }

    /**
     * 工作项的全部提交记录（按提交次数倒序）。
     *
     * @param id 记录编号
     * @return 工作项的全部提交记录（按提交次数倒序）结果集合
     */
    @GetMapping("/{id}/submissions")
    @Operation(summary = "工作项的全部提交记录（按提交次数倒序）")
    @Parameter(name = "id", description = "工作项编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public CommonResult<List<FillSubmissionRespVO>> getSubmissions(@PathVariable("id") Long id) {
        return success(submissionService.getSubmissionsByWorkItem(id));
    }

}
