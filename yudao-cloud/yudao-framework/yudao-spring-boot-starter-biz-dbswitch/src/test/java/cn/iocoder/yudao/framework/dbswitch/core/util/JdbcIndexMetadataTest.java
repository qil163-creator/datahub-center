package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.TableIndexEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexFieldMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** {@link JdbcIndexMetadata#collect} against mocked {@link DatabaseMetaData#getIndexInfo} rows. */
class JdbcIndexMetadataTest {
    private static final short OTHER = DatabaseMetaData.tableIndexOther;
    private static final short STATISTIC = DatabaseMetaData.tableIndexStatistic;

    private final ResultSet rows = mock(ResultSet.class);

    @Test void compositeKeysFollowOrdinalPositionNotDriverRowOrder() throws Exception {
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("idx_multi");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(true);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(2, 1, 3);
        when(rows.getString("COLUMN_NAME")).thenReturn("b", "a", "c");
        when(rows.getString("ASC_OR_DESC")).thenReturn("A", "A", "D");
        var indexes = JdbcIndexMetadata.collect(rows);
        assertEquals(1, indexes.size());
        var index = indexes.get(0);
        assertEquals("idx_multi", index.indexName());
        assertEquals(TableIndexEnum.NORMAL, index.indexType());
        assertFalse(index.unique());
        assertEquals(List.of("a", "b", "c"), index.fieldNames());
        assertEquals(List.of(1, 2, 3),
                index.indexFields().stream().map(IndexFieldMeta::ordinalPosition).toList());
        assertFalse(index.indexFields().get(0).descending());
        assertFalse(index.indexFields().get(1).descending());
        assertTrue(index.indexFields().get(2).descending());
        assertNull(index.filterCondition());
    }

    @Test void uniquenessAndMissingSortOrderBelongToEachIndex() throws Exception {
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("ux_a", "idx_b");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(false, true);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rows.getString("COLUMN_NAME")).thenReturn("a", "b");
        when(rows.getString("ASC_OR_DESC")).thenReturn(null, "D");
        var indexes = JdbcIndexMetadata.collect(rows);
        assertEquals(2, indexes.size());
        assertEquals(TableIndexEnum.UNIQUE, indexes.get(0).indexType());
        assertTrue(indexes.get(0).unique());
        assertNull(indexes.get(0).indexFields().get(0).ascending());
        assertFalse(indexes.get(0).indexFields().get(0).descending());
        assertEquals(TableIndexEnum.NORMAL, indexes.get(1).indexType());
        assertTrue(indexes.get(1).indexFields().get(0).descending());
    }

    @Test void interleavedRowsAreGroupedByIndexNameInFirstSeenOrder() throws Exception {
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("idx_1", "idx_2", "idx_1");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(true);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1, 1, 2);
        when(rows.getString("COLUMN_NAME")).thenReturn("a", "x", "b");
        var indexes = JdbcIndexMetadata.collect(rows);
        assertEquals(2, indexes.size());
        assertEquals("idx_1", indexes.get(0).indexName());
        assertEquals(List.of("a", "b"), indexes.get(0).fieldNames());
        assertEquals("idx_2", indexes.get(1).indexName());
        assertEquals(List.of("x"), indexes.get(1).fieldNames());
    }

    @Test void statisticRowsAreSkippedBeforeAnyFieldIsRead() throws Exception {
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getShort("TYPE")).thenReturn(STATISTIC, OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("idx_a");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(true);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rows.getString("COLUMN_NAME")).thenReturn("a");
        var indexes = JdbcIndexMetadata.collect(rows);
        assertEquals(1, indexes.size());
        assertEquals("idx_a", indexes.get(0).indexName());
        verify(rows, times(1)).getString("INDEX_NAME");
    }

    @Test void filterConditionColumnIsReadWhenPresentAndBlankValuesBecomeNull() throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("INDEX_NAME");
        when(metadata.getColumnLabel(2)).thenReturn("filter_condition");
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("idx_part", "idx_plain", "idx_blank");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(true);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rows.getString("COLUMN_NAME")).thenReturn("a", "b", "c");
        when(rows.getString("FILTER_CONDITION")).thenReturn("(deleted = false)", null, "   ");
        var indexes = JdbcIndexMetadata.collect(rows);
        assertEquals(3, indexes.size());
        assertEquals("(deleted = false)", indexes.get(0).filterCondition());
        assertNull(indexes.get(1).filterCondition());
        assertNull(indexes.get(2).filterCondition());
    }

    @Test void missingFilterConditionColumnIsNeverRead() throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("INDEX_NAME");
        when(metadata.getColumnLabel(2)).thenReturn("ASC_OR_DESC");
        when(rows.next()).thenReturn(true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("idx_a");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(true);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rows.getString("COLUMN_NAME")).thenReturn("a");
        var indexes = JdbcIndexMetadata.collect(rows);
        assertNull(indexes.get(0).filterCondition());
        verify(rows, never()).getString("FILTER_CONDITION");
    }

    @Test void expressionKeyKeepsNullFieldNameInsteadOfGuessingAColumn() throws Exception {
        when(rows.next()).thenReturn(true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn("idx_expr");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(false);
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rows.getString("COLUMN_NAME")).thenReturn(null);
        var indexes = JdbcIndexMetadata.collect(rows);
        assertEquals(1, indexes.size());
        assertNull(indexes.get(0).indexFields().get(0).fieldName());
        assertEquals(List.of(), indexes.get(0).fieldNames().stream().filter(java.util.Objects::nonNull).toList());
    }

    @ParameterizedTest @ValueSource(strings = {"blank-name", "null-name", "duplicate-ordinal", "ordinal-gap",
            "inconsistent-unique", "conflicting-filter"})
    void inconsistentIndexEvidenceIsRejectedInsteadOfSilentlyRepaired(String fault) throws Exception {
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getShort("TYPE")).thenReturn(OTHER);
        when(rows.getString("INDEX_NAME")).thenReturn(switch (fault) {
            case "blank-name" -> "";
            case "null-name" -> null;
            default -> "idx_a";
        }, "idx_a");
        when(rows.getBoolean("NON_UNIQUE")).thenReturn(true, !fault.equals("inconsistent-unique"));
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1, switch (fault) {
            case "duplicate-ordinal" -> 1;
            case "ordinal-gap" -> 3;
            default -> 2;
        });
        when(rows.getString("COLUMN_NAME")).thenReturn("a", "b");
        if (fault.equals("conflicting-filter")) {
            when(rows.getString("FILTER_CONDITION")).thenReturn("a = 1", "a = 2");
        }
        assertThrows(SQLException.class, () -> JdbcIndexMetadata.collect(rows));
    }
}
