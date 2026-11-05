package cn.iocoder.yudao.module.governance.controller.admin.category;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.service.category.SafetyCategoryService;
import cn.iocoder.yudao.module.governance.service.category.SafetyCategoryDisplayService;
import cn.iocoder.yudao.module.governance.service.category.SafetyCategoryMaskService;
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

@Tag(name = "管理后台 - 数据分类信息")
@RestController
@RequestMapping("/governance/safety-category")
@Validated
public class SafetyCategoryController {

    @Resource
    private SafetyCategoryService categoryService;

    @Resource
    private SafetyCategoryDisplayService display;

    @Resource
    private SafetyCategoryMaskService masks;

    /**
     * 创建数据安全--数据分类信息
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-数据分类信息", subType = "创建数据分类信息",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建数据分类信息",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建数据分类信息")
    @PreAuthorize("@ss.hasPermission('governance:category:create')")
    public CommonResult<Long> createCategory(@Valid @RequestBody SafetyCategorySaveReqVO createReqVO) {
        return success(categoryService.createCategory(createReqVO));
    }

    /**
     * 更新数据安全--数据分类信息
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据分类信息", subType = "更新数据分类信息",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新数据分类信息",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据分类信息")
    @PreAuthorize("@ss.hasPermission('governance:category:update')")
    public CommonResult<Boolean> updateCategory(@Valid @RequestBody SafetyCategorySaveReqVO updateReqVO) {
        categoryService.updateCategory(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据安全--数据分类信息
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据分类信息", subType = "删除数据分类信息",
            bizNo = "{{(#id) ?: 0}}", success = "删除数据分类信息",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除数据分类信息")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category:delete')")
    public CommonResult<Boolean> deleteCategory(@RequestParam("id") @Positive Long id,
            @RequestParam("revision") @Positive Long revision) {
        categoryService.deleteCategory(id, revision);
        return success(true);
    }

    /**
     * 批量删除数据安全--数据分类信息
     *
     * @param request 批量删除请求，包含分类编号与预期修订号
     */
    @PostMapping("/delete-list")
    @LogRecord(type = "数据治理-数据分类信息", subType = "批量删除数据分类信息",
            bizNo = "{{(#request.items.![id]) ?: 0}}", success = "批量删除数据分类信息",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除数据分类信息")
    @PreAuthorize("@ss.hasPermission('governance:category:delete')")
    public CommonResult<Boolean> deleteCategoryList(@Valid @RequestBody SafetyCategoryBatchReqVO request) {
        categoryService.deleteCategoryList(request);
        return success(true);
    }

    /**
     * 获得数据安全--数据分类信息
     *
     *  @param id 编号
     *  @return 数据安全--数据分类信息
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据分类信息")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:category:query')")
    public CommonResult<SafetyCategoryRespVO> getCategory(@RequestParam("id") @Positive Long id) {
        SafetyCategoryDO category = categoryService.getCategory(id);
        return success(convert(List.of(category)).getFirst());
    }

    /**
     * 获得数据安全--数据分类信息分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据安全--数据分类信息分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据分类信息分页")
    @PreAuthorize("@ss.hasPermission('governance:category:query')")
    public CommonResult<PageResult<SafetyCategoryRespVO>> getCategoryPage(@Valid SafetyCategoryPageReqVO pageReqVO) {
        PageResult<SafetyCategoryDO> pageResult = categoryService.getCategoryPage(pageReqVO);
        return success(new PageResult<>(convert(pageResult.getList()), pageResult.getTotal()));
    }

    /**
     * 导出数据分类信息 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据分类信息 Excel")
    @PreAuthorize("@ss.hasPermission('governance:category:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportCategoryExcel(@Valid SafetyCategoryPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<SafetyCategoryDO> list = categoryService.getCategoryPage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "数据分类信息.xls", "数据", SafetyCategoryRespVO.class,
                convert(list));
    }

    /**
     * 获得分类维护所需类目、分级和优先级选项。
     */
    @GetMapping("/options")
    @Operation(summary = "获得分类维护所需类目、分级和优先级选项")
    @PreAuthorize("@ss.hasPermission('governance:category:query')")
    public CommonResult<SafetyCategoryOptionsRespVO> options() {
        return success(display.options());
    }

    /**
     * 修改分类启停状态。
     */
    @PutMapping("/change-status")
    @LogRecord(type = "数据治理-数据分类信息", subType = "修改分类启停状态",
            bizNo = "{{(#request.id) ?: 0}}", success = "修改分类启停状态",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改分类启停状态")
    @PreAuthorize("@ss.hasPermission('governance:category:update')")
    public CommonResult<Boolean> changeStatus(@Valid @RequestBody SafetyCategoryStatusReqVO request) {
        categoryService.changeStatus(request);
        return success(true);
    }

    /**
     * 批量指定数据分级。
     */
    @PutMapping("/batch-level")
    @LogRecord(type = "数据治理-数据分类信息", subType = "批量指定数据分级",
            bizNo = "{{(#request.items.![id]) ?: 0}}", success = "批量指定数据分级",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量指定数据分级")
    @PreAuthorize("@ss.hasPermission('governance:category:update')")
    public CommonResult<Boolean> assignLevel(@Valid @RequestBody SafetyCategoryBatchLevelReqVO request) {
        categoryService.assignLevel(request);
        return success(true);
    }

    /**
     * 读取分类展示脱敏配置。
     */
    @GetMapping("/mask")
    @Operation(summary = "读取分类展示脱敏配置")
    @PreAuthorize("@ss.hasPermission('governance:category:query')")
    public CommonResult<SafetyCategoryMaskRespVO> getMask(@RequestParam("id") @Positive Long id) {
        return success(masks.get(id));
    }

    /**
     * 保存分类展示脱敏配置及规则版本。
     */
    @PutMapping("/mask")
    @LogRecord(type = "数据治理-数据分类信息", subType = "保存分类展示脱敏配置及规则版本",
            bizNo = "{{(#request.id) ?: 0}}", success = "保存分类展示脱敏配置及规则版本",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存分类展示脱敏配置及规则版本")
    @PreAuthorize("@ss.hasPermission('governance:category:update')")
    public CommonResult<Boolean> saveMask(@Valid @RequestBody SafetyCategoryMaskReqVO request) {
        masks.save(request);
        return success(true);
    }

    /**
     * 将安全分类记录转换为接口展示响应。
     */
    private List<SafetyCategoryRespVO> convert(List<SafetyCategoryDO> rows) {
        var result = BeanUtils.toBean(rows, SafetyCategoryRespVO.class);
        display.fill(result);
        for (int i = 0; i < result.size(); i++) {
            display.sourceDisplay(result.get(i), rows.get(i));
        }
        return result;
    }

}
