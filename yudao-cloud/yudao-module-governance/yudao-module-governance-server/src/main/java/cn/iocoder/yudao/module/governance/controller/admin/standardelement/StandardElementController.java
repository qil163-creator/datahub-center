package cn.iocoder.yudao.module.governance.controller.admin.standardelement;

import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementEnumCheckRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImpactRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImportExcelVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementMappedColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementModelFieldRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementStatisticsRespVO;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaRespVO;
import io.swagger.v3.oas.annotations.Parameters;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.*;
import jakarta.servlet.http.*;

import java.util.*;
import java.io.IOException;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.*;

import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardElementService;

@Tag(name = "管理后台 - 数据标准--标准数据元")
@RestController
@RequestMapping("/governance/standard-element")
@Validated
public class StandardElementController {

    @Resource
    private StandardCatalogService catalogService;

    /**
     * 查询共享标准分类。
     */
    @GetMapping("/category-list")
    @Operation(summary = "查询共享标准分类")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<List<CategoryDatametaRespVO>> categoryList() {
        return success(catalogService.categoryList());
    }

    /**
     * 切换标准启用状态。
     */
    @PutMapping("/update-status")
    @Operation(summary = "切换标准启用状态")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> updateStatus(@RequestParam Integer id, @RequestParam Short status,
            @RequestParam Long revision) {
        standardElementService.updateStatus(id, status, revision);
        return success(true);
    }


    @Resource
    private StandardElementService standardElementService;

    /**
     * 创建数据标准--标准数据元
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @Operation(summary = "创建数据标准--标准数据元")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:create')")
    public CommonResult<Integer> createStandardElement(@Valid @RequestBody StandardElementSaveReqVO createReqVO) {
        return success(standardElementService.createStandardElement(createReqVO));
    }

    /**
     * 更新数据标准--标准数据元
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @Operation(summary = "更新数据标准--标准数据元")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> updateStandardElement(@Valid @RequestBody StandardElementSaveReqVO updateReqVO) {
        standardElementService.updateStandardElement(updateReqVO);
        return success(true);
    }

    /**
     * 绑定数据标准--标准数据元的标准码表。
     */
    @PutMapping("/bind-code-set")
    @Operation(summary = "绑定数据标准--标准数据元的标准码表")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @Parameter(name = "codeSetId", description = "标准码表编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> bindStandardElementCodeSet(@RequestParam("id") Integer id,
            @RequestParam("codeSetId") Integer codeSetId) {
        standardElementService.bindCodeSet(id, codeSetId);
        return success(true);
    }

    /**
     * 发布标准数据元（草稿 → 已发布）
     *
     *  @param id 数据元编号
     */
    @PutMapping("/publish")
    @Operation(summary = "发布数据标准--标准数据元（草稿 → 已发布）")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> publishStandardElement(@RequestParam("id") Integer id) {
        standardElementService.publishStandardElement(id);
        return success(true);
    }

    /**
     * 获得标准数据元的设计态落标（建模模块绑定该标准的模型字段）。
     */
    @GetMapping("/model-fields")
    @Operation(summary = "获得标准数据元的设计态落标（建模模块绑定该标准的模型字段）")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query') and @ss.hasPermission('modeling:model:query')")
    public CommonResult<List<StandardElementModelFieldRespVO>> getStandardElementModelFields(
            @RequestParam("id") Integer id) {
        return success(standardElementService.getModelFields(id));
    }

    /**
     * 生成落标字段的码值内容稽核 SQL（数据元需绑定码表）。
     */
    @GetMapping("/enum-check-sql")
    @Operation(summary = "生成落标字段的码值内容稽核 SQL（数据元需绑定码表）")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<List<StandardElementEnumCheckRespVO>> getStandardElementEnumCheckSqls(
            @RequestParam("id") Integer id) {
        return success(standardElementService.getEnumCheckSqls(id));
    }

    /**
     * 标准数据元影响分析（直接落标表）。
     */
    @GetMapping("/impact")
    @Operation(summary = "标准数据元影响分析（直接落标表）")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<StandardElementImpactRespVO> getStandardElementImpact(
            @RequestParam("id") Integer id) {
        return success(standardElementService.getImpactAnalysis(id));
    }

    /**
     * 撤销落标映射（同时解除元数据字段与该标准的绑定）。
     */
    @PutMapping("/revoke-mapping")
    @Operation(summary = "撤销落标映射（同时解除元数据字段与该标准的绑定）")
    @Parameter(name = "mappingId", description = "落标映射编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> revokeStandardElementMapping(@RequestParam("mappingId") Long mappingId) {
        standardElementService.revokeMapping(mappingId);
        return success(true);
    }

    /**
     * 废止标准数据元（已发布 → 已废止）
     *
     *  @param id 数据元编号
     */
    @PutMapping("/deprecate")
    @Operation(summary = "废止数据标准--标准数据元（已发布 → 已废止）")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Boolean> deprecateStandardElement(@RequestParam("id") Integer id) {
        standardElementService.deprecateStandardElement(id);
        return success(true);
    }

