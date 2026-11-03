package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;

/**
 * 数据库表列的元信息
 * <p>
 * 职责：与具体数据库无关的“中间层”字段类型模型。构造时把 {@link ColumnDescription}
 * 中的 JDBC 类型（java.sql.Types）归一化为本类定义的 TYPE_XXX 通用类型
 * （字符串/整数/小数/大数/布尔/日期/时间/时间戳/二进制等），并推导出统一的长度与精度
 * （详见 {@link #create(ColumnDescription)} 的映射规则）。
 * <p>
 * 协作关系：各数据库方言实现类（DatabaseMysqlImpl、DatabaseOracleImpl 等）在生成建表
 * 字段定义（getFieldDefinition）时，依据本类的通用类型与长度精度拼出目标库的字段类型，
 * 从而实现跨库的类型映射；类型常量与判断逻辑参考了 Kettle 的 ValueMetaData 设计
 *
 * @author jrl
 */
public class ColumnMetaData {

    /**
     * 表示尚未设置类型的值类型
     * <p>（未设置类型）
     */
    public static final int TYPE_NONE = 0;

    /**
     * 表示双精度浮点数的值类型。
     * <p>（双精度浮点数/普通小数）
     */
    public static final int TYPE_NUMBER = 1;

    /**
     * 表示文本 String 的值类型。
     * <p>（文本字符串）
     */
    public static final int TYPE_STRING = 2;

    /**
     * 表示 Date 的值类型。
     * <p>（日期，不含时间）
     */
    public static final int TYPE_DATE = 3;

    /**
     * 表示布尔值的值类型。
     * <p>（布尔值）
     */
    public static final int TYPE_BOOLEAN = 4;

    /**
     * 表示长整数的值类型。
     * <p>（长整型）
     */
    public static final int TYPE_INTEGER = 5;

    /**
     * 表示包含任意精度
     * 浮点数的值类型。
     * <p>（任意精度大数，对应 BigDecimal，用于超出 long/double 表示范围的数值）
     */
    public static final int TYPE_BIGNUMBER = 6;

    /**
     * 表示 Object 的值类型。
     * <p>（可序列化对象）
     */
    public static final int TYPE_SERIALIZABLE = 7;

    /**
     * 表示二进制数据的值类型：BLOB、CLOB 等。
     * <p>（二进制数据，如 BLOB、BINARY 等）
     */
    public static final int TYPE_BINARY = 8;

    /**
     * 表示纳秒精度日期时间的值类型
     * <p>（时间戳，日期 + 时间，纳秒精度）
     */
    public static final int TYPE_TIMESTAMP = 9;

    /**
     * 表示时间的值类型
     * <p>（时间，不含日期）
     */
    public static final int TYPE_TIME = 10;

    /**
     * 表示互联网地址的值类型
     * <p>（网络地址类型，如 PostgreSQL 的 inet）
     */
    public static final int TYPE_INET = 11;

    /**
     * 类型代码常量 typeCodes。
     * <p>（各 TYPE_XXX 常量对应的可读名称，下标即类型值）
     */
    public static final String[] TYPE_CODES = new String[]{"-", "Number", "String", "Date", "Boolean",
            "Integer",
            "BigNumber", "Serializable", "Binary", "Timestamp", "Time", "Internet Address",};

    //////////////////////////////////////////////////////////////////////

    /**
     * 列名
     * <p>（字段名）
     */
    protected String name;

    /** 长度：字符串为字符数，数值为有效位数（-1 表示未定义/任意长度） */
    protected int length;

    /** 精度：数值的小数位数（-1 表示未定义） */
    protected int precision;

    /** 归一化后的通用类型，取值为本类的 TYPE_XXX 常量 */
    protected int type;

    /** 原始的 JDBC 类型（java.sql.Types 常量） */
    protected int sqlType;

    /** 保留驱动原始类型名称作为验证依据，即使多个原生类型共用同一 JDBC 代码。 */
    private String nativeTypeName;

    /** 字段注释 */
    private String remarks;

    /** 是否允许为 NULL */
    private boolean isNullable;

    /** 原始字段默认表达式；跨产品适配由显式类型/DDL计划决定 */
    private String defaultValue;

    /**
     * 构造方法
     * <p>
     * 构造方法：根据字段描述信息完成 JDBC 类型到通用类型的归一化（见 {@link #create}）
     *
     * @param desc 字段描述信息（来自元数据查询）
     */
    public ColumnMetaData(ColumnDescription desc) {
        this.create(desc);
    }

    /**
     * 返回名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置名称。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 返回长度。
     */
    public int getLength() {
        return length;
    }

