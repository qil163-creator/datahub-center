package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRuleSuggestionRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 由探查结果直接映射质量规则建议，纯函数、不访问数据库、不创建任何规则。
 *
 * <ul>
 *     <li>空值数为 0（抽样行数 &gt; 0）→ 非空（只证明了无 NULL，建议 allowBlank=true）；</li>
 *     <li>唯一值数 = 非空数且非空数 &gt; 1 → 唯一；</li>
 *     <li>1 ≤ 唯一值数 ≤ 20 且存在重复取值（排除日期时间）→ 枚举；取值只有在未脱敏且 Top 值覆盖全部取值时才带入；</li>
 *     <li>文本最小长度 = 最大长度 &gt; 0 → 长度。</li>
 * </ul>
 *
 * @author Li
 */
public final class ProfileRuleRecommender {

    /** 枚举建议的最大取值种数 */
    public static final int ENUM_MAX_DISTINCT = 20;

    private static final Set<String> ENUM_KINDS = Set.of("TEXT", "NUMERIC", "BOOLEAN");
    private static final Set<String> DISTINCT_KINDS = Set.of("TEXT", "NUMERIC", "TEMPORAL", "BOOLEAN");

    /**
     * 工具类不实例化。
     */
    private ProfileRuleRecommender() {
    }

    /**
     * 为一次成功探查的全部字段生成建议，顺序为字段顺序 × 非空/唯一/枚举/长度。
     *
     * @param sampleRows 抽样行数
     * @param columns    字段指标
     * @param values     Top 值，按字段指标编号分组
     */
    public static List<ProfileRuleSuggestionRespVO> recommend(long sampleRows, List<ProfileColumnDO> columns,
            Map<Long, List<ProfileValueDO>> values) {
        List<ProfileRuleSuggestionRespVO> result = new ArrayList<>();
        if (sampleRows <= 0) {
            return result;
        }
        for (ProfileColumnDO column : columns) {
            long nonNull = nz(column.getNonNullCount());
            Long distinct = column.getDistinctCount();
            if (nz(column.getNullCount()) == 0 && nonNull > 0) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("allowBlank", true);
                result.add(suggestion(column, "NOT_NULL", "NOT_NULL_VALIDATION", "COMPLETENESS", "非空校验",
                        "抽样 " + sampleRows + " 行中没有空值", params));
            }
            if (distinct != null && DISTINCT_KINDS.contains(column.getColumnKind()) && nonNull > 1
                    && distinct == nonNull) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("ignoreNull", true);
                result.add(suggestion(column, "UNIQUE", "UNIQUE_VALIDATION", "UNIQUENESS", "唯一校验",
                        "抽样内 " + nonNull + " 个非空值互不重复（唯一率 100%）", params));
            }
            if (distinct != null && ENUM_KINDS.contains(column.getColumnKind()) && distinct >= 1
                    && distinct <= ENUM_MAX_DISTINCT && distinct < nonNull) {
                List<ProfileValueDO> top = values.getOrDefault(column.getId(), List.of());
                boolean masked = Objects.equals(column.getValueMasked(), 1)
                        || top.stream().anyMatch(value -> Objects.equals(value.getValueMasked(), 1));
                boolean complete = !masked && top.size() == distinct;
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("validValues", complete ? top.stream().map(ProfileValueDO::getValueText).toList() : List.of());
                params.put("ignoreCase", false);
                params.put("ignoreNullValue", true);
                String reason = "抽样内只有 " + distinct + " 种取值" + (complete ? ""
                        : masked ? "；字段已脱敏，取值需人工补全" : "；Top 值未覆盖全部取值，需人工补全");
                result.add(suggestion(column, "ENUM", "ENUM_VALIDATION", "NORMALIZATION", "枚举值校验", reason,
                        params));
            }
            if ("TEXT".equals(column.getColumnKind()) && nonNull > 0 && column.getMinLength() != null
                    && column.getMinLength() > 0 && column.getMinLength().equals(column.getMaxLength())) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("minLength", column.getMinLength());
                params.put("maxLength", column.getMaxLength());
                params.put("ignoreNullValue", true);
                result.add(suggestion(column, "LENGTH", "LENGTH_VALIDATION", "NORMALIZATION", "长度校验",
                        "抽样内非空值长度均为 " + column.getMinLength() + " 个字符", params));
            }
        }
        return result;
    }

    /**
     * 组装单条建议。
     */
    private static ProfileRuleSuggestionRespVO suggestion(ProfileColumnDO column, String ruleType, String strategyKey,
            String dim, String label, String reason, Map<String, Object> params) {
        ProfileRuleSuggestionRespVO vo = new ProfileRuleSuggestionRespVO();
        vo.setColumnName(column.getColumnName());
        vo.setRuleType(ruleType);
        vo.setStrategyKey(strategyKey);
        vo.setQualityDim(dim);
        vo.setRuleName(column.getColumnName() + "-" + label);
        vo.setReason(reason);
        params.put("column", column.getColumnName());
        vo.setParams(params);
        return vo;
    }

    /**
     * 空值按 0 处理。
     */
    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

}