    /**
     * 获得标准数据元的落标字段列表。
     */
    @GetMapping("/mapped-columns")
    @Operation(summary = "获得标准数据元的落标字段列表")
    @Parameter(name = "id", description = "数据元编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<List<StandardElementMappedColumnRespVO>> getStandardElementMappedColumns(
            @RequestParam("id") Integer id) {
        return success(standardElementService.getMappedColumns(id));
    }

    /**
     * 获得标准数据元统计概览。
     */
    @GetMapping("/statistics")
    @Operation(summary = "获得标准数据元统计概览")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<StandardElementStatisticsRespVO> getStandardElementStatistics() {
        return success(standardElementService.getStatistics());
    }

    /**
     * 全局同步建模字段的 MODEL_FIELD 落标事实：
     *  补齐缺失事实，字段删除或改绑其它标准的活动事实置为 STALE。
     *
     *  @return 变更的事实数量
     */
    @PutMapping("/sync-model-mappings")
    @Operation(summary = "全局同步建模字段的落标事实（补齐缺失、失效置 STALE）")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:update')")
    public CommonResult<Integer> syncModelFieldMappings() {
        return success(standardElementService.syncModelFieldMappings());
    }

    /**
     * 获得标准数据元导入模板。
     */
    @GetMapping("/get-import-template")
    @Operation(summary = "获得标准数据元导入模板")
    public void importTemplate(HttpServletResponse response) throws IOException {
        List<StandardElementImportExcelVO> list = List.of(
                StandardElementImportExcelVO.builder()
                        .name("姓名").engName("person_name").columnType("VARCHAR")
                        .categoryName("人员信息").dataLength(64).nullableFlag(0)
                        .description("自然人姓名").build(),
                StandardElementImportExcelVO.builder()
                        .name("年龄").engName("person_age").columnType("INTEGER")
                        .categoryName("人员信息").dataPrecision(3).dataScale(0)
                        .valueRange("[0,150]").measureUnit("岁").nullableFlag(1).build());
        ExcelUtils.write(response, "标准数据元导入模板.xls", "数据元列表",
                StandardElementImportExcelVO.class, list);
    }

    /**
     * 导入标准数据元。
     */
    @PostMapping("/import")
    @Operation(summary = "导入标准数据元")
    @Parameters({
            @Parameter(name = "file", description = "Excel 文件", required = true),
            @Parameter(name = "updateSupport", description = "是否支持更新（按英文名称匹配），默认为 false", example = "true")
    })
    @PreAuthorize("@ss.hasPermission('governance:standard-element:import')")
    @ApiAccessLog(operateType = IMPORT)
    public CommonResult<StandardElementImportRespVO> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "updateSupport", required = false, defaultValue = "false") Boolean updateSupport)
            throws Exception {
        List<StandardElementImportExcelVO> list = ExcelUtils.read(file, StandardElementImportExcelVO.class);
        return success(standardElementService.importStandardElementList(list, updateSupport));
    }

    /**
     * 删除数据标准--标准数据元
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据标准--标准数据元")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-element:delete')")
    public CommonResult<Boolean> deleteStandardElement(@RequestParam("id") Integer id) {
        standardElementService.deleteStandardElement(id);
        return success(true);
    }

    /**
     * 批量删除数据标准--标准数据元。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @Operation(summary = "批量删除数据标准--标准数据元")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:delete')")
    public CommonResult<Boolean> deleteStandardElementList(@RequestParam("ids") List<Integer> ids) {
        standardElementService.deleteStandardElementListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据标准--标准数据元
     *
     *  @param id 编号
     *  @return 数据标准--标准数据元
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据标准--标准数据元")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<StandardElementRespVO> getStandardElement(@RequestParam("id") Integer id) {
        StandardElementDO standardElement = standardElementService.getStandardElement(id);
        var result = BeanUtils.toBean(standardElement, StandardElementRespVO.class);
        if (result != null) {
            catalogService.fillElements(List.of(result));
        }
        return success(result);
    }

    /**
     * 获得数据标准--标准数据元分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据标准--标准数据元分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据标准--标准数据元分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:query')")
    public CommonResult<PageResult<StandardElementRespVO>> getStandardElementPage(
            @Valid StandardElementPageReqVO pageReqVO) {
        PageResult<StandardElementDO> pageResult = standardElementService.getStandardElementPage(pageReqVO);
        var result = BeanUtils.toBean(pageResult, StandardElementRespVO.class);
        catalogService.fillElements(result.getList());
        return success(result);
    }

    /**
     * 导出数据标准--标准数据元 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据标准--标准数据元 Excel")
    @PreAuthorize("@ss.hasPermission('governance:standard-element:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportStandardElementExcel(@Valid StandardElementPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<StandardElementDO> list = standardElementService.getStandardElementPage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "数据标准--标准数据元.xls", "数据", StandardElementRespVO.class,
                BeanUtils.toBean(list, StandardElementRespVO.class));
    }

}
