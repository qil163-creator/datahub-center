package cn.iocoder.yudao.module.asset.controller.admin.sensitive;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.*;
import cn.iocoder.yudao.module.asset.service.sensitive.AssetSensitiveListService;
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

@Tag(name = "管理后台 - 资产敏感清单")
@RestController
@RequestMapping("/asset/sensitive-list")
@Validated
public class AssetSensitiveListController {

    @Resource
    private AssetSensitiveListService service;

    /**
     * 在资产维护范围内分页查询敏感清单。
     */
    @GetMapping("/page")
    @Operation(summary = "在资产维护范围内分页查询敏感清单")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:query')")
    public CommonResult<PageResult<AssetSensitiveRespVO>> page(@Valid AssetSensitivePageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 查询敏感清单详情。
     */
    @GetMapping("/get")
    @Operation(summary = "查询敏感清单详情")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:query')")
    public CommonResult<AssetSensitiveRespVO> get(@RequestParam @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 创建敏感清单记录，默认禁用。
     */
    @LogRecord(type = "数据资产-资产敏感清单", subType = "创建敏感清单记录，默认禁用", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了敏感清单项【编号{{#_ret.data}}】，资产【{{#req.assetId}}】，字段【{{#req.columnId}}】，分类【{{#req.categoryId}}】，初始状态为禁用", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建敏感清单记录，默认禁用")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:create')")
    public CommonResult<Long> create(@Valid @RequestBody AssetSensitiveSaveReqVO req) {
        return success(service.create(req));
    }

    /**
     * 修改禁用状态的敏感清单记录。
     */
    @LogRecord(type = "数据资产-资产敏感清单", subType = "修改禁用状态的敏感清单记录", bizNo = "{{(#req.id) ?: 0}}",
            success = "修改了敏感清单项【编号{{#req.id}}】，资产【{{#req.assetId}}】，字段【{{#req.columnId}}】，分类【{{#req.categoryId}}】，校验修订【{{#req.revision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "修改禁用状态的敏感清单记录")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AssetSensitiveSaveReqVO req) {
        service.update(req);
        return success(true);
    }

    /**
     * 按修订号启用或禁用敏感清单记录。
     */
    @LogRecord(type = "数据资产-资产敏感清单", subType = "按修订号启用或禁用敏感清单记录", bizNo = "{{(#req.id) ?: 0}}",
            success = "将敏感清单项【编号{{#req.id}}】设为【{{#req.status == 0 ? '启用' : '禁用'}}】，校验修订【{{#req.revision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/change-status")
    @Operation(summary = "按修订号启用或禁用敏感清单记录")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody AssetSensitiveStatusReqVO req) {
        service.changeStatus(req);
        return success(true);
    }

    /**
     * 删除禁用状态的敏感清单记录。
     */
    @LogRecord(type = "数据资产-资产敏感清单", subType = "删除禁用状态的敏感清单记录", bizNo = "{{(#id) ?: 0}}",
            success = "删除了已禁用敏感清单项【编号{{#id}}】，校验修订【{{#revision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除禁用状态的敏感清单记录")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id, @RequestParam @Min(0) Integer revision) {
        var item = new AssetSensitiveDeleteReqVO.Item();
        item.setId(id);
        item.setRevision(revision);
        service.delete(List.of(item), true);
        return success(true);
    }

    /**
     * 原子校验所选范围并批量删除禁用项，返回跳过的启用项。
     */
    @LogRecord(type = "数据资产-资产敏感清单", subType = "原子校验所选范围并批量删除禁用项，返回跳过的启用项", bizNo = "{{(#_ret.data.deletedIds) ?: 0}}",
            success = "批量删除了敏感清单项，实际删除编号集合【{{#_ret.data.deletedIds}}】，删除【{{#_ret.data.deletedIds.size()}}】项，启用项不记作删除成功", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/delete-list")
    @Operation(summary = "原子校验所选范围并批量删除禁用项，返回跳过的启用项")
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:delete')")
    public CommonResult<AssetSensitiveOptionVO.Deleted> deleteList(@Valid @RequestBody AssetSensitiveDeleteReqVO req) {
        return success(service.delete(req.getItems(), false));
    }

    /**
     * 查询治理侧分类目录及叶分类。
     */
    @GetMapping("/categories")
    @Operation(summary = "查询治理侧分类目录及叶分类")
    @PreAuthorize("@ss.hasAnyPermissions('asset:sensitive-list:query','asset:sensitive-list:create','asset:sensitive-list:update')")
    public CommonResult<List<AssetSensitiveOptionVO.Category>> categories() {
        return success(service.categories());
    }

    /**
     * 查询维护范围内可绑定的真实资产。
     */
    @GetMapping("/asset-options")
    @Operation(summary = "查询维护范围内可绑定的真实资产")
    @PreAuthorize("@ss.hasAnyPermissions('asset:sensitive-list:query','asset:sensitive-list:create','asset:sensitive-list:update')")
    public CommonResult<PageResult<AssetSensitiveOptionVO.Asset>> assets(@Valid AssetSensitivePageReqVO req) {
        return success(service.assetOptions(req));
    }

    /**
     * 查询可维护资产的当前字段，支持迁入字段。
     */
    @GetMapping("/column-options")
    @Operation(summary = "查询可维护资产的当前字段，支持迁入字段")
    @PreAuthorize("@ss.hasAnyPermissions('asset:sensitive-list:query','asset:sensitive-list:create','asset:sensitive-list:update')")
    public CommonResult<List<AssetSensitiveOptionVO.Column>> columns(@RequestParam @Positive Long assetId) {
        return success(service.columnOptions(assetId));
    }
}
