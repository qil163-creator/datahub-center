package cn.iocoder.yudao.module.modeling.controller.admin.indicator;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryMetaReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryMetaRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryRespVO;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 指标数据查询：读取来源物化表的真实数据，使用独立权限，不复用指标定义的查询权限。
 */
@Tag(name = "管理后台 - 指标数据查询")
@RestController
@RequestMapping("/modeling/indicator-model")
@Validated
public class IndicatorQueryController {

    @Resource
    private IndicatorQueryService service;

    /**
     * 按维度、时间范围和粒度查询指标值。
     *
     * @param request 请求参数
     * @return 列、行与生效口径
     */
    @PostMapping("/query")
    @Operation(summary = "查询指标数据")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:data-query')")
    public CommonResult<IndicatorQueryRespVO> query(@Valid @RequestBody IndicatorQueryReqVO request) {
        return success(service.query(request));
    }

    /**
     * 查询所选指标可共同使用的维度字段与时间能力。
     *
     * @param request 请求参数
     * @return 可选维度
     */
    @PostMapping("/query-meta")
    @Operation(summary = "获得指标查询可选维度")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:data-query')")
    public CommonResult<IndicatorQueryMetaRespVO> meta(@Valid @RequestBody IndicatorQueryMetaReqVO request) {
        return success(service.meta(request));
    }
}
