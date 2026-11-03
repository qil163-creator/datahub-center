package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillPlanCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 采集计划分类")
@RestController
@RequestMapping("/dataforge/fill-plan-category")
@Validated
public class FillPlanCategoryController {

    @Resource
    private FillPlanCategoryService categoryService;

    /**
     * 获得采集计划分类列表，前端构建目录树。
     *
     * @return 分类列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得采集计划分类列表，前端构建目录树")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:query')")
    public CommonResult<List<FillPlanCategoryRespVO>> getCategoryList() {
        return success(categoryService.getCategoryList());
    }

    /**
     * 获得采集计划分类。
     *
     * @param id 记录编号
     * @return 填报计划分类响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得采集计划分类")
    @Parameter(name = "id", description = "分类编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:query')")
    public CommonResult<FillPlanCategoryRespVO> getCategory(@RequestParam("id") @Positive Long id) {
        return success(categoryService.getCategory(id));
    }

    /**
     * 创建采集计划分类。
     *
     * @param reqVO 请求参数
     * @return 填报计划分类
     */
    @PostMapping("/create")
    @Operation(summary = "创建采集计划分类")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:create')")
    public CommonResult<Long> createCategory(@Valid @RequestBody FillPlanCategorySaveReqVO reqVO) {
        return success(categoryService.createCategory(reqVO));
    }

    /**
     * 更新采集计划分类。
     *
     * @param reqVO 请求参数
     * @return 填报计划分类
     */
    @PutMapping("/update")
    @Operation(summary = "更新采集计划分类")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:update')")
    public CommonResult<Boolean> updateCategory(@Valid @RequestBody FillPlanCategorySaveReqVO reqVO) {
        categoryService.updateCategory(reqVO);
        return success(true);
    }

    /**
     * 删除采集计划分类，有子分类或计划引用时拒绝。
     *
     * @param id 记录编号
     * @return 填报计划分类
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除采集计划分类，有子分类或计划引用时拒绝")
    @Parameter(name = "id", description = "分类编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:delete')")
    public CommonResult<Boolean> deleteCategory(@RequestParam("id") @Positive Long id) {
        categoryService.deleteCategory(id);
        return success(true);
    }

}
