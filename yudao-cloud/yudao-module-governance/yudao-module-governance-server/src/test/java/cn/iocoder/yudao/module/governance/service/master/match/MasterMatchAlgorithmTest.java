package cn.iocoder.yudao.module.governance.service.master.match;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static cn.iocoder.yudao.module.governance.service.master.match.MasterMatchConfig.Algorithm.*;
import static cn.iocoder.yudao.module.governance.service.master.match.MasterMatchConfig.Survivorship.*;
import static org.junit.jupiter.api.Assertions.*;

/** 主数据相似匹配算法、疑似重复聚类与黄金记录取值规则 */
class MasterMatchAlgorithmTest {

    @Test
    void normalizationIgnoresWidthCaseSpacingAndPunctuation() {
        assertEquals("abc 公司", MasterSimilarity.normalizeExact("  ＡＢＣ   公司 "));
        assertEquals("abc有限公司", MasterSimilarity.normalizeText("ＡＢＣ（有限）公司。"));
        assertTrue(MasterSimilarity.blank("　 "));
        assertTrue(MasterSimilarity.blank(null));
        assertFalse(MasterSimilarity.blank(0));
    }

    @Test
    void textSimilarityAlgorithmsMatchKnownValues() {
        assertEquals(0.9611, MasterSimilarity.jaroWinkler("martha", "marhta"), 1e-4);
        assertEquals(0.8133, MasterSimilarity.jaroWinkler("dixon", "dicksonx"), 1e-4);
        assertEquals(1, MasterSimilarity.jaroWinkler("同", "同"));
        assertEquals(0, MasterSimilarity.jaroWinkler("", "a"));
        assertEquals(1 - 3.0 / 7, MasterSimilarity.levenshtein("kitten", "sitting"), 1e-9);
        assertEquals(1 - 1.0 / 6, MasterSimilarity.levenshtein("北京市海淀区", "北京海淀区"), 1e-9);
        assertEquals(0, MasterSimilarity.levenshtein("", ""));
        assertEquals(1, MasterSimilarity.score(TEXT, "华为技术有限公司", "华为技术（有限）公司"));
        assertEquals(1, MasterSimilarity.score(EXACT, "ABC", " abc "));
    }

    @Test
    void numericSimilarityIsRelativeAndFallsBackToExact() {
        assertEquals(1, MasterSimilarity.numeric("100.0", "100"));
        assertEquals(0.9, MasterSimilarity.numeric("100", "90"), 1e-9);
        assertEquals(0, MasterSimilarity.numeric("-1", "1"));
        assertEquals(1, MasterSimilarity.numeric("N/A", "n/a"));
        assertEquals(0, MasterSimilarity.numeric("N/A", "1"));
    }

