package cn.iocoder.yudao.framework.dbswitch.pgwriter.mapping;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.function.ToBooleanFunction;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.function.ToFloatFunction;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.model.ColumnDefinition;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.model.TableDefinition;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.PgBinaryWriter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.DataType;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.ObjectIdentifier;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.CollectionValueHandler;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.IValueHandler;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.IValueHandlerProvider;
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
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * 实体到 PostgreSQL 表的列映射抽象基类,注册各 Java 类型字段的写入函数并生成 COPY 命令,供批量写入时按列序列化实体。
 */
public abstract class AbstractMapping<TEntity> {

    protected boolean usePostgresQuoting;

    protected final IValueHandlerProvider provider;

    protected final TableDefinition table;

    protected final List<ColumnDefinition<TEntity>> columns;

    /**
     * 使用给定参数初始化 {@code AbstractMapping}。
     */
    protected AbstractMapping(String schemaName, String tableName) {
        this(new ValueHandlerProvider(), schemaName, tableName, false);
    }

    /**
     * 使用给定参数初始化 {@code AbstractMapping}。
     */
    protected AbstractMapping(String schemaName, String tableName, boolean usePostgresQuoting) {
        this(new ValueHandlerProvider(), schemaName, tableName, usePostgresQuoting);
    }

    /**
     * 初始化 {@code AbstractMapping}。
     */
    protected AbstractMapping(IValueHandlerProvider provider, String schemaName, String tableName,
            boolean usePostgresQuoting) {
        this.provider = provider;
        this.table = new TableDefinition(schemaName, tableName);
        this.usePostgresQuoting = usePostgresQuoting;
        this.columns = new ArrayList<>();
    }

    /**
     * 设置使用PostgreSQL引用规则。
     */
    protected void usePostgresQuoting(boolean enabled) {
        this.usePostgresQuoting = enabled;
    }

    /**
     * 映射集合。
     */
    protected <TElementType, TCollectionType extends Collection<TElementType>> void mapCollection(
            String columnName, DataType dataType, Function<TEntity, TCollectionType> propertyGetter) {

        final IValueHandler<TElementType> valueHandler = provider.resolve(dataType);
        final int valueOID = ObjectIdentifier.mapFrom(dataType);

        map(columnName, new CollectionValueHandler<>(valueOID, valueHandler), propertyGetter);
    }

    /**
     * 按当前映射定义转换字段、类型或数据结构。
     */
    protected <TProperty> void map(String columnName, DataType dataType,
            Function<TEntity, TProperty> propertyGetter) {
        final IValueHandler<TProperty> valueHandler = provider.resolve(dataType);

        map(columnName, valueHandler, propertyGetter);
    }

