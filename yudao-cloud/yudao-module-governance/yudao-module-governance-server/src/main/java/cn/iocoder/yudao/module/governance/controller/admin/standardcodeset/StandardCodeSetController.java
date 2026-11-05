package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetImportExcelVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetSaveReqVO;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.governance.service.standardcodeset.CodeSetModelAssociationService;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
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

import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.service.standardcodeset.StandardCodeSetService;

@Tag(name = "管理后台 - 数据标准--标准码")
@RestController
@RequestMapping("/governance/standard-code-set")
@Validated
public class StandardCodeSetController {

    @Resource
    private StandardCatalogService catalogService;

    @Resource
    private CodeSetModelAssociationService modelAssociations;

    @GetMapping("/model-fields")
    @Operation(summary = "查询直接或通过数据元关联代码表的模型字段")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query') and @ss.hasPermission('modeling:model:query')")
    public CommonResult<List<ModelStandardFieldRespDTO>> getModelFields(@RequestParam("id") Integer id) {
        return success(modelAssociations.getFields(id));
    }

    /**
     * 查询共享标准分类。
     */
    @GetMapping("/category-list")
    @Operation(summary = "查询共享标准分类")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<List<CategoryDatametaRespVO>> categoryList() {
        return success(catalogService.categoryList());
    }

    /**
     * 切换标准启用状态。
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-标准码", subType = "切换标准启用状态",
            bizNo = "{{(#id) ?: 0}}", success = "调整了标准码表【{{(#auditObjectName) ?: '编号' + #id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "切换标准启用状态")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Boolean> updateStatus(@RequestParam Integer id, @RequestParam Short status,
            @RequestParam Long revision) {
        captureAuditContext(id);
        standardCodeSetService.updateStatus(id, status, revision);
        return success(true);
    }


    @Resource
    private StandardCodeSetService standardCodeSetService;

    /**
     * 创建数据标准--标准码
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-标准码", subType = "创建数据标准--标准码",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了标准码表【{{(#createReqVO.name) ?: '未设置'}}】，编码【{{(#createReqVO.type) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建数据标准--标准码")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:create')")
    public CommonResult<Integer> createStandardCodeSet(@Valid @RequestBody StandardCodeSetSaveReqVO createReqVO) {
        return success(standardCodeSetService.createStandardCodeSet(createReqVO));
    }

    /**
     * 更新数据标准--标准码
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-标准码", subType = "更新数据标准--标准码",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了标准码表，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.name) ?: '未设置'}}】，提交编码【{{(#updateReqVO.type) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据标准--标准码")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Boolean> updateStandardCodeSet(@Valid @RequestBody StandardCodeSetSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        standardCodeSetService.updateStandardCodeSet(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据标准--标准码
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-标准码", subType = "删除数据标准--标准码",
            bizNo = "{{(#id) ?: 0}}", success = "删除了标准码表【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除数据标准--标准码")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:delete')")
    public CommonResult<Boolean> deleteStandardCodeSet(@RequestParam("id") Integer id) {
        captureAuditContext(id);
        standardCodeSetService.deleteStandardCodeSet(id);
        return success(true);
    }

    /**
     * 批量删除数据标准--标准码。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据治理-标准码", subType = "批量删除数据标准--标准码",
            bizNo = "{{(#ids) ?: 0}}", success = "提交批量删除标准码表，对象编号【{{(#ids) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除数据标准--标准码")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:delete')")
    public CommonResult<Boolean> deleteStandardCodeSetList(@RequestParam("ids") List<Integer> ids) {
        standardCodeSetService.deleteStandardCodeSetListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据标准--标准码
     *
     *  @param id 编号
     *  @return 数据标准--标准码
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据标准--标准码")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<StandardCodeSetRespVO> getStandardCodeSet(@RequestParam("id") Integer id) {
        StandardCodeSetDO standardCodeSet = standardCodeSetService.getStandardCodeSet(id);
        StandardCodeSetRespVO vo = BeanUtils.toBean(standardCodeSet, StandardCodeSetRespVO.class);
        if (vo != null) {
            standardCodeSetService.fillItemCounts(Collections.singletonList(vo));
            catalogService.fillCodeSets(List.of(vo));
        }
        return success(vo);
    }

    /**
     * 获得数据标准--标准码分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据标准--标准码分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据标准--标准码分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<PageResult<StandardCodeSetRespVO>> getStandardCodeSetPage(
            @Valid StandardCodeSetPageReqVO pageReqVO) {
        PageResult<StandardCodeSetDO> pageResult = standardCodeSetService.getStandardCodeSetPage(pageReqVO);
        PageResult<StandardCodeSetRespVO> voResult = BeanUtils.toBean(pageResult, StandardCodeSetRespVO.class);
        standardCodeSetService.fillItemCounts(voResult.getList());
        catalogService.fillCodeSets(voResult.getList());
        return success(voResult);
    }

    /**
     * 获得码表/码值导入模板。
     */
    @GetMapping("/get-import-template")
    @Operation(summary = "获得码表/码值导入模板")
    public void importTemplate(HttpServletResponse response) throws IOException {
        List<StandardCodeSetImportExcelVO> list = List.of(
                StandardCodeSetImportExcelVO.builder()
                        .codeSetName("性别").type("std_gender").value("1").label("男").sort(1).build(),
                StandardCodeSetImportExcelVO.builder()
                        .codeSetName("性别").type("std_gender").value("2").label("女").sort(2).build(),
                StandardCodeSetImportExcelVO.builder()
                        .codeSetName("证件类型").type("std_id_type").value("01").label("居民身份证").sort(1)
                        .remark("GB/T 2261.1").build());
        ExcelUtils.write(response, "码表码值导入模板.xls", "码值列表",
                StandardCodeSetImportExcelVO.class, list);
    }

    /**
     * 导入码表/码值。
     */
    @PostMapping("/import")
    @LogRecord(type = "数据治理-标准码", subType = "导入码表与码值（处理结果见导入回执）",
            bizNo = "0", success = "提交了码表与码值的Excel导入，允许更新已有记录【{{(#updateSupport) ?: '未设置'}}】，逐行处理结果见导入回执",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "导入码表/码值")
    @Parameters({
            @Parameter(name = "file", description = "Excel 文件", required = true),
            @Parameter(name = "updateSupport", description = "是否支持更新（按字典类型+键值匹配），默认为 false", example = "true")
    })
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:import')")
    @ApiAccessLog(operateType = IMPORT)
    public CommonResult<StandardCodeSetImportRespVO> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "updateSupport", required = false, defaultValue = "false") Boolean updateSupport)
            throws Exception {
        List<StandardCodeSetImportExcelVO> list = ExcelUtils.read(file, StandardCodeSetImportExcelVO.class);
        return success(standardCodeSetService.importStandardCodeSetList(list, updateSupport));
    }

    /**
     * 导出数据标准--标准码 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据标准--标准码 Excel")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportStandardCodeSetExcel(@Valid StandardCodeSetPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<StandardCodeSetDO> list = standardCodeSetService.getStandardCodeSetPage(pageReqVO).getList();
        List<StandardCodeSetRespVO> voList = BeanUtils.toBean(list, StandardCodeSetRespVO.class);
        standardCodeSetService.fillItemCounts(voList);
        // 导出 Excel
        ExcelUtils.write(response, "数据标准--标准码.xls", "数据", StandardCodeSetRespVO.class, voList);
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Integer id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = standardCodeSetService.getStandardCodeSet(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getName() == null
                        ? "编号" + id : auditBefore.getName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getRevision() == null
                        ? "未设置" : String.valueOf(auditBefore.getRevision()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
