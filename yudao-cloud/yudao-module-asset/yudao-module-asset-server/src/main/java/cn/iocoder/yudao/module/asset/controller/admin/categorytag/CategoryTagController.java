package cn.iocoder.yudao.module.asset.controller.admin.categorytag;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagListReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagSaveReqVO;
import cn.iocoder.yudao.module.asset.service.categorytag.CategoryTagService;
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

@Tag(name = "管理后台 - 标签类目")
@RestController
@RequestMapping("/asset/category-tag")
@Validated
public class CategoryTagController {

    @Resource
    private CategoryTagService categoryTagService;

    /**
     * 创建标签类目。
     */
    @PostMapping("/create")
    @Operation(summary = "创建标签类目")
    @PreAuthorize("@ss.hasPermission('asset:category-tag:create')")
    public CommonResult<Long> createCategoryTag(@Valid @RequestBody CategoryTagSaveReqVO reqVO) {
        return success(categoryTagService.createCategoryTag(reqVO));
    }

    /**
     * 更新标签类目。
     */
    @PutMapping("/update")
    @Operation(summary = "更新标签类目")
    @PreAuthorize("@ss.hasPermission('asset:category-tag:update')")
    public CommonResult<Boolean> updateCategoryTag(@Valid @RequestBody CategoryTagSaveReqVO reqVO) {
        categoryTagService.updateCategoryTag(reqVO);
        return success(true);
    }

    /**
     * 删除标签类目。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除标签类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:category-tag:delete')")
    public CommonResult<Boolean> deleteCategoryTag(@RequestParam("id") Long id) {
        categoryTagService.deleteCategoryTag(id);
        return success(true);
    }

    /**
     * 获得标签类目。
     */
    @GetMapping("/get")
    @Operation(summary = "获得标签类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:category-tag:query')")
    public CommonResult<CategoryTagRespVO> getCategoryTag(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(categoryTagService.getCategoryTag(id), CategoryTagRespVO.class));
    }

    /**
     * 获得标签类目列表（前端组装树）。
     */
    @GetMapping("/list")
    @Operation(summary = "获得标签类目列表（前端组装树）")
    @PreAuthorize("@ss.hasAnyPermissions('asset:category-tag:query', 'asset:tag:query', 'asset:tag:create', 'asset:tag:update')")
    public CommonResult<List<CategoryTagRespVO>> getCategoryTagList(@Valid CategoryTagListReqVO reqVO) {
        return success(categoryTagService.getCategoryTagList(reqVO));
    }

}
