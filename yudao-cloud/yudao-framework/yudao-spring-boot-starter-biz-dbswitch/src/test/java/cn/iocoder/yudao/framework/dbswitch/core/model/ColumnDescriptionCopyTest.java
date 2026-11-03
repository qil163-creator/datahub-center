package cn.iocoder.yudao.framework.dbswitch.core.model;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class ColumnDescriptionCopyTest {

    @Test
    void mappedCopyShouldKeepExplicitTypeAndPrecisionOverrides() {
        ColumnDescription source = new ColumnDescription();
        source.setFieldName("source_amount");
        source.setFieldType(Types.VARCHAR);
        source.setDisplaySize(255);
        source.setValType(ColumnMetaData.TYPE_BIGNUMBER);
        source.setLength(38);
        source.setPrecision(12);
        source.setDefaultValue("0");
        source.setNullable(true);

        ColumnDescription target = source.copy();
        target.setFieldName("target_amount");
        ColumnMetaData metadata = target.getMetaData();

        assertEquals("source_amount", source.getFieldName());
        assertEquals("target_amount", metadata.getName());
        assertEquals(ColumnMetaData.TYPE_BIGNUMBER, metadata.getType());
        assertEquals(38, metadata.getLength());
        assertEquals(12, metadata.getPrecision());
        assertEquals(source.getDefaultValue(), metadata.getDefaultValue());
        assertEquals(source.isNullable(), metadata.isNullable());
    }

    @Test
    void sourceAndTargetIndexesShouldNotShareMutableState() {
        ColumnDescription source = new ColumnDescription();
        source.setIndexes(new ArrayList<>(List.of("source_index")));
        ColumnDescription target = source.copy();

        assertNotSame(source.getIndexes(), target.getIndexes());
        target.getIndexes().clear();
        target.getIndexes().add("target_index");
        assertEquals(List.of("source_index"), source.getIndexes());
        source.getIndexes().add("another_source_index");
        assertEquals(List.of("target_index"), target.getIndexes());
    }

    @Test
    void absentOptionalMetadataShouldStayAbsentAndIndexesIndependent() {
        ColumnDescription source = new ColumnDescription();
        source.setLabelName("fallback_name");
        ColumnDescription target = source.copy();

        assertEquals("fallback_name", target.getFieldName());
        assertNull(target.getValType());
        assertNull(target.getLength());
        assertNull(target.getPrecision());
        target.getIndexes().add("target_index");
        assertEquals(List.of(), source.getIndexes());
    }
}
