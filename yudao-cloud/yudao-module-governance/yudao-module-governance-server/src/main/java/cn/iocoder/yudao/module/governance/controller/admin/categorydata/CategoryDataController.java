package cn.iocoder.yudao.module.governance.controller.admin.categorydata;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import cn.iocoder.yudao.module.governance.service.categorydata.CategoryDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据分类 类目")
@RestController
@RequestMapping("/governance/category-data")
@Validated
public class CategoryDataController {

    @Resource
    private CategoryDataService categoryDataService;

    /**
     * 创建数据分类 类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-数据分类 类目", bizId = "#result")
    @Operation(summary = "创建数据分类类目")
    @PreAuthorize("@ss.hasPermission('governance:category-data:create')")
    public CommonResult<Long> createCategoryData(@Valid @RequestBody CategoryDataSaveReqVO createReqVO) {
        return success(categoryDataService.createCategoryData(createReqVO));
    }

    /**
     * 更新数据分类 类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据分类 类目", bizId = "#updateReqVO.id")
    @Operation(summary = "更新数据分类类目")
    @PreAuthorize("@ss.hasPermission('governance:category-data:update')")
    public CommonResult<Boolean> updateCategoryData(@Valid @RequestBody CategoryDataSaveReqVO updateReqVO) {
        categoryDataService.updateCategoryData(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据分类 类目管理
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据分类 类目", bizId = "#id")
    @Operation(summary = "删除数据分类类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-data:delete')")
    public CommonResult<Boolean> deleteCategoryData(@RequestParam("id") @Positive Long id,
            @RequestParam("revision") @Positive Long revision) {
        categoryDataService.deleteCategoryData(id, revision);
        return success(true);
    }


    /**
     * 获得数据分类 类目管理
     *
     *  @param id 编号
     *  @return 数据分类 类目管理
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据分类类目")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:category-data:query')")
    public CommonResult<CategoryDataRespVO> getCategoryData(@RequestParam("id") Long id) {
        CategoryDataDO categoryData = categoryDataService.getCategoryData(id);
        return success(BeanUtils.toBean(categoryData, CategoryDataRespVO.class));
    }

    /**
     * 获得数据分类 类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 数据分类 类目管理列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得数据分类类目列表")
    @PreAuthorize("@ss.hasPermission('governance:category-data:query')")
    public CommonResult<List<CategoryDataRespVO>> getCategoryDataList(@Valid CategoryDataListReqVO listReqVO) {
        List<CategoryDataDO> list = categoryDataService.getCategoryDataList(listReqVO);
        return success(BeanUtils.toBean(list, CategoryDataRespVO.class));
    }

    /**
     * 导出数据分类类目 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据分类类目 Excel")
    @PreAuthorize("@ss.hasPermission('governance:category-data:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportCategoryDataExcel(@Valid CategoryDataListReqVO listReqVO,
            HttpServletResponse response) throws IOException {
        List<CategoryDataDO> list = categoryDataService.getCategoryDataList(listReqVO);
        // 导出 Excel
        ExcelUtils.write(response, "数据分类 类目管理.xls", "数据", CategoryDataRespVO.class,
                BeanUtils.toBean(list, CategoryDataRespVO.class));
    }

}
