package cn.iocoder.yudao.module.modeling.controller.admin.planning;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.*;
import cn.iocoder.yudao.module.modeling.service.planning.BizCategoryService;
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

@Tag(name = "管理后台 - 业务分类")
@RestController
@RequestMapping("/modeling/biz-category")
@Validated
public class BizCategoryController {

    @Resource
    private BizCategoryService bizCategoryService;

    /**
     * 创建业务分类。
     *
     * @param createReqVO 创建参数
     * @return 业务分类
     */
    @PostMapping("/create")
    @Operation(summary = "创建业务分类")
    @PreAuthorize("@ss.hasPermission('modeling:biz-category:create')")
    public CommonResult<Long> createBizCategory(@Valid @RequestBody BizCategorySaveReqVO createReqVO) {
        return success(bizCategoryService.createBizCategory(createReqVO));
    }

    /**
     * 更新业务分类。
     *
     * @param updateReqVO 更新参数
     * @return 业务分类
     */
    @PutMapping("/update")
    @Operation(summary = "更新业务分类")
    @PreAuthorize("@ss.hasPermission('modeling:biz-category:update')")
    public CommonResult<Boolean> updateBizCategory(@Valid @RequestBody BizCategorySaveReqVO updateReqVO) {
        bizCategoryService.updateBizCategory(updateReqVO);
        return success(true);
    }

    /**
     * 更新业务分类状态。
     *
     * @param reqVO 请求参数
     * @return 业务分类
     */
    @PutMapping("/update-status")
    @Operation(summary = "更新业务分类状态")
    @PreAuthorize("@ss.hasPermission('modeling:biz-category:update')")
    public CommonResult<Boolean> updateBizCategoryStatus(
            @Valid @RequestBody BizCategoryUpdateStatusReqVO reqVO) {
        bizCategoryService.updateBizCategoryStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    /**
     * 删除业务分类。
     *
     * @param id 记录编号
     * @return 业务分类
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除业务分类")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:biz-category:delete')")
    public CommonResult<Boolean> deleteBizCategory(@RequestParam("id") Long id) {
        bizCategoryService.deleteBizCategory(id);
        return success(true);
    }

    /**
     * 获得业务分类。
     *
     * @param id 记录编号
     * @return 业务分类响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得业务分类")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('modeling:biz-category:query')")
    public CommonResult<BizCategoryRespVO> getBizCategory(@RequestParam("id") Long id) {
        return success(bizCategoryService.getBizCategory(id));
    }

    /**
     * 获得业务分类列表（树形数据平铺返回）。
     *
     * @param listReqVO 列表请求参数
     * @return 业务分类列表（树形数据平铺返回）结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "获得业务分类列表（树形数据平铺返回）")
    @PreAuthorize("@ss.hasPermission('modeling:biz-category:query')")
    public CommonResult<List<BizCategoryRespVO>> getBizCategoryList(@Valid BizCategoryListReqVO listReqVO) {
        return success(bizCategoryService.getBizCategoryList(listReqVO));
    }

}
