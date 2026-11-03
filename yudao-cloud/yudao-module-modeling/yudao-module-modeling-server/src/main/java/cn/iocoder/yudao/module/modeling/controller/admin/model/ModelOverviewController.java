package cn.iocoder.yudao.module.modeling.controller.admin.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO;
import cn.iocoder.yudao.module.modeling.service.model.ModelOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据建模总览")
@RestController
@RequestMapping("/modeling/overview")
@Validated
public class ModelOverviewController {

    @Resource
    private ModelOverviewService modelOverviewService;

    /**
     * 获得数据建模总览统计（首页数据概览）。
     *
     * @return 模型概览
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据建模总览统计（首页数据概览）")
    @PreAuthorize("@ss.hasAnyPermissions('modeling:data-domain:query', 'modeling:subject-domain:query', "
            + "'modeling:biz-category:query', 'modeling:model:query', 'modeling:model-materialized:query', "
            + "'modeling:concept-model:query', 'modeling:indicator-model:query')")
    public CommonResult<ModelOverviewRespVO> getModelOverview() {
        return success(modelOverviewService.getModelOverview());
    }

}
