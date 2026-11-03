package cn.iocoder.yudao.module.governance.controller.admin.quality;

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
    @Operation(summary = "创建规则集")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:create')")
    public CommonResult<Long> createRuleSet(@Valid @RequestBody QualityRuleSetSaveReqVO createReqVO) {
        return success(qualityRuleSetService.createRuleSet(createReqVO));
    }

    /**
     * 更新规则集。
     */
    @PutMapping("/update")
    @Operation(summary = "更新规则集")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:update')")
    public CommonResult<Boolean> updateRuleSet(@Valid @RequestBody QualityRuleSetSaveReqVO updateReqVO) {
        qualityRuleSetService.updateRuleSet(updateReqVO);
        return success(true);
    }

    /**
     * 更新规则集状态。
     */
    @PutMapping("/update-status")
    @Operation(summary = "更新规则集状态")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:update')")
    public CommonResult<Boolean> updateRuleSetStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        qualityRuleSetService.updateRuleSetStatus(id, status);
        return success(true);
    }

    /**
     * 删除规则集。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除规则集")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:delete')")
    public CommonResult<Boolean> deleteRuleSet(@RequestParam("id") Long id) {
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
    @Operation(summary = "保存规则集明细（全量覆盖）")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule-set:update')")
    public CommonResult<Boolean> saveRuleSetItems(@Valid @RequestBody QualityRuleSetItemSaveReqVO reqVO) {
        qualityRuleSetService.saveRuleSetItems(reqVO);
        return success(true);
    }

}
