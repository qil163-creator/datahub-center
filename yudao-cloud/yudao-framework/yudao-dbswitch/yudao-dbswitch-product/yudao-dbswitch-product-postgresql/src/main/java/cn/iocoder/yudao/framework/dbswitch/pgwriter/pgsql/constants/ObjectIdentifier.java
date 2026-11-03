package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants;

import java.util.HashMap;
import java.util.Map;

// https://github.com/postgres/postgres/blob/master/src/include/catalog/pg_type.h

/**
 * PostgreSQL 类型 OID 常量表,维护各数据类型与系统目录 pg_type 中 OID 的映射关系。
 */
public class ObjectIdentifier {

    // region OID 1 - 99

    // 布尔值，'true' 或 'false'
    public static int Boolean = 16;

    // 变长字符串，二进制值经过转义
    public static int Bytea = 17;

    // 单个字符
    public static int Char = 18;

    // 用于保存系统标识符的 63 字节类型
    public static int Name = 19;

    // 约 18 位整数，占 8 字节
    public static int Int8 = 20;

    // 约 -3.2 万至 3.2 万的整数，占 2 字节
    public static int Int2 = 21;

    // 约 -20 亿至 20 亿的整数，占 4 字节
    public static int Int4 = 23;

    // 变长字符串，不指定长度限制
    public static int Text = 25;

    // 对象标识符（OID），最大约 40 亿
    public static int Oid = 26;

    // （块号，偏移量），元组的物理位置
    public static int Tid = 27;

    // 事务标识符
    public static int Xid = 28;

    // 命令标识符类型，表示事务内的顺序
    public static int Cid = 29;

    // endregion

    // region OID 100 - 199

    // 内置 JSONB；OID 114 属于 json，其二进制协议不含 JSONB 版本字节。
    public static int Jsonb = 3802;

    // XML 内容
    public static int Xml = 115;

    // endregion

    // region OID 600 - 699

    // 几何点 '(x, y)'
    public static int Point = 600;

    // 几何线段 '(pt1, pt2)'
    public static int LineSegment = 601;

    // 几何路径 '(pt1,...)'
    public static int Path = 602;

    // 几何矩形 '(lower left, upper right)'（左下点、右上点）
    public static int Box = 603;

    // 几何多边形 '(pt1, ...)'
    public static int Polygon = 604;

    // 几何直线
    public static int Line = 628;

    // endregion

    // region OID 700 - 799

    // 单精度浮点数，占 4 字节
    public static int SinglePrecision = 700;

    // 双精度浮点数，占 8 字节
    public static int DoublePrecision = 701;

    // 有限范围的绝对日期时间（Unix 系统时间）
    public static int AbsTime = 702;

    // 有限范围的相对时间间隔（Unix 时间差）
    public static int RelTime = 703;

    // （abstime，abstime），时间间隔
    public static int TInterval = 704;

    // 未知类型
    public static int Unknown = 705;

    // 几何圆 '(center, radius)'（圆心、半径）
    public static int Circle = 705;

    // 货币金额，格式为 $d,ddd.cc
    public static int Cash = 790;

    // 货币类型
    public static int Money = 791;

    // endregion

    // region OID 800 - 899

    // XX:XX:XX:XX:XX:XX，MAC 地址
    public static int MacAddress = 829;

    // IP 地址或掩码，表示主机地址，网络掩码可选
    public static int Inet = 869;

    // 网络 IP 地址或掩码，表示网络地址
    public static int Cidr = 650;

    // XX:XX:XX:XX:XX:XX:XX:XX，MAC 地址
    public static int MacAddress8 = 774;

    // endregion

    // region OIDS 1000 - 1099

    // char(length)，以空格补齐的字符串，固定存储长度
    public static int CharLength = 1042;

    // varchar(length)，不以空格补齐的字符串，可变存储长度
    public static int VarCharLength = 1043;

