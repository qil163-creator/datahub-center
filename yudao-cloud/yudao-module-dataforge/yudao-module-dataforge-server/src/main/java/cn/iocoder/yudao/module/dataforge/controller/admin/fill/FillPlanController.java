package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillPlanService;
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
 * 在线填报-采集计划 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报采集计划")
@RestController
@RequestMapping("/dataforge/fill-plan")
@Validated
public class FillPlanController {

    @Resource
    private FillPlanService planService;

    /**
     * 创建采集计划。
     *
     * @param createReqVO 创建参数
     * @return 填报计划
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-在线填报采集计划", subType = "创建采集计划", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了采集计划【{{(#createReqVO.name) ?: '未设置'}}】，模板版本【{{(#createReqVO.tableVersionId) ?: '未设置'}}】，周期类型【{{(#createReqVO.periodType) ?: '未设置'}}】，发布审批开关【{{(#createReqVO.publishApprovalEnabled) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建采集计划")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:create')")
    public CommonResult<Long> createPlan(@Valid @RequestBody FillPlanSaveReqVO createReqVO) {
        return success(planService.createPlan(createReqVO));
    }

    /**
     * 更新采集计划。
     *
     * @param updateReqVO 更新参数
     * @return 填报计划
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-在线填报采集计划", subType = "更新采集计划", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了采集计划【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#updateReqVO.name) ?: '未设置'}}】，模板版本【{{(#updateReqVO.tableVersionId) ?: '未设置'}}】，周期类型【{{(#updateReqVO.periodType) ?: '未设置'}}】，发布审批开关【{{(#updateReqVO.publishApprovalEnabled) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新采集计划", description = "启用中的计划不允许变更模板版本与周期类型")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:update')")
    public CommonResult<Boolean> updatePlan(@Valid @RequestBody FillPlanSaveReqVO updateReqVO) {
        String operateName = "编号" + updateReqVO.getId();
        try {
            var operateObject = planService.getPlan(updateReqVO.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        planService.updatePlan(updateReqVO);
        return success(true);
    }

    /**
     * 删除采集计划（仅草稿/停用状态）。
     *
     * @param id 记录编号
     * @return 填报计划
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-在线填报采集计划", subType = "删除采集计划（仅草稿/停用状态）", bizNo = "{{(#id) ?: 0}}",
            success = "删除了采集计划【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除采集计划（仅草稿/停用状态）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:delete')")
    public CommonResult<Boolean> deletePlan(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = planService.getPlan(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        planService.deletePlan(id);
        return success(true);
    }

    /**
     * 获得采集计划（含目标组织与填报人）。
     *
     * @param id 记录编号
     * @return 填报计划响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得采集计划（含目标组织与填报人）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:query')")
    public CommonResult<FillPlanRespVO> getPlan(@RequestParam("id") Long id) {
        return success(planService.getPlan(id));
    }

    /**
     * 获得采集计划分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 采集计划分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得采集计划分页")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:query')")
    public CommonResult<PageResult<FillPlanRespVO>> getPlanPage(@Valid FillPlanPageReqVO pageReqVO) {
        return success(planService.getPlanPage(pageReqVO));
    }

    /**
     * 发布（启用）采集计划。
     *
     * @param id 记录编号
     * @return 填报计划
     */
    @PutMapping("/publish")
    @LogRecord(type = "数据开发-在线填报采集计划", subType = "发布（启用）采集计划", bizNo = "{{(#id) ?: 0}}",
            success = "启用了采集计划【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布（启用）采集计划", description = "完整性校验并登记目标表写入；一次性计划发布即生成任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:publish')")
    public CommonResult<Boolean> publishPlan(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = planService.getPlan(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        planService.publishPlan(id);
        return success(true);
    }

    /**
     * 停用采集计划。
     *
     * @param id 记录编号
     * @return 填报计划
     */
    @PutMapping("/disable")
    @LogRecord(type = "数据开发-在线填报采集计划", subType = "停用采集计划", bizNo = "{{(#id) ?: 0}}",
            success = "停用了采集计划【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "停用采集计划", description = "停止未来任务生成；已生成任务不受影响")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:publish')")
    public CommonResult<Boolean> disablePlan(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = planService.getPlan(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        planService.disablePlan(id);
        return success(true);
    }

}
