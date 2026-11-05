package cn.iocoder.yudao.module.modeling.controller.admin.indicator;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
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

@Tag(name = "管理后台 - 指标模型")
@RestController
@RequestMapping("/modeling/indicator-model")
@Validated
public class IndicatorModelController {

    @Resource
    private IndicatorModelService service;

    /**
     * 创建指标定义。
     *
     * @param request 请求参数
     * @return 指标模型
     */
    @LogRecord(type = "数据建模-指标模型", subType = "创建指标定义", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了指标定义【{{#request.name}}】，编码【{{#request.code}}】，编号【{{#_ret.data}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建指标定义")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:create')")
    public CommonResult<Long> create(@Valid @RequestBody IndicatorModelSaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 更新草稿指标定义。
     *
     * @param request 请求参数
     * @return 指标模型
     */
    @LogRecord(type = "数据建模-指标模型", subType = "更新草稿指标定义", bizNo = "{{(#request.id) ?: 0}}",
            success = "修改了指标定义【{{#request.name}}】（编号{{#request.id}}），编码【{{#request.code}}】，校验修订【{{#request.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新草稿指标定义")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody IndicatorModelSaveReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 删除未被引用的草稿指标。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 指标模型
     */
    @LogRecord(type = "数据建模-指标模型", subType = "删除未被引用的草稿指标", bizNo = "{{(#id) ?: 0}}",
            success = "删除了指标定义【编号{{#id}}】，校验修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除未被引用的草稿指标")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id,
            @RequestParam @PositiveOrZero Long expectedRevision) {
        service.delete(id, expectedRevision);
        return success(true);
    }

    /**
     * 启用或停用指标定义。
     *
     * @param request 请求参数
     * @return 指标模型
     */
    @LogRecord(type = "数据建模-指标模型", subType = "启用或停用指标定义", bizNo = "{{(#request.id) ?: 0}}",
            success = "将指标定义【编号{{#request.id}}】状态设为【{{#request.status}}】，校验修订【{{#request.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "启用或停用指标定义")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody IndicatorModelStatusReqVO request) {
        service.updateStatus(request);
        return success(true);
    }

    /**
     * 获得指标定义详情。
     *
     * @param id 记录编号
     * @return 指标模型响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得指标定义详情")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:query')")
    public CommonResult<IndicatorModelRespVO> get(@RequestParam @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 分页查询指标定义。
     *
     * @param request 请求参数
     * @return 分页查询指标定义结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询指标定义")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:query')")
    public CommonResult<PageResult<IndicatorModelRespVO>> page(@Valid IndicatorModelPageReqVO request) {
        return success(service.page(request));
    }

    /**
     * 获得可引用的启用原子指标。
     *
     * @return 可引用的启用原子指标结果集合
     */
    @GetMapping("/atomic-options")
    @Operation(summary = "获得可引用的启用原子指标")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:query')")
    public CommonResult<List<IndicatorModelRespVO>> atomicOptions() {
        return success(service.atomicOptions());
    }

    /**
     * 获得来源已发布当前设计的稳定字段。
     *
     * @param modelId 模型编号
     * @return 来源已发布当前设计的稳定字段结果集合
     */
    @GetMapping("/source-fields")
    @Operation(summary = "获得来源已发布当前设计的稳定字段")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:query') && @ss.hasPermission('modeling:model:query')")
    public CommonResult<List<IndicatorSourceFieldRespVO>> sourceFields(@RequestParam @Positive Long modelId) {
        return success(service.sourceFields(modelId));
    }
}

