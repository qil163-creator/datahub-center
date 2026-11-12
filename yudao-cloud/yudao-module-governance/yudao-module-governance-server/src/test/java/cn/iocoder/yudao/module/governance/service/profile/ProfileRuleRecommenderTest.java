package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRuleSuggestionRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推荐映射：空值率 0 → 非空，唯一率 100% → 唯一，≤20 种取值 → 枚举，长度稳定 → 长度；不创建规则。
 */
class ProfileRuleRecommenderTest {

    @Test
    void zeroNullRateSuggestsNotNull() {
        ProfileColumnDO id = column(1L, "id", "NUMERIC", 100, 100L);
        List<ProfileRuleSuggestionRespVO> result = ProfileRuleRecommender.recommend(100, List.of(id), Map.of());
        assertThat(result).extracting(ProfileRuleSuggestionRespVO::getRuleType).containsExactly("NOT_NULL", "UNIQUE");
        ProfileRuleSuggestionRespVO notNull = result.get(0);
        assertThat(notNull.getStrategyKey()).isEqualTo("NOT_NULL_VALIDATION");
        assertThat(notNull.getQualityDim()).isEqualTo("COMPLETENESS");
        assertThat(notNull.getParams()).containsEntry("allowBlank", true).containsEntry("column", "id");
        assertThat(result.get(1).getStrategyKey()).isEqualTo("UNIQUE_VALIDATION");
        assertThat(result.get(1).getParams()).containsEntry("ignoreNull", true);
    }

    @Test
    void nullsOrDuplicatesSuppressNotNullAndUnique() {
        ProfileColumnDO withNull = column(1L, "a", "NUMERIC", 99, 99L);
        ProfileColumnDO duplicated = column(2L, "b", "NUMERIC", 100, 50L);
        List<ProfileRuleSuggestionRespVO> result = ProfileRuleRecommender.recommend(100,
                List.of(withNull, duplicated), Map.of());
        assertThat(result).extracting(s -> s.getColumnName() + ":" + s.getRuleType())
                .containsExactly("a:UNIQUE", "b:NOT_NULL");
    }

    @Test
    void lowCardinalityWithCompleteTopValuesSuggestsEnumWithValues() {
        ProfileColumnDO status = column(3L, "status", "TEXT", 90, 2L);
        List<ProfileRuleSuggestionRespVO> result = ProfileRuleRecommender.recommend(100, List.of(status),
                Map.of(3L, List.of(value(3L, 1, "A", 0), value(3L, 2, "B", 0))));
        ProfileRuleSuggestionRespVO enumRule = only(result, "ENUM");
        assertThat(enumRule.getStrategyKey()).isEqualTo("ENUM_VALIDATION");
        assertThat(enumRule.getParams().get("validValues")).isEqualTo(List.of("A", "B"));
        assertThat(enumRule.getReason()).doesNotContain("人工补全");
    }