    /**
     * 设置长度。
     */
    public void setLength(int length) {
        this.length = length;
    }

    /**
     * 返回精度。
     */
    public int getPrecision() {
        return precision;
    }

    /**
     * 设置精度。
     */
    public void setPrecision(int precision) {
        this.precision = precision;
    }

    /**
     * 返回类型。
     */
    public int getType() {
        return type;
    }

    /**
     * 设置类型。
     */
    public void setType(int type) {
        this.type = type;
    }

    /**
     * 返回备注。
     */
    public String getRemarks() {
        return remarks;
    }

    /**
     * 设置备注。
     */
    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    /**
     * 返回是否可空。
     */
    public boolean isNullable() {
        return isNullable;
    }

    /**
     * 设置是否可空。
     */
    public void setNullable(boolean nullable) {
        isNullable = nullable;
    }

    /**
     * 返回默认值。
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * 设置默认值。
     */
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * 检查值是否为 String。
     *
     * @return 值为 String 时返回 true。
     */
    public boolean isString() {
        return type == TYPE_STRING;
    }

    /**
     * 检查此值是否为 Date
     *
     * @return 值为 Date 时返回 true
     */
    public boolean isDate() {
        return type == TYPE_DATE;
    }

    /**
     * 检查此值是否为 Time
     *
     * @return 值为 Time 时返回 true
     */
    public boolean isTime() {
        return type == TYPE_TIME;
    }

    /**
     * 检查此值是否为 DateTime
     *
     * @return 值为 DateTime 时返回 true
     */
    public boolean isDateTime() {
        return type == TYPE_TIMESTAMP;
    }

    /**
     * 检查值是否为大数
     *
     * @return 值为大数时返回 true
     */
    public boolean isBigNumber() {
        return type == TYPE_BIGNUMBER;
    }

    /**
     * 检查值是否为 Number
     *
     * @return 值为 Number 时返回 true
     */
    public boolean isNumber() {
        return type == TYPE_NUMBER;
    }

    /**
     * 检查此值是否为布尔值
     *
     * @return 此值类型为 boolean 时返回 true。
     */
    public boolean isBoolean() {
        return type == TYPE_BOOLEAN;
    }

    /**
     * 检查此值是否为 Serializable 类型
     *
     * @return 此值类型为 Serializable 时返回 true
     */
    public boolean isSerializableType() {
        return type == TYPE_SERIALIZABLE;
    }

    /**
     * 检查此值是否为 Binary 类型
     *
     * @return 此值类型为 Binary 时返回 true
     */
    public boolean isBinary() {
        return type == TYPE_BINARY;
    }

    /**
     * 检查此值是否为 Integer
     *
     * @return 此值为整数时返回 true
     */
    public boolean isInteger() {
        return type == TYPE_INTEGER;
    }

    /**
     * 检查此值是否为数值类型；Number
     * 或 Integer 类型的值均属于数值
     *
     * @return 值类型为 Number 或 Integer 时返回 true
     */
    public boolean isNumeric() {
        return isInteger() || isNumber() || isBigNumber();
    }

    /**
     * 检查指定类型是否为 Integer 或 Number
     *
     * @param t 要检查的类型
     * @return 类型为 Integer 或 Number 时返回 true
     */
    public static final boolean isNumeric(int t) {
        return t == TYPE_INTEGER || t == TYPE_NUMBER || t == TYPE_BIGNUMBER;
    }

    /**
     * 以文本形式返回值类型："String"、"Number"、"Integer"、"Boolean"、"Date"，
     * ...
     *
     * @return 描述值类型的字符串。
     */
    public String getTypeDesc() {
        return TYPE_CODES[type];
    }

    /**
     * 设置SQL 类型。
     */
    public void setSqlType(int sqlType) {
        this.sqlType = sqlType;
    }

    /**
     * 返回SQL 类型。
     */
    public int getSqlType() {
        return sqlType;
    }

    /**
     * 返回原生类型名称。
     */
    public String getNativeTypeName() {
        return nativeTypeName;
    }

