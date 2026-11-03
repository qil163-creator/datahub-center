package cn.iocoder.yudao.module.modeling.controller.admin.businesscondition;

import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.service.businesscondition.BusinessConditionService;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 业务限定")
@RestController
@RequestMapping("/modeling/business-condition")
@Validated
public class BusinessConditionController {

    @Resource
    private BusinessConditionService service;

    @Resource
    private IndicatorModelService indicatorService;

    /**
     * 创建业务限定定义。
     *
     * @param request 请求参数
     * @return 业务限定
     */
    @PostMapping("/create")
    @Operation(summary = "创建业务限定定义")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:create')")
    public CommonResult<Long> create(@Valid @RequestBody BusinessConditionSaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 修改未启用的业务限定。
     *
     * @param request 请求参数
     * @return 业务限定
     */
    @PutMapping("/update")
    @Operation(summary = "修改未启用的业务限定")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody BusinessConditionSaveReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 删除未启用的业务限定。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 业务限定
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除未启用的业务限定")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id,
            @RequestParam @PositiveOrZero Long expectedRevision) {
        service.delete(id, expectedRevision);
        return success(true);
    }

    /**
     * 启用或停用业务限定。
     *
     * @param request 请求参数
     * @return 业务限定
     */
    @PutMapping("/update-status")
    @Operation(summary = "启用或停用业务限定")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody BusinessConditionStatusReqVO request) {
        service.updateStatus(request);
        return success(true);
    }

    /**
     * 获得业务限定详情。
     *
     * @param id 记录编号
     * @return 业务限定响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得业务限定详情")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:query')")
    public CommonResult<BusinessConditionRespVO> get(@RequestParam @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 分页查询业务限定。
     *
     * @param request 请求参数
     * @return 分页查询业务限定结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询业务限定")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:query')")
    public CommonResult<PageResult<BusinessConditionRespVO>> page(@Valid BusinessConditionPageReqVO request) {
        return success(service.page(request));
    }

    /**
     * 获得来源已发布当前设计的稳定字段。
     *
     * @param modelId 模型编号
     * @return 来源已发布当前设计的稳定字段结果集合
     */
    @GetMapping("/source-fields")
    @Operation(summary = "获得来源已发布当前设计的稳定字段")
    @PreAuthorize("@ss.hasPermission('modeling:business-condition:query') && @ss.hasPermission('modeling:model:query')")
    public CommonResult<List<IndicatorSourceFieldRespVO>> sourceFields(@RequestParam @Positive Long modelId) {
        return success(indicatorService.sourceFields(modelId));
    }
}