    @Test
    void enumValuesAreNotCarriedWhenMaskedOrIncomplete() {
        ProfileColumnDO masked = column(4L, "level", "TEXT", 100, 3L);
        masked.setValueMasked(1);
        ProfileColumnDO incomplete = column(5L, "code", "NUMERIC", 100, 15L);
        List<ProfileValueDO> top = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            top.add(value(5L, i, String.valueOf(i), 0));
        }
        List<ProfileRuleSuggestionRespVO> result = ProfileRuleRecommender.recommend(100, List.of(masked, incomplete),
                Map.of(4L, List.of(value(4L, 1, null, 1)), 5L, top));
        List<ProfileRuleSuggestionRespVO> enums = result.stream().filter(s -> s.getRuleType().equals("ENUM")).toList();
        assertThat(enums).hasSize(2).allSatisfy(s -> assertThat(s.getParams().get("validValues")).isEqualTo(List.of()));
        assertThat(enums.get(0).getReason()).contains("已脱敏");
        assertThat(enums.get(1).getReason()).contains("未覆盖");
    }

    @Test
    void enumBoundaryIsTwentyDistinctValuesAndExcludesTemporalAndUnique() {
        assertThat(ProfileRuleRecommender.recommend(100, List.of(column(1L, "a", "TEXT", 100, 20L)), Map.of()))
                .extracting(ProfileRuleSuggestionRespVO::getRuleType).contains("ENUM");
        assertThat(ProfileRuleRecommender.recommend(100, List.of(column(1L, "a", "TEXT", 100, 21L)), Map.of()))
                .extracting(ProfileRuleSuggestionRespVO::getRuleType).doesNotContain("ENUM");
        assertThat(ProfileRuleRecommender.recommend(100, List.of(column(1L, "d", "TEMPORAL", 100, 3L)), Map.of()))
                .extracting(ProfileRuleSuggestionRespVO::getRuleType).doesNotContain("ENUM");
        // 全部取值互不相同（如 5 行 5 值）只推荐唯一，不推荐枚举
        ProfileColumnDO key = column(1L, "k", "TEXT", 5, 5L);
        key.setNullCount(0L);
        assertThat(ProfileRuleRecommender.recommend(5, List.of(key), Map.of()))
                .extracting(ProfileRuleSuggestionRespVO::getRuleType).containsExactly("NOT_NULL", "UNIQUE");
    }

    @Test
    void stableTextLengthSuggestsLength() {
        ProfileColumnDO phone = column(6L, "phone", "TEXT", 80, 80L);
        phone.setMinLength(11L);
        phone.setMaxLength(11L);
        ProfileColumnDO name = column(7L, "name", "TEXT", 80, 70L);
        name.setMinLength(2L);
        name.setMaxLength(4L);
        List<ProfileRuleSuggestionRespVO> result = ProfileRuleRecommender.recommend(100, List.of(phone, name),
                Map.of());
        ProfileRuleSuggestionRespVO length = only(result, "LENGTH");
        assertThat(length.getColumnName()).isEqualTo("phone");
        assertThat(length.getStrategyKey()).isEqualTo("LENGTH_VALIDATION");
        assertThat(length.getParams()).containsEntry("minLength", 11L).containsEntry("maxLength", 11L);
    }

    @Test
    void otherKindsAndEmptySamplesProduceNoSuggestions() {
        ProfileColumnDO blob = column(8L, "doc", "OTHER", 100, null);
        assertThat(ProfileRuleRecommender.recommend(100, List.of(blob), Map.of()))
                .extracting(ProfileRuleSuggestionRespVO::getRuleType).containsExactly("NOT_NULL");
        assertThat(ProfileRuleRecommender.recommend(0, List.of(column(1L, "a", "TEXT", 0, 0L)), Map.of())).isEmpty();
    }

    private static ProfileRuleSuggestionRespVO only(List<ProfileRuleSuggestionRespVO> result, String type) {
        List<ProfileRuleSuggestionRespVO> matched = result.stream().filter(s -> s.getRuleType().equals(type)).toList();
        assertThat(matched).hasSize(1);
        return matched.get(0);
    }

    private static ProfileColumnDO column(Long id, String name, String kind, long nonNull, Long distinct) {
        ProfileColumnDO column = new ProfileColumnDO();
        column.setId(id);
        column.setColumnName(name);
        column.setColumnKind(kind);
        column.setNonNullCount(nonNull);
        column.setNullCount(100 - Math.min(100, nonNull));
        column.setDistinctCount(distinct);
        column.setValueMasked(0);
        return column;
    }

    private static ProfileValueDO value(Long columnId, int rank, String text, int masked) {
        ProfileValueDO value = new ProfileValueDO();
        value.setProfileColumnId(columnId);
        value.setRankNo(rank);
        value.setValueText(text);
        value.setValueCount(10L);
        value.setValueMasked(masked);
        return value;
    }

}
