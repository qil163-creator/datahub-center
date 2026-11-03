package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstancePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityTaskInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-任务实例 Controller（只读监控）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-任务实例监控")
@RestController
@RequestMapping("/governance/quality-task-instance")
@Validated
public class QualityTaskInstanceController {

    @Resource
    private QualityTaskInstanceService instanceService;

    /**
     * 获得任务实例。
     */
    @GetMapping("/get")
    @Operation(summary = "获得任务实例")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task-instance:query')")
    public CommonResult<QualityTaskInstanceRespVO> getInstance(@RequestParam("id") Long id) {
        return success(instanceService.getInstanceDetail(id));
    }

    /**
     * 获得任务实例分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得任务实例分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-task-instance:query')")
    public CommonResult<PageResult<QualityTaskInstanceRespVO>> getInstancePage(
            @Valid QualityTaskInstancePageReqVO pageReqVO) {
        return success(instanceService.getInstancePage(pageReqVO));
    }

}
