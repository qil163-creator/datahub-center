package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeColumnSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgresCopyTargetDefinitionTest {
    @ParameterizedTest
    @ValueSource(strings = {"smallint CHECK(id BETWEEN -128 AND 127)",
            "smallint CHECK(id BETWEEN 0 AND 255)", "integer CHECK(id BETWEEN 0 AND 65535)",
            "integer CHECK(id BETWEEN -8388608 AND 8388607)", "integer CHECK(id BETWEEN 0 AND 16777215)",
            "bigint CHECK(id BETWEEN 0 AND 4294967295)",
            "numeric(20,0) CHECK(id BETWEEN 0 AND 18446744073709551615)",
            "numeric(65,30) CHECK(id >= 0)"})
    void generatedNumericDomainChecksPreserveTheMappedSourceRange(String definition) {
        assertDoesNotThrow(() -> validate("CREATE TABLE s.rows(id " + definition + ")"));
    }

    @Test void generatedCheckUsesTheExactQuotedPhysicalColumn() {
        assertDoesNotThrow(() -> PostgresCopyTargetDefinition.validate("s", "rows", List.of(
                "CREATE TABLE s.rows(\"Id; value\" numeric(20,0) CHECK(\"Id; value\" BETWEEN 0 AND 18446744073709551615))"),
                List.of("Id; value")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"smallint CHECK(id NOT BETWEEN -128 AND 127)",
            "smallint CHECK(id BETWEEN -129 AND 127)", "integer CHECK(id BETWEEN 0 AND 255)",
            "numeric(21,0) CHECK(id BETWEEN 0 AND 18446744073709551615)",
            "numeric(20,1) CHECK(id BETWEEN 0 AND 18446744073709551615)",
            "numeric(20,0) CHECK(id BETWEEN 0 AND 18446744073709551616)",
            "smallint CHECK(other BETWEEN -128 AND 127)", "smallint CHECK(rows.id BETWEEN -128 AND 127)",
            "smallint CHECK(abs(id) BETWEEN 0 AND 255)", "smallint CHECK(id BETWEEN (SELECT 0) AND 255)",
            "smallint CHECK(id BETWEEN 0::integer AND 255)", "smallint CHECK(id BETWEEN 0 AND (127+128))",
            "smallint CHECK(id BETWEEN 0 AND 255 OR private_predicate(id))",
            "smallint CONSTRAINT private_check CHECK(id BETWEEN 0 AND 255)",
            "smallint CHECK(id >= 0)", "text CHECK(id >= 0)", "numeric(65,30) CHECK(id > 0)",
            "numeric(65,30) CHECK(0 <= id)", "numeric(65,30) CHECK(id >= 1)",
            "numeric(65,30) CHECK(id >= private_function())"})
    void checksOutsideTheGeneratedFiniteNumericDomainAreRejected(String definition) {
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> validate("CREATE TABLE s.rows(id " + definition + ")"));
        assertNull(rejected.getCause()); assertFalse(rejected.getMessage().contains("private_"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENABLE", "DISABLE", "VALIDATE", "NOVALIDATE", "RELY", "NORELY", "ENFORCED", "NOT ENFORCED"})
    void parserAcceptedCheckFlagsCannotExtendTheGeneratedBareCheckProfile(String flag) {
        assertThrows(IllegalArgumentException.class,
                () -> validate("CREATE TABLE s.rows(id smallint CHECK(id BETWEEN 0 AND 255) " + flag + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"smallint COMMENT 'extra'", "smallint CHECK(id BETWEEN 0 AND 255) COMMENT 'extra'"})
    void parserAcceptedInlineColumnCommentsCannotReachPostgresAfterTargetReplacement(String definition) {
        assertThrows(IllegalArgumentException.class, () -> validate("CREATE TABLE s.rows(id " + definition + ")"));
    }

    @Test void tableCheckIsNotGrantedByAColumnCheckProfile() {
        assertThrows(IllegalArgumentException.class,
                () -> validate("CREATE TABLE s.rows(id smallint, CHECK(id BETWEEN 0 AND 255))"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"smallint", "integer", "bigint", "boolean", "real", "double precision",
            "numeric", "numeric(40,20)", "numeric(10,-2)", "float(24)", "text", "char(2)",
            "character varying(100)", "bytea", "date", "uuid", "pg_catalog.uuid", "jsonb", "pg_catalog.jsonb", "time(6) without time zone", "timestamp(6)",
            "bigint GENERATED ALWAYS AS IDENTITY (START WITH 3000000000 INCREMENT BY 7 CACHE 1)"})
    void actualNativeScalarDefinitionsAreAdmitted(String nativeType) {
        assertDoesNotThrow(() -> validate("CREATE TABLE s.rows(id " + nativeType + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"smallint[]", "int2[]", "integer[]", "int4[]", "bigint[]", "int8[]",
            "boolean[]", "bool[]", "text[]", "numeric[]", "decimal[]", "pg_catalog.int4[]"})
    void finiteOneLevelUnboundedArraySyntaxIsAdmittedWithoutGrantingRuntimeShape(String nativeType) {
        assertDoesNotThrow(() -> validate("CREATE TABLE s.rows(id " + nativeType + ")"));
    }

    @Test void quotedIdentifiersCommentsAndColumnBtreeIndexKeepExactTarget() {
        assertDoesNotThrow(() -> PostgresCopyTargetDefinition.validate("Schéma", "Order", List.of(
                "CREATE TABLE \"Schéma\".\"Order\"(\"Select\" bigint NOT NULL, note text, PRIMARY KEY(\"Select\"))",
                "COMMENT ON TABLE \"Schéma\".\"Order\" IS 'value; with ''quotes'''",
                "COMMENT ON COLUMN \"Schéma\".\"Order\".note IS NULL",
                "CREATE UNIQUE INDEX order_id ON \"Schéma\".\"Order\"(\"Select\" DESC)"), List.of("Select", "note")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uuid[]", "uuid(1)", "integer[][]", "varchar(3)[]", "numeric(5,2)[]", "integer[3]", "public.private_domain", "money", "jsonb[]", "jsonb(1)", "json", "timestamptz",
            "timestamp with time zone", "time with time zone", "bit(8)", "numeric(1001)", "varchar(0)",
            "timestamp(7)", "float(54)", "integer GENERATED ALWAYS AS (1 + 1) STORED"})
    void JdbcCompatibleButUnprovedNativeEncodingsAreRejected(String nativeType) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> validate("CREATE TABLE s.rows(id " + nativeType + ")"));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private_domain"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE TABLE other.rows(id integer)", "CREATE TABLE s.other(id integer)",
            "CREATE TABLE rows(id integer)", "CREATE TABLE s.rows AS SELECT 1 AS id",
            "CREATE TABLE s.rows(LIKE s.source)", "CREATE TABLE IF NOT EXISTS s.rows(id integer)",
            "CREATE TEMPORARY TABLE s.rows(id integer)", "CREATE UNLOGGED TABLE s.rows(id integer)",
            "CREATE TABLE s.rows(id integer) PARTITION BY RANGE(id)",
            "CREATE TABLE s.rows(id integer); ALTER TABLE s.rows ALTER COLUMN id TYPE uuid",
            "CREATE TABLE s.rows(id integer); DROP TABLE s.marker",
            "CREATE TABLE s.rows(id integer); CREATE TABLE s.extra(id integer)",
            "CREATE TABLE s.rows(id integer); INSERT INTO s.rows VALUES(1)",
            "CREATE TABLE s.rows(id integer); COMMENT ON TABLE s.marker IS 'private-token'",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.marker(id)",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows(abs(id))",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows(id) WHERE id > 0",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows(id) WHERE private_predicate(id)",
            "CREATE TABLE s.rows(id integer,note text); CREATE INDEX x ON s.rows(id) INCLUDE(note)",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows(id int4_ops)",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows(id) WITH (fillfactor=70)",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows USING hash(id)",
            "CREATE TABLE s.rows(id integer); CREATE INDEX x ON s.rows(id COLLATE \"C\")"})
    void DefinitionOrAdditionalStatementCannotChangeTargetOrEncoding(String script) {
        var failure = assertThrows(IllegalArgumentException.class, () -> validate(script));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-token"));
    }

    @Test void RetainedFieldsMustExistAndHaveDistinctPhysicalNames() {
        var script = List.of("CREATE TABLE s.rows(id integer, \"ID\" text)");
        assertDoesNotThrow(() -> PostgresCopyTargetDefinition.validate("s", "rows", script, List.of("id", "ID")));
        assertThrows(IllegalArgumentException.class, () -> PostgresCopyTargetDefinition.validate("s", "rows", script, List.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> PostgresCopyTargetDefinition.validate("s", "rows", script, List.of("id", "id")));
        assertThrows(IllegalArgumentException.class, () -> PostgresCopyTargetDefinition.validate("s", "rows", script, List.of()));
        assertThrows(IllegalArgumentException.class, () -> PostgresCopyTargetDefinition.validate("s", "rows", List.of("COMMENT ON TABLE s.rows IS 'x'"), List.of("id")));
    }

    @ParameterizedTest @ValueSource(strings = {"smallint", "integer", "bigint"})
    void ownedDefaultPlanClosesCreationReferenceAndOwnershipForTheSameIntegerColumn(String type) {
        assertDoesNotThrow(() -> PostgresCopyTargetDefinition.validate("s", "rows", ownedPlan(type), List.of("id")));
    }

    @Test void independentOwnedDefaultsKeepQuotedPhysicalColumnsAndOneOwnershipEach() {
        String schema = "Schéma", table = "Order", first = "Select", second = "Key\"; name";
        String relation = qualified(schema, table), firstSequence = destination(schema, table, first), secondSequence = destination(schema, table, second);
        assertDoesNotThrow(() -> PostgresCopyTargetDefinition.validate(schema, table, List.of(
                createSequence(firstSequence, "integer"), createSequence(secondSequence, "bigint"),
                "CREATE TABLE " + relation + "(" + quoted(first) + " integer DEFAULT " + nextval(firstSequence)
                        + "," + quoted(second) + " bigint DEFAULT " + nextval(secondSequence) + ")",
                "COMMENT ON COLUMN " + relation + "." + quoted(second) + " IS 'fixture'",
                "ALTER SEQUENCE " + secondSequence + " OWNED BY " + relation + "." + quoted(second),
                "ALTER SEQUENCE " + firstSequence + " OWNED BY " + relation + "." + quoted(first)), List.of(first, second)));
    }

    @ParameterizedTest @ValueSource(strings = {"missingOwnership", "missingDefault", "missingCreation", "unusedSequence",
            "duplicateCreation", "duplicateOwnership", "ownershipBeforeTable", "creationAfterTable", "cycle", "missingCycle",
            "restart", "alterCache", "alterCycle", "differentSequenceSchema", "differentOwnerSchema", "differentOwnerTable",
            "differentOwnerColumn", "differentAlteredSequence", "differentDefaultSchema", "arbitraryClosedSequence",
            "wrongSequenceType", "nonIntegerOwner", "nestedNextval", "ownerFunction", "implicitRegclass", "currval", "setval",
            "sharedDefault", "missingIncrement", "zeroIncrement", "zeroCache", "outOfTypeBounds", "sequenceComment",
            "unknownSequenceOption", "unknownSideDdl"})
    void sequencePlansCannotGrantAnOpenDependencyOrAnUnprovedMutation(String mutation) {
        var valid = ownedPlan("integer");
        String create = valid.get(0), table = valid.get(1), own = valid.get(2);
        String sequence = destination("s", "rows", "id");
        List<String> changed = switch (mutation) {
            case "missingOwnership" -> List.of(create, table);
            case "missingDefault" -> List.of(create, "CREATE TABLE s.rows(id integer)", own);
            case "missingCreation" -> List.of(table, own);
            case "unusedSequence" -> List.of(createSequence("s.unused", "integer"), create, table, own);
            case "duplicateCreation" -> List.of(create, create, table, own);
            case "duplicateOwnership" -> List.of(create, table, own, own);
            case "ownershipBeforeTable" -> List.of(create, own, table);
            case "creationAfterTable" -> List.of(table, create, own);
            case "cycle" -> List.of(create.replace("NO CYCLE", "CYCLE"), table, own);
            case "missingCycle" -> List.of(create.replace(" NO CYCLE", ""), table, own);
            case "restart" -> List.of(create, table, own, "ALTER SEQUENCE " + sequence + " RESTART WITH 51");
            case "alterCache" -> List.of(create, table, own, "ALTER SEQUENCE " + sequence + " CACHE 8");
            case "alterCycle" -> List.of(create, table, own, "ALTER SEQUENCE " + sequence + " CYCLE");
            case "differentSequenceSchema" -> List.of(create.replace("\"s\".", "\"other\"."), table, own);
            case "differentOwnerSchema" -> List.of(create, table, own.replace("OWNED BY s.", "OWNED BY other."));
            case "differentOwnerTable" -> List.of(create, table, own.replace("s.rows.id", "s.other.id"));
            case "differentOwnerColumn" -> List.of(create, table, own.replace("s.rows.id", "s.rows.note"));
            case "differentAlteredSequence" -> List.of(create, table, own.replace(sequence, "s.other_seq"));
            case "differentDefaultSchema" -> List.of(create, table.replace("\"s\".", "\"other\"."), own);
            case "arbitraryClosedSequence" -> List.of(create.replace(sequence, "s.arbitrary"),
                    table.replace(nextval(sequence), nextval("s.arbitrary")), own.replace(sequence, "s.arbitrary"));
            case "wrongSequenceType" -> List.of(create.replace("AS integer", "AS bigint"), table, own);
            case "nonIntegerOwner" -> List.of(create, table.replace("id integer", "id numeric(10,0)"), own);
            case "nestedNextval" -> List.of(create, table.replace(nextval(sequence), "abs(" + nextval(sequence) + ")"), own);
            case "ownerFunction" -> List.of(create, table.replace("nextval(", "other.nextval("), own);
            case "implicitRegclass" -> List.of(create, table.replace("::regclass", ""), own);
            case "currval" -> List.of(create, table.replace("nextval(", "currval("), own);
            case "setval" -> List.of(create, table.replace(nextval(sequence), "setval('" + sequence + "'::regclass,7)"), own);
            case "sharedDefault" -> List.of(create, table.substring(0, table.length() - 1)
                    + ",other integer DEFAULT " + nextval(sequence) + ")", own);
            case "missingIncrement" -> List.of(create.replace(" INCREMENT BY 2", ""), table, own);
            case "zeroIncrement" -> List.of(create.replace("INCREMENT BY 2", "INCREMENT BY 0"), table, own);
            case "zeroCache" -> List.of(create.replace("CACHE 1", "CACHE 0"), table, own);
            case "outOfTypeBounds" -> List.of(create.replace("MAXVALUE 10000", "MAXVALUE 2147483648"), table, own);
            case "sequenceComment" -> List.of(create, table, own, "COMMENT ON SEQUENCE " + sequence + " IS 'private-token'");
            case "unknownSequenceOption" -> List.of(create + " OWNED BY s.rows.id", table, own);
            case "unknownSideDdl" -> List.of(create, table, own, "ALTER TABLE s.rows ADD COLUMN secret text");
            default -> throw new AssertionError(mutation);
        };
        var failure = assertThrows(IllegalArgumentException.class,
                () -> PostgresCopyTargetDefinition.validate("s", "rows", changed, List.of("id")));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-token"));
    }

    private static List<String> ownedPlan(String type) {
        String sequence = destination("s", "rows", "id");
        return List.of(createSequence(sequence, type), "CREATE TABLE s.rows(id " + type + " DEFAULT " + nextval(sequence) + ")",
                "ALTER SEQUENCE " + sequence + " OWNED BY s.rows.id");
    }
    private static String createSequence(String destination, String type) {
        return "CREATE SEQUENCE " + destination + " AS " + type + " START WITH 11 INCREMENT BY 2 MINVALUE 1 MAXVALUE 10000 CACHE 1 NO CYCLE";
    }
    private static String destination(String schema, String table, String column) {
        return qualified(schema, NativeColumnSql.sequenceName(schema, table, column));
    }
    private static String nextval(String sequence) { return "nextval('" + sequence.replace("'", "''") + "'::regclass)"; }
    private static String qualified(String schema, String name) { return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, name); }
    private static String quoted(String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, name); }

    private static void validate(String script) {
        PostgresCopyTargetDefinition.validate("s", "rows", List.of(script), List.of("id"));
    }
}
