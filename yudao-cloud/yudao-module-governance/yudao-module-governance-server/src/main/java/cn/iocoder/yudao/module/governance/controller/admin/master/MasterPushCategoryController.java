package cn.iocoder.yudao.module.governance.controller.admin.master;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterPushCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据推送类目")
@RestController
@RequestMapping("/governance/master-push-category")
@Validated
@RequiredArgsConstructor
public class MasterPushCategoryController {
    private final MasterPushCategoryService service;

    @GetMapping("/tree") @Operation(summary = "读取推送类目目录")
    @PreAuthorize("@ss.hasAnyPermissions('governance:master-distribute:query', 'governance:master-distribute:create', 'governance:master-distribute:update', 'governance:master-push-category:query')")
    public CommonResult<List<MasterPushCategoryRespVO>> tree() { return success(service.tree()); }

    @GetMapping("/list") @Operation(summary = "读取推送类目列表")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:query')")
    public CommonResult<List<MasterPushCategoryRespVO>> list() { return success(service.list()); }

    @GetMapping("/get") @Operation(summary = "读取推送类目详情")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:query')")
    public CommonResult<MasterPushCategoryRespVO> get(@RequestParam @Positive Long id) { return success(service.get(id)); }

    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-主数据推送类目", bizId = "#result")
    @Operation(summary = "创建推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:create')")
    public CommonResult<Long> create(@Valid @RequestBody MasterPushCategorySaveReqVO request) { return success(service.create(request)); }

    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-主数据推送类目", bizId = "#request.id")
    @Operation(summary = "修改推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MasterPushCategorySaveReqVO request) { service.update(request); return success(true); }

    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据治理-主数据推送类目", bizId = "#request.id")
    @Operation(summary = "更新推送类目状态")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody MasterPushCategoryStatusReqVO request) { service.updateStatus(request); return success(true); }

    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-主数据推送类目", bizId = "#id")
    @Operation(summary = "删除推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id) { service.delete(List.of(id)); return success(true); }

    @DeleteMapping("/delete-list")
    @BusinessOperateLog(type = "数据治理-主数据推送类目", bizId = "#ids")
    @Operation(summary = "批量删除推送类目")
    @PreAuthorize("@ss.hasPermission('governance:master-push-category:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam @NotEmpty @Size(max = 200) List<@Positive Long> ids) { service.delete(ids); return success(true); }
}
