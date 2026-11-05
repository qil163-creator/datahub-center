package cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.service.categorycleanrule.CategoryCleanRuleService;
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

@Tag(name = "管理后台 - 清洗规则 类目管理")
@RestController
@RequestMapping("/governance/category-clean-rule")
@Validated
public class CategoryCleanRuleController {

    @Resource
    private CategoryCleanRuleService categoryCleanRuleService;

    /**
     * 创建清洗规则 类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-清洗规则类目", subType = "创建清洗规则类目",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了清洗规则类目【{{(#createReqVO.categoryName) ?: '未设置'}}】，编码【{{(#createReqVO.categoryCode) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建清洗规则类目")
    @PreAuthorize("@ss.hasPermission('governance:category-clean-rule:create')")
    public CommonResult<Long> createCategoryCleanRule(@Valid @RequestBody CategoryCleanRuleSaveReqVO createReqVO) {
        return success(categoryCleanRuleService.createCategoryCleanRule(createReqVO));
    }

    /**
     * 更新清洗规则 类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-清洗规则类目", subType = "更新清洗规则类目",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了清洗规则类目，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.categoryName) ?: '未设置'}}】，提交编码【{{(#updateReqVO.categoryCode) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新清洗规则类目")
    @PreAuthorize("@ss.hasPermission('governance:category-clean-rule:update')")
    public CommonResult<Boolean> updateCategoryCleanRule(@Valid @RequestBody CategoryCleanRuleSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        categoryCleanRuleService.updateCategoryCleanRule(updateReqVO);
        return success(true);
    }

    /**
     * 删除清洗规则 类目管理
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-清洗规则类目", subType = "删除清洗规则类目",
            bizNo = "{{(#id) ?: 0}}", success = "删除了清洗规则类目【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除清洗规则类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-clean-rule:delete')")
    public CommonResult<Boolean> deleteCategoryCleanRule(@RequestParam("id") Long id) {
        captureAuditContext(id);
        categoryCleanRuleService.deleteCategoryCleanRule(id);
        return success(true);
    }


    /**
     * 获得清洗规则 类目管理
     *
     *  @param id 编号
     *  @return 清洗规则 类目管理
     */
    @GetMapping("/get")
    @Operation(summary = "获得清洗规则类目")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:category-clean-rule:query')")
    public CommonResult<CategoryCleanRuleRespVO> getCategoryCleanRule(@RequestParam("id") Long id) {
        CategoryCleanRuleDO categoryCleanRule = categoryCleanRuleService.getCategoryCleanRule(id);
        return success(BeanUtils.toBean(categoryCleanRule, CategoryCleanRuleRespVO.class));
    }

    /**
     * 获得清洗规则 类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 清洗规则 类目管理列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得清洗规则类目列表")
    @PreAuthorize("@ss.hasPermission('governance:category-clean-rule:query')")
    public CommonResult<List<CategoryCleanRuleRespVO>> getCategoryCleanRuleList(
            @Valid CategoryCleanRuleListReqVO listReqVO) {
        List<CategoryCleanRuleDO> list = categoryCleanRuleService.getCategoryCleanRuleList(listReqVO);
        return success(BeanUtils.toBean(list, CategoryCleanRuleRespVO.class));
    }

    /**
     * 导出清洗规则类目 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出清洗规则类目 Excel")
    @PreAuthorize("@ss.hasPermission('governance:category-clean-rule:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportCategoryCleanRuleExcel(@Valid CategoryCleanRuleListReqVO listReqVO,
            HttpServletResponse response) throws IOException {
        List<CategoryCleanRuleDO> list = categoryCleanRuleService.getCategoryCleanRuleList(listReqVO);
        // 导出 Excel
        ExcelUtils.write(response, "清洗规则 类目管理.xls", "数据", CategoryCleanRuleRespVO.class,
                BeanUtils.toBean(list, CategoryCleanRuleRespVO.class));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = categoryCleanRuleService.getCategoryCleanRule(id);
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
