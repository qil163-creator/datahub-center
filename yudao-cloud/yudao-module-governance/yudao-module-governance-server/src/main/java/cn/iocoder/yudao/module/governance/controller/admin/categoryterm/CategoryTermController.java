package cn.iocoder.yudao.module.governance.controller.admin.categoryterm;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 业务术语类目")
@RestController
@RequestMapping("/governance/category-term")
@Validated
public class CategoryTermController {

    @Resource
    private CategoryTermService categoryTermService;

    @PostMapping("/create")
    @LogRecord(type = "数据治理-业务术语类目", subType = "创建业务术语类目",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了业务术语类目【{{(#reqVO.categoryName) ?: '未设置'}}】，编码【{{(#reqVO.categoryCode) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建业务术语类目")
    @PreAuthorize("@ss.hasPermission('governance:category-term:create')")
    public CommonResult<Long> createCategoryTerm(@Valid @RequestBody CategoryTermSaveReqVO reqVO) {
        return success(categoryTermService.createCategoryTerm(reqVO));
    }

    @PutMapping("/update")
    @LogRecord(type = "数据治理-业务术语类目", subType = "更新业务术语类目",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "修改了业务术语类目，原名称【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】，提交名称【{{(#reqVO.categoryName) ?: '未设置'}}】，提交编码【{{(#reqVO.categoryCode) ?: '未设置'}}】，目标状态【{{(#reqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新业务术语类目")
    @PreAuthorize("@ss.hasPermission('governance:category-term:update')")
    public CommonResult<Boolean> updateCategoryTerm(@Valid @RequestBody CategoryTermSaveReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        categoryTermService.updateCategoryTerm(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-业务术语类目", subType = "删除业务术语类目",
            bizNo = "{{(#id) ?: 0}}", success = "删除了业务术语类目【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除业务术语类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-term:delete')")
    public CommonResult<Boolean> deleteCategoryTerm(@RequestParam("id") Long id) {
        captureAuditContext(id);
        categoryTermService.deleteCategoryTerm(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得业务术语类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-term:query')")
    public CommonResult<CategoryTermRespVO> getCategoryTerm(@RequestParam("id") Long id) {
        CategoryTermDO term = categoryTermService.getCategoryTerm(id);
        return success(BeanUtils.toBean(term, CategoryTermRespVO.class));
    }

    @GetMapping("/list")
    @Operation(summary = "获得业务术语类目列表")
    @PreAuthorize("@ss.hasPermission('governance:category-term:query')")
    public CommonResult<List<CategoryTermRespVO>> getCategoryTermList(@Valid CategoryTermListReqVO reqVO) {
        List<CategoryTermDO> list = categoryTermService.getCategoryTermList(reqVO);
        return success(BeanUtils.toBean(list, CategoryTermRespVO.class));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = categoryTermService.getCategoryTerm(id);
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
