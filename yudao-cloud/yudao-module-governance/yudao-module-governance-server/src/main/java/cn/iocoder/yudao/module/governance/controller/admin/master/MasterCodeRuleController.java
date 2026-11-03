package cn.iocoder.yudao.module.governance.controller.admin.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterCodeRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据编码规则")
@RestController
@RequestMapping("/governance/master-code-rule")
@Validated
public class MasterCodeRuleController {

    @Resource
    private MasterCodeRuleService service;

    /**
     * 创建编码规则。
     */
    @PostMapping("/create")
    @Operation(summary = "创建编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:create')")
    public CommonResult<Long> create(@Valid @RequestBody MasterCodeRuleSaveReqVO req) {
        return success(service.create(req));
    }

    /**
     * 修改编码规则。
     */
    @PutMapping("/update")
    @Operation(summary = "修改编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MasterCodeRuleSaveReqVO req) {
        service.update(req);
        return success(true);
    }

    /**
     * 启停编码规则。
     */
    @PutMapping("/update-status")
    @Operation(summary = "启停编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody MasterCodeRuleStatusReqVO req) {
        service.updateStatus(req);
        return success(true);
    }

    /**
     * 删除编码规则。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:delete')")
    public CommonResult<Boolean> delete(@RequestParam Long id) {
        service.deleteList(List.of(id));
        return success(true);
    }

    /**
     * 批量删除编码规则。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam List<Long> ids) {
        service.deleteList(ids);
        return success(true);
    }

    /**
     * 查询编码规则。
     */
    @GetMapping("/get")
    @Operation(summary = "查询编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:query')")
    public CommonResult<MasterCodeRuleRespVO> get(@RequestParam Long id) {
        return success(service.get(id));
    }

    /**
     * 分页查询编码规则。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询编码规则")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:query')")
    public CommonResult<PageResult<MasterCodeRuleRespVO>> page(@Valid MasterCodeRulePageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 预览编码，不发放流水号。
     */
    @PostMapping("/preview")
    @Operation(summary = "预览编码，不发放流水号")
    @PreAuthorize("@ss.hasPermission('governance:master-code-rule:query') or @ss.hasPermission('governance:master-code-rule:create') or @ss.hasPermission('governance:master-code-rule:update')")
    public CommonResult<String> preview(@Valid @RequestBody MasterCodeRuleSaveReqVO req) {
        return success(service.preview(req));
    }
}
