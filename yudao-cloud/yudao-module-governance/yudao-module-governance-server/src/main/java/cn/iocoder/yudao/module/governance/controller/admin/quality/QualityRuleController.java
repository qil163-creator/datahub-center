package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.quality.core.QualityStrategyMeta;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRuleRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePreviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePreviewRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.QualityStrategyRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.service.quality.QualityRuleService;
import cn.iocoder.yudao.module.governance.service.qualityrule.AuditRuleService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据质量-质量规则
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-质量规则")
@RestController
@RequestMapping("/governance/quality-rule")
@Validated
public class QualityRuleController {

    @Resource
    private QualityRuleService qualityRuleService;

    @Resource
    private AuditRuleService auditRuleService;

    @Resource
    private AuditSqlRegistry auditSqlRegistry;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建质量规则。
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-数据质量-质量规则", bizId = "#result")
    @Operation(summary = "创建质量规则")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:create')")
    public CommonResult<Long> createQualityRule(@Valid @RequestBody QualityRuleSaveReqVO createReqVO) {
        return success(qualityRuleService.createQualityRule(createReqVO));
    }

    /**
     * 更新质量规则。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据质量-质量规则", bizId = "#updateReqVO.id")
    @Operation(summary = "更新质量规则")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:update')")
    public CommonResult<Boolean> updateQualityRule(@Valid @RequestBody QualityRuleSaveReqVO updateReqVO) {
        qualityRuleService.updateQualityRule(updateReqVO);
        return success(true);
    }

    /**
     * 更新质量规则状态。
     */
    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据治理-数据质量-质量规则", bizId = "#id")
    @Operation(summary = "更新质量规则状态")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:update')")
    public CommonResult<Boolean> updateQualityRuleStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        qualityRuleService.updateQualityRuleStatus(id, status);
        return success(true);
    }

    /**
     * 删除质量规则。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据质量-质量规则", bizId = "#id")
    @Operation(summary = "删除质量规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:delete')")
    public CommonResult<Boolean> deleteQualityRule(@RequestParam("id") Long id) {
        qualityRuleService.deleteQualityRule(id);
        return success(true);
    }

    /**
     * 获得质量规则。
     */
    @GetMapping("/get")
    @Operation(summary = "获得质量规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:query')")
    public CommonResult<QualityRuleRespVO> getQualityRule(@RequestParam("id") Long id) {
        QualityRuleDO rule = qualityRuleService.getQualityRule(id);
        if (rule == null) {
            return success(null);
        }
        return success(fillRespVO(List.of(BeanUtils.toBean(rule, QualityRuleRespVO.class))).get(0));
    }

    /**
     * 获得质量规则分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得质量规则分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:query')")
    public CommonResult<PageResult<QualityRuleRespVO>> getQualityRulePage(@Valid QualityRulePageReqVO pageReqVO) {
        PageResult<QualityRuleDO> pageResult = qualityRuleService.getQualityRulePage(pageReqVO);
        PageResult<QualityRuleRespVO> result = BeanUtils.toBean(pageResult, QualityRuleRespVO.class);
        fillRespVO(result.getList());
        return success(result);
    }

    /**
     * 启用规则精简列表；传入标准数据元编号时只返回该标准物化的规则。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得质量规则精简列表（启用状态）")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:query')")
    public CommonResult<List<QualityRuleRespVO>> getQualityRuleSimpleList(
            @RequestParam(value = "standardElementId", required = false) Long standardElementId) {
        List<QualityRuleDO> list = qualityRuleService.getQualityRuleSimpleList(standardElementId);
        return success(BeanUtils.toBean(list, QualityRuleRespVO.class));
    }

    /**
     * 预览规则 SQL（错误统计 + 错误明细，复用质量引擎）。
     */
    @PostMapping("/preview-sql")
    @Operation(summary = "预览规则 SQL（错误统计 + 错误明细，复用质量引擎）")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:query')")
    public CommonResult<AuditRulePreviewRespVO> previewSql(@Valid @RequestBody AuditRulePreviewReqVO reqVO) {
        return success(auditRuleService.previewSql(reqVO));
    }

    /**
     * 获得质量引擎策略列表。
     */
    @GetMapping("/strategy-list")
    @Operation(summary = "获得质量引擎策略列表")
    @PreAuthorize("@ss.hasPermission('governance:quality-rule:query')")
    public CommonResult<List<QualityStrategyRespVO>> getStrategyList() {
        List<QualityStrategyMeta> strategies = auditSqlRegistry.listStrategies();
        return success(strategies.stream()
                .map(meta -> new QualityStrategyRespVO(meta.getStrategyKey(), meta.getStrategyName(),
                        meta.getConfigExample()))
                .collect(Collectors.toList()));
    }

    /**
     * 填充创建人名称
     */
    private List<QualityRuleRespVO> fillRespVO(List<QualityRuleRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return list;
        }
        Set<Long> userIds = list.stream().map(QualityRuleRespVO::getCreator)
                .filter(creator -> StrUtil.isNotBlank(creator) && creator.matches("\\d+"))
                .map(Long::valueOf).collect(Collectors.toSet());
        if (CollUtil.isNotEmpty(userIds)) {
            Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserList(userIds).getCheckedData().stream()
                    .collect(Collectors.toMap(AdminUserRespDTO::getId, user -> user));
            list.forEach(vo -> {
                if (StrUtil.isNotBlank(vo.getCreator()) && vo.getCreator().matches("\\d+")) {
                    AdminUserRespDTO user = userMap.get(Long.valueOf(vo.getCreator()));
                    vo.setCreatorName(user != null ? user.getNickname() : vo.getCreator());
                }
            });
        }
        return list;
    }

}
