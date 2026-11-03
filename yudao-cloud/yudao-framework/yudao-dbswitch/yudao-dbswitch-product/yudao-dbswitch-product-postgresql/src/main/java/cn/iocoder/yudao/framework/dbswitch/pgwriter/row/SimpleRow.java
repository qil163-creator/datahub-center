package cn.iocoder.yudao.framework.dbswitch.pgwriter.row;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.PgBinaryWriter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.DataType;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.ObjectIdentifier;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.CollectionValueHandler;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.IValueHandler;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.RangeValueHandler;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.ValueHandlerProvider;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Box;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Circle;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Line;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.LineSegment;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Path;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Point;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Polygon;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.network.MacAddress;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.range.Range;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PostgreSQL COPY 二进制协议的“单行数据”封装
 * <p>
 * 职责：表示 COPY BINARY 流中的一行。调用方通过 setXxx 系列方法按“列名或列下标”
 * 设置各列的值，本类并不立即写出，而是把“如何写这一列”记录为一个延迟执行的动作
 * （Consumer&lt;PgBinaryWriter&gt;）；当整行设置完毕后由 {@link #writeRow} 按列顺序统一写出，
 * 未设置的列自动写入 NULL。
 * <p>
 * 协作关系：由 SimpleRowWriter 在 startRow 回调中创建并传给调用方
 * （如 PostgresCompatibleCopyWriterImpl），值的二进制编码由 {@link IValueHandler} 各实现完成；
 * setText/setVarChar/setJsonb 等文本类方法会先经 nullCharacterHandler 处理
 * 字符串中的 \0 字符（PostgreSQL 文本不允许包含 \0）。
 * 代码源自开源库 PgBulkInsert（de.bytefish.pgbulkinsert）
 */
public class SimpleRow {

    /** 数据类型 -> 二进制编码处理器 的解析器 */
    private final ValueHandlerProvider provider;

    /** 列名 -> 列下标 的映射（决定 COPY 流中的列顺序） */
    private final Map<String, Integer> lookup;

    /** 列下标 -> 该列的延迟写出动作；未登记的列写 NULL */
    private final Map<Integer, Consumer<PgBinaryWriter>> actions;

    /** 文本值中 \0 空字符的清理策略（PostgreSQL 文本类型不允许 \0） */
    private final Function<String, String> nullCharacterHandler;

    /**
     * 初始化 {@code SimpleRow}。
     */
    public SimpleRow(ValueHandlerProvider provider, Map<String, Integer> lookup,
            Function<String, String> nullCharacterHandler) {
        this.provider = provider;
        this.lookup = lookup;
        this.actions = new HashMap<>();
        this.nullCharacterHandler = nullCharacterHandler;
    }

    /**
     * 按列名设置列值（内部把列名解析为下标后委托给下标重载；列名不存在直接抛异常）
     *
     * @param columnName 列名
     * @param type       该列的 PostgreSQL 数据类型
     * @param value      列值（可为 null）
     */
    public <TTargetType> void setValue(String columnName, DataType type, TTargetType value) {
        final int ordinal = Objects.requireNonNull(lookup.get(columnName), columnName + " not found");

        setValue(ordinal, type, value);
    }

    /**
     * 按列下标设置列值：根据数据类型解析出对应的二进制编码处理器，
     * 并把“写出该值”登记为延迟动作（真正写出发生在 writeRow）
     *
     * @param ordinal 列下标（0 基）
     * @param type    该列的 PostgreSQL 数据类型
     * @param value   列值（可为 null）
     */
    public <TTargetType> void setValue(int ordinal, DataType type, TTargetType value) {
        final IValueHandler<TTargetType> handler = provider.resolve(type);

        actions.put(ordinal, (writer) -> writer.write(handler, value));
    }

    /**
     * 按列名设置列值（直接指定自定义的二进制编码处理器）
     */
    public <TTargetType> void setValue(String columnName, IValueHandler<TTargetType> handler,
            TTargetType value) {
        final int ordinal = Objects.requireNonNull(lookup.get(columnName), columnName + " not found");

        setValue(ordinal, handler, value);
    }

    /**
     * 按列下标设置列值（直接指定自定义的二进制编码处理器）
     */
    public <TTargetType> void setValue(int ordinal, IValueHandler<TTargetType> handler,
            TTargetType value) {
        actions.put(ordinal, (writer) -> writer.write(handler, value));
    }

    /**
     * 按列名设置数组（集合）类型的列值，如 int4[]、text[] 等
     *
     * @param columnName 列名
     * @param type       数组元素的 PostgreSQL 数据类型
     * @param value      元素集合
     */
    public <TElementType, TCollectionType extends Collection<TElementType>> void setCollection(
            String columnName, DataType type, TCollectionType value) {
        final int ordinal = Objects.requireNonNull(lookup.get(columnName), columnName + " not found");

        setCollection(ordinal, type, value);
    }


    /**
     * 按列下标设置数组（集合）类型的列值：
     * 用元素类型的 OID 与元素编码处理器组装 CollectionValueHandler，按 PostgreSQL 数组的二进制格式写出
     */
    public <TElementType, TCollectionType extends Collection<TElementType>> void setCollection(
            int ordinal, DataType type, TCollectionType value) {
        final CollectionValueHandler<TElementType, TCollectionType> handler = new CollectionValueHandler<>(
                ObjectIdentifier.mapFrom(type), provider.resolve(type));

        actions.put(ordinal, (writer) -> writer.write(handler, value));
    }


    /**
     * 把整行数据写入 COPY 二进制流：按列下标顺序依次执行各列登记的写出动作；
     * 未设置过值的列写入 NULL
     *
     * @param writer COPY 二进制协议写出器
     */
    public void writeRow(PgBinaryWriter writer) {
        for (int ordinalIdx = 0; ordinalIdx < lookup.keySet().size(); ordinalIdx++) {

            // 未设置此序号时，按 NULL 处理：
            // 该列没有登记写出动作时，视为 NULL 写出
            final Consumer<PgBinaryWriter> action = actions.get(ordinalIdx);
            if (action == null) {
                writer.writeNull();
            } else {
                action.accept(writer);
            }
        }
    }

    // region 数值类型
    // 以下为各 PostgreSQL 类型的便捷设值方法（均委托给 setValue/setCollection）：
    // 数值、日期时间、网络地址、文本、UUID、JSONB、HSTORE、几何、数组、区间等；
    // 每类都提供“按列名”与“按列下标”两个重载

    /**
     * 设置布尔。
     */
    public void setBoolean(String columnName, Boolean value) {
        setValue(columnName, DataType.Boolean, value);
    }

    /**
     * 设置布尔。
     */
    public void setBoolean(int ordinal, Boolean value) {
        setValue(ordinal, DataType.Boolean, value);
    }

    /**
     * 设置字节。
     */
    public void setByte(String columnName, Byte value) {
        setValue(columnName, DataType.Char, value);
    }

    /**
     * 设置字节。
     */
    public void setByte(int ordinal, Byte value) {
        setValue(ordinal, DataType.Char, value);
    }

    /**
     * 设置短整数。
     */
    public void setShort(String columnName, Short value) {
        setValue(columnName, DataType.Int2, value);
    }

    /**
     * 设置短整数。
     */
    public void setShort(int ordinal, Short value) {
        setValue(ordinal, DataType.Int2, value);
    }

    /**
     * 设置整数。
     */
    public void setInteger(String columnName, Integer value) {
        setValue(columnName, DataType.Int4, value);
    }

    /**
     * 设置整数。
     */
    public void setInteger(int ordinal, Integer value) {
        setValue(ordinal, DataType.Int4, value);
    }

    /**
     * 设置数值。
     */
    public void setNumeric(String columnName, Number value) {
        setValue(columnName, DataType.Numeric, value);
    }

    /**
     * 设置数值。
     */
    public void setNumeric(int ordinal, Number value) {
        setValue(ordinal, DataType.Numeric, value);
    }

    /**
     * 设置长整数。
     */
    public void setLong(String columnName, Long value) {
        setValue(columnName, DataType.Int8, value);
    }

    /**
     * 设置长整数。
     */
    public void setLong(int ordinal, Long value) {
        setValue(ordinal, DataType.Int8, value);
    }

    /**
     * 设置浮点数。
     */
    public void setFloat(String columnName, Float value) {
        setValue(columnName, DataType.SinglePrecision, value);
    }

    /**
     * 设置浮点数。
     */
    public void setFloat(int ordinal, Float value) {
        setValue(ordinal, DataType.SinglePrecision, value);
    }

    /**
     * 设置双精度浮点数。
     */
    public void setDouble(String columnName, Double value) {
        setValue(columnName, DataType.DoublePrecision, value);
    }

    /**
     * 设置双精度浮点数。
     */
    public void setDouble(int ordinal, Double value) {
        setValue(ordinal, DataType.DoublePrecision, value);
    }

    // endregion

    // region 时间类型

    /**
     * 设置日期。
     */
    public void setDate(String columnName, LocalDate value) {
        setValue(columnName, DataType.Date, value);
    }

    /**
     * 设置日期。
     */
    public void setDate(int ordinal, LocalDate value) {
        setValue(ordinal, DataType.Date, value);
    }

    /**
     * 设置时间。
     */
    public void setTime(String columnName, LocalTime value) {
        setValue(columnName, DataType.Time, value);
    }

    /**
     * 设置时间。
     */
    public void setTime(int ordinal, LocalTime value) {
        setValue(ordinal, DataType.Time, value);
    }

    /**
     * 设置时间标记。
     */
    public void setTimeStamp(String columnName, LocalDateTime value) {
        setValue(columnName, DataType.Timestamp, value);
    }

    /**
     * 设置时间标记。
     */
    public void setTimeStamp(int ordinal, LocalDateTime value) {

        setValue(ordinal, DataType.Timestamp, value);
    }

    /**
     * 设置时间标记时区。
     */
    public void setTimeStampTz(String columnName, ZonedDateTime value) {
        setValue(columnName, DataType.TimestampTz, value);
    }

    /**
     * 设置时间标记时区。
     */
    public void setTimeStampTz(int ordinal, ZonedDateTime value) {
        setValue(ordinal, DataType.TimestampTz, value);
    }

    // endregion

    // region 网络类型

    /**
     * 按指定列登记IPv6 地址及其 COPY 编码类型。
     */
    public void setInet6Addr(String columnName, Inet6Address value) {
        setValue(columnName, DataType.Inet6, value);
    }

    /**
     * 按指定列登记IPv6 地址及其 COPY 编码类型。
     */
    public void setInet6Addr(int ordinal, Inet6Address value) {
        setValue(ordinal, DataType.Inet6, value);
    }

    /**
     * 按指定列登记IPv4 地址及其 COPY 编码类型。
     */
    public void setInet4Addr(String columnName, Inet4Address value) {
        setValue(columnName, DataType.Inet4, value);
    }

    /**
     * 按指定列登记IPv4 地址及其 COPY 编码类型。
     */
    public void setInet4Addr(int ordinal, Inet4Address value) {
        setValue(ordinal, DataType.Inet4, value);
    }

    /**
     * 按指定列登记MAC 地址及其 COPY 编码类型。
     */
    public void setMacAddress(String columnName, MacAddress value) {
        setValue(columnName, DataType.MacAddress, value);
    }

    /**
     * 按指定列登记MAC 地址及其 COPY 编码类型。
     */
    public void setMacAddress(int ordinal, MacAddress value) {
        setValue(ordinal, DataType.MacAddress, value);
    }

    // endregion

    // region 文本类型
    // 文本类设值：先经 nullCharacterHandler 清理字符串中的 \0 空字符再写出

    /**
     * 设置文本。
     */
    public void setText(String columnName, String value) {
        setValue(columnName, DataType.Text, nullCharacterHandler.apply(value));
    }

    /**
     * 设置文本。
     */
    public void setText(int ordinal, String value) {
        setValue(ordinal, DataType.Text, nullCharacterHandler.apply(value));
    }

    /**
     * 设置变量字符。
     */
    public void setVarChar(String columnName, String value) {
        setValue(columnName, DataType.Text, nullCharacterHandler.apply(value));
    }

    /**
     * 设置变量字符。
     */
    public void setVarChar(int ordinal, String value) {
        setValue(ordinal, DataType.Text, nullCharacterHandler.apply(value));
    }

    // endregion

    // region UUID 类型

    /**
     * 设置UUID。
     */
    public void setUUID(String columnName, UUID value) {
        setValue(columnName, DataType.Uuid, value);
    }

    /**
     * 设置UUID。
     */
    public void setUUID(int ordinal, UUID value) {
        setValue(ordinal, DataType.Uuid, value);
    }

    // endregion

    // region JSON 类型

    /**
     * 设置JSONB。
     */
    public void setJsonb(String columnName, String value) {
        setValue(columnName, DataType.Jsonb, nullCharacterHandler.apply(value));
    }

    /**
     * 设置JSONB。
     */
    public void setJsonb(int ordinal, String value) {
        setValue(ordinal, DataType.Jsonb, nullCharacterHandler.apply(value));
    }

    // endregion

    // region hstore 类型

    /**
     * 设置键值映射。
     */
    public void setHstore(String columnName, Map<String, String> value) {
        setValue(columnName, DataType.Hstore, value);
    }

    /**
     * 设置键值映射。
     */
    public void setHstore(int ordinal, Map<String, String> value) {
        setValue(ordinal, DataType.Hstore, value);
    }

    // endregion

    // region 几何类型

    /**
     * 设置点。
     */
    public void setPoint(String columnName, Point value) {
        setValue(columnName, DataType.Point, value);
    }

    /**
     * 设置点。
     */
    public void setPoint(int ordinal, Point value) {
        setValue(ordinal, DataType.Point, value);
    }

    /**
     * 设置矩形。
     */
    public void setBox(String columnName, Box value) {
        setValue(columnName, DataType.Box, value);
    }

    /**
     * 设置矩形。
     */
    public void setBox(int ordinal, Box value) {
        setValue(ordinal, DataType.Box, value);
    }

    /**
     * 设置路径。
     */
    public void setPath(String columnName, Path value) {
        setValue(columnName, DataType.Path, value);
    }

    /**
     * 设置路径。
     */
    public void setPath(int ordinal, Path value) {
        setValue(ordinal, DataType.Path, value);
    }

    /**
     * 设置多边形。
     */
    public void setPolygon(String columnName, Polygon value) {
        setValue(columnName, DataType.Polygon, value);
    }

    /**
     * 设置多边形。
     */
    public void setPolygon(int ordinal, Polygon value) {
        setValue(ordinal, DataType.Polygon, value);
    }

    /**
     * 设置直线。
     */
    public void setLine(String columnName, Line value) {
        setValue(columnName, DataType.Line, value);
    }

    /**
     * 设置直线。
     */
    public void setLine(int ordinal, Line value) {
        setValue(ordinal, DataType.Line, value);
    }

    /**
     * 按指定列登记几何线段及其 COPY 编码类型。
     */
    public void setLineSegment(String columnName, LineSegment value) {
        setValue(columnName, DataType.LineSegment, value);
    }

    /**
     * 按指定列登记几何线段及其 COPY 编码类型。
     */
    public void setLineSegment(int ordinal, LineSegment value) {
        setValue(ordinal, DataType.LineSegment, value);
    }

    /**
     * 设置圆。
     */
    public void setCircle(String columnName, Circle value) {
        setValue(columnName, DataType.Circle, value);
    }

    /**
     * 设置圆。
     */
    public void setCircle(int ordinal, Circle value) {
        setValue(ordinal, DataType.Circle, value);
    }

    // endregion

    // region 数组类型

    /**
     * 设置字节数组。
     */
    public void setByteArray(String columnName, byte[] value) {
        setValue(columnName, DataType.Bytea, value);
    }

    /**
     * 设置字节数组。
     */
    public void setByteArray(int ordinal, byte[] value) {
        setValue(ordinal, DataType.Bytea, value);
    }

    /**
     * 设置布尔数组。
     */
    public void setBooleanArray(String columnName, Collection<Boolean> value) {
        setCollection(columnName, DataType.Boolean, value);
    }

    /**
     * 设置布尔数组。
     */
    public void setBooleanArray(int ordinal, Collection<Boolean> value) {
        setCollection(ordinal, DataType.Boolean, value);
    }

    /**
     * 设置短整数数组。
     */
    public <T extends Number> void setShortArray(String columnName, Collection<T> value) {
        setCollection(columnName, DataType.Int2, value);
    }

    /**
     * 设置短整数数组。
     */
    public <T extends Number> void setShortArray(int ordinal, Collection<T> value) {
        setCollection(ordinal, DataType.Int2, value);
    }

    /**
     * 设置整数数组。
     */
    public <T extends Number> void setIntegerArray(String columnName, Collection<T> value) {
        setCollection(columnName, DataType.Int4, value);
    }

    /**
     * 设置整数数组。
     */
    public <T extends Number> void setIntegerArray(int ordinal, Collection<T> value) {
        setCollection(ordinal, DataType.Int4, value);
    }

    /**
     * 设置长整数数组。
     */
    public <T extends Number> void setLongArray(String columnName, Collection<T> value) {
        setCollection(columnName, DataType.Int8, value);
    }

    /**
     * 设置长整数数组。
     */
    public <T extends Number> void setLongArray(int ordinal, Collection<T> value) {
        setCollection(ordinal, DataType.Int8, value);
    }

    /**
     * 设置文本数组。
     */
    public void setTextArray(String columnName, Collection<String> value) {
        Collection<String> values = value.stream()
                .map(x -> nullCharacterHandler.apply(x))
                .collect(Collectors.toList());

        setCollection(columnName, DataType.Text, values);
    }

    /**
     * 设置文本数组。
     */
    public void setTextArray(int ordinal, Collection<String> value) {
        Collection<String> values = value.stream()
                .map(x -> nullCharacterHandler.apply(x))
                .collect(Collectors.toList());

        setCollection(ordinal, DataType.Text, values);
    }

    /**
     * 设置变量字符数组。
     */
    public void setVarCharArray(String columnName, Collection<String> value) {

        Collection<String> values = value.stream()
                .map(x -> nullCharacterHandler.apply(x))
                .collect(Collectors.toList());

        setCollection(columnName, DataType.VarChar, values);
    }

    /**
     * 设置变量字符数组。
     */
    public void setVarCharArray(int ordinal, Collection<String> value) {
        Collection<String> values = value.stream()
                .map(x -> nullCharacterHandler.apply(x))
                .collect(Collectors.toList());

        setCollection(ordinal, DataType.VarChar, values);
    }

    /**
     * 设置浮点数数组。
     */
    public <T extends Number> void setFloatArray(String columnName, Collection<T> value) {
        setCollection(columnName, DataType.SinglePrecision, value);
    }

    /**
     * 设置浮点数数组。
     */
    public <T extends Number> void setFloatArray(int ordinal, Collection<T> value) {
        setCollection(ordinal, DataType.SinglePrecision, value);
    }

    /**
     * 设置双精度浮点数数组。
     */
    public <T extends Number> void setDoubleArray(String columnName, Collection<T> value) {
        setCollection(columnName, DataType.DoublePrecision, value);
    }

    /**
     * 设置双精度浮点数数组。
     */
    public <T extends Number> void setDoubleArray(int ordinal, Collection<T> value) {
        setCollection(ordinal, DataType.DoublePrecision, value);
    }

    /**
     * 设置数值数组。
     */
    public <T extends Number> void setNumericArray(String columnName, Collection<T> value) {
        setCollection(columnName, DataType.Numeric, value);
    }

    /**
     * 设置数值数组。
     */
    public <T extends Number> void setNumericArray(int ordinal, Collection<T> value) {
        setCollection(ordinal, DataType.Numeric, value);
    }

    /**
     * 设置UUID数组。
     */
    public void setUUIDArray(String columnName, Collection<UUID> value) {
        setCollection(columnName, DataType.Uuid, value);
    }

    /**
     * 设置UUID数组。
     */
    public void setUUIDArray(int ordinal, Collection<UUID> value) {
        setCollection(ordinal, DataType.Uuid, value);
    }

    /**
     * 按指定列登记IPv4 地址数组及其 COPY 编码类型。
     */
    public void setInet4Array(String columnName, Collection<Inet4Address> value) {
        setCollection(columnName, DataType.Inet4, value);
    }

    /**
     * 按指定列登记IPv4 地址数组及其 COPY 编码类型。
     */
    public void setInet4Array(int ordinal, Collection<Inet4Address> value) {
        setCollection(ordinal, DataType.Inet4, value);
    }

    /**
     * 按指定列登记IPv6 地址数组及其 COPY 编码类型。
     */
    public void setInet6Array(String columnName, Collection<Inet6Address> value) {
        setCollection(columnName, DataType.Inet6, value);
    }

    /**
     * 按指定列登记IPv6 地址数组及其 COPY 编码类型。
     */
    public void setInet6Array(int ordinal, Collection<Inet6Address> value) {
        setCollection(ordinal, DataType.Inet6, value);
    }

    // endregion

    // region 范围类型
    // 区间类型（tsrange/int4range 等）：用元素类型的处理器组装 RangeValueHandler 写出

    /**
     * 按指定列登记 PostgreSQL 范围值及元素编码器，待整行写出时编码。
     */
    public <TElementType> void setRange(String columnName, DataType dataType,
            Range<TElementType> value) {

        final IValueHandler<TElementType> valueHandler = provider.resolve(dataType);

        setValue(columnName, new RangeValueHandler<>(valueHandler), value);
    }

    /**
     * 按指定列登记 PostgreSQL 范围值及元素编码器，待整行写出时编码。
     */
    public <TElementType> void setRange(int ordinal, DataType dataType, Range<TElementType> value) {

        final IValueHandler<TElementType> valueHandler = provider.resolve(dataType);

        setValue(ordinal, new RangeValueHandler<>(valueHandler), value);
    }

    /**
     * 设置时间戳范围。
     */
    public void setTsRange(String columnName, Range<LocalDateTime> value) {
        setValue(columnName, DataType.TsRange, value);
    }

    /**
     * 设置时间戳范围。
     */
    public void setTsRange(int ordinal, Range<LocalDateTime> value) {
        setValue(ordinal, DataType.TsRange, value);
    }

    /**
     * 设置时间戳时区范围。
     */
    public void setTsTzRange(String columnName, Range<ZonedDateTime> value) {
        setValue(columnName, DataType.TsTzRange, value);
    }

    /**
     * 设置时间戳时区范围。
     */
    public void setTsTzRange(int ordinal, Range<ZonedDateTime> value) {
        setValue(ordinal, DataType.TsTzRange, value);
    }

    /**
     * 按指定列登记32 位整数范围及其 COPY 编码类型。
     */
    public void setInt4Range(String columnName, Range<Integer> value) {
        setValue(columnName, DataType.Int4Range, value);
    }

    /**
     * 按指定列登记32 位整数范围及其 COPY 编码类型。
     */
    public void setInt4Range(int ordinal, Range<Integer> value) {
        setValue(ordinal, DataType.Int4Range, value);
    }

    /**
     * 按指定列登记64 位整数范围及其 COPY 编码类型。
     */
    public void setInt8Range(String columnName, Range<Long> value) {
        setValue(columnName, DataType.Int8Range, value);
    }

    /**
     * 按指定列登记64 位整数范围及其 COPY 编码类型。
     */
    public void setInt8Range(int ordinal, Range<Long> value) {
        setValue(ordinal, DataType.Int8Range, value);
    }

    /**
     * 设置数量范围。
     */
    public void setNumRange(String columnName, Range<Number> value) {
        setValue(columnName, DataType.NumRange, value);
    }

    /**
     * 设置数量范围。
     */
    public void setNumRange(int ordinal, Range<Number> value) {
        setValue(ordinal, DataType.NumRange, value);
    }


    /**
     * 设置日期范围。
     */
    public void setDateRange(String columnName, Range<LocalDate> value) {
        setValue(columnName, DataType.DateRange, value);
    }

    /**
     * 设置日期范围。
     */
    public void setDateRange(int ordinal, Range<LocalDate> value) {
        setValue(ordinal, DataType.DateRange, value);
    }

    // endregion
}
