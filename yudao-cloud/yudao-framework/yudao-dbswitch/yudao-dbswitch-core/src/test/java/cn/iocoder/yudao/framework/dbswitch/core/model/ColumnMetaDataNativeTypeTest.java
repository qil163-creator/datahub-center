package cn.iocoder.yudao.framework.dbswitch.core.model;

import org.junit.jupiter.api.Test;
import java.sql.Types;
import static org.junit.jupiter.api.Assertions.*;

class ColumnMetaDataNativeTypeTest {
    @Test void constructorAndDescriptionCopyRetainRawNativeSpellingWithoutChangingJdbcFacts() {
        var description=new ColumnDescription();description.setFieldName("at");description.setFieldType(Types.TIMESTAMP);
        description.setFieldTypeName("timestamptz");description.setPrecision(6);description.setSigned(false);
        var metadata=description.getMetaData();
        assertEquals("timestamptz",metadata.getNativeTypeName());assertEquals(Types.TIMESTAMP,metadata.getSqlType());
        assertEquals(ColumnMetaData.TYPE_TIMESTAMP,metadata.getType());
        assertEquals("timestamptz",description.copy().getMetaData().getNativeTypeName());
        description.setFieldTypeName("timestamp");assertEquals("timestamptz",metadata.getNativeTypeName());
        assertEquals("timestamp",description.getMetaData().getNativeTypeName());
    }
}
