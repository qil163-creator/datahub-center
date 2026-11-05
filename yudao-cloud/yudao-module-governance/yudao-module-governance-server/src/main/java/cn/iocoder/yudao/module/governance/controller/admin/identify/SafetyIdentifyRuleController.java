package cn.iocoder.yudao.module.governance.controller.admin.identify;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyIdentifyRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyIdentifyRuleRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyIdentifyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.service.identify.SafetyIdentifyService;
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

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * 管理后台 - 敏感数据识别规则
 *
 * @author Li
 */
@Tag(name = "管理后台 - 敏感数据识别规则")
@RestController
@RequestMapping("/governance/safety-identify-rule")
@Validated
public class SafetyIdentifyRuleController {

    @Resource
    private SafetyIdentifyService identifyService;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private SafetyLevelMapper levelMapper;

    /**
     * 创建敏感识别规则。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-敏感数据识别规则", subType = "创建敏感识别规则",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建敏感识别规则",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建敏感识别规则")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:create')")
    public CommonResult<Long> createRule(@Valid @RequestBody SafetyIdentifyRuleSaveReqVO createReqVO) {
        return success(identifyService.createRule(createReqVO));
    }

    /**
     * 更新敏感识别规则。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-敏感数据识别规则", subType = "更新敏感识别规则",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新敏感识别规则",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新敏感识别规则")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:update')")
    public CommonResult<Boolean> updateRule(@Valid @RequestBody SafetyIdentifyRuleSaveReqVO updateReqVO) {
        identifyService.updateRule(updateReqVO);
        return success(true);
    }

    /**
     * 删除敏感识别规则。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-敏感数据识别规则", subType = "删除敏感识别规则",
            bizNo = "{{(#id) ?: 0}}", success = "删除敏感识别规则",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除敏感识别规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:delete')")
    public CommonResult<Boolean> deleteRule(@RequestParam("id") Long id) {
        identifyService.deleteRule(id);
        return success(true);
    }

    /**
     * 获得敏感识别规则。
     */
    @GetMapping("/get")
    @Operation(summary = "获得敏感识别规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:query')")
    public CommonResult<SafetyIdentifyRuleRespVO> getRule(@RequestParam("id") Long id) {
        SafetyIdentifyRuleDO rule = identifyService.getRule(id);
        return success(fillNames(List.of(BeanUtils.toBean(rule, SafetyIdentifyRuleRespVO.class))).get(0));
    }

    /**
     * 获得敏感识别规则分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得敏感识别规则分页")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:query')")
    public CommonResult<PageResult<SafetyIdentifyRuleRespVO>> getRulePage(
            @Valid SafetyIdentifyRulePageReqVO pageReqVO) {
        PageResult<SafetyIdentifyRuleDO> pageResult = identifyService.getRulePage(pageReqVO);
        PageResult<SafetyIdentifyRuleRespVO> result = BeanUtils.toBean(pageResult, SafetyIdentifyRuleRespVO.class);
        fillNames(result.getList());
        return success(result);
    }

    /**
     * 填充分类/分级名称
     */
    private List<SafetyIdentifyRuleRespVO> fillNames(List<SafetyIdentifyRuleRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return list;
        }
        List<Long> categoryIds = convertList(list, SafetyIdentifyRuleRespVO::getCategoryId);
        Map<Long, SafetyCategoryDO> categoryMap = CollUtil.isEmpty(categoryIds) ? Map.of()
                : convertMap(categoryMapper.selectByIds(categoryIds), SafetyCategoryDO::getId);
        Map<Long, SafetyLevelDO> levelMap = convertMap(levelMapper.selectList(), SafetyLevelDO::getId);
        list.forEach(vo -> {
            SafetyCategoryDO category = categoryMap.get(vo.getCategoryId());
            Long effectiveLevelId = vo.getLevelId();
            if (category != null) {
                vo.setCategoryName(category.getName());
                if (effectiveLevelId == null && category.getDataLevelId() != null) {
                    effectiveLevelId = category.getDataLevelId().longValue();
                }
            }
            SafetyLevelDO level = effectiveLevelId == null ? null : levelMap.get(effectiveLevelId);
            if (level != null) {
                vo.setLevelName(level.getSafetyName());
            }
        });
        return list;
    }

}
