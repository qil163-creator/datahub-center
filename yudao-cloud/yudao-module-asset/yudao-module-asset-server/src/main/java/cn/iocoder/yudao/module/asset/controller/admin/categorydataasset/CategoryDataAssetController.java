package cn.iocoder.yudao.module.asset.controller.admin.categorydataasset;

import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetListReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import cn.iocoder.yudao.module.asset.service.categorydataasset.CategoryDataAssetService;
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

@Tag(name = "管理后台 - 数据资产类目管理")
@RestController
@RequestMapping("/asset/category-data-asset")
@Validated
public class CategoryDataAssetController {

    @Resource
    private CategoryDataAssetService categoryDataAssetService;

    /**
     * 创建数据资产类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @LogRecord(type = "数据资产-数据资产类目管理", subType = "创建数据资产类目管理", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了数据资产类目【{{#createReqVO.categoryName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建数据资产类目管理")
    @PreAuthorize("@ss.hasPermission('asset:category-data-asset:create')")
    public CommonResult<Long> createCategoryDataAsset(@Valid @RequestBody CategoryDataAssetSaveReqVO createReqVO) {
        return success(categoryDataAssetService.createCategoryDataAsset(createReqVO));
    }

    /**
     * 更新数据资产类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @LogRecord(type = "数据资产-数据资产类目管理", subType = "更新数据资产类目管理", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了数据资产类目【{{#auditObjectName}}】，名称由【{{#auditObjectName}}】改为【{{#updateReqVO.categoryName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新数据资产类目管理")
    @PreAuthorize("@ss.hasPermission('asset:category-data-asset:update')")
    public CommonResult<Boolean> updateCategoryDataAsset(@Valid @RequestBody CategoryDataAssetSaveReqVO updateReqVO) {
        var auditBefore = categoryDataAssetService.getCategoryDataAsset(updateReqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getCategoryName() == null || auditBefore.getCategoryName().isBlank()
                        ? "编号" + updateReqVO.getId() : auditBefore.getCategoryName());

        categoryDataAssetService.updateCategoryDataAsset(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据资产类目管理
     *
     *  @param id 编号
     */
    @LogRecord(type = "数据资产-数据资产类目管理", subType = "删除数据资产类目管理", bizNo = "{{(#id) ?: 0}}",
            success = "删除了数据资产类目【{{#auditObjectName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据资产类目管理")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:category-data-asset:delete')")
    public CommonResult<Boolean> deleteCategoryDataAsset(@RequestParam("id") Long id) {
        var auditBefore = categoryDataAssetService.getCategoryDataAsset(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getCategoryName() == null || auditBefore.getCategoryName().isBlank()
                        ? "编号" + id : auditBefore.getCategoryName());

        categoryDataAssetService.deleteCategoryDataAsset(id);
        return success(true);
    }


    /**
     * 获得数据资产类目管理
     *
     *  @param id 编号
     *  @return 数据资产类目管理
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据资产类目管理")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('asset:category-data-asset:query')")
    public CommonResult<CategoryDataAssetRespVO> getCategoryDataAsset(@RequestParam("id") Long id) {
        CategoryDataAssetDO categoryDataAsset = categoryDataAssetService.getCategoryDataAsset(id);
        return success(BeanUtils.toBean(categoryDataAsset, CategoryDataAssetRespVO.class));
    }

    /**
     * 获得数据资产类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 数据资产类目管理列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得数据资产类目管理列表")
    @PreAuthorize("@ss.hasPermission('asset:category-data-asset:query')")
    public CommonResult<List<CategoryDataAssetRespVO>> getCategoryDataAssetList(
            @Valid CategoryDataAssetListReqVO listReqVO) {
        List<CategoryDataAssetDO> list = categoryDataAssetService.getCategoryDataAssetList(listReqVO);
        return success(BeanUtils.toBean(list, CategoryDataAssetRespVO.class));
    }

    /**
     * 导出数据资产类目管理 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据资产类目管理 Excel")
    @PreAuthorize("@ss.hasPermission('asset:category-data-asset:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportCategoryDataAssetExcel(@Valid CategoryDataAssetListReqVO listReqVO,
            HttpServletResponse response) throws IOException {
        List<CategoryDataAssetDO> list = categoryDataAssetService.getCategoryDataAssetList(listReqVO);
        // 导出 Excel
        ExcelUtils.write(response, "数据资产类目管理.xls", "数据", CategoryDataAssetRespVO.class,
                BeanUtils.toBean(list, CategoryDataAssetRespVO.class));
    }

}
