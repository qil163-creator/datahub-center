package cn.iocoder.yudao.module.governance.controller.admin.categorydatameta;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.service.categorydatameta.CategoryDatametaService;
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

@Tag(name = "管理后台 - 标准数据元类目")
@RestController
@RequestMapping("/governance/category-datameta")
@Validated
public class CategoryDatametaController {

    @Resource
    private CategoryDatametaService categoryDatametaService;

    /**
     * 创建标准数据元类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-标准数据元类目", subType = "创建标准数据元类目",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了标准数据元类目【{{(#createReqVO.categoryName) ?: '未设置'}}】，编码【{{(#createReqVO.categoryCode) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建标准数据元类目")
    @PreAuthorize("@ss.hasPermission('governance:category-datameta:create')")
    public CommonResult<Long> createCategoryDatameta(@Valid @RequestBody CategoryDatametaSaveReqVO createReqVO) {
        return success(categoryDatametaService.createCategoryDatameta(createReqVO));
    }

    /**
     * 更新标准数据元类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-标准数据元类目", subType = "更新标准数据元类目",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了标准数据元类目，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.categoryName) ?: '未设置'}}】，提交编码【{{(#updateReqVO.categoryCode) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新标准数据元类目")
    @PreAuthorize("@ss.hasPermission('governance:category-datameta:update')")
    public CommonResult<Boolean> updateCategoryDatameta(@Valid @RequestBody CategoryDatametaSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        categoryDatametaService.updateCategoryDatameta(updateReqVO);
        return success(true);
    }

    /**
     * 删除标准数据元类目管理
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-标准数据元类目", subType = "删除标准数据元类目",
            bizNo = "{{(#id) ?: 0}}", success = "删除了标准数据元类目【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除标准数据元类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-datameta:delete')")
    public CommonResult<Boolean> deleteCategoryDatameta(@RequestParam("id") Long id) {
        captureAuditContext(id);
        categoryDatametaService.deleteCategoryDatameta(id);
        return success(true);
    }


    /**
     * 获得标准数据元类目管理
     *
     *  @param id 编号
     *  @return 标准数据元类目管理
     */
    @GetMapping("/get")
    @Operation(summary = "获得标准数据元类目")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:category-datameta:query')")
    public CommonResult<CategoryDatametaRespVO> getCategoryDatameta(@RequestParam("id") Long id) {
        CategoryDatametaDO categoryDatameta = categoryDatametaService.getCategoryDatameta(id);
        return success(BeanUtils.toBean(categoryDatameta, CategoryDatametaRespVO.class));
    }

    /**
     * 获得标准数据元类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 标准数据元类目管理列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得标准数据元类目列表")
    @PreAuthorize("@ss.hasPermission('governance:category-datameta:query')")
    public CommonResult<List<CategoryDatametaRespVO>> getCategoryDatametaList(
            @Valid CategoryDatametaListReqVO listReqVO) {
        List<CategoryDatametaDO> list = categoryDatametaService.getCategoryDatametaList(listReqVO);
        return success(BeanUtils.toBean(list, CategoryDatametaRespVO.class));
    }

    /**
     * 导出标准数据元类目 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出标准数据元类目 Excel")
    @PreAuthorize("@ss.hasPermission('governance:category-datameta:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportCategoryDatametaExcel(@Valid CategoryDatametaListReqVO listReqVO,
            HttpServletResponse response) throws IOException {
        List<CategoryDatametaDO> list = categoryDatametaService.getCategoryDatametaList(listReqVO);
        // 导出 Excel
        ExcelUtils.write(response, "标准数据元类目管理.xls", "数据", CategoryDatametaRespVO.class,
                BeanUtils.toBean(list, CategoryDatametaRespVO.class));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = categoryDatametaService.getCategoryDatameta(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getCategoryName() == null
                        ? "编号" + id : auditBefore.getCategoryName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
