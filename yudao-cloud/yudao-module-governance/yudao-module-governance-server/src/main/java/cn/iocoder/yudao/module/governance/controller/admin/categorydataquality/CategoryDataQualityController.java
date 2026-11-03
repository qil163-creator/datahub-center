package cn.iocoder.yudao.module.governance.controller.admin.categorydataquality;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualityListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualityRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualitySaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydataquality.CategoryDataQualityDO;
import cn.iocoder.yudao.module.governance.service.categorydataquality.CategoryDataQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据质量类目")
@RestController
@RequestMapping("/governance/category-data-quality")
@Validated
public class CategoryDataQualityController {

    @Resource
    private CategoryDataQualityService categoryDataQualityService;

    /**
     * 创建数据质量类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @Operation(summary = "创建数据质量类目")
    @PreAuthorize("@ss.hasPermission('governance:category-data-quality:create')")
    public CommonResult<Long> createCategoryDataQuality(@Valid @RequestBody CategoryDataQualitySaveReqVO createReqVO) {
        return success(categoryDataQualityService.createCategoryDataQuality(createReqVO));
    }

    /**
     * 更新数据质量类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @Operation(summary = "更新数据质量类目")
    @PreAuthorize("@ss.hasPermission('governance:category-data-quality:update')")
    public CommonResult<Boolean> updateCategoryDataQuality(
            @Valid @RequestBody CategoryDataQualitySaveReqVO updateReqVO) {
        categoryDataQualityService.updateCategoryDataQuality(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据质量类目管理
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据质量类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-data-quality:delete')")
    public CommonResult<Boolean> deleteCategoryDataQuality(@RequestParam("id") Long id) {
        categoryDataQualityService.deleteCategoryDataQuality(id);
        return success(true);
    }


    /**
     * 获得数据质量类目管理
     *
     *  @param id 编号
     *  @return 数据质量类目管理
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据质量类目")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:category-data-quality:query')")
    public CommonResult<CategoryDataQualityRespVO> getCategoryDataQuality(@RequestParam("id") Long id) {
        CategoryDataQualityDO categoryDataQuality = categoryDataQualityService.getCategoryDataQuality(id);
        return success(BeanUtils.toBean(categoryDataQuality, CategoryDataQualityRespVO.class));
    }

    /**
     * 获得数据质量类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 数据质量类目管理列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得数据质量类目列表")
    @PreAuthorize("@ss.hasPermission('governance:category-data-quality:query')")
    public CommonResult<List<CategoryDataQualityRespVO>> getCategoryDataQualityList(
            @Valid CategoryDataQualityListReqVO listReqVO) {
        List<CategoryDataQualityDO> list = categoryDataQualityService.getCategoryDataQualityList(listReqVO);
        return success(BeanUtils.toBean(list, CategoryDataQualityRespVO.class));
    }

    /**
     * 导出数据质量类目 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据质量类目 Excel")
    @PreAuthorize("@ss.hasPermission('governance:category-data-quality:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportCategoryDataQualityExcel(@Valid CategoryDataQualityListReqVO listReqVO,
            HttpServletResponse response) throws IOException {
        List<CategoryDataQualityDO> list = categoryDataQualityService.getCategoryDataQualityList(listReqVO);
        // 导出 Excel
        ExcelUtils.write(response, "数据质量类目管理.xls", "数据", CategoryDataQualityRespVO.class,
                BeanUtils.toBean(list, CategoryDataQualityRespVO.class));
    }

}
