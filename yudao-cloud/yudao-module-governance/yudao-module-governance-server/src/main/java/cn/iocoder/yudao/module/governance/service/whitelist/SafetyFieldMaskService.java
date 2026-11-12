package cn.iocoder.yudao.module.governance.service.whitelist;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.security.dto.*;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.service.rule.*;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 脱敏决策复用治理定义及引擎；调用方仍须先完成对象和字段读取授权。 */
@Service
@Validated
public class SafetyFieldMaskService {

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.governance.service.security.SafetyKeyCenterService keyCenter;


    @Resource
    private SafetyWhitelistService whitelists;

    @Resource
    private SafetyCategoryMapper categories;

    @Resource
    private SafetyRuleService rules;

    /**
     * 冻结当前用户与场景的分类脱敏策略，返回脱敏数据和策略指纹。
     */
    public SafetyFieldMaskRespDTO mask(@Valid SafetyFieldMaskReqDTO req) {
        var policy = prepare(req.getUserId(), req.getScene(), req.getFields(), req.getApplicationScene());
        var result = new SafetyFieldMaskRespDTO();
        result.setRows(apply(policy, req.getRows()));
        result.setPolicyFingerprint(policy.fingerprint());
        return result;
    }

    /**
     * 汇总字段分类、豁免范围与可用规则版本，生成可复核的脱敏策略快照。
     */
    public Policy prepare(Long userId, String scene, List<SafetyFieldMaskReqDTO.Field> fields) {
        return prepare(userId, scene, fields, "2");
    }

    /**
     * 汇总字段分类、豁免范围与可用规则版本，生成可复核的脱敏策略快照。
     */
    public Policy prepare(Long userId, String scene, List<SafetyFieldMaskReqDTO.Field> fields,
            String applicationScene) {
        var categoryIds = new TreeSet<Long>();
        var byField = new TreeMap<String, List<Long>>();
        for (var field : fields) {
            var ids = field.getCategoryIds().stream().distinct().sorted().toList();
            if (byField.putIfAbsent(field.getFieldName(), ids) != null) {
                throw exception(SAFETY_MASK_INVALID, "字段分类上下文重复");
            }
            categoryIds.addAll(ids);
        }
        if (categoryIds.size() > 1000) {
            throw exception(SAFETY_MASK_INVALID, "分类上下文超出有界大小");
        }
        var definitionMap = categoryIds.isEmpty() ? Map.<Long, cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO>of() : categories.selectByIds(
                        categoryIds).stream()
                .collect(java.util.stream.Collectors.toMap(v -> v.getId(), v -> v));
        if (!definitionMap.keySet().equals(categoryIds)) {
            throw exception(SAFETY_MASK_INVALID, "分类事实已变化，请重新读取");
        }
        var global = whitelists.getMaskExemptStrict(userId, scene);
        if (global == null || global.getExempt() == null) {
            throw exception(PERMISSION_SQL_PARSE_FAIL, "当前脱敏豁免无法核验");
        }
        var exempt = Boolean.TRUE.equals(global.getExempt()) ? Set.copyOf(
                categoryIds) : whitelists.getCategoryExemptionsStrict(userId, scene, categoryIds);
        if (exempt == null || !categoryIds.containsAll(exempt)) {
            throw exception(PERMISSION_SQL_PARSE_FAIL, "分类豁免无法核验");
        }
        var ruleIds = new TreeSet<Long>();
        for (var category : definitionMap.values()) {
            if (!exempt.contains(category.getId()) && category.getRuleId() != null) {
                ruleIds.add(category.getRuleId());
            }
        }
        var specs = ruleIds.isEmpty() ? Map.<Long, SafetyMaskingSpec>of() : rules.freezeUsableMaskingSpecs(ruleIds);
        if (specs == null) {
            throw exception(RULE_VERSION_UNAVAILABLE);
        }
        var ignored = new HashSet<Long>();
        for (var category : definitionMap.values()) {
            var spec = category.getRuleId() == null ? null : specs.get(category.getRuleId());
            if (spec != null && spec.getConfig().get("applicationScene") instanceof List<?> scenes && !scenes.contains(
                    applicationScene)) {
                ignored.add(category.getId());
            }
        }
        var material = new TreeMap<String, Object>();
        material.put("semantics", "CLASSIFIED_FIELD_MASK_V1");
        material.put("user", userId);
        material.put("scene", scene);
        material.put("applicationScene", applicationScene);
        material.put("fields", byField);
        material.put("exempt", exempt.stream().sorted().toList());
        material.put("definitions",
                definitionMap.values().stream().sorted(Comparator.comparing(v -> v.getId())).map(v -> {
                    var data = new TreeMap<String, Object>();
                    data.put("id", v.getId());
                    data.put("revision", v.getRevision());
                    data.put("rule", v.getRuleId());
                    return data;
                }).toList());
        material.put("versions",
                specs.values().stream().sorted(Comparator.comparing(SafetyMaskingSpec::getRuleId)).map(v -> {
                    var data = new TreeMap<String, Object>();
                    data.put("rule", v.getRuleId());
                    data.put("version", v.getVersionId());
                    return data;
                }).toList());
        return new Policy(byField, definitionMap, specs, exempt, Set.copyOf(ignored),
                cn.hutool.crypto.digest.DigestUtil.sha256Hex(JsonUtils.toJsonString(material)));
    }

    /**
     * 逐行复制数据并按冻结分类策略处理字段，规则不可用时使用失败关闭掩码。
     */
    public List<Map<String, Object>> apply(Policy policy, List<Map<String, Object>> rows) {
        var processed = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            if (row.size() > 1000) {
                throw exception(SAFETY_MASK_INVALID, "数据列超过有界大小");
            }
            var output = new LinkedHashMap<String, Object>(row);
            for (var entry : policy.fields().entrySet()) {
                if (!row.containsKey(entry.getKey())) {
                    continue;
                }
                Object value = row.get(entry.getKey());
                for (Long id : entry.getValue()) {
                    if (policy.exempt().contains(id) || policy.ignored().contains(id)) {
                        continue;
                    }
                    var category = policy.definitions().get(id);
                    var spec = category.getRuleId() == null ? null : policy.specs().get(category.getRuleId());
                    value = maskOrUnavailable(value, spec);
                }
                output.put(entry.getKey(), value);
            }
            processed.add(output);
        }
        return processed;
    }

    /**
     * 按冻结规则处理单值；规则缺失或执行失败（如密钥引用停用、主密钥未配置）时用不可逆掩码，绝不回退原值。
     */
    private Object maskOrUnavailable(Object value, SafetyMaskingSpec spec) {
        if (spec == null) {
            return SafetyMaskEngine.unavailableMask(value);
        }
        try {
            return SafetyMaskEngine.mask(value, spec.getMethodCode(), spec.getConfig(), spec.getReplaceItems(),
                    keyCenter == null ? null : keyCenter::encrypt);
        } catch (RuntimeException unavailable) {
            return SafetyMaskEngine.unavailableMask(value);
        }
    }

    public record Policy(Map<String, List<Long>> fields,
                         Map<Long, cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO> definitions,
                         Map<Long, SafetyMaskingSpec> specs, Set<Long> exempt, Set<Long> ignored, String fingerprint) {

        /**
         * 判断指定字段的有效分类策略是否引用目标脱敏规则。
         */
        public boolean represents(String field, Long ruleId) {
            return ruleId != null && fields.getOrDefault(field, List.of()).stream()
                    .anyMatch(id -> !ignored.contains(id) && Objects.equals(definitions.get(id).getRuleId(), ruleId));
        }
    }
}
