package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.ValueHandlerAlreadyRegisteredException;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.ValueHandlerNotRegisteredException;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.DataType;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 值处理器提供者默认实现,预注册各 PostgreSQL 数据类型对应的值处理器,并支持按类型解析与扩展注册。
 */
public class ValueHandlerProvider implements IValueHandlerProvider {

    private final Map<DataType, IValueHandler> valueHandlers;

    /**
     * 初始化 {@code ValueHandlerProvider}。
     */
    public ValueHandlerProvider() {
        valueHandlers = new EnumMap<>(DataType.class);

        add(DataType.Boolean, new BooleanValueHandler());
        add(DataType.Char, new ByteValueHandler<>());
        add(DataType.Numeric, new BigDecimalValueHandler<>());
        add(DataType.DoublePrecision, new DoubleValueHandler<>());
        add(DataType.SinglePrecision, new FloatValueHandler<>());
        add(DataType.Date, new LocalDateValueHandler());
        add(DataType.Time, new LocalTimeValueHandler());
        add(DataType.Timestamp, new LocalDateTimeValueHandler());
        add(DataType.TimestampTz, new ZonedDateTimeValueHandler());
        add(DataType.Int2, new ShortValueHandler<>());
        add(DataType.Int4, new IntegerValueHandler<>());
        add(DataType.Int8, new LongValueHandler<>());
        add(DataType.Text, new StringValueHandler());
        add(DataType.VarChar, new StringValueHandler());
        add(DataType.Inet4, new Inet4AddressValueHandler());
        add(DataType.Inet6, new Inet6AddressValueHandler());
        add(DataType.Uuid, new UUIDValueHandler());
        add(DataType.Bytea, new ByteArrayValueHandler());
        add(DataType.Jsonb, new JsonbValueHandler());
        add(DataType.Hstore, new HstoreValueHandler());
        add(DataType.Point, new PointValueHandler());
        add(DataType.Box, new BoxValueHandler());
        add(DataType.Line, new LineValueHandler());
        add(DataType.LineSegment, new LineSegmentValueHandler());
        add(DataType.Path, new PathValueHandler());
        add(DataType.Polygon, new PolygonValueHandler());
        add(DataType.Circle, new CircleValueHandler());
        add(DataType.MacAddress, new MacAddressValueHandler());
        add(DataType.TsRange, new RangeValueHandler<>(new LocalDateTimeValueHandler()));
        add(DataType.TsTzRange, new RangeValueHandler<>(new ZonedDateTimeValueHandler()));
        add(DataType.Int4Range, new RangeValueHandler<>(new IntegerValueHandler<>()));
        add(DataType.Int8Range, new RangeValueHandler<>(new LongValueHandler<>()));
        add(DataType.NumRange, new RangeValueHandler<>(new BigDecimalValueHandler<>()));
        add(DataType.DateRange, new RangeValueHandler<>(new LocalDateValueHandler()));
    }

    /**
     * 将当前值加入集合或累计统计。
     */
    public <TTargetType> ValueHandlerProvider add(DataType targetType,
            IValueHandler<TTargetType> valueHandler) {
        if (valueHandlers.containsKey(targetType)) {
            throw new ValueHandlerAlreadyRegisteredException(
                    String.format("TargetType '%s' has already been registered", targetType));
        }

        valueHandlers.put(targetType, valueHandler);

        return this;
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    @Override
    public <TTargetType> IValueHandler<TTargetType> resolve(DataType dataType) {

        @SuppressWarnings("unchecked")
        IValueHandler<TTargetType> handler = valueHandlers.get(dataType);
        if (handler == null) {
            throw new ValueHandlerNotRegisteredException(
                    String.format("DataType '%s' has not been registered", dataType));
        }
        return handler;
    }


    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {

        String valueHandlersString =
                valueHandlers.entrySet()
                        .stream()
                        .map(e -> e.getValue().toString())
                        .collect(Collectors.joining(", "));

        return "ValueHandlerProvider{" +
                "valueHandlers=[" + valueHandlersString + "]" +
                '}';
    }
}
