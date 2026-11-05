package cn.iocoder.yudao.module.asset.controller.admin.assettag;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.*;
import jakarta.validation.constraints.*;
import cn.iocoder.yudao.module.asset.service.assettag.AssetTagService;
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

@Tag(name = "管理后台 - 资产标签")
@RestController
@RequestMapping("/asset/tag")
@Validated
public class AssetTagController {

    @Resource
    private AssetTagService tagService;

    /**
     * 创建资产标签。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "创建资产标签", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建资产标签", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建资产标签")
    @PreAuthorize("@ss.hasPermission('asset:tag:create')")
    public CommonResult<Long> createTag(@Valid @RequestBody AssetTagSaveReqVO reqVO) {
        return success(tagService.createTag(reqVO));
    }

    /**
     * 更新资产标签。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "更新资产标签", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "更新资产标签", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新资产标签")
    @PreAuthorize("@ss.hasPermission('asset:tag:update')")
    public CommonResult<Boolean> updateTag(@Valid @RequestBody AssetTagSaveReqVO reqVO) {
        tagService.updateTag(reqVO);
        return success(true);
    }

    /**
     * 启用/停用资产标签。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "启用/停用资产标签", bizNo = "{{(#id) ?: 0}}",
            success = "启用/停用资产标签", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "启用/停用资产标签")
    @PreAuthorize("@ss.hasPermission('asset:tag:update')")
    public CommonResult<Boolean> updateTagStatus(@RequestParam("id") Long id,
            @RequestParam("status") @Min(0) @Max(1) Integer status,
            @RequestParam("revision") @Min(0) Integer revision) {
        tagService.updateTagStatus(id, status, revision);
        return success(true);
    }

    /**
     * 删除资产标签。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "删除资产标签", bizNo = "{{(#id) ?: 0}}",
            success = "删除资产标签", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除资产标签")
    @PreAuthorize("@ss.hasPermission('asset:tag:delete')")
    public CommonResult<Boolean> deleteTag(@RequestParam("id") Long id,
            @RequestParam("revision") @Min(0) Integer revision) {
        tagService.deleteTag(id, revision);
        return success(true);
    }

    /**
     * 按修订号原子批量删除禁用且无引用的标签。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "按修订号原子批量删除禁用且无引用的标签", bizNo = "{{(#req.items.![id]) ?: 0}}",
            success = "按修订号原子批量删除禁用且无引用的标签", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/delete-list")
    @Operation(summary = "按修订号原子批量删除禁用且无引用的标签")
    @PreAuthorize("@ss.hasPermission('asset:tag:delete')")
    public CommonResult<Boolean> deleteList(@Valid @RequestBody AssetTagDeleteListReqVO req) {
        tagService.deleteTags(req.getItems());
        return success(true);
    }

    /**
     * 分页读取维护范围内的关联资产。
     */
    @GetMapping("/linked-assets")
    @Operation(summary = "分页读取维护范围内的关联资产")
    @PreAuthorize("@ss.hasPermission('asset:tag:query')")
    public CommonResult<PageResult<AssetTagLinkedRespVO>> linked(@Valid AssetTagLinkedPageReqVO req) {
        return success(tagService.linkedAssets(req));
    }

    /**
     * 维护范围内按修订号移除单条资产标签关联。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "维护范围内按修订号移除单条资产标签关联", bizNo = "{{(#relationId) ?: 0}}",
            success = "维护范围内按修订号移除单条资产标签关联", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/unbind")
    @Operation(summary = "维护范围内按修订号移除单条资产标签关联")
    @PreAuthorize("@ss.hasPermission('asset:tag:update')")
    public CommonResult<Boolean> unbind(@RequestParam @Positive Long relationId,
            @RequestParam @Min(0) Integer revision) {
        tagService.unbind(relationId, revision);
        return success(true);
    }

    /**
     * 获得资产标签详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得资产标签详情")
    @Parameter(name = "id", description = "标签编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:tag:query')")
    public CommonResult<AssetTagRespVO> getTag(@RequestParam("id") Long id) {
        return success(tagService.getTag(id));
    }

    /**
     * 获得资产标签分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得资产标签分页")
    @PreAuthorize("@ss.hasPermission('asset:tag:query')")
    public CommonResult<PageResult<AssetTagRespVO>> getTagPage(@Valid AssetTagPageReqVO reqVO) {
        return success(tagService.getTagPage(reqVO));
    }

    /**
     * 获得资产标签列表（下拉选择用）。
     */
    @GetMapping("/list")
    @Operation(summary = "获得资产标签列表（下拉选择用）")
    @PreAuthorize("@ss.hasAnyPermissions('asset:tag:query', 'asset:data-asset:query', 'asset:data-asset:tag')")
    public CommonResult<List<AssetTagRespVO>> getTagList() {
        return success(tagService.getTagList());
    }

    /**
     * 获得资产已绑定标签。
     */
    @GetMapping("/asset-list")
    @Operation(summary = "获得资产已绑定标签")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<List<AssetTagRespVO>> getAssetTagList(@RequestParam("assetId") Long assetId) {
        return success(tagService.getTagsByAssetId(assetId));
    }

    /**
     * 全量覆盖资产标签。
     */
    @LogRecord(type = "数据资产-资产标签", subType = "全量覆盖资产标签", bizNo = "{{(#reqVO.assetId) ?: 0}}",
            success = "全量覆盖资产标签", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/bind")
    @Operation(summary = "全量覆盖资产标签")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:tag')")
    public CommonResult<Boolean> bindTags(@Valid @RequestBody AssetTagBindReqVO reqVO) {
        tagService.bindTags(reqVO);
        return success(true);
    }

}
