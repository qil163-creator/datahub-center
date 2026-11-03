package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarLogicalTypeMapper;

import java.sql.Types;
import java.time.Instant;

/** Column fixtures using the JDBC metadata read from 神通 V8.0.251217. Not Oracle or DM facts. */
final class OscarMappingFixtures {
    private OscarMappingFixtures() { }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(ProductTypeEnum.OSCAR, "OSCAR", OscarLogicalTypeMapper.SERVER_VERSION,
                OscarLogicalTypeMapper.METHOD, Instant.EPOCH);
    }

    /** Unqualified NUMBER, reported by the driver as numeric(1000,38). */
    static ColumnDescription bareNumber(String name) { return numeric(name, 1000, 38); }

    static ColumnDescription numeric(String name, int precision, int scale) {
        ColumnDescription column = column(name, Types.NUMERIC, "numeric", "java.math.BigDecimal", precision, scale, true);
        evidence(column, "numeric", MetadataValue.value(precision), MetadataValue.value(scale), MetadataValue.absent());
        return column;
    }

    static ColumnDescription date(String name) {
        ColumnDescription column = column(name, Types.DATE, "date", "java.sql.Date", 8, 0, false);
        evidence(column, "date", MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
        return column;
    }

    static ColumnDescription timestamp(String name) {
        ColumnDescription column = column(name, Types.TIMESTAMP, "timestamp", "java.sql.Timestamp", 23, 0, false);
        evidence(column, "timestamp", MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
        return column;
    }

    static ColumnDescription blob(String name) {
        ColumnDescription column = column(name, Types.BLOB, "blob", "java.sql.Blob", 0, 0, false);
        evidence(column, "blob", MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
        return column;
    }

    static ColumnDescription character(String name, String nativeType, int jdbc, int length) {
        String className = "clob".equals(nativeType) ? "java.sql.Clob" : "java.lang.String";
        ColumnDescription column = column(name, jdbc, nativeType, className, length, 0, false);
        MetadataValue<Long> characters = "clob".equals(nativeType) ? MetadataValue.absent() : MetadataValue.value((long) length);
        evidence(column, nativeType, MetadataValue.absent(), MetadataValue.absent(), characters);
        return column;
    }

    private static ColumnDescription column(String name, int jdbc, String nativeType, String className,
                                            int precision, int scale, boolean signed) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(ProductTypeEnum.OSCAR);
        column.setNullable(true);
        column.setSigned(signed);
        column.setFieldType(jdbc);
        column.setFieldTypeName(nativeType);
        column.setFiledTypeClassName(className);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        return column;
    }

    private static void evidence(ColumnDescription column, String nativeType, MetadataValue<Integer> precision,
                                 MetadataValue<Integer> scale, MetadataValue<Long> characters) {
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, characters,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.unknown()));
    }
}
