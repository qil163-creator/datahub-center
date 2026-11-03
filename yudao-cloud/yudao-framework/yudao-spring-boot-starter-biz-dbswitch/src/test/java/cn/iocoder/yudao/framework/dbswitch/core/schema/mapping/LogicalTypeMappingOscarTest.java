package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.TreeSet;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden checks for the 神通 V8.0.251217 facts. No live connection in this class. */
class LogicalTypeMappingOscarTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private static final OscarLogicalTypeMapper MAPPER = new OscarLogicalTypeMapper();

    @Test void registryCarriesTheProbedOscarMapper() {
        var mapper = REGISTRY.typeMapper(OSCAR).orElseThrow();
        assertInstanceOf(OscarLogicalTypeMapper.class, mapper);
        assertEquals(OSCAR, mapper.product());
        assertTrue(MAPPINGS.supports(OSCAR, POSTGRESQL));
        assertFalse(MAPPINGS.supports(OSCAR, OSCAR));
    }

    @Test void bareNumberIsBoundedAndParameterizedNumbersKeepTheirScale() {
        assertEquals("DECIMAL(1000,38)", logical(OscarMappingFixtures.bareNumber("n")));
        assertEquals("DECIMAL(10,2)", logical(OscarMappingFixtures.numeric("n", 10, 2)));
        assertEquals("DECIMAL(38,0)", logical(OscarMappingFixtures.numeric("n", 38, 0)));
        assertEquals("EXACT|numeric(1000,38)|", outcome(MAPPINGS.map(POSTGRESQL, OscarMappingFixtures.bareNumber("v"), false)));
        assertEquals("EXACT|numeric(10,2)|", outcome(MAPPINGS.map(POSTGRESQL, OscarMappingFixtures.numeric("v", 10, 2), false)));
        assertEquals("NUMBER", nativeSql(new LogicalType.DecimalType(1000, 38, false, false), "numeric"));
        assertEquals("NUMBER(10,2)", nativeSql(new LogicalType.DecimalType(10, 2, false, false), "numeric"));
        assertEquals("UNSUPPORTED", MAPPER.toNative(new LogicalColumn("v", new LogicalType.DecimalType(null, null, false, false),
                true, false, OSCAR, "numeric")).fidelity().name());
    }

    @Test void dateDropsTimeAndBlobUsesTheReportedDisplaySize() {
        var date = MAPPER.toLogical(OscarMappingFixtures.date("d"));
        assertEquals(TypeFidelity.EXACT, date.fidelity());
        assertEquals("DATE(1..9999)", date.value().canonical());
        assertTrue(date.issues().stream().anyMatch(issue -> issue.code().equals("DATE_TIME_NOT_STORED")));
        assertEquals("EXACT|date|", outcome(MAPPINGS.map(POSTGRESQL, OscarMappingFixtures.date("v"), false)));
        assertEquals("DATE", nativeSql(new LogicalType.DateType(1, 9999, false), "date"));
        var blob = MAPPER.toLogical(OscarMappingFixtures.blob("b"));
        assertEquals(TypeFidelity.EXACT, blob.fidelity());
        assertEquals("BINARY(LARGE,MAX_OCTETS=2147483647)", blob.value().canonical());
        assertEquals("EXACT|bytea|", outcome(MAPPINGS.map(POSTGRESQL, OscarMappingFixtures.blob("v"), false)));
        assertEquals("BLOB", nativeSql(new LogicalType.BinaryType(LogicalType.StorageKind.LARGE, null, 2147483647L), "blob"));
    }

    @Test void characterAndTimestampStayUnsupported() {
        assertEquals("REPERTOIRE_UNOBSERVED", code(OscarMappingFixtures.character("c", "char", Types.CHAR, 10)));
        assertEquals("REPERTOIRE_UNOBSERVED", code(OscarMappingFixtures.character("c", "bpchar", Types.CHAR, 10)));
        assertEquals("REPERTOIRE_UNOBSERVED", code(OscarMappingFixtures.character("v", "varchar", Types.VARCHAR, 20)));
        assertEquals("REPERTOIRE_UNOBSERVED", code(OscarMappingFixtures.character("c", "clob", Types.CLOB, 0)));
        assertEquals("TIMESTAMP_YEAR_BOUNDS_UNPROVEN", code(OscarMappingFixtures.timestamp("t")));
        assertEquals("UNSUPPORTED|-|REPERTOIRE_UNOBSERVED", outcome(MAPPINGS.map(POSTGRESQL,
                OscarMappingFixtures.character("v", "varchar", Types.VARCHAR, 20), false)));
    }

    @Test void anotherServerVersionIsNotThisProbe() {
        var column = OscarMappingFixtures.numeric("n", 10, 2);
        var facts = column.getMetadataEvidence();
        column.setMetadataEvidence(new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(OSCAR, "OSCAR", "other",
                        OscarLogicalTypeMapper.METHOD, java.time.Instant.EPOCH),
                facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                facts.sequence()));
        assertEquals("SOURCE_PROVENANCE_UNVERIFIED", code(column));
    }

    private static String outcome(ColumnTypeMapping mapping) {
        var codes = new TreeSet<String>(mapping.codes(TypeMappingIssue.Severity.LOSS));
        codes.addAll(mapping.codes(TypeMappingIssue.Severity.BLOCKER));
        return mapping.fidelity() + "|" + (mapping.fidelity() == TypeFidelity.UNSUPPORTED || mapping.targetNativeType() == null
                ? "-" : mapping.targetNativeType()) + "|" + String.join(",", codes);
    }

    private static String logical(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription column) {
        var mapped = MAPPER.toLogical(column);
        assertEquals(TypeFidelity.EXACT, mapped.fidelity());
        return mapped.value().canonical();
    }

    private static String code(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription column) {
        var mapped = MAPPER.toLogical(column);
        assertEquals(TypeFidelity.UNSUPPORTED, mapped.fidelity());
        return mapped.issues().get(0).code();
    }

    private static String nativeSql(LogicalType type, String sourceType) {
        var mapped = MAPPER.toNative(new LogicalColumn("v", type, true, false, OSCAR, sourceType));
        assertEquals(TypeFidelity.EXACT, mapped.fidelity());
        return mapped.value().typeSql();
    }
}