    // 日期
    public static int Date = 1082;

    // 一天内的时间
    public static int Time = 1082;

    // endregion

    // region OIDS 1100 - 1199

    // 日期时间
    public static int Timestamp = 1114;

    // 带时区的日期时间
    public static int TimestampTz = 1184;

    // 时间间隔
    public static int Interval = 1186;

    // endregion

    // region OIDS 1200 - 1299

    // 带时区的一天内时间
    public static int TimeTz = 1266;

    // endregion

    // region OIDS 1500 - 1599

    // 定长位串
    public static int Bit = 1560;

    // 变长位串
    public static int VarBit = 1562;

    // endregion

    // region OIDS 1700 - 1799

    public static int Numeric = 1700;

    // endregion

    // region UUID 类型

    public static int Uuid = 2950;

    // endregion

    // region 伪类型

    public static int Record = 2249;

    // endregion

    private static Map<DataType, Integer> mapping = buildLookupTable();

    /**
     * 构建 COPY 数据类型到 PostgreSQL 类型对象编号的映射。
     */
    private static Map<DataType, Integer> buildLookupTable() {

        final Map<DataType, Integer> mapping = new HashMap<>();

        mapping.put(DataType.Boolean, Boolean);
        mapping.put(DataType.Bytea, Bytea);
        mapping.put(DataType.Char, Char);
        mapping.put(DataType.Name, Name);
        mapping.put(DataType.Int8, Int8);
        mapping.put(DataType.Int2, Int2);
        mapping.put(DataType.Int4, Int4);
        mapping.put(DataType.Text, Text);
        mapping.put(DataType.Oid, Oid);
        mapping.put(DataType.Tid, Tid);
        mapping.put(DataType.Xid, Xid);
        mapping.put(DataType.Cid, Cid);
        mapping.put(DataType.Jsonb, Jsonb);
        mapping.put(DataType.Xml, Xml);
        mapping.put(DataType.Point, Point);
        mapping.put(DataType.LineSegment, LineSegment);
        mapping.put(DataType.Path, Path);
        mapping.put(DataType.Box, Box);
        mapping.put(DataType.Polygon, Polygon);
        mapping.put(DataType.Line, Line);
        mapping.put(DataType.SinglePrecision, SinglePrecision);
        mapping.put(DataType.DoublePrecision, DoublePrecision);
        mapping.put(DataType.AbsTime, AbsTime);
        mapping.put(DataType.RelTime, RelTime);
        mapping.put(DataType.TInterval, TInterval);
        mapping.put(DataType.Unknown, Unknown);
        mapping.put(DataType.Circle, Circle);
        mapping.put(DataType.Cash, Cash);
        mapping.put(DataType.Money, Money);
        mapping.put(DataType.MacAddress, MacAddress);
        mapping.put(DataType.Inet4, Inet);
        mapping.put(DataType.Inet6, Inet);
        mapping.put(DataType.Cidr, Cidr);
        mapping.put(DataType.MacAddress8, MacAddress8);
        mapping.put(DataType.CharLength, CharLength);
        mapping.put(DataType.VarChar, VarCharLength);
        mapping.put(DataType.Date, Date);
        mapping.put(DataType.Time, Time);
        mapping.put(DataType.Timestamp, Timestamp);
        mapping.put(DataType.TimestampTz, TimestampTz);
        mapping.put(DataType.Interval, Interval);
        mapping.put(DataType.TimeTz, TimeTz);
        mapping.put(DataType.Bit, Bit);
        mapping.put(DataType.VarBit, VarBit);
        mapping.put(DataType.Numeric, Numeric);
        mapping.put(DataType.Uuid, Uuid);
        mapping.put(DataType.Record, Record);

        return mapping;
    }

    /**
     * 映射来源。
     */
    public static int mapFrom(DataType type) {
        if (mapping.containsKey(type)) {
            return mapping.get(type);
        }
        return Unknown;
    }
}
