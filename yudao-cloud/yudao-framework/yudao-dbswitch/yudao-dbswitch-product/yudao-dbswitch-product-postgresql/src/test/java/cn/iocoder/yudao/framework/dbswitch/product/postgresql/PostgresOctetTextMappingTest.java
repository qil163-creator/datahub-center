package cn.iocoder.yudao.framework.dbswitch.product.postgresql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/** 字节长度域必须由 PostgreSQL CHECK 保留，不能误改为字符长度。 */
class PostgresOctetTextMappingTest {
    private final PostgresLogicalTypeMapper mapper = new PostgresLogicalTypeMapper();

    @ParameterizedTest @CsvSource({"utf8mb4,false", "UNICODE,false", "utf8mb4,true", "UNICODE,true"})
    void finiteUnicodeNoPadOctetsRemainExactWithRuntimeEncodingAndByteChecks(String repertoire, boolean nul) {
        var result = map(new CharacterType(StorageKind.VARYING, 12L, LengthUnit.OCTETS, null,
                repertoire, Comparison.BINARY_NO_PAD, null, nul), false);
        assertEquals(TypeFidelity.EXACT, result.fidelity());
        assertEquals("text COLLATE \"C\"", result.value().typeSql());
        assertEquals("{column} IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length({column}) <= 12)", result.value().checkTemplate());
        assertEquals("\"note_\"\"'%\" IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length(\"note_\"\"'%\") <= 12)",
                result.value().check("\"note_\"\"'%\""));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("TARGET_ENCODING_UTF8_REQUIRED")));
        assertEquals(nul, result.issues().stream().anyMatch(i -> i.code().equals("NUL_CHARACTER_REJECTED_BY_TARGET")));
    }

    @ParameterizedTest @CsvSource({"FIXED,BINARY_NO_PAD,utf8mb4", "VARYING,BINARY_PAD_SPACE,utf8mb4",
            "VARYING,DETERMINISTIC_DEFAULT,utf8mb4", "VARYING,NAMED,utf8mb4", "VARYING,BINARY_NO_PAD,latin1"})
    void unprovenDomainsStayUnsupported(StorageKind kind, Comparison comparison, String repertoire) {
        var result = map(new CharacterType(kind, 12L, LengthUnit.OCTETS, null, repertoire,
                comparison, comparison == Comparison.NAMED ? "unverified" : null, false), false);
        assertEquals(TypeFidelity.UNSUPPORTED, result.fidelity()); assertNull(result.value());
    }

    @Test void textKeysLargeDomainsAndOversizedBoundsStayUnsupported() {
        assertEquals(TypeFidelity.UNSUPPORTED, map(text(12), true).fidelity());
        assertEquals(TypeFidelity.UNSUPPORTED, map(new CharacterType(StorageKind.LARGE, null, LengthUnit.OCTETS,
                2147483647L, "utf8mb4", Comparison.BINARY_NO_PAD, null, true), false).fidelity());
        assertEquals(TypeFidelity.UNSUPPORTED, map(text(1073741824L), false).fidelity());
        assertEquals(TypeFidelity.EXACT, map(text(1073741823L), false).fidelity());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE, names = {"DORIS", "STARROCKS"})
    void sourceRepertoireCannotGrantUnverifiedUtf8OctetSemantics(ProductTypeEnum product) {
        for (String repertoire : new String[]{"UNICODE", "utf8mb4"}) {
            var text = new CharacterType(StorageKind.VARYING, 12L, LengthUnit.OCTETS, null,
                    repertoire, Comparison.BINARY_NO_PAD, null, false);
            var result = mapper.toNative(new LogicalColumn("v", text, true, false, product, "varying(12 byte)"));
            assertEquals(TypeFidelity.UNSUPPORTED, result.fidelity()); assertNull(result.value());
            assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("TYPE_UNMAPPED")));
        }
    }

    @Test void existingCharacterUnitsStillUseCharacterTypmod() {
        var result = map(new CharacterType(StorageKind.VARYING, 12L, LengthUnit.CHARACTERS, null,
                "UNICODE", Comparison.BINARY_NO_PAD, null, false), false);
        assertEquals(TypeFidelity.EXACT, result.fidelity());
        assertEquals("character varying(12) COLLATE \"C\"", result.value().typeSql()); assertNull(result.value().checkTemplate());
    }

    private static CharacterType text(long length) {
        return new CharacterType(StorageKind.VARYING, length, LengthUnit.OCTETS, null,
                "utf8mb4", Comparison.BINARY_NO_PAD, null, false);
    }
    private TypeMappingResult<NativeColumnType> map(CharacterType text, boolean key) {
        return mapper.toNative(new LogicalColumn("note_\"'%", text, true, key, ProductTypeEnum.STARROCKS, "varchar(12)"));
    }
}
