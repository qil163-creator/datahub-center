package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo.*;
import cn.iocoder.yudao.module.modeling.service.conceptmodel.ConceptModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 概念模型")
@RestController
@RequestMapping("/modeling/concept-model")
@Validated
public class ConceptModelController {

    @Resource
    private ConceptModelService service;

    /**
     * 创建概念模型草稿。
     *
     * @param request 请求参数
     * @return 概念模型
     */
    @LogRecord(type = "数据建模-概念模型", subType = "创建概念模型草稿", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建概念模型草稿", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建概念模型草稿")
    @PreAuthorize("@ss.hasPermission('modeling:concept-model:create')")
    public CommonResult<Long> create(@Valid @RequestBody ConceptModelSaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 修改概念模型及实体关系设计。
     *
     * @param request 请求参数
     * @return 概念模型
     */
    @LogRecord(type = "数据建模-概念模型", subType = "修改概念模型及实体关系设计", bizNo = "{{(#request.id) ?: 0}}",
            success = "修改概念模型及实体关系设计", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "修改概念模型及实体关系设计")
    @PreAuthorize("@ss.hasPermission('modeling:concept-model:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody ConceptModelUpdateReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 启用或停用概念模型。
     *
     * @param request 请求参数
     * @return 概念模型
     */
    @LogRecord(type = "数据建模-概念模型", subType = "启用或停用概念模型", bizNo = "{{(#request.id) ?: 0}}",
            success = "启用或停用概念模型", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "启用或停用概念模型")
    @PreAuthorize("@ss.hasPermission('modeling:concept-model:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody ConceptModelStatusReqVO request) {
        service.updateStatus(request);
        return success(true);
    }

    /**
     * 删除概念模型草稿。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 概念模型
     */
    @LogRecord(type = "数据建模-概念模型", subType = "删除概念模型草稿", bizNo = "{{(#id) ?: 0}}",
            success = "删除概念模型草稿", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除概念模型草稿")
    @PreAuthorize("@ss.hasPermission('modeling:concept-model:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") @Positive Long id,
            @RequestParam("expectedRevision") @PositiveOrZero Long expectedRevision) {
        service.delete(id, expectedRevision);
        return success(true);
    }

    /**
     * 获得概念模型及完整实体关系设计。
     *
     * @param id 记录编号
     * @return 概念模型响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得概念模型及完整实体关系设计")
    @PreAuthorize("@ss.hasPermission('modeling:concept-model:query')")
    public CommonResult<ConceptModelRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 获得概念模型分页，不返回设计正文。
     *
     * @param request 请求参数
     * @return 概念模型分页，不返回设计正文结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得概念模型分页，不返回设计正文")
    @PreAuthorize("@ss.hasPermission('modeling:concept-model:query')")
    public CommonResult<PageResult<ConceptModelRespVO>> page(@Valid ConceptModelPageReqVO request) {
        return success(service.page(request));
    }
}
