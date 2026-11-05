package cn.iocoder.yudao.module.governance.controller.admin.master;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDomainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据域")
@RestController
@RequestMapping("/governance/master-domain")
@Validated
public class MasterDomainController {

    @Resource
    private MasterDomainService domainService;

    /**
     * 创建主数据域。
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-主数据域", bizId = "#result")
    @Operation(summary = "创建主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:create')")
    public CommonResult<Long> create(@Valid @RequestBody MasterDomainSaveReqVO req) {
        return success(domainService.createDomain(req));
    }

    /**
     * 修改主数据域。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-主数据域", bizId = "#req.id")
    @Operation(summary = "修改主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MasterDomainSaveReqVO req) {
        domainService.updateDomain(req);
        return success(true);
    }

    /**
     * 更新主数据域状态。
     */
    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据治理-主数据域", bizId = "#req.id")
    @Operation(summary = "更新主数据域状态")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody MasterDomainUpdateStatusReqVO req) {
        domainService.updateStatus(req.getId(), req.getStatus());
        return success(true);
    }

    /**
     * 删除主数据域。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-主数据域", bizId = "#id")
    @Operation(summary = "删除主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        domainService.deleteDomain(id);
        return success(true);
    }

    /**
     * 批量删除主数据域。
     */
    @DeleteMapping("/delete-list")
    @BusinessOperateLog(type = "数据治理-主数据域", bizId = "#ids")
    @Operation(summary = "批量删除主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam("ids") @NotEmpty @Size(max = 100) List<Long> ids) {
        domainService.deleteDomainList(ids);
        return success(true);
    }

    /**
     * 查询主数据域。
     */
    @GetMapping("/get")
    @Operation(summary = "查询主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:query')")
    public CommonResult<MasterDomainRespVO> get(@RequestParam("id") Long id) {
        return success(domainService.getDomain(id));
    }

    /**
     * 分页查询主数据域。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询主数据域")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:query')")
    public CommonResult<PageResult<MasterDomainRespVO>> page(@Valid MasterDomainPageReqVO req) {
        return success(domainService.getDomainPage(req));
    }

    /**
     * 查询主数据域列表。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "查询主数据域列表")
    @PreAuthorize("@ss.hasPermission('governance:master-domain:query') or "
            + "@ss.hasPermission('governance:master-model:query') or @ss.hasPermission('governance:master-data:query')")
    public CommonResult<List<MasterDomainRespVO>> simpleList() {
        return success(domainService.getSimpleList());
    }
}
