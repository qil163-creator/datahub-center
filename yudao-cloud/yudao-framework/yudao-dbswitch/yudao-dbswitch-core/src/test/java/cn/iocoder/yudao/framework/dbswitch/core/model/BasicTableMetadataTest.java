package cn.iocoder.yudao.framework.dbswitch.core.model;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BasicTableMetadataTest {
    @Test void freezingDoesNotMutateColumnsOrMarkPrimaryKeys() {
        var column = new ColumnDescription(); column.setFieldName("id"); column.setDefaultValue("original");
        var indexes = new ArrayList<>(List.of("existing_index")); column.setIndexes(indexes);
        var columns = new ArrayList<>(List.of(column)); var keys = new ArrayList<>(List.of("id"));
        var metadata = new BasicTableMetadata(columns, keys);
        assertFalse(column.isPk()); assertFalse(metadata.columns().getFirst().isPk());
        assertEquals("original", column.getDefaultValue());
        column.setFieldName("changed"); indexes.clear(); columns.clear(); keys.clear();
        assertEquals("id", metadata.columns().getFirst().getFieldName());
        assertEquals(List.of("existing_index"), metadata.columns().getFirst().getIndexes());
        assertEquals(List.of("id"), metadata.primaryKeys());
        assertThrows(UnsupportedOperationException.class, () -> metadata.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> metadata.primaryKeys().clear());
    }

    @Test void aCallerCannotModifyAStoredColumnOrNestedIndexList() {
        var column = new ColumnDescription(); column.setFieldName("id"); column.setIndexes(new ArrayList<>(List.of("i")));
        var metadata = new BasicTableMetadata(List.of(column), List.of("id"));
        var returned = metadata.columns().getFirst(); returned.setPk(true); returned.setFieldName("changed");
        returned.getIndexes().clear();
        assertEquals("id", metadata.columns().getFirst().getFieldName());
        assertFalse(metadata.columns().getFirst().isPk());
        assertEquals(List.of("i"), metadata.columns().getFirst().getIndexes());
    }

    @Test void aKeylessReadIsExplicitButUnknownOrNullResultsCannotBecomeEmpty() {
        assertTrue(new BasicTableMetadata(List.of(), List.of()).primaryKeys().isEmpty());
        assertThrows(NullPointerException.class, () -> new BasicTableMetadata(null, List.of()));
        assertThrows(NullPointerException.class, () -> new BasicTableMetadata(List.of(), null));
        assertThrows(NullPointerException.class, () -> new BasicTableMetadata(java.util.Arrays.asList((ColumnDescription)null), List.of()));
    }
}
