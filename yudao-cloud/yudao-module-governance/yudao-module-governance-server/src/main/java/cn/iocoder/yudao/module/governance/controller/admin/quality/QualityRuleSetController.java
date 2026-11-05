package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetItemSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetDO;
import cn.iocoder.yudao.module.governance.service.quality.QualityRuleSetService;
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

/**
 * 数据治理-数据质量-规则集 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-规则集")
@RestController
@RequestMapping("/governance/quality-rule-set")
@Validated
public class QualityRuleSetController {

    @Resource
    private QualityRuleSetService qualityRuleSetService;

    /**
     * 创建规则集。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-数据质量-规则集", subType = "创建规则集",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了质量规则集【{{(#createReqVO.setName) ?: '未设置'}}】，编码【{{(#createReqVO.setCode) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建规则集")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:create')")
    public CommonResult<Long> createRuleSet(@Valid @RequestBody QualityRuleSetSaveReqVO createReqVO) {
        return success(qualityRuleSetService.createRuleSet(createReqVO));
    }

    /**
     * 更新规则集。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-规则集", subType = "更新规则集",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了质量规则集，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.setName) ?: '未设置'}}】，提交编码【{{(#updateReqVO.setCode) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新规则集")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:update')")
    public CommonResult<Boolean> updateRuleSet(@Valid @RequestBody QualityRuleSetSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        qualityRuleSetService.updateRuleSet(updateReqVO);
        return success(true);
    }

    /**
     * 更新规则集状态。
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-数据质量-规则集", subType = "更新规则集状态",
            bizNo = "{{(#id) ?: 0}}", success = "调整了质量规则集【{{(#auditObjectName) ?: '编号' + #id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新规则集状态")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:update')")
    public CommonResult<Boolean> updateRuleSetStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        captureAuditContext(id);
        qualityRuleSetService.updateRuleSetStatus(id, status);
        return success(true);
    }

    /**
     * 删除规则集。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-规则集", subType = "删除规则集",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量规则集【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除规则集")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:delete')")
    public CommonResult<Boolean> deleteRuleSet(@RequestParam("id") Long id) {
        captureAuditContext(id);
        qualityRuleSetService.deleteRuleSet(id);
        return success(true);
    }

    /**
     * 获得规则集。
     */
    @GetMapping("/get")
    @Operation(summary = "获得规则集")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:query')")
    public CommonResult<QualityRuleSetRespVO> getRuleSet(@RequestParam("id") Long id) {
        QualityRuleSetDO ruleSet = qualityRuleSetService.getRuleSet(id);
        return success(BeanUtils.toBean(ruleSet, QualityRuleSetRespVO.class));
    }

    /**
     * 获得规则集分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得规则集分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:query')")
    public CommonResult<PageResult<QualityRuleSetRespVO>> getRuleSetPage(@Valid QualityRuleSetPageReqVO pageReqVO) {
        return success(qualityRuleSetService.getRuleSetPage(pageReqVO));
    }

    /**
     * 获得规则集精简列表（启用状态）。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得规则集精简列表（启用状态）")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:query')")
    public CommonResult<List<QualityRuleSetRespVO>> getRuleSetSimpleList() {
        return success(BeanUtils.toBean(qualityRuleSetService.getRuleSetSimpleList(), QualityRuleSetRespVO.class));
    }

    /**
     * 规则集明细列表（带规则名称/类型/维度）
     */
    @GetMapping("/items")
    @Operation(summary = "获得规则集明细列表")
    @Parameter(name = "ruleSetId", description = "规则集编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:query')")
    public CommonResult<List<QualityRuleSetRespVO.Item>> getRuleSetItems(@RequestParam("ruleSetId") Long ruleSetId) {
        return success(qualityRuleSetService.getRuleSetItems(ruleSetId));
    }

    /**
     * 保存规则集规则（全量覆盖）
     */
    @PostMapping("/save-items")
    @LogRecord(type = "数据治理-数据质量-规则集", subType = "保存规则集明细（全量覆盖）",
            bizNo = "{{(#reqVO.ruleSetId) ?: 0}}", success = "更新了规则集【{{(#auditObjectName) ?: '编号' + #reqVO.ruleSetId}}】的规则明细，规则编号【{{(#reqVO.items.![ruleId]) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存规则集明细（全量覆盖）")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:update')")
    public CommonResult<Boolean> saveRuleSetItems(@Valid @RequestBody QualityRuleSetItemSaveReqVO reqVO) {
        captureAuditContext(reqVO.getRuleSetId());
        qualityRuleSetService.saveRuleSetItems(reqVO);
        return success(true);
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = qualityRuleSetService.getRuleSet(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getSetName() == null
                        ? "编号" + id : auditBefore.getSetName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