    private static MasterMatchConfig config(List<String> blocking, String threshold,
            MasterMatchConfig.Field... fields) {
        return new MasterMatchConfig(List.of(fields), blocking, new BigDecimal(threshold), Map.of(), 1000);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void transitivePairsFormOneGroupAndBlockingLimitsComparisons() {
        List<Map<String, Object>> rows = List.of(
                row("name", "华为技术有限公司", "city", "深圳"),
                row("name", "华为技术有限公司深圳", "city", "深圳"),
                row("name", "华为技术有限公司深圳分", "city", "深圳"),
                row("name", "华为技术有限公司", "city", "北京"),
                row("name", "腾讯科技", "city", "深圳"),
                row("name", "华为技术有限公司", "city", null));
        var cfg = config(List.of("city"), "0.9", new MasterMatchConfig.Field("name", TEXT, 1));
        var result = MasterMatcher.match(rows, cfg);
        assertEquals(1, result.groups().size());
        assertEquals(List.of(0, 1, 2), result.groups().getFirst().members());
        assertEquals(6, result.comparedPairs(), "深圳块 4 条两两比较，北京块 1 条不比较");
        assertEquals(1, result.unblockedRows(), "分块字段为空的记录不参与比较");
        assertTrue(result.groups().getFirst().maxScore() >= 0.9);

        var unblocked = MasterMatcher.match(rows, config(List.of(), "0.99", new MasterMatchConfig.Field("name", TEXT, 1)));
        assertEquals(List.of(0, 3, 5), unblocked.groups().getFirst().members());
        assertEquals(15, unblocked.comparedPairs());
    }

    @Test
    void weightedScoreSkipsBlankFieldsButRequiresHalfOfTheWeight() {
        var cfg = config(List.of(), "0.8", new MasterMatchConfig.Field("name", TEXT, 3),
                new MasterMatchConfig.Field("phone", EXACT, 1), new MasterMatchConfig.Field("tax", EXACT, 2));
        List<Map<String, Object>> rows = List.of(
                row("name", "上海某某贸易", "phone", "123", "tax", null),
                row("name", "上海某某贸易", "phone", "456", "tax", "T1"),
                row("name", null, "phone", "123", "tax", "T1"));
        var pair = MasterMatcher.score(0, 1, rows, cfg, 6);
        assertNotNull(pair);
        assertEquals(0.75, pair.score(), 1e-9, "tax 一侧为空不计入：(3*1 + 1*0) / 4");
        assertEquals(Set.of("name", "phone"), pair.fieldScores().keySet());
        assertNull(MasterMatcher.score(0, 2, rows, cfg, 6), "只有 phone 可比，权重 1 不足总权重 6 的一半");
        assertTrue(MasterMatcher.match(rows, cfg).groups().isEmpty());
    }

    @Test
    void oversizedClustersAndTooManyComparisonsAreRejected() {
        List<Map<String, Object>> same = new ArrayList<>();
        for (int i = 0; i < MasterMatchConfig.MAX_GROUP_SIZE + 1; i++) {
            same.add(row("name", "同名"));
        }
        var result = MasterMatcher.match(same, config(List.of(), "0.9", new MasterMatchConfig.Field("name", EXACT, 1)));
        assertTrue(result.groups().isEmpty());
        assertEquals(1, result.oversizedClusters());

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 3200; i++) {
            many.add(row("name", "n" + i));
        }
        var cfg = config(List.of(), "0.9", new MasterMatchConfig.Field("name", EXACT, 1));
        assertEquals(3200L * 3199 / 2, MasterMatcher.estimateComparisons(many, cfg));
        assertThrows(IllegalArgumentException.class, () -> MasterMatcher.match(many, cfg));
    }

    @Test
    void survivorshipPicksValuesFromMembersByRule() {
        List<Map<String, Object>> members = List.of(
                row("id", "1", "name", "华为", "address", null, "tel", "111", "amount", "10", "grade", "A"),
                row("id", "2", "name", "华为技术有限公司", "address", "深圳坂田", "tel", "222", "amount", "30", "grade", "B"),
                row("id", "3", "name", "华为技术", "address", "深圳", "tel", "222", "amount", "20", "grade", "B"));
        List<String> fields = List.of("id", "name", "address", "tel", "amount", "grade");
        assertEquals(1, MasterSurvivorship.survivor(members, fields), "前两条同样完整时取靠前的第二条（第一条地址为空）");
        var suggestion = MasterSurvivorship.suggest(members, fields, Set.of("id"),
                Map.of("name", MOST_COMPLETE, "tel", MOST_FREQUENT, "amount", MIN, "grade", MAX, "id", MAX), 0);
        assertEquals(0, suggestion.survivor());
        assertEquals("1", suggestion.values().get("id"), "主键始终取保留记录");
        assertEquals("华为技术有限公司", suggestion.values().get("name"));
        assertEquals("深圳坂田", suggestion.values().get("address"), "保留记录为空时按组内顺序补全");
        assertEquals(1, suggestion.sources().get("address"));
        assertEquals("222", suggestion.values().get("tel"));
        assertEquals("10", suggestion.values().get("amount"));
        assertEquals("B", suggestion.values().get("grade"));
        assertEquals(1, suggestion.sources().get("grade"), "并列最大时保留记录优先，其次按组内顺序");
        assertThrows(IllegalArgumentException.class,
                () -> MasterSurvivorship.suggest(members, fields, Set.of("id"), Map.of(), 5));
    }

}
