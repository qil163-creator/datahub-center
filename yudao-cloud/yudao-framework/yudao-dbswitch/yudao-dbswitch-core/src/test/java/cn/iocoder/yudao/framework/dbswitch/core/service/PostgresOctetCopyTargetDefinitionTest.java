package cn.iocoder.yudao.framework.dbswitch.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 只准入组件生成的有限 UTF8 字节域，不向任意 CHECK 或函数开放 COPY。 */
class PostgresOctetCopyTargetDefinitionTest {
    @ParameterizedTest @ValueSource(longs = {1, 12, 65533, 1073741823})
    void generatedFixedTextDomainCanBeInspectedBeforeTargetReplacement(long bound) {
        assertDoesNotThrow(() -> validate("text COLLATE \"C\" CHECK(" + predicate("note", bound) + ")"));
    }
    @Test void quotedColumnIsMatchedPhysicallyInEveryReference() {
        var name = "note_\"'%"; var quoted = "\"note_\"\"'%\"";
        assertDoesNotThrow(() -> PostgresCopyTargetDefinition.validate("s", "rows", List.of(
                "CREATE TABLE s.rows(" + quoted + " text COLLATE \"C\" CHECK(" + predicate(quoted, 12) + "))"), List.of(name)));
    }
    @ParameterizedTest @ValueSource(strings = {
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=0)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=-1)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=1073741824)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)>12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<='12')",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=6+6)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(other)<=12)",
            "other IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NOT NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('client_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='LATIN1' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding',true)='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting(note)='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note,12)<=12)",
            "note IS NULL OR (private_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND private_length(note)<=12)",
            "note IS NULL OR (public.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND public.octet_length(note)<=12)",
            "note IS NULL OR (current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting($$server_encoding$$)='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting(DISTINCT 'server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding') OVER ()='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(rows.note)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note::text)<=12)",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' OR pg_catalog.octet_length(note)<=12)",
            "note IS NULL AND (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12)",
            "(pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12) OR note IS NULL",
            "note IS NULL OR (pg_catalog.octet_length(note)<=12 AND pg_catalog.current_setting('server_encoding')='UTF8')",
            "pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12",
            "note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(note)<=12 AND private_predicate(note))"
    })
    void nonCanonicalDomainsRemainRejectedWithoutCallerSqlInErrors(String check) {
        var rejected = assertThrows(IllegalArgumentException.class, () -> validate("text COLLATE \"C\" CHECK(" + check + ")"));
        assertNull(rejected.getCause()); assertFalse(rejected.getMessage().contains("private_"));
    }
    @ParameterizedTest @ValueSource(strings = {"text", "text COLLATE \"POSIX\"", "varchar(12) COLLATE \"C\"",
            "text COLLATE pg_catalog.\"C\"", "text COLLATE \"C\" CONSTRAINT private_check"})
    void typeOrCollationOrNamedConstraintCannotReplaceTheGeneratedDomain(String type) {
        assertThrows(IllegalArgumentException.class, () -> validate(type + " CHECK(" + predicate("note", 12) + ")"));
    }
    @Test void tableChecksAndAdditionalColumnChecksAreNotGranted() {
        assertThrows(IllegalArgumentException.class, () -> PostgresCopyTargetDefinition.validate("s", "rows", List.of(
                "CREATE TABLE s.rows(note text COLLATE \"C\",CHECK(" + predicate("note", 12) + "))"), List.of("note")));
        assertThrows(IllegalArgumentException.class, () -> validate("text COLLATE \"C\" CHECK(" + predicate("note", 12) + ") CHECK(note IS NULL)"));
    }
    @Test void deeplyNestedFunctionsNeverExtendTheFixedDepthExpressionShape() {
        String value = "note"; for (int i = 0; i < 64; i++) value = "lower(" + value + ")";
        String nested = value;
        assertThrows(IllegalArgumentException.class, () -> validate("text COLLATE \"C\" CHECK(note IS NULL OR (pg_catalog.current_setting('server_encoding')='UTF8' AND pg_catalog.octet_length(" + nested + ")<=12))"));
    }
    private static String predicate(String column, long bound) { return column + " IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length(" + column + ") <= " + bound + ")"; }
    private static void validate(String definition) { PostgresCopyTargetDefinition.validate("s", "rows", List.of("CREATE TABLE s.rows(note " + definition + ")"), List.of("note")); }
}
