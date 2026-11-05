package cn.iocoder.yudao.module.governance.controller.admin.standardterm;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermCategoryRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermService;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.service.standardterm.StandardTermService;
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

@Tag(name = "管理后台 - 数据标准--业务术语")
@RestController
@RequestMapping("/governance/standard-term")
@Validated
public class StandardTermController {

    @Resource
    private StandardTermService standardTermService;

    @Resource
    private CategoryTermService categoryTermService;

    @GetMapping("/category-list")
    @Operation(summary = "获得业务术语类目树节点")
    @PreAuthorize("@ss.hasAnyPermissions('governance:standard-term:query', 'governance:standard-term:create', 'governance:standard-term:update')")
    public CommonResult<List<StandardTermCategoryRespVO>> getCategories() {
        return success(BeanUtils.toBean(categoryTermService.getCategoryTermList(new CategoryTermListReqVO()),
                StandardTermCategoryRespVO.class));
    }

    /**
     * 创建业务术语
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-业务术语", subType = "创建业务术语",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了业务术语【{{(#createReqVO.name) ?: '未设置'}}】，英文名称【{{(#createReqVO.engName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建业务术语")
    @PreAuthorize("@ss.hasPermission('governance:standard-term:create')")
    public CommonResult<Long> createStandardTerm(@Valid @RequestBody StandardTermSaveReqVO createReqVO) {
        return success(standardTermService.createStandardTerm(createReqVO));
    }

    /**
     * 更新业务术语
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-业务术语", subType = "更新业务术语",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了业务术语，名称从【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】改为【{{(#updateReqVO.name) ?: '未设置'}}】，状态【{{(#auditBeforeStatus) ?: '未设置'}}】→【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新业务术语")
    @PreAuthorize("@ss.hasPermission('governance:standard-term:update')")
    public CommonResult<Boolean> updateStandardTerm(@Valid @RequestBody StandardTermSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        standardTermService.updateStandardTerm(updateReqVO);
        return success(true);
    }

    /**
     * 删除业务术语
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-业务术语", subType = "删除业务术语",
            bizNo = "{{(#id) ?: 0}}", success = "删除了业务术语【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除业务术语")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-term:delete')")
    public CommonResult<Boolean> deleteStandardTerm(@RequestParam("id") Long id) {
        captureAuditContext(id);
        standardTermService.deleteStandardTerm(id);
        return success(true);
    }

    /**
     * 批量删除业务术语。
     */
    @DeleteMapping("/delete-list")
    @LogRecord(type = "数据治理-业务术语", subType = "批量删除业务术语",
            bizNo = "{{(#ids) ?: 0}}", success = "删除了现有业务术语【{{(#auditTermNames) ?: '未设置'}}】，请求术语编号【{{(#ids) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除业务术语")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-term:delete')")
    public CommonResult<Boolean> deleteStandardTermList(@RequestParam("ids") List<Long> ids) {
        LogRecordContext.putVariable("auditTermNames", "未找到现有术语");
        try {
            var auditTerms = standardTermService.getStandardTermList(ids);
            if (auditTerms != null && !auditTerms.isEmpty()) {
                LogRecordContext.putVariable("auditTermNames", String.join("、", auditTerms.stream()
                        .map(term -> term.getName() == null ? "编号" + term.getId() : term.getName()).toList()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原批量删除逻辑处理业务校验。
        }

        standardTermService.deleteStandardTermListByIds(ids);
        return success(true);
    }

    /**
     * 获得业务术语
     */
    @GetMapping("/get")
    @Operation(summary = "获得业务术语")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-term:query')")
    public CommonResult<StandardTermRespVO> getStandardTerm(@RequestParam("id") Long id) {
        StandardTermDO term = standardTermService.getStandardTerm(id);
        return success(BeanUtils.toBean(term, StandardTermRespVO.class));
    }

    /**
     * 获得业务术语分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得业务术语分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-term:query')")
    public CommonResult<PageResult<StandardTermRespVO>> getStandardTermPage(
            @Valid StandardTermPageReqVO pageReqVO) {
        PageResult<StandardTermDO> pageResult = standardTermService.getStandardTermPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, StandardTermRespVO.class));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = standardTermService.getStandardTerm(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getName() == null
                        ? "编号" + id : auditBefore.getName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
