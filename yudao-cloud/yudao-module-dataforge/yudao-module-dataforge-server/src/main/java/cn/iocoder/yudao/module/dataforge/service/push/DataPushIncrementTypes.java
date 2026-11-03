package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;

import java.sql.Types;
import java.util.Locale;

/**
 * 增量字段仅允许数值或时间类型，避免按字符串水位造成漏数 / 乱序
 *
 * @author Li
 */
public final class DataPushIncrementTypes {

    /**
     * 限制数据推送增量类型工具的实例化，统一通过静态能力使用。
     */
    private DataPushIncrementTypes() {
    }

    /**
     * 判断支持范围条件是否成立。
     *
     * @param jdbcType JDBC 类型
     * @param typeName 类型名称
     * @return 处理是否成功
     */
    public static boolean supported(Integer jdbcType, String typeName) {
        if (jdbcType != null && jdbcType != Types.OTHER && jdbcType != Types.NULL) {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                     Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE,
                     Types.DATE, Types.TIME, Types.TIMESTAMP,
                     Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> true;
                default -> false;
            };
        }
        return supportedByTypeName(typeName);
    }

    /**
     * 判断支持范围按类型名称条件是否成立。
     *
     * @param typeName 类型名称
     * @return 处理是否成功
     */
    static boolean supportedByTypeName(String typeName) {
        if (StrUtil.isBlank(typeName)) {
            return false;
        }
        String normalized = typeName.toUpperCase(Locale.ROOT);
        if (normalized.contains("INTERVAL") || normalized.contains("CHAR") || normalized.contains("TEXT")
                || normalized.contains("CLOB") || normalized.contains("BLOB") || normalized.contains("JSON")
                || normalized.contains("XML") || normalized.contains("UUID") || normalized.contains("BOOL")
                || normalized.contains("BINARY") || normalized.contains("IMAGE") || normalized.contains("ENUM")) {
            return false;
        }
        return normalized.contains("INT") || normalized.contains("NUM") || normalized.contains("DEC")
                || normalized.contains("DATE") || normalized.contains("TIME") || normalized.contains("SERIAL")
                || normalized.contains("FLOAT") || normalized.contains("DOUBLE") || normalized.contains("REAL")
                || normalized.contains("MONEY") || normalized.contains("YEAR") || normalized.contains("EPOCH");
    }

}
