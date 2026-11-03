package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.utils.TimeStampUtils;

import java.time.LocalDate;

/**
 * LocalDate 值转换器,将日期转换为 PostgreSQL 纪元(2000-01-01)起的天数。
 */
public class LocalDateConverter implements IValueConverter<LocalDate, Integer> {

    /**
     * 按目标类型约定转换当前输入值。
     */
    @Override
    public Integer convert(final LocalDate date) {
        return TimeStampUtils.toPgDays(date);
    }

}
