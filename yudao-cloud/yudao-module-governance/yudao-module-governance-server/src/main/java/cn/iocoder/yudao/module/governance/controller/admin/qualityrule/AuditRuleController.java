package cn.iocoder.yudao.module.governance.controller.admin.qualityrule;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.quality.core.QualityStrategyMeta;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDO;
import cn.iocoder.yudao.module.governance.service.qualityrule.AuditRuleDimensionService;
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
 * 稽查规则 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量稽查规则")
@RestController
@RequestMapping("/governance/audit-rule")
@Validated
public class AuditRuleController {

    // 规则目录及其独立维度为平台共享定义，统一按稽查规则查询权限授权。
    @Resource
    private AuditRuleDimensionService auditRuleDimensionService;

    @Resource
    private AuditRuleService auditRuleService;

    @Resource
    private AuditSqlRegistry auditSqlRegistry;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建稽查规则。
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-数据质量稽查规则", bizId = "#result")
    @Operation(summary = "创建稽查规则")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:create')")
    public CommonResult<Long> createAuditRule(@Valid @RequestBody AuditRuleSaveReqVO createReqVO) {
        return success(auditRuleService.createAuditRule(createReqVO));
    }

    /**
     * 更新稽查规则。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据质量稽查规则", bizId = "#updateReqVO.id")
    @Operation(summary = "更新稽查规则")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:update')")
    public CommonResult<Boolean> updateAuditRule(@Valid @RequestBody AuditRuleSaveReqVO updateReqVO) {
        auditRuleService.updateAuditRule(updateReqVO);
        return success(true);
    }

    /**
     * 更新稽查规则状态。
     */
    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据治理-数据质量稽查规则", bizId = "#id")
    @Operation(summary = "更新稽查规则状态")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:update')")
    public CommonResult<Boolean> updateAuditRuleStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        auditRuleService.updateAuditRuleStatus(id, status);
        return success(true);
    }

    /**
     * 删除稽查规则。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据质量稽查规则", bizId = "#id")
    @Operation(summary = "删除稽查规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:delete')")
    public CommonResult<Boolean> deleteAuditRule(@RequestParam("id") Long id) {
        auditRuleService.deleteAuditRule(id);
        return success(true);
    }

    /**
     * 获得稽查规则。
     */
    @GetMapping("/get")
    @Operation(summary = "获得稽查规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:query')")
    public CommonResult<AuditRuleRespVO> getAuditRule(@RequestParam("id") Long id) {
        AuditRuleDO rule = auditRuleService.getAuditRule(id);
        if (rule == null) {
            return success(null);
        }
        return success(fillRespVO(List.of(BeanUtils.toBean(rule, AuditRuleRespVO.class))).get(0));
    }

    /**
     * 获得稽查规则分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得稽查规则分页")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:query')")
    public CommonResult<PageResult<AuditRuleRespVO>> getAuditRulePage(@Valid AuditRulePageReqVO pageReqVO) {
        PageResult<AuditRuleDO> pageResult = auditRuleService.getAuditRulePage(pageReqVO);
        PageResult<AuditRuleRespVO> result = BeanUtils.toBean(pageResult, AuditRuleRespVO.class);
        fillRespVO(result.getList());
        return success(result);
    }

    /**
     * 获得稽查规则维度列表。
     */
    @GetMapping("/dimension-list")
    @Operation(summary = "获得稽查规则维度列表", description = "平台共享的独立平面维度，按显示顺序和编号排序")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:query')")
    public CommonResult<List<AuditRuleDimensionRespVO>> getDimensionList() {
        return success(BeanUtils.toBean(auditRuleDimensionService.getDimensionList(), AuditRuleDimensionRespVO.class));
    }

    /**
     * 获得质量引擎稽查策略列表。
     */
    @GetMapping("/strategy-list")
    @Operation(summary = "获得质量引擎稽查策略列表")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:query')")
    public CommonResult<List<QualityStrategyRespVO>> getStrategyList() {
        List<QualityStrategyMeta> strategies = auditSqlRegistry.listStrategies();
        return success(strategies.stream()
                .map(meta -> new QualityStrategyRespVO(meta.getStrategyKey(), meta.getStrategyName(),
                        meta.getConfigExample()))
                .collect(Collectors.toList()));
    }

    /**
     * 按策略生成稽查 SQL 预览（错误统计 + 错误明细）
     */
    @PostMapping("/preview-sql")
    @Operation(summary = "预览稽查 SQL（错误统计 + 错误明细）")
    @PreAuthorize("@ss.hasPermission('governance:audit-rule:query')")
    public CommonResult<AuditRulePreviewRespVO> previewSql(@Valid @RequestBody AuditRulePreviewReqVO reqVO) {
        return success(auditRuleService.previewSql(reqVO));
    }

    /**
     * 填充维度名称 / 创建人名称 / 引擎支持标记
     */
    private List<AuditRuleRespVO> fillRespVO(List<AuditRuleRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return list;
        }
        // 维度名称
        Set<Long> dimensionIds = list.stream().map(AuditRuleRespVO::getDimensionId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (CollUtil.isNotEmpty(dimensionIds)) {
            Map<Long, String> nameMap = auditRuleDimensionService.getDimensionNameMap(dimensionIds);
            list.forEach(
                    vo -> vo.setDimensionName(vo.getDimensionId() == null ? null : nameMap.get(vo.getDimensionId())));
        }
        // 创建人名称
        Set<Long> userIds = list.stream().map(AuditRuleRespVO::getCreator)
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
        // 引擎支持标记
        list.forEach(vo -> vo.setEngineSupported(auditSqlRegistry.contains(vo.getStrategyKey())));
        return list;
    }

}
