package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.TableIndexEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexFieldMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link MigrationIndexes#map}: source index keys renamed through the column mapping, whole-index skips. */
class MigrationIndexesTest {

    @Test void keysAreRenamedPreservingOrderDirectionAndUniqueness() {
        var plan = MigrationIndexes.map(List.of(
                        new IndexDescription("idx_ab", TableIndexEnum.NORMAL, List.of(
                                new IndexFieldMeta("colA", 1, Boolean.TRUE),
                                new IndexFieldMeta("colB", 2, Boolean.FALSE)), null),
                        new IndexDescription("ux_a", TableIndexEnum.UNIQUE, List.of(
                                new IndexFieldMeta("colA", 1, null)), null)),
                Map.of("colA", "a1", "colB", "b1"));
        assertEquals(List.of(), plan.skipped());
        assertEquals(2, plan.indexes().size());
        var composite = plan.indexes().get(0);
        assertEquals("idx_ab", composite.indexName());
        assertEquals(TableIndexEnum.NORMAL, composite.indexType());
        assertEquals(List.of("a1", "b1"), composite.fieldNames());
        assertEquals(List.of(1, 2),
                composite.indexFields().stream().map(IndexFieldMeta::ordinalPosition).toList());
        assertFalse(composite.indexFields().get(0).descending());
        assertTrue(composite.indexFields().get(1).descending());
        assertNull(composite.filterCondition());
        var unique = plan.indexes().get(1);
        assertEquals(TableIndexEnum.UNIQUE, unique.indexType());
        assertTrue(unique.unique());
        assertNull(unique.indexFields().get(0).ascending());
    }

    @Test void partialIndexIsSkippedWithItsReason() {
        var plan = MigrationIndexes.map(List.of(
                        new IndexDescription("idx_part", TableIndexEnum.UNIQUE, List.of(
                                new IndexFieldMeta("colA", 1, Boolean.TRUE)), "deleted = 0")),
                Map.of("colA", "a1"));
        assertEquals(List.of(), plan.indexes());
        assertEquals(List.of("idx_part：部分索引的条件不能随普通索引重建"), plan.skipped());
    }

    @Test void unmappedOrEmptyMappedKeyDropsTheWholeIndex() {
        var plan = MigrationIndexes.map(List.of(
                        new IndexDescription("idx_mix", TableIndexEnum.NORMAL, List.of(
                                new IndexFieldMeta("colA", 1, Boolean.TRUE),
                                new IndexFieldMeta("colX", 2, Boolean.TRUE)), null),
                        new IndexDescription("idx_empty", TableIndexEnum.NORMAL, List.of(
                                new IndexFieldMeta("colE", 1, null)), null)),
                Map.of("colA", "a1", "colE", ""));
        assertEquals(List.of(), plan.indexes());
        assertEquals(List.of(
                "idx_mix：键 colX 不是源表列（函数/表达式键）或已被列映射排除",
                "idx_empty：键 colE 不是源表列（函数/表达式键）或已被列映射排除"), plan.skipped());
    }

    @Test void expressionKeyWithNullFieldNameIsSkipped() {
        var plan = MigrationIndexes.map(List.of(
                        new IndexDescription("idx_expr", TableIndexEnum.UNIQUE, List.of(
                                new IndexFieldMeta(null, 1, null)), null)),
                Map.of("colA", "a1"));
        assertEquals(List.of(), plan.indexes());
        assertEquals(List.of("idx_expr：键 null 不是源表列（函数/表达式键）或已被列映射排除"), plan.skipped());
    }

    @Test void blankFilterConditionIsCanonicalizedToNullAndTheIndexIsRetained() {
        var plan = MigrationIndexes.map(List.of(
                        new IndexDescription("idx_blank", TableIndexEnum.NORMAL, List.of(
                                new IndexFieldMeta("colA", 1, null)), "   ")),
                Map.of("colA", "a1"));
        assertEquals(List.of(), plan.skipped());
        assertEquals(1, plan.indexes().size());
        assertNull(plan.indexes().get(0).filterCondition());
        assertEquals(List.of("a1"), plan.indexes().get(0).fieldNames());
    }
}
