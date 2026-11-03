package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeColumnSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.CLICKHOUSE;
import static org.junit.jupiter.api.Assertions.*;

/** Finite DDL grammar defenses; actual catalog and CREATE/FULL proof lives in native tests. */
class ClickhouseNativeColumnSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {"Int8", "Int16", "Int32", "Int64", "UInt8", "UInt16", "UInt32", "UInt64", "Bool",
            "String", "UUID", "Date", "Date32", "DateTime", "DateTime64(6)", "Decimal(38, 9)", "Nullable(UInt64)",
            "Nullable(String)", "Nullable(Decimal(38,9))"})
    void rendersOnlyOrdinaryNativeScalarColumns(String type) {
        var column = ClickHouseMappingFixtures.family("value", type);
        assertEquals("`value` " + type + (column.isNullable() ? "" : " NOT NULL"), NativeColumnSql.definition(CLICKHOUSE, column));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "String; DROP TABLE x", "String DEFAULT sleep(1)", "UInt64/**/", "Nullable(Nullable(String))",
            "FixedString(10)", "LowCardinality(String)", "Array(Int64)", "Map(String,String)", "Tuple(Int64,String)",
            "DateTime('UTC')", "DateTime64(6,'UTC')", "DateTime64(10)", "Decimal(0,0)", "Decimal(77,0)",
            "Decimal(38,39)", "Decimal(999999999999999,0)", "Decimal(38,-1)"})
    void rejectsIncompleteOrUnprovedTypeGrammar(String type) {
        assertFalse(NativeColumnSql.supportedType(CLICKHOUSE, type));
        assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, ClickHouseMappingFixtures.family("x", type)));
    }

    @Test void refusesDefaultGeneratedAndUnknownEvidenceBeforeRendering() {
        for (var defaultValue : java.util.List.of(MetadataValue.<String>unknown(), MetadataValue.<String>unsupported(),
                MetadataValue.value("7"), MetadataValue.value("7; DROP TABLE x"))) {
            var column = ClickHouseMappingFixtures.family("x", "Int32");
            var f = column.getMetadataEvidence();
            column.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), defaultValue,
                    f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation()));
            assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, column));
        }
        var unknown = ClickHouseMappingFixtures.family("x", "Int32");
        var f = unknown.getMetadataEvidence();
        unknown.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), f.defaultExpression(),
                f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(), MetadataValue.unknown(), f.generatedExpression(), f.charset(), f.collation()));
        assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, unknown));
        var generated = ClickHouseMappingFixtures.family("x", "Int32");
        f = generated.getMetadataEvidence();
        generated.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), f.defaultExpression(),
                f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), MetadataValue.value("id+1"), f.charset(), f.collation()));
        assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, generated));
    }

    @Test void refusesNullabilityAndIdentityContradictions() {
        var nullable = ClickHouseMappingFixtures.family("x", "Nullable(String)"); nullable.setNullable(false);
        assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, nullable));
        var identity = ClickHouseMappingFixtures.family("x", "Int64"); identity.setAutoIncrement(true);
        assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, identity));
        assertThrows(RuntimeException.class, () -> NativeColumnSql.definition(CLICKHOUSE, ClickHouseMappingFixtures.family("a`b", "String")));
        assertFalse(NativeColumnSql.supportedType(CLICKHOUSE, null));
    }
}
