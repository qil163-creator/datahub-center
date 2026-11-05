package cn.iocoder.yudao.module.governance.controller.admin.qualityrule;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.quality.core.QualityStrategyMeta;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleRespVO;
import cn.iocoder.yudao.module.governance.service.categorycleanrule.CategoryCleanRuleService;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.service.qualityrule.CleanRuleService;
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
 * 清洗规则 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量清洗规则")
@RestController
@RequestMapping("/governance/clean-rule")
@Validated
public class CleanRuleController {

    // 规则目录为平台共享定义；列表、详情与分类树统一按目录查询权限授权。
    @Resource
    private CategoryCleanRuleService categoryCleanRuleService;

    @Resource
    private CleanRuleService cleanRuleService;

    @Resource
    private CategoryCleanRuleMapper categoryCleanRuleMapper;

    @Resource
    private CleanFunctionRegistry cleanFunctionRegistry;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建清洗规则。
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-数据质量清洗规则", bizId = "#result")
    @Operation(summary = "创建清洗规则")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:create')")
    public CommonResult<Long> createCleanRule(@Valid @RequestBody CleanRuleSaveReqVO createReqVO) {
        return success(cleanRuleService.createCleanRule(createReqVO));
    }

    /**
     * 更新清洗规则。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据质量清洗规则", bizId = "#updateReqVO.id")
    @Operation(summary = "更新清洗规则")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:update')")
    public CommonResult<Boolean> updateCleanRule(@Valid @RequestBody CleanRuleSaveReqVO updateReqVO) {
        cleanRuleService.updateCleanRule(updateReqVO);
        return success(true);
    }

    /**
     * 更新清洗规则状态。
     */
    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据治理-数据质量清洗规则", bizId = "#id")
    @Operation(summary = "更新清洗规则状态")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:update')")
    public CommonResult<Boolean> updateCleanRuleStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        cleanRuleService.updateCleanRuleStatus(id, status);
        return success(true);
    }

    /**
     * 删除清洗规则。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据质量清洗规则", bizId = "#id")
    @Operation(summary = "删除清洗规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:delete')")
    public CommonResult<Boolean> deleteCleanRule(@RequestParam("id") Long id) {
        cleanRuleService.deleteCleanRule(id);
        return success(true);
    }

    /**
     * 获得清洗规则。
     */
    @GetMapping("/get")
    @Operation(summary = "获得清洗规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:query')")
    public CommonResult<CleanRuleRespVO> getCleanRule(@RequestParam("id") Long id) {
        CleanRuleDO rule = cleanRuleService.getCleanRule(id);
        if (rule == null) {
            return success(null);
        }
        return success(fillRespVO(List.of(toRespVO(rule))).get(0));
    }

    /**
     * 获得清洗规则分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得清洗规则分页")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:query')")
    public CommonResult<PageResult<CleanRuleRespVO>> getCleanRulePage(@Valid CleanRulePageReqVO pageReqVO) {
        PageResult<CleanRuleDO> pageResult = cleanRuleService.getCleanRulePage(pageReqVO);
        PageResult<CleanRuleRespVO> result = new PageResult<>(
                pageResult.getList().stream().map(this::toRespVO).toList(), pageResult.getTotal());
        fillRespVO(result.getList());
        return success(result);
    }

    /**
     * 获得规则目录分类列表。
     */
    @GetMapping("/category-list")
    @Operation(summary = "获得规则目录分类列表", description = "平台共享目录，返回包含父级编号的平面分类列表")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:query')")
    public CommonResult<List<CategoryCleanRuleRespVO>> getCategoryList() {
        return success(BeanUtils.toBean(categoryCleanRuleService.getCategoryCleanRuleList(
                new CategoryCleanRuleListReqVO()), CategoryCleanRuleRespVO.class));
    }

    /**
     * 获得质量引擎清洗策略列表。
     */
    @GetMapping("/strategy-list")
    @Operation(summary = "获得质量引擎清洗策略列表")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:query')")
    public CommonResult<List<QualityStrategyRespVO>> getStrategyList() {
        List<QualityStrategyMeta> strategies = cleanFunctionRegistry.listStrategies();
        return success(strategies.stream()
                .map(meta -> new QualityStrategyRespVO(meta.getStrategyKey(), meta.getStrategyName(),
                        meta.getConfigExample()))
                .collect(Collectors.toList()));
    }

    /**
     * 在线测试清洗规则：对样例数据执行清洗函数并返回结果
     */
    @PostMapping("/test")
    @Operation(summary = "在线测试清洗规则")
    @PreAuthorize("@ss.hasPermission('governance:clean-rule:query')")
    public CommonResult<CleanRuleTestRespVO> testCleanRule(@Valid @RequestBody CleanRuleTestReqVO reqVO) {
        return success(cleanRuleService.testCleanRule(reqVO));
    }

    /**
     * 填充类目名称 / 创建人名称 / 引擎支持标记
     */
    private List<CleanRuleRespVO> fillRespVO(List<CleanRuleRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return list;
        }
        // 类目名称
        Set<Long> categoryIds = list.stream().map(CleanRuleRespVO::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (CollUtil.isNotEmpty(categoryIds)) {
            Map<Long, String> nameMap = categoryCleanRuleMapper.selectBatchIds(categoryIds).stream()
                    .collect(Collectors.toMap(CategoryCleanRuleDO::getId, CategoryCleanRuleDO::getCategoryName));
            list.forEach(vo -> vo.setCategoryName(nameMap.get(vo.getCategoryId())));
        }
        // 创建人名称
        Set<Long> userIds = list.stream().map(CleanRuleRespVO::getCreator)
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
        list.forEach(vo -> {
            vo.setEngineSupported(cleanFunctionRegistry.contains(vo.getStrategyKey()));
            vo.setRowTransformSupported(cleanFunctionRegistry.supportsRowTransform(vo.getStrategyKey()));
            var function = cleanFunctionRegistry.getFunction(vo.getStrategyKey());
            vo.setColumnBinding(function == null ? null : function.getColumnBinding().name());
        });
        return list;
    }

    /**
     * 转换响应VO。
     */
    private CleanRuleRespVO toRespVO(CleanRuleDO rule) {
        CleanRuleRespVO response = BeanUtils.toBean(rule, CleanRuleRespVO.class);
        if (StrUtil.isNotBlank(rule.getConfigJson())) {
            response.setConfig(JsonUtils.parseObject(rule.getConfigJson(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }));
        } else {
            response.setConfig(Map.of());
        }
        return response;
    }

}
