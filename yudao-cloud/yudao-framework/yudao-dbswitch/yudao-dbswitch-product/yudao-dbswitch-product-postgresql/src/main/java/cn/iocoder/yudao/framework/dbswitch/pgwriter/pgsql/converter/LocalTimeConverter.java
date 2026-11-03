package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter;

import java.time.LocalTime;

/**
 * LocalTime 值转换器,将时间转换为当日零点起的微秒数。
 */
public class LocalTimeConverter implements IValueConverter<LocalTime, Long> {

    /**
     * 按目标类型约定转换当前输入值。
     */
    @Override
    public Long convert(final LocalTime time) {
        return time.toNanoOfDay() / 1000L;
    }

}
