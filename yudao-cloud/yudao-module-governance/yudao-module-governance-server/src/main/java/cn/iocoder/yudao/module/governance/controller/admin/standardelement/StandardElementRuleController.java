package cn.iocoder.yudao.module.governance.controller.admin.standardelement;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.*;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardElementRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据元关联规则")
@RestController
@RequestMapping("/governance/standard-element")
@Validated
@RequiredArgsConstructor
public class StandardElementRuleController {

    private final StandardElementRuleService service;

    /**
     * 查询关联配置分页。
     */
    @GetMapping("/rule-page")
    @Operation(summary = "查询关联配置分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<PageResult<StandardElementRuleRespVO>> page(@Valid StandardElementRulePageReqVO request) {
        return success(service.page(request));
    }

    /**
     * 查询关联配置详情。
     */
    @GetMapping("/rule-get")
    @Operation(summary = "查询关联配置详情")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<StandardElementRuleRespVO> get(@RequestParam Integer elementId, @RequestParam Long id) {
        return success(service.get(elementId, id));
    }

    /**
     * 创建关联配置。
     */
    @PostMapping("/rule-create")
    @BusinessOperateLog(type = "数据治理-数据元关联规则", bizId = "#result")
    @Operation(summary = "创建关联配置")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Long> create(@Valid @RequestBody StandardElementRuleSaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 更新关联配置。
     */
    @PutMapping("/rule-update")
    @BusinessOperateLog(type = "数据治理-数据元关联规则", bizId = "#request.id")
    @Operation(summary = "更新关联配置")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody StandardElementRuleSaveReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 删除关联配置。
     */
    @DeleteMapping("/rule-delete")
    @BusinessOperateLog(type = "数据治理-数据元关联规则", bizId = "#id")
    @Operation(summary = "删除关联配置")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> delete(@RequestParam Integer elementId, @RequestParam Long id) {
        service.delete(elementId, id);
        return success(true);
    }

    /**
     * 查询全部关联规则模板及适用状态。
     */
    @GetMapping("/rule-templates")
    @Operation(summary = "查询全部关联规则模板及适用状态")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<java.util.List<StandardElementRuleTemplateRespVO>> templates(@RequestParam String kind) {
        return success(service.templates(kind));
    }

    /**
     * 查询当前用户可用的模型字段上下文。
     */
    @GetMapping("/rule-context")
    @Operation(summary = "查询当前用户可用的模型字段上下文")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<StandardElementRuleContextRespVO> context(@RequestParam Integer elementId) {
        return success(service.context(elementId));
    }

    /**
     * 检测字段字符样例。
     */
    @PostMapping("/rule-sample")
    @Operation(summary = "检测字段字符样例")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<StandardElementRuleService.SampleResult> sample(
            @Valid @RequestBody StandardElementRuleSampleReqVO request) {
        return success(service.sample(request));
    }

}
