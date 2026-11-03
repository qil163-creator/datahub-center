package cn.iocoder.yudao.framework.etl.flink.tdengine;

import com.taosdata.jdbc.tmq.Deserializer;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.logical.*;
import org.apache.flink.table.types.logical.utils.LogicalTypeParser;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;

/** 使用官方 TMQ 反序列化 SPI 校验实际主题的列合同；时间戳由 Flink 按声明精度序列化。 */
public final class ManagedTdengineDeserializer implements Deserializer<RowData> {
    public static final String SCHEMA = "datahub.tdengine.row-type";
    private RowType rowType;

    @Override public void configure(Map<?, ?> properties) {
        Object value = properties.get(SCHEMA);
        if (!(value instanceof String text) || text.length() > 100_000) throw new IllegalArgumentException("TDengine 受管字段合同缺失或超限");
        LogicalType parsed = LogicalTypeParser.parse(text);
        if (!(parsed instanceof RowType row) || row.getFieldCount() == 0 || row.getFieldCount() > 1024) throw new IllegalArgumentException("TDengine 受管字段合同无效");
        rowType = row;
        // 官方 SDK 随后会把未知属性交给服务器；只消费并移除本反序列化器的私有字段。
        properties.remove(SCHEMA);
    }

    @Override public RowData deserialize(ResultSet rows, String database, String table) throws SQLException {
        if (rowType == null) throw new SQLException("TDengine 受管反序列化器尚未初始化");
        ResultSetMetaData meta = rows.getMetaData();
        if (meta.getColumnCount() != rowType.getFieldCount()) throw new SQLException("TDengine 实际主题字段数量与受管合同不一致");
        GenericRowData result = new GenericRowData(rowType.getFieldCount());
        for (int i = 0; i < rowType.getFieldCount(); i++) {
            if (!rowType.getFieldNames().get(i).equals(meta.getColumnLabel(i + 1))) throw new SQLException("TDengine 实际主题字段顺序与受管合同不一致");
            LogicalType type = rowType.getTypeAt(i);
            Object value = rows.getObject(i + 1);
            if (value == null) { result.setField(i, null); continue; }
            try {
                Object converted = switch (type.getTypeRoot()) {
                    case CHAR, VARCHAR -> {
                        if (value instanceof String text) yield StringData.fromString(text);
                        if (value instanceof byte[] bytes) yield StringData.fromString(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
                        throw new IllegalArgumentException();
                    }
                    case BOOLEAN -> { if (!(value instanceof Boolean)) throw new IllegalArgumentException(); yield value; }
                    case TINYINT -> { if (!(value instanceof Byte)) throw new IllegalArgumentException(); yield value; }
                    case SMALLINT -> { if (!(value instanceof Short)) throw new IllegalArgumentException(); yield value; }
                    case INTEGER -> { if (!(value instanceof Integer)) throw new IllegalArgumentException(); yield value; }
                    case BIGINT -> { if (!(value instanceof Long)) throw new IllegalArgumentException(); yield value; }
                    case FLOAT -> { if (!(value instanceof Float)) throw new IllegalArgumentException(); yield value; }
                    case DOUBLE -> { if (!(value instanceof Double)) throw new IllegalArgumentException(); yield value; }
                    case DECIMAL -> {
                        if (!(value instanceof BigDecimal decimal)) throw new IllegalArgumentException();
                        DecimalType definition = (DecimalType) type;
                        BigDecimal exact = decimal.setScale(definition.getScale());
                        if (exact.precision() > definition.getPrecision()) throw new IllegalArgumentException();
                        DecimalData convertedDecimal = DecimalData.fromBigDecimal(exact, definition.getPrecision(), definition.getScale());
                        if (convertedDecimal == null) throw new IllegalArgumentException();
                        yield convertedDecimal;
                    }
                    case TIMESTAMP_WITHOUT_TIME_ZONE -> {
                        if (!(value instanceof Timestamp timestamp)) throw new IllegalArgumentException();
                        int precision = ((TimestampType) type).getPrecision();
                        if (timestamp.getNanos() % (int) Math.pow(10, 9 - precision) != 0) throw new IllegalArgumentException();
                        yield TimestampData.fromTimestamp(timestamp);
                    }
                    default -> throw new IllegalArgumentException();
                };
                result.setField(i, converted);
            } catch (Exception failure) { throw new SQLException("TDengine 实际主题字段值与声明类型或精度不一致"); }
        }
        return result;
    }
}
