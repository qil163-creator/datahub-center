package cn.iocoder.yudao.module.dataforge.controller.admin.push;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.*;
import cn.iocoder.yudao.module.dataforge.service.push.PushCategoryService;
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

@Tag(name = "管理后台 - 数据推送类目")
@RestController
@RequestMapping("/dataforge/push-category")
@Validated
@RequiredArgsConstructor
public class PushCategoryController {
    private final PushCategoryService service;

    @GetMapping("/tree") @Operation(summary = "读取推送类目目录")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:data-push:query', 'dataforge:data-push:create', 'dataforge:data-push:update', 'dataforge:push-category:query')")
    public CommonResult<List<PushCategoryRespVO>> tree() { return success(service.tree()); }

    @GetMapping("/list") @Operation(summary = "读取推送类目列表")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:query')")
    public CommonResult<List<PushCategoryRespVO>> list() { return success(service.list()); }

    @GetMapping("/get") @Operation(summary = "读取推送类目详情")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:query')")
    public CommonResult<PushCategoryRespVO> get(@RequestParam @Positive Long id) { return success(service.get(id)); }

    @PostMapping("/create") @LogRecord(type = "数据开发-数据推送类目", subType = "创建推送类目", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建推送类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建推送类目")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:create')")
    public CommonResult<Long> create(@Valid @RequestBody PushCategorySaveReqVO request) { return success(service.create(request)); }

    @PutMapping("/update") @LogRecord(type = "数据开发-数据推送类目", subType = "修改推送类目", bizNo = "{{(#request.id) ?: 0}}",
            success = "修改推送类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改推送类目")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody PushCategorySaveReqVO request) { service.update(request); return success(true); }

    @PutMapping("/update-status") @LogRecord(type = "数据开发-数据推送类目", subType = "更新推送类目状态", bizNo = "{{(#request.id) ?: 0}}",
            success = "更新推送类目状态", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新推送类目状态")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody PushCategoryStatusReqVO request) { service.updateStatus(request); return success(true); }

    @DeleteMapping("/delete") @LogRecord(type = "数据开发-数据推送类目", subType = "删除推送类目", bizNo = "{{(#id) ?: 0}}",
            success = "删除推送类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除推送类目")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id) { service.delete(List.of(id)); return success(true); }

    @DeleteMapping("/delete-list") @LogRecord(type = "数据开发-数据推送类目", subType = "批量删除推送类目", bizNo = "{{(#ids) ?: 0}}",
            success = "批量删除推送类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除推送类目")
    @PreAuthorize("@ss.hasPermission('dataforge:push-category:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam @NotEmpty @Size(max = 200) List<@Positive Long> ids) { service.delete(ids); return success(true); }
}
