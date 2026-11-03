package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Column fixtures with the shape sqlite-jdbc 3.31.1 reports for an empty projection ({@code WHERE 1=0}) plus
 * {@code pragma_table_info}. The class name is {@code java.lang.Object} and the display size is 2147483647.
 * Declared length stays in the pragma type string; JDBC type names drop it ({@code VARCHAR}, {@code CHAR},
 * {@code DECIMAL}).
 */
final class Sqlite3MappingFixtures {
    private static final Pattern VARCHAR = Pattern.compile("(?i)varchar\\((\\d+)\\)");
    private static final Pattern CHAR = Pattern.compile("(?i)char\\((\\d+)\\)");
    private static final Pattern DECIMAL = Pattern.compile("(?i)decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    private Sqlite3MappingFixtures() { }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(ProductTypeEnum.SQLITE3, "SQLite", "3.31.1", "SQLITE_CATALOG_V1", Instant.EPOCH);
    }

    /** A probed declaration. The native type keeps the caller's spelling, which is what pragma stored. */
    static ColumnDescription column(String name, String nativeType) {
        String declared = nativeType.trim();
        String lower = declared.toLowerCase(Locale.ROOT);
        int jdbc;
        String typeName;
        boolean signed;
        int precision = 0;
        int scale = 0;
        MetadataValue<Integer> catalogPrecision = MetadataValue.absent();
        MetadataValue<Integer> catalogScale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        Matcher varchar = VARCHAR.matcher(declared);
        Matcher character = CHAR.matcher(declared);
        Matcher decimal = DECIMAL.matcher(declared);
        if (lower.equals("integer")) { jdbc = Types.INTEGER; typeName = "INTEGER"; signed = true; }
        else if (lower.equals("real")) { jdbc = Types.REAL; typeName = "REAL"; signed = true; }
        else if (lower.equals("text")) { jdbc = Types.VARCHAR; typeName = "TEXT"; signed = false; }
        else if (lower.equals("blob")) { jdbc = Types.BLOB; typeName = "BLOB"; signed = false; }
        else if (lower.equals("numeric")) { jdbc = Types.NUMERIC; typeName = "NUMERIC"; signed = true; }
        else if (lower.equals("boolean")) { jdbc = Types.BOOLEAN; typeName = "BOOLEAN"; signed = false; }
        else if (lower.equals("date")) { jdbc = Types.DATE; typeName = "DATE"; signed = false; }
        else if (lower.equals("datetime")) { jdbc = Types.DATE; typeName = "DATETIME"; signed = false; }
        else if (varchar.matches()) {
            precision = Integer.parseInt(varchar.group(1));
            jdbc = Types.VARCHAR; typeName = "VARCHAR"; signed = false;
            length = MetadataValue.value((long) precision);
        } else if (character.matches()) {
            precision = Integer.parseInt(character.group(1));
            jdbc = Types.CHAR; typeName = "CHAR"; signed = false;
            length = MetadataValue.value((long) precision);
        } else if (decimal.matches()) {
            precision = Integer.parseInt(decimal.group(1));
            scale = Integer.parseInt(decimal.group(2));
            jdbc = Types.DECIMAL; typeName = "DECIMAL"; signed = false;
            catalogPrecision = MetadataValue.value(precision);
            catalogScale = MetadataValue.value(scale);
        } else {
            throw new IllegalArgumentException(nativeType);
        }
        return observed(name, declared, jdbc, typeName, signed, precision, scale, catalogPrecision, catalogScale, length);
    }

    /** A declaration that was probed and is intentionally not a mapped strict type (for example INT or an empty type). */
    static ColumnDescription observed(String name, String nativeType, int jdbc, String typeName, boolean signed,
                                      int precision, int scale) {
        return observed(name, nativeType, jdbc, typeName, signed, precision, scale,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
    }

    static ColumnDescription notNull(ColumnDescription column) {
        column.setNullable(false);
        return withFacts(column, facts -> new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                MetadataValue.value(false), facts.numericPrecision(), facts.numericScale(), facts.characterLength(), facts.identity(),
                facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
    }

    static ColumnDescription withFacts(ColumnDescription column, UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    private static ColumnDescription observed(String name, String nativeType, int jdbc, String typeName, boolean signed,
                                              int precision, int scale, MetadataValue<Integer> catalogPrecision,
                                              MetadataValue<Integer> catalogScale, MetadataValue<Long> length) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(ProductTypeEnum.SQLITE3);
        column.setNullable(true);
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName("java.lang.Object");
        column.setSigned(signed);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        column.setDisplaySize(Integer.MAX_VALUE);
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), catalogPrecision, catalogScale, length,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent()));
        return column;
    }
}
