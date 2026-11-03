package cn.iocoder.yudao.module.governance.controller.admin.doc;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;

import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocSimpleRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardversion.vo.StandardObjectVersionRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.service.doc.StandardDocService;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaRespVO;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
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

@Tag(name = "管理后台 - 标准文件登记信息")
@RestController
@RequestMapping("/governance/standard-doc")
@Validated
public class StandardDocController {

    @Resource
    private StandardDocService docService;

    @Resource
    private StandardCatalogService catalogService;

    /**
     * 获得平台共享标准类目。
     */
    @GetMapping("/categories")
    @Operation(summary = "获得平台共享标准类目")
    @PreAuthorize("@ss.hasPermission('governance:doc:query')")
    public CommonResult<List<CategoryDatametaRespVO>> getDocCategories() {
        return success(catalogService.categoryList());
    }

    /**
     * 创建数据标准--标准文件登记
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @Operation(summary = "创建标准文件登记信息")
    @PreAuthorize("@ss.hasPermission('governance:doc:create')")
    public CommonResult<Long> createDoc(@Valid @RequestBody StandardDocSaveReqVO createReqVO) {
        return success(docService.createDoc(createReqVO));
    }

    /**
     * 按登记工作台要求校验类目、原文和标准编号后创建。
     */
    @PostMapping("/registration/create")
    @Operation(summary = "登记标准文件，类目、标准编号和原文必填")
    @PreAuthorize("@ss.hasPermission('governance:doc:create')")
    public CommonResult<Long> registerDoc(@Valid @RequestBody StandardDocSaveReqVO createReqVO) {
        return success(docService.registerDoc(createReqVO));
    }

    /**
     * 登记工作台提交完整元信息，允许清空版本号、废止日期。
     */
    @PutMapping("/registration/update")
    @Operation(summary = "更新标准文件登记工作台元信息")
    @PreAuthorize("@ss.hasPermission('governance:doc:update')")
    public CommonResult<Boolean> updateRegisteredDoc(@Valid @RequestBody StandardDocSaveReqVO updateReqVO) {
        docService.updateRegisteredDoc(updateReqVO);
        return success(true);
    }

    /**
     * 更新数据标准--标准文件登记
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @Operation(summary = "更新标准文件登记信息")
    @PreAuthorize("@ss.hasPermission('governance:doc:update')")
    public CommonResult<Boolean> updateDoc(@Valid @RequestBody StandardDocSaveReqVO updateReqVO) {
        docService.updateDoc(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据标准--标准文件登记
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除标准文件登记信息")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:doc:delete')")
    public CommonResult<Boolean> deleteDoc(@RequestParam("id") Long id) {
        docService.deleteDoc(id);
        return success(true);
    }

    /**
     * 批量删除标准文件登记信息。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @Operation(summary = "批量删除标准文件登记信息")
    @PreAuthorize("@ss.hasPermission('governance:doc:delete')")
    public CommonResult<Boolean> deleteDocList(@RequestParam("ids") List<Long> ids) {
        docService.deleteDocListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据标准--标准文件登记
     *
     *  @param id 编号
     *  @return 数据标准--标准文件登记
     */
    @GetMapping("/get")
    @Operation(summary = "获得标准文件登记信息")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:doc:query')")
    public CommonResult<StandardDocRespVO> getDoc(@RequestParam("id") Long id) {
        StandardDocDO doc = docService.getDoc(id);
        StandardDocRespVO result = BeanUtils.toBean(doc, StandardDocRespVO.class);
        docService.fillDisplayNames(List.of(result));
        return success(result);
    }

    /**
     * 获得标准原文的最新可访问 URL
     *
     *  @param id 标准文件编号
     *  @return 最新可访问 URL
     */
    @GetMapping("/preview-url")
    @Operation(summary = "获得标准原文的最新可访问 URL")
    @Parameter(name = "id", description = "标准文件编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:doc:query')")
    public CommonResult<String> getDocPreviewUrl(@RequestParam("id") Long id) {
        return success(docService.getDocPreviewUrl(id));
    }

    /**
     * 获得数据标准--标准文件登记分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据标准--标准文件登记分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得标准文件登记信息分页")
    @PreAuthorize("@ss.hasPermission('governance:doc:query')")
    public CommonResult<PageResult<StandardDocRespVO>> getDocPage(@Valid StandardDocPageReqVO pageReqVO) {
        PageResult<StandardDocDO> pageResult = docService.getDocPage(pageReqVO);
        PageResult<StandardDocRespVO> result = BeanUtils.toBean(pageResult, StandardDocRespVO.class);
        docService.fillDisplayNames(result.getList());
        return success(result);
    }

    /**
     * 获得替代标准候选精简列表
     *
     *  @param excludeId 排除的标准文件编号
     *  @return 标准文件候选列表
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得替代标准候选精简列表")
    @Parameter(name = "excludeId", description = "需要排除的标准文件编号")
    @PreAuthorize("@ss.hasPermission('governance:doc:query')")
    public CommonResult<List<StandardDocSimpleRespVO>> getDocSimpleList(
            @RequestParam(value = "excludeId", required = false) Long excludeId) {
        List<StandardDocDO> list = docService.getDocSimpleList(excludeId);
        return success(BeanUtils.toBean(list, StandardDocSimpleRespVO.class));
    }

    /**
     * 生成标准文件版本快照（版本号按文件递增）
     *
     *  @param id            标准文件编号
     *  @param changeSummary 变更说明
     *  @return 新版本号
     */
    @PutMapping("/publish-version")
    @Operation(summary = "生成标准文件版本快照")
    @Parameter(name = "id", description = "标准文件编号", required = true)
    @Parameter(name = "changeSummary", description = "变更说明")
    @PreAuthorize("@ss.hasPermission('governance:doc:update')")
    public CommonResult<Integer> publishDocVersion(
            @RequestParam("id") Long id,
            @RequestParam(value = "changeSummary", required = false) String changeSummary) {
        return success(docService.publishDocVersion(id, changeSummary));
    }

    /**
     * 获得标准文件的版本快照列表。
     */
    @GetMapping("/versions")
    @Operation(summary = "获得标准文件的版本快照列表")
    @Parameter(name = "id", description = "标准文件编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:doc:query')")
    public CommonResult<List<StandardObjectVersionRespVO>> getDocVersions(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(docService.getDocVersionList(id),
                StandardObjectVersionRespVO.class));
    }

    /**
     * 导出标准文件登记信息 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出标准文件登记信息 Excel")
    @PreAuthorize("@ss.hasPermission('governance:doc:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportDocExcel(@Valid StandardDocPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<StandardDocDO> list = docService.getDocPage(pageReqVO).getList();
        List<StandardDocRespVO> rows = BeanUtils.toBean(list, StandardDocRespVO.class);
        docService.fillDisplayNames(rows);
        // 导出 Excel
        ExcelUtils.write(response, "标准文件登记信息.xls", "数据", StandardDocRespVO.class,
                rows);
    }

}
