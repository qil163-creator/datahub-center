package cn.iocoder.yudao.module.governance.service.category;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rulever.SafetyRuleVerDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import tools.jackson.core.type.TypeReference;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 分类只允许保存自身的展示掩码，发布和执行继续复用既有版本服务。 */
@Service
@Validated
public class SafetyCategoryMaskService {

    @Resource
    private SafetyCategoryMapper categories;

    @Resource
    private SafetyRuleService rules;

    /**
     * 按请求标识读取安全分类脱敏详情。
     */
    public SafetyCategoryMaskRespVO get(Long id) {
        var category = categories.selectById(id);
        if (category == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        if (category.getRuleId() == null) {
            return null;
        }
        var rule = rules.getRule(category.getRuleId());
        var version = rule == null ? null : rules.getRuleVer(rule.getCurrentVerId());
        if (rule == null || version == null || !Objects.equals(version.getRuleId(), rule.getId())
                || !SafetyRuleVerDO.STATUS_PUBLISHED.equals(version.getStatus())
                || !Objects.equals(version.getConfigChecksum(), DigestUtil.sha256Hex(version.getConfigJson()))) {
            throw exception(SAFETY_MASK_INVALID, "已配置规则版本缺失或摘要不一致");
        }
        if (!"MASK".equals(rule.getMethodCode())) {
            throw exception(SAFETY_MASK_INVALID, "该规则不是展示掩码，须在脱敏规则管理中维护");
        }
        Map<String, Object> config = JsonUtils.parseObject(version.getConfigJson(), new TypeReference<>() {
        });
        var result = new SafetyCategoryMaskRespVO();
        result.setId(id);
        result.setRevision(category.getRevision());
        result.setRuleId(rule.getId());
        result.setRuleVersionId(version.getId());
        result.setRuleRevisionToken(SafetyRuleServiceImpl.revisionToken(rule));
        result.setRuleName(rule.getRuleName());
        result.setStatus(rule.getStatus());
        result.setDescription(rule.getDescription());
        result.setMaskType((String) config.get("maskType"));
        result.setReplaceRule((String) config.get("replaceRule"));
        result.setReplaceContent((String) config.get("maskChar"));
        result.setApplicationScene(config.get("applicationScene") instanceof List<?> scenes
                ? scenes.stream().map(String::valueOf).toList() : List.of());
        List<SafetyCategoryMaskReqVO.Interval> intervals = new ArrayList<>();
        if (config.get("intervals") instanceof List<?> ranges) {
            for (var raw : ranges) {
                if (!(raw instanceof Map<?, ?> range)) {
                    throw exception(SAFETY_MASK_INVALID, "区间格式错误");
                }
                var item = new SafetyCategoryMaskReqVO.Interval();
                item.setStartNum(Integer.valueOf(String.valueOf(range.get("start"))));
                item.setEndNum(Integer.valueOf(String.valueOf(range.get("end"))));
                intervals.add(item);
            }
        }
        result.setIntervalList(intervals);
        return result;
    }

    /**
     * 保存安全分类脱敏配置或业务记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(@Valid SafetyCategoryMaskReqVO request) {
        categories.lockDefinitions();
        var category = categories.selectForUpdate(request.getId());
        if (category == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        SafetyCategoryServiceImpl.checkRevision(request.getRevision(), category.getRevision());
        var save = new SafetyRuleSaveReqVO();
        save.setRuleName(request.getRuleName().trim());
        save.setMethodCode("MASK");
        save.setStatus(request.getStatus());
        save.setDescription(request.getDescription());
        save.setSort(category.getSort() == null ? 0 : category.getSort());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("intervals", request.getIntervalList().stream()
                .map(range -> Map.of("start", range.getStartNum(), "end", range.getEndNum())).toList());
        config.put("maskChar", request.getReplaceContent());
        config.put("applicationScene", request.getApplicationScene().stream().distinct().sorted().toList());
        config.put("maskType", request.getMaskType());
        config.put("replaceRule", request.getReplaceRule());
        save.setConfig(config);
        Long ruleId = category.getRuleId();
        if (ruleId == null) {
            ruleId = rules.createRule(save);
        } else {
            if (request.getRuleVersionId() == null || request.getRuleRevisionToken() == null) {
                throw exception(SAFETY_CATEGORY_CONFLICT);
            }
            if (categories.countByRule(ruleId) != 1) {
                throw exception(SAFETY_MASK_INVALID, "该规则被其他分类共享，须在脱敏规则管理中维护");
            }
            var current = rules.getRule(ruleId);
            if (current == null) {
                throw exception(RULE_NOT_EXISTS);
            }
            save.setSort(current.getSort());
            save.setId(ruleId);
            save.setExpectedVersionId(request.getRuleVersionId());
            save.setExpectedRevisionToken(request.getRuleRevisionToken());
            rules.updateRule(save);
        }
        if (categories.update(null, SafetyCategoryServiceImpl.changed(category).set(
                cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO::getRuleId, ruleId)) != 1) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
    }
}