    /**
     * 按当前映射定义转换字段、类型或数据结构。
     */
    protected <TProperty> void map(String columnName, IValueHandler<TProperty> valueHandler,
            Function<TEntity, TProperty> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.write(valueHandler, propertyGetter.apply(entity));
        });
    }

    // region 数值类型

    /**
     * 映射布尔。
     */
    protected void mapBoolean(String columnName, Function<TEntity, Boolean> propertyGetter) {
        map(columnName, DataType.Boolean, propertyGetter);
    }

    /**
     * 映射布尔。
     */
    protected void mapBoolean(String columnName, ToBooleanFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeBoolean(propertyGetter.applyAsBoolean(entity));
        });
    }

    /**
     * 为指定列注册字节类型的 COPY 值映射。
     */
    protected void mapByte(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.Char, propertyGetter);
    }

    /**
     * 为指定列注册字节类型的 COPY 值映射。
     */
    protected void mapByte(String columnName, ToIntFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeByte(propertyGetter.applyAsInt(entity));
        });
    }

    /**
     * 映射短整数。
     */
    protected void mapShort(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.Int2, propertyGetter);
    }

    /**
     * 映射短整数。
     */
    protected void mapShort(String columnName, ToIntFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeShort(propertyGetter.applyAsInt(entity));
        });
    }

    /**
     * 映射整数。
     */
    protected void mapInteger(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.Int4, propertyGetter);
    }

    /**
     * 映射整数。
     */
    protected void mapInteger(String columnName, ToIntFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeInt(propertyGetter.applyAsInt(entity));
        });
    }

    /**
     * 映射数值。
     */
    protected void mapNumeric(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.Numeric, propertyGetter);
    }

    /**
     * 映射长整数。
     */
    protected void mapLong(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.Int8, propertyGetter);
    }

    /**
     * 映射长整数。
     */
    protected void mapLong(String columnName, ToLongFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeLong(propertyGetter.applyAsLong(entity));
        });
    }

    /**
     * 映射浮点数。
     */
    protected void mapFloat(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.SinglePrecision, propertyGetter);
    }

    /**
     * 映射浮点数。
     */
    protected void mapFloat(String columnName, ToFloatFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeFloat(propertyGetter.applyAsFloat(entity));
        });
    }

    /**
     * 映射双精度浮点数。
     */
    protected void mapDouble(String columnName, Function<TEntity, Number> propertyGetter) {
        map(columnName, DataType.DoublePrecision, propertyGetter);
    }

    /**
     * 映射双精度浮点数。
     */
    protected void mapDouble(String columnName, ToDoubleFunction<TEntity> propertyGetter) {
        addColumn(columnName, (binaryWriter, entity) -> {
            binaryWriter.writeDouble(propertyGetter.applyAsDouble(entity));
        });
    }

    // endregion

    // region 网络类型

    /**
     * 为指定列注册IPv4 地址的 COPY 值映射。
     */
    protected void mapInet4Addr(String columnName, Function<TEntity, Inet4Address> propertyGetter) {
        map(columnName, DataType.Inet4, propertyGetter);
    }

    /**
     * 为指定列注册IPv6 地址的 COPY 值映射。
     */
    protected void mapInet6Addr(String columnName, Function<TEntity, Inet6Address> propertyGetter) {
        map(columnName, DataType.Inet6, propertyGetter);
    }

    /**
     * 为指定列注册MAC 地址的 COPY 值映射。
     */
    protected void mapMacAddress(String columnName, Function<TEntity, MacAddress> propertyGetter) {
        map(columnName, DataType.MacAddress, propertyGetter);
    }

    // endregion

    // region 时间类型

    /**
     * 映射日期。
     */
    protected void mapDate(String columnName, Function<TEntity, LocalDate> propertyGetter) {
        map(columnName, DataType.Date, propertyGetter);
    }

    /**
     * 映射时间。
     */
    protected void mapTime(String columnName, Function<TEntity, LocalTime> propertyGetter) {
        map(columnName, DataType.Time, propertyGetter);
    }

    /**
     * 映射时间标记。
     */
    protected void mapTimeStamp(String columnName, Function<TEntity, LocalDateTime> propertyGetter) {
        map(columnName, DataType.Timestamp, propertyGetter);
    }

    /**
     * 映射时间标记时区。
     */
    protected void mapTimeStampTz(String columnName,
            Function<TEntity, ZonedDateTime> propertyGetter) {
        map(columnName, DataType.TimestampTz, propertyGetter);
    }

    // endregion

    // region 文本类型

    /**
     * 映射文本。
     */
    protected void mapText(String columnName, Function<TEntity, String> propertyGetter) {
        map(columnName, DataType.Text, propertyGetter);
    }

    /**
     * 映射变量字符。
     */
    protected void mapVarChar(String columnName, Function<TEntity, String> propertyGetter) {
        map(columnName, DataType.Text, propertyGetter);
    }

    // engregion

    // region UUID 类型

    /**
     * 映射UUID。
     */
    protected void mapUUID(String columnName, Function<TEntity, UUID> propertyGetter) {
        map(columnName, DataType.Uuid, propertyGetter);
    }

    // endregion

    // region JSON 类型

    /**
     * 映射JSONB。
     */
    protected void mapJsonb(String columnName, Function<TEntity, String> propertyGetter) {
        map(columnName, DataType.Jsonb, propertyGetter);
    }

    // endregion

    // region hstore 类型

    /**
     * 映射键值映射。
     */
    protected void mapHstore(String columnName,
            Function<TEntity, Map<String, String>> propertyGetter) {
        map(columnName, DataType.Hstore, propertyGetter);
    }

    // endregion

    // region 几何类型

    /**
     * 映射点。
     */
    protected void mapPoint(String columnName, Function<TEntity, Point> propertyGetter) {
        map(columnName, DataType.Point, propertyGetter);
    }

    /**
     * 映射矩形。
     */
    protected void mapBox(String columnName, Function<TEntity, Box> propertyGetter) {
        map(columnName, DataType.Box, propertyGetter);
    }

    /**
     * 映射路径。
     */
    protected void mapPath(String columnName, Function<TEntity, Path> propertyGetter) {
        map(columnName, DataType.Path, propertyGetter);
    }

    /**
     * 映射多边形。
     */
    protected void mapPolygon(String columnName, Function<TEntity, Polygon> propertyGetter) {
        map(columnName, DataType.Polygon, propertyGetter);
    }

    /**
     * 为指定列注册几何直线类型的 COPY 值映射。
     */
    protected void mapLine(String columnName, Function<TEntity, Line> propertyGetter) {
        map(columnName, DataType.Line, propertyGetter);
    }

    /**
     * 为指定列注册几何线段的 COPY 值映射。
     */
    protected void mapLineSegment(String columnName, Function<TEntity, LineSegment> propertyGetter) {
        map(columnName, DataType.LineSegment, propertyGetter);
    }

    /**
     * 映射圆。
     */
    protected void mapCircle(String columnName, Function<TEntity, Circle> propertyGetter) {
        map(columnName, DataType.Circle, propertyGetter);
    }

    // endregion

    // region 数组类型

    /**
     * 映射布尔数组。
     */
    protected void mapBooleanArray(String columnName,
            Function<TEntity, Collection<Boolean>> propertyGetter) {
        mapCollection(columnName, DataType.Boolean, propertyGetter);
    }

    /**
     * 映射字节数组。
     */
    protected void mapByteArray(String columnName, Function<TEntity, byte[]> propertyGetter) {
        map(columnName, DataType.Bytea, propertyGetter);
    }

    /**
     * 映射短整数数组。
     */
    protected <T extends Number> void mapShortArray(String columnName,
            Function<TEntity, Collection<T>> propertyGetter) {
        mapCollection(columnName, DataType.Int2, propertyGetter);
    }

    /**
     * 映射整数数组。
     */
    protected <T extends Number> void mapIntegerArray(String columnName,
            Function<TEntity, Collection<T>> propertyGetter) {
        mapCollection(columnName, DataType.Int4, propertyGetter);
    }

    /**
     * 映射长整数数组。
     */
    protected <T extends Number> void mapLongArray(String columnName,
            Function<TEntity, Collection<T>> propertyGetter) {
        mapCollection(columnName, DataType.Int8, propertyGetter);
    }

    /**
     * 映射文本数组。
     */
    protected void mapTextArray(String columnName,
            Function<TEntity, Collection<String>> propertyGetter) {
        mapCollection(columnName, DataType.Text, propertyGetter);
    }

    /**
     * 映射变量字符数组。
     */
    protected void mapVarCharArray(String columnName,
            Function<TEntity, Collection<String>> propertyGetter) {
        mapCollection(columnName, DataType.VarChar, propertyGetter);
    }

    /**
     * 映射浮点数数组。
     */
    protected <T extends Number> void mapFloatArray(String columnName,
            Function<TEntity, Collection<T>> propertyGetter) {
        mapCollection(columnName, DataType.SinglePrecision, propertyGetter);
    }

    /**
     * 映射双精度浮点数数组。
     */
    protected <T extends Number> void mapDoubleArray(String columnName,
            Function<TEntity, Collection<T>> propertyGetter) {
        mapCollection(columnName, DataType.DoublePrecision, propertyGetter);
    }

    /**
     * 映射数值数组。
     */
    protected <T extends Number> void mapNumericArray(String columnName,
            Function<TEntity, Collection<T>> propertyGetter) {
        mapCollection(columnName, DataType.Numeric, propertyGetter);
    }

    /**
     * 映射UUID数组。
     */
    protected void mapUUIDArray(String columnName,
            Function<TEntity, Collection<UUID>> propertyGetter) {
        mapCollection(columnName, DataType.Uuid, propertyGetter);
    }

    /**
     * 为指定列注册IPv4 地址数组的 COPY 值映射。
     */
    protected void mapInet4Array(String columnName,
            Function<TEntity, Collection<Inet4Address>> propertyGetter) {
        mapCollection(columnName, DataType.Inet4, propertyGetter);
    }

    /**
     * 为指定列注册IPv6 地址数组的 COPY 值映射。
     */
    protected void mapInet6Array(String columnName,
            Function<TEntity, Collection<Inet6Address>> propertyGetter) {
        mapCollection(columnName, DataType.Inet6, propertyGetter);
    }

    // endregion

    // region 范围类型

    /**
     * 为指定列注册 PostgreSQL 范围类型的 COPY 值映射。
     */
    protected <TElementType> void mapRange(String columnName, DataType dataType,
            Function<TEntity, Range<TElementType>> propertyGetter) {
        final IValueHandler<TElementType> valueHandler = provider.resolve(dataType);

        map(columnName, new RangeValueHandler<>(valueHandler), propertyGetter);
    }

    /**
     * 映射时间戳范围。
     */
    protected void mapTsRange(String columnName,
            Function<TEntity, Range<LocalDateTime>> propertyGetter) {
        map(columnName, DataType.TsRange, propertyGetter);
    }

    /**
     * 映射时间戳时区范围。
     */
    protected void mapTsTzRange(String columnName,
            Function<TEntity, Range<ZonedDateTime>> propertyGetter) {
        map(columnName, DataType.TsTzRange, propertyGetter);
    }

    /**
     * 为指定列注册32 位整数范围的 COPY 值映射。
     */
    protected void mapInt4Range(String columnName, Function<TEntity, Range<Integer>> propertyGetter) {
        map(columnName, DataType.Int4Range, propertyGetter);
    }

    /**
     * 为指定列注册64 位整数范围的 COPY 值映射。
     */
    protected void mapInt8Range(String columnName, Function<TEntity, Range<Long>> propertyGetter) {
        map(columnName, DataType.Int8Range, propertyGetter);
    }

    /**
     * 映射数量范围。
     */
    protected void mapNumRange(String columnName, Function<TEntity, Range<Number>> propertyGetter) {
        map(columnName, DataType.NumRange, propertyGetter);
    }

    /**
     * 映射日期范围。
     */
    protected void mapDateRange(String columnName,
            Function<TEntity, Range<LocalDate>> propertyGetter) {
        map(columnName, DataType.DateRange, propertyGetter);
    }

    // endregion

    /**
     * 添加列。
     */
    private void addColumn(String columnName, BiConsumer<PgBinaryWriter, TEntity> action) {
        columns.add(new ColumnDefinition<>(columnName, action));
    }

    /**
     * 返回列集合。
     */
    public List<ColumnDefinition<TEntity>> getColumns() {
        return columns;
    }

    /**
     * 返回COPY命令。
     */
    public String getCopyCommand() {
        String commaSeparatedColumns = columns.stream()
                .map(x -> x.getColumnName())
                .map(x -> usePostgresQuoting ? PostgreSqlUtils.quoteIdentifier(x) : x)
                .collect(Collectors.joining(", "));

        return String.format("COPY %1$s(%2$s) FROM STDIN BINARY",
                table.GetFullyQualifiedTableName(usePostgresQuoting),
                commaSeparatedColumns);
    }
}
