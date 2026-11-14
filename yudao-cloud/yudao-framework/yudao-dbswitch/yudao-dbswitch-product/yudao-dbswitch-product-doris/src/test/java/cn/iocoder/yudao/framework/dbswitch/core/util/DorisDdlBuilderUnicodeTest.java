package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.DorisTableDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 按字符输入的字段长度须容纳完整 Unicode，超界改为 STRING 而非截断声明。 */
class DorisDdlBuilderUnicodeTest {
    @Test void supplementaryCharactersReceiveFourBytesPerCharacter() {
        assertEquals("VARCHAR(4)", type(1));
        assertEquals("VARCHAR(400)", type(100));
        assertEquals(400, "😀".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test void varcharBoundaryDoesNotClampOrOverflow() {
        assertEquals("VARCHAR(65532)", type(16383));
        assertEquals("STRING", type(16384));
        assertEquals("STRING", type(Integer.MAX_VALUE));
        assertEquals("VARCHAR(1020)", type(null));
    }

    private static String type(Integer characters) {
        return DorisDdlBuilder.dorisColumnType(DorisTableDefinition.Column.builder().name("note")
                .typeName("varchar").length(characters).nullable(true).build());
    }
}
