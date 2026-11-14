package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillChildActionReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillChildrenRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDispatchCandidateRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDispatchReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillRollupRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillHierarchyService;
import com.mzt.logapi.starter.annotation.LogRecord;
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
 * 在线填报-多级下发与逐级汇总 Controller
 *
 * <p>按钮权限复用 dataforge:fill-work-item:my（查看）与 dataforge:fill-work-item:edit（操作）；
 * 对象级范围在服务端校验：操作人须是上级实例的有效填报人。</p>
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报多级下发")
@RestController
@RequestMapping("/dataforge/fill-work-item")
@Validated
public class FillHierarchyController {

    @Resource
    private FillHierarchyService hierarchyService;

    @GetMapping("/{id}/children")
    @Operation(summary = "获得直接下级与汇总状态")
    @Parameter(name = "id", description = "上级实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:my')")
    public CommonResult<FillChildrenRespVO> getChildren(@PathVariable("id") Long id) {
        return success(hierarchyService.getChildren(id));
    }

    @GetMapping("/{id}/dispatch-candidates")
    @Operation(summary = "获得可下发的下级组织", description = "本单位部门子树内的组织，带出默认下发名单")
    @Parameter(name = "id", description = "上级实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<List<FillDispatchCandidateRespVO>> getDispatchCandidates(@PathVariable("id") Long id) {
        return success(hierarchyService.getDispatchCandidates(id));
    }

    @PostMapping("/{id}/dispatch")
    @LogRecord(type = "数据开发-在线填报工作项", subType = "向下级下发", bizNo = "{{#id}}",
            success = "从填报工作项【{{#id}}】向下级下发了【{{#_ret.data}}】个组织", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "向下级下发")
    @Parameter(name = "id", description = "上级实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<Integer> dispatch(@PathVariable("id") Long id, @Valid @RequestBody FillDispatchReqVO reqVO) {
        return success(hierarchyService.dispatch(id, reqVO));
    }

    @PostMapping("/{id}/rollup")
    @Operation(summary = "汇总下级数据", description = "把审核通过的下级数据写入本单位草稿，替换上一次汇总的行")
    @Parameter(name = "id", description = "上级实例编号", required = true)
    @Parameter(name = "expectedVersion", description = "客户端已同步到的服务端版本", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<FillRollupRespVO> rollup(@PathVariable("id") Long id,
                                                 @RequestParam("expectedVersion") Long expectedVersion) {
        return success(hierarchyService.rollup(id, expectedVersion));
    }

    @PostMapping("/{id}/urge-children")
    @Operation(summary = "催办未完成的下级")
    @Parameter(name = "id", description = "上级实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<Integer> urgeChildren(@PathVariable("id") Long id) {
        return success(hierarchyService.urgeChildren(id));
    }

    @PutMapping("/child/review")
    @LogRecord(type = "数据开发-在线填报工作项", subType = "上级单位审核下级", bizNo = "{{#reqVO.childWorkItemId}}",
            success = "审核下级填报工作项【{{#reqVO.childWorkItemId}}】，结论【{{#reqVO.approve ? '通过' : '驳回'}}】，意见【{{(#reqVO.reason) ?: '无'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "上级单位审核下级提交")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<Boolean> review(@Valid @RequestBody FillChildActionReqVO reqVO) {
        hierarchyService.review(reqVO);
        return success(true);
    }

    @PutMapping("/child/return")
    @LogRecord(type = "数据开发-在线填报工作项", subType = "退回已汇入的下级", bizNo = "{{#reqVO.childWorkItemId}}",
            success = "退回下级填报工作项【{{#reqVO.childWorkItemId}}】，原因【{{(#reqVO.reason) ?: '无'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "退回已汇入的下级（逐级退回）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<Boolean> returnChild(@Valid @RequestBody FillChildActionReqVO reqVO) {
        hierarchyService.returnChild(reqVO);
        return success(true);
    }

    @PutMapping("/child/cancel")
    @LogRecord(type = "数据开发-在线填报工作项", subType = "撤销下发", bizNo = "{{#reqVO.childWorkItemId}}",
            success = "撤销下级填报工作项【{{#reqVO.childWorkItemId}}】的下发，原因【{{(#reqVO.reason) ?: '无'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "撤销下发（仅未提交的下级）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<Integer> cancelChild(@Valid @RequestBody FillChildActionReqVO reqVO) {
        return success(hierarchyService.cancelChild(reqVO));
    }

    @PutMapping("/child/deadline")
    @Operation(summary = "调整下级截止时间（不能晚于本单位截止时间）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-work-item:edit')")
    public CommonResult<Boolean> updateChildDeadline(@Valid @RequestBody FillChildActionReqVO reqVO) {
        hierarchyService.updateChildDeadline(reqVO);
        return success(true);
    }

}