    /**
     * 核心类型归一化逻辑：把 JDBC 类型（java.sql.Types）映射为通用类型（TYPE_XXX），并推导长度与精度
     * <p>
     * 主要映射规则：
     * 1. 调用方已显式指定 valType 时直接采用，不再映射；
     * 2. CHAR/VARCHAR 系映射为字符串并取显示宽度；LONGVARCHAR/CLOB/SQLXML/ROWID 等大文本
     *    映射为字符串且长度取 CLOB_LENGTH 上限；
     * 3. BIGINT：有符号映射为整型；无符号可能溢出 long，映射为大数（BIGNUMBER）；
     *    INTEGER/SMALLINT/TINYINT 映射为整型并给出对应的典型位数；
     * 4. DECIMAL/DOUBLE/FLOAT/REAL/NUMERIC 先映射为小数（NUMBER），再按各库特性修正：
     *    浮点类型精度异常时重置；PostgreSQL 双精度与未定长 NUMERIC 归为大数；
     *    MySQL 驱动返回的异常精度（精度大于长度）重置；Hive 的 DOUBLE/FLOAT 改用固定精度；
     *    定点数无小数位且长度不超 18 用整型承载、超出升级为大数；Oracle 的 NUMBER(38,0) 视为整型，
     *    未定义长度精度的 NUMBER 归为大数；
     * 5. TIMESTAMP/DATE/TIME/BOOLEAN(BIT)/BINARY 系列映射为对应通用类型；未识别类型保持 TYPE_NONE；
     * 6. 默认表达式原样保留，不使用通用正则转换产品语义
     *
     * @param desc 字段描述信息
     */
    private void create(ColumnDescription desc) {
        this.nativeTypeName = desc.getFieldTypeName();
        int length = -1;
        int precision = -1;
        int valtype;
        int type = desc.getFieldType();
        boolean signed = desc.isSigned();

        // 调用方已显式指定通用类型时直接使用，否则按 JDBC 类型映射
        Integer valType = desc.getValType();
        if (valType == null) {
            switch (type) {
                case java.sql.Types.CHAR:
                case java.sql.Types.NCHAR:
                case java.sql.Types.VARCHAR:
                case java.sql.Types.NVARCHAR:
                    valtype = ColumnMetaData.TYPE_STRING;
                    length = desc.getDisplaySize();
                    break;

                case java.sql.Types.LONGVARCHAR:
                case java.sql.Types.LONGNVARCHAR:
                case java.sql.Types.CLOB:
                case java.sql.Types.NCLOB:
                case java.sql.Types.SQLXML:
                case java.sql.Types.ROWID:
                    valtype = ColumnMetaData.TYPE_STRING;
                    length = AbstractDatabase.CLOB_LENGTH;
                    break;

                case java.sql.Types.BIGINT:
                    // 校验无符号 BIGINT 是否溢出！
                    //
                    if (signed) {
                        valtype = ColumnMetaData.TYPE_INTEGER;
                        precision = 0; // 最大值 9.223.372.036.854.775.807
                        length = 15;
                    } else {
                        valtype = ColumnMetaData.TYPE_BIGNUMBER;
                        precision = 0; // 最大值 18.446.744.073.709.551.615
                        length = 16;
                    }
                    break;

                case java.sql.Types.INTEGER:
                    valtype = ColumnMetaData.TYPE_INTEGER;
                    precision = 0; // 最大值 2.147.483.647
                    length = 9;
                    break;

                case java.sql.Types.SMALLINT:
                    valtype = ColumnMetaData.TYPE_INTEGER;
                    precision = 0; // 最大值 32.767
                    length = 4;
                    break;

                case java.sql.Types.TINYINT:
                    valtype = ColumnMetaData.TYPE_INTEGER;
                    precision = 0; // 最大值 127
                    length = 2;
                    break;

                case java.sql.Types.DECIMAL:
                case java.sql.Types.DOUBLE:
                case java.sql.Types.FLOAT:
                case java.sql.Types.REAL:
                case java.sql.Types.NUMERIC:
                    valtype = ColumnMetaData.TYPE_NUMBER;
                    length = desc.getPrecisionSize();
                    precision = desc.getScaleSize();
                    // 长度/精度达到 126 视为驱动返回的无效值，重置为未定义（-1）
                    if (length >= 126 && type != java.sql.Types.DECIMAL && type != java.sql.Types.NUMERIC) {
                        length = -1;
                    }
                    if (precision >= 126 && type != java.sql.Types.DECIMAL && type != java.sql.Types.NUMERIC) {
                        precision = -1;
                    }

                    // 浮点类型（DOUBLE/FLOAT/REAL）的长度精度按各数据库特性修正
                    if (type == java.sql.Types.DOUBLE || type == java.sql.Types.FLOAT
                            || type == java.sql.Types.REAL) {
                        if (precision == 0) {
                            if (!signed) {
                                precision = -1; // 以下类型报告的精度明显不正确：
                                // Double/Float/Real
                            } else {
                                length = 18;
                                precision = 4;
                            }
                        }

                        // 处理 PostgreSQL 的双精度类型时，
                        if (desc.getDbType().isLikePostgres() && type == java.sql.Types.DOUBLE
                                && precision >= 16
                                && length >= 16) {
                            precision = -1;
                            length = -1;
                        }

                        // MySQL：最高精度为双精度浮点数（double）
                        // 返回的 (12,31) 不正确
                        if (desc.getDbType().isLikeMysql()) {
                            if (precision >= length) {
                                precision = -1;
                                length = -1;
                            }
                        }

                        // 处理 Hive 的 double/float 精度类型时
                        if (desc.getDbType().isLikeHive()) {
                            if (type == java.sql.Types.DOUBLE
                                    && precision >= 15
                                    && length >= 15) {
                                precision = 6;
                                length = 25;
                            }

                            if (type == java.sql.Types.FLOAT
                                    && precision >= 7
                                    && length >= 7) {
                                precision = 6;
                                length = 25;
                            }
                        }

                        // 当长度或精度需要 BIGNUMBER 时
                        //if (length > 15 || precision > 15) {
                        //	valtype = ColumnMetaData.TYPE_BIGNUMBER;
                        //}
                    } else {
                        // 定点数（DECIMAL/NUMERIC）：无小数位且长度不超 18 位可用整型承载，超出则升级为大数
                        if (precision == 0) {
                            if (length <= 18 && length > 0) { // Oracle 等产品也受影响
                                // 此处同样受影响。
                                valtype = ColumnMetaData.TYPE_INTEGER; // Long 最多可容纳 18 位
                                // 有效数字
                            } else if (length > 18) {
                                valtype = ColumnMetaData.TYPE_BIGNUMBER;
                            }
                        } else { // 已知精度：保留 NUMBER 还是改为 BIGNUMBER？
                            if (length > 15 || precision > 15) {
                                valtype = ColumnMetaData.TYPE_BIGNUMBER;
                            }
                        }
                    }

                    if (desc.getDbType().isLikePostgres()) {
                        // 大小未定义 => 任意精度
                        // PostgreSQL 未定义长度的 NUMERIC 表示任意精度，归为大数
                        if (type == java.sql.Types.NUMERIC && length == 0 && precision == 0) {
                            valtype = ColumnMetaData.TYPE_BIGNUMBER;
                            length = -1;
                            precision = -1;
                        }
                    }

                    if (desc.getDbType().isLikeOracle()) {
                        // Oracle：NUMBER(38,0) 视为整型；长度精度都未定义的 NUMBER 归为大数
                        if (precision == 0 && length == 38) {
                            valtype = ColumnMetaData.TYPE_INTEGER;
                        }
                        if (precision <= 0 && length <= 0) {
                            // 大小未定义：BIGNUMBER，
                            // Oracle 的精度可能为 38，对
                            // Number 类型来说过大
                            valtype = ColumnMetaData.TYPE_BIGNUMBER;
                            length = -1;
                            precision = -1;
                        }
                    }

                    break;

                case java.sql.Types.TIMESTAMP:
                case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
                    valtype = ColumnMetaData.TYPE_TIMESTAMP;
                    length = desc.getScaleSize();
                    break;

                case java.sql.Types.DATE:
                    valtype = ColumnMetaData.TYPE_DATE;
                    break;

                case java.sql.Types.TIME:
                case java.sql.Types.TIME_WITH_TIMEZONE:
                    valtype = ColumnMetaData.TYPE_TIME;
                    break;

                case java.sql.Types.BOOLEAN:
                case java.sql.Types.BIT:
                    valtype = ColumnMetaData.TYPE_BOOLEAN;
                    break;

                case java.sql.Types.BINARY:
                case java.sql.Types.BLOB:
                case java.sql.Types.VARBINARY:
                case java.sql.Types.LONGVARBINARY:
                    valtype = ColumnMetaData.TYPE_BINARY;
                    precision = -1;
                    break;

                default:
                    valtype = ColumnMetaData.TYPE_NONE;
                    precision = desc.getScaleSize();
                    break;
            }
        } else {
            length = desc.getLength() == null ? -1 : desc.getLength();
            precision = desc.getPrecision() == null ? 0 : desc.getPrecision();
            valtype = valType;
        }

        this.name = desc.getFieldName();
        this.length = length;
        this.precision = precision;
        this.sqlType = type;
        this.type = valtype;
        this.remarks = desc.getRemarks();
        this.isNullable = desc.isNullable();
        // 表达式属于其源方言；不要删除类型转换、字面量或序列语义。
        this.defaultValue = desc.getDefaultValue();
    }

}
