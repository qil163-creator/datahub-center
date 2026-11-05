package cn.iocoder.yudao.module.governance.controller.admin.rule;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;

import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyReplaceItemVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rulever.SafetyRuleVerDO;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
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
import java.util.Map;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 脱敏规则（主表）；参数演进由版本表承载")
@RestController
@RequestMapping("/governance/safety-rule")
@Validated
public class SafetyRuleController {

    @Resource
    private SafetyRuleService ruleService;

    /**
     * 创建数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-脱敏规则（主表）；参数演进由版本表承载", subType = "创建脱敏规则",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了脱敏规则【{{(#createReqVO.ruleName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:create')")
    public CommonResult<Long> createRule(@Valid @RequestBody SafetyRuleSaveReqVO createReqVO) {
        return success(ruleService.createRule(createReqVO));
    }

    /**
     * 更新数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-脱敏规则（主表）；参数演进由版本表承载", subType = "更新脱敏规则",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了脱敏规则，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.ruleName) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:update')")
    public CommonResult<Boolean> updateRule(@Valid @RequestBody SafetyRuleSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        ruleService.updateRule(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-脱敏规则（主表）；参数演进由版本表承载", subType = "删除脱敏规则",
            bizNo = "{{(#id) ?: 0}}", success = "删除了脱敏规则【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除脱敏规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:rule:delete')")
    public CommonResult<Boolean> deleteRule(@RequestParam("id") Long id) {
        captureAuditContext(id);
        ruleService.deleteRule(id);
        return success(true);
    }

    /**
     * 批量删除脱敏规则。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据治理-脱敏规则（主表）；参数演进由版本表承载", subType = "批量删除脱敏规则",
            bizNo = "{{(#ids) ?: 0}}", success = "提交批量删除脱敏规则，对象编号【{{(#ids) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:delete')")
    public CommonResult<Boolean> deleteRuleList(@RequestParam("ids") List<Long> ids) {
        ruleService.deleteRuleListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param id 编号
     *  @return 数据安全--脱敏规则（主表）；参数演进由版本表承载
     */
    @GetMapping("/get")
    @Operation(summary = "获得脱敏规则")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:rule:query')")
    public CommonResult<SafetyRuleRespVO> getRule(@RequestParam("id") Long id) {
        SafetyRuleDO rule = ruleService.getRule(id);
        SafetyRuleRespVO respVO = BeanUtils.toBean(rule, SafetyRuleRespVO.class);
        // 补充当前生效版本的脱敏参数与替换明细，用于表单回显
        if (respVO != null && rule.getCurrentVerId() != null) {
            SafetyRuleVerDO currentVer = ruleService.getRuleVer(rule.getCurrentVerId());
            if (currentVer != null) {
                respVO.setCurrentVersionNo(currentVer.getVersionNo());
                respVO.setConfig(JsonUtils.parseObject(currentVer.getConfigJson(), Map.class));
                respVO.setReplaceItems(BeanUtils.toBean(
                        ruleService.getReplaceItemListByRuleVerId(currentVer.getId()), SafetyReplaceItemVO.class));
            }
        }
        return success(respVO);
    }

    /**
     * 获得数据安全--脱敏规则（主表）；参数演进由版本表承载分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据安全--脱敏规则（主表）；参数演进由版本表承载分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:query')")
    public CommonResult<PageResult<SafetyRuleRespVO>> getRulePage(@Valid SafetyRulePageReqVO pageReqVO) {
        PageResult<SafetyRuleDO> pageResult = ruleService.getRulePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, SafetyRuleRespVO.class));
    }

    /**
     * 导出脱敏规则。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出脱敏规则")
    @PreAuthorize("@ss.hasPermission('governance:rule:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportRuleExcel(@Valid SafetyRulePageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<SafetyRuleDO> list = ruleService.getRulePage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "脱敏规则.xls", "数据", SafetyRuleRespVO.class,
                BeanUtils.toBean(list, SafetyRuleRespVO.class));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = ruleService.getRule(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getRuleName() == null
                        ? "编号" + id : auditBefore.getRuleName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getCurrentVerId() == null
                        ? "未设置" : String.valueOf(auditBefore.getCurrentVerId()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
