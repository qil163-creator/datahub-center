package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxDb3LogicalTypeMapper;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3;

/**
 * Column fixtures with the facts the probed InfluxDB 3 Core 3.11.5 Flight SQL driver and
 * {@code information_schema.columns} actually reported. {@code SELECT} metadata has null class name,
 * {@code isSigned=false} and precision/scale 0; the catalog type string is the Arrow name.
 */
final class InfluxDb3MappingFixtures {
    private InfluxDb3MappingFixtures() { }

    static ColumnDescription column(String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(INFLUXDB3);
        boolean time = "Timestamp(ns)".equals(nativeType);
        column.setNullable(!time);
        column.setSigned(false);
        column.setPrecisionSize(0);
        column.setScaleSize(0);
        column.setFieldType(jdbc(nativeType));
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        if ("Float64".equals(nativeType)) precision = MetadataValue.value(24);
        if ("Utf8".equals(nativeType)) length = MetadataValue.value(2147483647L);
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(!time), precision, MetadataValue.absent(), length,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(INFLUXDB3, InfluxDb3LogicalTypeMapper.SERVER_PRODUCT,
                InfluxDb3LogicalTypeMapper.SERVER_VERSION, InfluxDb3LogicalTypeMapper.CATALOG_METHOD, Instant.EPOCH);
    }

    /** Flight JDBC type of a stored column. Decimal128 was only a cast expression, so it stays OTHER. */
    private static int jdbc(String nativeType) {
        return switch (nativeType) {
            case "Int64", "UInt64" -> Types.BIGINT;
            case "Float64" -> Types.DOUBLE;
            case "Boolean" -> Types.BOOLEAN;
            case "Utf8", "Dictionary(Int32, Utf8)" -> Types.VARCHAR;
            case "Timestamp(ns)" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
    }
}
