package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.utils.TimeStampUtils;

import java.time.LocalDateTime;

/**
 * LocalDateTime 值转换器,将日期时间转换为 PostgreSQL 时间戳的微秒值。
 */
public class LocalDateTimeConverter implements IValueConverter<LocalDateTime, Long> {

    /**
     * 按目标类型约定转换当前输入值。
     */
    @Override
    public Long convert(final LocalDateTime dateTime) {
        return TimeStampUtils.convertToPostgresTimeStamp(dateTime);
    }
}
