package cn.iocoder.yudao.framework.etl.spark;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** 工作节点文件输出的无损标量序列化，供 HDFS 与 FTP 数据源复用。 */
public final class EtlSparkRowValues {
    private EtlSparkRowValues() { }

    public static String format(InternalRow row, StructField field, int index, Map<String, String> options) {
        if (row.isNullAt(index)) return null;
        DataType type = field.dataType();
        if (type.equals(DataTypes.StringType)) return row.getUTF8String(index).toString();
        if (type.equals(DataTypes.LongType)) return String.valueOf(row.getLong(index));
        if (type.equals(DataTypes.IntegerType)) return String.valueOf(row.getInt(index));
        if (type.equals(DataTypes.ShortType)) return String.valueOf(row.getShort(index));
        if (type.equals(DataTypes.ByteType)) return String.valueOf(row.getByte(index));
        if (type.equals(DataTypes.DoubleType)) return String.valueOf(row.getDouble(index));
        if (type.equals(DataTypes.FloatType)) return String.valueOf(row.getFloat(index));
        if (type.equals(DataTypes.BooleanType)) return String.valueOf(row.getBoolean(index));
        if (type instanceof DecimalType decimal) return row.getDecimal(index, decimal.precision(), decimal.scale()).toJavaBigDecimal().toPlainString();
        String format = options.get("datahub.format." + field.name().toLowerCase(Locale.ROOT));
        if (type.equals(DataTypes.DateType)) {
            LocalDate day = LocalDate.ofEpochDay(row.getInt(index)); return format == null ? day.toString() : day.format(DateTimeFormatter.ofPattern(format));
        }
        if (type.equals(DataTypes.TimestampType)) {
            long micros = row.getLong(index); Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
            return format == null ? instant.toString() : DateTimeFormatter.ofPattern(format).withZone(ZoneOffset.UTC).format(instant);
        }
        throw new IllegalArgumentException("文件输出字段类型尚未支持：" + field.name());
    }

}
