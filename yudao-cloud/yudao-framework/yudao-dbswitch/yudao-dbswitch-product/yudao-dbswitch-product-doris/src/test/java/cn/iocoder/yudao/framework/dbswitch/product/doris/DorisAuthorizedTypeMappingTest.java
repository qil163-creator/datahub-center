package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMappings;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresLogicalTypeMapper;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用来源原生证据验证完整建表链，不以手写 Doris DDL 替代类型映射。 */
class DorisAuthorizedTypeMappingTest {
    private final LogicalTypeMappings mappings = LogicalTypeMappings.of(
            List.of(new PostgresLogicalTypeMapper(), new DorisLogicalTypeMapper()));

    @Test void nativePrimaryKeyProducesUniqueMowAndUnicodeBytesWithoutRelaxingValueMapping() {
        var id = column("id", "bigint", Types.BIGINT, false, null, null, null);
        var note = column("note", "character varying(1)", Types.VARCHAR, true, null, null, 1L);
        note.setRemarks("字段'说明");
        var options = new DorisCreateTableOptions(null, "HASH", List.of("id"), "FIXED", 2, 1);
        String ddl = DorisAuthorizedCreateNew.createSql("dw", "rows", List.of(id, note), List.of("id"),
                "数据'来源", Map.of(DorisCreateTableOptions.TABLE_PROPERTY, options.toJson()), mappings);
        var actual = DorisNativeSchema.parse(ddl);
        assertTrue(actual.unique());
        assertEquals(List.of("id"), actual.keys());
        assertEquals("true", actual.properties().get("enable_unique_key_merge_on_write"));
        assertEquals("varchar(4)", actual.column("note").type());
        assertEquals("字段'说明", actual.column("note").comment());
        assertEquals("数据'来源", actual.comment());
        assertEquals("字段'说明", note.getRemarks());
        assertEquals("character varying(1)", note.getMetadataEvidence().nativeType().value());
    }

    @Test void sourceWithoutNativePrimaryKeyUsesDuplicateRows() {
        var id = column("id", "bigint", Types.BIGINT, false, null, null, null);
        var actual = DorisNativeSchema.parse(DorisAuthorizedCreateNew.createSql("dw", "rows", List.of(id),
                List.of(), "", Map.of(), mappings));
        assertEquals("DUPLICATE", actual.model());
        assertFalse(actual.properties().containsKey("enable_unique_key_merge_on_write"));
    }

    @Test void uniqueLayoutDoesNotWaiveNumericNanOrTextKeySemantics() {
        var numeric = column("id", "numeric(18,4)", Types.NUMERIC, false, 18, 4, null);
        assertThrows(RuntimeException.class, () -> DorisAuthorizedCreateNew.createSql("dw", "rows", List.of(numeric),
                List.of("id"), "", Map.of(), mappings));
        var text = column("id", "character varying(20)", Types.VARCHAR, false, null, null, 20L);
        assertThrows(RuntimeException.class, () -> DorisAuthorizedCreateNew.createSql("dw", "rows", List.of(text),
                List.of("id"), "", Map.of(), mappings));
    }

    @Test void omittedDefaultsAreClearedOnTheMappedCopyOnly() {
        var id = column("id", "bigint", Types.BIGINT, false, null, null, null);
        id.setDefaultValue("42");
        var f = id.getMetadataEvidence();
        id.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), value("42"), f.nullable(),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(),
                f.charset(), f.collation(), f.sequence()));
        var source = new cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties();
        var target = new cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties();
        target.setTargetProductType(ProductTypeEnum.DORIS);
        var copied = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings.resolve(source, target,
                "rows", List.of(id), List.of("id"));
        var actual = DorisNativeSchema.parse(DorisAuthorizedCreateNew.createSql("dw", "rows", copied.columns(),
                copied.primaryKeys(), "", Map.of(), mappings));
        assertNull(actual.column("id").defaultSql());
        assertEquals("42", id.getDefaultValue());
        assertEquals("42", id.getMetadataEvidence().defaultExpression().value());
    }

    private static ColumnDescription column(String name, String nativeType, int jdbc, boolean nullable,
            Integer precision, Integer scale, Long characters) {
        var result = new ColumnDescription();
        result.setFieldName(name); result.setLabelName(name); result.setDbType(ProductTypeEnum.POSTGRESQL);
        result.setFieldType(jdbc); result.setFieldTypeName(nativeType); result.setNullable(nullable); result.setSigned(true);
        result.setPrecisionSize(precision == null ? 0 : precision); result.setScaleSize(scale == null ? 0 : scale);
        result.setMetadataEvidence(new ColumnMetadataEvidence(
                new MetadataProvenance(ProductTypeEnum.POSTGRESQL, "PostgreSQL", "16.15", "PG_CATALOG_V1", Instant.EPOCH),
                value(nativeType), absent(), value(nullable), precision == null ? absent() : value(precision),
                scale == null ? absent() : value(scale), characters == null ? absent() : value(characters),
                absent(), absent(), absent(), absent(), absent()));
        return result;
    }
}
