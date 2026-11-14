package cn.iocoder.yudao.module.dataforge.controller.admin.filltable;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRuleRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRuleSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.filltable.FillTableRuleService;
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
 * 数据填报-填报表校验规则（行级、表级、跨期）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据填报-填报表校验规则")
@RestController
@RequestMapping("/dataforge/fill-table-rule")
@Validated
public class FillTableRuleController {

    @Resource
    private FillTableRuleService ruleService;

    @GetMapping("/list-by-table")
    @Operation(summary = "获得填报表的校验规则列表")
    @Parameter(name = "tableId", description = "填报表编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<List<FillTableRuleRespVO>> getRuleList(@RequestParam("tableId") Long tableId) {
        return success(ruleService.getRuleListByTableId(tableId));
    }

    @PostMapping("/create")
    @LogRecord(type = "数据开发-数据填报-填报表校验规则", subType = "创建校验规则", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "在填报表【{{(#reqVO.tableId) ?: '未设置'}}】创建了{{(#reqVO.scope) ?: ''}}规则【{{(#reqVO.name) ?: '未设置'}}】：{{(#reqVO.expression) ?: ''}}",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "创建填报表校验规则")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Long> createRule(@Valid @RequestBody FillTableRuleSaveReqVO reqVO) {
        return success(ruleService.createRule(reqVO));
    }

    @PutMapping("/update")
    @LogRecord(type = "数据开发-数据填报-填报表校验规则", subType = "修改校验规则", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "修改了校验规则【{{(#reqVO.name) ?: '未设置'}}】：{{(#reqVO.expression) ?: ''}}",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "修改填报表校验规则")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Boolean> updateRule(@Valid @RequestBody FillTableRuleSaveReqVO reqVO) {
        ruleService.updateRule(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据填报-填报表校验规则", subType = "删除校验规则", bizNo = "{{(#id) ?: 0}}",
            success = "删除了校验规则【{{(#id) ?: 0}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "删除填报表校验规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Boolean> deleteRule(@RequestParam("id") Long id) {
        ruleService.deleteRule(id);
        return success(true);
    }

}
